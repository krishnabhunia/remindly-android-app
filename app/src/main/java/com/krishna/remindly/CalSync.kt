package com.krishna.remindly

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * v1.15 item 6 — device-calendar integration.
 *
 * v1.46 (Feature 4a): Remindly is now **read-only** toward the calendar. The write path
 * (creating/updating/deleting events) is DISABLED and its UI is HIDDEN, but the code is kept
 * intact so it can be brought back. **To re-enable writing: set WRITE_ENABLED = true** — every
 * write entry point and the hidden settings UI are gated on this one flag.
 * Reading (see readNext) powers the "From Calendar" view and stays active.
 */
object CalSync {

    /** v1.46 Feature 4a — master switch for the calendar WRITE path. Flip to true to restore it. */
    const val WRITE_ENABLED = false

    fun hasPerms(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** v1.46: reading only needs READ_CALENDAR — the read-only view must not demand write access. */
    fun hasReadPerm(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun enabled(context: Context): Boolean =
        WRITE_ENABLED && SettingsStore.s.value.calendarSync && hasPerms(context)

    data class CalInfo(val id: Long, val name: String, val account: String, val type: String = "")

    /**
     * v1.55: projection and row-reader live together. They drifted apart in v1.50 — the projection
     * gained ACCOUNT_TYPE but the row was still built from three columns, so every CalInfo.type was
     * blank. That silently disabled requestSync, and new Google Calendar events never arrived.
     */
    internal val CAL_PROJECTION = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.ACCOUNT_TYPE
    )

    internal fun calInfoFrom(cur: android.database.Cursor): CalInfo = CalInfo(
        cur.getLong(0),
        cur.getString(1) ?: "?",
        cur.getString(2) ?: "?",
        cur.getString(3) ?: ""            // the column that was being dropped
    )

    /** v1.19 item 1: every calendar the user may write into. */
    fun writableCalendars(context: Context): List<CalInfo> {
        if (!hasPerms(context)) return emptyList()
        val out = mutableListOf<CalInfo>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
                ), null, null, CalendarContract.Calendars.ACCOUNT_NAME
            )?.use { cur ->
                while (cur.moveToNext()) {
                    // v1.55: column 3 here is CALENDAR_ACCESS_LEVEL (an Int), NOT ACCOUNT_TYPE.
                    // A stray v1.50 edit read it as a String and would have stored "700" as the
                    // account type. Type is left at its default; only readableCalendars supplies it.
                    if (cur.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                        out.add(CalInfo(cur.getLong(0), cur.getString(1) ?: "?", cur.getString(2) ?: "?"))
                }
            }
        }
        return out
    }

    /**
     * v1.46 Feature 4a: every calendar the user can READ. Since Remindly is read-only now, the
     * picker must not exclude calendars the user can't write to (subscribed/holiday calendars).
     */
    fun readableCalendars(context: Context): List<CalInfo> {
        if (!hasReadPerm(context)) return emptyList()
        val out = mutableListOf<CalInfo>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                CAL_PROJECTION, null, null, CalendarContract.Calendars.ACCOUNT_NAME
            )?.use { cur ->
                while (cur.moveToNext()) {
                    out.add(calInfoFrom(cur))
                }
            }
        }.onFailure { Logger.e(context, "CALREAD", it, "list readable calendars") }
        // v1.48: record what the provider actually returned. A missing account is missing from
        // Android itself (calendar sync off for it) — nothing here filters by account.
        Logger.e(context, "CALREAD", null, "provider returned ${out.size} calendars across ${out.map { it.account }.distinct().size} accounts")
        return out
    }

    fun targetInfo(context: Context): CalInfo? {
        val id = targetCalendarId(context) ?: return null
        // v1.46: resolve against readable calendars (read-only mode).
        return readableCalendars(context).firstOrNull { it.id == id }
    }

    /**
     * v1.49 (Option B): one-time upgrade of the legacy single-calendar pick. The stored
     * calendarTargetId's ACCOUNT is adopted and ALL of that account's calendars are selected.
     * Needs a provider query, so it runs lazily rather than in the pure settings migration.
     * If the old calendar no longer exists we leave the account unset and let the user pick.
     */
    fun migrateSelectionIfNeeded(context: Context) {
        val s = SettingsStore.s.value
        if (s.calendarAccount != null || s.calendarTargetId <= 0L) return
        val old = readableCalendars(context).firstOrNull { it.id == s.calendarTargetId }
        if (old == null) {
            Logger.e(context, "CALREAD", null, "legacy calendar ${s.calendarTargetId} not found — asking user to pick")
            return
        }
        SettingsStore.update { it.copy(calendarAccount = old.account, calendarIds = emptySet()) }
        Logger.e(context, "CALREAD", null, "migrated to account ${old.account} with ALL calendars selected")
    }

    /** v1.21 item 3b: strictly the calendar Krishna picked — nothing is chosen for him. */
    fun targetCalendarId(context: Context): Long? {
        if (!hasReadPerm(context)) return null
        // v1.21 item 3b: nothing is auto-selected — no chosen calendar means no sync.
        val chosen = SettingsStore.s.value.calendarTargetId
        return if (chosen > 0L) chosen else null
    }

    /**
     * v1.45 Feature 4c: READ-ONLY import — the events on the chosen calendar for the next [days].
     * Remindly never edits these; they only surface in the "From Calendar" view. Empty if no
     * permission or no chosen calendar. Device-only (needs a real calendar provider).
     */
    /**
     * v1.50: the same window read BACKWARDS — powers the Done filter, which previously showed the
     * very same future events as Active. Newest-first is applied by the caller's grouping.
     */
    /**
     * v1.50: re-querying the provider only returns what Android has ALREADY synced, so a plain
     * refresh can silently show stale data. Ask the platform to sync the chosen account's calendar
     * first; the re-read then picks up whatever arrives. Best-effort — never throws.
     */
    fun requestAccountSync(context: Context) {
        runCatching {
            val acct = SettingsStore.s.value.calendarAccount ?: run {
                Logger.e(context, "CALREAD", null, "sync skipped: no account chosen")
                return
            }
            val rows = readableCalendars(context).filter { it.account.equals(acct, ignoreCase = true) }
            // v1.55: if the provider gives no account type, assume Google rather than doing nothing.
            val type = rows.firstOrNull { it.type.isNotBlank() }?.type ?: "com.google"
            val bundle = android.os.Bundle().apply {
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            android.content.ContentResolver.requestSync(
                android.accounts.Account(acct, type), CalendarContract.AUTHORITY, bundle
            )
            Logger.e(context, "CALREAD", null, "sync requested for $acct (type=$type, ${rows.size} calendars)")
        }.onFailure { Logger.e(context, "CALREAD", it, "requestSync failed") }
    }

    fun readPast(context: Context, days: Int): List<CalEvent> = readWindow(context, days, past = true)

    fun readNext(context: Context, days: Int): List<CalEvent> = readWindow(context, days, past = false)

    private fun readWindow(context: Context, days: Int, past: Boolean): List<CalEvent> {
        if (!hasReadPerm(context)) return emptyList()
        migrateSelectionIfNeeded(context)
        // v1.49: read every calendar the user selected (empty selection = all in the account).
        val ids = selectedCalendarIds(SettingsStore.s.value, readableCalendars(context))
        if (ids.isEmpty()) return emptyList()
        // v1.53: day-anchored bounds — today belongs to Active, whatever the hour. Using `now`
        // hid this morning's meetings and every all-day event from the Active view.
        val (from, end) = calendarWindowBounds(System.currentTimeMillis(), days, past)
        val out = mutableListOf<CalEvent>()
        runCatching {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, from)
            ContentUris.appendId(builder, end)
            context.contentResolver.query(
                builder.build(),
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY
                ),
                "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",")})",
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    out.add(
                        CalEvent(
                            id = cur.getLong(0),
                            title = cur.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)",
                            start = cur.getLong(2),
                            end = cur.getLong(3),
                            allDay = cur.getInt(4) == 1
                        )
                    )
                }
            }
        }.onFailure { Logger.e(context, "CALREAD", it, "read calendar instances") }
        // v1.53: record the resolved window so "why is this empty?" is answerable from Error Logs.
        Logger.e(
            context, "CALREAD", null,
            "window ${if (past) "PAST" else "ACTIVE"} $from..$end over ${ids.size} calendar(s) -> ${out.size} events"
        )
        return out
    }

    /** Clean repeat patterns export as real recurring events; complex ones sync single-next. */
    fun rruleFor(item: Item): String? = when (item.repeatMode) {
        "DAILY" -> "FREQ=DAILY"
        "WEEKLY" -> {
            val names = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
            val by = item.repeatDays.filter { it in 1..7 }.sorted().joinToString(",") { names[it - 1] }
            if (by.isBlank()) null else "FREQ=WEEKLY;BYDAY=$by"
        }
        "MONTHLY_DAY" -> item.repeatDays.filter { it in 1..31 }.takeIf { it.size == 1 }
            ?.let { "FREQ=MONTHLY;BYMONTHDAY=${it.first()}" }
        "YEARLY" -> "FREQ=YEARLY"
        else -> null // MONTHLY multi-set / MONTHLY_ORD / EVERY_N / Q / H / SPACED → single-next
    }

    private fun tabOn(item: Item): Boolean = when (item.tab) {
        Tab.SHOP -> SettingsStore.s.value.calSyncShop
        Tab.LEARN -> SettingsStore.s.value.calSyncLearn
        else -> SettingsStore.s.value.calSyncTasks
    }

    fun syncItem(context: Context, item: Item) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        if (!enabled(context)) return
        if (!tabOn(item)) { removeItem(context, item); return }
        val live = !item.done && item.deletedAt == null && item.dueAt != null
        if (!live) { removeItem(context, item); return }
        val calId = targetCalendarId(context) ?: return
        val begin = item.dueAt!!
        val allDay = !item.dueHasTime
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DESCRIPTION, "Remindly · ${item.tab.title}" + (item.notes?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""))
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.DTSTART, startOfDayMs(begin))
                put(CalendarContract.Events.DTEND, startOfDayMs(begin) + 86_400_000L)
            } else {
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.DTSTART, begin)
                val rr = rruleFor(item)
                if (rr != null) put(CalendarContract.Events.DURATION, "PT30M")
                else put(CalendarContract.Events.DTEND, begin + 30 * 60_000L)
            }
            val rr = rruleFor(item)
            if (rr != null && !allDay) put(CalendarContract.Events.RRULE, rr)
        }
        runCatching {
            val existing = item.calEventId
            if (existing != null) {
                val rows = context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), v, null, null
                )
                if (rows > 0) return
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.let { uri ->
                ItemStore.upsert(ItemStore.get(item.id)?.copy(calEventId = ContentUris.parseId(uri)) ?: return@let)
            }
        }.onFailure { Logger.e(context, "calsync", it) }
    }

    fun removeItem(context: Context, item: Item) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        val id = item.calEventId ?: return
        if (!hasPerms(context)) return
        runCatching {
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null
            )
        }
        ItemStore.get(item.id)?.let { cur -> if (cur.calEventId != null) ItemStore.upsert(cur.copy(calEventId = null)) }
    }

    // ---------------- v1.19: recurring calls (opt-in) ----------------

    fun syncCall(context: Context, r: CallReminder) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        if (!enabled(context) || !SettingsStore.s.value.calSyncCalls) return
        val live = !r.done || r.repeatMode != "OFF"
        val at = r.recurAt
        if (r.deletedAt != null || r.repeatMode == "OFF" || at == null || !live) { removeCall(context, r); return }
        val calId = targetCalendarId(context) ?: return
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, "Call: " + r.display)
            put(CalendarContract.Events.DESCRIPTION, "Remindly · Calls" + (r.note?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""))
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.DTSTART, at)
            put(CalendarContract.Events.DTEND, at + 30 * 60_000L)
        }
        runCatching {
            val existing = r.calEventId
            if (existing != null) {
                val rows = context.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), v, null, null
                )
                if (rows > 0) return
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.let { uri ->
                CallStore.get(r.id)?.let { cur -> CallStore.upsert(cur.copy(calEventId = ContentUris.parseId(uri))) }
            }
        }.onFailure { Logger.e(context, "calsync", it) }
    }

    fun removeCall(context: Context, r: CallReminder) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        val id = r.calEventId ?: return
        if (!hasPerms(context)) return
        runCatching {
            context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        }
        CallStore.get(r.id)?.let { cur -> if (cur.calEventId != null) CallStore.upsert(cur.copy(calEventId = null)) }
    }

    /** Toggle ON → write every live due-dated item (+ recurring calls when opted in). */
    fun backfill(context: Context) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        if (!enabled(context)) return
        ItemStore.items.value.filter { !it.done && it.deletedAt == null && it.dueAt != null }
            .forEach { syncItem(context, it) }
        if (SettingsStore.s.value.calSyncCalls)
            CallStore.calls.value.filter { it.deletedAt == null && it.repeatMode != "OFF" && it.recurAt != null }
                .forEach { syncCall(context, it) }
    }

    /** Toggle OFF → delete every Remindly-created event and clear the mappings. */
    fun teardown(context: Context) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        if (!hasPerms(context)) return
        ItemStore.items.value.filter { it.calEventId != null }.forEach { removeItem(context, it) }
        CallStore.calls.value.filter { it.calEventId != null }.forEach { removeCall(context, it) }
    }

    /** v1.20 item 8: drop events belonging to tabs that are now switched off
     *  (covers the schema-v15 migration that turns every tab's sync off). */
    fun pruneDisabled(context: Context) {
        if (!hasPerms(context)) return
        val s = SettingsStore.s.value
        ItemStore.items.value.filter { it.calEventId != null && !tabOn(it) }.forEach { removeItem(context, it) }
        if (!s.calSyncCalls) CallStore.calls.value.filter { it.calEventId != null }.forEach { removeCall(context, it) }
    }

    /** v1.19: scoped moves for the per-tab chips and target changes. */
    fun teardownTab(context: Context, tab: Tab) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        ItemStore.items.value.filter { it.tab == tab && it.calEventId != null }.forEach { removeItem(context, it) }
    }
    fun backfillTab(context: Context, tab: Tab) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        ItemStore.items.value.filter { it.tab == tab && !it.done && it.deletedAt == null && it.dueAt != null }
            .forEach { syncItem(context, it) }
    }
    fun teardownCalls(context: Context) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        CallStore.calls.value.filter { it.calEventId != null }.forEach { removeCall(context, it) }
    }
    fun backfillCalls(context: Context) {
        if (!WRITE_ENABLED) return   // v1.46 Feature 4a: calendar writing disabled
        CallStore.calls.value.filter { it.deletedAt == null && it.repeatMode != "OFF" && it.recurAt != null }
            .forEach { syncCall(context, it) }
    }
}
