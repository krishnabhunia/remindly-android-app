package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.11 — N48: Buy tab LISTS FIRST + list sharing (Share icon, one-tap WhatsApp, "List_Name:-").
 * The pure pieces run here: list membership, the groups → lists migration (idempotent, two devices
 * converge), card stats, ordering, per-id merge, the settings mirror, delete / merge / duplicate /
 * restart / privacy, the cross-list duplicate warning, recent-in-list, and the share text in
 * Krishna's exact format. Screens, the WhatsApp hand-off and the alarm are device checks (section W).
 */
class V211Test {

    private val now = 1_790_000_000_000L
    private fun buy(id: Long, title: String, group: String? = null, listId: Long? = null, done: Boolean = false,
                    qty: String? = null, unit: String? = null, pr: Priority? = Priority.MEDIUM, price: String? = null) =
        Item(id = id, tab = Tab.SHOP, title = title, group = group, listId = listId, done = done,
            doneAt = if (done) now else null, quantity = qty, unit = unit, priority = pr, price = price, createdAt = now - id)
    private fun list(id: Long, name: String, order: Int = 0, created: Long = now, pinned: Boolean = false) =
        ShopList(id = id, name = name, order = order, createdAt = created, updatedAt = created, pinned = pinned)

    // ---------------------------------------------------------------- membership

    @Test fun anItemAddedInsideAList_belongsToThatList() {
        val g = list(10, "Groceries")
        val i = buy(1, "Milk", listId = 10, group = "Groceries")
        assertEquals(10L, listIdOf(i, listOf(g)))
        assertEquals(listOf(i), itemsInList(listOf(i), 10, listOf(g)))
    }

    @Test fun listIdWinsOverAStaleGroupName_andGroupNameIsTheFallback() {
        val a = list(10, "Groceries"); val b = list(11, "Monthly stock")
        assertEquals(11L, listIdOf(buy(1, "Atta", group = "Groceries", listId = 11), listOf(a, b)))
        assertEquals(10L, listIdOf(buy(2, "Eggs", group = " groceries "), listOf(a, b)))   // older device: name only
    }

    @Test fun itemsWithNoLiveList_areUnsorted() {
        val dead = list(10, "Old").copy(deletedAt = now)
        val items = listOf(buy(1, "A"), buy(2, "B", listId = 10), buy(3, "C", group = "Nowhere"))
        assertEquals(3, itemsInList(items, UNSORTED_LIST_ID, listOf(dead)).size)
    }

    @Test fun buyNowScope_spansEveryList_butNeverDeletedOrOtherTabs() {
        val items = listOf(buy(1, "A", listId = 10), buy(2, "B"), buy(3, "C").copy(deletedAt = now),
            Item(id = 4, tab = Tab.TASKS, title = "task"))
        assertEquals(listOf(1L, 2L), itemsInList(items, BUY_NOW_LIST_ID, listOf(list(10, "G"))).map { it.id })
    }

    // ---------------------------------------------------------------- migration

    @Test fun upgrade_turnsEveryGroupIntoAList_andStampsItems() {
        val items = listOf(buy(1, "Milk", group = "Groceries"), buy(2, "Detergent", group = "Home"), buy(3, "Loose"))
        val seed = seedShopLists(items, emptyList(), listOf("Groceries", "Party"), mapOf("Groceries" to "🛒"),
            listOf("Party", "Groceries"), defaultGroup = "Home", currentDefault = null, now = now)
        assertEquals(setOf("Groceries", "Party", "Home"), liveLists(seed.lists).map { it.name }.toSet())
        val g = seed.lists.first { it.name == "Groceries" }
        assertEquals("🛒", g.icon); assertEquals(1, g.order)                       // N45 order kept
        assertEquals(0, seed.lists.first { it.name == "Party" }.order)
        assertEquals(g.id, seed.items.first { it.id == 1L }.listId)
        assertTrue(seed.items.none { it.id == 3L })                                   // ungrouped → Unsorted, untouched
        assertEquals(seed.lists.first { it.name == "Home" }.id, seed.defaultListId)   // old default group → default list
    }

    @Test fun upgrade_isIdempotent_secondRunChangesNothing() {
        val items = listOf(buy(1, "Milk", group = "Groceries"))
        val s1 = seedShopLists(items, emptyList(), emptyList(), emptyMap(), emptyList(), "", null, now)
        val applied = items.map { i -> s1.items.firstOrNull { it.id == i.id } ?: i }
        val s2 = seedShopLists(applied, s1.lists, listOf("Groceries"), emptyMap(), emptyList(), "", s1.defaultListId, now + 5)
        assertEquals(s1.lists, s2.lists)
        assertTrue(s2.items.isEmpty())
    }

