package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.86 — N24/N25 (schedule kind), N18 (calendar toggle words), N20 (auto-roll missed repeats),
 * N26 (call-scan window + pre-install purge), N11 (minute labels — see V156Test, fixed there).
 *
 * NEGATIVE CONTROL (recorded in the BACKLOG): with CallEngine.installFence temporarily returning
 * 0L, the V186CallScanTest fence suite fails (pre-install rows flood in) — proving the tests
 * guard the fix, not the plumbing.
 */
class V186Test {

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun item(
        id: Long = 1, tab: Tab = Tab.TASKS, title: String = "t",
        dueAt: Long? = null, dueHasTime: Boolean = true, repeatMode: String = "OFF",
        repeatDays: List<Int> = emptyList(), snoozedUntil: Long? = null,
        alertType: String = "N", missedAt: List<Long> = emptyList(),
        done: Boolean = false, deletedAt: Long? = null, repeatCount: Int? = null, repeatDone: Int = 0
    ) = Item(
        id = id, tab = tab, title = title, dueAt = dueAt, dueHasTime = dueHasTime,
        repeatMode = repeatMode, repeatDays = repeatDays, snoozedUntil = snoozedUntil,
        alertType = alertType, missedAt = missedAt, done = done, deletedAt = deletedAt,
        repeatCount = repeatCount, repeatDone = repeatDone
    )

    // ---------------- N24: seed rule + clearSchedule ----------------

    @Test fun sched_kind_of_states_the_items_truth() {
        assertEquals(SchedKind.REPEAT, schedKindOf(item(repeatMode = "DAILY", dueAt = at(2026, 8, 9))))
        assertEquals(SchedKind.NONE, schedKindOf(item(dueAt = null)))
        assertEquals(SchedKind.ONCE, schedKindOf(item(dueAt = at(2026, 8, 9))))
    }

    @Test fun clear_schedule_zeroes_the_schedule_and_nothing_else() {
        val loud = item(
            dueAt = at(2026, 8, 9, 20), repeatMode = "WEEKLY", repeatDays = listOf(2),
            snoozedUntil = at(2026, 8, 9, 21), alertType = "A",
            missedAt = listOf(at(2026, 8, 2, 20)), repeatCount = 5, repeatDone = 2
        ).copy(returnAt = at(2026, 9, 1), expiryAt = at(2026, 9, 5))
        val c = clearSchedule(loud)
        assertNull(c.dueAt); assertFalse(c.dueHasTime); assertNull(c.snoozedUntil)
        assertEquals("OFF", c.repeatMode); assertTrue(c.repeatDays.isEmpty())
        assertNull(c.repeatCount); assertEquals(0, c.repeatDone)
        // preserved: type, history, silent Shop-lapse return, expiry
        assertEquals("A", c.alertType)
        assertEquals(1, c.missedAt.size)
        assertEquals(loud.returnAt, c.returnAt)
        assertEquals(loud.expiryAt, c.expiryAt)
    }

    // ---------------- N25: resolver + Shop clamp + corrupt fallback ----------------

    @Test fun sched_default_resolver_matrix() {
        val g = AppSettings(schedDefault = "REPEAT")
        assertEquals(SchedKind.REPEAT, schedDefaultFor(g, Tab.TASKS))               // inherit
        assertEquals(SchedKind.NONE, schedDefaultFor(g.copy(tasksSchedDefault = "NONE"), Tab.TASKS))
        assertEquals(SchedKind.ONCE, schedDefaultFor(g.copy(learnSchedDefault = "ONCE"), Tab.LEARN))
    }

    @Test fun shop_clamps_none_to_once_everywhere() {
        assertEquals(SchedKind.ONCE, schedDefaultFor(AppSettings(schedDefault = "NONE"), Tab.SHOP))
        assertEquals(SchedKind.ONCE,
            schedDefaultFor(AppSettings(shopSchedDefault = "NONE"), Tab.SHOP))
    }

    @Test fun corrupt_default_falls_back_to_once_not_a_lie() {
        // The N10 chip-lie class: an off-list stored value must resolve to a REAL kind.
        assertEquals(SchedKind.ONCE, toKind("BANANAS"))
        assertEquals(SchedKind.ONCE, toKind(null))
        assertEquals(SchedKind.ONCE, schedDefaultFor(AppSettings(schedDefault = "BANANAS"), Tab.TASKS))
    }

    // ---------------- N18: toggle vocabulary ----------------

    @Test fun toggle_labels_follow_the_mode() {
        assertEquals("Active" to "Done", toggleLabels(false))
        assertEquals("Upcoming" to "Past", toggleLabels(true))
    }

    // ---------------- N20: missedRows ----------------

    @Test fun missed_rows_newest_first_capped_at_ten() {
        val ts = (1..14).map { at(2026, 7, it, 9) }
        val rows = missedRows(item(missedAt = ts))
        assertEquals(10, rows.size)
        assertEquals(at(2026, 7, 14, 9), rows.first())
        assertTrue(rows.zipWithNext().all { (a, b) -> a > b })
    }

    // ---------------- N20: the roll — Krishna's example verbatim ----------------

    @Test fun weekly_tuesday_dismissed_rolls_one_week_and_logs_the_miss() {
        // Due Tue 04-Aug-2026 20:00, alarm dismissed (not done). At the 11-Aug midnight sweep
        // the item lands on Tue 11-Aug 20:00 with 04-Aug logged as missed.
        val i = item(dueAt = at(2026, 8, 4, 20), repeatMode = "WEEKLY", repeatDays = listOf(2))
        val rolled = rollMissedRepeats(listOf(i), at(2026, 8, 11, 0, 30))
        assertEquals(1, rolled.size)
        assertEquals(at(2026, 8, 11, 20), rolled[0].dueAt)
        assertEquals(listOf(at(2026, 8, 4, 20)), rolled[0].missedAt)
    }

