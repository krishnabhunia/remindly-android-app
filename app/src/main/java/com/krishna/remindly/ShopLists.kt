package com.krishna.remindly

/*
 * v2.11 (N48) — Buy tab LISTS FIRST (design/n48-lists-first-design-v1.html, rounds 1–2).
 * Everything here is PURE (no Android) so the unit tests run the real logic:
 *   • ShopList record + heal, the deterministic seed id, name helpers
 *   • which list an item belongs to (listId first, then its group NAME, else Unsorted)
 *   • the startup migration (groups → lists, items stamped) and duplicate-name healing
 *   • card stats, list ordering, per-id merge for sync/backup, the settings mirror
 *   • the share text ("List_Name:-" + numbered lines), delete / merge / duplicate / restart helpers
 * The list records live in AppSettings.shopLists; Item.group keeps mirroring the list NAME so older
 * app versions and the Classic view still see the same grouping.
 */

/** The virtual "Unsorted" list: Buy items whose list is missing or deleted. */
const val UNSORTED_LIST_ID = -1L
/** The cross-list Buy Now view (N38) opened from the Lists screen. */
const val BUY_NOW_LIST_ID = -2L

data class ShopList(
    val id: Long,
    val name: String = "",
    val icon: String? = null,
    val pinned: Boolean = false,
    val order: Int = 0,
    val usualShopId: Long? = null,
    val shoppingDay: Long? = null,      // any instant on the shopping DAY (local); null = none
    val personal: Boolean = false,      // Private list: every item in it is Personal (PIN-locked)
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null
)

@Suppress("USELESS_ELVIS")
fun healShopList(l: ShopList): ShopList = l.copy(
    name = (l.name ?: "").trim(),
    icon = l.icon?.trim()?.takeIf { it.isNotBlank() }
)

fun liveLists(lists: List<ShopList>): List<ShopList> = lists.filter { it.deletedAt == null && it.name.isNotBlank() }

fun listNamed(lists: List<ShopList>, name: String?): ShopList? {
    val n = name?.trim().orEmpty()
    if (n.isEmpty()) return null
    return liveLists(lists).firstOrNull { it.name.equals(n, ignoreCase = true) }
}

/** "Groceries" → "Groceries 2" (then 3, 4 …) when a live list already uses the name. */
fun uniqueListName(base: String, lists: List<ShopList>): String {
    val b = base.trim().ifEmpty { "List" }
    if (listNamed(lists, b) == null) return b
    var n = 2
    while (listNamed(lists, "$b $n") != null) n++
    return "$b $n"
}

/**
 * Seeded lists get an id derived from the NAME, so two devices migrating the same group mint the
 * SAME id and the per-id sync merge collapses them. The range (0x7E << 56 …) sits above every id
 * Ids.next() can mint (device tag ≤ 0xFFF in bits 44–55), so it can never collide with one.
 */
fun seedListId(name: String): Long {
    var h = -0x340d631b7bdddcdbL          // FNV-1a 64 offset basis
    for (b in name.trim().lowercase().toByteArray(Charsets.UTF_8)) {
        h = h xor (b.toLong() and 0xFF)
        h *= 0x100000001b3L
    }
    return (0x7EL shl 56) or (h and ((1L shl 56) - 1))
}

/** The live list an item belongs to: its listId when that list is live, else a live list named
 *  like its group, else null (= Unsorted). Only meaningful for Buy (Tab.SHOP) items. */
fun listIdOf(item: Item, lists: List<ShopList>): Long? {
    val live = liveLists(lists)
    item.listId?.let { id -> if (live.any { it.id == id }) return id }
    return listNamed(live, item.group)?.id
}

/** Buy items shown for [listId] (a real list, UNSORTED_LIST_ID, or BUY_NOW_LIST_ID = all). */
fun itemsInList(items: List<Item>, listId: Long, lists: List<ShopList>): List<Item> =
    items.filter { it.tab == Tab.SHOP && it.deletedAt == null }.filter {
        when (listId) {
            BUY_NOW_LIST_ID -> true
            UNSORTED_LIST_ID -> listIdOf(it, lists) == null
            else -> listIdOf(it, lists) == listId
        }
    }