    @Test fun twoDevicesMigratingTheSameGroup_mintTheSameListId() {
        val a = seedShopLists(listOf(buy(1, "Milk", group = "Groceries")), emptyList(), emptyList(), emptyMap(), emptyList(), "", null, now)
        val b = seedShopLists(listOf(buy(2, "Eggs", group = "groceries")), emptyList(), emptyList(), emptyMap(), emptyList(), "", null, now + 999)
        assertEquals(a.lists.single().id, b.lists.single().id)
        assertEquals(1, liveLists(mergeShopLists(a.lists, b.lists)).size)
    }

    @Test fun seedIds_neverCollideWithMintedIds() {
        val minted = Ids.composeId(now, 0, 0xFFF)
        assertTrue(seedListId("Groceries") > minted)
        assertTrue(seedListId("Groceries") > 0)
        assertNotEquals(seedListId("Groceries"), seedListId("Grocerie"))
    }

    @Test fun duplicateNamesFromTwoDevices_collapseToTheOldest_andItemsFollow() {
        val old = list(5, "Diwali", created = now - 1000); val young = list(9, "diwali", created = now)
        val items = listOf(buy(1, "Diya", listId = 9, group = "diwali"))
        val seed = seedShopLists(items, listOf(old, young), emptyList(), emptyMap(), emptyList(), "", null, now + 1)
        assertEquals(listOf(5L), liveLists(seed.lists).map { it.id })
        assertEquals(5L, seed.items.single().listId)
        assertEquals("Diwali", seed.items.single().group)
    }

    @Test fun deletedListsItems_stayUnsorted_afterReseed() {
        val gone = list(10, "Old").copy(deletedAt = now)
        val items = listOf(buy(1, "A", listId = null, group = null))
        val seed = seedShopLists(items, listOf(gone), emptyList(), emptyMap(), emptyList(), "", null, now)
        assertTrue(liveLists(seed.lists).isEmpty())
        assertTrue(seed.items.isEmpty())
    }

    // ---------------------------------------------------------------- sync / backup / mirror

    @Test fun mergeShopLists_latestWinsPerId_andTombstonesPropagate() {
        val local = listOf(list(1, "A").copy(updatedAt = 10), list(2, "B").copy(updatedAt = 10))
        val remote = listOf(list(1, "A renamed").copy(updatedAt = 20), list(2, "B").copy(updatedAt = 30, deletedAt = 30), list(3, "C"))
        val m = mergeShopLists(local, remote)
        assertEquals("A renamed", m.first { it.id == 1L }.name)
        assertNotNull(m.first { it.id == 2L }.deletedAt)
        assertEquals(3, m.size)
        assertEquals("A", mergeShopLists(listOf(list(1, "A").copy(updatedAt = 50)), listOf(list(1, "old").copy(updatedAt = 40))).single().name)
    }

    @Test fun settingsMirror_keepsOlderAppsSeeingTheSameGroups() {
        val s = AppSettings(
            shopLists = listOf(list(1, "Groceries", order = 1).copy(icon = "🛒"), list(2, "Monthly", order = 0), list(3, "Gone").copy(deletedAt = now)),
            groupIcons = mapOf("Work" to "💼", "Gone" to "x"), tasksGroups = listOf("Work"), shopDefaultListId = 1
        )
        val m = mirrorListsIntoSettings(s)
        assertEquals(listOf("Monthly", "Groceries"), m.shopGroups)
        assertEquals("💼", m.groupIcons["Work"])          // a Task group's icon is untouched
        assertEquals("🛒", m.groupIcons["Groceries"])
        assertEquals("Groceries", m.shopDefaultGroup)
    }

    @Test fun healSettings_coatsPre43Json() {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val raw = gsonLike(AppSettings())
        val h = healSettings(raw)
        assertEquals("LISTS", h.buyOpensOn); assertEquals("RECENT", h.buyListSort); assertEquals("SHOP", h.buyInnerSort)
        assertEquals(":-", h.shareHeadingSuffix); assertEquals("WHATSAPP", h.shareWaApp)
        assertTrue(h.shopLists.isEmpty())
        assertEquals(43, AppSettings().ver)
    }

