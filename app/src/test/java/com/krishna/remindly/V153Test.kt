package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.53 — calendar read window is anchored on the DAY, not the instant.
 *
 * These are the tests that should have existed for v1.50: pure date arithmetic, no device needed,
 * and they would have caught the bug Krishna found (today's earlier events and every all-day event
 * were invisible in the Active view).
 */
class V153Test {

    private val DAY = 86_400_000L
    // an arbitrary "now" at roughly mid-afternoon
    private val now = startOfDayMs(1_800_000_000_000L) + 16 * 3_600_000L
    private val today = startOfDayMs(now)

    @Test fun active_window_starts_at_the_beginning_of_today() {
        val (from, _) = calendarWindowBounds(now, 7, past = false)
        assertEquals("must not start at 'now'", today, from)
    }

    @Test fun an_event_earlier_today_falls_inside_the_active_window() {
        val thisMorning = today + 10 * 3_600_000L      // 10:00, already finished at 16:00
        val (from, to) = calendarWindowBounds(now, 7, past = false)
        assertTrue("this morning's meeting belongs to Active", thisMorning in from until to)
    }

    @Test fun an_all_day_event_today_falls_inside_the_active_window() {
        // all-day instances begin at midnight — the exact case that could never show before
        val (from, to) = calendarWindowBounds(now, 7, past = false)
        assertTrue("all-day today must be visible in Active", today in from until to)
    }

    @Test fun done_window_ends_at_the_start_of_today() {
        val (_, to) = calendarWindowBounds(now, 7, past = true)
        assertEquals(today, to)
    }

    @Test fun today_is_never_shown_in_the_done_window() {
        val thisMorning = today + 10 * 3_600_000L
        val (from, to) = calendarWindowBounds(now, 7, past = true)
        assertTrue("today belongs to Active only", thisMorning !in from until to)
    }

    @Test fun yesterday_is_in_done_and_not_in_active() {
        val yesterday = today - DAY + 9 * 3_600_000L
        val (pFrom, pTo) = calendarWindowBounds(now, 7, past = true)
        val (aFrom, aTo) = calendarWindowBounds(now, 7, past = false)
        assertTrue(yesterday in pFrom until pTo)
        assertTrue(yesterday !in aFrom until aTo)
    }

    @Test fun the_two_windows_meet_without_overlapping() {
        val (pFrom, pTo) = calendarWindowBounds(now, 7, past = true)
        val (aFrom, aTo) = calendarWindowBounds(now, 7, past = false)
        assertEquals("past ends exactly where active begins", pTo, aFrom)
        assertTrue(pFrom < pTo && aFrom < aTo)
    }

    @Test fun span_covers_the_requested_days_each_way() {
        val (aFrom, aTo) = calendarWindowBounds(now, 30, past = false)
        assertEquals(30 * DAY, aTo - aFrom)
        val (pFrom, pTo) = calendarWindowBounds(now, 30, past = true)
        assertEquals(30 * DAY, pTo - pFrom)
    }

    @Test fun long_windows_are_not_truncated() {
        val (from, to) = calendarWindowBounds(now, 24 * 31, past = false)
        assertTrue("24 months must survive the clamp", to - from > 700 * DAY)
    }

    @Test fun zero_or_negative_days_are_clamped_to_one() {
        val (from, to) = calendarWindowBounds(now, 0, past = false)
        assertEquals(DAY, to - from)
    }
}
