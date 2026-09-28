package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.43 Shop Phase D — "By Shop" grouping (pure). Geofence registration/arrival and shop sync are
 * device/backend-verified (no device or Firestore in the build env).
 */
class V143Test {

    private fun item(id: Long, shop: String?) =
        Item(id = id, tab = Tab.SHOP, title = "i$id", shopName = shop)

    @Test fun groups_by_shop_alphabetically() {
        val g = shopGroupsOf(listOf(item(1, "Zephyr"), item(2, "Anna"), item(3, "Anna")))
        assertEquals(listOf("Anna", "Zephyr"), g.map { it.first })
        assertEquals(2, g.first { it.first == "Anna" }.second.size)
    }

    @Test fun unnamed_go_last_under_no_shop() {
        val g = shopGroupsOf(listOf(item(1, "Big Bazaar"), item(2, null), item(3, "  ")))
        assertEquals(listOf("Big Bazaar", "No Shop"), g.map { it.first })
        assertEquals(2, g.first { it.first == "No Shop" }.second.size)
    }

    @Test fun trims_and_is_case_sensitive_label_but_grouped_by_trimmed() {
        // trailing space shouldn't create a separate bucket from the trimmed name
        val g = shopGroupsOf(listOf(item(1, "Mart"), item(2, "Mart ")))
        assertEquals(listOf("Mart"), g.map { it.first })
        assertEquals(2, g.first().second.size)
    }

    @Test fun empty_input_gives_no_groups() {
        assertEquals(emptyList<Pair<String, List<Item>>>(), shopGroupsOf(emptyList()))
    }
}
