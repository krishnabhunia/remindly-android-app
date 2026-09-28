package com.krishna.remindly

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * v1.35 — cross-device sync (Firestore). Phase 1b.
 *
 * OPT-IN: runs only when settings.cloudSync is on AND a Firebase user exists. The data model is
 * already merge-ready — every record has an `updatedAt` merge stamp and a `deletedAt` tombstone,
 * and IDs are device-tagged (v1.34) so two devices never collide. This layer mirrors the local
 * stores to /users/{uid}/{items|calls|places}/{id} and /users/{uid}/settings/app, and folds
 * remote changes back through last-writer-wins.
 *
 * Records are stored as JSON blobs ({ "json": <gson>, "updatedAt": <Long> }) rather than Firestore
 * POJOs, because the models have required (non-default) fields Firestore's reflection can't build.
 * Reads go through the same gson + heal* used for local files.
 *
 * Every Firebase call is guarded; failures write Logger.e (Error Logs / Bug email) and degrade to
 * local-only — sync never crashes the app.
 *
 * HONESTY: this cannot be runtime-verified in the build environment (no device, no emulator, and
 * the backend is the user's Firebase project). Automated coverage is the pure merge/strip logic in
 * V135Test; live two-device behaviour is a device checklist.
 */
object SyncAuth {
    /** Firebase uid (shared across a user's devices), or null when signed out. */
    val uid = MutableStateFlow<String?>(null)

    private fun auth() = runCatching { FirebaseAuth.getInstance() }.getOrNull()

    fun current(): String? = runCatching { auth()?.currentUser?.uid }.getOrNull()

    /** Exchange the Google sign-in ID token for a Firebase Auth session so devices share a root. */
    fun linkFromGoogle(context: Context, onDone: (Boolean) -> Unit = {}) {
        val idToken = runCatching { GoogleSignIn.getLastSignedInAccount(context)?.idToken }.getOrNull()
        if (idToken.isNullOrBlank()) { onDone(false); return }
        val a = auth() ?: run { onDone(false); return }
        val cred = GoogleAuthProvider.getCredential(idToken, null)
        runCatching {
            a.signInWithCredential(cred)
                .addOnSuccessListener {
                    uid.value = it.user?.uid
                    onDone(uid.value != null)
                    runCatching { SyncRepo.startIfEnabled(context.applicationContext) }
                    runCatching { ShareStore.startIfSignedIn(context.applicationContext) }   // v1.87 (N17)
                }
                .addOnFailureListener {
                    Logger.e(context, "SYNC", it, "firebase link failed")
                    onDone(false)
                }
        }.onFailure { Logger.e(context, "SYNC", it, "firebase link threw"); onDone(false) }
    }

    fun signOut(context: Context) {
        runCatching { auth()?.signOut() }
        uid.value = null
        SyncRepo.stop()
    }
}

object SyncRepo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val regs = mutableListOf<ListenerRegistration>()
    @Volatile private var running = false
    @Volatile private var observersStarted = false

    // Echo suppression: id -> updatedAt last written by us OR applied from remote, so the local
    // flow-observer doesn't bounce the same record straight back to the cloud.
    private val seenItems = HashMap<Long, Long>()
    private val seenCalls = HashMap<Long, Long>()
    private val seenPlaces = HashMap<Long, Long>()
    private val seenShops = HashMap<Long, Long>()
    @Volatile private var seenSettings = -1L

    private fun db() = FirebaseFirestore.getInstance()
    private fun root(uid: String) = db().collection("users").document(uid)

    private fun stampOf(updatedAt: Long, createdAt: Long) = if (updatedAt > 0) updatedAt else createdAt

    /**
     * v1.70 (Q10): this build's sync schema. Written into every record we push. A writer that
     * omits it (any build older than v1.70, including iOS v1.52) reads back as 0, which is what
     * triggers the field-preserving merge below.
     */
    // v1.86 (N20): 71 — Item.missedAt joined the wire json. Older writers (<71) take the
    // field-preserving merge path below so the log is never clobbered by a stale build.
    const val SYNC_SCHEMA = 71

    /** Uploads serialise nulls so an intentional CLEAR travels as an explicit null, not a gap. */
    private val syncGson: com.google.gson.Gson =
        com.google.gson.GsonBuilder().serializeNulls().create()

    /**
     * v1.70 (Q10): merge a remote record without letting an older writer narrow it.
     *
     * Gson fills any ABSENT key with the Kotlin default, so deserialising a record written by a
     * build that predates a field silently reset that field (this is what wiped per-item
     * alertType when iOS v1.52 echoed items back). When the remote reports a lower schema we
     * therefore overlay ONLY the keys it actually carries on top of the local record; every
     * other field keeps its local value.
     */
    private fun <T : Any> mergeRecord(
        context: Context,
        json: String?,
        remoteVer: Int,
        cls: Class<T>,
        findLocal: (T) -> T?
    ): T? = runCatching {
        val raw = json ?: return@runCatching null
        val probe = gson.fromJson(raw, cls) ?: return@runCatching null
        val local = findLocal(probe)
        if (local == null || remoteVer >= SYNC_SCHEMA) {
            probe
        } else {
            val base = syncGson.toJsonTree(local).asJsonObject
            val remote = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val missing = base.entrySet().map { it.key }.filter { !remote.has(it) }
            remote.entrySet().forEach { (k, v) -> base.add(k, v) }
            if (missing.isNotEmpty()) Logger.e(
                context, "SYNC", null,
                "${cls.simpleName} from older sync schema v$remoteVer \u2014 kept local " +
                    "${missing.size} field(s): ${missing.joinToString(",")}"
            )
            gson.fromJson(base, cls)
        }
    }.getOrElse { Logger.e(context, "SYNC", it, "merge ${cls.simpleName} failed \u2014 record skipped"); null }

    private fun verOf(d: com.google.firebase.firestore.DocumentSnapshot): Int =
        runCatching { d.getLong("schemaVer")?.toInt() ?: 0 }.getOrDefault(0)

    /** Start sync if opted-in and authed. Idempotent — safe to call on any state change. */
    fun startIfEnabled(context: Context) {
        val on = runCatching { SettingsStore.s.value.cloudSync }.getOrDefault(false)
        val uid = SyncAuth.current()
        if (!on || uid == null) { stop(); return }
        if (running) return
        running = true
        runCatching {
            Degrades.clear(Degrades.SYNC)   // v1.71 (N2): a clean attach means sync is healthy
            attachRemoteListeners(context, uid)
            startLocalObservers(context)
        }.onFailure {
            Logger.e(context, "SYNC", it, "sync start failed")
            running = false
        }
    }

    fun stop() {
        regs.forEach { runCatching { it.remove() } }
        regs.clear()
        running = false
    }

    // ---------------------------------------------------------------- remote -> local

    private fun attachRemoteListeners(context: Context, uid: String) {
        val r = root(uid)
        regs += r.collection("items").addSnapshotListener { snap, e ->
            if (e != null) { Logger.e(context, "SYNC", e, "items listen").also { Degrades.raise(context, Degrades.SYNC, "items listen failed") }; return@addSnapshotListener }
            val byId = ItemStore.items.value.associateBy { it.id }
            val inc = snap?.documents?.mapNotNull { d ->
                mergeRecord(context, d.getString("json"), verOf(d), Item::class.java) { byId[it.id] }
                    ?.let { runCatching { healItem(it) }.getOrNull() }
            } ?: return@addSnapshotListener
            if (inc.isNotEmpty()) applyItems(context, inc)
        }
        regs += r.collection("calls").addSnapshotListener { snap, e ->
            if (e != null) { Logger.e(context, "SYNC", e, "calls listen").also { Degrades.raise(context, Degrades.SYNC, "calls listen failed") }; return@addSnapshotListener }
            val byId = CallStore.calls.value.associateBy { it.id }
            val inc = snap?.documents?.mapNotNull { d ->
                mergeRecord(context, d.getString("json"), verOf(d), CallReminder::class.java) { byId[it.id] }
                    ?.let { runCatching { healCall(it) }.getOrNull() }
            } ?: return@addSnapshotListener
            if (inc.isNotEmpty()) applyCalls(context, inc)
        }
        regs += r.collection("places").addSnapshotListener { snap, e ->
            if (e != null) { Logger.e(context, "SYNC", e, "places listen").also { Degrades.raise(context, Degrades.SYNC, "places listen failed") }; return@addSnapshotListener }
            val byId = PlaceStore.places.value.associateBy { it.id }
            val inc = snap?.documents?.mapNotNull { d ->
                mergeRecord(context, d.getString("json"), verOf(d), GeoPlace::class.java) { byId[it.id] }
                    ?.let { runCatching { healPlace(it) }.getOrNull() }
            } ?: return@addSnapshotListener
            if (inc.isNotEmpty()) applyPlaces(context, inc)
        }
        regs += r.collection("shops").addSnapshotListener { snap, e ->
            if (e != null) { Logger.e(context, "SYNC", e, "shops listen").also { Degrades.raise(context, Degrades.SYNC, "shops listen failed") }; return@addSnapshotListener }
            val byId = ShopStore.shops.value.associateBy { it.id }
            val inc = snap?.documents?.mapNotNull { d ->
                mergeRecord(context, d.getString("json"), verOf(d), Shop::class.java) { byId[it.id] }
                    ?.let { runCatching { healShop(it) }.getOrNull() }
            } ?: return@addSnapshotListener
            if (inc.isNotEmpty()) applyShops(context, inc)
        }
        regs += r.collection("settings").document("app").addSnapshotListener { snap, e ->
            if (e != null) { Logger.e(context, "SYNC", e, "settings listen").also { Degrades.raise(context, Degrades.SYNC, "settings listen failed") }; return@addSnapshotListener }
            val doc = snap ?: return@addSnapshotListener
            val rv = verOf(doc)
            if (rv < SYNC_SCHEMA) {
                // v1.70 (Q10): a fresh or older install carries DEFAULT settings with a fresh
                // timestamp. Adopting it would reset this device's preferences wholesale, so a
                // lower-schema settings document is never applied. Items still merge normally.
                Logger.e(
                    context, "SYNC", null,
                    "settings document from older sync schema v$rv \u2014 not adopted (would " +
                        "overwrite this device's settings with the other device's defaults)"
                )
                return@addSnapshotListener
            }
            val remote = decodeSettings(doc.getString("json")) ?: return@addSnapshotListener
            applySettings(context, remote)
        }
    }

    private fun decodeItem(json: String?): Item? =
        json?.let { runCatching { healItem(gson.fromJson(it, Item::class.java)) }.getOrNull() }
    private fun decodeCall(json: String?): CallReminder? =
        json?.let { runCatching { healCall(gson.fromJson(it, CallReminder::class.java)) }.getOrNull() }
    private fun decodePlace(json: String?): GeoPlace? =
        json?.let { runCatching { healPlace(gson.fromJson(it, GeoPlace::class.java)) }.getOrNull() }

    private fun decodeShop(json: String?): Shop? =
        json?.let { runCatching { healShop(gson.fromJson(it, Shop::class.java)) }.getOrNull() }
    private fun decodeSettings(json: String?): AppSettings? =
        json?.let { runCatching { healSettings(gson.fromJson(it, AppSettings::class.java)) }.getOrNull() }

    private fun applyItems(context: Context, incoming: List<Item>) {
        val cur = ItemStore.items.value.associateBy { it.id }.toMutableMap()
        var changed = false
        incoming.forEach { inc ->
            val ex = cur[inc.id]
            if (ex == null || stampOf(inc.updatedAt, inc.createdAt) >= stampOf(ex.updatedAt, ex.createdAt)) {
                if (ex != inc) { cur[inc.id] = inc; changed = true }
                seenItems[inc.id] = inc.updatedAt
            }
        }
        if (changed) {
            ItemStore.replaceAll(cur.values.toList())
            runCatching { AlarmScheduler.rescheduleAll(context) }
        }
    }

    private fun applyCalls(context: Context, incoming: List<CallReminder>) {
        val cur = CallStore.calls.value.associateBy { it.id }.toMutableMap()
        var changed = false
        incoming.forEach { inc ->
            val ex = cur[inc.id]
            if (ex == null || stampOf(inc.updatedAt, inc.createdAt) >= stampOf(ex.updatedAt, ex.createdAt)) {
                if (ex != inc) { cur[inc.id] = inc; changed = true }
                seenCalls[inc.id] = inc.updatedAt
            }
        }
        if (changed) CallStore.replaceAll(cur.values.toList())
    }

    private fun applyPlaces(context: Context, incoming: List<GeoPlace>) {
        val cur = PlaceStore.places.value.associateBy { it.id }.toMutableMap()
        var changed = false
        incoming.forEach { inc ->
            val ex = cur[inc.id]
            if (ex == null || inc.updatedAt >= ex.updatedAt) {
                if (ex != inc) { cur[inc.id] = inc; changed = true }
                seenPlaces[inc.id] = inc.updatedAt
            }
        }
        if (changed) {
            PlaceStore.replaceAll(cur.values.toList())
            runCatching { Geofencer.registerAll(context) }
        }
    }

    private fun applyShops(context: Context, incoming: List<Shop>) {
        val cur = ShopStore.shops.value.associateBy { it.id }.toMutableMap()
        var changed = false
        incoming.forEach { inc ->
            val ex = cur[inc.id]
            if (ex == null || inc.updatedAt >= ex.updatedAt) {
                if (ex != inc) { cur[inc.id] = inc; changed = true }
                seenShops[inc.id] = inc.updatedAt
            }
        }
        if (changed) {
            ShopStore.replaceAll(cur.values.toList())
            runCatching { Geofencer.registerAll(context) }
        }
    }

    private fun applySettings(context: Context, remote: AppSettings) {
        val local = SettingsStore.s.value
        if (remote.settingsUpdatedAt <= local.settingsUpdatedAt) return
        // Keep device-local fields; union groups/topics so neither device loses one.
        val merged = remote.copy(
            cloudSync = local.cloudSync,                 // the toggle is per-device
            lastSyncAt = local.lastSyncAt,
            lastDataBackupAt = local.lastDataBackupAt,
            tasksGroups = (local.tasksGroups + remote.tasksGroups).distinct(),
            shopGroups = (local.shopGroups + remote.shopGroups).distinct(),
            learnTopics = (local.learnTopics + remote.learnTopics).distinct()
        )
        seenSettings = remote.settingsUpdatedAt
        SettingsStore.applyRemote(merged)
    }

    // ---------------------------------------------------------------- local -> remote

    private fun startLocalObservers(context: Context) {
        if (observersStarted) return
        observersStarted = true
        scope.launch { ItemStore.items.collect { if (running) pushItems(context, it) } }
        scope.launch { CallStore.calls.collect { if (running) pushCalls(context, it) } }
        scope.launch { PlaceStore.places.collect { if (running) pushPlaces(context, it) } }
        scope.launch { ShopStore.shops.collect { if (running) pushShops(context, it) } }
        scope.launch { SettingsStore.s.collect { if (running) pushSettings(context, it) } }
    }

    private fun pushItems(context: Context, list: List<Item>) {
        val uid = SyncAuth.current() ?: return
        val col = root(uid).collection("items")
        list.forEach { rec ->
            if (seenItems[rec.id] == rec.updatedAt) return@forEach
            seenItems[rec.id] = rec.updatedAt
            runCatching {
                col.document(rec.id.toString())
                    .set(mapOf("json" to syncGson.toJson(rec), "updatedAt" to rec.updatedAt, "schemaVer" to SYNC_SCHEMA))
                    .addOnFailureListener { Logger.e(context, "SYNC", it, "item push ${rec.id}").also { Degrades.raise(context, Degrades.SYNC, "item push failed") } }
            }.onFailure { Logger.e(context, "SYNC", it, "item push threw ${rec.id}").also { Degrades.raise(context, Degrades.SYNC, "item push failed") } }
        }
    }

    private fun pushCalls(context: Context, list: List<CallReminder>) {
        val uid = SyncAuth.current() ?: return
        val col = root(uid).collection("calls")
        list.forEach { rec ->
            if (seenCalls[rec.id] == rec.updatedAt) return@forEach
            seenCalls[rec.id] = rec.updatedAt
            runCatching {
                col.document(rec.id.toString())
                    .set(mapOf("json" to syncGson.toJson(rec), "updatedAt" to rec.updatedAt, "schemaVer" to SYNC_SCHEMA))
                    .addOnFailureListener { Logger.e(context, "SYNC", it, "call push ${rec.id}").also { Degrades.raise(context, Degrades.SYNC, "call push failed") } }
            }.onFailure { Logger.e(context, "SYNC", it, "call push threw ${rec.id}").also { Degrades.raise(context, Degrades.SYNC, "call push failed") } }
        }
    }

    private fun pushPlaces(context: Context, list: List<GeoPlace>) {
        val uid = SyncAuth.current() ?: return
        val col = root(uid).collection("places")
        list.forEach { rec ->
            if (seenPlaces[rec.id] == rec.updatedAt) return@forEach
            seenPlaces[rec.id] = rec.updatedAt
            runCatching {
                col.document(rec.id.toString())
                    .set(mapOf("json" to syncGson.toJson(rec), "updatedAt" to rec.updatedAt, "schemaVer" to SYNC_SCHEMA))
                    .addOnFailureListener { Logger.e(context, "SYNC", it, "place push ${rec.id}").also { Degrades.raise(context, Degrades.SYNC, "place push failed") } }
            }.onFailure { Logger.e(context, "SYNC", it, "place push threw ${rec.id}").also { Degrades.raise(context, Degrades.SYNC, "place push failed") } }
        }
    }

    private fun pushShops(context: Context, list: List<Shop>) {
        val uid = SyncAuth.current() ?: return
        val col = root(uid).collection("shops")
        list.forEach { rec ->
            if (seenShops[rec.id] == rec.updatedAt) return@forEach
            seenShops[rec.id] = rec.updatedAt
            runCatching {
                col.document(rec.id.toString())
                    .set(mapOf("json" to syncGson.toJson(rec), "updatedAt" to rec.updatedAt, "schemaVer" to SYNC_SCHEMA))
                    .addOnFailureListener { Logger.e(context, "SYNC", it, "shop push ${rec.id}").also { Degrades.raise(context, Degrades.SYNC, "shop push failed") } }
            }.onFailure { Logger.e(context, "SYNC", it, "shop push threw ${rec.id}").also { Degrades.raise(context, Degrades.SYNC, "shop push failed") } }
        }
    }

    private fun pushSettings(context: Context, s: AppSettings) {
        val uid = SyncAuth.current() ?: return
        if (seenSettings == s.settingsUpdatedAt) return
        seenSettings = s.settingsUpdatedAt
        val payload = Backup.settingsForSync(s)
        runCatching {
            root(uid).collection("settings").document("app")
                .set(mapOf("json" to syncGson.toJson(payload), "updatedAt" to s.settingsUpdatedAt, "schemaVer" to SYNC_SCHEMA))
                .addOnFailureListener { Logger.e(context, "SYNC", it, "settings push").also { Degrades.raise(context, Degrades.SYNC, "settings push failed") } }
        }.onFailure { Logger.e(context, "SYNC", it, "settings push threw").also { Degrades.raise(context, Degrades.SYNC, "settings push failed") } }
    }
}