// ---------------------------------------------------------------- migration / healing

data class ListSeed(val lists: List<ShopList>, val items: List<Item>, val defaultListId: Long?)

/**
 * The upgrade step, run at every start and after every import/sync (idempotent):
 *  1. every Buy group name (the settings registry + live items), trimmed and case-folded,
 *     becomes a live ShopList (deterministic id, N45 icon, N45 order) unless one exists;
 *  2. live lists sharing a name collapse to ONE (oldest createdAt, then smallest id) — the rest
 *     are tombstoned at [now];
 *  3. every Buy item whose list resolves gets listId + group stamped to that list; an item whose
 *     list is gone keeps listId null (Unsorted);
 *  4. the old default GROUP becomes the default LIST when no default list is set.
 * [items] returned are ONLY the changed ones.
 */
fun seedShopLists(
    items: List<Item>,
    lists: List<ShopList>,
    groupNames: List<String>,
    icons: Map<String, String>,
    groupOrder: List<String>,
    defaultGroup: String,
    currentDefault: Long?,
    now: Long
): ListSeed {
    val out = lists.toMutableList()
    val shopItems = items.filter { it.tab == Tab.SHOP && it.deletedAt == null }
    val names = LinkedHashMap<String, String>()
    (groupNames + shopItems.mapNotNull { it.group }).forEach { raw ->
        val n = raw.trim()
        if (n.isNotEmpty()) names.putIfAbsent(n.lowercase(), n)
    }
    var nextOrder = (out.maxOfOrNull { it.order } ?: -1) + 1
    names.values.forEach { n ->
        if (listNamed(out, n) != null) return@forEach
        // A tombstoned list with the same name stays deleted only when no live item still uses it.
        val ord = groupOrder.indexOfFirst { it.equals(n, ignoreCase = true) }
        val id = seedListId(n)
        val prior = out.firstOrNull { it.id == id }
        if (prior != null) out.remove(prior)
        out += ShopList(
            id = id, name = n, icon = icons[n]?.takeIf { it.isNotBlank() },
            order = if (ord >= 0) ord else nextOrder++, createdAt = prior?.createdAt?.takeIf { it > 0 } ?: now,
            updatedAt = now
        )
    }
    // 2. duplicate live names → keep the oldest
    val healed = dedupeListNames(out, now)
    // 3. stamp items
    val changed = items.mapNotNull { i ->
        if (i.tab != Tab.SHOP || i.deletedAt != null) return@mapNotNull null
        val id = listIdOf(i, healed) ?: return@mapNotNull (if (i.listId != null) i.copy(listId = null) else null)
        val name = healed.first { it.id == id }.name
        if (i.listId == id && i.group == name) null else i.copy(listId = id, group = name)
    }
    val def = currentDefault?.takeIf { d -> liveLists(healed).any { it.id == d } }
        ?: listNamed(healed, defaultGroup)?.id
    return ListSeed(healed, changed, def)
}

/** Live lists that share a name (case-insensitive) collapse to the oldest; the rest are tombstoned. */
fun dedupeListNames(lists: List<ShopList>, now: Long): List<ShopList> {
    val live = liveLists(lists)
    val losers = live.groupBy { it.name.lowercase() }.values
        .filter { it.size > 1 }
        .flatMap { same -> same.sortedWith(compareBy({ it.createdAt }, { it.id })).drop(1) }
        .map { it.id }.toSet()
    if (losers.isEmpty()) return lists
    return lists.map { if (it.id in losers) it.copy(deletedAt = now, updatedAt = now) else it }
}

