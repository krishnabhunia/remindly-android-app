package com.krishna.remindly

import android.Manifest
import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

// ================================================================ store

object CallStore {
    val calls = MutableStateFlow<List<CallReminder>>(emptyList())
    private val file get() = File(Stores.appContext.filesDir, "calls.json")

    fun load() {
        calls.value = runCatching {
            gson.fromJson(file.readText(), Array<CallReminder>::class.java).toList().map(::healCall)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(gson.toJson(calls.value)) }
        Dirty.flag = true
    }

    fun get(id: Long): CallReminder? = calls.value.firstOrNull { it.id == id }

    fun activeByNumber(norm: String): CallReminder? =
        calls.value.firstOrNull { !it.done && normalizePhone(it.number) == norm }

    fun upsert(r: CallReminder) {
        val stamped = r.copy(updatedAt = System.currentTimeMillis())
        calls.value = calls.value.filter { it.id != stamped.id } + stamped
        save()
    }

    fun delete(id: Long) {
        calls.value = calls.value.filter { it.id != id }
        save()
    }

    fun replaceAll(newCalls: List<CallReminder>) {
        calls.value = newCalls
        save()
    }
}

// ================================================================ engine

data class RecentCall(val number: String, val name: String?, val date: Long, val type: Int)

object CallEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** callId -> epoch millis when the reminder moves to Done (manual tick countdown). */
    val pendingDone = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val jobs = HashMap<Long, Job>()

    fun hasCallLog(c: Context) = ContextCompat.checkSelfPermission(
        c, Manifest.permission.READ_CALL_LOG
    ) == PackageManager.PERMISSION_GRANTED

    fun hasPhoneState(c: Context) = ContextCompat.checkSelfPermission(
        c, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    fun hasContacts(c: Context) = ContextCompat.checkSelfPermission(
        c, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ call log processing

    /** Reads everything in the call log since the last processed entry and applies the rules. */
    /**
     * v1.52: scans by call-log ROW ID rather than date.
     *
     * The old `DATE > lastLog` scan had a permanent-loss race: `lastLog` advanced to the newest
     * date seen, so if a missed-call row was written late (after a call with a later timestamp had
     * already been processed) it was skipped forever. Row ids increase in write order, so
     * `_ID > lastId` cannot miss a late arrival.
     *
     * Every exit is logged — this path used to fail completely silently, which is why a real
     * missed-call bug could not be diagnosed from Error Logs.
     */
    // v1.86 (N26): the scan runs SERIALIZED OFF the main thread. `process()` used to be
    // @Synchronized and was called straight from MainActivity's startup path — a long scan held
    // the lock while the next caller blocked the MAIN thread behind it (D4: the OEM "hang").
    private val scanExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** N26 test seam: Robolectric cannot set PackageManager.firstInstallTime — tests inject. */
    @Volatile @androidx.annotation.VisibleForTesting
    var fenceOverride: Long? = null

    /**
     * N26: THE fence — missed calls count strictly from the point of INSTALLATION
     * (`firstInstallTime`): monotonic real time, identical on every OEM, survives prefs wipes.
     * ⚑ a permission revoke/regrant does NOT move it — installation does.
     */
    fun installFence(context: Context): Long = fenceOverride ?: runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    }.getOrElse {
        Logger.e(context, "CALLSCAN", it, "firstInstallTime unavailable — fencing at now")
        System.currentTimeMillis()
    }

    /**
     * v2.6.2 (N41): anchor the watermark WITHOUT reading anything. Called on app start so a phone
     * that installs today can never later interpret "no watermark" as "ingest from installation".
     */
    fun ensureWatermark(context: Context) {
        scanExec.execute {
            runCatching {
                val prefs = context.getSharedPreferences("call_engine", Context.MODE_PRIVATE)
                if (!prefs.contains("lastSeen") && !prefs.contains("lastId")) {
                    prefs.edit().putLong("lastSeen", System.currentTimeMillis()).apply()
                    Logger.e(context, "CALLSCAN", null, "N41: watermark anchored at first app start — history ignored")
                }
            }.onFailure { Logger.e(context, "CALLSCAN", it, "watermark anchor failed") }
        }
    }

    fun process(context: Context) {
        scanExec.execute {
            runCatching { processNow(context) }
                .onFailure { Logger.e(context, "CALLSCAN", it, "scan crashed") }
        }
    }

    /**
     * v1.86 (N26) REWRITE. The v1.52 `_ID` design had four verified defects: the first-run
     * baseline used the smuggled-LIMIT trick (`"_ID DESC LIMIT 1"`) that several OEM providers
     * reject — and on ANY failure it still wrote lastId=0, after which `_ID > 0` selected the
     * ENTIRE call history (D1, the Redmi/MI/Vivo flood); `_ID` itself is not a fence on
     * renumbering providers (D2); the loop had no row cap (D3); and it all ran on the main
     * thread (D4). Now: SQL-level fence `DATE >= max(firstInstallTime, lastSeen − 48 h)` (the
     * 48 h lookback keeps v1.52's late-written-row guarantee without trusting `_ID`; later than
     * that is accepted-as-lost and logged), a row-key dedupe ring so renumbered rows can never
     * re-ingest or double missedCount, a hard cap of SCAN_ROW_CAP rows per sweep, and no LIMIT
     * trick anywhere. `lastId` is retired (read once for the migration log, then ignored).
     */
    @Synchronized
    internal fun processNow(context: Context) {
        if (!hasCallLog(context)) {
            Logger.e(context, "CALLSCAN", null, "skipped: READ_CALL_LOG not granted (or auto-revoked)")
            return
        }
        val prefs = context.getSharedPreferences("call_engine", Context.MODE_PRIVATE)
        val proj = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME
        )
        val fence = installFence(context)
        val now = System.currentTimeMillis()

        // ---- first run / one-time migration from the retired lastId model ----
        if (!prefs.contains("lastSeen")) {
            val upgraded = prefs.contains("lastId")
            if (!upgraded) {
                // v2.6.2 (N41, Krishna): anchor at NOW, never at the install date. Anchoring at
                // firstInstallTime is exactly what let a first sweep on an old package (data
                // cleared, restore, late permission grant) ingest hundreds of historical rows and
                // fire one ring/alarm per number on Vivo/Redmi. History is ignored, always.
                prefs.edit().putLong("lastSeen", now).apply()
                Logger.e(context, "CALLSCAN", null,
                    "N41 first run: watermark anchored at ${formatDateTime(now)} — history ignored, live calls only")
                return
            }
            val oldId = prefs.getLong("lastId", -1L)
            // ⚑ purge (announced): AUTO reminders born from PRE-INSTALL calls are junk from the
            // D1 flood — delete them and their alarms; MANUAL reminders are untouched.
            val (_, purged) = purgePreInstall(CallStore.calls.value, fence)
            purged.forEach { r ->
                // Kill every alarm species a call can own: snooze + demoted (one helper) and
                // the recurrence chain; then the record itself. CallStore.delete is the hard
                // remove — pre-install junk must not resurface in Recently Deleted either.
                AlarmScheduler.cancelForCall(context, r.id)
                CallStore.delete(r.id)
            }
            // Seed the dedupe ring with the rows in the live window WITHOUT processing them, so an
            // upgrade never re-ingests calls the old model already handled. v2.6.2 (N41): the seed
            // reads the live window only — the 48 h reach-back is retired with the rest of history.
            val seed = LinkedHashSet<String>()
            runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI, proj,
                    "${CallLog.Calls.DATE} >= ?", arrayOf((now - LIVE_WINDOW_MS).toString()),
                    CallLog.Calls.DATE + " ASC"
                )?.use { cur ->
                    val iNum = cur.getColumnIndex(CallLog.Calls.NUMBER)
                    val iType = cur.getColumnIndex(CallLog.Calls.TYPE)
                    val iDate = cur.getColumnIndex(CallLog.Calls.DATE)
                    while (cur.moveToNext() && seed.size < RING_CAP) {
                        seed.add(rowKey(cur.getString(iNum) ?: "", cur.getLong(iDate), cur.getInt(iType)))
                    }
                }
            }.onFailure { Logger.e(context, "CALLSCAN", it, "ring-seed query failed (migration continues)") }
            saveRing(prefs, seed)
            prefs.edit().putLong("lastSeen", now).remove("lastId").apply()
            Logger.e(
                context, "CALLSCAN", null,
                "N26 migration: lastId=$oldId retired; fence=${formatDateTime(fence)}; " +
                    "purged ${purged.size} pre-install auto reminder(s); ring seeded ${seed.size} key(s)"
            )
            return
        }

        val since = prefs.getLong("lastSeen", 0L)
        // v2.6.2 (N41): a short live window — the log is read ONLY to resolve the row the OS
        // writes moments after a call ends. No install fence, no 48 h reach-back, no history.
        val windowStart = liveScanWindow(since, now)
        var rows = 0; var missed = 0; var rejected = 0; var convo = 0; var skipped = 0
        var dupes = 0; var total = 0; var history = 0
        // v2.6.2 (N41): reminders created by THIS sweep — alerted once, together, at the end.
        val sweep = ArrayList<CallReminder>(4)
        val includeRejected = SettingsStore.s.value.callIncludeRejected
        val ring = loadRing(prefs)
        data class Row(val id: Long, val number: String, val type: Int, val date: Long, val dur: Long, val cached: String?)
        val batch = ArrayList<Row>(SCAN_ROW_CAP)
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, proj,
                "${CallLog.Calls.DATE} >= ?", arrayOf(windowStart.toString()),
                CallLog.Calls.DATE + " DESC"
            )?.use { cur ->
                total = cur.count
                val iId = cur.getColumnIndex(CallLog.Calls._ID)
                val iNum = cur.getColumnIndex(CallLog.Calls.NUMBER)
                val iType = cur.getColumnIndex(CallLog.Calls.TYPE)
                val iDate = cur.getColumnIndex(CallLog.Calls.DATE)
                val iDur = cur.getColumnIndex(CallLog.Calls.DURATION)
                val iName = cur.getColumnIndex(CallLog.Calls.CACHED_NAME)
                // Hard cap D3: read the NEWEST cap rows, then process ascending.
                while (cur.moveToNext() && batch.size < SCAN_ROW_CAP) {
                    batch.add(Row(
                        if (iId >= 0) cur.getLong(iId) else 0L,
                        cur.getString(iNum) ?: "",
                        cur.getInt(iType), cur.getLong(iDate), cur.getLong(iDur),
                        if (iName >= 0) cur.getString(iName) else null
                    ))
                }
            } ?: Logger.e(context, "CALLSCAN", null, "call log query returned null cursor")
            if (total > SCAN_ROW_CAP) Logger.e(
                context, "CALLSCAN", null,
                "clamped: $total rows in window, processing newest $SCAN_ROW_CAP"
            )
            for (r in batch.asReversed()) {
                    rows++
                    val id = r.id
                    val number = r.number
                    val type = r.type
                    val date = r.date
                    val dur = r.dur
                    val cached = r.cached
                    val key = rowKey(number, date, type)
                    if (key in ring) { dupes++; continue }
                    ring.add(key)
                    if (number.isBlank()) {
                        skipped++
                        Logger.e(context, "CALLSCAN", null, "row $id skipped: private/withheld number (type $type)")
                        continue
                    }
                    // v2.6.2 (N41): a row outside the live window can never be processed, whatever
                    // the provider hands back — history is ignored by construction, not by trust.
                    if (!isLiveRow(date, now)) { history++; continue }
                    when {
                        type == CallLog.Calls.MISSED_TYPE -> { missed++; onMissed(context, number, date, cached, sweep) }
                        (type == CallLog.Calls.REJECTED_TYPE || type == CallLog.Calls.BLOCKED_TYPE) -> {
                            if (includeRejected) { rejected++; onMissed(context, number, date, cached, sweep) }
                            else { skipped++; Logger.e(context, "CALLSCAN", null, "row $id skipped: declined/blocked call — enable 'Treat declined calls as missed' to capture these") }
                        }
                        type == CallLog.Calls.INCOMING_TYPE -> { if (dur >= 10) { convo++; onConversation(context, number, dur, incoming = true) } else skipped++ }
                        type == CallLog.Calls.OUTGOING_TYPE -> { if (dur >= 10) { convo++; onConversation(context, number, dur, incoming = false) } else skipped++ }
                        else -> { skipped++; Logger.e(context, "CALLSCAN", null, "row $id skipped: unhandled type $type") }
                    }
            }
        } catch (e: SecurityException) {
            // Previously swallowed in silence — the single biggest reason this was undiagnosable.
            Logger.e(context, "CALLSCAN", e, "call log query denied (SecurityException)")
        }
        saveRing(prefs, ring)
        // v2.6.2 (N41): the anchor does NOT advance — the window is always "the last 5 minutes,
        // but never before the anchor", so a late-written row still lands and the ring de-dupes.
        // (`lastSeen` is kept only as that anchor; it is written once, on first run.)
        // v2.6.2 (N41) storm guard: ONE alert per sweep, never one per number.
        runCatching {
            if (alertsForSweep(sweep.size) > 0) {
                if (sweep.size == 1) fireDetection(context, sweep.first())
                else {
                    Alerts.infoNotification(
                        context, "${sweep.size} missed calls",
                        sweep.take(4).joinToString(", ") { it.name ?: it.number } +
                            (if (sweep.size > 4) " and ${sweep.size - 4} more" else ""),
                        Tab.TASKS
                    )
                    Logger.e(context, "CALLSCAN", null, "storm guard: ${sweep.size} new reminders in one sweep → single summary")
                }
            }
        }.onFailure { Logger.e(context, "CALLSCAN", it, "sweep alert failed") }
        Logger.e(
            context, "CALLSCAN", null,
            "live window ${formatDateTime(windowStart)} (install ${formatDateTime(fence)}): " +
                "$rows rows of $total, $missed missed, $rejected declined-as-missed, $convo conversations, " +
                "$skipped skipped, $dupes dedupe-skips, $history ignored-as-history"
        )
    
        maybeEnrich(context)
    }

    // ---------------------------------------------------------- N26 dedupe ring (renumber-proof)
    private const val RING_CAP = 300
    internal fun rowKey(number: String, date: Long, type: Int) = "$number|$date|$type"
    private fun loadRing(prefs: android.content.SharedPreferences): LinkedHashSet<String> =
        LinkedHashSet((prefs.getString("ring", "") ?: "").split('\u0001').filter { it.isNotBlank() })
    private fun saveRing(prefs: android.content.SharedPreferences, ring: LinkedHashSet<String>) {
        val trimmed = ring.toList().takeLast(RING_CAP)
        prefs.edit().putString("ring", trimmed.joinToString("\u0001")).apply()
    }

    private fun maybeEnrich(context: Context) {
        // v1.67: enrich reminders still missing structured fields — covers every add path
        // and pre-feature reminders. Contact added later gets picked up on the next open.
        runCatching {
            CallStore.calls.value
                .filter { !it.done && it.deletedAt == null && it.firstName == null && it.lastName == null && it.company == null }
                .forEach { r0 ->
                    lookupContact(context, r0.number)?.let { ci ->
                        if (ci.first != null || ci.last != null || ci.company != null) {
                            CallStore.upsert(r0.copy(
                                firstName = ci.first, lastName = ci.last, company = ci.company,
                                updatedAt = System.currentTimeMillis()
                            ))
                            Logger.e(context, "CALLS", null, "enriched ${r0.number}: ${callFullName(ci.first, ci.last) ?: "-"} / ${ci.company ?: "-"}")
                        }
                    }
                }
        }
    }

    private fun onMissed(context: Context, number: String, date: Long, cached: String?, sweep: MutableList<CallReminder>? = null) {
        val norm = normalizePhone(number)
        if (norm.isBlank()) return
        val existing = CallStore.activeByNumber(norm)
        if (existing != null) {
            // One reminder per number: refresh it, keep its source, re-arm the single nag.
            val upd = existing.copy(
                missedCount = existing.missedCount + 1,
                lastMissedAt = date,
            )
            CallStore.upsert(upd)
        } else {
            val name = lookupName(context, number) ?: cached
            val r = CallReminder(
                id = Ids.next(), number = number, name = name,
                source = CallSource.AUTO, createdAt = date,
                lastMissedAt = date, missedCount = 1
            )
            CallStore.upsert(r)
            // v2.6.2 (N41): a sweep collects its new reminders and raises ONE alert at the end;
            // callers outside a sweep (manual add paths) keep the immediate single alert.
            if (sweep != null) sweep.add(r) else fireDetection(context, r)   // v1.68: the ONE detection alert
        }
    }

    private fun onConversation(context: Context, number: String, durationSec: Long, incoming: Boolean) {
        val norm = normalizePhone(number)
        val matches = CallStore.calls.value.filter {
            !it.done && normalizePhone(it.number) == norm
        }
        for (r in matches) {
            val clears = r.source == CallSource.AUTO || !incoming
            if (clears) {
                val note = (if (incoming) "They called" else "Called back") + " · " + durationLabel(durationSec)
                markCleared(context, r, note)
            }
        }
    }

    // ------------------------------------------------------------ manual actions

    /** Returns false if that number already has an active reminder. */
    fun manualAdd(context: Context, number: String, name: String?): Boolean {
        val norm = normalizePhone(number)
        if (norm.isBlank()) return false
        if (CallStore.activeByNumber(norm) != null) return false
        val now = System.currentTimeMillis()
        val r = CallReminder(
            id = Ids.next(), number = number,
            name = name?.takeIf { it.isNotBlank() } ?: lookupName(context, number),
            source = CallSource.MANUAL, createdAt = now,
            missedCount = 0
        )
        CallStore.upsert(r)
        return true
    }

    fun startComplete(context: Context, r: CallReminder) {
        if (pendingDone.value.containsKey(r.id)) return
        val delaySec = delayFor(SettingsStore.s.value, null).coerceIn(0, 300)
        if (delaySec == 0) {
            val app = context.applicationContext
            scope.launch {
                Fx.celebrate(r.id)
                val nxt = if (r.repeatMode != "OFF") nextOccurrenceCall(r, System.currentTimeMillis()) else null
                markCleared(app, r, "Marked done")
                Ack.show(nxt?.let { "Done · returns " + formatDayTime(it) } ?: "Marked done") {
                    CallStore.get(r.id)?.takeIf { it.done }?.let { startRevive(app, it) }
                }
            }
            return
        }
        val endsAt = System.currentTimeMillis() + delaySec * 1000L
        pendingDone.value = pendingDone.value + (r.id to endsAt)
        jobs[r.id] = scope.launch {
            delay(delaySec * 1000L)
            pendingDone.value = pendingDone.value - r.id
            jobs.remove(r.id)
            Fx.celebrate(r.id)
            markCleared(context, r, "Marked done")
        }
    }

    fun cancelPending(id: Long) {
        jobs.remove(id)?.cancel()
        pendingDone.value = pendingDone.value - id
    }

    /** v1.15 item 18 (duration corrected v1.84): snooze for the CONFIGURED duration — cancels the
     *  current alert, re-fires later, and pauses the overdue chain (it restarts from the snoozed fire). */
    fun snooze(context: Context, r: CallReminder) {
        AlarmScheduler.cancelCallSnooze(context, r.id)
        AlarmScheduler.scheduleCallSnooze(context, r.id)
        // v1.80 (Q17): persist the target so it shows in "Coming up" and can be cancelled.
        val until = snoozeTargetMs(SettingsStore.s.value)
        CallStore.get(r.id)?.let { CallStore.upsert(it.copy(snoozedUntil = until)) }
        androidx.core.app.NotificationManagerCompat.from(context).cancel((r.id % 100_000).toInt() + 51_000)
        // v1.84 (N14, found while tracing): the Ack claimed "1 h" while the schedule used the
        // configured snooze — the FIFTH display-lies instance. The label now reads the setting.
        Ack.show("Snoozed · rings in " + Alerts.snoozeLabel(snoozeMinutes(SettingsStore.s.value))) {}
    }

    fun markCleared(context: Context, r: CallReminder, note: String) {
        cancelPending(r.id)
        AlarmScheduler.cancelCallSnooze(context, r.id)
        // v1.14: recurring manual calls advance to the next occurrence and rest in Done
        // until midnight of that day (full parity with item recurrence).
        val nxt = if (r.repeatMode != "OFF")
            nextOccurrenceCall(r, maxOf(System.currentTimeMillis(), r.recurAt ?: 0L)) else null
        val cleared = r.copy(done = true, doneAt = System.currentTimeMillis(), clearedNote = note, nagAt = null, recurAt = nxt ?: r.recurAt)
        CallStore.upsert(cleared)
        CalSync.syncCall(context, cleared)
        TodayWidget.refresh(context)
    }

    fun startRevive(context: Context, r: CallReminder) {
        scope.launch {
            Fx.reviveOut(r.id)
            CallStore.upsert(r.copy(done = false, doneAt = null, clearedNote = null))
            Fx.markRevived(scope, r.id)
        }
    }

    fun delete(context: Context, r: CallReminder) {
        // v1.11 soft-delete into the Recently-deleted bin.
        cancelPending(r.id)
        // v2.8 (N44, Krishna): a deleted reminder must leave NO future scheduler event — this used
        // to cancel only the snooze + quiet re-fire, leaving the recurring and overdue alarms armed.
        AlarmScheduler.cancelForCall(context, r.id)
        CalSync.removeCall(context, r)
        CallStore.upsert(r.copy(deletedAt = System.currentTimeMillis()))
    }

    fun deleteForever(context: Context, r: CallReminder) {
        cancelPending(r.id)
        AlarmScheduler.cancelCallSnooze(context, r.id)
        CallStore.delete(r.id)
    }

    /** v1.5: undo for instant deletes — puts the reminder back exactly as it was. */
    fun restore(context: Context, r: CallReminder) {
        CallStore.upsert(r)
    }

    // ------------------------------------------------------------ nag


    /** v1.8: Done pressed on a ladder notification. */
    fun completeFromNotification(context: Context, id: Long) {
        val r = CallStore.calls.value.firstOrNull { it.id == id } ?: return
        if (r.done) return
        // v1.15: route through markCleared so recurring cycles advance correctly.
        markCleared(context, r, "Marked done")
    }


    // ------------------------------------------------------------ lookups

    fun lookupName(context: Context, number: String): String? {
        if (!hasContacts(context)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    fun recentCalls(context: Context, max: Int = 30): List<RecentCall> {
        if (!hasCallLog(context)) return emptyList()
        val out = LinkedHashMap<String, RecentCall>()
        val proj = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE, CallLog.Calls.TYPE
        )
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC"
            )?.use { cur ->
                val iNum = cur.getColumnIndex(CallLog.Calls.NUMBER)
                val iName = cur.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iDate = cur.getColumnIndex(CallLog.Calls.DATE)
                val iType = cur.getColumnIndex(CallLog.Calls.TYPE)
                var rows = 0
                while (cur.moveToNext() && rows < 200 && out.size < max) {
                    rows++
                    val number = cur.getString(iNum) ?: continue
                    if (number.isBlank()) continue
                    val norm = normalizePhone(number)
                    if (norm.isBlank() || out.containsKey(norm)) continue
                    out[norm] = RecentCall(
                        number = number,
                        name = cur.getString(iName),
                        date = cur.getLong(iDate),
                        type = cur.getInt(iType)
                    )
                }
            }
        }
        return out.values.toList()
    }

    /** Reads a contact picked via ActivityResultContracts.PickContact. */
    fun readPickedContact(context: Context, contactUri: Uri): Pair<String?, String?> {
        var name: String? = null
        var number: String? = null
        runCatching {
            context.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )?.use { cur ->
                if (cur.moveToFirst()) {
                    val id = cur.getString(0)
                    name = cur.getString(1)
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(id), null
                    )?.use { pc -> if (pc.moveToFirst()) number = pc.getString(0) }
                }
            }
        }
        return name to number
    }

    data class ContactInfo(val first: String?, val last: String?, val company: String?)

    /** v1.67: structured name + company from DEVICE contacts. Null-safe; never blocks an add. */
    fun lookupContact(context: Context, number: String): ContactInfo? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cid = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
        var first: String? = null; var last: String? = null; var company: String? = null
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                ContactsContract.CommonDataKinds.Organization.COMPANY
            ),
            ContactsContract.Data.CONTACT_ID + " = ? AND " + ContactsContract.Data.MIMETYPE + " IN (?, ?)",
            arrayOf(
                cid.toString(),
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
            ), null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    first = c.getString(1); last = c.getString(2)
                } else company = c.getString(3)
            }
        }
        ContactInfo(first, last, company)
    }.getOrElse { e -> Logger.e(context, "CALLS", e, "contact enrich failed (non-fatal)"); null }

    /** v1.68: the ONE missed-call detection alert, on the reminder's own letter. */
    fun fireDetection(context: Context, r: CallReminder, force: String? = null) {
        val s = SettingsStore.s.value
        if (!alertsEnabledFor(null, s)) { Logger.e(context, "CALLS", null, "detection suppressed — alerts off"); return }
        when (force ?: resolveCallTypes(r, s)) {
            "A" -> AlarmService.start(context, AlarmService.MODE_CALL, r.id, "call")
            "R" -> AlarmService.start(context, AlarmService.MODE_CALL, r.id, "call", ringOnly = true)
            else -> Alerts.fireCallNotification(context, r)
        }
    }

}

