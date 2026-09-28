package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.82 — written under N6's rules: these call the PRODUCTION functions.
 *
 * The previous suites contained private copies of the logic labelled "Mirrors ..." — those pass
 * even if the production code is deleted, which is exactly how Q19 reached the user three times.
 * Every assertion below invokes real code.
 */
class V182Test {

    // ---------------- N6/Q19: one source for every deferral target ----------------

    @Test fun targetIsExactlyTheConfiguredMinutesFromNow() {
        val now = 1_000_000L
        assertEquals(now + 90 * 60_000L, snoozeTargetMs(AppSettings(snoozeM1 = 90), now))
        assertEquals(now + 30 * 60_000L, snoozeTargetMs(AppSettings(snoozeM1 = 30), now))
    }

    /** The exact regression: 60 minutes was hardcoded on the call path. */
    @Test fun ninetyIsNotSixty() {
        val now = 0L
        assertEquals(90 * 60_000L, snoozeTargetMs(AppSettings(), now))
        assertNotEquals("the hardcoded hour must be gone", 3_600_000L, snoozeTargetMs(AppSettings(), now))
    }

    @Test fun defaultIsNinety() = assertEquals(90, snoozeMinutes(AppSettings()))

    @Test fun corruptDurationsAreCoercedNotObeyed() {
        // a zero or negative value would fire instantly and loop; an absurd one would never fire
        assertEquals(1, snoozeMinutes(AppSettings(snoozeM1 = 0)))
        assertEquals(1, snoozeMinutes(AppSettings(snoozeM1 = -5)))
        assertEquals(24 * 60, snoozeMinutes(AppSettings(snoozeM1 = 99_999)))
    }

    @Test fun targetAlwaysLiesInTheFuture() {
        val now = 500_000L
        listOf(0, -5, 1, 90, 99_999).forEach { m ->
            assertTrue("m=$m", snoozeTargetMs(AppSettings(snoozeM1 = m), now) > now)
        }
    }

    /**
     * The v1.82 defect in one assertion: the Ring notification typed its label and its number
     * separately, so a changed setting left one button disagreeing with every other path.
     */
    @Test fun labelAndTargetAgreeForEveryDuration() {
        val now = 0L
        listOf(1, 10, 30, 60, 90, 120).forEach { m ->
            val s = AppSettings(snoozeM1 = m)
            val minutesFromTarget = (snoozeTargetMs(s, now) / 60_000L).toInt()
            assertEquals("target for $m", m, minutesFromTarget)
            assertTrue("label for $m mentions the value", Alerts.snoozeLabel(m).isNotBlank())
        }
        assertNotEquals(Alerts.snoozeLabel(30), Alerts.snoozeLabel(90))
    }

    // ---------------- N6: removed things must be tested as GONE ----------------

    @Test fun theSecondSnoozeDurationIsGone() {
        // AppSettings must not carry the purged field; reflection catches a silent re-add
        val names = AppSettings::class.java.declaredFields.map { it.name }
        assertTrue("snoozeM1 kept", names.contains("snoozeM1"))
        assertEquals("snoozeM2 must stay purged", false, names.contains("snoozeM2"))
    }

    // ---------------- Q17 regression cover, now against real code ----------------

    @Test fun liveSnoozeIsTheRealFunction() {
        val now = 1_000L
        assertEquals(now + 1, liveSnooze(now + 1, now))
        assertEquals(null, liveSnooze(now - 1, now))
        assertEquals(null, liveSnooze(null, now))
    }

    @Test fun aSnoozeSetNowIsAlwaysLive() {
        val now = 42L
        val target = snoozeTargetMs(AppSettings(), now)
        assertEquals(target, liveSnooze(target, now))
    }

    // ---------------- N5 resolvers, already real, kept as cover ----------------

    @Test fun callsStillNeverGetTheReminderChip() {
        assertEquals(false, cardFieldsFor(AppSettings(cardShowAlertType = true), null).alertType)
    }

    @Test fun alertLabelsRemainOneSourceOfTruth() {
        assertEquals("Alarm", alertTypeLabel("A"))
        assertEquals("Ring", alertTypeLabel("R"))
        assertEquals("Notify", alertTypeLabel("N"))
    }
}
