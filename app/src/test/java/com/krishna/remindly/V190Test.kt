package com.krishna.remindly

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.90 — two-mode redesign, the pure layer. Everything a unit can prove is proven here:
 * schema heals for the six new settings fields and four new entities, the one-time city
 * seeding migration, mode-aware nav visibility, product search + cheapest-link maths, the
 * checkout price-memory merge, and the city/chain delete cascades. Compose surfaces, geofence
 * firing and sheet insets are device-checklist territory (section K) — stated honestly.
 */
class V190Test {

    private val gson = Gson()

    private fun shop(
        id: Long, name: String, area: String? = null, cityId: Long? = null,
        chainId: Long? = null, deletedAt: Long? = null, isDefault: Boolean = false
    ) = Shop(id = id, name = name, area = area, cityId = cityId, chainId = chainId,
        deletedAt = deletedAt, isDefault = isDefault)

    private fun link(p: Long, s: Long, price: Double = 0.0, up: Double = 0.0, del: Long? = null) =
        ProductLink(productId = p, shopId = s, lastPrice = price, lastUnitPrice = up, lastAt = 1L, deletedAt = del)

    // ---------------------------------------------------------------- heals (coat rule)

    @Test fun healSettings_coatsNullModeFields_fromLegacyJson() {
        // Pre-35 JSON has none of the new fields → Gson leaves the Strings null; the very
        // first copy() would throw without the coat (v1.72 rule).
        val legacy = gson.fromJson("{\"ver\":34}", AppSettings::class.java)
        val healed = healSettings(legacy)
        assertEquals(150f, healed.shopNewRadius)
        // and the copy() itself must not throw:
        healed.copy(shopNewRadius = 200f)
    }

    @Test fun healSettings_normalizesGarbageValues() {
        // a non-positive stored radius falls back to the 150 m DEFAULT (not the smallest stop)
        val h = healSettings(AppSettings(shopNewRadius = -3f))
        assertEquals(150f, h.shopNewRadius)
        assertEquals(400f, healSettings(AppSettings(shopNewRadius = 420f)).shopNewRadius)   // snaps to a stop
    }

    @Test fun healSettings_snapsShopNewRadius() {
        assertEquals(150f, healSettings(AppSettings(shopNewRadius = -5f)).shopNewRadius)
        assertEquals(3000f, healSettings(AppSettings(shopNewRadius = 99999f)).shopNewRadius)   // v2.02 scale tops at 3 km
        assertEquals(200f, healSettings(AppSettings(shopNewRadius = 199f)).shopNewRadius)
    }

    @Test fun heals_newEntities_surviveNullFieldsFromGson() {
        val c = gson.fromJson("{\"id\":1}", City::class.java)
        assertEquals("", healCity(c).name)
        val ch = gson.fromJson("{\"id\":2}", Chain::class.java)
        assertEquals("", healChain(ch).name)
        val p = gson.fromJson("{\"id\":3}", Product::class.java)
        val hp = healProduct(p)
        assertEquals("", hp.name); assertNull(hp.category); assertNull(hp.defaultUnit)
        val l = gson.fromJson("{\"productId\":3,\"shopId\":4,\"lastPrice\":-2,\"lastUnitPrice\":\"NaN\"}", ProductLink::class.java)
        val hl = healLink(l)
        assertEquals(0.0, hl.lastPrice, 0.0); assertEquals(0.0, hl.lastUnitPrice, 0.0)
    }

    @Test fun healItem_legacyJsonWithoutV190Fields_copiesSafely() {
        val i = gson.fromJson("{\"id\":9,\"tab\":\"SHOP\",\"title\":\"Rice\"}", Item::class.java)
        val h = healItem(i)
        assertNull(h.productId); assertNull(h.shopId)
        h.copy(title = "x")   // nullable adds must never break copy()
    }

    // ---------------------------------------------------------------- city seeding migration