/** Per-id latest-wins merge (sync + backup import). Tombstones win when they are newer. */
fun mergeShopLists(local: List<ShopList>, incoming: List<ShopList>): List<ShopList> {
    val byId = LinkedHashMap<Long, ShopList>()
    local.forEach { byId[it.id] = it }
    incoming.forEach { inc ->
        val ex = byId[inc.id]
        if (ex == null || inc.updatedAt >= ex.updatedAt) byId[inc.id] = inc
    }
    return byId.values.toList()
}

/** The settings mirror kept for older app versions and the Classic view: the Buy group registry,
 *  its order and its icons follow the live lists. Groups that are not lists are dropped. */
fun mirrorListsIntoSettings(s: AppSettings): AppSettings {
    val live = liveLists(s.shopLists).sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
    val names = live.map { it.name }
    val listNames = liveLists(s.shopLists).map { it.name.lowercase() }.toSet()
    // groupIcons is shared with Task groups (keyed by name): keep every non-list key, then the lists' own.
    val icons = s.groupIcons.filterKeys { k -> k.lowercase() !in listNames } +
        live.mapNotNull { l -> l.icon?.let { l.name to it } }
    val defName = s.shopDefaultListId?.let { d -> live.firstOrNull { it.id == d }?.name } ?: ""
    return s.copy(shopGroups = names, shopGroupOrder = names, groupIcons = icons, shopDefaultGroup = defName)
}

// ---------------------------------------------------------------- the Lists screen

data class ListStats(
    val toBuy: Int,
    val done: Int,
    val estTotal: Double?,          // null = no price known for any to-buy item
    val shops: List<String>,        // where the to-buy items point (max 2)
    val lastActivity: Long
)

/** Card figures for one list's items (already scoped with itemsInList). */
fun listStats(listItems: List<Item>, shopsById: Map<Long, String>, listUpdatedAt: Long = 0L): ListStats {
    val live = listItems.filter { it.deletedAt == null }
    val open = live.filter { !it.done }
    var total = 0.0
    var any = false
    open.forEach { i ->
        estPriceOf(i)?.let { total += it; any = true }
    }
    val shops = open.mapNotNull { i -> (i.shopId?.let { shopsById[it] } ?: i.shopName)?.trim()?.takeIf { it.isNotBlank() } }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(2)
    val last = (live.map { maxOf(it.updatedAt, it.createdAt, it.doneAt ?: 0L) } + listUpdatedAt).maxOrNull() ?: 0L
    return ListStats(open.size, live.count { it.done }, if (any) total else null, shops, last)
}

/** A to-buy item's expected cost: its price, else unit price × quantity (N45 history included). */
fun estPriceOf(i: Item): Double? {
    i.price?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { return it }
    val up = itemUnitPrice(i) ?: return null
    val q = i.quantity?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
    return up.first * q
}

/** "≈ ₹640" — whole rupees when round, else two decimals. */
fun estLabel(v: Double?): String? = v?.let { "≈ ₹" + (if (it == kotlin.math.floor(it)) "%,.0f".format(it) else "%,.2f".format(it)) }

/** Pinned first, then by the sort chip: RECENT (latest activity), AZ, CUSTOM (drag/move order). */
fun sortedLists(lists: List<ShopList>, mode: String, lastActivity: (ShopList) -> Long): List<ShopList> {
    val live = liveLists(lists)
    val byMode: Comparator<ShopList> = when (mode) {
        "AZ" -> compareBy { it.name.lowercase() }
        "CUSTOM" -> compareBy<ShopList>({ it.order }, { it.name.lowercase() })
        else -> compareByDescending<ShopList> { lastActivity(it) }.thenBy { it.name.lowercase() }
    }
    return live.sortedWith(compareBy<ShopList> { !it.pinned }.then(byMode))
}

