package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.39 — Shop Phase B: the checkout calculator math (pure, fully tested here). The dialog UI and
 * the completion intercept are device-checklist.
 */
class V139Test {

    private fun eq(a: Double?, b: Double, msg: String = "") =
        assertEquals(msg, b, a!!, 0.001)

    @Test fun unit_price_times_qty_gives_cost() {
        val c = computeShopCalc(unitPriceIn = 45.0, qtyIn = 2.0, costIn = null, paidIn = null)
        eq(c.cost, 90.0, "cost = unit × qty")
        eq(c.unitPrice, 45.0); eq(c.qty, 2.0)
    }

    @Test fun qty_and_cost_give_unit_price() {
        val c = computeShopCalc(unitPriceIn = null, qtyIn = 4.0, costIn = 200.0, paidIn = null)
        eq(c.unitPrice, 50.0, "unit = cost / qty")
        eq(c.cost, 200.0)
    }

    @Test fun unit_price_and_cost_give_qty() {
        val c = computeShopCalc(unitPriceIn = 25.0, qtyIn = null, costIn = 100.0, paidIn = null)
        eq(c.qty, 4.0, "qty = cost / unit")
    }

    @Test fun buy_price_yields_discount_amount_and_percent() {
        // MRP 200, paid 150 → discount 50, 25%
        val c = computeShopCalc(unitPriceIn = 50.0, qtyIn = 4.0, costIn = null, paidIn = 150.0)
        eq(c.cost, 200.0)
        eq(c.discountAmt, 50.0)
        eq(c.discountPct, 25.0)
    }

    @Test fun no_buy_price_means_no_discount() {
        val c = computeShopCalc(60.0, 1.0, null, null)
        assertNull(c.discountAmt)
        assertNull(c.discountPct)
    }

    @Test fun zero_qty_cannot_derive_unit_price() {
        val c = computeShopCalc(unitPriceIn = null, qtyIn = 0.0, costIn = 100.0, paidIn = null)
        assertNull("no divide-by-zero", c.unitPrice)
    }

    @Test fun zero_unit_price_cannot_derive_qty() {
        val c = computeShopCalc(unitPriceIn = 0.0, qtyIn = null, costIn = 100.0, paidIn = null)
        assertNull(c.qty)
    }

    @Test fun nothing_entered_gives_no_cost() {
        val c = computeShopCalc(null, null, null, null)
        assertNull(c.cost)
    }

    @Test fun explicit_cost_is_respected_when_all_three_given() {
        // user overrode cost to 210 even though 50×4=200 → keep the explicit cost
        val c = computeShopCalc(unitPriceIn = 50.0, qtyIn = 4.0, costIn = 210.0, paidIn = null)
        eq(c.cost, 210.0)
    }

    @Test fun pushPurchase_keeps_last_12() {
        var h = emptyList<PricePoint>()
        for (i in 1..15) h = pushPurchase(h, PricePoint(at = i.toLong(), price = i.toDouble()))
        assertEquals(12, h.size)
        assertEquals(4L, h.first().at)   // 15 pushed, oldest kept is #4
        assertEquals(15L, h.last().at)
    }

    @Test fun trimNum_drops_trailing_zero() {
        assertEquals("2", trimNum(2.0))
        assertEquals("1.5", trimNum(1.5))
    }
}
