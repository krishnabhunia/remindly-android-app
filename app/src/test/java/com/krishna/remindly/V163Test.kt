package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v1.63 — confirmation-toast text builders. The toasts themselves (all four card modes,
 * notification buttons, lock screen) are device-checklist.
 */
class V163Test {

    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(2026, 0, 15, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    // ---- snooze: duration wording + exact clock time ----

    @Test fun snooze_same_day_names_the_clock_time() {
        val now = at(10, 0); val fire = at(10, 30)
        assertEquals("\u23F0 Snoozed 30 m \u2014 rings again at " + hm(fire), snoozeToast(30, fire, now))
    }

    /**
     * v1.83 (N10): was `snooze_90_reads_one_hour_thirty`, pinning "1 h 30 min". That format came
     * from `minLbl`, the second formatter, while the BUTTON that fired this toast said "1 h" via
     * the truncating `snoozeLabel`. One value, two strings, neither of them "90". Krishna's rule
     * is now plain minutes everywhere, from one formatter.
     */
    @Test fun snooze_90_reads_ninety_minutes() {
        val now = at(10, 0); val fire = at(11, 30)
        assertTrue(snoozeToast(90, fire, now).contains("90 m"))
        assertTrue("the collapsed form must be gone", !snoozeToast(90, fire, now).contains("1 h"))
    }

    @Test fun snooze_crossing_midnight_shows_the_full_date() {
        val now = at(23, 50)
        val fire = now + 30 * 60_000L
        assertEquals("\u23F0 Snoozed 30 m \u2014 rings again at " + formatDateTime(fire), snoozeToast(30, fire, now))
    }

    // ---- done / dismiss: destination + title handling ----

    @Test fun done_item_names_title_and_tab_done_list() {
        val msg = doneToastItem("Buy milk", Tab.SHOP)
        assertTrue(msg.contains("\"Buy milk\""))
        assertTrue(msg.contains(Tab.SHOP.title))
        assertTrue(msg.contains("Done list"))
    }

    @Test fun long_titles_truncate_with_ellipsis() {
        val msg = doneToastItem("A very very long shopping item title indeed", Tab.TASKS)
        assertTrue(msg.contains("\u2026"))
        assertTrue(!msg.contains("indeed"))
    }

    @Test fun dismiss_keeps_the_item_and_says_so() {
        assertTrue(dismissToast("Report").contains("\"Report\" stays on your list"))
    }

    @Test fun call_done_names_the_contact() {
        assertTrue(doneToastCall("Amit").contains("Amit"))
    }

    // ---- stop / close ----

    @Test fun fixed_texts_are_stable() {
        assertEquals("Closed \u2014 reminder stays on the Calls tab", closeCallToast())
        assertEquals("Sound stopped", stopToastText())
    }
}