/** CUSTOM order: move [id] one place up/down within the current custom order; renumbers 0..n. */
fun moveListOrder(lists: List<ShopList>, id: Long, up: Boolean, now: Long): List<ShopList> {
    val ordered = sortedLists(lists, "CUSTOM") { 0L }.toMutableList()
    val i = ordered.indexOfFirst { it.id == id }
    if (i < 0) return lists
    val j = if (up) i - 1 else i + 1
    if (j !in ordered.indices || ordered[j].pinned != ordered[i].pinned) return lists
    val a = ordered[i]; ordered[i] = ordered[j]; ordered[j] = a
    val newOrder = ordered.mapIndexed { idx, l -> l.id to idx }.toMap()
    return lists.map { l -> newOrder[l.id]?.let { o -> if (o != l.order) l.copy(order = o, updatedAt = now) else l } ?: l }
}

/** The next free CUSTOM position for a new list. */
fun nextListOrder(lists: List<ShopList>): Int = (liveLists(lists).maxOfOrNull { it.order } ?: -1) + 1

// ---------------------------------------------------------------- inside a list

/** L4: another LIVE list already holding an open item with this title (case-insensitive). */
fun otherListHolding(items: List<Item>, title: String, currentListId: Long?, lists: List<ShopList>): ShopList? {
    val t = title.trim().lowercase()
    if (t.isEmpty()) return null
    val hit = items.firstOrNull {
        it.tab == Tab.SHOP && !it.done && it.deletedAt == null && it.title.trim().lowercase() == t &&
            listIdOf(it, lists).let { l -> l != null && l != currentListId }
    } ?: return null
    return liveLists(lists).firstOrNull { it.id == listIdOf(hit, lists) }
}

/** L4: "Recently bought in this list" — bought titles (newest first) matching the typed text,
 *  skipping titles already open in the list. */
fun recentInList(listItems: List<Item>, query: String, limit: Int = 4): List<Item> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val open = listItems.filter { !it.done && it.deletedAt == null }.map { it.title.trim().lowercase() }.toSet()
    return listItems.filter { it.done && it.deletedAt == null }
        .sortedByDescending { it.doneAt ?: it.updatedAt }
        .distinctBy { it.title.trim().lowercase() }
        .filter { it.title.trim().lowercase().let { t -> t !in open && t.contains(q) } }
        .sortedBy { if (it.title.trim().lowercase().startsWith(q)) 0 else 1 }
        .take(limit)
}

/** Inside-a-list grouping By Category (from the linked Product); "No category" last. */
fun categoryGroupsOf(items: List<Item>, productsById: Map<Long, Product>): List<Pair<String, List<Item>>> {
    val by = items.groupBy { i -> i.productId?.let { productsById[it]?.category }?.trim()?.takeIf { it.isNotBlank() } }
    return by.filterKeys { it != null }.entries.sortedBy { it.key!!.lowercase() }.map { it.key!! to it.value } +
        (by[null]?.let { listOf("No category" to it) } ?: emptyList())
}

/** The cross-list Buy Now view: items grouped under their list (custom order), Unsorted last. */
fun listGroupsOf(items: List<Item>, lists: List<ShopList>): List<Pair<String, List<Item>>> {
    val by = items.groupBy { listIdOf(it, lists) }
    val ordered = sortedLists(lists, "CUSTOM") { 0L }
    return ordered.mapNotNull { l -> by[l.id]?.let { ((l.icon?.let { "$it " } ?: "") + l.name) to it } } +
        (by[null]?.let { listOf("Unsorted" to it) } ?: emptyList())
}

// ---------------------------------------------------------------- list actions

enum class ListDeleteMode { KEEP_UNSORTED, MOVE_TO, DELETE_ITEMS }

/**
 * L6: what happens to a deleted list's items. KEEP_UNSORTED clears listId+group; MOVE_TO re-homes
 * them into [target]. DELETE_ITEMS returns them unchanged — the caller soft-deletes (Bin + Undo).
 */
fun itemsAfterListDelete(listItems: List<Item>, mode: ListDeleteMode, target: ShopList?): List<Item> = when (mode) {
    ListDeleteMode.KEEP_UNSORTED -> listItems.map { it.copy(listId = null, group = null) }
    ListDeleteMode.MOVE_TO -> if (target == null) listItems.map { it.copy(listId = null, group = null) }
        else listItems.map { it.copy(listId = target.id, group = target.name) }
    ListDeleteMode.DELETE_ITEMS -> listItems
}

