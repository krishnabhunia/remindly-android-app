package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.29 item 2: the Day header now appends the day's shopping total, reusing the same
 * `spendLabel` the Year header uses. These pin the pure sum/format logic.
 * (item 1 is a pure layout justify — device-checklist only.)
 */
class V129Test {

    private fun shopItem(id: Long, price: String?) =
        Item(id = id, tab = Tab.SHOP, title = "i$id", price = price)

    @Test fun spendLabel_sums_a_days_prices_ignoring_blanks_and_symbols() {
        val day = listOf(
            shopItem(1, "₹1,200"),   // 1200
            shopItem(2, "300"),      // 300
            shopItem(3, null),       // ignored
            shopItem(4, "   "),      // ignored
            shopItem(5, "50.50")     // 50.5
        )
        // 1200 + 300 + 50.5 = 1550.5 -> two decimals because it isn't whole
        assertEquals(" · ₹1,550.50", spendLabel(day))
    }

    @Test fun spendLabel_whole_total_has_no_decimals() {
        assertEquals(" · ₹1,500", spendLabel(listOf(shopItem(1, "₹1,200"), shopItem(2, "300"))))
    }

    @Test fun spendLabel_empty_when_no_priced_items() {
        assertEquals("", spendLabel(listOf(shopItem(1, null), shopItem(2, ""))))
        assertEquals("", spendLabel(emptyList()))
    }

    @Test fun shopSpend_parses_currency_formatting() {
        assertEquals(1500.0, shopSpend(listOf(shopItem(1, "₹1,200"), shopItem(2, "300"))), 0.001)
    }
}