    @Test fun seed_createsCityPerDistinctArea_caseInsensitive() {
        val shops = listOf(
            shop(1, "Big Bazaar", area = "Serampore"),
            shop(2, "More", area = "serampore "),
            shop(3, "Spencer's", area = "Kolkata")
        )
        var next = 100L
        val (cities, updated) = seedCitiesFromShops(shops, emptyList(), { next++ }, now = 5L)
        assertEquals(2, cities.size)
        val ser = cities.first { it.name.equals("Serampore", true) }
        assertEquals(ser.id, updated[0].cityId)
        assertEquals(ser.id, updated[1].cityId)   // dedup despite case/space
        assertEquals(cities.first { it.name == "Kolkata" }.id, updated[2].cityId)
    }

    @Test fun seed_blankOrMissingArea_staysUnassigned() {
        val shops = listOf(shop(1, "A", area = null), shop(2, "B", area = "   "))
        val (cities, updated) = seedCitiesFromShops(shops, emptyList(), { 1L }, 0L)
        assertTrue(cities.isEmpty())
        assertNull(updated[0].cityId); assertNull(updated[1].cityId)
    }

    @Test fun seed_isIdempotent_andPreservesExistingAssignments() {
        val shops = listOf(shop(1, "A", area = "Serampore"))
        var next = 10L
        val (c1, s1) = seedCitiesFromShops(shops, emptyList(), { next++ }, 0L)
        val (c2, s2) = seedCitiesFromShops(s1, c1, { next++ }, 1L)
        assertEquals(c1, c2)              // no duplicate city on the second pass
        assertEquals(s1, s2)              // shops untouched once assigned
    }

    @Test fun seed_reusesExistingCityByName_andSkipsDeletedShops() {
        val existing = listOf(City(id = 7, name = "Serampore"))
        val shops = listOf(shop(1, "A", area = "SERAMPORE"), shop(2, "Dead", area = "Delhi", deletedAt = 9L))
        val (cities, updated) = seedCitiesFromShops(shops, existing, { 99L }, 0L)
        assertEquals(1, cities.size)              // no new city — reused id 7, Delhi skipped
        assertEquals(7L, updated[0].cityId)
        assertNull(updated[1].cityId)             // deleted shop untouched
    }

    // ---------------------------------------------------------------- names & search

    @Test fun branchAutoName_edges() {
        assertEquals("D-Mart – Serampore", branchAutoName("D-Mart", "Serampore"))
        assertEquals("D-Mart", branchAutoName(" D-Mart ", "  "))
        assertEquals("Serampore", branchAutoName("", "Serampore"))
        assertEquals("", branchAutoName(" ", " "))
    }

    @Test fun productMatches_nameCategoryAndBlank() {
        val p = Product(1, "Basmati Rice", category = "Grocery")
        assertTrue(productMatches(p, "rice"))
        assertTrue(productMatches(p, "GROC"))
        assertTrue(productMatches(p, "   "))
        assertFalse(productMatches(p, "milk"))
    }

    // ---------------------------------------------------------------- cheapest link + labels

    @Test fun cheapestLink_prefersUnitPrice_thenPrice_skipsDeadRows() {
        val live = setOf(1L, 2L, 3L)
        val links = listOf(
            link(9, 1, price = 100.0, up = 52.0),
            link(9, 2, price = 90.0, up = 48.0),        // winner (lowest unit price)
            link(9, 3, price = 10.0, up = 0.0),         // no unit price → loses to any up>0
            link(9, 4, price = 1.0, up = 1.0),          // shop 4 not live
            link(9, 2, price = 1.0, up = 1.0, del = 5L) // deleted link ignored
                .copy(shopId = 5)
        )
        assertEquals(2L, cheapestLink(links, live)?.shopId)
    }

    @Test fun cheapestLink_fallsBackToTotalPrice_whenNoUnitPrices() {
        val links = listOf(link(9, 1, price = 80.0), link(9, 2, price = 60.0))
        assertEquals(2L, cheapestLink(links, setOf(1L, 2L))?.shopId)
        assertNull(cheapestLink(emptyList(), setOf(1L)))
        assertNull(cheapestLink(links, emptySet()))
    }

