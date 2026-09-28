package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.84 — N13 (quiet demotes the record) and N14 (Coming-up rows as pure data).
 * Per N6: every assertion calls PRODUCTION code. No mirrored copies.
 */
class V184Test {

    private fun item(
        alertType: String = "A",
        snoozedUntil: Long? = null,
        dueAt: Long? = null,
        done: Boolean = false,
        repeatMode: String = "OFF",
        returnAt: Long? = null
    ) = Item(
        id = 42L, title = "t", tab = Tab.TASKS, alertType = alertType,
        snoozedUntil = snoozedUntil, dueAt = dueAt, done = done,
        repeatMode = repeatMode, returnAt = returnAt
    )

    // ---------------- N13: the quiet transition ----------------

    /** THE report: after Quiet, the record itself must say Notify. */
    @Test fun quietDemoteSetsNotifyAndSnooze() {
        val out = quietDemote(item(alertType = "A"), fireAt = 5_000L)
        assertEquals("N", out.alertType)
        assertEquals(5_000L, out.snoozedUntil)
    }

    @Test fun quietDemoteWorksFromEveryStartingType() {
        for (t in listOf("A", "R", "N")) {
            val out = quietDemote(item(alertType = t), 9_000L)
            assertEquals("started as $t", "N", out.alertType)
            assertEquals(9_000L, out.snoozedUntil)
        }
    }

    /** The demote must not disturb anything else on the record. */
    @Test fun quietDemotePreservesEveryOtherField() {
        val src = item(alertType = "R", dueAt = 111L, repeatMode = "DAILY")
        val out = quietDemote(src, 7_000L)
        assertEquals(src.id, out.id)
        assertEquals(src.title, out.title)
        assertEquals(src.tab, out.tab)
        assertEquals(src.dueAt, out.dueAt)
        assertEquals(src.repeatMode, out.repeatMode)
        assertEquals(src.done, out.done)
    }

    @Test fun quietDemoteIsIdempotent() {
        val once = quietDemote(item(), 5_000L)
        val twice = quietDemote(once, 5_000L)
        assertEquals(once, twice)
    }

    /** Editor chip + Coming up both read alertType — with "N" the label is Notify. */
    @Test fun demotedItemLabelsAsNotify() {
        assertEquals("Notify", alertTypeLabel(quietDemote(item(alertType = "A"), 1L).alertType))
    }

    // ---------------- N14: Coming-up rows are pure and honest ----------------

    /** The exact end state Krishna asked to SEE: "Snoozed · Notify" after a Quiet. */
    @Test fun quietThenRowsReadsSnoozedNotify() {
        val now = 1_000L
        val demoted = quietDemote(item(alertType = "A", dueAt = 500L), fireAt = now + 90 * 60_000L)
        val rows = comingUpRows(demoted, now)
        assertEquals(1, rows.size)
        assertEquals("Snoozed \u00b7 Notify", rows[0].what)
        assertEquals(now + 90 * 60_000L, rows[0].at)
        assertTrue("the snoozed row must carry the x", rows[0].cancellable)
    }

    @Test fun liveSnoozeRowShowsTheItemsOwnType() {
        val now = 1_000L
        assertEquals("Snoozed \u00b7 Alarm", comingUpRows(item(alertType = "A", snoozedUntil = 2_000L), now)[0].what)
        assertEquals("Snoozed \u00b7 Ring", comingUpRows(item(alertType = "R", snoozedUntil = 2_000L), now)[0].what)
    }

    /** An EXPIRED snooze is no snooze — the box falls back to the due row (Q17's liveSnooze). */
    @Test fun expiredSnoozeFallsBackToDue() {
        val now = 10_000L
        val rows = comingUpRows(item(alertType = "A", snoozedUntil = 9_000L, dueAt = 20_000L), now)
        assertEquals(1, rows.size)
        assertEquals("Due \u00b7 Alarm", rows[0].what)
        assertFalse(rows[0].cancellable)
    }

    @Test fun cancelledSnoozeRemovesTheRow() {
        val now = 1_000L
        val snoozed = item(snoozedUntil = 5_000L)
        assertEquals(1, comingUpRows(snoozed, now).size)
        val cancelled = snoozed.copy(snoozedUntil = null)
        assertTrue("no due, no snooze -> no rows", comingUpRows(cancelled, now).isEmpty())
    }

    @Test fun pastDueAndNoSnoozeShowsNothing() {
        assertTrue(comingUpRows(item(dueAt = 500L), now = 1_000L).isEmpty())
    }

    @Test fun doneRepeatingItemShowsReturns() {
        val rows = comingUpRows(item(done = true, repeatMode = "DAILY", dueAt = 9_000L), now = 1_000L)
        assertEquals(1, rows.size)
        assertEquals("Returns", rows[0].what)
        assertFalse(rows[0].cancellable)
    }

    @Test fun lapseReturnRowAppearsWhenInTheFuture() {
        val rows = comingUpRows(item(returnAt = 9_000L), now = 1_000L)
        assertEquals(1, rows.size)
        assertEquals("Back on your list", rows[0].what)
        assertFalse(rows[0].cancellable)
    }

    /** Only the snooze row is ever cancellable — the x must not appear on anything else. */
    @Test fun onlyTheSnoozeRowIsCancellable() {
        val now = 1_000L
        val many = comingUpRows(
            item(done = true, repeatMode = "DAILY", dueAt = 9_000L, returnAt = 8_000L), now
        )
        assertTrue(many.isNotEmpty())
        assertTrue(many.none { it.cancellable })
    }
}
