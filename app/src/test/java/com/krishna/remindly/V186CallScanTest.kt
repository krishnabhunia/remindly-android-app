package com.krishna.remindly

import android.Manifest
import android.app.AlarmManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.86 (N26) — the OEM call-log flood, end to end against the REAL CallEngine.processNow and an
 * in-memory CallLog provider that honours exactly what production sends: selection
 * "DATE >= ?" and DATE ASC/DESC ordering. The retired `_ID`/baseline model is gone; these tests
 * pin the replacement: install-time fence, 48 h lookback, row-key dedupe ring, 200-row clamp,
 * and the upgrade purge.
 *
 * NEGATIVE CONTROL (run + recorded in the BACKLOG): CallEngine.installFence hardwired to `0L`
 * makes pre_install_history_never_ingests and the migration purge test FAIL — the fence, not
 * the harness, is what holds the flood back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V186CallScanTest {

    class FakeCallLog : ContentProvider() {
        companion object {
            data class R(val id: Long, val number: String, val type: Int, val date: Long, val dur: Long, val name: String?)
            val rows = mutableListOf<R>()
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           args: Array<out String>?, sort: String?): Cursor {
            var data = rows.toList()
            if (selection?.contains("date >= ?") == true || selection?.contains("DATE >= ?") == true ||
                selection?.contains("${CallLog.Calls.DATE} >= ?") == true) {
                val min = args!![0].toLong()
                data = data.filter { it.date >= min }
            }
            data = if (sort?.contains("DESC") == true) data.sortedByDescending { it.date }
                   else data.sortedBy { it.date }
            val proj = projection ?: arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME)
            val c = MatrixCursor(proj)
            data.forEach { r ->
                c.addRow(proj.map { col -> when (col) {
                    CallLog.Calls._ID -> r.id
                    CallLog.Calls.NUMBER -> r.number
                    CallLog.Calls.TYPE -> r.type
                    CallLog.Calls.DATE -> r.date
                    CallLog.Calls.DURATION -> r.dur
                    CallLog.Calls.CACHED_NAME -> r.name
                    else -> null
                } })
            }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private lateinit var context: Context
    private val t0 = System.currentTimeMillis()
    private val fence = t0 - 10L * 86_400_000               // installed 10 days ago
    private fun prefs() = context.getSharedPreferences("call_engine", Context.MODE_PRIVATE)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_CALL_LOG)
        Robolectric.setupContentProvider(FakeCallLog::class.java, CallLog.AUTHORITY)
        Stores.init(context)
        FakeCallLog.rows.clear()
        prefs().edit().clear().commit()
        CallStore.replaceAll(emptyList())
        CallEngine.fenceOverride = fence
        // v2.6.2 (N41): the engine anchors ONCE on first touch and thereafter reads only the live
        // window (max(anchor, now − 5 min)). These scans exercise an app that has been installed a
        // while, so the anchor sits at the install date; fresh-install behaviour has its own tests.
        prefs().edit().putLong("lastSeen", fence).commit()
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.clear()
    }

    private fun missedRow(id: Long, number: String, date: Long) =
        FakeCallLog.Companion.R(id, number, CallLog.Calls.MISSED_TYPE, date, 0, null)

    private fun autoCalls() = CallStore.calls.value.filter { it.source == CallSource.AUTO && it.deletedAt == null }

    // ---------------- the flood itself ----------------

    @Test fun pre_install_history_never_ingests() {
        // Redmi scenario: 300 historical missed calls BEFORE installation.
        prefs().edit().clear().commit()                       // true first install: no watermark
        repeat(300) { i -> FakeCallLog.rows.add(missedRow(i.toLong(), "9$i", fence - (i + 1) * 60_000L)) }
        CallEngine.processNow(context)          // v2.6.2: anchors at NOW and reads nothing
        CallEngine.processNow(context)
        assertTrue("pre-install rows must never become reminders", autoCalls().isEmpty())
    }

    @Test fun post_install_history_never_ingests_either() {
        // v2.6.2 (N41, Krishna): the Vivo/Redmi flood was POST-install history — hundreds of rows
        // written between installing and granting the permission. History is now ignored outright,
        // whichever side of the install date it sits on.
        prefs().edit().clear().commit()
        repeat(300) { i -> FakeCallLog.rows.add(missedRow(i.toLong(), "7$i", fence + (i + 1) * 60_000L)) }
        CallEngine.processNow(context)
        CallEngine.processNow(context)
        assertTrue("post-install history must never become reminders either", autoCalls().isEmpty())
    }

    @Test fun only_a_call_inside_the_live_window_ingests() {
        // v2.6.2 (N41) replaces the N26 fence rule: the log is read only to resolve the row the OS
        // just wrote, so a fresh row lands and everything older is ignored as history.
        FakeCallLog.rows.add(missedRow(1, "9001", t0 - 60_000))                 // a minute ago ✓
        FakeCallLog.rows.add(missedRow(2, "9002", t0 - 6 * 60_000))             // 6 min: history ✗
        FakeCallLog.rows.add(missedRow(3, "9003", fence + 3_600_000))           // days old: history ✗
        CallEngine.processNow(context)
        assertEquals(listOf("9001"), autoCalls().map { it.number })
    }

    @Test fun renumbered_rows_do_not_duplicate_or_reingest() {
        FakeCallLog.rows.add(missedRow(10, "9111", t0 - 60_000))
        CallEngine.processNow(context)
        assertEquals(1, autoCalls().size)
        val stamp = autoCalls()[0].updatedAt
        // OEM renumber: same call, new _ID — the row key (number|date|type) must hold the line.
        FakeCallLog.rows.clear()
        FakeCallLog.rows.add(missedRow(9910, "9111", t0 - 60_000))
        CallEngine.processNow(context)
        assertEquals(1, autoCalls().size)
        assertEquals("a dedupe skip must not touch the record", stamp, autoCalls()[0].updatedAt)
    }

    @Test fun late_written_rows_inside_the_live_window_land_beyond_are_lost_by_design() {
        // The OEM writes the row a little after the call ends: inside the 5-minute window it is
        // still caught (the receiver retries); beyond it, the call is accepted-as-lost — the
        // stated trade-off of "history must be completely ignored".
        FakeCallLog.rows.add(missedRow(9, "9000", t0 - 30_000))
        CallEngine.processNow(context)
        FakeCallLog.rows.add(missedRow(1, "9004", t0 - 4 * 60_000))             // late, still live ✓
        FakeCallLog.rows.add(missedRow(2, "9009", t0 - 9 * 60_000))             // too late ✗
        CallEngine.processNow(context)
        assertEquals(listOf("9000", "9004"), autoCalls().map { it.number }.sorted())
        assertTrue(autoCalls().none { it.number == "9009" })
    }

    @Test fun a_scan_never_processes_more_than_the_cap() {
        // 500 rows inside the live window (a provider dump) — the cap still holds and, per the
        // v2.6.2 storm guard, the whole sweep raises ONE alert rather than 200.
        repeat(500) { i -> FakeCallLog.rows.add(missedRow(i.toLong(), "8$i", t0 - 240_000 + i * 400L)) }
        CallEngine.processNow(context)
        assertEquals(SCAN_ROW_CAP, autoCalls().size)
        // and the NEWEST rows won the clamp
        assertTrue(autoCalls().any { it.number == "8499" })
    }

    @Test fun two_scans_are_idempotent() {
        FakeCallLog.rows.add(missedRow(1, "9222", t0 - 45_000))
        CallEngine.processNow(context)
        CallEngine.processNow(context)
        assertEquals(1, autoCalls().size)
        assertEquals(1, autoCalls()[0].missedCount)
    }

    // ---------------- the upgrade migration ----------------

    @Test fun upgrade_purges_pre_install_junk_cancels_alarms_and_seeds_the_ring() {
        prefs().edit().clear().putLong("lastId", 999L).commit()          // v1.85 state (no watermark)
        val junk = CallReminder(id = 71, number = "7100", source = CallSource.AUTO, lastMissedAt = fence - 5_000)
        val manual = CallReminder(id = 72, number = "7200", source = CallSource.MANUAL, lastMissedAt = fence - 5_000)
        CallStore.replaceAll(listOf(junk, manual))
        val am = context.getSystemService(AlarmManager::class.java)
        val before = shadowOf(am).scheduledAlarms.size
        AlarmScheduler.scheduleAt(context, t0 + 600_000, AlarmScheduler.TYPE_CSNOOZE, 71)
        FakeCallLog.rows.add(missedRow(1, "9333", t0 - 60_000))         // recent, pre-handled by old model
        assertEquals(before + 1, shadowOf(am).scheduledAlarms.size)

        CallEngine.processNow(context)                                    // migration pass

        assertTrue("junk auto reminder hard-purged", CallStore.calls.value.none { it.id == 71L })
        assertTrue("manual reminder survives", CallStore.calls.value.any { it.id == 72L })
        assertEquals("its shadow alarm died with it", before, shadowOf(am).scheduledAlarms.size)
        assertTrue("lastSeen adopted", prefs().contains("lastSeen"))
        assertTrue("lastId retired", !prefs().contains("lastId"))
        assertTrue("ring seeded", !prefs().getString("ring", "").isNullOrEmpty())

        CallEngine.processNow(context)                                    // first real scan
        assertTrue("the seeded ring blocks re-ingest of the old model's rows",
            autoCalls().none { it.number == "9333" })
    }

    @Test fun fresh_install_ingests_nothing_and_anchors_at_now() {
        // v2.6.2 (N41): THE fix for Krishna's Vivo/Redmi report — the very first pass must create
        // no reminders and raise no alerts, whatever the log holds; it only writes the watermark.
        prefs().edit().clear().commit()
        FakeCallLog.rows.add(missedRow(1, "9444", fence + 86_400_000))
        FakeCallLog.rows.add(missedRow(2, "9445", t0 - 30_000))
        CallEngine.processNow(context)
        assertTrue("first pass must ingest nothing", autoCalls().isEmpty())
        val anchored = prefs().getLong("lastSeen", 0L)
        assertTrue("watermark anchored at ~now, never at the install date", anchored >= t0 - 5_000)
    }
}
