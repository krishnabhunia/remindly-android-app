package com.krishna.remindly

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.05 — N37 (per-shop arrival alert: Notify / Ring / Alarm, default + cooldown) and N38 (the
 * "Buy Now" view: a filter over ACTIVE scoped to the shop that last triggered a geofence).
 * Alarm cards, the ring service, the 3-segment control and the geofence itself are device-only
 * (checklist section P) — stated honestly.
 */
class V205Test {

    private val gson = Gson()

    private fun shop(id: Long, name: String, types: String? = null, fired: Long = 0L, deleted: Long? = null) =
        Shop(id = id, name = name, lat = 1.0, lng = 2.0, arriveTypes = types, lastArriveFired = fired, deletedAt = deleted)

    private fun buy(id: Long, title: String, shopId: Long? = null, shopName: String? = null,
                    done: Boolean = false, deleted: Long? = null, tab: Tab = Tab.SHOP) =
        Item(id = id, tab = tab, title = title, shopId = shopId, shopName = shopName, done = done, deletedAt = deleted)

    // ---------------------------------------------------------------- N37: alert type sets

    @Test fun normalizeAlertTypes_keepsNRA_inOrder_deduped() {
        assertEquals("NRA", normalizeAlertTypes("arn"))
        assertEquals("NA", normalizeAlertTypes("AANN"))
        assertEquals("R", normalizeAlertTypes("r"))
        assertEquals("N", normalizeAlertTypes("Nx!7"))
    }

    @Test fun normalizeAlertTypes_nullVsDeliberateSilence() {
        assertNull("null = follow the default", normalizeAlertTypes(null))
        assertNull("blank = follow the default", normalizeAlertTypes("   "))
        assertEquals("", normalizeAlertTypes("zzz"))   // typed something with no N/R/A → silent
    }

    @Test fun resolveShopArriveTypes_perShopWins_elseGlobalDefault() {
        val s = AppSettings(shopArriveTypes = "RN")
        assertEquals("NR", resolveShopArriveTypes(shop(1, "A", types = "rn"), s))
        assertEquals("A", resolveShopArriveTypes(shop(1, "A", types = "A"), s))
        assertEquals("NR", resolveShopArriveTypes(shop(1, "A", types = null), s))     // default
        assertEquals("", resolveShopArriveTypes(shop(1, "A", types = ""), s))          // silent by choice
        assertEquals("N", resolveShopArriveTypes(shop(1, "A"), AppSettings(shopArriveTypes = "junk")))
    }

    @Test fun defaultArrivalIsNotify_soNothingChangesUntilChosen() {
        assertEquals("N", AppSettings().shopArriveTypes)
        assertEquals(10, AppSettings().shopArriveCooldownMin)
        assertEquals("N", resolveShopArriveTypes(shop(1, "A"), AppSettings()))
    }

    @Test fun alertTypesLabel_readsBackAsShown() {
        assertEquals("Default", alertTypesLabel(null))
        assertEquals("Silent", alertTypesLabel(""))
        assertEquals("Notify", alertTypesLabel("N"))
        assertEquals("Ring + Notify", alertTypesLabel("NR"))
        assertEquals("Alarm + Notify", alertTypesLabel("NRA"))   // Alarm outranks Ring for sound
        assertEquals("Alarm", alertTypesLabel("A"))
    }

    // ---------------------------------------------------------------- N37: cooldown

    @Test fun cooldown_blocksRepeatArrivals_thenAllows() {
        val s = AppSettings(shopArriveCooldownMin = 10)
        val now = 1_000_000_000L
        assertFalse(arriveCooldownPassed(shop(1, "A", fired = now - 5 * 60_000L), s, now))
        assertTrue(arriveCooldownPassed(shop(1, "A", fired = now - 10 * 60_000L), s, now))
        assertTrue("never fired before", arriveCooldownPassed(shop(1, "A", fired = 0L), s, now))
    }

    @Test fun cooldown_offMeansAlwaysAllowed() {
        val now = 5_000L
        assertTrue(arriveCooldownPassed(shop(1, "A", fired = now), AppSettings(shopArriveCooldownMin = 0), now))
        assertTrue(arriveCooldownPassed(shop(1, "A", fired = now), AppSettings(shopArriveCooldownMin = -3), now))
    }

    // ---------------------------------------------------------------- N38: the Buy Now filter

