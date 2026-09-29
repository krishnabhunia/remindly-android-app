package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9 — N47 (in-app updates from the GitHub feed) and N45 (shopping-list features). The pure
 * pieces are proven here: the feed parser's strictness, newer-only comparison, the 24 h cadence,
 * SHA-256, product suggestion ranking, the share text with both toggles, group icons, section
 * table, heals and schema 42. The download/installer flow, mic, splash and the sheets are device
 * checks (section V).
 */
class V29Test {

    private val goodFeed = """{"versionCode":2009000,"versionName":"2.9","apk":"Remindly-2.9.apk",
        "apkUrl":"https://raw.githubusercontent.com/krishnabhunia/remindly-android-app/main/releases/Remindly-2.9.apk",
        "sha256":"${"a".repeat(64)}","sizeBytes":16000000,"notes":"In-app updates."}"""

    // ---------------------------------------------------------------- N47: feed

    @Test fun parseVersionFeed_acceptsTheRealShape() {
        val f = parseVersionFeed(goodFeed)!!
        assertEquals(2009000, f.versionCode); assertEquals("2.9", f.versionName)
        assertEquals("Remindly-2.9.apk", f.apk); assertTrue(f.apkUrl.startsWith("https://"))
        assertEquals(64, f.sha256.length); assertEquals(16_000_000L, f.sizeBytes); assertEquals("In-app updates.", f.notes)
    }

    @Test fun parseVersionFeed_rejectsAnythingUnsafe() {
        assertNull("not json", parseVersionFeed("<html>"))
        assertNull("missing sha", parseVersionFeed("""{"versionCode":1,"versionName":"x","apk":"a.apk","apkUrl":"https://x/a.apk"}"""))
        assertNull("short sha", parseVersionFeed(goodFeed.replace("a".repeat(64), "abc")))
        assertNull("http url", parseVersionFeed(goodFeed.replace("https://", "http://")))
        assertNull("not an apk", parseVersionFeed(goodFeed.replace("Remindly-2.9.apk\"", "Remindly-2.9.zip\"")))
        assertNull("zero code", parseVersionFeed(goodFeed.replace("2009000", "0")))
    }

    @Test fun updateAvailable_newerOnly() {
        val f = parseVersionFeed(goodFeed)
        assertTrue(updateAvailable(f, 2008000))
        assertFalse("same build is not an update", updateAvailable(f, 2009000))
        assertFalse("never downgrade", updateAvailable(f, 2010000))
        assertFalse(updateAvailable(null, 1))
    }

    @Test fun updateCheckDue_onceADay_andOnlyWhenEnabled() {
        val now = 1_000_000_000_000L
        assertTrue(updateCheckDue(0L, now, autoCheck = true))
        assertFalse(updateCheckDue(now - 3600_000L, now, true))
        assertTrue(updateCheckDue(now - 25 * 3600_000L, now, true))
        assertFalse("disabled", updateCheckDue(0L, now, autoCheck = false))
    }

    @Test fun sha256Hex_matchesKnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }

    // ---------------------------------------------------------------- N45: suggestions + share

    private fun prod(id: Long, name: String, cat: String? = null, deleted: Long? = null) = Product(id = id, name = name, category = cat, deletedAt = deleted)

    @Test fun productSuggestions_prefixFirst_thenContains_caseInsensitive_capped() {
        val ps = listOf(prod(1, "Apple Juice", "Beverages"), prod(2, "apple", "Fruit"), prod(3, "Pineapple", "Fruit"),
                        prod(4, "Milk", "Dairy"), prod(5, "Apple Cider", deleted = 9L), prod(6, "Cheese", "Dairy apples"))
        assertEquals(listOf("apple", "Apple Juice", "Cheese", "Pineapple"), productSuggestions("ap", ps).map { it.name })
        assertTrue("one char = nothing", productSuggestions("a", ps).isEmpty())
        assertEquals(2, productSuggestions("ap", ps, limit = 2).size)
        assertTrue("category match", productSuggestions("dairy", ps).map { it.name }.containsAll(listOf("Milk", "Cheese")))
    }

    @Test fun groupShareText_honoursBothToggles() {
        val items = listOf(
            Item(id = 1, tab = Tab.SHOP, title = "Milk", quantity = "2 L", shopName = "D-Mart"),
            Item(id = 2, tab = Tab.SHOP, title = "Onions", quantity = "1 kg", done = true),
            Item(id = 3, tab = Tab.SHOP, title = "Gone", deletedAt = 5L)
        )
        assertEquals("Shopping list – Groceries\n- Milk 2 L · D-Mart", groupShareText("Groceries", items, includeDone = false, includeQty = true))
        assertEquals("Shopping list – Groceries\n- Milk\n- Onions (✓)", groupShareText("Groceries", items, includeDone = true, includeQty = false))
    }

    @Test fun groupIcons_lookupAndHeal() {
        val s = AppSettings(groupIcons = mapOf("Groceries" to "🛒", "Blank" to " "))
        assertEquals("🛒", groupIconFor("Groceries", s))
        assertNull(groupIconFor("Blank", s)); assertNull(groupIconFor("Nope", s))
        val legacy = com.google.gson.Gson().fromJson("{\"ver\":41}", AppSettings::class.java)
        val h = healSettings(legacy)
        assertEquals(emptyMap<String, String>(), h.groupIcons); assertEquals("", h.shopDefaultGroup)
        assertTrue(h.updateAutoCheck); assertTrue(h.updateWifiOnly); assertTrue(h.shopVoiceAdd); assertTrue(h.shopSuggest)
        assertFalse(h.shareIncludeDone); assertTrue(h.shareIncludeQty)
        h.copy(groupIcons = mapOf("x" to "y"))
    }

    // ---------------------------------------------------------------- deck & schema

    @Test fun sections_updatesOnGeneral_sharingOnBuy() {
        assertTrue(settingsSectionVisible(null, "updates")); assertFalse(settingsSectionVisible("BUY", "updates"))
        assertTrue(settingsSectionVisible("BUY", "sharing")); assertFalse(settingsSectionVisible(null, "sharing"))
        assertFalse(settingsSectionVisible("TASKS", "sharing"))
    }

    @Test fun v29_schemaIs42() {
        assertEquals(43, AppSettings().ver)
        assertEquals(43, healSettings(AppSettings()).ver)
    }
}