/** Rename: every item of the list follows the new name (group mirror). */
fun itemsAfterListRename(listItems: List<Item>, list: ShopList, newName: String): List<Item> =
    listItems.map { it.copy(listId = list.id, group = newName.trim()) }

/** Private list ON → every item Personal; OFF → none (the list owns the flag). */
fun itemsAfterPrivacy(listItems: List<Item>, personal: Boolean): List<Item> =
    listItems.filter { it.personal != personal }.map { it.copy(personal = personal) }

/** Duplicate: fresh open copies of the live items, in the new list. */
fun duplicateListItems(listItems: List<Item>, newList: ShopList, newId: () -> Long, now: Long): List<Item> =
    listItems.filter { it.deletedAt == null }.map {
        it.copy(
            id = newId(), listId = newList.id, group = newList.name, done = false, doneAt = null,
            snoozedUntil = null, returnAt = null, createdAt = now, updatedAt = now, calEventId = null,
            personal = it.personal || newList.personal
        )
    }

/** L12 Restart: the bought items that return to To buy (quantity, unit, price kept). */
fun restartTargets(listItems: List<Item>): List<Item> = listItems.filter { it.done && it.deletedAt == null }

/** Mark all bought: the open items. */
fun markAllTargets(listItems: List<Item>): List<Item> = listItems.filter { !it.done && it.deletedAt == null }

// ---------------------------------------------------------------- sharing (round 2)

data class ListShareOpts(
    val includeBought: Boolean,
    val urgentTag: Boolean,
    val boughtTag: Boolean,
    val qtyType: Boolean,
    val suffix: String = ":-"
)

fun listShareOptsOf(s: AppSettings): ListShareOpts = ListShareOpts(
    includeBought = s.shareIncludeDone, urgentTag = s.shareUrgentTag, boughtTag = s.shareBoughtTag,
    qtyType = s.shareIncludeQty, suffix = s.shareHeadingSuffix
)

/** "5 / kg" · "3" (no unit) · null (no quantity → the segment is dropped). */
fun qtySegment(i: Item): String? {
    val q = i.quantity?.trim().orEmpty()
    if (q.isEmpty()) return null
    val u = i.unit?.trim().orEmpty()
    return if (u.isEmpty()) q else "$q / $u"
}

fun listShareLine(n: Int, i: Item, o: ListShareOpts): String = buildString {
    append(n).append(". ").append(i.title.trim())
    if (o.qtyType) qtySegment(i)?.let { append(" - ").append(it) }
    if (o.urgentTag && i.priority == Priority.URGENT) append(" - Urgent")
    if (o.boughtTag && i.done) append(" - Bought")
}

/**
 * Krishna's format (29-Sep-2026):
 *   List_Name:-
 *   <blank line>
 *   1. Item - Quantity / Type - Urgent(optional) - Bought(optional)
 * Lines keep the on-screen order; bought lines come last (only when included). Prices, shop and
 * notes are never written. The caller removes locked Personal items first.
 */
fun listShareText(listName: String, items: List<Item>, o: ListShareOpts): String {
    val live = items.filter { it.deletedAt == null }
    val rows = live.filter { !it.done } + (if (o.includeBought) live.filter { it.done } else emptyList())
    val head = listName.trim() + o.suffix
    if (rows.isEmpty()) return head
    return head + "\n\n" + rows.mapIndexed { idx, i -> listShareLine(idx + 1, i, o) }.joinToString("\n")
}

/** WhatsApp package for the chosen app, falling back to the other one when only that is installed. */
fun whatsAppPackage(choice: String, installed: (String) -> Boolean): String? {
    val wa = "com.whatsapp"; val biz = "com.whatsapp.w4b"
    val order = if (choice == "BUSINESS") listOf(biz, wa) else listOf(wa, biz)
    return order.firstOrNull(installed)
}