    @Test fun buyNow_takesOnlyThatShopsLiveActiveItems() {
        val s = shop(7, "D-Mart – Kalyan")
        val items = listOf(
            buy(1, "Rice", shopId = 7),
            buy(2, "Toothpaste", shopName = "D-Mart – Kalyan"),          // legacy free-text match
            buy(3, "Milk", shopId = 8),                                   // other shop
            buy(4, "Oil", shopId = 7, done = true),                       // done
            buy(5, "Soap", shopId = 7, deleted = 99L),                    // deleted
            buy(6, "Pay bill", shopId = 7, tab = Tab.TASKS),              // not a buy item
            buy(7, "Ghee", shopName = "d-mart – kalyan")                  // case-insensitive
        )
        assertEquals(listOf(1L, 2L, 7L), buyNowItems(items, s).map { it.id })
    }

    @Test fun buyNow_shopIdWinsOverName_andNullShopIsEmpty() {
        val s = shop(7, "D-Mart – Kalyan")
        // an item explicitly tagged to ANOTHER shop is excluded even if the name matches
        val misTagged = buy(1, "Rice", shopId = 8, shopName = "D-Mart – Kalyan")
        assertTrue(buyNowItems(listOf(misTagged), s).isEmpty())
        assertTrue(buyNowItems(listOf(buy(2, "Rice", shopId = 7)), null).isEmpty())
        assertTrue(buyNowItems(listOf(buy(3, "Rice", shopId = 7)), shop(7, "x", deleted = 5L)).isEmpty())
    }

    @Test fun buyNowLabel_ellipsisesLongShopNames() {
        assertEquals("Buy Now", buyNowLabel(null))
        assertEquals("Buy Now", buyNowLabel("   "))
        assertEquals("Buy Now · More", buyNowLabel("More"))
        val long = buyNowLabel("Reliance Smart Bazaar – Kalyan")
        assertTrue(long.startsWith("Buy Now · "))
        assertTrue(long.endsWith("…"))
        assertTrue("label stays short", long.length <= "Buy Now · ".length + 14)
    }

    @Test fun buyNow_autoHidesWhenItsShopIsGone() {
        val live = mapOf(7L to shop(7, "A"), 8L to shop(8, "B", deleted = 1L))
        assertTrue(buyNowStillValid(7L, live))
        assertFalse("deleted shop", buyNowStillValid(8L, live))
        assertFalse("unknown shop", buyNowStillValid(9L, live))
        assertFalse("not armed", buyNowStillValid(null, live))
    }

    // ---------------------------------------------------------------- persistence & schema

    @Test fun shopArrivalFields_surviveJsonRoundTrip_andHeal() {
        val s = shop(1, "D-Mart", types = "ar", fired = 42L)
        val back = healShop(gson.fromJson(gson.toJson(s), Shop::class.java))
        assertEquals("RA", back.arriveTypes)
        assertEquals(42L, back.lastArriveFired)
        // pre-39 JSON: no arriveTypes at all → null (= default), and copy() must not throw
        val legacy = healShop(gson.fromJson("{\"id\":2,\"name\":\"Old\"}", Shop::class.java))
        assertNull(legacy.arriveTypes)
        assertEquals(0L, legacy.lastArriveFired)
        legacy.copy(name = "x")
    }

    @Test fun settingsHeal_coatsArrivalDefaults() {
        val legacy = gson.fromJson("{\"ver\":38}", AppSettings::class.java)
        val h = healSettings(legacy)
        assertEquals("N", h.shopArriveTypes)
        assertEquals(10, h.shopArriveCooldownMin)
        assertEquals("negative cooldown heals to the default", 10, healSettings(AppSettings(shopArriveCooldownMin = -1)).shopArriveCooldownMin)
        h.copy(shopArriveTypes = "A")
    }

    @Test fun shopsReset_restoresArrivalDefaults_only() {
        val s = AppSettings(shopArriveTypes = "A", shopArriveCooldownMin = 60, shopCheckoutCalc = false, theme = "DARK")
        val r = resetSettingsFor(s, "SHOPS")
        assertEquals("N", r.shopArriveTypes)
        assertEquals(10, r.shopArriveCooldownMin)
        assertFalse("buy-list scope untouched", r.shopCheckoutCalc)
        assertEquals("DARK", r.theme)
    }

    @Test fun v205_schemaIs39() {
        assertEquals(42, AppSettings().ver)
        assertEquals(42, healSettings(AppSettings()).ver)
    }
}
