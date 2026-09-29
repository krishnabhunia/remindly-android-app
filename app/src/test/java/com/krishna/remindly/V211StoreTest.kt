package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.11 — N48, the store-side pieces that touch Stores.kt: the schema-43 migration (Gson leaves the
 * new Booleans false on a pre-43 file — the ON defaults must come back), Discard keeping the list
 * records, the Buy ⚙ section table and Reset scope, and a ≤2.10 data backup still parsing.
 */
class V211StoreTest {

    @Test fun pre43Settings_getTheOnDefaultsBack() {
        val old = AppSettings(ver = 42, buyShowUnsorted = false, buyCardTotal = false, buyDupWarn = false,
            shareWaIcon = false, shareUrgentTag = false, shareBoughtTag = false)
        val m = SettingsStore.migrate(old)
        assertEquals(43, m.ver)
        assertTrue(m.buyShowUnsorted && m.buyCardTotal && m.buyDupWarn && m.shareWaIcon && m.shareUrgentTag && m.shareBoughtTag)
        // a 43 file keeps the user's OFF choices
        assertFalse(SettingsStore.migrate(m.copy(shareWaIcon = false)).shareWaIcon)
    }

    @Test fun discardingABuySettingsPage_neverDropsAList() {
        val l = ShopList(id = 1, name = "Groceries", createdAt = 1, updatedAt = 1)
        val snap = AppSettings(buyReopenLast = false)
        val cur = AppSettings(buyReopenLast = true, shopLists = listOf(l))
        val back = keepListData(snap, cur)
        assertFalse(back.buyReopenLast)                 // the setting reverts
        assertEquals(listOf(l), back.shopLists)         // the list survives
        assertEquals(listOf("Groceries"), back.shopGroups)
    }

    @Test fun buyGearPage_showsLists_andResetLeavesListsAlone() {
        assertTrue(settingsSectionVisible("BUY", "b-lists"))
        assertTrue(settingsSectionVisible("BUY", "sharing"))
        assertFalse(settingsSectionVisible(null, "b-lists"))
        val l = ShopList(id = 1, name = "G")
        val s = AppSettings(shopLists = listOf(l), shopDefaultListId = 1, buyOpensOn = "CLASSIC", shareWaIcon = false)
        val r = resetSettingsFor(s, "BUY")
        assertEquals("LISTS", r.buyOpensOn); assertTrue(r.shareWaIcon)
        assertEquals(listOf(l), r.shopLists); assertEquals(1L, r.shopDefaultListId)
    }

    @Test fun oldDataBackup_withoutLists_stillParses() {
        val json = """{"kind":"remindly-data","items":[{"id":1,"tab":"SHOP","title":"Milk","group":"Groceries"}],"calls":[],"places":[]}"""
        val b = Backup.parseData(json)!!
        assertTrue(b.shopLists!!.isEmpty())
        assertNull(b.items.single().listId)
        val seed = seedShopLists(b.items, emptyList(), b.shopGroups, emptyMap(), emptyList(), "", null, 1L)
        assertEquals(listOf("Groceries"), liveLists(seed.lists).map { it.name })
    }

    @Test fun settingsDiff_reportsTheNewBuyRows() {
        val d = settingsDiff(AppSettings(), AppSettings(buyOpensOn = "CLASSIC", shareWaApp = "BUSINESS"))
        assertTrue(d.any { it.first == "Buy tab opens on" && it.third == "Classic" })
        assertTrue(d.any { it.first == "WhatsApp app" && it.third == "WhatsApp Business" })
    }
}
