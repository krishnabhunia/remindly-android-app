package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.80 — Q17 snooze lifecycle, N5 chip resolvers, Q16 colour maps. */
class V180Test {

    private val NOW = 1_000_000L

    // ---------------- Q17: a snooze only counts while it is in the future ----------------

    @Test fun liveSnoozeIgnoresThePast() {
        assertEquals(NOW + 500, liveSnooze(NOW + 500, NOW))
        assertNull("a past snooze reads as absent", liveSnooze(NOW - 500, NOW))
        assertNull(liveSnooze(null, NOW))
    }

    /** v2.10 (N6 pt2): the production decision AlarmScheduler.scheduleForItem uses — no copy. */
    private fun fireAt(item: Item, now: Long): Long? = itemFireAt(item, now)

    @Test fun mutedItemsAreNeverArmed() =
        assertNull("v2.8 N44: a deleted alert arms nothing", fireAt(item(due = NOW + 200).copy(alertType = ALERT_MUTED), NOW))

    private fun item(due: Long? = null, snooze: Long? = null,
                     done: Boolean = false, deleted: Long? = null, repeat: String = "OFF") =
        Item(id = 1, tab = Tab.TASKS, title = "x", dueAt = due, snoozedUntil = snooze,
             done = done, deletedAt = deleted, repeatMode = repeat)

    @Test fun futureSnoozeBeatsDueAt() =
        assertEquals(NOW + 900, fireAt(item(due = NOW - 100, snooze = NOW + 900), NOW))

    @Test fun staleSnoozeFallsBackToDueAt() =
        assertEquals(NOW + 200, fireAt(item(due = NOW + 200, snooze = NOW - 900), NOW))

    @Test fun noSnoozeIsUnchangedBehaviour() =
        assertEquals(NOW + 200, fireAt(item(due = NOW + 200), NOW))

    @Test fun deletedAndDoneAreNeverArmed() {
        assertNull(fireAt(item(due = NOW + 200, deleted = 5L), NOW))
        assertNull(fireAt(item(due = NOW + 200, done = true), NOW))
    }

    /** Reopening at 14:30 an item snoozed to 15:00 must still fire at 15:00. */
    @Test fun reopenKeepsALiveSnooze() {
        val done = item(due = NOW - 5_000, snooze = NOW + 1_800, done = true)
        val revived = done.copy(done = false, doneAt = null, returnAt = null)
        assertEquals(NOW + 1_800, fireAt(revived, NOW))
    }

    /** Reopening a week later must ignore the stale snooze. */
    @Test fun reopenIgnoresAStaleSnooze() {
        val done = item(due = NOW - 5_000, snooze = NOW - 1_800, done = true)
        val revived = done.copy(done = false)
        assertEquals(NOW - 5_000, fireAt(revived, NOW))
    }

    @Test fun completionClearsTheSnooze() {
        val completed = item(due = NOW + 100, snooze = NOW + 900).copy(done = true, snoozedUntil = null)
        assertNull("must not linger on the Done card", completed.snoozedUntil)
        assertNull("must not carry into the next occurrence", fireAt(completed, NOW))
    }

    // ---------------- N5: chip resolvers ----------------

    @Test fun onlyAlarmAndRingShowAChip() {
        assertTrue(showAlertChip("A")); assertTrue(showAlertChip("R"))
        assertFalse("Notify is what every item already is", showAlertChip("N"))
        assertFalse(showAlertChip("?"))
    }

    @Test fun callsNeverGetTheChipEvenWhenTheGlobalIsOn() {
        val s = AppSettings(cardShowAlertType = true)
        assertFalse("Calls is out of scope", cardFieldsFor(s, null).alertType)
        assertTrue(cardFieldsFor(s, Tab.TASKS).alertType)
    }

    @Test fun perTabOverrideBeatsGlobalBothWays() {
        val on = AppSettings(cardShowAlertType = true,
            tabCardOv = mapOf(tabPrefix(Tab.SHOP) + "CardAlertType" to "OFF"))
        assertFalse(cardFieldsFor(on, Tab.SHOP).alertType)
        assertTrue(cardFieldsFor(on, Tab.TASKS).alertType)
        val off = AppSettings(cardShowAlertType = false,
            tabCardOv = mapOf(tabPrefix(Tab.LEARN) + "CardAlertType" to "ON"))
        assertTrue(cardFieldsFor(off, Tab.LEARN).alertType)
        assertFalse(cardFieldsFor(off, Tab.TASKS).alertType)
    }

    @Test fun styleInheritsThenOverrides() {
        val s = AppSettings(cardAlertTypeStyle = "ICON",
            tabCardOv = mapOf(tabPrefix(Tab.SHOP) + "CardAlertStyle" to "TEXT"))
        assertEquals("ICON", cardAlertStyleFor(s, Tab.TASKS))
        assertEquals("TEXT", cardAlertStyleFor(s, Tab.SHOP))
    }

    @Test fun colourInheritsThenOverridesAndRejectsGarbage() {
        val s = AppSettings(alertColorA = "#D32F2F",
            tabCardOv = mapOf(tabPrefix(Tab.SHOP) + "CardAlertColorA" to "#00AA00",
                              tabPrefix(Tab.LEARN) + "CardAlertColorA" to "nonsense"))
        assertEquals("#D32F2F", cardAlertColorFor(s, Tab.TASKS, "A"))
        assertEquals("#00AA00", cardAlertColorFor(s, Tab.SHOP, "A"))
        assertEquals("garbage falls back to the global", "#D32F2F", cardAlertColorFor(s, Tab.LEARN, "A"))
    }

    @Test fun labelsAreOneSourceOfTruth() {
        assertEquals("Alarm", alertTypeLabel("A"))
        assertEquals("Ring", alertTypeLabel("R"))
        assertEquals("Notify", alertTypeLabel("N"))
        assertEquals("Notify", alertTypeLabel("junk"))
    }

    // ---------------- Q16: two independent colour systems ----------------

    @Test fun everyTypeGetsItsOwnGradient() {
        val a = alertCardGradient("A"); val r = alertCardGradient("R"); val n = alertCardGradient("N")
        assertEquals(3, setOf(a, r, n).size)
        assertEquals("unknown letter falls back to the quietest", n, alertCardGradient("?"))
    }

    @Test fun actionColoursAreDistinctAndConstant() {
        val done = alertActionColor("DONE"); val dismiss = alertActionColor("DISMISS")
        val snooze = alertActionColor("SNOOZE")
        assertEquals(3, setOf(done, dismiss, snooze).size)
        assertEquals("same action, same colour, every card", done, alertActionColor("DONE"))
    }
}
