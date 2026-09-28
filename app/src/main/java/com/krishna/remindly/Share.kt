package com.krishna.remindly

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * v1.87 (N17) — LIST SHARING. One-way, read-only, FROZEN copies between Remindly accounts.
 * Tasks + Shop only. The share document IS the receiver's copy (no local mirror, no Item schema
 * change): /shares/{id}. Blocklist: /users/{uid}/blocked/{emailLower}. Works regardless of the
 * personal Cloud-sync toggle — its only requirements are the share switch and a signed-in
 * Google/Firebase session (the same session Sync uses).
 *
 * Locked decisions Q1–Q9 (BACKLOG 08-Aug-2026) are enforced here, not in the UI:
 *   Q1 frozen copy (send writes items once; nothing ever updates them)
 *   Q2 re-share replaces on ACCEPT (dedupe key fromEmail+listName — see replaceKeysOnAccept)
 *   Q3 receiver ticks are DEVICE-LOCAL (SharedPreferences, never written to Firestore)
 *   Q4 receiver deletes the whole copy only (receiverDeleted flag)
 *   Q5 Block surfaces to the sender as plain DECLINED
 *   Q6 sender delete never touches an accepted copy (senderDeleted flag hides it sender-side;
 *      a physical doc delete is only ever issued for non-accepted records)
 *   Q7 one recipient per share   Q8 whitelist excludes price   Q9 no push — badge on open
 */

// ---------------------------------------------------------------- wire model

/** Q8: THE whitelist. A shared item is these four strings and nothing else — no dates, no
 *  alerts, no done/snooze state, and never a price. */
data class SharedItem(
    val title: String,
    val note: String = "",
    val quantity: String = "",
    val unit: String = ""
)

data class ShareRecord(
    val id: String,
    val fromUid: String,
    val fromEmail: String,
    val toEmail: String,          // stored case-folded
    val tab: String,              // "TASKS" | "SHOP"
    val listName: String,
    val items: List<SharedItem>,
    val sentAt: Long,
    val status: String,           // pending | accepted | declined | revoked
    val receiverDeleted: Boolean,
    val senderDeleted: Boolean
)

const val SHARE_ITEM_CAP = 200
const val SHARE_PENDING = "pending"
const val SHARE_ACCEPTED = "accepted"
const val SHARE_DECLINED = "declined"
const val SHARE_REVOKED = "revoked"

// ---------------------------------------------------------------- pure logic (unit-tested)

/** Q8 whitelist mapping. Tasks carry title+note; Shop adds quantity+unit. Price NEVER copies. */
fun sharedItemsOf(items: List<Item>, tab: Tab): List<SharedItem> = items.map { i ->
    SharedItem(
        title = i.title,
        note = i.notes,
        quantity = if (tab == Tab.SHOP) (i.quantity ?: "") else "",
        unit = if (tab == Tab.SHOP) (i.unit ?: "") else ""
    )
}

/** Default chip selection: everything not done and not personal-locked. */
fun defaultShareSelection(items: List<Item>, isLocked: (Item) -> Boolean): Set<Long> =
    items.filter { !it.done && !isLocked(it) }.map { it.id }.toSet()

fun validShareCount(n: Int): Boolean = n in 1..SHARE_ITEM_CAP

private fun emailKey(e: String) = e.trim().lowercase()

/** Very light address sanity — Firestore rules do the real matching on the auth token. */
fun plausibleEmail(e: String): Boolean {
    val t = e.trim()
    return t.length >= 5 && "@" in t.drop(1).dropLast(3) && " " !in t
}

/** Blocked senders vanish client-side BEFORE render (companion 1). */
fun withoutBlocked(shares: List<ShareRecord>, blocked: Set<String>): List<ShareRecord> =
    shares.filter { emailKey(it.fromEmail) !in blocked }

fun pendingReceived(shares: List<ShareRecord>, blocked: Set<String>): List<ShareRecord> =
    withoutBlocked(shares, blocked)
        .filter { it.status == SHARE_PENDING && !it.receiverDeleted }
        .sortedByDescending { it.sentAt }

fun acceptedReceived(shares: List<ShareRecord>, blocked: Set<String>): List<ShareRecord> =
    withoutBlocked(shares, blocked)
        .filter { it.status == SHARE_ACCEPTED && !it.receiverDeleted }
        .sortedByDescending { it.sentAt }

fun visibleSent(shares: List<ShareRecord>): List<ShareRecord> =
    shares.filter { !it.senderDeleted }.sortedByDescending { it.sentAt }

