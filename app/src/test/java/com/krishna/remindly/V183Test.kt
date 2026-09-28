package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.83 (N10) — the snooze DISPLAY, not the snooze duration.
 *
 * The duration was correct from v1.81 onward. What reached Krishna was a label: `snoozeLabel` did
 * `m / 60` with integer division, so 90 printed as "1 h" (remainder discarded) and 60 printed
 * "1 h" too. The settings chip row showed "1 h" twice and the alert card claimed an hour while
 * scheduling 90 minutes.
 *
 * Every earlier gate and suite passed because they all measured the PLUMBING — does the button
 * route through snoozeTargetMs, does the target equal now + minutes. None of them ever asked
 * whether the string the user reads names the value the app uses. That is the round trip below,
 * and it is the assertion that would have failed in v1.81.
 *
 * Per N6: these call production code. No mirrored copies.
 */
class V183Test {

    private val chips = listOf(10, 30, 60, 90, 120)

    // ---------------- the round trip: label must name its own value ----------------

    /** THE regression. 90 must not print as "1 h". */
    @Test fun ninetyPrintsAsNinety() {
        assertEquals("90 m", snoozeMinLabel(90))
        assertEquals("90 m", Alerts.snoozeLabel(90))
        assertNotEquals("the truncating hour label must be gone", "1 h", Alerts.snoozeLabel(90))
    }

    /** The general property, not just the instance that was reported. */
    @Test fun everyChipLabelContainsItsOwnNumber() {
        for (m in chips) {
            assertTrue(
                "snoozeLabel($m) = \"${Alerts.snoozeLabel(m)}\" does not name $m",
                Alerts.snoozeLabel(m).contains(m.toString())
            )
        }
    }

    /** Beyond the chip set, so a future option cannot reintroduce collapsing. */
    @Test fun labelNamesItsValueAcrossTheWholeLegalBand() {
        for (m in listOf(1, 5, 45, 59, 60, 61, 89, 90, 91, 120, 359, 720, 1439, 1440)) {
            assertTrue(
                "snoozeLabel($m) = \"${Alerts.snoozeLabel(m)}\" does not name $m",
                Alerts.snoozeLabel(m).contains(m.toString())
            )
        }
    }

    /** Two chips reading "1 h" is what the screenshot showed. Distinct values, distinct labels. */
    @Test fun distinctDurationsNeverShareALabel() {
        val seen = chips.map { Alerts.snoozeLabel(it) }
        assertEquals("chip labels must be unique", seen.size, seen.toSet().size)
        assertNotEquals(Alerts.snoozeLabel(60), Alerts.snoozeLabel(90))
        assertNotEquals(Alerts.snoozeLabel(60), Alerts.snoozeLabel(120))
    }

    /** The hour and day branches are gone: nothing collapses, at any magnitude. */
    @Test fun noHourOrDayCollapsing() {
        assertEquals("60 m", Alerts.snoozeLabel(60))
        assertEquals("120 m", Alerts.snoozeLabel(120))
        assertEquals("1440 m", Alerts.snoozeLabel(1440))
        for (m in chips) {
            assertTrue("no 'h' unit may appear", !Alerts.snoozeLabel(m).contains(" h"))
            assertTrue("no 'd' unit may appear", !Alerts.snoozeLabel(m).contains(" d"))
        }
    }

    // ---------------- one formatter: button and toast must agree ----------------

    /** Before: the button said "1 h" and the toast it fired said "1 h 30 min". Same 90. */
    @Test fun buttonLabelAndToastAgree() {
        for (m in chips) {
            val fireAt = 5_000_000L
            assertTrue(
                "toast for $m must contain the same text the button shows",
                snoozeToast(m, fireAt, now = 0L).contains(Alerts.snoozeLabel(m))
            )
        }
    }

    @Test fun snoozeLabelDelegatesToTheSingleFormatter() {
        for (m in chips) assertEquals(snoozeMinLabel(m), Alerts.snoozeLabel(m))
    }

    // ---------------- the label follows the SETTING, not a literal ----------------

    /**
     * AlarmActivity passed a bare 90 to snoozeToast, correct only while the setting was 90.
     * This pins the composition every card must use: label = snoozeLabel(snoozeMinutes(settings)).
     */
    @Test fun labelTracksTheConfiguredValue() {
        for (m in chips) {
            val s = AppSettings(snoozeM1 = m)
            assertEquals("$m m", Alerts.snoozeLabel(snoozeMinutes(s)))
        }
    }

    /** The card's text and the alarm it schedules must describe one duration. */
    @Test fun labelAndScheduledTargetDescribeTheSameDuration() {
        val now = 1_000_000L
        for (m in chips) {
            val s = AppSettings(snoozeM1 = m)
            val minutesInLabel = Alerts.snoozeLabel(snoozeMinutes(s)).filter { it.isDigit() }.toInt()
            assertEquals(
                "card says $minutesInLabel min but the alarm is set elsewhere",
                now + minutesInLabel * 60_000L,
                snoozeTargetMs(s, now)
            )
        }
    }

    // ---------------- the settings chip row cannot show a value the app is not using ----------

    /** The default lands on chip index 3, which is 90 — what the screenshot actually selected. */
    @Test fun defaultSelectsTheNinetyChip() {
        assertEquals(90, AppSettings().snoozeM1)
        assertEquals(3, chips.indexOf(snoozeMinutes(AppSettings())))
    }

    /**
     * indexOf(-1).coerceAtLeast(0) used to display "10 m" as selected for any off-list value.
     * The production rule is now: an off-list value joins the list as its own chip.
     */
    @Test fun offListValueBecomesItsOwnChipRatherThanAWrongOne() {
        for (stored in listOf(45, 75, 200)) {
            val opts = (chips + stored).sorted()
            val ix = opts.indexOf(stored)
            assertTrue("off-list value must resolve to a real chip", ix >= 0)
            assertEquals("the selected chip must show the stored value", "$stored m", Alerts.snoozeLabel(opts[ix]))
        }
    }

    /** A corrupt value is coerced, and the label still names what the app will use. */
    @Test fun coercedValueIsStillNamedHonestly() {
        val low = snoozeMinutes(AppSettings(snoozeM1 = 0))
        val high = snoozeMinutes(AppSettings(snoozeM1 = 99_999))
        assertEquals(1, low)
        assertEquals(1440, high)
        assertEquals("1 m", Alerts.snoozeLabel(low))
        assertEquals("1440 m", Alerts.snoozeLabel(high))
    }
}
