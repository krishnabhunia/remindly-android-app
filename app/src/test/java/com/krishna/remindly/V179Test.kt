package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.79 — Q11 header/list invariant, Q12 delete guard, Q14 bulk-clear partition, N4 WhatsApp. */
class V179Test {

    private fun item(id: Long, done: Boolean, personal: Boolean = false,
                     title: String = "x", repeat: String = "OFF", deleted: Long? = null) =
        Item(id = id, tab = Tab.TASKS, title = title, done = done, personal = personal,
             repeatMode = repeat, deletedAt = deleted)

    // ---------------- Q11: the header must describe exactly what the list renders ----------------

    /** v2.10 (N6 pt2): calls the PRODUCTION pipeline behind ListScreens.scopeOf — no copy. */
    private fun scope(all: List<Item>, doneFlag: Boolean, shop: Boolean,
                      personalFilter: Boolean, query: String) =
        listScope(all.map { it.copy(tab = if (shop) Tab.SHOP else Tab.TASKS) }, if (shop) Tab.SHOP else Tab.TASKS,
            doneFlag, personalFilter, searchOpen = query.isNotEmpty(), searchQ = query)

    @Test fun headerEqualsVisible_acrossEveryCombination() {
        val all = listOf(
            item(1, false), item(2, false, personal = true),
            item(3, true), item(4, true, personal = true),
            item(5, false, title = "milk"), item(6, false, deleted = 99L)
        )
        for (shop in listOf(true, false))
            for (pf in listOf(true, false))
                for (q in listOf("", "milk", "zzz"))
                    for (done in listOf(true, false)) {
                        val visible = scope(all, done, shop, pf, q)
                        val header = scope(all, done, shop, pf, q).size
                        assertEquals("shop=$shop pf=$pf q='$q' done=$done", visible.size, header)
                    }
    }

    @Test fun theOldBugReproduced_headerCountedMoreThanTheListShowed() {
        val all = listOf(item(1, false, personal = true), item(2, true, personal = true))
        // old header: every tab item, ignoring the person filter
        val oldHeaderActive = all.count { !it.done }
        val nowShowing = scope(all, false, shop = true, personalFilter = false, query = "").size
        assertEquals(1, oldHeaderActive)
        assertEquals(0, nowShowing)
        // new header agrees with the list
        assertEquals(nowShowing, scope(all, false, true, false, "").size)
    }

    @Test fun softDeletedNeverCounted() {
        val all = listOf(item(1, false), item(2, false, deleted = 1L))
        assertEquals(1, scope(all, false, false, false, "").size)
    }

    // ---------------- Q12: rescheduleAll must skip soft-deleted items ----------------

    /** v2.10 (N6 pt2): the production decision AlarmScheduler.rescheduleAll now uses. */
    private fun wouldSchedule(items: List<Item>, now: Long) = items.filter { rearmKind(it, now) != null }.map { it.id }

    @Test fun rearmKindDistinguishesDueFromLapse() {
        assertEquals(Rearm.DUE, rearmKind(item(1, false), 1_000L))
        assertEquals(Rearm.LAPSE, rearmKind(item(3, true).copy(returnAt = 2_000L), 1_000L))
        assertEquals("a lapse already past is not re-armed", null, rearmKind(item(3, true).copy(returnAt = 500L), 1_000L))
        assertEquals(null, rearmKind(item(2, false, deleted = 5L), 1_000L))
    }

    @Test fun deletedItemsAreNotRearmed() {
        val now = 1_000L
        val items = listOf(
            item(1, false),                                   // active, alive -> schedule
            item(2, false, deleted = 500L),                   // active, DELETED -> must skip
            item(3, true).copy(returnAt = 2_000L),            // lapse pending -> schedule
            item(4, true, deleted = 500L).copy(returnAt = 2_000L)  // lapse, DELETED -> must skip
        )
        assertEquals(listOf(1L, 3L), wouldSchedule(items, now))
    }

    // ---------------- Q14: bulk clear partitions on repeatMode ----------------

    /** v2.10 (N6 pt2): production bulkClearTargets, used by the list's bulk clear. */
    private fun doomed(group: List<Item>, alsoRepeats: Boolean) = bulkClearTargets(group, alsoRepeats)

    @Test fun allRecurringGroup_clearsNothingUntilTicked() {
        val g = listOf(item(1, true, repeat = "DAILY"), item(2, true, repeat = "WEEKLY"))
        assertTrue(doomed(g, false).isEmpty())
        assertFalse(doomed(g, false).isNotEmpty())
        assertEquals(2, doomed(g, true).size)
    }

    @Test fun mixedGroup_clearsOnlyTheFinishedOnes() {
        val g = listOf(item(1, true), item(2, true, repeat = "DAILY"), item(3, true))
        assertEquals(listOf(1L, 3L), doomed(g, false).map { it.id })
        assertEquals(3, doomed(g, true).size)
    }

    @Test fun nonRecurringGroup_behavesAsBefore() {
        val g = listOf(item(1, true), item(2, true))
        assertEquals(2, doomed(g, false).size)
        assertEquals(doomed(g, false), doomed(g, true))
    }

    // ---------------- N4: WhatsApp number normalisation ----------------

    /** v2.10 (N6 pt2): production waDigits, which CallActions.waNumber delegates to. */
    private fun wa(number: String, cc: String = "91"): String = waDigits(number, cc)

    @Test fun searchFindsNotesAndGroupsNotJustTitles() {
        // the old mirror searched titles only; production also searches notes/group/topic/shop/platform
        val all = listOf(item(1, false).copy(notes = "buy milk"), item(2, false).copy(group = "Dairy"), item(3, false))
        assertEquals(listOf(1L), scope(all, false, false, false, "milk").map { it.id })
        assertEquals(listOf(2L), scope(all, false, false, false, "dairy").map { it.id })
    }

    @Test fun tenDigitGetsTheDefaultCode() = assertEquals("919876543210", wa("9876543210"))

    @Test fun alreadyInternationalIsLeftAlone() =
        assertEquals("919876543210", wa("+91 98765 43210"))

    @Test fun punctuationIsStripped() = assertEquals("919876543210", wa("(987) 654-3210"))

    @Test fun otherCountryCodeHonoured() = assertEquals("449876543210", wa("9876543210", "44"))

    @Test fun blankCountryCodeFallsBack() = assertEquals("919876543210", wa("9876543210", ""))

    @Test fun garbageYieldsNothingUsable() {
        assertEquals("", wa("abc"))
        assertTrue(wa("12").length < 8)
    }
}
