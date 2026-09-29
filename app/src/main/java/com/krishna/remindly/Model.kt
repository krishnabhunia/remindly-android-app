package com.krishna.remindly

import kotlin.math.roundToInt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------- enums

enum class Tab(val title: String) { TASKS("Tasks"), SHOP("Shop"), LEARN("Learn") }

enum class Priority(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High"), URGENT("Urgent")
}

enum class LapseUnit(val label: String) { DAYS("Days"), MONTHS("Months") }

enum class TriggerType(val label: String) {
    LEAVE("When I leave"), ARRIVE("When I arrive")
}

enum class CallSource(val label: String) { AUTO("Auto"), MANUAL("Manual") }

val PLATFORM_OPTIONS = listOf("Online", "Offline", "Internet", "LinkedIn Learning", "Udemy")

// ---------------------------------------------------------------- entities

/**
 * A recorded purchase. `price` stays the MRP/cost total (legacy field; still drives the editor's
 * "Last: ₹X" hint). v1.39 adds the shop, quantity, unit, unit price, what was actually paid, and
 * the discount — the data the checkout calculator captures and the cheapest-shop recommendation (Phase C) will use.
 */
data class PricePoint(
    val at: Long = 0L,
    val price: Double = 0.0,        // MRP / cost total
    val shop: String = "",
    val qty: Double = 0.0,
    val unit: String = "",
    val unitPrice: Double = 0.0,
    val paid: Double = 0.0,         // buy price actually paid; 0.0 = not entered
    val discountPct: Double = 0.0
)

/** Result of the Shop checkout calculator (Phase B). Any field may be null when not derivable. */
data class ShopCalc(
    val unitPrice: Double?,
    val qty: Double?,
    val cost: Double?,        // MRP total
    val paid: Double?,        // buy price
    val discountAmt: Double?,
    val discountPct: Double?
)

/**
 * Pure checkout math (unit-tested). Given any two of {unitPrice, qty, cost} the third is derived
 * (cost = unitPrice × qty). If `paid` (buy price) is given, discount amount and % come from cost.
 * Division guards: a zero qty/unitPrice can't derive its partner, so that field stays null.
 */
fun computeShopCalc(unitPriceIn: Double?, qtyIn: Double?, costIn: Double?, paidIn: Double?): ShopCalc {
    var u = unitPriceIn
    var q = qtyIn
    var c = costIn
    when {
        u != null && q != null && c == null -> c = u * q
        q != null && c != null && u == null && q != 0.0 -> u = c / q
        u != null && c != null && q == null && u != 0.0 -> q = c / u
    }
    if (c == null && u != null && q != null) c = u * q
    val amt = if (c != null && paidIn != null) c - paidIn else null
    val pct = if (c != null && c > 0.0 && paidIn != null) (c - paidIn) / c * 100.0 else null
    return ShopCalc(u, q, c, paidIn, amt, pct)
}

/** v1.40 append an enriched purchase, keeping the last 12. */
fun pushPurchase(history: List<PricePoint>, point: PricePoint): List<PricePoint> =
    (history + point).takeLast(12)

/**
 * v1.40 (bug #3): count of a tab's cards "pending today" for the bottom-nav badge — items that
 * are due today or overdue (dueAt < start of tomorrow), not future, excluding done and soft-deleted.
 * Items with no due date are not counted.
 */
fun pendingTodayCount(items: List<Item>, tab: Tab, startOfTomorrow: Long): Int =
    items.count {
        it.tab == tab && !it.done && it.deletedAt == null &&
            it.dueAt != null && it.dueAt!! < startOfTomorrow
    }

/** v1.41 Feature 1: bottom-nav tab visibility (index 0..4; 4 = Settings, always visible). */
fun isTabVisible(s: AppSettings, i: Int): Boolean = when (i) {
    // v2.00 (N31): index 1 (Shop) is NEVER in the Task-mode bar — Shop mode owns it.
    0 -> s.showTasks; 1 -> false; 2 -> s.showLearn; 3 -> s.showCalls; else -> true
}
fun firstVisibleTab(s: AppSettings): Int = (0..4).firstOrNull { isTabVisible(s, it) } ?: 4
fun nextVisibleTab(s: AppSettings, from: Int, forward: Boolean): Int {
    val range = if (forward) (from + 1..4) else (from - 1 downTo 0)
    return range.firstOrNull { isTabVisible(s, it) } ?: from
}

// ---------------------------------------------------------------- Shop Phase C (v1.42)

/** unit → (family, factor to the family's base unit). Unknown units become their own family. */
fun unitFamily(unit: String): Pair<String, Double> = when (unit.trim().lowercase()) {
    "kg" -> "WEIGHT" to 1000.0   // base = gram
    "g" -> "WEIGHT" to 1.0
    "l" -> "VOLUME" to 1000.0    // base = millilitre
    "ml" -> "VOLUME" to 1.0
    "dozen" -> "COUNT" to 12.0   // base = piece
    "pcs" -> "COUNT" to 1.0
    else -> (unit.trim().lowercase().ifBlank { "?" }) to 1.0
}

/** A cheapest-shop suggestion: [unitPrice] per [unit] at [shop], normalized to [perBase] for comparison. */
data class ShopRec(
    val shop: String,
    val perBase: Double,
    val unit: String,
    val unitPrice: Double,
    val at: Long,
    val family: String
)

/**
 * Cheapest shop for [name], by unit price normalized within a unit-family (g↔kg, ml↔L, dozen=12pcs).
 * Scans every Shop item's purchase history for records that carry shop + unitPrice + unit. If
 * [preferUnit] is given and that family has records, it's restricted to that family; otherwise the
 * family of the most recent record is used (incomparable units aren't mixed). Returns the lowest
 * per-base record (tie → most recent), or null when nothing comparable exists.
 */
fun cheapestShop(items: List<Item>, name: String, preferUnit: String? = null): ShopRec? {
    val key = name.trim().lowercase()
    if (key.isEmpty()) return null
    val recs = items
        .filter { it.tab == Tab.SHOP && it.deletedAt == null && it.title.trim().lowercase() == key }
        .flatMap { it.priceHistory }
        .filter { it.shop.isNotBlank() && it.unitPrice > 0.0 && it.unit.isNotBlank() }
        .map { pp ->
            val (fam, factor) = unitFamily(pp.unit)
            ShopRec(pp.shop, pp.unitPrice / factor, pp.unit, pp.unitPrice, pp.at, fam)
        }
    if (recs.isEmpty()) return null
    val family = preferUnit?.let { unitFamily(it).first }?.takeIf { fam -> recs.any { it.family == fam } }
        ?: recs.maxByOrNull { it.at }!!.family
    return recs.filter { it.family == family }.minWithOrNull(compareBy({ it.perBase }, { -it.at }))
}

/**
 * v1.43 Shop Phase D: group Shop items by shop name (alphabetical); items with no shop fall last
 * under "No Shop". Pure so the grouping order is unit-testable.
 */
fun shopGroupsOf(items: List<Item>): List<Pair<String, List<Item>>> {
    val byShop = items.groupBy { it.shopName?.trim().takeUnless { s -> s.isNullOrBlank() } }
    return byShop.filterKeys { it != null }.entries
        .sortedBy { it.key!!.lowercase() }
        .map { it.key!! to it.value } +
        (byShop[null]?.let { listOf("No Shop" to it) } ?: emptyList())
}

data class Item(
    val id: Long,
    val tab: Tab,
    val title: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val dueAt: Long? = null,
    val priority: Priority? = null,
    val alertType: String = "N",   // v1.68: THE per-item Reminder Type
    // v1.80 (Q17): a live snooze. Persisted so it survives a reboot, shows in "Coming up",
    // and can be cancelled. Null or past = no snooze.
    val snoozedUntil: Long? = null,
    // Shop extras
    val quantity: String? = null,
    val price: String? = null,
    val shopName: String? = null,
    val lapseValue: Int? = null,
    val lapseUnit: LapseUnit? = null,
    val expiryAt: Long? = null,
    val personal: Boolean = false,
    // Learn extras
    val platform: String? = null,
    val url: String? = null,
    val topic: String? = null,
    // v1.58 1.3: EXTRA alert rules for this item, ADDITIVE to the normal due alert.
    // null = inherit the tab's default extras; "" = explicitly none; else "B30,A60=RN,C1080,D<epoch>".
    // v1.86 (N20): occurrences AUTO-ROLLED past without completion — newest first, capped at
    // MISSED_CAP. Purely a log; never affects scheduling. Rides sync as part of the item json.
    val missedAt: List<Long> = emptyList(),
    // v1.6: false = date-only due (rings at the Default due time chosen at save)
    val dueHasTime: Boolean = true,
    // v1.8 recurrence
    val repeatMode: String = "OFF",        // OFF DAILY WEEKLY MONTHLY_DAY MONTHLY_ORD EVERY_N
    val repeatDays: List<Int> = emptyList(),// WEEKLY: ISO weekdays 1=Mon..7=Sun
    val repeatN: Int = 1,                   // EVERY_N count
    val repeatUnit: String = "D",          // EVERY_N unit D/W/M
    val repeatOrd: Int = 1,                 // MONTHLY_ORD legacy single: 1..4, 5 = Last
    val repeatDow: Int = 1,                 // MONTHLY_ORD legacy single weekday
    val repeatOrdList: List<Int> = emptyList(), // v1.9 MONTHLY_ORD set: ord*10+dow (e.g. 11=First Mon, 56=Last Sat)
    val repeatCount: Int? = null,           // v1.22 item 7: end after N occurrences (>= 2), null = never ends
    val repeatDone: Int = 0,                // completions tallied against repeatCount
    val updatedAt: Long = 0L,               // v1.9 merge stamp
    val deletedAt: Long? = null,            // v1.11 soft-delete (Recently deleted bin)
    // v1.15
    val progress: Int = 0,                  // Learn: 0-100 %
    val spacedStep: Int = 0,                // Learn SPACED revisit ladder position
    val hoursSpent: Double = 0.0,           // Learn: time invested
    val oosAt: Long? = null,                // Shop trip: out-of-stock day stamp
    val unit: String? = null,               // Shop: kg/g/L/ml/pcs/dozen
    // v1.90: structured links — a buy item may reference a Product and a registered Shop by id.
    // Both nullable: free-text buy items remain first-class; shopName stays the display fallback
    // (v1.72 coat rule: nullable adds ride legacy JSON with no heal needed).
    val productId: Long? = null,
    val shopId: Long? = null,
    val staple: Boolean = false,            // Shop: one-tap staples
    val priceHistory: List<PricePoint> = emptyList(), // Shop: last 12 purchases
    val calEventId: Long? = null,           // calendar sync mapping
    // Shop grouping label (v1.4)
    val group: String? = null,
    // v2.11 (N48): the ShopList this Buy item belongs to (null = Unsorted). `group` keeps mirroring
    // the list's NAME so older app versions and the Classic view still see the same grouping.
    val listId: Long? = null,
    // state
    val done: Boolean = false,
    val doneAt: Long? = null,
    val returnAt: Long? = null
)

data class GeoPlace(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Float,
    val trigger: TriggerType,
    val enabled: Boolean = true,
    val lastFired: Long = 0L,
    val groupFilter: List<String> = emptyList(), // v1.15 place→group binding
    val updatedAt: Long = 0L,               // v1.34 merge stamp (sync groundwork)
    val deletedAt: Long? = null             // v1.34 soft-delete tombstone (sync groundwork)
)

/**
 * v1.37 (Shop overhaul, Phase A): a registered shop. Name is the identity items bind to via
 * item.shopName. Geofence (lat/lng/radius) is optional; isDefault marks the one auto-filled on new
 * shop items. Merge-stamped like everything else for future cross-device sync.
 */
data class Shop(
    val id: Long,
    val name: String,
    // v1.48: one optional free-text locator — city OR landmark OR PIN (not three fields).
    // v1.90: superseded by cityId for organisation; kept for legacy heal + one-time seeding.
    val area: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val radius: Float = 150f,
    val isDefault: Boolean = false,
    // v1.90: a shop lives in one City (null = Unassigned bucket) and optionally belongs to a
    // Chain (D-Mart, Reliance Smart Bazaar…) — a chain branch is just a Shop with chainId set.
    val cityId: Long? = null,
    val chainId: Long? = null,
    // v2.05 (N37): how THIS shop alerts on arrival — null = follow the Shops ⚙ default.
    val arriveTypes: String? = null,        // subset of "NRA"
    val lastArriveFired: Long = 0L,         // per-shop cooldown clock
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null
) {
    val hasGeofence: Boolean get() = lat != null && lng != null
}

fun healShop(s: Shop): Shop = s.copy(
    name = s.name ?: "",
    arriveTypes = normalizeAlertTypes(s.arriveTypes),
    area = s.area?.trim()?.takeIf { it.isNotBlank() },
    radius = snapRadius(if (s.radius > 0f) s.radius else 150f)
)

// ---------------------------------------------------------------- v1.90 shop-mode entities

/** A city holding shops. Soft-deleted like every other record. */
data class City(
    val id: Long,
    val name: String,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null
)

fun healCity(c: City): City = c.copy(name = (c.name ?: "").trim())

/** A shop chain (brand). Branches are Shops carrying chainId; the chain itself has no geofence. */
data class Chain(
    val id: Long,
    val name: String,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null
)

fun healChain(c: Chain): Chain = c.copy(name = (c.name ?: "").trim())

/** A catalogued product the buy list can reference. */
data class Product(
    val id: Long,
    val name: String,
    val category: String? = null,
    val defaultUnit: String? = null,        // kg/g/L/ml/pcs/dozen/strip…
    val note: String? = null,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null
)

fun healProduct(p: Product): Product = p.copy(
    name = (p.name ?: "").trim(),
    category = p.category?.trim()?.takeIf { it.isNotBlank() },
    defaultUnit = p.defaultUnit?.trim()?.takeIf { it.isNotBlank() }
)

/** Product ↔ Shop availability link with the last known price at that shop (many-to-many). */
data class ProductLink(
    val productId: Long,
    val shopId: Long,
    val lastPrice: Double = 0.0,            // total cost last paid/entered (0 = unknown)
    val lastUnitPrice: Double = 0.0,        // per defaultUnit (0 = unknown)
    val lastAt: Long = 0L,
    val deletedAt: Long? = null
)

fun healLink(l: ProductLink): ProductLink = l.copy(
    lastPrice = if (l.lastPrice.isFinite() && l.lastPrice >= 0.0) l.lastPrice else 0.0,
    lastUnitPrice = if (l.lastUnitPrice.isFinite() && l.lastUnitPrice >= 0.0) l.lastUnitPrice else 0.0
)

/** "D-Mart – Serampore" — the auto branch name shown (editable) in the shop editor. */
fun branchAutoName(chain: String, city: String): String {
    val c = chain.trim(); val t = city.trim()
    return when {
        c.isEmpty() -> t
        t.isEmpty() -> c
        else -> "$c – $t"
    }
}

/**
 * v1.90 one-time migration: build Cities from the distinct free-text `area` values on live
 * shops (case-insensitive dedupe, trimmed) and stamp each shop's cityId. Blank/absent area →
 * cityId stays null (the Unassigned bucket). Pure and idempotent: shops that already carry a
 * cityId are left untouched, and existing cities are reused by name instead of duplicated.
 * Returns (allCities, updatedShops).
 */
fun seedCitiesFromShops(
    shops: List<Shop>,
    existing: List<City>,
    idGen: () -> Long,
    now: Long
): Pair<List<City>, List<Shop>> {
    val cities = existing.toMutableList()
    fun cityFor(name: String): City {
        val key = name.trim()
        cities.firstOrNull { it.deletedAt == null && it.name.equals(key, ignoreCase = true) }?.let { return it }
        val c = City(id = idGen(), name = key, updatedAt = now)
        cities += c
        return c
    }
    val updated = shops.map { s ->
        if (s.deletedAt != null || s.cityId != null) s
        else {
            val area = s.area?.trim().orEmpty()
            if (area.isEmpty()) s else s.copy(cityId = cityFor(area).id, updatedAt = now)
        }
    }
    return cities to updated
}

/** Pure: the shop list after a city is deleted — its shops move to Unassigned (cityId null). */
fun shopsAfterCityDelete(shops: List<Shop>, cityId: Long): List<Shop> =
    shops.map { if (it.cityId == cityId) it.copy(cityId = null) else it }

/**
 * Pure: the shop list after a chain is deleted. keepBranches true → branches become local
 * shops; false → branches are soft-deleted (default flag dropped, existing tombstones kept).
 */
fun shopsAfterChainDelete(shops: List<Shop>, chainId: Long, keepBranches: Boolean, now: Long): List<Shop> =
    shops.map {
        when {
            it.chainId != chainId -> it
            keepBranches -> it.copy(chainId = null, updatedAt = now)
            else -> it.copy(chainId = null, deletedAt = it.deletedAt ?: now, isDefault = false, updatedAt = now)
        }
    }

/** Pure: checkout write-back merge — a 0/absent figure never erases a remembered price. */
fun mergedLinkPrice(prev: ProductLink?, productId: Long, shopId: Long, price: Double, unitPrice: Double, at: Long): ProductLink =
    ProductLink(
        productId = productId, shopId = shopId,
        lastPrice = if (price > 0.0) price else prev?.lastPrice ?: 0.0,
        lastUnitPrice = if (unitPrice > 0.0) unitPrice else prev?.lastUnitPrice ?: 0.0,
        lastAt = at, deletedAt = null
    )

// ---------------------------------------------------------------- v2.01 (N32) pure helpers

/** Krishna's Option D (16-Aug-2026): clear space below every sheet = 25% of the window height. */
const val SHEET_CLEAR_PCT = 0.25f
const val SHEET_CLEAR_MIN_EXTRA_DP = 24f
const val SHEET_CLEAR_IME_DP = 24f

