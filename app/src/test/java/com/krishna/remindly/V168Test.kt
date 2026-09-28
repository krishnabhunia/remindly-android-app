package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.68 Great Retirement pins: per-item types, migration collapse, duration band, quiet toast. */
class V168Test {
    private fun item(t: String) = Item(id = 9, title = "x", tab = Tab.TASKS, alertType = t)

    @Test fun itemLetterWins() {
        val s = AppSettings()
        assertEquals("A", resolveAlertTypes(item("A"), s))
        assertEquals("R", resolveAlertTypes(item("R"), s))
        assertEquals("N", resolveAlertTypes(item("N"), s))
    }

    @Test fun junkFallsBackToNotify() {
        assertEquals("N", resolveAlertTypes(item("Z"), AppSettings()))
    }

    @Test fun ringBand() {
        assertEquals(7, coerceRingSeconds(0))
        assertEquals(7, coerceRingSeconds(-5))
        assertEquals(3, coerceRingSeconds(2))
        assertEquals(180, coerceRingSeconds(999))
        assertEquals(45, coerceRingSeconds(45))
    }

    @Test fun durationParserBand() {
        assertEquals(7, minSecToSeconds("", "7"))
        assertNull(minSecToSeconds("", "2"))
        assertEquals(180, minSecToSeconds("3", "0"))
        assertNull(minSecToSeconds("3", "1"))
    }

    @Test fun quietToastMentionsQuietly() {
        assertTrue(quietSnoozeToast(System.currentTimeMillis() + 90 * 60_000L).contains("quietly"))
    }

    @Test fun freshItemDefaultsToNotify() {
        assertEquals("N", Item(id = 1, title = "x", tab = Tab.TASKS).alertType)
    }

    @Test fun mediumAndNullTagsHidden_q3() {
        org.junit.Assert.assertFalse(showPriorityTag(Priority.MEDIUM, AppSettings(), Tab.TASKS))
        org.junit.Assert.assertFalse(showPriorityTag(null, AppSettings(), Tab.TASKS))
        org.junit.Assert.assertTrue(showPriorityTag(Priority.URGENT, AppSettings(), Tab.TASKS))
        org.junit.Assert.assertTrue(showPriorityTag(Priority.HIGH, AppSettings(), Tab.TASKS))
        org.junit.Assert.assertTrue(showPriorityTag(Priority.LOW, AppSettings(), Tab.TASKS))
    }

}
