package com.krishna.remindly

import android.content.Context

/**
 * v2.11 (N48) — the Buy lists. The records live in AppSettings.shopLists (so they ride the
 * settings doc that already syncs and backs up — no new Firestore collection, no rules change);
 * every write stamps updatedAt for the per-id merge and refreshes the settings mirror
 * (shopGroups / shopGroupOrder / groupIcons / shopDefaultGroup) that older app versions and the
 * Classic view read. Item edits go through ItemStore/Engine so alarms, calendar and sync follow.
 * Every entry point is guarded: a failure is logged and leaves the data as it was.
 */
object ShopListStore {

    fun all(): List<ShopList> = SettingsStore.s.value.shopLists
    fun live(): List<ShopList> = liveLists(all())
    fun get(id: Long?): ShopList? = id?.let { i -> live().firstOrNull { it.id == i } }

    private fun writeLists(transform: (List<ShopList>) -> List<ShopList>) {
        SettingsStore.update { s -> mirrorListsIntoSettings(s.copy(shopLists = transform(s.shopLists))) }
    }

    private fun stamp(l: ShopList): ShopList = l.copy(updatedAt = System.currentTimeMillis())

    fun upsert(list: ShopList) = writeLists { ls -> ls.filter { it.id != list.id } + stamp(list) }

    /** Items currently in [listId] (live Buy items only). */
    fun itemsOf(listId: Long): List<Item> = itemsInList(ItemStore.items.value, listId, all())

    /**
     * The upgrade step + healing (idempotent): groups → lists, duplicate names collapse, items
     * stamped. Runs at startup and after every import / sync apply.
     */
    fun reconcile(context: Context) {
        runCatching {
            val s = SettingsStore.s.value
            val seed = seedShopLists(
                ItemStore.items.value, s.shopLists, s.shopGroups, s.groupIcons, s.shopGroupOrder,
                s.shopDefaultGroup, s.shopDefaultListId, System.currentTimeMillis()
            )
            if (seed.items.isNotEmpty()) {
                // Migration stamps are derived data (every device computes the same from the
                // group name), so they are written WITHOUT bumping updatedAt — no sync storm.
                val byId = seed.items.associateBy { it.id }
                ItemStore.replaceAll(ItemStore.items.value.map { byId[it.id] ?: it })
            }
            if (seed.lists != s.shopLists || seed.defaultListId != s.shopDefaultListId || mirrorListsIntoSettings(s) != s) {
                SettingsStore.update { mirrorListsIntoSettings(it.copy(shopLists = seed.lists, shopDefaultListId = seed.defaultListId)) }
            }
            val added = liveLists(seed.lists).size - liveLists(s.shopLists).size
            if (added > 0 || seed.items.isNotEmpty())
                Logger.e(context, "LISTS", null, "reconcile: +$added list(s), ${seed.items.size} item(s) stamped")
        }.onFailure { Logger.e(context, "LISTS", it, "list reconcile failed — data left as it was") }
    }

    fun create(context: Context, name: String, icon: String?, usualShopId: Long?, shoppingDay: Long?, personal: Boolean): ShopList? =
        runCatching {
            val now = System.currentTimeMillis()
            val l = ShopList(
                id = Ids.next(), name = uniqueListName(name, all()), icon = icon?.takeIf { it.isNotBlank() },
                order = nextListOrder(all()), usualShopId = usualShopId, shoppingDay = shoppingDay,
                personal = personal, createdAt = now, updatedAt = now
            )
            writeLists { it + l }
            scheduleShoppingDay(context, l)
            Logger.e(context, "LISTS", null, "created list '${l.name}'")
            l
        }.onFailure { Logger.e(context, "LISTS", it, "create list failed") }.getOrNull()