// ---------------------------------------------------------------- shopping day (F5)

/** The shopping-day reminder fires at 09:00 local on that day (same hour as the expiry alarm). */
const val SHOPPING_DAY_HOUR = 9

fun shoppingDayFireAt(dayMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
    java.time.Instant.ofEpochMilli(dayMs).atZone(zone).toLocalDate()
        .atTime(SHOPPING_DAY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

/** Lists whose shopping-day reminder is still ahead of [now]. */
fun upcomingShoppingDays(lists: List<ShopList>, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Pair<ShopList, Long>> =
    liveLists(lists).mapNotNull { l -> l.shoppingDay?.let { d -> shoppingDayFireAt(d, zone).takeIf { it > now }?.let { l to it } } }

// ---------------------------------------------------------------- G1: a received list becomes mine

/** A received (frozen) Shop share copied into my lists: a new list named after the sender's list. */
fun itemsFromShared(shared: List<SharedItem>, list: ShopList, newId: () -> Long, now: Long): List<Item> =
    shared.filter { it.title.isNotBlank() }.map { s ->
        Item(
            id = newId(), tab = Tab.SHOP, title = s.title.trim(), notes = s.note,
            quantity = s.quantity.ifBlank { null }, unit = s.unit.ifBlank { null },
            priority = Priority.MEDIUM, listId = list.id, group = list.name,
            personal = list.personal, createdAt = now, updatedAt = now
        )
    }

// ---------------------------------------------------------------- card wording

/** "just now" · "5 min ago" · "2 h ago" · "yesterday" · "3 d ago" · "12 Aug" (older than a week). */
fun relativeAgo(then: Long, now: Long): String {
    if (then <= 0L) return ""
    val d = now - then
    return when {
        d < 60_000L -> "just now"
        d < 3_600_000L -> "${d / 60_000L} min ago"
        d < 86_400_000L -> "${d / 3_600_000L} h ago"
        d < 2 * 86_400_000L -> "yesterday"
        d < 7 * 86_400_000L -> "${d / 86_400_000L} d ago"
        else -> java.time.Instant.ofEpochMilli(then).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH))
    }
}

/** The card's second line: "5 to buy · 2 done · updated 2 h ago" / "All bought · …" / "Empty · …". */
fun listCardSubtitle(st: ListStats, now: Long): String {
    val head = when {
        st.toBuy == 0 && st.done == 0 -> "Empty"
        st.toBuy == 0 -> "All bought"
        else -> "${st.toBuy} to buy · ${st.done} done"
    }
    val ago = relativeAgo(st.lastActivity, now)
    return if (ago.isEmpty()) head else "$head · updated $ago"
}

/** The on-screen order a list is shared in: its inner grouping flattened (open lines first is
 *  applied later by listShareText). Mirrors ListPage's grouping for the same sort key. */
fun shareOrderFor(items: List<Item>, mode: String, products: List<Product>): List<Item> {
    val live = items.filter { it.deletedAt == null }
    fun basis(i: Item) = i.dueAt ?: i.createdAt
    return when (mode) {
        "SHOP" -> shopGroupsOf(live).flatMap { (_, v) -> v.sortedBy(::basis) }
        "CATEGORY" -> categoryGroupsOf(live, products.associateBy { it.id }).flatMap { (_, v) -> v.sortedBy(::basis) }
        "PRIORITY" -> live.sortedWith(compareBy<Item>({ rankOf(it.priority) }, { basis(it) }))
        "NONE" -> live.sortedWith(compareBy<Item>({ it.createdAt }, { it.id }))
        else -> live.sortedBy(::basis)
    }
}

/** Discarding a settings page restores its snapshot — but the LIST RECORDS are data (items point
 *  at them), so the current ones are kept and the mirror is rebuilt from them. */
fun keepListData(snapshot: AppSettings, current: AppSettings): AppSettings =
    mirrorListsIntoSettings(snapshot.copy(shopLists = current.shopLists))
