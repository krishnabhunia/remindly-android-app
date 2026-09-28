package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.81 — Q18 snooze regression + migration, Q15 notify-tap plumbing. */
class V181Test {

    // ---------------- Q18: the default, and the migration that makes it real ----------------

    @Test fun defaultSnoozeIsNinetyAgain() = assertEquals(90, AppSettings().snoozeM1)

    @Test fun migrationFlagStartsUnset() = assertEquals(false, AppSettings().snoozeFixed90)

    /** Mirrors the one-time migration in RemindlyApp. */
    private fun migrate(s: AppSettings): AppSettings =
        if (s.snoozeFixed90) s
        else s.copy(snoozeM1 = if (s.snoozeM1 == 10) 90 else s.snoozeM1, snoozeFixed90 = true)

    @Test fun storedTenBecomesNinety() {
        // the exact state v1.80 left behind: the default was 10 and got persisted
        val stored = AppSettings(snoozeM1 = 10)
        assertEquals(90, migrate(stored).snoozeM1)
        assertTrue(migrate(stored).snoozeFixed90)
    }

    @Test fun aDeliberateValueIsLeftAlone() {
        assertEquals(30, migrate(AppSettings(snoozeM1 = 30)).snoozeM1)
        assertEquals(120, migrate(AppSettings(snoozeM1 = 120)).snoozeM1)
    }

    @Test fun migrationDoesNotRunTwice() {
        // a user who later chooses 10 on purpose must keep it
        val once = migrate(AppSettings(snoozeM1 = 10))
        val chosen = once.copy(snoozeM1 = 10)
        assertEquals("already migrated, so 10 is now a choice", 10, migrate(chosen).snoozeM1)
    }

    @Test fun labelFollowsTheConfiguredValue() {
        // button text and behaviour come from the same number, so they cannot disagree
        listOf(10, 30, 60, 90, 120).forEach { m ->
            assertTrue("label for $m", Alerts.snoozeLabel(m).isNotBlank())
        }
        assertNotEquals(Alerts.snoozeLabel(10), Alerts.snoozeLabel(90))
    }

    // ---------------- Q15: the notify-tap request holder ----------------

    @Test fun requestIgnoresAnAbsentId() {
        NotifyOpen.clear()
        NotifyOpen.request(0L)
        assertEquals("no extra means no card", 0L, NotifyOpen.pending.value)
        NotifyOpen.request(-1L)
        assertEquals(0L, NotifyOpen.pending.value)
    }

    @Test fun requestAndClearRoundTrip() {
        NotifyOpen.clear()
        NotifyOpen.request(42L)
        assertEquals(42L, NotifyOpen.pending.value)
        NotifyOpen.clear()
        assertEquals(0L, NotifyOpen.pending.value)
    }

    @Test fun aLaterTapWins() {
        NotifyOpen.clear()
        NotifyOpen.request(7L)
        NotifyOpen.request(9L)
        assertEquals("onNewIntent after onCreate must not be ignored", 9L, NotifyOpen.pending.value)
        NotifyOpen.clear()
    }

    /**
     * The subtle Q15 defect: with FLAG_UPDATE_CURRENT a shared request code lets every
     * notification on a tab overwrite each other's extras, so every tap opens the same item.
     */
    private fun requestCode(id: Long) = (8_500_000 + id).toInt()

    @Test fun twoItemsGetDifferentRequestCodes() {
        assertNotEquals(requestCode(1L), requestCode(2L))
        assertEquals(3, listOf(1L, 2L, 3L).map { requestCode(it) }.toSet().size)
    }
}
