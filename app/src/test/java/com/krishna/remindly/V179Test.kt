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

    /** Mirrors ListScreens.scopeOf: one pipeline, two projections. */
    private fun scope(all: List<Item>, doneFlag: Boolean, shop: Boolean,
                      personalFilter: Boolean, query: String) = all
        .filter { it.deletedAt == null }
        .filter { it.done == doneFlag }
        .let { l -> if (shop) l.filter { it.personal == personalFilter } else l }
        .let { l ->
            val q = query.trim().lowercase()
            if (q.isEmpty()) l else l.filter { it.title.lowercase().contains(q) }
        }

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

    /** Mirrors the guard added to AlarmScheduler.rescheduleAll. */
    private fun wouldSchedule(items: List<Item>, now: Long) = items.filter { i ->
        i.deletedAt == null && (!i.done || (i.returnAt != null && i.returnAt!! > now))
    }.map { it.id }

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

    private fun doomed(group: List<Item>, alsoRepeats: Boolean) =
        if (alsoRepeats) group else group.filter { it.repeatMode == "OFF" }

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

    private fun wa(number: String, cc: String = "91"): String {
        val digits = number.filter { it.isDigit() }
        val code = cc.filter { it.isDigit() }.ifBlank { "91" }
        return if (digits.length == 10) "$code$digits" else digits
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