    /** Simulates Gson on a pre-43 file: the new reference fields come back null. */
    private fun gsonLike(a: AppSettings): AppSettings {
        val c = a.copy()
        listOf("shopLists", "buyOpensOn", "buyListSort", "buyInnerSort", "shareWaApp", "shareHeadingSuffix").forEach { f ->
            AppSettings::class.java.getDeclaredField(f).apply { isAccessible = true }.set(c, null)
        }
        return c
    }

    // ---------------------------------------------------------------- Lists screen

    @Test fun cardStats_countEstimateAndShops() {
        val items = listOf(
            buy(1, "Milk", qty = "2", price = "108").copy(shopName = "D-Mart – Kalyan"),
            buy(2, "Rice", qty = "5", unit = "kg").copy(priceHistory = listOf(PricePoint(at = now, price = 395.0, shop = "D-Mart – Kalyan", qty = 5.0, unit = "kg", unitPrice = 79.0))),
            buy(3, "Coriander"),
            buy(4, "Onions", done = true, price = "40")
        )
        val st = listStats(items, emptyMap())
        assertEquals(3, st.toBuy); assertEquals(1, st.done)
        assertEquals(108.0 + 395.0, st.estTotal!!, 0.001)
        assertEquals(listOf("D-Mart – Kalyan"), st.shops)
        assertEquals("≈ ₹503", estLabel(st.estTotal))
        assertNull(listStats(listOf(buy(9, "x")), emptyMap()).estTotal)
    }

    @Test fun listOrder_pinnedFirst_thenChip() {
        val l = listOf(list(1, "b", order = 2), list(2, "a", order = 1), list(3, "c", order = 0, pinned = true))
        assertEquals(listOf(3L, 2L, 1L), sortedLists(l, "AZ") { 0 }.map { it.id })
        assertEquals(listOf(3L, 2L, 1L), sortedLists(l, "CUSTOM") { 0 }.map { it.id })
        assertEquals(listOf(3L, 1L, 2L), sortedLists(l, "RECENT") { if (it.id == 1L) 99 else 1 }.map { it.id })
    }

    @Test fun moveUpInCustomOrder_swapsNeighbours_andNeverCrossesThePinnedBlock() {
        val l = listOf(list(1, "a", order = 0), list(2, "b", order = 1), list(3, "p", order = 2, pinned = true))
        val moved = moveListOrder(l, 2, up = true, now = now)
        assertEquals(listOf(3L, 2L, 1L), sortedLists(moved, "CUSTOM") { 0 }.map { it.id })
        assertEquals(sortedLists(moved, "CUSTOM") { 0 }, sortedLists(moveListOrder(moved, 2, up = true, now = now), "CUSTOM") { 0 })
    }

    @Test fun newListNames_areNeverDuplicated() {
        val l = listOf(list(1, "Groceries"), list(2, "Groceries 2"))
        assertEquals("groceries 3", uniqueListName("groceries", l))   // the typed spelling is kept
        assertEquals("Party", uniqueListName(" Party ", l))
    }

    // ---------------------------------------------------------------- inside a list

    @Test fun toothpasteAlreadyInMonthly_warnsWhenAddedToGroceries() {
        val lists = listOf(list(1, "Groceries"), list(2, "Monthly stock"))
        val items = listOf(buy(1, "Toothpaste", listId = 2))
        assertEquals("Monthly stock", otherListHolding(items, " toothpaste", 1, lists)?.name)
        assertNull(otherListHolding(items, "Toothpaste", 2, lists))                         // same list: the old warning covers it
        assertNull(otherListHolding(listOf(buy(1, "Toothpaste", listId = 2, done = true)), "Toothpaste", 1, lists))
    }

    @Test fun recentlyBoughtInThisList_matchesTyping_skipsOpenOnes() {
        val items = listOf(
            buy(1, "Tomato", done = true).copy(doneAt = now - 10), buy(2, "Tomato", done = true).copy(doneAt = now),
            buy(3, "Toor dal", done = true), buy(4, "Tofu"), buy(5, "Potato", done = true)
        )
        val r = recentInList(items, "to")
        assertEquals(listOf("Tomato", "Toor dal", "Potato"), r.map { it.title })
        assertTrue(recentInList(items, "").isEmpty())
    }

    @Test fun categoryGrouping_usesTheLinkedProduct_noCategoryLast() {
        val p = mapOf(1L to Product(1, "Milk", category = "Dairy"), 2L to Product(2, "Rice", category = "Staples"))
        val g = categoryGroupsOf(listOf(buy(1, "Milk").copy(productId = 1), buy(2, "Rice").copy(productId = 2), buy(3, "Bread")), p)
        assertEquals(listOf("Dairy", "Staples", "No category"), g.map { it.first })
    }