    /** Edit sheet save: name (items follow), icon, usual shop, shopping day, Private. */
    fun edit(context: Context, list: ShopList, name: String, icon: String?, usualShopId: Long?, shoppingDay: Long?, personal: Boolean) {
        runCatching {
            val others = all().filter { it.id != list.id }
            val newName = if (name.trim().equals(list.name, ignoreCase = true)) name.trim().ifEmpty { list.name } else uniqueListName(name, others)
            val items = itemsOf(list.id)
            val updated = list.copy(name = newName, icon = icon?.takeIf { it.isNotBlank() }, usualShopId = usualShopId,
                shoppingDay = shoppingDay, personal = personal)
            upsert(updated)
            if (newName != list.name) ItemStore.upsertAll(itemsAfterListRename(items, list, newName))
            if (personal != list.personal) ItemStore.upsertAll(itemsAfterPrivacy(itemsOf(list.id), personal))
            scheduleShoppingDay(context, updated)
            Logger.e(context, "LISTS", null, "edited list '${list.name}'" + (if (newName != list.name) " → '$newName'" else ""))
        }.onFailure { Logger.e(context, "LISTS", it, "edit list failed") }
    }

    fun togglePin(context: Context, list: ShopList) = runCatching { upsert(list.copy(pinned = !list.pinned)) }
        .onFailure { Logger.e(context, "LISTS", it, "pin failed") }

    fun move(context: Context, list: ShopList, up: Boolean) = runCatching {
        val now = System.currentTimeMillis()
        writeLists { moveListOrder(it, list.id, up, now) }
    }.onFailure { Logger.e(context, "LISTS", it, "reorder failed") }

    /** L6. DELETE_ITEMS soft-deletes (Bin, restorable); the others re-home the items. */
    fun delete(context: Context, list: ShopList, mode: ListDeleteMode, target: ShopList?): List<Item> {
        val items = itemsOf(list.id)
        return runCatching {
            when (mode) {
                ListDeleteMode.DELETE_ITEMS -> items.forEach { Engine.delete(context, it) }
                else -> ItemStore.upsertAll(itemsAfterListDelete(items, mode, target))
            }
            val now = System.currentTimeMillis()
            writeLists { ls -> ls.map { if (it.id == list.id) it.copy(deletedAt = now, updatedAt = now) else it } }
            if (SettingsStore.s.value.shopDefaultListId == list.id) SettingsStore.update { mirrorListsIntoSettings(it.copy(shopDefaultListId = null)) }
            if (UiStore.s.value.openListId == list.id) UiStore.update { it.copy(openListId = null) }
            AlarmScheduler.cancelShoppingDay(context, list.id)
            Logger.e(context, "LISTS", null, "deleted list '${list.name}' (${items.size} item(s), $mode${target?.let { " → '${it.name}'" } ?: ""})")
            items
        }.onFailure { Logger.e(context, "LISTS", it, "delete list failed") }.getOrDefault(emptyList())
    }

    /** Undo for L6: the list comes back and its items return to it. */
    fun undoDelete(context: Context, list: ShopList, items: List<Item>, mode: ListDeleteMode) {
        runCatching {
            upsert(list.copy(deletedAt = null))
            if (mode == ListDeleteMode.DELETE_ITEMS) items.forEach { Engine.restore(context, it) }
            else ItemStore.upsertAll(items.map { it.copy(listId = list.id, group = list.name) })
            scheduleShoppingDay(context, list)
        }.onFailure { Logger.e(context, "LISTS", it, "undo delete list failed") }
    }

    fun merge(context: Context, from: ShopList, into: ShopList) {
        delete(context, from, ListDeleteMode.MOVE_TO, into)
    }

    fun duplicate(context: Context, list: ShopList): ShopList? = runCatching {
        val now = System.currentTimeMillis()
        val copy = list.copy(id = Ids.next(), name = uniqueListName(list.name, all()), pinned = false,
            order = nextListOrder(all()), createdAt = now, updatedAt = now, shoppingDay = null)
        writeLists { it + copy }
        ItemStore.upsertAll(duplicateListItems(itemsOf(list.id), copy, { Ids.next() }, now))
        Logger.e(context, "LISTS", null, "duplicated '${list.name}' → '${copy.name}'")
        copy
    }.onFailure { Logger.e(context, "LISTS", it, "duplicate list failed") }.getOrNull()