/**
 * Bottom clearance for a bottom sheet, in dp. Never derived from insets read inside the sheet
 * popup (that measured 0 on Krishna's device): [navBottomDp] is the REAL nav-bar height measured
 * on the activity window; [windowHeightDp] is the app window height (split-screen/landscape
 * safe). While the keyboard is showing it already covers the nav bar, so only a small gap is kept.
 * Total and safe on garbage: negatives/NaN treated as 0.
 */
fun sheetClearanceDp(windowHeightDp: Float, navBottomDp: Float, imeVisible: Boolean): Float {
    if (imeVisible) return SHEET_CLEAR_IME_DP
    val h = if (windowHeightDp.isFinite() && windowHeightDp > 0f) windowHeightDp else 0f
    val nav = if (navBottomDp.isFinite() && navBottomDp > 0f) navBottomDp else 0f
    return maxOf(h * SHEET_CLEAR_PCT, nav + SHEET_CLEAR_MIN_EXTRA_DP)
}

/**
 * Which settings sections a page shows — one table for the General page (null), the per-tab gear
 * pages, and the Shop-mode Settings deck. v2.01 (N32): the Shop-mode sections became accordions
 * here (shop-mode / shop-buy / shop-geo / shop-data); "shop-mode" (Start in) is on BOTH the
 * General page (first) and the SHOP deck (B1).
 */
fun settingsSectionVisible(filterKey: String?, key: String): Boolean = when (filterKey) {
    // v2.04 (N36): "shop-mode" (Start in) is gone — the ☰ drawer owns the mode, always last used.
    null -> key in setOf("permissions", "updates", "maps", "api-keys", "alerts", "adding", "done", "lists", "sched", "gestures", "swipe", "clock",
        "appearance", "google", "backup", "errlog", "health", "tests", "bin", "details", "about")
    "TASKS" -> key in setOf("t-groups", "t-add", "t-cal")
    // v2.04 (N35): each Shop-mode tab owns its settings, like Tasks/Learn/Calls in Task mode.
    "BUY" -> key in setOf("b-lists", "shop-buy", "s-groups", "s-add", "pin", "sharing")   // v2.11 (N48): + Lists
    "SHOPS" -> key in setOf("shop-geo", "location")
    "PRODUCTS" -> key in setOf("shop-data")
    "SHOP" -> key in setOf("b-lists", "shop-buy", "s-groups", "s-add", "pin")   // legacy key = the Buy page
    "LEARN" -> key in setOf("l-groups", "l-add")
    "CALLS" -> key in setOf("callrem", "c-add", "c-hk", "c-al", "c-ge", "c-cl")
    else -> false
}

/** The Reset row's exact scope per page — pure so the scope is unit-tested (v2.04: per Shop-mode tab). */
fun resetSettingsFor(s: AppSettings, filterKey: String?): AppSettings {
    val d = AppSettings()
    return when (filterKey) {
        null -> s.copy(
            defaultDueMinutes = d.defaultDueMinutes,
            snoozeM1 = d.snoozeM1,
            moveDelaySec = d.moveDelaySec,
            gestureCardSwipe = d.gestureCardSwipe, cardSwipeRightDone = d.cardSwipeRightDone,
            gesturePageSwipe = d.gesturePageSwipe, pageSwipeRightDone = d.pageSwipeRightDone,
            gestureNavSwipe = d.gestureNavSwipe, navSwipeRightNext = d.navSwipeRightNext,
            activeSwipeAction = d.activeSwipeAction, doneSwipeAction = d.doneSwipeAction,
            deleteStyle = d.deleteStyle, revSwipeDelete = d.revSwipeDelete,
            fontScale = d.fontScale, fsNav = d.fsNav, fsCardTitle = d.fsCardTitle,
            fsCardDetail = d.fsCardDetail, fsGroupHeader = d.fsGroupHeader,
            fsScreenHeader = d.fsScreenHeader, fsButtons = d.fsButtons,
            densityPct = d.densityPct,
            theme = d.theme, badges = d.badges,
            // v2.02 (N33): provider/limit reset; API keys are NEVER touched by a reset (explicit Remove only)
            mapProvider = d.mapProvider, geoLimit = d.geoLimit, indiaBilling = d.indiaBilling,
            defaultRadius = d.defaultRadius,
            groupHeaderCheck = d.groupHeaderCheck          // v2.7 (N43)
        )
        "TASKS" -> s.copy(
            tasksGroupCheck = d.tasksGroupCheck,           // v2.7 (N43)
            tasksAddFull = d.tasksAddFull, tasksNewDueMode = d.tasksNewDueMode,
            tasksNewDueDays = d.tasksNewDueDays, tasksNewDueMinutes = d.tasksNewDueMinutes,
            tasksDoneClearDays = d.tasksDoneClearDays
        )
        // v2.04 (N35): Buy page resets the buy-list scope; Shops page resets the geofence scope.
        "SHOP", "BUY" -> s.copy(
            shopGroupCheck = d.shopGroupCheck,             // v2.7 (N43)
            shopAddFull = d.shopAddFull, shopNewDueMode = d.shopNewDueMode,
            shopNewDueDays = d.shopNewDueDays, shopNewDueMinutes = d.shopNewDueMinutes,
            shopDoneClearDays = d.shopDoneClearDays,
            shopCheckoutCalc = d.shopCheckoutCalc, shopCheapestHint = d.shopCheapestHint,
            shopSort = d.shopSort,
            // v2.11 (N48): Lists + Sharing rows (never the list records or the default list — data).
            buyOpensOn = d.buyOpensOn, buyReopenLast = d.buyReopenLast, buyShowUnsorted = d.buyShowUnsorted,
            buyCardTotal = d.buyCardTotal, buyDupWarn = d.buyDupWarn, buyListSort = d.buyListSort, buyInnerSort = d.buyInnerSort,
            shareWaIcon = d.shareWaIcon, shareWaApp = d.shareWaApp, shareUrgentTag = d.shareUrgentTag,
            shareBoughtTag = d.shareBoughtTag, shareHeadingSuffix = d.shareHeadingSuffix
        )
        "SHOPS" -> s.copy(
            shopArriveAlert = d.shopArriveAlert, shopNewRadius = d.shopNewRadius,
            defaultRadius = d.defaultRadius,
            // v2.05 (N37): the arrival-alert default and cooldown are Shops-scoped too.
            shopArriveTypes = d.shopArriveTypes, shopArriveCooldownMin = d.shopArriveCooldownMin
        )
        "PRODUCTS" -> s
        "LEARN" -> s.copy(
            learnGroupCheck = d.learnGroupCheck,           // v2.7 (N43)
            learnAddFull = d.learnAddFull, learnNewDueMode = d.learnNewDueMode,
            learnNewDueDays = d.learnNewDueDays, learnNewDueMinutes = d.learnNewDueMinutes,
            learnDoneClearDays = d.learnDoneClearDays
        )
        else -> s.copy(
            callReminderMinutes = d.callReminderMinutes,
            callsDoneClearDays = d.callsDoneClearDays
        )
    }
}

// ---------------------------------------------------------------- v2.02 (N33) pure helpers

const val PROVIDER_GOOGLE = "GOOGLE"
const val PROVIDER_OSM = "OSM"

fun mapProviderNormalized(p: String?): String = if (p == PROVIDER_OSM) PROVIDER_OSM else PROVIDER_GOOGLE

/** Google's monthly free cap for the Geocoding (Essentials) SKU: 10k globally, 7× for India-billed. */
fun freeCapFor(indiaBilling: Boolean): Int = if (indiaBilling) 70_000 else 10_000
fun defaultGeoLimitFor(indiaBilling: Boolean): Int = (freeCapFor(indiaBilling) * 0.8f).toInt()

/** "yyyy-MM" in America/Los_Angeles — Google resets free usage at Pacific midnight on the 1st. */
fun monthKeyPacific(nowMs: Long): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
    cal.timeInMillis = nowMs
    return "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
}

/** "1 Sep" style label for the first day of the month AFTER [monthKey] ("2026-08" → "1 Sep"). */
fun nextMonthLabel(monthKey: String): String {
    val parts = monthKey.split("-")
    val y = parts.getOrNull(0)?.toIntOrNull() ?: return "the 1st"
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return "the 1st"
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val nm = if (m >= 12) 1 else m + 1
    val ny = if (m >= 12) y + 1 else y
    return "1 ${names.getOrElse(nm - 1) { "?" }}" + (if (ny != y) " $ny" else "")
}

/** Per-device Google geocoding usage for one month (lives in UiStore, never synced). */
data class GeoUsage(val monthKey: String = "", val count: Int = 0, val lockedMonth: String? = null)

/** Roll the usage forward to [nowKey]: a new month resets the count and clears an old lock. */
fun usageForMonth(u: GeoUsage, nowKey: String): GeoUsage =
    if (u.monthKey == nowKey) u.copy(lockedMonth = if (u.lockedMonth == nowKey) u.lockedMonth else null)
    else GeoUsage(monthKey = nowKey, count = 0, lockedMonth = null)

fun isGeoLocked(u: GeoUsage, nowKey: String): Boolean = usageForMonth(u, nowKey).lockedMonth == nowKey

/** After one Google geocoding call: count +1; crossing the limit locks the rest of the month. */
fun usageAfterCall(u: GeoUsage, nowKey: String, limit: Int): GeoUsage {
    val cur = usageForMonth(u, nowKey)
    val n = cur.count + 1
    return cur.copy(count = n, lockedMonth = if (n >= limit) nowKey else cur.lockedMonth)
}

/** Which provider a GEOCODING call uses right now (Krishna's rule: Google, else OSM). */
fun resolveGeoProvider(s: AppSettings, u: GeoUsage, nowKey: String, keyPresent: Boolean): String {
    if (mapProviderNormalized(s.mapProvider) != PROVIDER_GOOGLE) return PROVIDER_OSM
    if (!keyPresent) return PROVIDER_OSM
    val cur = usageForMonth(u, nowKey)
    if (cur.lockedMonth == nowKey) return PROVIDER_OSM
    if (cur.count >= s.geoLimit) return PROVIDER_OSM
    return PROVIDER_GOOGLE
}

/** Which provider the MAP PICKER uses (map loads are free/unlimited; the lock still forces OSM). */
fun resolveMapProvider(s: AppSettings, u: GeoUsage, nowKey: String, sdkKeyPresent: Boolean, playServicesOk: Boolean): String {
    if (mapProviderNormalized(s.mapProvider) != PROVIDER_GOOGLE) return PROVIDER_OSM
    if (!sdkKeyPresent || !playServicesOk) return PROVIDER_OSM
    if (isGeoLocked(u, nowKey)) return PROVIDER_OSM
    return PROVIDER_GOOGLE
}

/** Outcome classes for a Google Geocoding response. */
enum class GeoOutcome { OK, EMPTY, QUOTA_LOCK, DENIED_LOCK, TRANSIENT }

/** Maps Google's status/HTTP code to what the app does: lock, fall back, or use the result. */
fun classifyGeoStatus(status: String?, httpCode: Int): GeoOutcome = when {
    httpCode == 429 -> GeoOutcome.QUOTA_LOCK
    httpCode in 500..599 -> GeoOutcome.TRANSIENT
    httpCode == 403 -> GeoOutcome.DENIED_LOCK
    status == "OK" -> GeoOutcome.OK
    status == "ZERO_RESULTS" -> GeoOutcome.EMPTY
    status == "OVER_QUERY_LIMIT" || status == "OVER_DAILY_LIMIT" -> GeoOutcome.QUOTA_LOCK
    status == "REQUEST_DENIED" -> GeoOutcome.DENIED_LOCK
    else -> GeoOutcome.TRANSIENT           // UNKNOWN_ERROR, INVALID_REQUEST, null → OSM this call only
}

/** Strip a Google API key from anything that might be logged. */
fun redactKey(text: String): String = text.replace(Regex("(key=)[^&\\s]+"), "$1<redacted>")

// ---------------------------------------------------------------- v2.7 (N43) group-header checkboxes

/** Per-tab INHERIT/ON/OFF over the global switch — the same shape as tasksAlertsOn & co. */
fun groupCheckFor(tab: Tab, s: AppSettings): Boolean {
    val per = when (tab) { Tab.TASKS -> s.tasksGroupCheck; Tab.LEARN -> s.learnGroupCheck; Tab.SHOP -> s.shopGroupCheck }
    return when (per) { "ON" -> true; "OFF" -> false; else -> s.groupHeaderCheck }
}

/** What a group header's checkbox shows for the items under it. */
enum class GroupCheckState { NONE, ALL, MIXED, LOCKED, EMPTY }

/**
 * The header state for [items] as seen in a view. In the Active view "done" means done-for-today
 * (recurring items that already completed); in the Done view every item is done by definition, so
 * the header reads ALL. Locked personal items (hidden behind the PIN) make the header LOCKED.
 */
fun groupState(items: List<Item>, isDoneView: Boolean, personalLocked: Boolean): GroupCheckState {
    val live = items.filter { it.deletedAt == null }
    if (live.isEmpty()) return GroupCheckState.EMPTY
    if (personalLocked && live.any { it.personal }) return GroupCheckState.LOCKED
    if (isDoneView) return GroupCheckState.ALL
    val done = live.count { it.done }
    return when (done) { 0 -> GroupCheckState.NONE; live.size -> GroupCheckState.ALL; else -> GroupCheckState.MIXED }
}

/** The ids a header tap acts on: Active → complete the not-yet-done; Done → restore the done. */
fun bulkTargets(items: List<Item>, isDoneView: Boolean): List<Long> =
    items.filter { it.deletedAt == null && (if (isDoneView) it.done else !it.done) }.map { it.id }

// ---------------------------------------------------------------- v2.7 (N42) scheduled alerts + (N40) reachability

enum class SchedSource { TASKS, LEARN, BUY, CALLS, SHOP_ARRIVAL, PLACE }
enum class SchedFlag { HIDDEN_TAB, PERSONAL, ALERTS_OFF, SHOP_DELETED }

/** One armed trigger. [at] null = a location trigger (no clock time). */
data class SchedRow(
    val at: Long?,
    val source: SchedSource,
    val reason: String,
    val title: String,
    val style: String,                  // subset of "NRA" — how it will sound
    val flags: List<SchedFlag>,
    val itemId: Long? = null, val callId: Long? = null, val shopId: Long? = null, val placeId: Long? = null,
    val kind: ComingUpKind? = null
) { val unreachable: Boolean get() = flags.isNotEmpty() }

/** N40: can the user open the card this item lives on? Tab hidden or personal-locked → no. */
fun itemReachable(item: Item, s: AppSettings, personalLocked: Boolean = true): Boolean {
    val tabOk = when (item.tab) { Tab.TASKS -> s.showTasks; Tab.LEARN -> s.showLearn; Tab.SHOP -> true }
    return tabOk && !(item.personal && personalLocked)
}

/** N40: the tabs that are hidden yet still carry live, alert-enabled items. */
fun orphanedAlertTabs(items: List<Item>, s: AppSettings): List<Tab> =
    items.filter { it.deletedAt == null && !it.done && it.dueAt != null }
        .map { it.tab }.distinct()
        .filter { t -> !itemReachable(Item(id = 0, tab = t, title = ""), s, personalLocked = false) && alertsEnabledFor(t, s) }

private fun sourceOf(tab: Tab) = when (tab) { Tab.TASKS -> SchedSource.TASKS; Tab.LEARN -> SchedSource.LEARN; Tab.SHOP -> SchedSource.BUY }

/**
 * v2.7 (N42): every armed trigger, sorted by time (location rows last). Pure so the page renders,
 * it does not decide. Item rows come from comingUpRows (the same truth the per-item box shows).
 */
fun scheduledRows(
    items: List<Item>, calls: List<CallReminder>, shops: List<Shop>, places: List<GeoPlace>,
    s: AppSettings, now: Long, personalLocked: Boolean = true
): List<SchedRow> {
    val out = ArrayList<SchedRow>()
    items.filter { it.deletedAt == null && !isMuted(it) }.forEach { i ->
        comingUpRows(i, now).forEach { r ->
            val flags = ArrayList<SchedFlag>()
            if (!itemReachable(i, s, personalLocked = false)) flags += SchedFlag.HIDDEN_TAB
            if (i.personal && personalLocked) flags += SchedFlag.PERSONAL
            if (!alertsEnabledFor(i.tab, s)) flags += SchedFlag.ALERTS_OFF
            out += SchedRow(r.at, sourceOf(i.tab), r.what, i.title, normalizeAlertTypes(i.alertType) ?: "N", flags, itemId = i.id, kind = r.kind)
        }
    }
    calls.filter { it.deletedAt == null && !it.done }.forEach { c ->
        val at = liveSnooze(c.snoozedUntil, now) ?: c.recurAt?.takeIf { it > now }
        if (at != null) {
            val flags = ArrayList<SchedFlag>()
            if (!s.showCalls) flags += SchedFlag.HIDDEN_TAB
            if (!alertsEnabledFor(null, s)) flags += SchedFlag.ALERTS_OFF
            out += SchedRow(at, SchedSource.CALLS, if (c.snoozedUntil != null) "snoozed" else "call-back reminder",
                c.name ?: c.number, normalizeAlertTypes(c.alertType) ?: "N", flags, callId = c.id)
        }
    }
    shops.filter { it.deletedAt == null && it.hasGeofence }.forEach { sh ->
        val flags = ArrayList<SchedFlag>()
        if (!s.shopArriveAlert || !alertsEnabledFor(Tab.SHOP, s)) flags += SchedFlag.ALERTS_OFF
        out += SchedRow(null, SchedSource.SHOP_ARRIVAL, "geofence armed · ${radiusLabel(sh.radius)}", sh.name,
            resolveShopArriveTypes(sh, s), flags, shopId = sh.id)
    }
    places.filter { it.enabled }.forEach { p ->
        out += SchedRow(null, SchedSource.PLACE,
            (if (p.trigger == TriggerType.LEAVE) "leave" else "arrive") + " fence · ${radiusLabel(p.radius)}" +
                (if (p.groupFilter.isNotEmpty()) " · " + p.groupFilter.joinToString(", ") else ""),
            p.name, "N", emptyList(), placeId = p.id)
    }
    return out.sortedWith(compareBy<SchedRow> { it.at == null }.thenBy { it.at ?: Long.MAX_VALUE }.thenBy { it.title.lowercase() })
}

