package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.49 — Shop/UI improvements + two-step calendar selection.
 * Rendering (chips, tag colours, picker scrolling) is device-checklist; these cover the logic.
 */
class V149Test {

    // ---- 1. unit price to 4 decimals ----

    @Test fun unit_price_shows_four_decimals() {
        assertEquals("0.0625", fmtUnitPrice(0.0625))
        assertEquals("50", fmtUnitPrice(50.0))          // v1.51: zeros trimmed, no padding
        assertEquals("0.001", fmtUnitPrice(0.001))      // v1.51: trailing zero trimmed
    }

    @Test fun tiny_unit_price_no_longer_rounds_to_zero() {
        // the v1.48 bug: %.0f turned 0.0625 into "0"
        assertTrue(fmtUnitPrice(0.0625) != "0")
    }

    // ---- 2. Best U.P ranks (labels only, no reordering) ----

    private fun shopItem(id: Long, title: String, price: String?, qty: String?, unit: String?, created: Long = id) =
        Item(id = id, tab = Tab.SHOP, title = title, price = price, quantity = qty, unit = unit, createdAt = created)

    @Test fun cheapest_gets_rank_one_and_others_follow() {
        val items = listOf(
            shopItem(1, "Rice", "100", "1", "kg"),   // 100/kg
            shopItem(2, "Rice", "45", "1", "kg"),    // 45/kg  -> best
            shopItem(3, "Rice", "60", "1", "kg")     // 60/kg
        )
        val r = bestUpRanks(items)
        assertEquals(1, r[2L]); assertEquals(2, r[3L]); assertEquals(3, r[1L])
    }

    @Test fun ranks_normalise_units_within_a_family() {
        val items = listOf(
            shopItem(1, "Rice", "100", "1", "kg"),   // 0.1 / g
            shopItem(2, "Rice", "60", "500", "g")    // 0.12 / g -> pricier
        )
        val r = bestUpRanks(items)
        assertEquals(1, r[1L]); assertEquals(2, r[2L])
    }

    @Test fun single_occurrence_gets_no_tag() {
        assertTrue(bestUpRanks(listOf(shopItem(1, "Solo", "10", "1", "kg"))).isEmpty())
    }

    @Test fun items_without_a_usable_price_get_no_tag() {
        val items = listOf(shopItem(1, "Rice", null, null, null), shopItem(2, "Rice", null, null, null))
        assertTrue(bestUpRanks(items).isEmpty())
    }

    @Test fun incomparable_units_are_not_ranked_against_each_other() {
        val items = listOf(
            shopItem(1, "Combo", "100", "1", "kg"),
            shopItem(2, "Combo", "5", "1", "pcs")
        )
        assertTrue("different families -> each group of one -> no tags", bestUpRanks(items).isEmpty())
    }

    @Test fun rank_labels_read_correctly() {
        assertEquals("Best U.P", upRankLabel(1))
        assertEquals("2 Best U.P", upRankLabel(2))
        assertEquals("5 Best U.P", upRankLabel(5))
    }

    // ---- 3. per-shop item counts ----

    @Test fun counts_active_items_matching_the_shop() {
        val items = listOf(
            Item(id = 1, tab = Tab.SHOP, title = "a", shopName = "Big Bazaar"),
            Item(id = 2, tab = Tab.SHOP, title = "b", shopName = " big bazaar "),   // trim + case
            Item(id = 3, tab = Tab.SHOP, title = "c", shopName = "Big Bazaar", done = true),
            Item(id = 4, tab = Tab.SHOP, title = "d", shopName = "Big Bazaar", deletedAt = 5L),
            Item(id = 5, tab = Tab.SHOP, title = "e", shopName = "Other")
        )
        assertEquals(2, shopItemCount(items, "Big Bazaar"))
        assertEquals(0, shopItemCount(items, "Nowhere"))
        assertEquals(0, shopItemCount(items, "   "))
    }

    // ---- 4. never repeat the grouped-by dimension as a chip ----

    @Test fun grouped_dimension_is_suppressed() {
        assertEquals("SHOP", suppressedChip("SHOP", Tab.SHOP))
        assertEquals("GROUP", suppressedChip("GROUP", Tab.SHOP))
        assertEquals("TOPIC", suppressedChip("GROUP", Tab.LEARN))
    }

    @Test fun by_date_keeps_the_card_time() {
        assertNull(suppressedChip("DATE", Tab.TASKS))
        assertNull(suppressedChip("NONE", Tab.TASKS))
        assertNull(suppressedChip("PRIORITY", Tab.TASKS))
    }

    // ---- calendar: account + calendars (empty = all) ----

    private val cals = listOf(
        CalSync.CalInfo(1, "Personal", "a@gmail.com"),
        CalSync.CalInfo(2, "Holidays", "a@gmail.com"),
        CalSync.CalInfo(3, "Work", "b@company.com")
    )

    @Test fun no_account_means_nothing_is_read() {
        assertTrue(selectedCalendarIds(AppSettings(), cals).isEmpty())
    }

    @Test fun empty_selection_means_every_calendar_in_the_account() {
        val s = AppSettings(calendarAccount = "a@gmail.com")
        assertEquals(listOf(1L, 2L), selectedCalendarIds(s, cals))
    }

    @Test fun narrowed_selection_is_respected_and_scoped_to_the_account() {
        val s = AppSettings(calendarAccount = "a@gmail.com", calendarIds = setOf(2L, 3L))
        assertEquals("id 3 belongs to another account", listOf(2L), selectedCalendarIds(s, cals))
    }

    @Test fun vanished_calendar_ids_are_dropped_not_crashed() {
        val s = AppSettings(calendarAccount = "a@gmail.com", calendarIds = setOf(99L))
        assertTrue(selectedCalendarIds(s, cals).isEmpty())
    }

    @Test fun accounts_are_listed_once_and_sorted() {
        assertEquals(listOf("a@gmail.com", "b@company.com"), calendarAccountsOf(cals))
    }
}
