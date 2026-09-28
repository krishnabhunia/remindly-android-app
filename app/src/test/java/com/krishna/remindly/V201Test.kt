package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.01 (N32) — the pure layer behind the five 2.00 bug reports:
 * B4 SheetBottomSpace math (Option D, IME-aware, floor), B1/B2 the settings-section table
 * (Mode on both pages, shop sections only on the SHOP deck), and the Reset scopes (General now
 * covers Start-in; SHOP covers the v1.90 toggles). Composables, sheet chrome, real nav-bar
 * measurement and the accordion look are device-checklist section M — stated honestly.
 */
class V201Test {

    // ---------------------------------------------------------------- B4: clearance math

    @Test fun optionD_25pct_ofWindow_wins_onTypicalPhone() {
        // 800 dp window, 48 dp 3-button bar: 25% = 200 dp > 48 + 24 = 72 dp
        assertEquals(200f, sheetClearanceDp(800f, 48f, imeVisible = false), 0.001f)
        // 915 dp window (6.7"): 228.75 dp
        assertEquals(228.75f, sheetClearanceDp(915f, 48f, imeVisible = false), 0.001f)
    }

    @Test fun floor_navBarPlus24_wins_whenWindowIsShort() {
        // 200 dp window (split-screen sliver): 25% = 50 dp < 48 + 24 = 72 dp → floor
        assertEquals(72f, sheetClearanceDp(200f, 48f, imeVisible = false), 0.001f)
        // gesture bar 16 dp, tiny window: max(50, 40) = 50
        assertEquals(50f, sheetClearanceDp(200f, 16f, imeVisible = false), 0.001f)
    }

    @Test fun keyboardOpen_keepsOnlyTheSmallGap() {
        assertEquals(SHEET_CLEAR_IME_DP, sheetClearanceDp(800f, 48f, imeVisible = true), 0.001f)
        assertEquals(24f, SHEET_CLEAR_IME_DP, 0.001f)
    }

    @Test fun clearance_isTotal_onGarbageInputs() {
        // negative / NaN / zero never throw and never go below the 24 dp extra
        assertEquals(24f, sheetClearanceDp(-10f, -5f, false), 0.001f)
        assertEquals(24f, sheetClearanceDp(Float.NaN, Float.NaN, false), 0.001f)
        assertEquals(24f, sheetClearanceDp(0f, 0f, false), 0.001f)
        assertTrue(sheetClearanceDp(Float.POSITIVE_INFINITY, 48f, false) >= 72f)  // infinity treated as 0 → floor
    }

    @Test fun constants_areOptionD() {
        assertEquals(0.25f, SHEET_CLEAR_PCT, 0.0f)
        assertEquals(24f, SHEET_CLEAR_MIN_EXTRA_DP, 0.0f)
    }

    // ---------------------------------------------------------------- B1/B2: section table

    @Test fun v204_shopModeSection_isGone_modeLivesInTheDrawer() {
        // v2.04 (N36): "Start in" removed — no page shows a Mode section any more.
        listOf(null, "SHOP", "BUY", "SHOPS", "PRODUCTS", "TASKS", "LEARN", "CALLS").forEach {
            assertFalse("shop-mode on $it", settingsSectionVisible(it, "shop-mode"))
        }
    }


    @Test fun v204_shopSections_liveOnTheirOwnTabPages() {
        // N35: Buy ⚙ / Shops ⚙ / Products ⚙ each own their sections; nothing leaks to General or Task tabs.
        listOf("shop-buy", "s-groups", "s-add", "pin").forEach { assertTrue(it, settingsSectionVisible("BUY", it)) }
        listOf("shop-geo", "location").forEach { assertTrue(it, settingsSectionVisible("SHOPS", it)) }
        assertTrue(settingsSectionVisible("PRODUCTS", "shop-data"))
        assertFalse(settingsSectionVisible("BUY", "shop-geo"))
        assertFalse(settingsSectionVisible("SHOPS", "shop-buy"))
        assertFalse(settingsSectionVisible("PRODUCTS", "pin"))
        listOf("shop-buy", "shop-geo", "shop-data", "s-groups", "s-add", "location", "pin").forEach { k ->
            assertFalse("$k on General", settingsSectionVisible(null, k))
            assertFalse("$k on TASKS", settingsSectionVisible("TASKS", k))
            assertFalse("$k on CALLS", settingsSectionVisible("CALLS", k))
        }
    }

    @Test fun generalPage_keepsItsGlobalSections_andAboutOnce() {
        listOf("alerts", "adding", "done", "gestures", "swipe", "clock", "appearance", "google",
            "backup", "errlog", "health", "tests", "bin", "details", "about").forEach { k ->
            assertTrue(k, settingsSectionVisible(null, k))
        }
        // About Me is General-only: Shop Settings renders AboutMeCard() itself, so the SHOP deck must
        // NOT also emit the "about" section (duplicate-section discipline).
        assertFalse(settingsSectionVisible("BUY", "about"))
        assertFalse(settingsSectionVisible("junk", "alerts"))
    }

    @Test fun perTabDecks_unchanged() {
        assertTrue(settingsSectionVisible("TASKS", "t-cal"))
        assertTrue(settingsSectionVisible("LEARN", "l-add"))
        assertTrue(settingsSectionVisible("CALLS", "c-hk"))
        assertFalse(settingsSectionVisible("TASKS", "l-add"))
    }

    // ---------------------------------------------------------------- Reset scopes (pure)

    @Test fun generalReset_leavesShopFieldsAlone() {
        val s = AppSettings(theme = "DARK", shopCheckoutCalc = false, shopNewRadius = 400f)
        val r = resetSettingsFor(s, null)
        assertEquals(AppSettings().theme, r.theme)
        assertFalse(r.shopCheckoutCalc)            // General reset never touches Shop toggles
        assertEquals(400f, r.shopNewRadius)
    }

    @Test fun v204_perTabResets_areScopedToTheirTab() {
        val s = AppSettings(
            shopCheckoutCalc = false, shopCheapestHint = false,
            shopArriveAlert = false, shopNewRadius = 500f, shopSort = "SHOP", theme = "DARK"
        )
        val buy = resetSettingsFor(s, "BUY")
        assertTrue(buy.shopCheckoutCalc); assertTrue(buy.shopCheapestHint)
        assertEquals(AppSettings().shopSort, buy.shopSort)
        assertFalse("Buy reset must not touch geofence fields", buy.shopArriveAlert)
        assertEquals(500f, buy.shopNewRadius)
        val shops = resetSettingsFor(s, "SHOPS")
        assertTrue(shops.shopArriveAlert); assertEquals(150f, shops.shopNewRadius)
        assertFalse("Shops reset must not touch buy-list fields", shops.shopCheckoutCalc)
        assertEquals("DARK", buy.theme); assertEquals("DARK", shops.theme)
        assertEquals(s, resetSettingsFor(s, "PRODUCTS"))   // nothing product-scoped to reset yet
    }

    @Test fun otherResets_areScopedToTheirTab() {
        val s = AppSettings(tasksDoneClearDays = 99, learnDoneClearDays = 77, callsDoneClearDays = 55)
        assertEquals(AppSettings().tasksDoneClearDays, resetSettingsFor(s, "TASKS").tasksDoneClearDays)
        assertEquals(77, resetSettingsFor(s, "TASKS").learnDoneClearDays)
        assertEquals(AppSettings().learnDoneClearDays, resetSettingsFor(s, "LEARN").learnDoneClearDays)
        assertEquals(AppSettings().callsDoneClearDays, resetSettingsFor(s, "CALLS").callsDoneClearDays)
    }
}