/** Rows inside a horizon (ms from now); location rows (no time) always pass. */
fun schedWithin(rows: List<SchedRow>, now: Long, horizonMs: Long?): List<SchedRow> =
    if (horizonMs == null) rows else rows.filter { it.at == null || it.at <= now + horizonMs }

// ---------------------------------------------------------------- v2.8 (N44) delete from Scheduled alerts

/** What "Delete this alert" does for a row — the TRIGGER goes, the record stays. */
enum class DeleteKind { MUTE_ITEM, CANCEL_SNOOZE, CANCEL_RETURN, DELETE_CALL, SILENCE_SHOP, DISABLE_PLACE }

fun deleteActionFor(row: SchedRow): DeleteKind? = when (row.source) {
    SchedSource.TASKS, SchedSource.LEARN, SchedSource.BUY -> when (row.kind) {
        ComingUpKind.SNOOZE -> DeleteKind.CANCEL_SNOOZE
        ComingUpKind.RETURNS, ComingUpKind.LAPSE_RETURN -> DeleteKind.CANCEL_RETURN
        else -> DeleteKind.MUTE_ITEM
    }
    SchedSource.CALLS -> DeleteKind.DELETE_CALL
    SchedSource.SHOP_ARRIVAL -> DeleteKind.SILENCE_SHOP
    SchedSource.PLACE -> DeleteKind.DISABLE_PLACE
}

fun deleteKindLabel(k: DeleteKind): String = when (k) {
    DeleteKind.MUTE_ITEM -> "Mute this item (it stays, with its date)"
    DeleteKind.CANCEL_SNOOZE -> "Delete the snooze (falls back to the original due)"
    DeleteKind.CANCEL_RETURN -> "Cancel the return (it stays in Done; recurrence stops)"
    DeleteKind.DELETE_CALL -> "Delete the call reminder"
    DeleteKind.SILENCE_SHOP -> "Silence arrivals at this shop (geofence kept for Buy Now)"
    DeleteKind.DISABLE_PLACE -> "Disable this place"
}

/** The rows a full record deletion would remove — what the confirm sheet enumerates. */
fun rowsForRecord(rows: List<SchedRow>, row: SchedRow): List<SchedRow> = rows.filter {
    (row.itemId != null && it.itemId == row.itemId) ||
    (row.callId != null && it.callId == row.callId) ||
    (row.shopId != null && it.shopId == row.shopId) ||
    (row.placeId != null && it.placeId == row.placeId)
}

// ---------------------------------------------------------------- v2.9 (N47) in-app updates

const val UPDATE_FEED_URL = "https://raw.githubusercontent.com/krishnabhunia/remindly-android-app/main/releases/version.json"
const val UPDATE_RELEASES_URL = "https://github.com/krishnabhunia/remindly-android-app/releases/latest"
const val UPDATE_CHECK_INTERVAL_MS = 24L * 3600_000L

/** The feed the app reads: releases/version.json in the repo (published with every release). */
data class VersionFeed(val versionCode: Int, val versionName: String, val apk: String, val apkUrl: String,
                       val sha256: String, val sizeBytes: Long, val notes: String)

/** Strict parse — any missing or malformed field returns null (the updater then stays quiet). */
fun parseVersionFeed(json: String): VersionFeed? = runCatching {
    // Gson, not org.json: the same parser runs in unit tests (org.json is an Android stub there).
    val o = com.google.gson.JsonParser.parseString(json).asJsonObject
    fun str(k: String): String? = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim()
    val code = o.get("versionCode")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
    val name = str("versionName") ?: return null
    val apk = str("apk") ?: return null; val url = str("apkUrl") ?: return null
    val sha = (str("sha256") ?: return null).lowercase()
    if (code <= 0 || name.isEmpty() || !apk.endsWith(".apk") || !url.startsWith("https://") || sha.length != 64) return null
    VersionFeed(code, name, apk, url, sha, o.get("sizeBytes")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L, str("notes") ?: "")
}.getOrNull()

/** Newer only — never "downgrade", never re-offer the installed build. */
fun updateAvailable(feed: VersionFeed?, installedCode: Int): Boolean = feed != null && feed.versionCode > installedCode

fun updateCheckDue(lastCheckMs: Long, now: Long, autoCheck: Boolean): Boolean =
    autoCheck && now - lastCheckMs >= UPDATE_CHECK_INTERVAL_MS

fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// ---------------------------------------------------------------- v2.9 (N45) shopping-list helpers

/** Product suggestions while typing: prefix matches first, then contains; case-insensitive; max [limit]. */
fun productSuggestions(query: String, products: List<Product>, limit: Int = 6): List<Product> {
    val q = query.trim().lowercase(); if (q.length < 2) return emptyList()
    val live = products.filter { it.deletedAt == null }
    val starts = live.filter { it.name.lowercase().startsWith(q) }
    val contains = live.filter { !it.name.lowercase().startsWith(q) && (it.name.lowercase().contains(q) || (it.category ?: "").lowercase().contains(q)) }
    return (starts.sortedBy { it.name.lowercase() } + contains.sortedBy { it.name.lowercase() }).take(limit)
}

/** The share text for a group, honouring the two toggles. */
fun groupShareText(title: String, items: List<Item>, includeDone: Boolean, includeQty: Boolean): String {
    val rows = items.filter { it.deletedAt == null && (includeDone || !it.done) }
    val sb = StringBuilder("Shopping list – $title\n")
    rows.forEach { i ->
        val qty = if (includeQty && !i.quantity.isNullOrBlank()) " ${i.quantity}" else ""
        val shop = if (includeQty && !i.shopName.isNullOrBlank()) " · ${i.shopName}" else ""
        sb.append("- ${i.title}$qty$shop${if (i.done) " (✓)" else ""}\n")
    }
    return sb.toString().trimEnd()
}

fun groupIconFor(name: String, s: AppSettings): String? = s.groupIcons[name]?.takeIf { it.isNotBlank() }

// ---------------------------------------------------------------- v2.6.1 (N39) navigation contract

/** Where a navigation request wants to land. A gear page is an OVERLAY, never a destination. */
data class NavTarget(val taskTab: Int? = null, val shopTab: Int? = null, val mode: String? = null)

/** What a navigator must do with a request. */
enum class NavAction { NAV_NOW, CLOSE_GEAR_THEN_NAV, GUARD_POPUP, IGNORE }

/**
 * v2.6.1 (N39) — the ONE navigation decision, shared by every navigator (task tab, shop tab, mode
 * flip, gesture swipe). The bug this fixes: the per-tab settings pages are a full-screen overlay
 * inside the content Box; Task-mode nav has always closed that overlay first, but the Shop-mode
 * navigator set the tab underneath and left the overlay covering the screen.
 *
 * [gearOpen]        a per-tab settings page (Buy/Shops/Products/Tasks/... ⚙) is on screen
 * [alreadyThere]    the request would not move anything
 * [leavingSettings] the CURRENT page is a settings surface whose changes need the leave-guard
 * [hasDiff]        that settings surface has unsaved changes
 */
fun navAction(gearOpen: Boolean, alreadyThere: Boolean, leavingSettings: Boolean, hasDiff: Boolean): NavAction = when {
    gearOpen -> NavAction.CLOSE_GEAR_THEN_NAV      // the overlay must go first, even if the tab matches
    alreadyThere -> NavAction.IGNORE
    leavingSettings && hasDiff -> NavAction.GUARD_POPUP
    else -> NavAction.NAV_NOW
}

// ---------------------------------------------------------------- v2.05 (N37) arrival alerts

/** Keep only N/R/A, upper-cased, de-duplicated, in NRA order. Blank/garbage → null (= default). */
fun normalizeAlertTypes(raw: String?): String? {
    if (raw == null) return null                              // unset → follow the default
    if (raw.isNotEmpty() && raw.isBlank()) return null        // whitespace-only JSON → unset
    // "" (every chip deselected) is a DELIBERATE choice: silent. It must never fold back into
    // "follow the default" — that would re-enable sound the user had switched off.
    return "NRA".filter { it in raw.uppercase() }
}

/** The alert set a shop uses on arrival: its own if set, else the global default. */
fun resolveShopArriveTypes(shop: Shop, s: AppSettings): String =
    normalizeAlertTypes(shop.arriveTypes) ?: (normalizeAlertTypes(s.shopArriveTypes) ?: "N")

/** True when this shop may alert again (per-shop cooldown, minutes). */
fun arriveCooldownPassed(shop: Shop, s: AppSettings, now: Long): Boolean {
    val mins = if (s.shopArriveCooldownMin > 0) s.shopArriveCooldownMin else 0
    return now - shop.lastArriveFired >= mins * 60_000L
}

/** Human label for a set: "Notify", "Ring + Notify", "Alarm", "Silent". */
fun alertTypesLabel(types: String?): String {
    val t = normalizeAlertTypes(types) ?: return "Default"
    if (t.isEmpty()) return "Silent"
    val parts = buildList {
        if ('A' in t) add("Alarm")
        if ('R' in t && 'A' !in t) add("Ring")
        if ('N' in t) add("Notify")
    }
    return parts.joinToString(" + ")
}

// ---------------------------------------------------------------- v2.05 (N38) "Buy Now" view

/** The three Buy-list views. BUY_NOW exists only while a shop arrival has armed it. */
enum class ListView { ACTIVE, BUY_NOW, DONE }

/**
 * Buy Now = a VIEW FILTER over ACTIVE, scoped to the shop that last triggered a geofence:
 * live, not-done items whose shopId matches, or (legacy/free-text items) whose shopName matches
 * the shop's name. Done, deleted and other shops' items are never included.
 */
fun buyNowItems(items: List<Item>, shop: Shop?): List<Item> {
    if (shop == null || shop.deletedAt != null) return emptyList()
    val name = shop.name.trim()
    return items.filter {
        it.tab == Tab.SHOP && it.deletedAt == null && !it.done &&
            (it.shopId == shop.id || (it.shopId == null && (it.shopName ?: "").trim().equals(name, ignoreCase = true)))
    }
}

/** Segment label; long shop names are ellipsised so the 3-segment control never wraps. */
fun buyNowLabel(shopName: String?, max: Int = 14): String {
    val n = shopName?.trim().orEmpty()
    if (n.isEmpty()) return "Buy Now"
    val short = if (n.length <= max) n else n.take(max - 1).trimEnd() + "…"
    return "Buy Now · $short"
}

/** Buy Now auto-hides when its shop is gone (deleted or never existed). */
fun buyNowStillValid(shopId: Long?, shopsById: Map<Long, Shop>): Boolean =
    shopId != null && shopsById[shopId]?.deletedAt == null && shopsById[shopId] != null

/** Case-insensitive product search across name and category. Blank query matches everything. */
fun productMatches(p: Product, q: String): Boolean {
    val query = q.trim()
    if (query.isEmpty()) return true
    return p.name.contains(query, ignoreCase = true) ||
        (p.category ?: "").contains(query, ignoreCase = true)
}

/** Cheapest live link for a product: lowest lastUnitPrice > 0, else lowest lastPrice > 0. */
fun cheapestLink(links: List<ProductLink>, liveShopIds: Set<Long>): ProductLink? {
    val live = links.filter { it.deletedAt == null && it.shopId in liveShopIds }
    return live.filter { it.lastUnitPrice > 0.0 }.minByOrNull { it.lastUnitPrice }
        ?: live.filter { it.lastPrice > 0.0 }.minByOrNull { it.lastPrice }
}

/** Display label for a buy item's shop: registered shop via shopId wins, then shopName text. */
fun buyShopLabel(item: Item, shopsById: Map<Long, Shop>): String? {
    item.shopId?.let { id -> shopsById[id]?.takeIf { it.deletedAt == null }?.let { return it.name } }
    return item.shopName?.trim()?.takeIf { it.isNotBlank() }
}

// -------- v1.90 mode-aware nav (pure, unit-tested). Task mode hides index 1 (Shop). --------

fun modeNormalized(m: String?): String = if (m == "SHOP") "SHOP" else "TASK"

fun taskModeTabVisible(s: AppSettings, i: Int): Boolean = i != 1 && isTabVisible(s, i)
fun firstVisibleTaskTab(s: AppSettings): Int = (0..4).firstOrNull { taskModeTabVisible(s, it) } ?: 4
fun nextVisibleTaskTab(s: AppSettings, from: Int, forward: Boolean): Int {
    val range = if (forward) (from + 1..4) else (from - 1 downTo 0)
    return range.firstOrNull { taskModeTabVisible(s, it) } ?: from
}

/** v2.04 (N36): a cold start always reopens the last used mode. */
fun resolveStartMode(lastMode: String?): String = modeNormalized(lastMode)

data class CallReminder(
    val id: Long,
    val number: String,
    val name: String? = null,
    val alertType: String = "N",   // v1.68: this call's Reminder Type (A/R/N)
    val snoozedUntil: Long? = null,   // v1.80 (Q17)
    // v1.79 (N4): optional WhatsApp message. Non-null means the alert offers "Send WhatsApp".
    val message: String? = null,
    // v1.67: structured contact enrichment (device contacts; nullable, JSON-safe)
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val source: CallSource,
    val createdAt: Long = System.currentTimeMillis(),
    val lastMissedAt: Long? = null,
    val note: String? = null,               // v1.8 small note on the reminder
    val updatedAt: Long = 0L,               // v1.9 merge stamp
    val deletedAt: Long? = null,            // v1.11 soft-delete
    // v1.14: recurring manual call reminders (auto-detected missed calls stay one-shot)
    val repeatMode: String = "OFF",
    val repeatDays: List<Int> = emptyList(),
    val repeatN: Int = 1,
    val repeatUnit: String = "D",
    val repeatOrd: Int = 1,
    val repeatDow: Int = 1,
    val repeatOrdList: List<Int> = emptyList(),
    val recurAt: Long? = null,              // next occurrence date-time
    val label: String? = null,              // v1.15 free-text label
    val savedGroups: List<String> = emptyList(), // v1.26 item 5: phonebook groups chosen when saving an unknown caller
    val repeatCount: Int? = null,           // v1.22 item 7: end after N occurrences (>= 2), null = never ends
    val repeatDone: Int = 0,                // completions tallied against repeatCount
    val calEventId: Long? = null,           // v1.19 calendar sync mapping
    val missedCount: Int = 0,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val clearedNote: String? = null,
    val nagAt: Long? = null,
    val nagFired: Boolean = false,
    // v1.58: per-reminder ONE-OFF alert date-times ("D<epochMs>"), on top of the global call rules.
) {
    val display: String get() = callDisplayOf(firstName, lastName, name, number)
}