    /** L12: every bought item back to To buy; returns them for Undo. */
    fun restart(context: Context, list: ShopList): List<Item> = runCatching {
        val t = restartTargets(itemsOf(list.id))
        t.forEach { Engine.undoDone(context, it) }
        Logger.e(context, "LISTS", null, "restarted '${list.name}' (${t.size} item(s))")
        t
    }.onFailure { Logger.e(context, "LISTS", it, "restart failed") }.getOrDefault(emptyList())

    fun undoRestart(context: Context, items: List<Item>) = runCatching {
        items.forEach { Engine.completeNow(context, it.id, shopStamp = false) }
    }.onFailure { Logger.e(context, "LISTS", it, "undo restart failed") }

    /** Moves one item into [list] (editor List field / L8). */
    fun moveItem(context: Context, item: Item, list: ShopList?) {
        runCatching {
            Engine.addOrUpdate(context, item.copy(listId = list?.id, group = list?.name,
                personal = if (list?.personal == true) true else item.personal))
        }.onFailure { Logger.e(context, "LISTS", it, "move item failed") }
    }

    /** G1: a received Shop share becomes a new list of mine. */
    fun addFromShare(context: Context, r: ShareRecord): ShopList? = runCatching {
        val l = create(context, r.listName, null, null, null, false) ?: return@runCatching null
        itemsFromShared(r.items, l, { Ids.next() }, System.currentTimeMillis()).forEach { Engine.addOrUpdate(context, it) }
        Logger.e(context, "LISTS", null, "copied shared '${r.listName}' from ${r.fromEmail} into my lists (${r.items.size})")
        l
    }.onFailure { Logger.e(context, "LISTS", it, "copy shared list failed") }.getOrNull()

    // ------------------------------------------------------------ shopping day (F5)

    fun scheduleShoppingDay(context: Context, list: ShopList) {
        runCatching {
            AlarmScheduler.cancelShoppingDay(context, list.id)
            if (list.deletedAt != null) return@runCatching
            val day = list.shoppingDay ?: return@runCatching
            AlarmScheduler.scheduleShoppingDay(context, list.id, shoppingDayFireAt(day))
        }.onFailure { Logger.e(context, "LISTS", it, "shopping-day schedule failed for '${list.name}'") }
    }

    fun rescheduleShoppingDays(context: Context) {
        runCatching {
            upcomingShoppingDays(all(), System.currentTimeMillis()).forEach { (l, at) -> AlarmScheduler.scheduleShoppingDay(context, l.id, at) }
        }.onFailure { Logger.e(context, "LISTS", it, "shopping-day reschedule failed") }
    }

    /** The alarm fired: one notification that morning (private lists never show their name). */
    fun fireShoppingDay(context: Context, listId: Long) {
        runCatching {
            val l = get(listId) ?: return@runCatching
            val day = l.shoppingDay ?: return@runCatching
            if (kotlin.math.abs(shoppingDayFireAt(day) - System.currentTimeMillis()) > 6L * 3600_000L) return@runCatching   // stale alarm
            val s = SettingsStore.s.value
            if (!alertsEnabledFor(Tab.SHOP, s)) { Logger.e(context, "ALERT", null, "shopping day suppressed: Shop alerts off"); return@runCatching }
            val open = markAllTargets(itemsOf(l.id)).size
            val name = if (l.personal) "a private list" else ((l.icon?.let { "$it " } ?: "") + l.name)
            Alerts.infoNotification(context, "Shopping day: $name",
                if (open == 0) "Nothing left to buy on it." else "$open item${if (open == 1) "" else "s"} to buy today.", Tab.SHOP)
            Logger.e(context, "ALERT", null, "shopping-day reminder fired for list ${l.id}")
        }.onFailure { Logger.e(context, "ALERT", it, "shopping-day fire failed") }
    }
}