// ================================================================ contact actions (v1.4)

private fun firstNumberFor(context: android.content.Context, contactId: String): String? {
    runCatching {
        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )?.use { cur -> if (cur.moveToNext()) return cur.getString(0) }
    }
    return null
}

object ContactSaver {
    /** v1.19 item 7: the account the phonebook itself syncs with — majority owner
     *  of existing contacts; several accounts → most common; none → device-local. */
    fun phonebookAccount(context: Context): Pair<String, String>? {
        val counts = HashMap<Pair<String, String>, Int>()
        runCatching {
            context.contentResolver.query(
                android.provider.ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.RawContacts.ACCOUNT_NAME,
                    android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE
                ),
                "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf("com.google"), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    val type = cur.getString(1) ?: continue
                    counts[name to type] = (counts[name to type] ?: 0) + 1
                }
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun ensureGroup(context: Context, title: String, account: Pair<String, String>?): Long? {
        runCatching {
            val sel = StringBuilder("${android.provider.ContactsContract.Groups.TITLE} = ?")
            val args = mutableListOf(title)
            if (account != null) {
                sel.append(" AND ${android.provider.ContactsContract.Groups.ACCOUNT_NAME} = ? AND ${android.provider.ContactsContract.Groups.ACCOUNT_TYPE} = ?")
                args.add(account.first); args.add(account.second)
            }
            context.contentResolver.query(
                android.provider.ContactsContract.Groups.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Groups._ID), sel.toString(), args.toTypedArray(), null
            )?.use { cur -> if (cur.moveToFirst()) return cur.getLong(0) }
        }
        return runCatching {
            val v = android.content.ContentValues().apply {
                put(android.provider.ContactsContract.Groups.TITLE, title)
                put(android.provider.ContactsContract.Groups.GROUP_VISIBLE, 1)
                if (account != null) {
                    put(android.provider.ContactsContract.Groups.ACCOUNT_NAME, account.first)
                    put(android.provider.ContactsContract.Groups.ACCOUNT_TYPE, account.second)
                }
            }
            context.contentResolver.insert(android.provider.ContactsContract.Groups.CONTENT_URI, v)
                ?.let { android.content.ContentUris.parseId(it) }
        }.getOrNull()
    }