data class AppSettings(
    val ver: Int = 43,
    // v2.11 (N48): Buy tab LISTS FIRST. The list records ride the settings doc (already synced and
    // backed up); Sync merges them per id, latest-wins. Buy-only settings (no other tab has lists).
    val shopLists: List<ShopList> = emptyList(),
    val buyOpensOn: String = "LISTS",           // LISTS | CLASSIC (today's flat view)
    val buyReopenLast: Boolean = false,         // skip the Lists screen and reopen the last list
    val buyShowUnsorted: Boolean = true,        // the dashed "Unsorted" card (auto-hides when empty)
    val buyCardTotal: Boolean = true,           // "≈ ₹" estimate on each list card
    val buyDupWarn: Boolean = true,             // warn when an item already sits in another list
    val buyListSort: String = "RECENT",         // RECENT | AZ | CUSTOM
    val buyInnerSort: String = "SHOP",          // inside a list: SHOP | CATEGORY | PRIORITY | DATE | NONE
    val shopDefaultListId: Long? = null,        // share-in / widget default (replaces shopDefaultGroup)
    // v2.11 (N48): list sharing — header icons + the "List_Name:-" text format.
    val shareWaIcon: Boolean = true,            // the one-tap WhatsApp icon in a list header
    val shareWaApp: String = "WHATSAPP",        // WHATSAPP | BUSINESS (used when both are installed)
    val shareUrgentTag: Boolean = true,         // " - Urgent" on priority-Urgent lines
    val shareBoughtTag: Boolean = true,         // " - Bought" on bought lines
    val shareHeadingSuffix: String = ":-",      // text after the list name
    // v2.9 (N47): in-app updates from the GitHub repo's releases/version.json feed.
    val updateAutoCheck: Boolean = true,
    val updateWifiOnly: Boolean = true,
    // v2.9 (N45): shopping-list features — group icons (emoji per group name), default group for
    // quick add, voice input, product suggestions, share defaults.
    val groupIcons: Map<String, String> = emptyMap(),
    val shopDefaultGroup: String = "",
    val shopVoiceAdd: Boolean = true,
    val shopSuggest: Boolean = true,
    val shareIncludeDone: Boolean = false,
    val shareIncludeQty: Boolean = true,
    // v2.7 (N43): checkbox on every group header — global switch + per-tab INHERIT/ON/OFF.
    val groupHeaderCheck: Boolean = true,
    val tasksGroupCheck: String = "INHERIT",
    val learnGroupCheck: String = "INHERIT",
    val shopGroupCheck: String = "INHERIT",
    // v2.05 (N37): default arrival alert for shops set to "Default", and the per-shop re-alert
    // cooldown. Types use the item alert vocabulary: N(otify) R(ing) A(larm), any combination.
    val shopArriveTypes: String = "N",
    val shopArriveCooldownMin: Int = 10,
    // v2.02 (N33): Google Maps as default provider with OSM fallback (Krishna, 17-Aug-2026).
    val mapProvider: String = "GOOGLE",     // GOOGLE / OSM (manual choice; the app may still fall back)
    val geoLimit: Int = 56000,              // app-side monthly cap on Google geocoding calls (≤ free cap)
    val indiaBilling: Boolean = true,       // 7× free tier for India-billed accounts
    // v1.90 two-UX-mode system; v2.00 (N31): the app is dual-mode ONLY — the v1.90 layoutMode
    // field (DUAL/CLASSIC) is DELETED (Krishna, 14-Aug-2026: Classic purged, never re-added).
    // v2.04 (N36): startMode DELETED — the app always reopens in the last used mode (☰ drawer).
    // v1.90 shop-mode behaviour toggles (each is the feature's global control).
    val shopCheckoutCalc: Boolean = true,   // checkout calculator dialog on Shop complete
    val shopCheapestHint: Boolean = true,   // cheapest-shop suggestion row in the Shop editor
    val shopArriveAlert: Boolean = true,    // geofence "Arrived at <shop>" notification
    val shopNewRadius: Float = 150f,        // default geofence radius pre-filled on NEW shops
    val citySeedDone: Boolean = false,      // one-time v1.90 migration: Cities seeded from Shop.area
    // v1.41 Feature 1: show/hide each tab (default all shown). Settings tab is always shown.
    // v2.00 (N31): showShop DELETED — Shop lives in Shop mode, never in the Task-mode bar.
    val showTasks: Boolean = true,
    val showLearn: Boolean = true,
    val showCalls: Boolean = true,
    // v1.41 Feature 3: one global switch to show/hide the Delete action on Done items (all tabs).
    val showDeleteOnDone: Boolean = true,
    // v1.44 UI 1: item-card element visibility. Name is always shown; these are the GLOBAL defaults,
    // and tabCardOv holds per-tab ON/OFF overrides (absent = inherit the global default).
    val cardShowPriority: Boolean = true,
    val cardShowDateTime: Boolean = true,
    val cardShowCheckbox: Boolean = true,
    val cardShowRepeat: Boolean = true,
    // v1.80 (N5): the Reminder Type chip. OFF by default — every item is Notify unless changed,
    // so ON would stamp a chip on nearly every card. Style: "ICON" | "TEXT".
    val cardShowAlertType: Boolean = false,
    val cardAlertTypeStyle: String = "ICON",
    // v1.80 (N5): chip colours are user-settable, globally and per tab. Defaults match Q16.
    val alertColorA: String = "#D32F2F",
    val alertColorR: String = "#EF6C00",
    val tabCardOv: Map<String, String> = emptyMap(),
    // v1.45 Feature 4c: read-only calendar import window (days) for the "From Calendar" view.
    val calendarReadDays: Int = 7,
    // v1.48: read window can be expressed in days (3/7/14/30/60) or months (3/6/12/18/24).
    val calendarReadUnit: String = "DAYS",     // DAYS | MONTHS
    // v1.48: read window can be days (3/7/14/30/60) or months (3..24).
    val calendarReadMonths: Int = 6,
    val moveDelaySec: Int = 5,
    val fontScale: Float = 1.0f,
    val defaultRadius: Float = 250f,
    val lastSyncAt: Long = 0L,
    // v1.3 gestures
    val navSwipeRightNext: Boolean = false,    // v1.5 default: slide right = previous tab
    val gestureCardSwipe: Boolean = true,      // swipe cards to move items
    val gesturePageSwipe: Boolean = true,      // swipe blank areas to flip Active/Done
    val gestureNavSwipe: Boolean = true,       // swipe the bottom bar to change tabs
    // v1.4
    val cardSwipeRightDone: Boolean = true,    // card direction: right completes
    val pageSwipeRightDone: Boolean = false,   // v1.5 default: right shows Active
    val density: String = "COMFY",             // legacy (pre-v1.7) — kept for old backups
    val densityPct: Float = 0.8f,              // v1.7: one versatile number; 0.80 == old Comfortable
    val shopGroupByGroup: Boolean = false,
    val learnGroupByTopic: Boolean = false,
    val fsNav: Float = 1f,
    val fsCardTitle: Float = 1f,
    val fsCardDetail: Float = 1f,
    val fsGroupHeader: Float = 1f,
    val fsScreenHeader: Float = 1f,
    val fsButtons: Float = 1f,
    // v1.5
    val activeSwipeAction: String = "MOVE",    // MOVE (to Done) or DELETE
    val doneSwipeAction: String = "MOVE",      // MOVE (back to Active) or DELETE
    val deleteStyle: String = "UNDO",          // UNDO chip or CONFIRM dialog
    // v1.32: reverse-swipe delete. The move direction always moves; the OPPOSITE
    // (previously dead) direction deletes when this is on. Default off; per-tab override key "RevSwipeDelete".
    val revSwipeDelete: Boolean = false,
    // v1.35: cross-device sync (Firestore). Opt-in; off until the user turns it on AND is signed in.
    val cloudSync: Boolean = false,
    // v1.35: settings merge stamp — bumped on every settings change so the settings doc LWW-merges.
    val settingsUpdatedAt: Long = 0L,
    // v1.6
    val tasksGroupByGroup: Boolean = false,
    val tasksGroups: List<String> = emptyList(),
    val shopGroups: List<String> = emptyList(),
    val learnTopics: List<String> = emptyList(),
    val groupsSeeded: Boolean = false,
    val defaultDueMinutes: Int = 1080,         // 18:00 — date-only items ring here
    val callReminderMinutes: Int = 1080,       // 18:00 — missed-call ladder step 2
    // v1.8 per-tab add style: false = quick bar, true = full editor
    // v1.61: OBSOLETE — the unified bar (morphing +/✓) replaced the per-tab add style;
    // fields kept only to avoid schema churn.
    val tasksAddFull: Boolean = false,
    val shopAddFull: Boolean = false,
    val learnAddFull: Boolean = false,
    // v1.8 per-tab new-item due defaults
    val tasksNewDueMode: String = "TOMORROW",  // OFF TODAY TOMORROW IN_N
    val tasksNewDueDays: Int = 2,
    val tasksNewDueMinutes: Int = 600,
    val shopNewDueMode: String = "TOMORROW",
    val shopNewDueDays: Int = 2,
    val shopNewDueMinutes: Int = 600,
    val learnNewDueMode: String = "TOMORROW",
    val learnNewDueDays: Int = 2,
    val learnNewDueMinutes: Int = 600,
    // v1.8 done auto-clear (days; 0 = off)
    val tasksDoneClearDays: Int = 0,
    val shopDoneClearDays: Int = 0,
    val learnDoneClearDays: Int = 0,
    val callsDoneClearDays: Int = 0,
    // v1.8 misc
    val theme: String = "SYSTEM",              // SYSTEM LIGHT DARK
    val badges: Boolean = true,
    // v1.81 (Q18): 90 restores the behaviour v1.80 silently changed. The unused second snooze
    // duration is purged — one snooze, as decided.
    val snoozeM1: Int = 90,
    val snoozeFixed90: Boolean = false,   // v1.81 (Q18) one-time migration flag
    // v1.9 alert matrix: SILENT / NOTIFICATION / ALARM (+ INHERIT below global)
    // v1.9 per-tab Done delay (migrated from moveDelaySec)
    val tasksMoveDelaySec: Int = 3,
    val shopMoveDelaySec: Int = 3,
    val learnMoveDelaySec: Int = 3,
    val callsMoveDelaySec: Int = 3,
    // v1.9 per-tab gestures (bar-slide stays global)
    val tCardSwipe: Boolean = true, val tCardRightDone: Boolean = true,
    val tPageSwipe: Boolean = true, val tPageRightDone: Boolean = false,
    val tActiveAction: String = "MOVE", val tDoneAction: String = "MOVE", val tDeleteStyle: String = "UNDO",
    val sCardSwipe: Boolean = true, val sCardRightDone: Boolean = true,
    val sPageSwipe: Boolean = true, val sPageRightDone: Boolean = false,
    val sActiveAction: String = "MOVE", val sDoneAction: String = "MOVE", val sDeleteStyle: String = "UNDO",
    val lCardSwipe: Boolean = true, val lCardRightDone: Boolean = true,
    val lPageSwipe: Boolean = true, val lPageRightDone: Boolean = false,
    val lActiveAction: String = "MOVE", val lDoneAction: String = "MOVE", val lDeleteStyle: String = "UNDO",
    val cCardSwipe: Boolean = true, val cCardRightDone: Boolean = true,
    val cPageSwipe: Boolean = true, val cPageRightDone: Boolean = false,
    val cActiveAction: String = "MOVE", val cDoneAction: String = "MOVE", val cDeleteStyle: String = "UNDO",
    // v1.9 backup bookkeeping
    val lastDataBackupAt: Long = 0L,
    // v1.10 inherit-from-global: global defaults + per-tab overrides
    val globalDoneClearDays: Int = 0,
    val globalNewDueMode: String = "TOMORROW",
    // v1.47 Feature 2c: whether a NEW item's due date carries a clock time. false = date-only
    // (the item rings at "Default due time"). Per-tab keys are INHERIT/ON/OFF.
    val globalNewDueTimed: Boolean = true,
    val tasksNewDueTimed: String = "INHERIT",
    val shopNewDueTimed: String = "INHERIT",
    val learnNewDueTimed: String = "INHERIT",
    // v1.86 (N25): which schedule kind a NEW item opens on. Global + per-tab Inherit/override.
    // Values: NONE / ONCE / REPEAT (per-tab also INHERIT). Shop cannot be NONE (D1) — the
    // resolver clamps it to ONCE by design.
    val schedDefault: String = "ONCE",
    val tasksSchedDefault: String = "INHERIT",
    val shopSchedDefault: String = "INHERIT",
    val learnSchedDefault: String = "INHERIT",
    // v1.87 (N17): list sharing — global master + per-tab (standing rule). Tasks + Shop only.
    val shareOn: Boolean = true,
    val tasksShareOn: String = "INHERIT",
    val shopShareOn: String = "INHERIT",
    val globalNewDueDays: Int = 3,
    val globalNewDueMinutes: Int = 600,
    // v1.10 per-tab gesture overrides; key "{t|s|l|c}{CardSwipe|CardRightDone|PageSwipe|PageRightDone|ActiveAction|DoneAction|DeleteStyle}",
    // value ON/OFF or MOVE/DELETE/UNDO/CONFIRM. ABSENT key = Inherit from the global gesture fields.
    val tabGestureOv: Map<String, String> = emptyMap(),
    // v1.11
    val globalOverdueNagHours: Int = 0,     // 0 = off
    val tasksOverdueNagHours: Int = -1,     // -1 = inherit
    val shopOverdueNagHours: Int = -1,
    val learnOverdueNagHours: Int = -1,
    val callsOverdueNagHours: Int = -1,
    val tasksSort: String = "DATE",         // DATE / GROUP / PRIORITY
    val shopSort: String = "DATE",
    val learnSort: String = "DATE",
    // v1.15
    val shopBudgetMonthly: Double = 0.0,    // 0 = off
    val shopGroupOrder: List<String> = emptyList(),
    val shopUntracked: Map<String, Double> = emptyMap(), // "yyyy-MM" → untracked ₹
    val calendarSync: Boolean = false,
    // v1.19
    val calendarTargetId: Long = 0L,        // legacy single pick; migrated to account+ids in v1.49
    // v1.49: two-variable calendar selection. calendarIds empty = ALL calendars in that account.
    val calendarAccount: String? = null,
    val calendarIds: Set<Long> = emptySet(),
    // v1.50: "Select None" must be distinguishable from "empty = ALL". Without this flag,
    // deselecting everything would silently select every calendar.
    val calendarNone: Boolean = false,
    // v1.52: a declined call is REJECTED_TYPE, not MISSED_TYPE, so it is ignored by default.
    val callIncludeRejected: Boolean = false,
    // v1.56 phase 1 of the alerts overhaul:
    val alertsEnabled: Boolean = true,                 // 1.1 global master, default ON
    val tasksAlertsOn: String = "INHERIT",             // 1.1 per-tab: INHERIT/ON/OFF
    val shopAlertsOn: String = "INHERIT",
    val learnAlertsOn: String = "INHERIT",
    val callsAlertsOn: String = "INHERIT",
    val alarmRingSeconds: Int = 7,                     // 1.4 sound auto-stop; 0 = until dismissed
    // v1.57 phase 2a:
    val ringRingSeconds: Int = 7,                      // RING channel duration; 0 = until dismissed
    val calendarCloudIds: Set<String> = emptySet(),    // Plan B narrowing; empty = ALL cloud calendars
    // v1.58 1.3: per-tab DEFAULT extra-alert rules (additive; "" = none = today's behaviour).
    val tasksAlertRules: String = "",
    val shopAlertRules: String = "",
    val learnAlertRules: String = "",
    val calSyncTasks: Boolean = false,
    val calSyncShop: Boolean = false,
    val calSyncLearn: Boolean = false,
    val calSyncCalls: Boolean = false,      // opt-in
    val callCardShowNumber: Boolean = false,
    val callCardShowMissedAt: Boolean = true,
    val callCardShowMissedCount: Boolean = false,
    val callCardShowNote: Boolean = false,
    val callCardShowLabel: Boolean = false,
    val callCardShowNext: Boolean = false,
    // ---- v1.22 ----
    // item 6: one global clock format for the whole app.
    val timeFormat: String = "PHONE",          // PHONE | H12 | H24
    // v1.79 (N4): wa.me needs full international format. Was hardcoded to 91 in waNumber().
    val defaultCountryCode: String = "91",
    // v1.71 (Q9): the Medium tag is off by default — restored global switch + per-tab override.
    val showMediumTag: Boolean = false,
    val tasksShowMediumTag: Int = -1,          // -1 Inherit · 1 Show · 0 Hide
    val shopShowMediumTag: Int = -1,
    val learnShowMediumTag: Int = -1,
    // item 4: delete allowed inside each tab's Done list.
    // item 8: adding defaults for manual call reminders.
    val callsNewDueMode: String = "TODAY",     // OFF | TODAY | TOMORROW | NDAYS
    val callsNewDueDays: Int = 3,
    // item 13: swipe physics — distance share of the card, flick speed, haptic tick.
    val swipeDistancePct: Int = 35,            // 15..70
    val swipeVelocityDp: Int = 125,            // 0 = off (distance only)
    val swipeHaptic: Boolean = true
)

data class BackupBlob(
    val version: Int = 2,
    val app: String = "Remindly",
    val exportedAt: Long = System.currentTimeMillis(),
    val items: List<Item> = emptyList(),
    val places: List<GeoPlace> = emptyList(),
    val calls: List<CallReminder>? = emptyList(),
    val settings: AppSettings = AppSettings()
)

// ---------------------------------------------------------------- ids

object Ids {
    private val last = AtomicLong(0)

    // v1.34 (sync groundwork): a per-install device tag (1..4095) is packed into the HIGH bits
    // of every new id, so two devices on the same account can never mint the same id — the
    // precondition for multi-device merge. 0 = untagged (all legacy ids, and the default until
    // initTag() runs at app startup). Legacy plain-millis ids sit in the tag-0 range and never
    // collide with tagged ids.
    private const val TIME_BITS = 44
    private const val TIME_MASK = (1L shl TIME_BITS) - 1

    @Volatile
    var deviceTag: Int = 0
        private set

    fun initTag(tag: Int) { deviceTag = tag.coerceIn(0, 0xFFF) }

    /** Pure composer (unit-tested): tag in high bits, millis in low bits, monotonic within a device. */
    fun composeId(now: Long, prev: Long, tag: Int): Long {
        val base = ((tag.toLong() and 0xFFF) shl TIME_BITS) or (now and TIME_MASK)
        return if (base > prev) base else prev + 1
    }

    /** Device tag an id was minted under (0 = legacy/untagged). */
    fun tagOf(id: Long): Int = ((id ushr TIME_BITS) and 0xFFF).toInt()

    fun next(): Long {
        while (true) {
            val prev = last.get()
            val candidate = composeId(System.currentTimeMillis(), prev, deviceTag)
            if (last.compareAndSet(prev, candidate)) return candidate
        }
    }
}

// ---------------------------------------------------------------- date helpers

private val monthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
private val dayFmt = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH)
private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val dateTimeFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)
private val timeFmt = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)
private val dayTimeFmt = DateTimeFormatter.ofPattern("EEE, dd MMM · hh:mm a", Locale.ENGLISH)

fun Long.toLocalDateTime(): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

fun Long.toLocalDate(): LocalDate = toLocalDateTime().toLocalDate()

fun formatMonth(ms: Long): String = YearMonth.from(ms.toLocalDate()).format(monthFmt)
fun minuteOfDay(ms: Long): Int {
    val t = ms.toLocalDateTime()
    return t.hour * 60 + t.minute
}

fun formatDay(ms: Long): String = ms.toLocalDate().format(dayFmt)
fun formatDate(ms: Long): String = ms.toLocalDate().format(dateFmt)
/** v1.24 item 4: was hardcoded to 12-hour — now follows the global Time Format like the rest. */
fun formatDateTime(ms: Long): String =
    runCatching { ms.toLocalDate().format(dateFmt) + ", " + formatTime(ms) }
        .getOrElse { ms.toLocalDateTime().format(dateTimeFmt) }
/** v1.22 item 6: one global clock format for every time the app prints. */
@Volatile var TIME_FORMAT: String = "PHONE"

private val fmt12 = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
private val fmt24 = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

fun timeFormatterFor(setting: String, phoneIs24: Boolean): java.time.format.DateTimeFormatter = when (setting) {
    "H12" -> fmt12
    "H24" -> fmt24
    else -> if (phoneIs24) fmt24 else fmt12
}

@Volatile var PHONE_IS_24H: Boolean = false

fun formatTime(ms: Long): String =
    runCatching { ms.toLocalDateTime().format(timeFormatterFor(TIME_FORMAT, PHONE_IS_24H)) }
        .getOrElse { ms.toLocalDateTime().format(timeFmt) }
fun formatDayTime(ms: Long): String = formatDay(ms) + " · " + formatTime(ms)

