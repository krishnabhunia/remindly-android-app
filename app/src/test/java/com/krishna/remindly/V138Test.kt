package com.krishna.remindly

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.38 — Shop Phase A follow-up bugs. UI parts (hamburger contrast/placement, shop dropdown)
 * are device-checklist; these cover the two pieces with logic: City removal is clean (Item still
 * heals) and the shop-item editor's default-shop pre-fill source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V138Test {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "shops.json").delete()
        Stores.init(context)
        ShopStore.replaceAll(emptyList())
    }

    // #3 — City removed: an Item round-trips through heal with shop fields intact and no city.
    @Test fun item_heals_with_shop_fields_and_no_city() {
        val i = Item(id = 1, tab = Tab.SHOP, title = "Rice", shopName = "Anna Stores", unit = "kg", quantity = "2", price = "90")
        val h = healItem(i)
        assertEquals("Anna Stores", h.shopName)
        assertEquals("kg", h.unit)
        assertEquals("2", h.quantity)
        assertEquals("90", h.price)
    }

    // #2 — the value the editor pre-fills for a NEW shop item comes from the default shop.
    @Test fun default_shop_name_is_available_for_prefill() {
        assertNull("no default → no pre-fill", ShopStore.default())
        ShopStore.upsert(Shop(id = 10, name = "Big Bazaar", isDefault = true))
        assertEquals("Big Bazaar", ShopStore.default()?.name)
    }
}