    /** Creates the contact (name + number + label group); returns the display name, or null on failure. */
    fun save(context: Context, number: String, first: String, last: String, company: String, labels: Set<String>): String? {
        val account = phonebookAccount(context)
        // v1.26 item 5: resolve every chosen label to a group; one bad group must not abort the save.
        val groupIds = labels.mapNotNull { lb ->
            runCatching { ensureGroup(context, lb, account) }
                .onFailure { Logger.e(context, "contactgroup", it) }
                .getOrNull()
        }
        val full = listOf(first.trim(), last.trim()).filter { it.isNotBlank() }.joinToString(" ")
        if (full.isBlank()) return null
        return runCatching {
            val ops = ArrayList<android.content.ContentProviderOperation>()
            ops.add(
                android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, account?.first)
                    .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, account?.second)
                    .build()
            )
            ops.add(
                android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(android.provider.ContactsContract.Data.MIMETYPE,
                        android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, first.trim())
                    .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, last.trim())
                    .build()
            )
            // v1.26 item 5: organisation / company row, written only when a company was entered.
            if (company.trim().isNotBlank()) ops.add(
                android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(android.provider.ContactsContract.Data.MIMETYPE,
                        android.provider.ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(android.provider.ContactsContract.CommonDataKinds.Organization.COMPANY, company.trim())
                    .build()
            )
            ops.add(
                android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(android.provider.ContactsContract.Data.MIMETYPE,
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE,
                        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
            // v1.26 item 5: one membership per resolved group; an empty set attaches none.
            groupIds.forEach { gid ->
                ops.add(
                    android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(android.provider.ContactsContract.Data.MIMETYPE,
                            android.provider.ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                        .withValue(android.provider.ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, gid)
                        .build()
                )
            }
            context.contentResolver.applyBatch(android.provider.ContactsContract.AUTHORITY, ops)
            full
        }.onFailure { Logger.e(context, "contactsave", it) }.getOrNull()
    }
}

/** v1.26 item 5: pure label-set logic for the Save Contact dialog — JVM-testable, no Android deps. */
object CallLabels {
    fun isDeleteAfter(s: String) = s.startsWith("Delete After")
    fun toggle(set: Set<String>, lb: String): Set<String> = if (lb in set) set - lb else set + lb
    fun setDeleteAfter(set: Set<String>, delLabel: String): Set<String> =
        set.filterNot { isDeleteAfter(it) }.toSet() + delLabel
    fun clearDeleteAfter(set: Set<String>): Set<String> = set.filterNot { isDeleteAfter(it) }.toSet()
}

object CallActions {

    fun dial(context: Context, number: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun sms(context: Context, number: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // v1.79 (N4): the country code is a setting, not a hardcoded 91. A number already carrying a
    // code is left alone; only a bare local number gets one prepended.
    fun waNumber(number: String, cc: String = SettingsStore.s.value.defaultCountryCode): String {
        return waDigits(number, cc)   // v2.10 (N6 pt2): pure, tested
    }

    /** v1.79 (N4): [text] pre-fills the message; the user still taps send inside WhatsApp. */
    fun waChat(context: Context, number: String, text: String? = null) {
        val wa = waNumber(number)
        if (wa.length < 8) {
            Logger.e(context, "WA", null, "number '$number' has no usable digits \u2014 wa.me skipped")
            toast(context, "That number can't be opened in WhatsApp")
            return
        }
        val url = "https://wa.me/$wa" +
            (text?.takeIf { it.isNotBlank() }?.let { "?text=" + Uri.encode(it) } ?: "")
        val ok = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Logger.e(context, "WA", it, "wa.me intent failed \u2014 WhatsApp may not be installed") }
            .isSuccess
        if (!ok) toast(context, "Couldn't open WhatsApp")
    }

    private fun toast(context: Context, msg: String) {
        runCatching { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }
}

// ================================================================ phone-state receiver

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        Stores.init(context)
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            // The call log lags the state change; wait a moment, then process the delta.
            // v1.52: some OEMs write the call-log row well after IDLE. Scan twice inside the
            // ~10s goAsync budget instead of a single tight 2.2s guess.
            // v2.6.2 (N41): the ONLY automatic trigger — a call just ended. The retry ladder covers
            // OEMs that write the row late; every sweep is bounded to the 5-minute live window.
            val pending = goAsync()
            Thread {
                try {
                    Thread.sleep(3000)
                    CallEngine.process(context)
                    CALL_RETRY_DELAYS_MS.forEach { d ->
                        if (d <= 60_000L) { Thread.sleep(minOf(d, 5_000L)); CallEngine.process(context) }
                    }
                } catch (e: Exception) {
                    Logger.e(context, "CALLSCAN", e, "receiver scan failed")
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