    @Test fun buyShopLabel_shopIdWins_deletedFallsBack_blankIsNull() {
        val shops = mapOf(
            1L to shop(1, "D-Mart – Serampore"),
            2L to shop(2, "Old", deletedAt = 9L)
        )
        val byId = Item(id = 1, tab = Tab.SHOP, title = "x", shopId = 1L, shopName = "typed")
        assertEquals("D-Mart – Serampore", buyShopLabel(byId, shops))
        val stale = Item(id = 2, tab = Tab.SHOP, title = "x", shopId = 2L, shopName = "Corner store")
        assertEquals("Corner store", buyShopLabel(stale, shops))
        val blank = Item(id = 3, tab = Tab.SHOP, title = "x", shopName = "   ")
        assertNull(buyShopLabel(blank, shops))
    }

    // ---------------------------------------------------------------- checkout price memory

    @Test fun mergedLinkPrice_zeroNeverErases_freshValuesWin() {
        val prev = link(9, 1, price = 100.0, up = 50.0)
        val m1 = mergedLinkPrice(prev, 9, 1, price = 0.0, unitPrice = 0.0, at = 7L)
        assertEquals(100.0, m1.lastPrice, 0.0); assertEquals(50.0, m1.lastUnitPrice, 0.0)
        assertEquals(7L, m1.lastAt); assertNull(m1.deletedAt)
        val m2 = mergedLinkPrice(prev, 9, 1, price = 120.0, unitPrice = 55.0, at = 8L)
        assertEquals(120.0, m2.lastPrice, 0.0); assertEquals(55.0, m2.lastUnitPrice, 0.0)
        val m3 = mergedLinkPrice(null, 9, 1, price = 0.0, unitPrice = 33.0, at = 9L)
        assertEquals(0.0, m3.lastPrice, 0.0); assertEquals(33.0, m3.lastUnitPrice, 0.0)
    }

    // ---------------------------------------------------------------- delete cascades (pure)

    @Test fun cityDelete_orphansItsShopsOnly() {
        val out = shopsAfterCityDelete(listOf(shop(1, "A", cityId = 5), shop(2, "B", cityId = 6)), 5L)
        assertNull(out[0].cityId); assertEquals(6L, out[1].cityId)
    }

    @Test fun chainDelete_keepBranches_makesThemLocal() {
        val out = shopsAfterChainDelete(
            listOf(shop(1, "D1", chainId = 9, isDefault = true), shop(2, "L", chainId = null)),
            chainId = 9, keepBranches = true, now = 4L
        )
        assertNull(out[0].chainId); assertNull(out[0].deletedAt); assertTrue(out[0].isDefault)
        assertEquals("L", out[1].name)
    }

    @Test fun chainDelete_deleteBranches_tombstonesAndDropsDefault() {
        val out = shopsAfterChainDelete(
            listOf(shop(1, "D1", chainId = 9, isDefault = true), shop(2, "D2", chainId = 9, deletedAt = 2L)),
            chainId = 9, keepBranches = false, now = 4L
        )
        assertEquals(4L, out[0].deletedAt); assertFalse(out[0].isDefault)
        assertEquals(2L, out[1].deletedAt)   // an existing tombstone is kept, not overwritten
    }

    // ---------------------------------------------------------------- mode-aware nav

    @Test fun normalizers_defaultUnknownValues() {
        assertEquals("TASK", modeNormalized(null)); assertEquals("SHOP", modeNormalized("SHOP"))
    }

    @Test fun taskMode_hidesShopTab_respectsUserHiddenTabs() {
        val s = AppSettings()
        assertTrue(taskModeTabVisible(s, 0))
        assertFalse(taskModeTabVisible(s, 1))                       // Shop never in Task mode
        assertTrue(taskModeTabVisible(s, 4))
        val hidden = AppSettings(showTasks = false, showLearn = false)
        assertFalse(taskModeTabVisible(hidden, 0))
        assertFalse(taskModeTabVisible(hidden, 2))
        assertEquals(3, firstVisibleTaskTab(hidden))                // Calls is first visible
        val allOff = AppSettings(showTasks = false, showLearn = false, showCalls = false)
        assertEquals(4, firstVisibleTaskTab(allOff))                // Settings always survives
    }