/** Combine a DatePicker UTC date-millis with hour/minute into local epoch millis. */
fun combineDateTime(utcDateMillis: Long, hour: Int, minute: Int): Long {
    val date = Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Local date at a given hour, for expiry alerts (9 AM on the expiry day). */
fun atHourOfDay(utcDateMillis: Long, hour: Int): Long {
    val date = Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Local epoch millis back to the UTC date-millis a DatePicker expects. */
fun toUtcDateMillis(localMillis: Long): Long =
    localMillis.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** v1.9 per-tab accessors (Calls passes tab = null). */
data class GestureSet(
    val cardSwipe: Boolean, val cardRightDone: Boolean,
    val pageSwipe: Boolean, val pageRightDone: Boolean,
    val activeAction: String, val doneAction: String, val deleteStyle: String,
    val revSwipeDelete: Boolean
)

/** v1.32: what a card swipe of a given sign resolves to. Pure — unit-tested. */
enum class SwipeOutcome { MOVE, DELETE, NONE }

/**
 * The move direction (Active→Done / Done→Active) is [moveToRight]; the opposite side deletes.
 * A swipe only fires if its own gesture is enabled: move needs [moveEnabled], delete needs [deleteEnabled].
 * offsetSign: +1 = swiped right, -1 = swiped left, 0 = no travel.
 */
fun swipeOutcome(
    offsetSign: Int,
    moveToRight: Boolean,
    moveEnabled: Boolean,
    deleteEnabled: Boolean
): SwipeOutcome {
    if (offsetSign == 0) return SwipeOutcome.NONE
    val isRight = offsetSign > 0
    val isMoveSide = (isRight == moveToRight)
    return when {
        isMoveSide && moveEnabled -> SwipeOutcome.MOVE
        !isMoveSide && deleteEnabled -> SwipeOutcome.DELETE
        else -> SwipeOutcome.NONE
    }
}

/** v1.45 Feature 4c: a read-only event read from the device calendar (never edited by Remindly). */
data class CalEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean
)

/**
 * v1.45 Feature 4c: group read-only calendar events by calendar day (ascending), events within a
 * day sorted by start. Pure so the day-bucketing is unit-testable.
 */
fun calEventsByDay(events: List<CalEvent>): List<Pair<Long, List<CalEvent>>> =
    events.groupBy { startOfDayMs(it.start) }.entries
        .sortedBy { it.key }
        .map { it.key to it.value.sortedBy { e -> e.start } }

/** v1.10: tab prefix for override keys ("c" = Calls). */
fun tabPrefix(tab: Tab?): String = when (tab) {
    Tab.SHOP -> "s"; Tab.LEARN -> "l"; Tab.TASKS -> "t"; else -> "c"
}

/** v1.44 UI 1: which item-card elements to render for a tab (name is always shown). */
data class CardFields(val priority: Boolean, val dateTime: Boolean, val checkbox: Boolean, val repeat: Boolean, val alertType: Boolean)

/** v1.44 inherit-aware card fields: per-tab override (ON/OFF) wins; absent key inherits the global default. */
fun cardFieldsFor(s: AppSettings, tab: Tab?): CardFields {
    val p = tabPrefix(tab)
    fun b(key: String, g: Boolean): Boolean = when (s.tabCardOv[p + key]) {
        "ON" -> true; "OFF" -> false; else -> g
    }
    return CardFields(
        priority = b("CardPriority", s.cardShowPriority),
        dateTime = b("CardDateTime", s.cardShowDateTime),
        checkbox = b("CardCheckbox", s.cardShowCheckbox),
        repeat = b("CardRepeat", s.cardShowRepeat),
        // v1.80 (N5): Calls is OUT OF SCOPE — a null tab returns false no matter the global, so
        // switching the global on can never leak a chip onto call cards.
        alertType = if (tab == null) false else b("CardAlertType", s.cardShowAlertType)
    )
}

/** v1.80 (N5): chip style, same inherit-aware map. "ICON" | "TEXT", absent = global. */
fun cardAlertStyleFor(s: AppSettings, tab: Tab?): String =
    when (s.tabCardOv[tabPrefix(tab) + "CardAlertStyle"]) {
        "ICON" -> "ICON"; "TEXT" -> "TEXT"; else -> s.cardAlertTypeStyle
    }

/** v1.80 (N5): chip colour, per-tab override then global. Blank/garbage falls back to the global. */
fun cardAlertColorFor(s: AppSettings, tab: Tab?, letter: String): String {
    val key = tabPrefix(tab) + "CardAlertColor" + letter
    val ov = s.tabCardOv[key]?.takeIf { it.startsWith("#") && (it.length == 7) }
    return ov ?: if (letter == "A") s.alertColorA else s.alertColorR
}

/** v1.80 (N5): Notify never shows a chip — it is what every item already is. */
fun showAlertChip(letter: String): Boolean = letter == "A" || letter == "R"

/**
 * v1.82 (N6/Q19): THE single source for every deferral target. Pure, so tests call THIS rather
 * than a copy of it — the mirrored-test defect N6 exists to end.
 *
 * Q19: three separate paths each computed their own duration, two of them hardcoded to an hour and
 * one with the number AND its label typed by hand. A literal duration anywhere in a snooze path is
 * now a build-gate failure; everything routes here.
 */
fun snoozeTargetMs(s: AppSettings, now: Long = System.currentTimeMillis()): Long =
    now + snoozeMinutes(s) * 60_000L

/** The configured snooze length, coerced to a sane band so a corrupt value cannot fire instantly. */
fun snoozeMinutes(s: AppSettings): Int = s.snoozeM1.coerceIn(1, 24 * 60)

/** v1.80 (Q17): a snooze only counts while it is still in the future. */
fun liveSnooze(at: Long?, now: Long = System.currentTimeMillis()): Long? =
    at?.takeIf { it > now }

/**
 * v1.84 (N13): THE quiet transition, single source for both quiet gestures (the Quiet button and
 * double-tap-outside). Quiet means "this reminder becomes a Notification" — so the item RECORD
 * says so, not just a PendingIntent type the editor cannot see. With alertType persisted, the
 * Reminder Type chip and "Coming up" show the truth with zero UI changes, the demote survives a
 * reboot, and Firestore carries it to iOS for free. Permanent by Krishna's instruction; a
 * repeating item notifies on every future occurrence until the type is changed back by hand.
 */
fun quietDemote(i: Item, fireAt: Long): Item = i.copy(alertType = "N", snoozedUntil = fireAt)

/**
 * v1.84 (N14): the "Coming up" rows as PURE data, so the labels are testable per N6 — the
 * composable renders exactly what this returns. Extracted verbatim from the v1.80 box logic.
 * NOTE: the geofence "At <place>" row is NOT included — Item has no per-item place field. Shop
 * geofencing binds places to GROUPS (AppSettings.places), not to individual items, so there is
 * nothing item-level to display. Revisit only if a per-item place is ever added.
 */
enum class ComingUpKind { SNOOZE, DUE, RETURNS, LAPSE_RETURN }
data class ComingUpRow(val at: Long?, val what: String, val kind: ComingUpKind) {
    val cancellable: Boolean get() = kind == ComingUpKind.SNOOZE
}
fun comingUpRows(i: Item, now: Long): List<ComingUpRow> {
    val rows = mutableListOf<ComingUpRow>()
    val snooze = liveSnooze(i.snoozedUntil, now)
    if (snooze != null)
        rows.add(ComingUpRow(snooze, "Snoozed \u00b7 " + alertTypeLabel(i.alertType), ComingUpKind.SNOOZE))
    else if (!i.done) i.dueAt?.takeIf { it > now }?.let {
        rows.add(ComingUpRow(it, "Due \u00b7 " + alertTypeLabel(i.alertType), ComingUpKind.DUE))
    }
    if (i.done && i.repeatMode != "OFF") i.dueAt?.let { rows.add(ComingUpRow(it, "Returns", ComingUpKind.RETURNS)) }
    i.returnAt?.takeIf { it > now }?.let { rows.add(ComingUpRow(it, "Back on your list", ComingUpKind.LAPSE_RETURN)) }
    return rows
}

/**
 * v1.85 (N16): the base an editor Save copies from — the LIVE record, never the open-time
 * snapshot. Save used to write a full object built from the snapshot captured when the sheet
 * opened, clobbering every store-side change made meanwhile: the cancelled snooze it resurrected
 * (the report), the fresh snooze it killed (the mirror), done set from a notification, a
 * mid-edit delete. Generic — serves Item and CallReminder alike. Fields the editor does not
 * own now flow from here.
 */
fun <T> editorSaveBase(snapshot: T?, live: T?): T? = snapshot?.let { live ?: it }

/** v1.85 (N16 companion 2): which store-owned Item fields a stale snapshot would have clobbered —
 *  logged at save so silent merges are visible in Error Logs. Pure, so the list is testable. */
fun staleItemFields(snap: Item, live: Item): List<String> = buildList {
    if (snap.snoozedUntil != live.snoozedUntil) add("snoozedUntil")
    if (snap.done != live.done) add("done")
    if (snap.doneAt != live.doneAt) add("doneAt")
    if (snap.deletedAt != live.deletedAt) add("deletedAt")
    if (snap.returnAt != live.returnAt) add("returnAt")
    if (snap.spacedStep != live.spacedStep) add("spacedStep")
}

/** v1.85 (N16 part B): same, for CallReminder. */
fun staleCallFields(snap: CallReminder, live: CallReminder): List<String> = buildList {
    if (snap.snoozedUntil != live.snoozedUntil) add("snoozedUntil")
    if (snap.done != live.done) add("done")
    if (snap.doneAt != live.doneAt) add("doneAt")
    if (snap.clearedNote != live.clearedNote) add("clearedNote")
    if (snap.deletedAt != live.deletedAt) add("deletedAt")
}

/**
 * v1.48 STANDING RULE: every geofence radius is DISCRETE — 50 m steps, 50 m .. 2 km.
 * Any stored value (including legacy ones outside the range) snaps into the legal set.
 */
/**
 * v2.02 (N33 C3, standing rule for ALL Krishna's apps, 17-Aug-2026): geofence radius is ALWAYS one
 * of exactly these 13 stops — never continuous, never a uniform step. Stored values off the scale
 * snap to the nearest stop (ties round down); garbage (NaN/≤0/∞) snaps to the smallest stop.
 */
val RADIUS_STOPS: List<Float> = listOf(50f, 100f, 150f, 200f, 250f, 300f, 400f, 500f, 750f, 1000f, 1500f, 2000f, 3000f)

fun snapRadius(v: Float): Float {
    if (!v.isFinite() || v <= 0f) return RADIUS_STOPS.first()
    var best = RADIUS_STOPS.first(); var bestD = Float.MAX_VALUE
    for (stop in RADIUS_STOPS) {
        val d = kotlin.math.abs(stop - v)
        if (d < bestD) { best = stop; bestD = d }      // strict < keeps the LOWER stop on a tie
    }
    return best
}

/** Kept for callers that only need the range ends. Sliders are gone — pickers use RADIUS_STOPS. */
const val RADIUS_MIN = 50f
const val RADIUS_MAX = 3000f

/** v1.48 human label for a radius: metres below 1 km, else km ("1.5 km", "2 km"). */
fun radiusLabel(v: Float): String {
    val r = if (v.isFinite() && v > 0f) v else RADIUS_STOPS.first()   // label what it is given; snapping is the caller's job
    if (r < 1000f) return "${r.toInt()} m"
    val km = r / 1000f
    return if (km == km.toInt().toFloat()) "${km.toInt()} km" else "%.1f km".format(km)
}

/**
 * v1.48: the calendar read window in DAYS, whichever unit the user picked.
 * Months are converted at 31 days so a 24-month window can't under-read.
 */
fun calendarReadWindowDays(s: AppSettings): Int =
    if (s.calendarReadUnit == "MONTHS") s.calendarReadMonths.coerceIn(3, 24) * 31
    else s.calendarReadDays.coerceIn(1, 60)

/** v1.10 inherit-aware gestures: override map wins; an absent key inherits the global fields. */
fun gesturesFor(s: AppSettings, tab: Tab?): GestureSet {
    val p = tabPrefix(tab)
    fun b(key: String, g: Boolean): Boolean = when (s.tabGestureOv[p + key]) {
        "ON" -> true; "OFF" -> false; else -> g
    }
    fun t(key: String, g: String): String = s.tabGestureOv[p + key] ?: g
    return GestureSet(
        cardSwipe = b("CardSwipe", s.gestureCardSwipe),
        cardRightDone = b("CardRightDone", s.cardSwipeRightDone),
        pageSwipe = b("PageSwipe", s.gesturePageSwipe),
        pageRightDone = b("PageRightDone", s.pageSwipeRightDone),
        activeAction = t("ActiveAction", s.activeSwipeAction),
        doneAction = t("DoneAction", s.doneSwipeAction),
        deleteStyle = t("DeleteStyle", s.deleteStyle),
        revSwipeDelete = b("RevSwipeDelete", s.revSwipeDelete)
    )
}

/** v1.10 inherit-aware Done delay: negative per-tab value = inherit the global. */
fun delayFor(s: AppSettings, tab: Tab?): Int {
    val v = when (tab) {
        Tab.SHOP -> s.shopMoveDelaySec
        Tab.LEARN -> s.learnMoveDelaySec
        Tab.TASKS -> s.tasksMoveDelaySec
        else -> s.callsMoveDelaySec
    }
    return if (v < 0) s.moveDelaySec else v
}

/** v1.10 inherit-aware auto-clear days: negative = inherit the global. */
fun clearFor(s: AppSettings, tab: Tab?): Int {
    val v = when (tab) {
        Tab.SHOP -> s.shopDoneClearDays
        Tab.LEARN -> s.learnDoneClearDays
        Tab.TASKS -> s.tasksDoneClearDays
        else -> s.callsDoneClearDays
    }
    return if (v < 0) s.globalDoneClearDays else v
}


/** v1.8: next occurrence strictly after `after`, from an item's repeat config. */
val SPACED_GAPS = intArrayOf(3, 7, 14, 30)
fun spacedGapDays(step: Int): Int = SPACED_GAPS.getOrElse(step) { 30 }

fun nextOccurrence(item: Item, after: Long): Long? {
    if (item.repeatMode == "SPACED") {
        val due = item.dueAt ?: return null
        var next = due
        while (next <= after) next += spacedGapDays(item.spacedStep) * 86_400_000L
        return next
    }
    return nextOccurrenceCore(
        item.repeatMode, item.repeatDays, item.repeatN, item.repeatUnit,
        item.repeatOrd, item.repeatDow, item.repeatOrdList, item.dueAt, after
    )
}

fun nextOccurrenceCall(r: CallReminder, after: Long): Long? = nextOccurrenceCore(
    r.repeatMode, r.repeatDays, r.repeatN, r.repeatUnit,
    r.repeatOrd, r.repeatDow, r.repeatOrdList, r.recurAt ?: r.createdAt, after
)

/** v1.14: the next 3 occurrences — the editor preview. */
fun previewOccurrences(
    mode: String, days: Set<Int>, n: Int, unit: String,
    ord: Int, dow: Int, ordList: List<Int>, anchor: Long, count: Int = 3,
    startFrom: Long? = null, spacedStep: Int = 0
): List<Long> {
    val out = mutableListOf<Long>()
    // v1.15 item 3: first occurrence = first pattern hit ON/AFTER the chosen start date,
    // never in the past — so the horizon opens at max(now, start).
    var after = maxOf(System.currentTimeMillis(), (startFrom ?: 0L) - 1L)
    // Day-set patterns: the anchor date itself may not match the pattern (e.g. a
    // Sunday start for Weekly-on-Mondays) — step it back so only pattern days emit.
    val anchorEff = if (mode in DAY_SET_MODES) anchor - 86_400_000L else anchor
    var step = spacedStep
    repeat(count) {
        val next = if (mode == "SPACED") {
            var nx = anchor
            while (nx <= after) nx += spacedGapDays(step) * 86_400_000L
            step++
            nx
        } else nextOccurrenceCore(mode, days.toList(), n, unit, ord, dow, ordList, anchorEff, after) ?: return out
        out.add(next); after = next
    }
    return out
}

val DAY_SET_MODES = setOf("WEEKLY", "MONTHLY_DAY", "MONTHLY_ORD")

/** v1.15 item 2: every occurrence inside the horizon (for the 365-day View-all list). */
fun occurrencesWithin(
    mode: String, days: Set<Int>, n: Int, unit: String,
    ord: Int, dow: Int, ordList: List<Int>, anchor: Long,
    startFrom: Long?, untilMs: Long, cap: Int = 400, spacedStep: Int = 0
): List<Long> {
    val out = mutableListOf<Long>()
    var after = maxOf(System.currentTimeMillis(), (startFrom ?: 0L) - 1L)
    val anchorEff = if (mode in DAY_SET_MODES) anchor - 86_400_000L else anchor
    var step = spacedStep
    while (out.size < cap) {
        val next = if (mode == "SPACED") {
            var nx = anchor
            while (nx <= after) nx += spacedGapDays(step) * 86_400_000L
            step++
            nx
        } else nextOccurrenceCore(mode, days.toList(), n, unit, ord, dow, ordList, anchorEff, after) ?: break
        if (next > untilMs) break
        out.add(next); after = next
    }
    return out
}

private fun nextOccurrenceCore(
    repeatMode: String, repeatDaysIn: List<Int>, repeatN: Int, repeatUnit: String,
    repeatOrd: Int, repeatDow: Int, repeatOrdList: List<Int>, dueAnchor: Long?, after: Long
): Long? {
    val due = dueAnchor ?: return null
    val zone = ZoneId.systemDefault()
    var t = Instant.ofEpochMilli(due).atZone(zone)
    val afterZ = Instant.ofEpochMilli(after).atZone(zone)
    var guard = 0
    fun bump(cur: java.time.ZonedDateTime): java.time.ZonedDateTime = when (repeatMode) {
        "DAILY" -> cur.plusDays(1)
        "WEEKLY" -> {
            val days = repeatDaysIn.ifEmpty { listOf(cur.dayOfWeek.value) }.sorted()
            var c = cur.plusDays(1)
            while (c.dayOfWeek.value !in days) c = c.plusDays(1)
            c
        }
        "MONTHLY_DAY" -> {
            // v1.9: a SET of month-days (repeatDays reused); empty set = the due's own day.
            val daySet = repeatDaysIn.filter { it in 1..31 }.sorted()
                .ifEmpty { listOf(Instant.ofEpochMilli(due).atZone(zone).dayOfMonth) }
            val curDate = cur.toLocalDate()
            val laterSameMonth = daySet
                .map { minOf(it, curDate.lengthOfMonth()) }
                .filter { it > curDate.dayOfMonth }
                .minOrNull()
            val next = if (laterSameMonth != null) curDate.withDayOfMonth(laterSameMonth)
            else {
                val m = curDate.withDayOfMonth(1).plusMonths(1)
                m.withDayOfMonth(minOf(daySet.first(), m.lengthOfMonth()))
            }
            next.atTime(cur.toLocalTime()).atZone(zone)
        }
        "MONTHLY_ORD" -> {
            // v1.9: a SET of ord×weekday patterns (encoded ord*10+dow); empty = legacy single pair.
            val pats = repeatOrdList.ifEmpty { listOf(repeatOrd * 10 + repeatDow) }
            fun hit(monthFirst: java.time.LocalDate, pat: Int): java.time.LocalDate {
                val ord = pat / 10; val dow = java.time.DayOfWeek.of((pat % 10).coerceIn(1, 7))
                return if (ord >= 5) {
                    var x = monthFirst.withDayOfMonth(monthFirst.lengthOfMonth())
                    while (x.dayOfWeek != dow) x = x.minusDays(1); x
                } else {
                    var x = monthFirst
                    while (x.dayOfWeek != dow) x = x.plusDays(1)
                    x.plusWeeks((ord - 1).toLong())
                }
            }
            val curDate = cur.toLocalDate()
            var monthFirst = curDate.withDayOfMonth(1)
            var best: java.time.LocalDate? = null
            var g2 = 0
            while (best == null && g2 < 24) {
                best = pats.map { hit(monthFirst, it) }.filter { it.isAfter(curDate) }.minOrNull()
                monthFirst = monthFirst.plusMonths(1); g2++
            }
            (best ?: curDate.plusMonths(1)).atTime(cur.toLocalTime()).atZone(zone)
        }
        "QUARTERLY" -> cur.plusMonths(3)
        "HALFYEARLY" -> cur.plusMonths(6)
        "YEARLY" -> cur.plusMonths(12)
        "EVERY_N" -> when (repeatUnit) {
            "W" -> cur.plusWeeks(repeatN.toLong())
            "M" -> cur.plusMonths(repeatN.toLong())
            else -> cur.plusDays(repeatN.toLong())
        }
        else -> cur.plusDays(1)
    }
    if (repeatMode == "OFF") return null
    while (!t.isAfter(afterZ) && guard < 1000) { t = bump(t); guard++ }
    return t.toInstant().toEpochMilli()
}

fun repeatLabel(item: Item): String? = when (item.repeatMode) {
    "SPACED" -> "Spaced · next gap ${spacedGapDays(item.spacedStep)}d"
    "DAILY" -> "Daily"
    "WEEKLY" -> {
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        "Weekly · " + item.repeatDays.sorted().joinToString(",") { names[(it - 1).coerceIn(0, 6)] }.ifBlank { "—" }
    }
    "MONTHLY_DAY" -> {
        val ds = item.repeatDays.filter { it in 1..31 }.sorted()
        if (ds.isEmpty()) "Monthly · same day" else "Monthly · " + ds.joinToString(",")
    }
    "MONTHLY_ORD" -> {
        val ords = listOf("First", "Second", "Third", "Fourth", "Last")
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val pats = item.repeatOrdList.ifEmpty { listOf(item.repeatOrd * 10 + item.repeatDow) }
        pats.joinToString(" + ") { p ->
            ords[((p / 10) - 1).coerceIn(0, 4)] + " " + names[((p % 10) - 1).coerceIn(0, 6)]
        }
    }
    "QUARTERLY" -> "Quarterly"
    "HALFYEARLY" -> "Half-yearly"
    "YEARLY" -> "Yearly"
    "EVERY_N" -> "Every ${item.repeatN} " + when (item.repeatUnit) { "W" -> "week(s)"; "M" -> "month(s)"; else -> "day(s)" }
    else -> null
}

/**
 * v1.47 Feature 2c: does a NEW item on this tab get a clock time with its due date?
 * Per-tab ON/OFF wins; INHERIT (default) follows the global setting.
 */
fun newDueTimedFor(tab: Tab, s: AppSettings): Boolean {
    val per = when (tab) {
        Tab.SHOP -> s.shopNewDueTimed
        Tab.LEARN -> s.learnNewDueTimed
        else -> s.tasksNewDueTimed
    }
    return when (per) { "ON" -> true; "OFF" -> false; else -> s.globalNewDueTimed }
}

/** v1.8: default due for a NEW item per that tab's settings; null when mode = OFF. */
fun defaultNewDue(tab: Tab, s: AppSettings): Long? {
    var (mode, days, mins) = when (tab) {
        Tab.SHOP -> Triple(s.shopNewDueMode, s.shopNewDueDays, s.shopNewDueMinutes)
        Tab.LEARN -> Triple(s.learnNewDueMode, s.learnNewDueDays, s.learnNewDueMinutes)
        else -> Triple(s.tasksNewDueMode, s.tasksNewDueDays, s.tasksNewDueMinutes)
    }
    if (mode == "INHERIT") { mode = s.globalNewDueMode; days = s.globalNewDueDays; mins = s.globalNewDueMinutes }
    if (mode == "OFF") return null
    // v1.47 Feature 2c: a date-only default lands at the global "Default due time", matching how
    // date-only items are stored everywhere else.
    if (!newDueTimedFor(tab, s)) mins = s.defaultDueMinutes
    val addDays = when (mode) { "TODAY" -> 0L; "IN_N" -> days.toLong(); else -> 1L }
    val zone = ZoneId.systemDefault()
    return java.time.LocalDate.now().plusDays(addDays)
        .atTime(mins / 60, mins % 60).atZone(zone).toInstant().toEpochMilli()
}

fun computeReturnAt(from: Long, value: Int, unit: LapseUnit): Long {
    val zone = ZoneId.systemDefault()
    val base = Instant.ofEpochMilli(from).atZone(zone)
    val target = when (unit) {
        LapseUnit.DAYS -> base.plusDays(value.toLong())
        LapseUnit.MONTHS -> base.plusMonths(value.toLong())
    }
    return target.toInstant().toEpochMilli()
}

fun lapseLabel(value: Int, unit: LapseUnit): String =
    "$value " + (if (value == 1) unit.label.dropLast(1) else unit.label).lowercase(Locale.ENGLISH)


/** Digits only, last 10 kept — good enough to match Indian numbers with/without +91. */
fun normalizePhone(n: String): String {
    val digits = n.filter { it.isDigit() }
    return if (digits.length > 10) digits.takeLast(10) else digits
}

fun durationLabel(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${"%02d".format(s)}s" else "${s}s"
}

// ---------------------------------------------------------------- grouping (Year → Month → Date)

data class DayGroup<T>(val key: String, val label: String, val items: List<T>)
data class MonthGroup<T>(val key: String, val label: String, val days: List<DayGroup<T>>)
data class YearGroup<T>(val key: String, val label: String, val months: List<MonthGroup<T>>)

fun <T> MonthGroup<T>.count(): Int = days.sumOf { it.items.size }
fun <T> YearGroup<T>.count(): Int = months.sumOf { it.count() }
fun <T> YearGroup<T>.allItems(): List<T> = months.flatMap { m -> m.days.flatMap { it.items } }

/** The date an item is grouped under. Active: due date (or created). Done: completion date. */
fun groupBasis(item: Item, done: Boolean): Long =
    if (done) item.doneAt ?: item.createdAt else item.dueAt ?: item.createdAt

fun callBasis(c: CallReminder, done: Boolean): Long =
    if (done) c.doneAt ?: c.createdAt
    // v1.20 item 3: recurring calls group under the current occurrence day (moving at
    // midnight); one-offs keep their missed/added day.
    else if (c.repeatMode != "OFF" && c.recurAt != null) c.recurAt ?: c.lastMissedAt ?: c.createdAt
    else c.lastMissedAt ?: c.createdAt

fun <T> buildYearGroups(
    list: List<T>,
    descending: Boolean,
    tieBreak: (T) -> Long,
    basis: (T) -> Long
): List<YearGroup<T>> {
    val sorted = if (descending) {
        list.sortedWith(compareByDescending(basis).thenByDescending(tieBreak))
    } else {
        list.sortedWith(compareBy(basis).thenBy(tieBreak))
    }
    val years = LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, MutableList<T>>>>()
    val monthLabels = HashMap<String, String>()
    val dayLabels = HashMap<String, String>()
    for (item in sorted) {
        val b = basis(item)
        val d = b.toLocalDate()
        val yKey = "%04d".format(d.year)
        val mKey = "$yKey-%02d".format(d.monthValue)
        val dKey = "$mKey-%02d".format(d.dayOfMonth)
        monthLabels[mKey] = formatMonth(b)
        dayLabels[dKey] = formatDay(b)
        years.getOrPut(yKey) { LinkedHashMap() }
            .getOrPut(mKey) { LinkedHashMap() }
            .getOrPut(dKey) { mutableListOf() }
            .add(item)
    }
    return years.map { (yKey, months) ->
        YearGroup(
            key = yKey,
            label = yKey,
            months = months.map { (mKey, days) ->
                MonthGroup(
                    key = mKey,
                    label = monthLabels[mKey] ?: mKey,
                    days = days.map { (dKey, items) -> DayGroup(dKey, dayLabels[dKey] ?: dKey, items) }
                )
            }
        )
    }
}


// ---------------------------------------------------------------- v1.10 load-healing
// Gson builds stored records by reflection and SKIPS Kotlin default values, so any
// field added after a record was saved comes back null despite a non-null type —
// the first .copy() then throws (that was the v1.9 repeatOrdList crash).
// These rebuild every loaded record with explicit fallbacks, permanently.

@Suppress("USELESS_ELVIS")
fun healItem(i: Item): Item = i.copy(
    // v1.72: copy() reads EVERY field as a default, so any non-null field that Gson may have
    // left null must be coerced here or the copy itself throws. alertType is the only one.
    alertType = i.alertType ?: "N",
    tab = i.tab ?: Tab.TASKS,
    title = i.title ?: "",
    notes = i.notes ?: "",
    repeatMode = i.repeatMode ?: "OFF",
    repeatDays = i.repeatDays ?: emptyList(),
    repeatUnit = i.repeatUnit ?: "D",
    repeatOrdList = i.repeatOrdList ?: emptyList(),
    priceHistory = i.priceHistory ?: emptyList(),
    // v1.86 (N20): missedAt joined the model — every pre-1.86 json on disk AND every cloud item
    // from a <71 writer has it null; without this coat the very first copy() throws.
    missedAt = i.missedAt ?: emptyList()
)

@Suppress("USELESS_ELVIS")
fun healCall(r: CallReminder): CallReminder = r.copy(
    alertType = r.alertType ?: "N",
    number = r.number ?: "",
    source = r.source ?: CallSource.MANUAL,
    repeatMode = r.repeatMode ?: "OFF",
    repeatDays = r.repeatDays ?: emptyList(),
    repeatUnit = r.repeatUnit ?: "D",
    repeatOrdList = r.repeatOrdList ?: emptyList(),
    savedGroups = r.savedGroups ?: emptyList()
)

@Suppress("USELESS_ELVIS")
fun healPlace(p: GeoPlace): GeoPlace = p.copy(
    groupFilter = p.groupFilter ?: emptyList(),
    name = p.name ?: "",
    trigger = p.trigger ?: TriggerType.LEAVE,
    radius = snapRadius(p.radius)          // v2.02 (N33): onto the 13-stop scale
)




// ---------------------------------------------------------------- v1.11 pure helpers

/** Priority sort rank: Urgent first; null behaves as Medium. */
fun rankOf(p: Priority?): Int = when (p) {
    Priority.URGENT -> 0; Priority.HIGH -> 1; Priority.LOW -> 3; else -> 2
}

fun sortOf(s: AppSettings, tab: Tab): String = when (tab) {
    Tab.SHOP -> s.shopSort; Tab.LEARN -> s.learnSort; else -> s.tasksSort
}

/** v1.12: done recurring items whose next-occurrence DAY has arrived come back to Active.
 *  Runs at midnight (TYPE_MIDNIGHT) and as an app-start catch-up. Pure and unit-tested. */
fun resurrectDue(items: List<Item>, now: Long): List<Item> {
    val zone = java.time.ZoneId.systemDefault()
    return items.filter { it ->
        it.done && it.deletedAt == null && it.repeatMode != "OFF" && it.dueAt != null &&
            !java.time.Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate()
                .isAfter(java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    }.map { it.copy(done = false, doneAt = null) }
}

// ---------------------------------------------------------------- v1.15 pure helpers


/**
 * v1.71 (Q9): per-tab override wins, else the global switch. Unknown ints coerce to Inherit
 * rather than throwing, so a corrupted value can never hide a tab's tags permanently.
 */
fun showMediumTagFor(s: AppSettings, tab: Tab?): Boolean {
    val raw = when (tab) {
        Tab.SHOP -> s.shopShowMediumTag
        Tab.LEARN -> s.learnShowMediumTag
        Tab.TASKS -> s.tasksShowMediumTag
        else -> -1
    }
    return when (raw) { 1 -> true; 0 -> false; else -> s.showMediumTag }
}

/**
 * v1.69 (Q3) / v1.71 (Q9): Urgent, High and Low always show. Medium is the everyday default so
 * it stays hidden unless the tab (or the global switch) asks for it. No priority = no tag.
 */
fun showPriorityTag(p: Priority?, s: AppSettings, tab: Tab?): Boolean =
    p != null && (p != Priority.MEDIUM || showMediumTagFor(s, tab))

fun lastPrice(i: Item): Double? = i.priceHistory.lastOrNull()?.price

fun priceDelta(typed: Double?, last: Double?): Double? =
    if (typed == null || last == null) null else typed - last

fun pushPrice(history: List<PricePoint>, at: Long, price: Double): List<PricePoint> =
    (history + PricePoint(at, price)).takeLast(12)

fun hoursLabel(items: List<Item>): String {
    val sum = items.sumOf { it.hoursSpent }
    return if (sum <= 0.0) "" else " · " + (if (sum % 1.0 == 0.0) "%.0f".format(sum) else "%.1f".format(sum)) + " h"
}

fun isOosToday(i: Item, now: Long = System.currentTimeMillis()): Boolean =
    i.oosAt != null && i.oosAt.toLocalDate() == now.toLocalDate()

fun clearStaleOos(items: List<Item>, now: Long): List<Item> =
    items.filter { it.oosAt != null && it.oosAt.toLocalDate() < now.toLocalDate() }
        .map { it.copy(oosAt = null) }

/** v1.22 item 7: a repeat with a count is exhausted once its tally reaches it. */
fun repeatExhausted(count: Int?, done: Int): Boolean = count != null && done >= count

/** The count a UI may store: never below 2, never negative; null means "never ends". */
fun sanitizeRepeatCount(n: Int?): Int? = when {
    n == null -> null
    n < 2 -> 2
    else -> n
}

/** v1.22 item 13: the one place that decides whether a swipe commits. */
fun shouldCommitSwipe(dragPx: Float, cardWidthPx: Float, velocityDpPerSec: Float, distancePct: Int, velocityDp: Int): Boolean {
    if (cardWidthPx <= 0f) return false
    val pct = distancePct.coerceIn(15, 70)
    val travelled = kotlin.math.abs(dragPx)
    if (!travelled.isFinite() || !velocityDpPerSec.isFinite()) return false
    if (travelled >= cardWidthPx * (pct / 100f)) return true
    // A flick commits early only when flick speed is enabled (0 = off, distance only).
    return velocityDp > 0 && kotlin.math.abs(velocityDpPerSec) >= velocityDp
}

/** v1.20 item 4: items are all-day events — overdue only once the due day has passed. */
fun isOverdueDay(dueAt: Long?, now: Long): Boolean =
    dueAt != null && dueAt.toLocalDate() < now.toLocalDate()

/** v1.20 item 2: the card's base label — the reminder's source (Auto/Manual). */
fun callBaseLabel(c: CallReminder): String = c.source.label

/** v1.20 item 3: an active call is overdue once its occurrence day has passed. */
fun callOverdue(c: CallReminder, done: Boolean, now: Long): Boolean =
    !done && isOverdueDay(callBasis(c, false), now)

fun monthKey(ms: Long): String {
    val d = ms.toLocalDate(); return "%04d-%02d".format(d.year, d.monthValue)
}

fun dupActiveMatch(items: List<Item>, tab: Tab, title: String): Boolean {
    val t = title.trim().lowercase()
    if (t.isEmpty()) return false
    return items.any { it.tab == tab && !it.done && it.deletedAt == null && it.title.trim().lowercase() == t }
}

/** v1.14: recurring manual calls resurrect exactly like items. */
fun resurrectCallsDue(calls: List<CallReminder>, now: Long): List<CallReminder> {
    val zone = java.time.ZoneId.systemDefault()
    return calls.filter { r ->
        r.done && r.deletedAt == null && r.repeatMode != "OFF" && r.recurAt != null &&
            !java.time.Instant.ofEpochMilli(r.recurAt).atZone(zone).toLocalDate()
                .isAfter(java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    }.map { it.copy(done = false, doneAt = null) }
}

/** v1.14 (item 6): Shop expenditure — price parsed as the entry's amount. */
fun shopSpend(items: List<Item>): Double = items.sumOf {
    it.price?.replace(",", "")?.replace("₹", "")?.trim()?.toDoubleOrNull() ?: 0.0
}

fun spendLabel(items: List<Item>): String {
    val v = shopSpend(items)
    if (v <= 0.0) return ""
    return " · ₹" + (if (v == kotlin.math.floor(v)) "%,.0f".format(v) else "%,.2f".format(v))
}

/** Bin: live-record filters and the 30-day purge. */
const val BIN_KEEP_MS: Long = 30L * 24 * 60 * 60 * 1000
fun <T> filterLive(list: List<T>, deletedAt: (T) -> Long?): List<T> = list.filter { deletedAt(it) == null }
fun purgeCutoff(now: Long): Long = now - BIN_KEEP_MS

/** Morning digest counts across live items (due today or overdue). */

@Suppress("USELESS_ELVIS")
fun healSettings(a: AppSettings): AppSettings = a.copy(
    // v2.02 (N33): provider + limit coats (null String on pre-37 JSON; limit within the free cap).
    mapProvider = mapProviderNormalized(a.mapProvider),
    geoLimit = a.geoLimit.coerceIn(0, freeCapFor(a.indiaBilling)),
    defaultRadius = snapRadius(a.defaultRadius),
    shopNewRadius = snapRadius(if (a.shopNewRadius > 0f) a.shopNewRadius else 150f),
    // v2.05 (N37): Gson may leave the String null on pre-39 JSON; copy() would throw.
    shopArriveTypes = normalizeAlertTypes(a.shopArriveTypes) ?: "N",
    // v2.7 (N43): per-tab group-check overrides — null Strings on pre-40 JSON would break copy().
    tasksGroupCheck = a.tasksGroupCheck ?: "INHERIT",
    groupIcons = a.groupIcons ?: emptyMap(),           // v2.9: null Map on pre-42 JSON
    // v2.11 (N48): null List/Strings on pre-43 JSON; every list record is healed too.
    shopLists = (a.shopLists ?: emptyList()).mapNotNull { runCatching { healShopList(it) }.getOrNull() },
    buyOpensOn = if (a.buyOpensOn == "CLASSIC") "CLASSIC" else "LISTS",
    buyListSort = a.buyListSort?.takeIf { it in setOf("RECENT", "AZ", "CUSTOM") } ?: "RECENT",
    buyInnerSort = a.buyInnerSort?.takeIf { it in setOf("SHOP", "CATEGORY", "PRIORITY", "DATE", "NONE") } ?: "SHOP",
    shareWaApp = if (a.shareWaApp == "BUSINESS") "BUSINESS" else "WHATSAPP",
    shareHeadingSuffix = a.shareHeadingSuffix ?: ":-",
    shopDefaultGroup = a.shopDefaultGroup ?: "",
    learnGroupCheck = a.learnGroupCheck ?: "INHERIT",
    shopGroupCheck = a.shopGroupCheck ?: "INHERIT",
    shopArriveCooldownMin = if (a.shopArriveCooldownMin >= 0) a.shopArriveCooldownMin else 10,
    calendarReadDays = if (a.calendarReadDays > 0) a.calendarReadDays else 7,
    calendarReadUnit = if (a.calendarReadUnit == "MONTHS") "MONTHS" else "DAYS",
    calendarReadMonths = a.calendarReadMonths.coerceIn(3, 24),
    density = a.density ?: ("COMFY"),
    activeSwipeAction = a.activeSwipeAction ?: ("MOVE"),
    doneSwipeAction = a.doneSwipeAction ?: ("MOVE"),
    deleteStyle = a.deleteStyle ?: ("UNDO"),
    tasksGroups = a.tasksGroups ?: (emptyList()),
    shopGroups = a.shopGroups ?: (emptyList()),
    learnTopics = a.learnTopics ?: (emptyList()),
    tasksNewDueMode = a.tasksNewDueMode ?: ("TOMORROW"),
    shopNewDueMode = a.shopNewDueMode ?: ("TOMORROW"),
    learnNewDueMode = a.learnNewDueMode ?: ("TOMORROW"),
    theme = a.theme ?: ("SYSTEM"),
    tActiveAction = a.tActiveAction ?: ("MOVE"),
    tDoneAction = a.tDoneAction ?: ("MOVE"),
    tDeleteStyle = a.tDeleteStyle ?: ("UNDO"),
    sActiveAction = a.sActiveAction ?: ("MOVE"),
    sDoneAction = a.sDoneAction ?: ("MOVE"),
    sDeleteStyle = a.sDeleteStyle ?: ("UNDO"),
    lActiveAction = a.lActiveAction ?: ("MOVE"),
    lDoneAction = a.lDoneAction ?: ("MOVE"),
    lDeleteStyle = a.lDeleteStyle ?: ("UNDO"),
    cActiveAction = a.cActiveAction ?: ("MOVE"),
    cDoneAction = a.cDoneAction ?: ("MOVE"),
    cDeleteStyle = a.cDeleteStyle ?: ("UNDO"),
    globalNewDueMode = a.globalNewDueMode ?: ("TOMORROW"),
    tasksNewDueTimed = a.tasksNewDueTimed ?: "INHERIT",
    shopNewDueTimed = a.shopNewDueTimed ?: "INHERIT",
    learnNewDueTimed = a.learnNewDueTimed ?: "INHERIT",
    tabGestureOv = a.tabGestureOv ?: (emptyMap()),
    tasksSort = a.tasksSort ?: ("DATE"),
    shopSort = a.shopSort ?: ("DATE"),
    learnSort = a.learnSort ?: ("DATE"),
    shopGroupOrder = a.shopGroupOrder ?: (emptyList()),
    shopUntracked = a.shopUntracked ?: (emptyMap()),
    calendarAccount = a.calendarAccount?.trim()?.takeIf { it.isNotBlank() },
    calendarIds = a.calendarIds ?: emptySet(),
    tasksAlertsOn = a.tasksAlertsOn ?: "INHERIT",
    shopAlertsOn = a.shopAlertsOn ?: "INHERIT",
    learnAlertsOn = a.learnAlertsOn ?: "INHERIT",
    callsAlertsOn = a.callsAlertsOn ?: "INHERIT",
    alarmRingSeconds = coerceRingSeconds(a.alarmRingSeconds).coerceIn(0, 1800),
    ringRingSeconds = coerceRingSeconds(a.ringRingSeconds).coerceIn(0, 1800),
    calendarCloudIds = a.calendarCloudIds ?: emptySet(),
    tasksAlertRules = a.tasksAlertRules ?: "",
    shopAlertRules = a.shopAlertRules ?: "",
    learnAlertRules = a.learnAlertRules ?: "",
    timeFormat = a.timeFormat ?: ("PHONE"),
    callsNewDueMode = a.callsNewDueMode ?: ("TODAY")
)

// ---------------------------------------------------------------- v1.49 helpers

/** v1.49: unit price always shows 4 decimals ("i.dddd") — ₹0.0625/g must not render as "₹0". */
fun fmtUnitPrice(v: Double): String = fmtExact(v)

/**
 * v1.49: which calendar IDs to read, given the picked account and (optional) narrowed set.
 * No account -> nothing. Empty id set -> EVERY calendar in that account (Krishna's default-all).
 * Ids that no longer exist are dropped, so a deleted calendar can never break the query.
 */
fun selectedCalendarIds(s: AppSettings, all: List<CalSync.CalInfo>): List<Long> {
    if (s.calendarNone) return emptyList()          // v1.50: explicit "Select None"
    val acct = s.calendarAccount ?: return emptyList()
    val inAccount = all.filter { it.account == acct }
    if (s.calendarIds.isEmpty()) return inAccount.map { it.id }
    return inAccount.filter { it.id in s.calendarIds }.map { it.id }
}

/** v1.49: distinct account names, for the first step of the two-step picker. */
fun calendarAccountsOf(all: List<CalSync.CalInfo>): List<String> =
    all.map { it.account }.distinct().sortedBy { it.lowercase() }

/** v1.49: unit price of a Shop item — its own price/qty, else the latest recorded purchase. */
fun itemUnitPrice(i: Item): Pair<Double, String>? {
    val qty = i.quantity?.trim()?.toDoubleOrNull()
    val price = i.price?.trim()?.toDoubleOrNull()
    val unit = i.unit?.trim().orEmpty()
    if (qty != null && qty > 0.0 && price != null && price > 0.0 && unit.isNotBlank())
        return (price / qty) to unit
    val last = i.priceHistory.lastOrNull { it.unitPrice > 0.0 && it.unit.isNotBlank() }
    return last?.let { it.unitPrice to it.unit }
}

/**
 * v1.49: "Best U.P" ranks for Shop cards. Items sharing a title (trim + case-insensitive) are
 * ranked by NORMALISED unit price within their unit family; rank 1 = cheapest. Ties break to the
 * more recently created. Groups of one, or items with no usable price, get NO rank (never a
 * misleading tag). Presentation only — the caller must NOT reorder the list.
 */
fun bestUpRanks(items: List<Item>): Map<Long, Int> {
    val out = HashMap<Long, Int>()
    items.filter { it.tab == Tab.SHOP && it.deletedAt == null }
        .mapNotNull { i -> itemUnitPrice(i)?.let { (up, unit) ->
            val (fam, factor) = unitFamily(unit); Triple(i, up / factor, fam) } }
        .groupBy { (i, _, fam) -> i.title.trim().lowercase() to fam }
        .forEach { (_, group) ->
            if (group.size < 2) return@forEach
            group.sortedWith(compareBy({ it.second }, { -it.first.createdAt }))
                .forEachIndexed { idx, (i, _, _) -> out[i.id] = idx + 1 }
        }
    return out
}

/** v1.49: label for a Best-U.P rank — 1 => "Best U.P", n => "n Best U.P". */
fun upRankLabel(rank: Int): String = if (rank <= 1) "Best U.P" else "$rank Best U.P"

/** v1.49: active (not done/deleted) Shop items tagged to a registered shop. */
fun shopItemCount(items: List<Item>, shopName: String): Int {
    val key = shopName.trim()
    if (key.isEmpty()) return 0
    return items.count {
        it.tab == Tab.SHOP && !it.done && it.deletedAt == null &&
            (it.shopName ?: "").trim().equals(key, ignoreCase = true)
    }
}

/**
 * v1.49: a chip must not repeat what the list is already grouped by.
 * Returns "SHOP", "GROUP" or "TOPIC" (the chip to hide), or null. By Date keeps the card's time.
 */
fun suppressedChip(sort: String, tab: Tab?): String? = when (sort) {
    "SHOP" -> "SHOP"
    "GROUP" -> if (tab == Tab.LEARN) "TOPIC" else "GROUP"
    else -> null
}

/**
 * v1.50: display a number EXACTLY as it is — never rounded up. Truncates (never rounds) at the
 * 4th decimal and strips trailing zeros, so 45.6 -> "45.6", 45.0 -> "45", 0.0625 -> "0.0625",
 * and 45.60 can never surface as "46" (the old %.0f bug). Total on NaN/infinite input.
 */
fun fmtExact(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "0"
    val bd = java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
    return bd.toPlainString()
}

/**
 * v1.53: the read window, anchored on the DAY rather than the instant.
 *
 * The v1.50 code used `now` as the boundary, which meant an event that had already finished today
 * fell into the PAST window and an all-day event (whose instance begins at midnight) could never
 * appear in the future window at all. The calendar is read-only, so "Done" cannot mean completed —
 * it can only mean earlier days. Today therefore always belongs to Active.
 *
 * Active  -> [startOfToday, startOfToday + span)
 * Done    -> [startOfToday - span, startOfToday)
 * Today is never duplicated across the two views.
 */
fun calendarWindowBounds(nowMs: Long, days: Int, past: Boolean): Pair<Long, Long> {
    val startToday = startOfDayMs(nowMs)
    val span = days.coerceIn(1, 760).toLong() * 86_400_000L
    return if (past) (startToday - span) to startToday else startToday to (startToday + span)
}

// ---------------------------------------------------------------- v1.56 alerts phase 1

/** 1.1: are alerts delivered for this tab? Per-tab ON/OFF wins; INHERIT follows the global master. */
fun alertsEnabledFor(tab: Tab?, s: AppSettings): Boolean {
    val per = when (tab) {
        Tab.SHOP -> s.shopAlertsOn; Tab.LEARN -> s.learnAlertsOn
        Tab.TASKS -> s.tasksAlertsOn; else -> s.callsAlertsOn
    }
    return when (per) { "ON" -> true; "OFF" -> false; else -> s.alertsEnabled }
}



/** Human label for a rule, for the settings list. */
fun callRuleLabel(r: Pair<Char, Int>): String =
    // v1.86 (N11, Krishna option 1): ONE unit language app-wide — offsets are plain minutes,
    // matching snoozeMinLabel everywhere else. "After 90 m", never "After 1 h 30 min".
    if (r.first == 'O') "After ${r.second} m"
    else "At %02d:%02d".format(r.second / 60, r.second % 60)

// ---------------------------------------------------------------- v1.57 type sets (1.2)


/** Channel set for an item: group override → tab override → global; HIGH priority refuses empty. */
/** v1.68: THE resolution — the item's own letter, nothing else. */
/** v2.8 (N44): an item whose alert was deleted from Scheduled alerts — silent, nothing armed. */
const val ALERT_MUTED = "OFF"
fun isMuted(item: Item): Boolean = item.alertType == ALERT_MUTED

fun resolveAlertTypes(item: Item, s: AppSettings): String =
    if (item.alertType == ALERT_MUTED) "" else item.alertType.takeIf { it == "A" || it == "R" || it == "N" } ?: "N"

/** v1.68: a call's detection channel = its own letter. */
fun resolveCallTypes(r: CallReminder, s: AppSettings): String =
    r.alertType.takeIf { it == "A" || it == "R" || it == "N" } ?: "N"






/** v1.68: durations in a hard 3 s – 3 min band; legacy 0 → 7 s. */
fun coerceRingSeconds(v: Int): Int = when {
    v <= 0 -> 7
    v < 3 -> 3
    v > 180 -> 180
    else -> v
}

/** v1.57 Plan-B narrowing: which cloud calendar IDs to read. Empty selection = ALL; None = nothing. */
fun filterCloudCalendars(all: List<String>, selected: Set<String>, none: Boolean): List<String> = when {
    none -> emptyList()
    selected.isEmpty() -> all
    else -> all.filter { it in selected }
}

// ---------------------------------------------------------------- v1.58 per-item extra alerts (1.3)




/**
 * v1.83 (N10): THE formatter for every snooze/defer duration — plain minutes, no collapsing.
 *
 * Replaces `minLbl`, which rendered 90 as "1 h 30 min" while `Alerts.snoozeLabel` rendered the
 * same 90 as "1 h". Two formatters, one value, two answers: the button and the toast it fired
 * disagreed. There is now one function and `Alerts.snoozeLabel` delegates to it.
 *
 * Named distinctly from ringDurationLabel(seconds) and durationLabel(Long) which format other units.
 */
fun snoozeMinLabel(m: Int): String = "$m m"

/** v1.61: the quick-add circle shows "+" (full editor) on a blank draft, "✓" (quick add) otherwise. */
fun quickCircleIsPlus(text: String): Boolean = text.isBlank()

/** v1.62: "45 s", "1 min 30 s", "2 min", or "Until dismissed". Exact — never rounded.
 *  (Named distinctly from the call-duration durationLabel(Long), which formats "2m 14s".) */
fun ringDurationLabel(seconds: Int): String = when {
    seconds <= 0 -> "7 s"   // v1.68
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} s"
}

/** v1.68 band: min+sec fields → total seconds; null when invalid (3 s – 3 min, sec 0–59). */
fun minSecToSeconds(minTxt: String, secTxt: String): Int? {
    val m = if (minTxt.isBlank()) 0 else minTxt.toIntOrNull() ?: return null
    val sec = if (secTxt.isBlank()) 0 else secTxt.toIntOrNull() ?: return null
    if (m < 0 || sec < 0 || sec > 59) return null
    val total = m * 60 + sec
    return if (total in 3..180) total else null
}

// ---------------------------------------------------------------- v1.63 confirmation toasts

private fun clockHM(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun toastTitle(t: String): String = if (t.length > 24) t.take(24) + "\u2026" else t

/** v1.68: the double-tap gesture's confirmation — quiet re-fire. */
fun quietSnoozeToast(fireAt: Long, now: Long = System.currentTimeMillis()): String =
    "\u23F0 Reminds again quietly at " + (if (startOfDayMs(fireAt) == startOfDayMs(now)) clockHM(fireAt) else formatDateTime(fireAt))

/** v1.63: "⏰ Snoozed 30 min — rings again at 14:52" (full date shown if it crosses midnight). */
fun snoozeToast(minutes: Int, fireAt: Long, now: Long = System.currentTimeMillis()): String {
    val at = if (startOfDayMs(fireAt) == startOfDayMs(now)) "at " + clockHM(fireAt)
             else "at " + formatDateTime(fireAt)
    return "\u23F0 Snoozed " + snoozeMinLabel(minutes) + " \u2014 rings again " + at
}

fun doneToastItem(title: String, tab: Tab): String =
    "\u2713 \"" + toastTitle(title) + "\" done \u2014 moved to " + tab.title + " \u00B7 Done list"

fun doneToastCall(name: String): String =
    "\u2713 Call reminder for " + toastTitle(name) + " marked done"

fun dismissToast(title: String): String =
    "Dismissed \u2014 \"" + toastTitle(title) + "\" stays on your list"

fun closeCallToast(): String = "Closed \u2014 reminder stays on the Calls tab"


fun stopToastText(): String = "Sound stopped"

// ---------------------------------------------------------------- v1.67 contact enrichment

/** "First Last" from structured parts; null when both are blank. */
fun callFullName(first: String?, last: String?): String? =
    listOfNotNull(
        first?.trim()?.takeIf { it.isNotBlank() },
        last?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString(" ").takeIf { it.isNotBlank() }

/** Display chain: structured name → legacy cached name → the raw number. */
fun callDisplayOf(first: String?, last: String?, name: String?, number: String): String =
    callFullName(first, last) ?: name?.takeIf { it.isNotBlank() } ?: number



// ================================================================ v1.86 (N24/N25/N20/N18/N26)

/** N24: the editor's schedule kind. NONE exists only where showNone is passed (Tasks + Learn). */
enum class SchedKind { NONE, ONCE, REPEAT }

/** N25: parse a stored default; off-list values fall back to ONCE (the N10 chip-lie class). */
fun toKind(v: String?): SchedKind = when (v) {
    "NONE" -> SchedKind.NONE; "REPEAT" -> SchedKind.REPEAT; else -> SchedKind.ONCE
}

/** N24 seed rule: a dateless, non-repeating item IS a No-Reminders item — say so. */
fun schedKindOf(i: Item): SchedKind = when {
    i.repeatMode != "OFF" -> SchedKind.REPEAT
    i.dueAt == null -> SchedKind.NONE
    else -> SchedKind.ONCE
}

/**
 * N24: Save under "No Reminders". Zeroes exactly the schedule: due, date-only flag, live snooze
 * (a never-alerting item must not ring — ⚑ announced + logged at the call site), the repeat
 * pattern and its end-count. PRESERVES alertType (restored if re-scheduled), missedAt (history),
 * returnAt (the silent Shop-lapse return is not an alert), expiryAt, and everything else.
 */
fun clearSchedule(i: Item): Item = i.copy(
    dueAt = null, dueHasTime = false, snoozedUntil = null,
    repeatMode = "OFF", repeatDays = emptyList(), repeatN = 1, repeatUnit = "D",
    repeatOrd = 1, repeatDow = 1, repeatOrdList = emptyList(),
    repeatCount = null, repeatDone = 0
)

/** N25 resolver: per-tab override -> global; Shop CLAMPS NONE to ONCE (D1, silent by design). */
fun schedDefaultFor(s: AppSettings, tab: Tab): SchedKind {
    val ov = when (tab) {
        Tab.SHOP -> s.shopSchedDefault; Tab.LEARN -> s.learnSchedDefault; else -> s.tasksSchedDefault
    }
    val k = if (ov != "INHERIT") toKind(ov) else toKind(s.schedDefault)
    return if (tab == Tab.SHOP && k == SchedKind.NONE) SchedKind.ONCE else k
}

/** N18: the shared toggle's vocabulary per mode — list speaks state, calendar speaks time. */
fun toggleLabels(calMode: Boolean): Pair<String, String> =
    if (calMode) "Upcoming" to "Past" else "Active" to "Done"

// ---------------------------------------------------------------- N20: auto-roll missed repeats

const val MISSED_CAP = 10
private const val ROLL_MAX_STEPS = 400

/** The editor/View-Alert "Missed Alerts" rows: newest first, capped. */
fun missedRows(i: Item): List<Long> = i.missedAt.sortedDescending().take(MISSED_CAP)

private fun dayStartOf(t: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = t
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * N20 (Krishna's 2.2): ACTIVE repeating items whose due day has passed ROLL FORWARD — every
 * missed occurrence is discarded into `missedAt` (never tallied, so an ends-after-N repeat can
 * never finish by absence) and dueAt lands on the first occurrence dated today or later.
 * A LIVE snooze is still coming and is never rolled. alertType / time-of-day / the original
 * chain anchor are preserved. Returns only the items that changed.
 */
fun rollMissedRepeats(items: List<Item>, now: Long): List<Item> {
    val today = dayStartOf(now)
    val out = ArrayList<Item>()
    for (i in items) {
        if (i.done || i.deletedAt != null) continue
        if (i.repeatMode == "OFF") continue
        val due = i.dueAt ?: continue
        if (dayStartOf(due) >= today) continue
        if ((i.snoozedUntil ?: 0L) > now) continue          // a live snooze is never rolled
        var cur = due
        val missed = ArrayList<Long>()
        var steps = 0
        while (dayStartOf(cur) < today && steps < ROLL_MAX_STEPS) {
            missed.add(cur)
            cur = nextOccurrence(i.copy(dueAt = cur), cur) ?: break
            steps++
        }
        if (steps >= ROLL_MAX_STEPS) continue                // corrupt anchor: never loop the sweep
        if (dayStartOf(cur) < today || missed.isEmpty()) continue
        out.add(i.copy(
            dueAt = cur,
            snoozedUntil = null,
            missedAt = (missed.reversed() + i.missedAt).take(MISSED_CAP)
        ))
    }
    return out
}

// ---------------------------------------------------------------- N26: call-scan window + purge

const val SCAN_ROW_CAP = 200

/**
 * v2.6.2 (N41, Krishna's ruling 18-Aug-2026): "the missed call will always be monitored and will be
 * only fetched when call comes and not from history. History must be completely ignored."
 * The call log is now read for ONE purpose — resolving the row the OS writes moments after a call
 * ends — so the window is a short LIVE window, never an install-date or 48 h reach-back. The old
 * SCAN_LOOKBACK_MS (48 h) and the install-fence anchor are retired: they are exactly what let a
 * first sweep ingest hundreds of historical rows on Vivo/Redmi, one ring/alarm per number.
 */
const val LIVE_WINDOW_MS = 5L * 60_000L

/** Retry ladder for OEMs that write the call-log row late (ms after the call ends). */
val CALL_RETRY_DELAYS_MS = listOf(10_000L, 60_000L)

/**
 * The live scan window start = max(install anchor, now − live window).
 * [anchorMs] is the IMMUTABLE first-run anchor, not an advancing watermark: if the watermark crept
 * forward with each sweep, a row the OEM writes late (its DATE sits a few minutes back) would fall
 * behind it and be lost — which is precisely what the retry ladder exists to catch. Re-reading the
 * same five minutes is safe because the dedupe ring rejects rows already ingested.
 */
fun liveScanWindow(anchorMs: Long, now: Long): Long =
    maxOf(if (anchorMs > 0) anchorMs else now - LIVE_WINDOW_MS, now - LIVE_WINDOW_MS)

/** A row is processable only if it belongs to the live window — history is ignored, always. */
fun isLiveRow(rowDate: Long, now: Long): Boolean = rowDate >= now - LIVE_WINDOW_MS && rowDate <= now + 60_000L

/**
 * Storm guard: however many new numbers one sweep produces, it may raise AT MOST ONE alert —
 * a single reminder alerts on its own letter, several become one summary. Continuous ring/alarm
 * chains are therefore structurally impossible, whatever a provider does.
 */
fun alertsForSweep(newReminders: Int): Int = if (newReminders <= 0) 0 else 1

/** N26 migration: AUTO reminders born from pre-install calls are junk — split them out. */
fun purgePreInstall(calls: List<CallReminder>, fenceMs: Long): Pair<List<CallReminder>, List<CallReminder>> {
    val purged = calls.filter {
        it.source == CallSource.AUTO && it.deletedAt == null && (it.lastMissedAt ?: Long.MAX_VALUE) < fenceMs
    }
    val keep = calls - purged.toSet()
    return keep to purged
}

// ================================================================ v2.10 (N9) permissions, asked in context

/**
 * Where a runtime permission stands. NOT_ASKED and BLOCKED both read "not granted" from the OS —
 * only our own record ([asked]) tells them apart, which is why the record exists.
 */
enum class PermState { GRANTED, NOT_ASKED, DENIED, BLOCKED }

/** What tapping "Allow" should do next. */
enum class PermAction { NONE, PRE_PROMPT, SYSTEM_DIALOG, OPEN_SETTINGS }

/**
 * One row per runtime permission group — DECLARED ONCE (standing rule, like ITEM_ALARM_TYPES):
 * the pre-prompt, the feature notes, Settings → Permissions and the manifest gate test all read this.
 * [permissions] are the manifest names requested together; [checkPermissions] must all be granted.
 * [minSdk]: below it the permission does not exist and counts as granted.
 */
data class PermInfo(
    val key: String,
    val title: String,
    val enables: String,
    val withoutIt: String,
    val permissions: List<String>,
    val checkPermissions: List<String> = permissions,
    val minSdk: Int = 1
)

object PermKeys {
    const val NOTIFICATIONS = "NOTIFICATIONS"
    const val CALL_LOG = "CALL_LOG"
    const val CONTACTS = "CONTACTS"
    const val SAVE_CONTACT = "SAVE_CONTACT"
    const val LOCATION = "LOCATION"
    const val BG_LOCATION = "BG_LOCATION"
    const val CALENDAR = "CALENDAR"
}

private const val P = "android.permission."

val RUNTIME_PERMISSIONS: List<PermInfo> = listOf(
    PermInfo(PermKeys.NOTIFICATIONS, "Notifications",
        "Shows your reminders, alarms and call-backs on screen.",
        "Nothing can appear on screen: alarms may still sound, but no card or notification shows what they are for.",
        listOf(P + "POST_NOTIFICATIONS"), minSdk = 33),
    PermInfo(PermKeys.CALL_LOG, "Call log & phone state",
        "Notices a missed call the moment it ends and adds a call-back reminder; clears it once you talk.",
        "The Calls tab still works — add call-backs by hand. Missed calls are not detected automatically.",
        listOf(P + "READ_CALL_LOG", P + "READ_PHONE_STATE")),
    PermInfo(PermKeys.CONTACTS, "Contacts (read)",
        "Shows the caller's name instead of the number on call-backs.",
        "Call-backs show the phone number instead of a name.",
        listOf(P + "READ_CONTACTS")),
    PermInfo(PermKeys.SAVE_CONTACT, "Contacts (save)",
        "Lets \"Save contact\" add an unknown caller to your phone's contacts.",
        "\"Save contact\" is unavailable; everything else works.",
        listOf(P + "WRITE_CONTACTS")),
    PermInfo(PermKeys.LOCATION, "Location",
        "Places and shop geofences: your shop list alerts you when you arrive or leave.",
        "Geofence alerts are off. Shop lists, shops and products work as normal.",
        listOf(P + "ACCESS_FINE_LOCATION", P + "ACCESS_COARSE_LOCATION"),
        checkPermissions = listOf(P + "ACCESS_FINE_LOCATION")),
    PermInfo(PermKeys.BG_LOCATION, "Location — all the time",
        "Lets geofence alerts fire while Remindly is closed (Android calls this \"Allow all the time\").",
        "Geofence alerts only fire while Remindly is open.",
        listOf(P + "ACCESS_BACKGROUND_LOCATION"), minSdk = 29),
    PermInfo(PermKeys.CALENDAR, "Calendar",
        "Shows your calendar events among Tasks and, if you turn it on, writes reminders into a calendar.",
        "Calendar reading and calendar sync are off; nothing else changes.",
        listOf(P + "READ_CALENDAR", P + "WRITE_CALENDAR"),
        checkPermissions = listOf(P + "READ_CALENDAR"))
)

fun permInfo(key: String): PermInfo = RUNTIME_PERMISSIONS.first { it.key == key }

/** The permissions to request on this SDK (drops ones that do not exist yet). */
fun permsToRequest(info: PermInfo, sdk: Int): List<String> = if (sdk < info.minSdk) emptyList() else info.permissions

/**
 * The resolver. [granted] from the OS; [asked] = we have shown the system dialog before (or it was
 * granted at some point); [canAskAgain] = Android's shouldShowRequestPermissionRationale.
 * After a refusal Android may stop showing the dialog at all — then only system settings can grant.
 */
fun resolvePermState(granted: Boolean, asked: Boolean, canAskAgain: Boolean): PermState = when {
    granted -> PermState.GRANTED
    !asked -> PermState.NOT_ASKED
    canAskAgain -> PermState.DENIED
    else -> PermState.BLOCKED
}

/**
 * N9 ⚑2: the pre-prompt (the WHY) is shown once, before the first system dialog. A permission
 * Android will no longer ask for goes to system settings instead of a button that silently does nothing.
 */
fun permAction(state: PermState, prePromptSeen: Boolean): PermAction = when (state) {
    PermState.GRANTED -> PermAction.NONE
    PermState.BLOCKED -> PermAction.OPEN_SETTINGS
    PermState.NOT_ASKED -> if (prePromptSeen) PermAction.SYSTEM_DIALOG else PermAction.PRE_PROMPT
    PermState.DENIED -> PermAction.SYSTEM_DIALOG
}

fun permStateLabel(state: PermState): String = when (state) {
    PermState.GRANTED -> "Allowed"
    PermState.NOT_ASKED -> "Not asked yet"
    PermState.DENIED -> "Off"
    PermState.BLOCKED -> "Off — change in Android settings"
}

fun permButtonLabel(state: PermState): String? = when (state) {
    PermState.GRANTED -> null
    PermState.BLOCKED -> "Open settings"
    else -> "Allow"
}

// ---- feature availability, per permission state (what each screen tells the user) ----

enum class CallDetect { AUTO, MANUAL_ONLY }
fun callDetectMode(callLog: PermState): CallDetect = if (callLog == PermState.GRANTED) CallDetect.AUTO else CallDetect.MANUAL_ONLY

/** Calls tab: the full intro card only before the user has ever decided; afterwards a one-line note. */
fun callsIntroVisible(callLog: PermState, prePromptSeen: Boolean): Boolean = callLog == PermState.NOT_ASKED && !prePromptSeen
fun callsNoteVisible(callLog: PermState, prePromptSeen: Boolean): Boolean =
    callLog != PermState.GRANTED && !callsIntroVisible(callLog, prePromptSeen)

fun callNamesShown(contacts: PermState): Boolean = contacts == PermState.GRANTED

enum class GeoMode { ALWAYS, WHILE_OPEN, OFF }
fun geofenceMode(location: PermState, background: PermState, sdk: Int): GeoMode = when {
    location != PermState.GRANTED -> GeoMode.OFF
    sdk < 29 || background == PermState.GRANTED -> GeoMode.ALWAYS
    else -> GeoMode.WHILE_OPEN
}

fun geofenceNote(mode: GeoMode): String? = when (mode) {
    GeoMode.ALWAYS -> null
    GeoMode.WHILE_OPEN -> "Geofence alerts only fire while Remindly is open — choose \"Allow all the time\" to get them in your pocket."
    GeoMode.OFF -> "Geofence alerts are off — location access isn't allowed. Shop lists work as normal."
}

fun remindersVisible(notifications: PermState, sdk: Int): Boolean = sdk < 33 || notifications == PermState.GRANTED

/**
 * N9 ⚑ one state with N2: a never-asked permission gets the pre-prompt, never the degrade banner —
 * the banner is for something the user once allowed (or refused) and is now off.
 */
fun permDegradeVisible(state: PermState): Boolean = state == PermState.DENIED || state == PermState.BLOCKED

// ================================================================ v2.10 (N6 pt2) pure extractions — tests call these

/**
 * The list pipeline behind BOTH the header count and the rendered list (v1.79 Q11). Extracted from
 * ListScreens.scopeOf so the test runs the real filter, not a copy.
 */
fun listScope(tabItems: List<Item>, tab: Tab, doneFlag: Boolean, personalFilter: Boolean,
              searchOpen: Boolean, searchQ: String): List<Item> = tabItems
    .filter { it.deletedAt == null }
    .filter { it.done == doneFlag }
    .let { list -> if (tab == Tab.SHOP) list.filter { it.personal == personalFilter } else list }
    .let { list ->
        val q = searchQ.trim().lowercase()
        if (!searchOpen || q.isEmpty()) list else list.filter { it0 ->
            listOfNotNull(it0.title, it0.notes, it0.group, it0.topic, it0.shopName, it0.platform)
                .any { f -> f.lowercase().contains(q) }
        }
    }

enum class Rearm { DUE, LAPSE }

/** What rescheduleAll re-arms for an item (v1.79 Q12: soft-deleted items are never re-armed). */
fun rearmKind(item: Item, now: Long): Rearm? = when {
    item.deletedAt != null -> null
    !item.done -> Rearm.DUE
    item.returnAt != null && item.returnAt > now -> Rearm.LAPSE
    else -> null
}

/** When an item's due alarm fires (v1.80 Q17: a live snooze wins; v2.8 N44: muted arms nothing). */
fun itemFireAt(item: Item, now: Long): Long? =
    if (item.done || item.deletedAt != null || isMuted(item)) null
    else liveSnooze(item.snoozedUntil, now) ?: item.dueAt

/** Bulk clear (v1.79 Q14): repeating items survive unless the user ticks "also delete repeating". */
fun bulkClearTargets(items: List<Item>, alsoRepeats: Boolean): List<Item> =
    if (alsoRepeats) items else items.filter { it.repeatMode == "OFF" }

/** wa.me number (v1.79 N4): a bare 10-digit local number gets the configured country code. */
fun waDigits(number: String, cc: String): String {
    val digits = number.filter { it.isDigit() }
    val code = cc.filter { it.isDigit() }.ifBlank { "91" }
    return if (digits.length == 10) "$code$digits" else digits
}

/** One-time v1.81 (Q18) migration: an accidental stored 10-minute snooze becomes 90, once. */
fun migrateSnooze90(s: AppSettings): AppSettings =
    if (s.snoozeFixed90) s else s.copy(snoozeM1 = if (s.snoozeM1 == 10) 90 else s.snoozeM1, snoozeFixed90 = true)

/** v1.81 (Q15): per-item request code for the notification tap, so taps never overwrite each other. */
fun itemTapRequestCode(itemId: Long): Int = (8_500_000 + itemId).toInt()

/**
 * v1.70 (Q10) older-writer merge, extracted from Sync.mergeRecord (v2.10 N6 pt2): overlay ONLY the
 * keys the remote actually carries onto the local record. Returns the merged record and the local
 * fields that were kept because the remote lacked them.
 */
fun <T : Any> overlayOlderWriter(remoteJson: String, local: T, cls: Class<T>): Pair<T, List<String>> {
    val base = com.google.gson.GsonBuilder().serializeNulls().create().toJsonTree(local).asJsonObject
    val remote = com.google.gson.JsonParser.parseString(remoteJson).asJsonObject
    val missing = base.entrySet().map { it.key }.filter { !remote.has(it) }
    remote.entrySet().forEach { (k, v) -> base.add(k, v) }
    return com.google.gson.Gson().fromJson(base, cls) to missing
}