/** Q2: accepting `accepted` retires every OTHER accepted copy with the same
 *  fromEmail+listName (case-folded). Returns the ids to mark receiverDeleted. */
fun replaceKeysOnAccept(all: List<ShareRecord>, accepted: ShareRecord): List<String> =
    all.filter {
        it.id != accepted.id &&
            it.status == SHARE_ACCEPTED && !it.receiverDeleted &&
            emailKey(it.fromEmail) == emailKey(accepted.fromEmail) &&
            it.listName == accepted.listName
    }.map { it.id }

/** The ONLY legal status moves. Everything else is refused before any write. */
fun canTransition(from: String, to: String, byRecipient: Boolean): Boolean = when {
    from == SHARE_PENDING && to == SHARE_ACCEPTED -> byRecipient
    from == SHARE_PENDING && to == SHARE_DECLINED -> byRecipient
    from == SHARE_PENDING && to == SHARE_REVOKED -> !byRecipient
    else -> false
}

/** The "Share as text" fallback body (system share sheet). */
fun shareAsText(listName: String, items: List<SharedItem>): String = buildString {
    append(listName)
    items.forEach { i ->
        append("\n\u2022 ").append(i.title)
        val q = listOf(i.quantity, i.unit).filter { it.isNotBlank() }.joinToString(" ")
        if (q.isNotBlank()) append(" (").append(q).append(")")
        if (i.note.isNotBlank()) append(" \u2014 ").append(i.note)
    }
}

/** Defensive doc -> record; any malformed field falls to a safe default, never a crash. */
fun shareRecordOf(id: String, d: Map<String, Any?>): ShareRecord {
    fun s(k: String) = (d[k] as? String) ?: ""
    fun b(k: String) = (d[k] as? Boolean) ?: false
    val items = (d["items"] as? List<*>)?.mapNotNull { raw ->
        (raw as? Map<*, *>)?.let { m ->
            SharedItem(
                title = (m["title"] as? String) ?: "",
                note = (m["note"] as? String) ?: "",
                quantity = (m["quantity"] as? String) ?: "",
                unit = (m["unit"] as? String) ?: ""
            )
        }
    } ?: emptyList()
    return ShareRecord(
        id = id,
        fromUid = s("fromUid"), fromEmail = s("fromEmail"), toEmail = s("toEmail"),
        tab = s("tab").ifBlank { "TASKS" }, listName = s("listName"),
        items = items,
        sentAt = (d["sentAt"] as? Number)?.toLong() ?: 0L,
        status = s("status").ifBlank { SHARE_PENDING },
        receiverDeleted = b("receiverDeleted"),
        senderDeleted = b("senderDeleted")
    )
}

/** Per-tab share switch: per-tab override -> global master (standing per-tab rule). */
fun shareEnabledFor(tab: Tab?, s: AppSettings): Boolean {
    if (tab != Tab.TASKS && tab != Tab.SHOP) return false
    val ov = if (tab == Tab.SHOP) s.shopShareOn else s.tasksShareOn
    return when (ov) { "ON" -> true; "OFF" -> false; else -> s.shareOn }
}

// ---------------------------------------------------------------- the store

object ShareStore {

    val sent = MutableStateFlow<List<ShareRecord>>(emptyList())
    val received = MutableStateFlow<List<ShareRecord>>(emptyList())
    val blocked = MutableStateFlow<Set<String>>(emptySet())
    /** bumps when a device-local tick toggles, so read-only sheets recompose */
    val ticksVersion = MutableStateFlow(0)

    private val regs = mutableListOf<ListenerRegistration>()
    private var running = false

    private fun db() = runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    fun uid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
    fun email(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.email }.getOrNull()
    fun signedIn(): Boolean = uid() != null && !email().isNullOrBlank()
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("share_store", Context.MODE_PRIVATE)