    @Test fun nextVisibleTaskTab_walksBothWays_skippingShop() {
        val s = AppSettings()
        assertEquals(2, nextVisibleTaskTab(s, 0, forward = true))   // 0 → 2 (1 skipped)
        assertEquals(0, nextVisibleTaskTab(s, 2, forward = false))  // 2 → 0 (1 skipped)
        assertEquals(4, nextVisibleTaskTab(s, 3, forward = true))
        assertEquals(0, nextVisibleTaskTab(s, 0, forward = false))  // edge stays put
    }

    @Test fun resolveStartMode_alwaysLastUsed() {
        // v2.04 (N36): the "Start in" setting is gone — a cold start reopens the last used mode.
        assertEquals("SHOP", resolveStartMode("SHOP"))
        assertEquals("TASK", resolveStartMode("TASK"))
        assertEquals("TASK", resolveStartMode(null))
        assertEquals("TASK", resolveStartMode("junk"))
    }


    // ---------------------------------------------------------------- v2.00 (N31): Classic purged

    @Test fun v200_legacyClassicJson_comesBackAsDualMode_noLayoutFieldSurvives() {
        // Krishna's question after v1.90 ("if dual mode is disabled, how to enable again?") — the
        // answer in 2.00 is: automatically. A v1.90 settings.json written in Classic:
        val legacy = "{\"ver\":35,\"layoutMode\":\"CLASSIC\",\"showShop\":false,\"startMode\":\"TASK\"}"
        val healed = healSettings(gson.fromJson(legacy, AppSettings::class.java))
        val out = gson.toJson(healed)
        assertFalse("layoutMode must not survive", out.contains("layoutMode"))
        assertFalse("showShop must not survive", out.contains("showShop"))
        assertFalse(taskModeTabVisible(healed, 1))              // Shop never in the Task-mode bar
        assertFalse(isTabVisible(healed, 1))                    // even the base helper agrees now
        assertTrue(taskModeTabVisible(healed, 0))
        assertEquals("SHOP", resolveStartMode("SHOP"))            // Shop mode still reachable
    }

    @Test fun v200_schemaIs37_afterN33() {
        assertEquals(43, AppSettings().ver)
        assertEquals(43, healSettings(AppSettings()).ver)
    }

    // ---------------------------------------------------------------- settings round-trip

    @Test fun v190Fields_surviveJsonRoundTrip() {
        val a = AppSettings(
            shopCheckoutCalc = false,
            shopCheapestHint = false, shopArriveAlert = false, shopNewRadius = 300f, citySeedDone = true
        )
        val b = healSettings(gson.fromJson(gson.toJson(a), AppSettings::class.java))
        assertFalse(b.shopCheckoutCalc)
        assertFalse(b.shopCheckoutCalc); assertFalse(b.shopCheapestHint); assertFalse(b.shopArriveAlert)
        assertEquals(300f, b.shopNewRadius); assertTrue(b.citySeedDone)
        assertNotNull(gson.toJson(b))
    }

    @Test fun shopAndItem_v190Fields_surviveJsonRoundTrip() {
        val s = shop(1, "D-Mart – Serampore", cityId = 4, chainId = 9)
        val s2 = healShop(gson.fromJson(gson.toJson(s), Shop::class.java))
        assertEquals(4L, s2.cityId); assertEquals(9L, s2.chainId)
        val i = Item(id = 1, tab = Tab.SHOP, title = "Rice", productId = 7L, shopId = 1L)
        val i2 = healItem(gson.fromJson(gson.toJson(i), Item::class.java))
        assertEquals(7L, i2.productId); assertEquals(1L, i2.shopId)
    }
}