    @Test fun two_weeks_offline_logs_both_misses_newest_first() {
        val i = item(dueAt = at(2026, 8, 4, 20), repeatMode = "WEEKLY", repeatDays = listOf(2))
        val rolled = rollMissedRepeats(listOf(i), at(2026, 8, 18, 0, 30))
        assertEquals(at(2026, 8, 18, 20), rolled[0].dueAt)
        assertEquals(listOf(at(2026, 8, 11, 20), at(2026, 8, 4, 20)), rolled[0].missedAt)
    }

    @Test fun live_snooze_is_never_rolled() {
        val now = at(2026, 8, 11, 0, 30)
        val i = item(
            dueAt = at(2026, 8, 4, 20), repeatMode = "WEEKLY", repeatDays = listOf(2),
            snoozedUntil = now + 3_600_000
        )
        assertTrue(rollMissedRepeats(listOf(i), now).isEmpty())
    }

    @Test fun done_deleted_off_and_dateless_never_roll() {
        val now = at(2026, 8, 11, 0, 30)
        val base = item(dueAt = at(2026, 8, 4, 20), repeatMode = "DAILY")
        assertTrue(rollMissedRepeats(listOf(
            base.copy(done = true),
            base.copy(deletedAt = now),
            base.copy(repeatMode = "OFF"),
            base.copy(dueAt = null)
        ), now).isEmpty())
    }

    @Test fun daily_month_gap_keeps_only_the_newest_ten() {
        val i = item(dueAt = at(2026, 7, 1, 9), repeatMode = "DAILY")
        val rolled = rollMissedRepeats(listOf(i), at(2026, 8, 11, 0, 30))
        assertEquals(at(2026, 8, 11, 9), rolled[0].dueAt)
        assertEquals(MISSED_CAP, rolled[0].missedAt.size)
        assertEquals(at(2026, 8, 10, 9), rolled[0].missedAt.first())   // newest kept
    }

    @Test fun roll_preserves_alert_type_and_never_tallies_repeat_done() {
        val i = item(
            dueAt = at(2026, 8, 4, 20), repeatMode = "WEEKLY", repeatDays = listOf(2),
            alertType = "A", repeatCount = 5, repeatDone = 1
        )
        val r = rollMissedRepeats(listOf(i), at(2026, 8, 11, 0, 30))[0]
        assertEquals("A", r.alertType)
        assertEquals(1, r.repeatDone)          // a MISS is not a completion
        assertEquals(5, r.repeatCount)
    }

    @Test fun corrupt_anchor_is_skipped_not_looped() {
        // ~2500 daily steps > ROLL_MAX_STEPS — the sweep must skip, not spin or half-roll.
        val i = item(dueAt = at(2019, 8, 1, 9), repeatMode = "DAILY")
        assertTrue(rollMissedRepeats(listOf(i), at(2026, 8, 11, 0, 30)).isEmpty())
    }

    @Test fun future_and_today_items_do_not_roll() {
        val now = at(2026, 8, 11, 10)
        assertTrue(rollMissedRepeats(listOf(
            item(dueAt = at(2026, 8, 11, 20), repeatMode = "DAILY"),   // today, later
            item(dueAt = at(2026, 8, 12, 9), repeatMode = "DAILY")     // tomorrow
        ), now).isEmpty())
    }

    // ---------------- N26: pure window + purge math ----------------

    /**
     * v2.6.2 (N41) SUPERSEDES the N26 fence window: the install fence and the 48 h reach-back are
     * retired — the call log is read only inside the 5-minute LIVE window, so no scan can ever see
     * history again (Krishna, 18-Aug-2026). The replacement contract lives in V262Test; this test
     * keeps the N26 intent — "a scan must never walk back to installation" — against the new rule.
     */
    @Test fun scan_window_is_live_only_and_never_reaches_installation() {
        val fence = at(2026, 8, 1, 12)
        val now = fence + 10L * 86_400_000                 // ten days after installing
        assertEquals(now - LIVE_WINDOW_MS, liveScanWindow(0L, now))          // first scan
        assertEquals(now - LIVE_WINDOW_MS, liveScanWindow(fence, now))       // stale watermark clamped
        val recent = now - 60_000L
        assertEquals(recent, liveScanWindow(recent, now))                    // steady state
    }

    @Test fun purge_takes_only_pre_install_auto_reminders() {
        val fence = at(2026, 8, 1, 12)
        fun cr(id: Long, src: CallSource, miss: Long?) = CallReminder(
            id = id, number = "98$id", source = src, lastMissedAt = miss
        )
        val junkAuto = cr(1, CallSource.AUTO, fence - 5_000)
        val manualOld = cr(2, CallSource.MANUAL, fence - 5_000)
        val autoNew = cr(3, CallSource.AUTO, fence + 5_000)
        val autoNoDate = cr(4, CallSource.AUTO, null)
        val (keep, purged) = purgePreInstall(listOf(junkAuto, manualOld, autoNew, autoNoDate), fence)
        assertEquals(listOf(1L), purged.map { it.id })
        assertEquals(listOf(2L, 3L, 4L), keep.map { it.id })
    }
}