    /** Sharing rides the SAME auth session as sync but NOT the sync toggle — only the share
     *  master switch and a signed-in session gate it. Safe to call repeatedly. */
    fun startIfSignedIn(context: Context) {
        val on = runCatching { SettingsStore.s.value.shareOn }.getOrDefault(true)
        val u = uid(); val e = email()
        if (!on || u == null || e.isNullOrBlank()) { stop(); return }
        if (running) return
        val d = db() ?: run {
            Logger.e(context, "SHARE", null, "firestore unavailable — sharing offline"); return
        }
        running = true
        runCatching {
            regs += d.collection("shares").whereEqualTo("fromUid", u)
                .addSnapshotListener { snap, err ->
                    if (err != null) { Logger.e(context, "SHARE", err, "sent listen failed (rules?)"); return@addSnapshotListener }
                    snap?.let { s0 -> sent.value = s0.documents.map { shareRecordOf(it.id, it.data ?: emptyMap()) } }
                }
            regs += d.collection("shares").whereEqualTo("toEmail", e.lowercase())
                .addSnapshotListener { snap, err ->
                    if (err != null) { Logger.e(context, "SHARE", err, "received listen failed (rules?)"); return@addSnapshotListener }
                    snap?.let { s0 -> received.value = s0.documents.map { shareRecordOf(it.id, it.data ?: emptyMap()) } }
                }
            regs += d.collection("users").document(u).collection("blocked")
                .addSnapshotListener { snap, err ->
                    if (err != null) { Logger.e(context, "SHARE", err, "blocklist listen failed"); return@addSnapshotListener }
                    snap?.let { s0 -> blocked.value = s0.documents.map { it.id.lowercase() }.toSet() }
                }
        }.onFailure {
            Logger.e(context, "SHARE", it, "share listeners failed to attach")
            running = false
        }
    }

    fun stop() {
        regs.forEach { runCatching { it.remove() } }
        regs.clear(); running = false
        sent.value = emptyList(); received.value = emptyList(); blocked.value = emptySet()
    }

    // ------------------------------------------------------------ sender ops

    /** A failed send marks NOTHING sent — the callback gets an honest false and the sheet
     *  stays open for retry (companion 1). */
    fun send(
        context: Context, tab: Tab, listName: String, toEmail: String,
        items: List<SharedItem>, onDone: (Boolean) -> Unit
    ) {
        val u = uid(); val e = email()
        if (u == null || e.isNullOrBlank()) {
            Logger.e(context, "SHARE", null, "send refused: not signed in"); onDone(false); return
        }
        if (!plausibleEmail(toEmail)) { onDone(false); return }
        if (!validShareCount(items.size)) {
            Logger.e(context, "SHARE", null, "send refused: ${items.size} items (cap $SHARE_ITEM_CAP)")
            onDone(false); return
        }
        val d = db() ?: run { Logger.e(context, "SHARE", null, "send: firestore unavailable"); onDone(false); return }
        val doc = d.collection("shares").document()
        val body = mapOf(
            "fromUid" to u, "fromEmail" to e, "toEmail" to emailKey(toEmail),
            "tab" to (if (tab == Tab.SHOP) "SHOP" else "TASKS"), "listName" to listName,
            "items" to items.map { mapOf("title" to it.title, "note" to it.note, "quantity" to it.quantity, "unit" to it.unit) },
            "sentAt" to System.currentTimeMillis(), "status" to SHARE_PENDING,
            "receiverDeleted" to false, "senderDeleted" to false
        )
        runCatching {
            doc.set(body)
                .addOnSuccessListener {
                    Logger.e(context, "SHARE", null, "sent '$listName' (${items.size}) -> ${emailKey(toEmail)}")
                    rememberRecent(context, toEmail); onDone(true)
                }
                .addOnFailureListener { err ->
                    Logger.e(context, "SHARE", err, "send failed (rules-denied or offline queue?)"); onDone(false)
                }
        }.onFailure { Logger.e(context, "SHARE", it, "send threw"); onDone(false) }
    }

    fun revoke(context: Context, r: ShareRecord) {
        if (!canTransition(r.status, SHARE_REVOKED, byRecipient = false)) return
        write(context, r.id, mapOf("status" to SHARE_REVOKED), "revoked '${r.listName}'")
    }

    /** Q6: an ACCEPTED record is the receiver's copy — the sender only hides it. */
    fun senderDelete(context: Context, r: ShareRecord) {
        if (r.status == SHARE_ACCEPTED) {
            write(context, r.id, mapOf("senderDeleted" to true), "sender hid accepted '${r.listName}'")
        } else {
            val d = db() ?: return
            runCatching {
                d.collection("shares").document(r.id).delete()
                    .addOnSuccessListener { Logger.e(context, "SHARE", null, "sender deleted '${r.listName}' (${r.status})") }
                    .addOnFailureListener { Logger.e(context, "SHARE", it, "sender delete failed") }
            }.onFailure { Logger.e(context, "SHARE", it, "sender delete threw") }
        }
    }

