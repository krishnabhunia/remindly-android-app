package com.krishna.remindly

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.37 — Shop overhaul, Phase A (Shop registry). Store logic is fully unit-tested; the drawer
 * UI (hamburger, add/edit dialog, map picker) is device-checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V137Test {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "shops.json").delete()
        Stores.init(context)
        ShopStore.replaceAll(emptyList())
    }

    private fun shop(id: Long, name: String, default: Boolean = false, geo: Boolean = false) = Shop(
        id = id, name = name, isDefault = default,
        lat = if (geo) 22.5 else null, lng = if (geo) 88.3 else null
    )

    @Test fun active_is_sorted_and_excludes_tombstones() {
        ShopStore.upsert(shop(1, "Zephyr Mart"))
        ShopStore.upsert(shop(2, "Anna Stores"))
        ShopStore.delete(1)
        val active = ShopStore.active()
        assertEquals(listOf("Anna Stores"), active.map { it.name })
    }

    @Test fun byName_is_trim_and_case_insensitive() {
        ShopStore.upsert(shop(1, "Big Bazaar"))
        assertNotNull(ShopStore.byName("  big bazaar "))
        assertNull(ShopStore.byName("bigbazaar"))
    }

    @Test fun hasGeofence_reflects_coordinates() {
        ShopStore.upsert(shop(1, "Geo", geo = true))
        ShopStore.upsert(shop(2, "NoGeo", geo = false))
        assertTrue(ShopStore.get(1)!!.hasGeofence)
        assertFalse(ShopStore.get(2)!!.hasGeofence)
    }

    // ---- single-default invariant ----

    @Test fun upserting_a_default_demotes_the_previous_default() {
        ShopStore.upsert(shop(1, "A", default = true))
        ShopStore.upsert(shop(2, "B", default = true))
        assertEquals(2L, ShopStore.default()!!.id)
        assertEquals(1, ShopStore.active().count { it.isDefault })
        assertFalse(ShopStore.get(1)!!.isDefault)
    }

    @Test fun setDefault_makes_exactly_one_default() {
        ShopStore.upsert(shop(1, "A"))
        ShopStore.upsert(shop(2, "B"))
        ShopStore.upsert(shop(3, "C"))
        ShopStore.setDefault(2)
        assertEquals(2L, ShopStore.default()!!.id)
        assertEquals(1, ShopStore.active().count { it.isDefault })
    }

    @Test fun deleting_the_default_clears_the_default() {
        ShopStore.upsert(shop(1, "Only", default = true))
        assertNotNull(ShopStore.default())
        ShopStore.delete(1)
        assertNull("no ghost default after delete", ShopStore.default())
        assertNull(ShopStore.get(1))
    }

    @Test fun soft_delete_keeps_a_tombstone() {
        ShopStore.upsert(shop(5, "Temp"))
        ShopStore.delete(5)
        val raw = ShopStore.shops.value.first { it.id == 5L }
        assertNotNull(raw.deletedAt)
        assertNull(ShopStore.get(5))
    }

    @Test fun healShop_defaults_radius_when_missing() {
        val healed = healShop(Shop(id = 1, name = "X", radius = 0f))
        assertEquals(150f, healed.radius)
    }
}