    @Test fun buyNowGroupsByList_unsortedLast() {
        val lists = listOf(list(1, "Groceries", order = 1).copy(icon = "🛒"), list(2, "Monthly", order = 0))
        val g = listGroupsOf(listOf(buy(1, "Milk", listId = 1), buy(2, "Atta", listId = 2), buy(3, "Loose")), lists)
        assertEquals(listOf("Monthly", "🛒 Groceries", "Unsorted"), g.map { it.first })
    }

    // ---------------------------------------------------------------- list actions

    @Test fun deletingAList_defaultKeepsItemsInUnsorted_withPriceHistory() {
        val ph = listOf(PricePoint(at = now, price = 40.0))
        val items = listOf(buy(1, "Onions", listId = 7, group = "G").copy(priceHistory = ph))
        val out = itemsAfterListDelete(items, ListDeleteMode.KEEP_UNSORTED, null)
        assertNull(out.single().listId); assertNull(out.single().group)
        assertEquals(ph, out.single().priceHistory)
        assertNull(listIdOf(out.single(), listOf(list(7, "G").copy(deletedAt = now))))
    }

    @Test fun deletingAList_moveTo_rehomesItems() {
        val t = list(9, "Monthly")
        val out = itemsAfterListDelete(listOf(buy(1, "A", listId = 7)), ListDeleteMode.MOVE_TO, t)
        assertEquals(9L, out.single().listId); assertEquals("Monthly", out.single().group)
    }

    @Test fun rename_movesEveryItemsGroupMirror() {
        val l = list(1, "Groceries")
        assertEquals(listOf("Weekly", "Weekly"), itemsAfterListRename(listOf(buy(1, "A", listId = 1), buy(2, "B", group = "Groceries")), l, " Weekly ").map { it.group })
    }

    @Test fun privateList_locksEveryItem_andTurningItOffUnlocksThem() {
        val items = listOf(buy(1, "A"), buy(2, "B").copy(personal = true))
        assertEquals(listOf(1L), itemsAfterPrivacy(items, true).map { it.id })
        assertTrue(itemsAfterPrivacy(items, true).all { it.personal })
        assertEquals(listOf(2L), itemsAfterPrivacy(items, false).map { it.id })
    }

    @Test fun duplicateList_makesFreshOpenCopies() {
        var n = 100L
        val nl = list(50, "Groceries 2")
        val out = duplicateListItems(listOf(buy(1, "A", done = true), buy(2, "B").copy(deletedAt = now)), nl, { n++ }, now)
        assertEquals(1, out.size)
        assertFalse(out.single().done); assertEquals(50L, out.single().listId); assertEquals(100L, out.single().id)
    }

    @Test fun restart_returnsOnlyBoughtItems_markAll_onlyOpenOnes() {
        val items = listOf(buy(1, "A", done = true), buy(2, "B"), buy(3, "C", done = true).copy(deletedAt = now))
        assertEquals(listOf(1L), restartTargets(items).map { it.id })
        assertEquals(listOf(2L), markAllTargets(items).map { it.id })
    }

    // ---------------------------------------------------------------- sharing: Krishna's format

    private val all = ListShareOpts(includeBought = true, urgentTag = true, boughtTag = true, qtyType = true)

    @Test fun shareText_isExactlyKrishnasFormat() {
        val items = listOf(
            buy(1, "Milk", qty = "2", unit = "L", pr = Priority.URGENT),
            buy(2, "Onions", qty = "1", unit = "kg", done = true),
            buy(3, "Eggs", qty = "12", unit = "pcs"),
            buy(4, "Bread", qty = "1")
        )
        assertEquals(
            "Groceries:-\n\n" +
                "1. Milk - 2 / L - Urgent\n" +
                "2. Eggs - 12 / pcs\n" +
                "3. Bread - 1\n" +
                "4. Onions - 1 / kg - Bought",
            listShareText("Groceries", items, all)
        )
    }

    @Test fun shareText_optionalPartsFollowTheSwitches() {
        val items = listOf(buy(1, "Milk", qty = "2", unit = "L", pr = Priority.URGENT), buy(2, "Onions", done = true))
        assertEquals("Groceries:-\n\n1. Milk", listShareText("Groceries", items, ListShareOpts(false, false, false, false)))
        assertEquals("Groceries:-\n\n1. Milk - 2 / L - Urgent\n2. Onions", listShareText("Groceries", items, all.copy(boughtTag = false)))
        assertEquals("G — list\n\n1. Milk - 2 / L - Urgent", listShareText("G", items, all.copy(includeBought = false, suffix = " — list")))
    }