    // ------------------------------------------------------------ receiver ops

    /** Idempotent (re-tap safe); Q2 replace rides the same batch. */
    fun accept(context: Context, r: ShareRecord) {
        if (r.status == SHARE_ACCEPTED) return
        if (!canTransition(r.status, SHARE_ACCEPTED, byRecipient = true)) return
        val d = db() ?: return
        val retire = replaceKeysOnAccept(received.value, r)
        runCatching {
            val b = d.batch()
            b.update(d.collection("shares").document(r.id), mapOf("status" to SHARE_ACCEPTED))
            retire.forEach { b.update(d.collection("shares").document(it), mapOf("receiverDeleted" to true)) }
            b.commit()
                .addOnSuccessListener {
                    Logger.e(context, "SHARE", null,
                        "accepted '${r.listName}' from ${r.fromEmail}" +
                            (if (retire.isNotEmpty()) "; replaced ${retire.size} older copy" else ""))
                }
                .addOnFailureListener { Logger.e(context, "SHARE", it, "accept failed (rules-denied?)") }
        }.onFailure { Logger.e(context, "SHARE", it, "accept threw") }
    }

    fun decline(context: Context, r: ShareRecord) {
        if (!canTransition(r.status, SHARE_DECLINED, byRecipient = true)) return
        write(context, r.id, mapOf("status" to SHARE_DECLINED), "declined '${r.listName}' from ${r.fromEmail}")
    }

    fun receiverDelete(context: Context, r: ShareRecord) =
        write(context, r.id, mapOf("receiverDeleted" to true), "deleted my copy of '${r.listName}'")

    /** Q5: block = blocklist doc + a plain DECLINE on every pending share from them. */
    fun block(context: Context, fromEmail: String) {
        val u = uid() ?: return; val d = db() ?: return
        val key = emailKey(fromEmail)
        runCatching {
            d.collection("users").document(u).collection("blocked").document(key)
                .set(mapOf("at" to System.currentTimeMillis()))
                .addOnSuccessListener { Logger.e(context, "SHARE", null, "blocked $key") }
                .addOnFailureListener { Logger.e(context, "SHARE", it, "block write failed") }
        }.onFailure { Logger.e(context, "SHARE", it, "block threw") }
        received.value.filter { emailKey(it.fromEmail) == key && it.status == SHARE_PENDING }
            .forEach { decline(context, it) }
    }

    fun unblock(context: Context, email0: String) {
        val u = uid() ?: return; val d = db() ?: return
        runCatching {
            d.collection("users").document(u).collection("blocked").document(emailKey(email0)).delete()
                .addOnSuccessListener { Logger.e(context, "SHARE", null, "unblocked ${emailKey(email0)}") }
                .addOnFailureListener { Logger.e(context, "SHARE", it, "unblock failed") }
        }.onFailure { Logger.e(context, "SHARE", it, "unblock threw") }
    }

    private fun write(context: Context, id: String, patch: Map<String, Any>, okMsg: String) {
        val d = db() ?: return
        runCatching {
            d.collection("shares").document(id).update(patch)
                .addOnSuccessListener { Logger.e(context, "SHARE", null, okMsg) }
                .addOnFailureListener { Logger.e(context, "SHARE", it, "write failed for $okMsg (rules-denied?)") }
        }.onFailure { Logger.e(context, "SHARE", it, "write threw for $okMsg") }
    }

    // ------------------------------------------------------------ device-local extras

    /** Q3: local strike-through ticks — SharedPreferences only, never synced, never an edit. */
    fun ticks(context: Context, shareId: String): Set<Int> =
        (prefs(context).getString("ticks_$shareId", "") ?: "")
            .split(',').mapNotNull { it.toIntOrNull() }.toSet()

    fun toggleTick(context: Context, shareId: String, index: Int) {
        val cur = ticks(context, shareId).toMutableSet()
        if (!cur.add(index)) cur.remove(index)
        prefs(context).edit().putString("ticks_$shareId", cur.joinToString(",")).apply()
        ticksVersion.value = ticksVersion.value + 1
    }

    fun recents(context: Context): List<String> =
        (prefs(context).getString("recents", "") ?: "").split('\u0001').filter { it.isNotBlank() }

    private fun rememberRecent(context: Context, email0: String) {
        val k = emailKey(email0)
        val list = (listOf(k) + recents(context).filter { it != k }).take(5)
        prefs(context).edit().putString("recents", list.joinToString("\u0001")).apply()
    }
}
