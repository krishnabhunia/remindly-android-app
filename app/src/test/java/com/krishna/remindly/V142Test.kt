package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.42 Shop Phase C — cheapest-shop recommendation by unit price, normalized within a unit-family
 * (g↔kg, ml↔L, dozen=12pcs). Pure engine tests; the editor chip is device-checklist.
 */
class V142Test {

    private fun purchase(shop: String, unitPrice: Double, unit: String, at: Long) =
        PricePoint(at = at, shop = shop, unitPrice = unitPrice, unit = unit, qty = 1.0)

    private fun shopItem(title: String, history: List<PricePoint>, deleted: Long? = null) =
        Item(id = title.hashCode().toLong(), tab = Tab.SHOP, title = title, priceHistory = history, deletedAt = deleted)

    @Test fun normalization_makes_kg_and_g_comparable() {
        // ₹50/kg = ₹0.05/g ; ₹30/500g scenario expressed as ₹60/kg equivalent (₹0.06/g)
        val items = listOf(
            shopItem("Rice", listOf(
                purchase("A", 50.0, "kg", 100),   // 0.05 / g
                purchase("B", 0.06, "g", 200)     // 0.06 / g  → A is cheaper
            ))
        )
        val rec = cheapestShop(items, "rice", "kg")!!
        assertEquals("A", rec.shop)
    }

    @Test fun dozen_normalizes_to_twelve_pieces() {
        val items = listOf(
            shopItem("Eggs", listOf(
                purchase("Mart", 60.0, "dozen", 100),  // 5.0 / pc
                purchase("Corner", 6.0, "pcs", 200)    // 6.0 / pc → Mart cheaper
            ))
        )
        val rec = cheapestShop(items, "Eggs", "pcs")!!
        assertEquals("Mart", rec.shop)
        assertEquals(5.0, rec.perBase, 1e-9)
    }

    @Test fun aggregates_across_separate_items_with_same_name() {
        val items = listOf(
            shopItem("Milk", listOf(purchase("Shop1", 55.0, "L", 100))),
            shopItem("Milk", listOf(purchase("Shop2", 50.0, "L", 150)))   // cheaper
        )
        assertEquals("Shop2", cheapestShop(items, "milk")!!.shop)
    }

    @Test fun ties_break_to_most_recent() {
        val items = listOf(
            shopItem("Sugar", listOf(
                purchase("Old", 40.0, "kg", 100),
                purchase("New", 40.0, "kg", 500)   // same price, newer
            ))
        )
        assertEquals("New", cheapestShop(items, "Sugar")!!.shop)
    }

    @Test fun ignores_records_without_shop_or_price_and_deleted_items() {
        val items = listOf(
            shopItem("Tea", listOf(
                PricePoint(at = 100, price = 90.0),                 // legacy, no shop/unitPrice → ignored
                purchase("", 80.0, "kg", 150),                      // no shop → ignored
                purchase("Real", 70.0, "kg", 200)
            )),
            shopItem("Tea", listOf(purchase("Ghost", 10.0, "kg", 300)), deleted = 5L)  // deleted → ignored
        )
        val rec = cheapestShop(items, "Tea")!!
        assertEquals("Real", rec.shop)
    }

    @Test fun empty_when_no_comparable_history() {
        assertNull(cheapestShop(emptyList(), "Anything"))
        assertNull(cheapestShop(listOf(shopItem("X", emptyList())), "X"))
        assertNull(cheapestShop(listOf(shopItem("X", listOf(purchase("A", 5.0, "kg", 1)))), ""))
    }

    @Test fun prefer_unit_restricts_to_that_family_when_present() {
        val items = listOf(
            shopItem("Combo", listOf(
                purchase("WeightShop", 100.0, "kg", 100),  // WEIGHT
                purchase("CountShop", 2.0, "pcs", 500)     // COUNT (more recent)
            ))
        )
        // prefer kg → WEIGHT family only → WeightShop
        assertEquals("WeightShop", cheapestShop(items, "Combo", "kg")!!.shop)
        // no preference → most-recent family (COUNT) → CountShop
        assertEquals("CountShop", cheapestShop(items, "Combo", null)!!.shop)
    }
}