    @Test fun shareText_highIsNotUrgent_noQuantityDropsTheSegment_emptyListIsJustTheHeading() {
        assertEquals("L:-\n\n1. Rice", listShareText("L", listOf(buy(1, "Rice", pr = Priority.HIGH)), all))
        assertEquals("L:-", listShareText("L", emptyList(), all))
        assertEquals("L:-", listShareText("L", listOf(buy(1, "x").copy(deletedAt = now)), all))
    }

    @Test fun shareText_neverCarriesPriceShopOrNotes() {
        val t = listShareText("L", listOf(buy(1, "Rice", qty = "5", unit = "kg", price = "395").copy(shopName = "D-Mart", notes = "brown")), all)
        assertFalse(t.contains("395")); assertFalse(t.contains("D-Mart")); assertFalse(t.contains("brown"))
    }

    @Test fun shareOpts_comeFromSettings() {
        val o = listShareOptsOf(AppSettings(shareIncludeDone = true, shareUrgentTag = false, shareBoughtTag = true, shareIncludeQty = false, shareHeadingSuffix = ":"))
        assertEquals(ListShareOpts(true, false, true, false, ":"), o)
    }

    @Test fun whatsAppPackage_prefersTheChoice_fallsBackToTheOther_nullWhenNeither() {
        assertEquals("com.whatsapp", whatsAppPackage("WHATSAPP") { true })
        assertEquals("com.whatsapp.w4b", whatsAppPackage("BUSINESS") { true })
        assertEquals("com.whatsapp.w4b", whatsAppPackage("WHATSAPP") { it == "com.whatsapp.w4b" })
        assertNull(whatsAppPackage("WHATSAPP") { false })
    }

    // ---------------------------------------------------------------- shopping day + G1

    @Test fun shoppingDayReminder_firesAtNineThatMorning_onlyWhenAhead() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val day = java.time.LocalDate.of(2026, 10, 19).atTime(17, 30).atZone(zone).toInstant().toEpochMilli()
        val at = shoppingDayFireAt(day, zone)
        assertEquals(java.time.LocalDate.of(2026, 10, 19).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), at)
        val l = list(1, "Diwali").copy(shoppingDay = day)
        assertEquals(1, upcomingShoppingDays(listOf(l), at - 1, zone).size)
        assertTrue(upcomingShoppingDays(listOf(l), at, zone).isEmpty())
    }

    @Test fun receivedShopList_becomesANewListOfOpenItems() {
        var n = 1L
        val l = list(77, "Groceries (Riya)")
        val out = itemsFromShared(listOf(SharedItem("Milk", quantity = "2", unit = "L"), SharedItem(" ")), l, { n++ }, now)
        assertEquals(1, out.size)
        val i = out.single()
        assertEquals(77L, i.listId); assertEquals("Groceries (Riya)", i.group); assertEquals("2", i.quantity); assertEquals("L", i.unit)
        assertFalse(i.done); assertEquals(Tab.SHOP, i.tab)
    }

    @Test fun cardSubtitle_readsLikeTheDesign() {
        assertEquals("5 to buy · 2 done · updated 2 h ago", listCardSubtitle(ListStats(5, 2, null, emptyList(), now - 2 * 3_600_000L), now))
        assertEquals("All bought · updated yesterday", listCardSubtitle(ListStats(0, 3, null, emptyList(), now - 30 * 3_600_000L), now))
        assertEquals("Empty", listCardSubtitle(ListStats(0, 0, null, emptyList(), 0L), now))
        assertEquals("just now", relativeAgo(now - 5_000, now))
        assertEquals("5 min ago", relativeAgo(now - 5 * 60_000L, now))
    }

    @Test fun shareOrder_followsTheOnScreenGrouping() {
        val items = listOf(
            buy(1, "Bread").copy(shopName = null, createdAt = 1), buy(2, "Milk").copy(shopName = "D-Mart", createdAt = 3),
            buy(3, "Eggs").copy(shopName = "D-Mart", createdAt = 2), buy(4, "Rice", pr = Priority.URGENT).copy(createdAt = 4)
        )
        assertEquals(listOf("Eggs", "Milk", "Bread", "Rice"), shareOrderFor(items, "SHOP", emptyList()).map { it.title })
        assertEquals("Rice", shareOrderFor(items, "PRIORITY", emptyList()).first().title)
        assertEquals(listOf("Bread", "Eggs", "Milk", "Rice"), shareOrderFor(items, "NONE", emptyList()).map { it.title })
    }
}
