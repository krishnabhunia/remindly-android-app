package com.krishna.remindly

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

@SuppressLint("StaticFieldLeak")
object Stores {
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        // Always re-point: under Robolectric every test gets a fresh Application/dataDir,
        // and a stale context here silently sends all file I/O to the wrong sandbox.
        val fresh = !::appContext.isInitialized || appContext !== context.applicationContext
        appContext = context.applicationContext
        if (!fresh) return
        // v1.34: load (or mint once) this install's device tag for collision-free multi-device ids.
        runCatching {
            val p = appContext.getSharedPreferences("remindly_device", Context.MODE_PRIVATE)
            var tag = p.getInt("tag", 0)
            if (tag !in 1..0xFFF) {
                tag = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 0x1000)
                p.edit().putInt("tag", tag).apply()
            }
            Ids.initTag(tag)
        }.onFailure { Logger.e(appContext, "IDS", it, "device-tag init failed; ids stay untagged") }
        SettingsStore.load()
        ItemStore.load()
        PlaceStore.load()
        CallStore.load()
        ShopStore.load()
        CityStore.load()
        ChainStore.load()
        ProductStore.load()
        // v1.90 one-time migration: seed Cities from the free-text Shop.area values. Pure
        // function (unit-tested), guarded, idempotent, and flagged off after the first run.
        runCatching {
            if (!SettingsStore.s.value.citySeedDone) {
                val (cities, shops) = seedCitiesFromShops(
                    ShopStore.shops.value, CityStore.cities.value, { Ids.next() }, System.currentTimeMillis()
                )
                if (cities != CityStore.cities.value) CityStore.replaceAll(cities)
                if (shops != ShopStore.shops.value) ShopStore.replaceAll(shops)
                SettingsStore.update { it.copy(citySeedDone = true) }
            }
        }.onFailure { Logger.e(appContext, "CITY", it, "city seed migration failed — will retry next start") }
        // v2.02 (N33 C3): if the 13-stop scale moved any stored radius, say so once (and re-register).
        runCatching {
            val n = RadiusSnapNotice.moved
            if (n > 0) {
                RadiusSnapNotice.moved = 0
                PlaceStore.replaceAll(PlaceStore.places.value); ShopStore.replaceAll(ShopStore.shops.value)
                UiStore.update { it.copy(pendingNotice = "Geofence radii moved to the new scale ($n adjusted to the nearest of 50 m…3 km).") }
                Logger.e(appContext, "GEO", null, "radius scale snap: $n radii adjusted")
            }
        }.onFailure { Logger.e(appContext, "GEO", it, "radius snap notice failed") }
    }
}

val gson: Gson = Gson()

/** v2.02 (N33 C3): how many stored geofence radii the new 13-stop scale moved at load (one-time notice). */
object RadiusSnapNotice { @Volatile var moved: Int = 0 }

/** Set whenever any store persists — used to decide whether to auto-sync to Drive. */
object Dirty {
    @Volatile
    var flag = false
}

// ---------------------------------------------------------------- items

object ItemStore {
    val items = MutableStateFlow<List<Item>>(emptyList())
    private val file get() = File(Stores.appContext.filesDir, "items.json")

    fun load() {
        items.value = runCatching {
            gson.fromJson(file.readText(), Array<Item>::class.java).toList().map(::healItem)
        }.onFailure {
            it.printStackTrace()
            runCatching { Logger.e(Stores.appContext, "LOAD", it, "items.json failed to parse") }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(gson.toJson(items.value)) }
        Dirty.flag = true
    }

    fun get(id: Long): Item? = items.value.firstOrNull { it.id == id }

    fun upsert(item: Item) {
        val stamped = item.copy(updatedAt = System.currentTimeMillis())
        val rest = items.value.filter { it.id != stamped.id }
        items.value = rest + stamped
        save()
    }

    fun delete(id: Long) {
        items.value = items.value.filter { it.id != id }
        save()
    }

    fun replaceAll(newItems: List<Item>) {
        items.value = newItems
        save()
    }

    fun pendingShopItems(): List<Item> =
        items.value.filter { it.tab == Tab.SHOP && !it.done }
}

// ---------------------------------------------------------------- places

object PlaceStore {
    val places = MutableStateFlow<List<GeoPlace>>(emptyList())
    private val file get() = File(Stores.appContext.filesDir, "places.json")

    fun load() {
        places.value = runCatching {
            val raw = gson.fromJson(file.readText(), Array<GeoPlace>::class.java).toList()
            val healed = raw.map(::healPlace)
            RadiusSnapNotice.moved += raw.zip(healed).count { (r, h) -> r.radius != h.radius }
            healed
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(gson.toJson(places.value)) }
        Dirty.flag = true
    }

    fun get(id: Long): GeoPlace? = places.value.firstOrNull { it.id == id && it.deletedAt == null }

    /** v1.35: live places only — tombstones (deletedAt set) are excluded from display and geofencing. */
    fun active(): List<GeoPlace> = places.value.filter { it.deletedAt == null }

    fun upsert(place: GeoPlace) {
        val stamped = place.copy(updatedAt = System.currentTimeMillis())
        val rest = places.value.filter { it.id != stamped.id }
        places.value = rest + stamped
        save()
    }

    fun delete(id: Long) {
        // v1.35: soft-delete so the removal is a merge-winning tombstone that syncs to other devices.
        val p = places.value.firstOrNull { it.id == id }
        if (p != null) upsert(p.copy(deletedAt = System.currentTimeMillis()))
    }

    fun replaceAll(newPlaces: List<GeoPlace>) {
        places.value = newPlaces
        save()
    }

    fun markFired(id: Long) {
        val p = get(id) ?: return
        upsert(p.copy(lastFired = System.currentTimeMillis()))
    }
}

// ---------------------------------------------------------------- shops (v1.37, Phase A)

object ShopStore {
    val shops = MutableStateFlow<List<Shop>>(emptyList())
    private val file get() = File(Stores.appContext.filesDir, "shops.json")

    fun load() {
        shops.value = runCatching {
            val raw = gson.fromJson(file.readText(), Array<Shop>::class.java).toList()
            val healed = raw.map(::healShop)
            RadiusSnapNotice.moved += raw.zip(healed).count { (r, h) -> r.deletedAt == null && r.radius != h.radius }
            healed
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(gson.toJson(shops.value)) }
        Dirty.flag = true
    }

    fun active(): List<Shop> = shops.value.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }
    fun get(id: Long): Shop? = shops.value.firstOrNull { it.id == id && it.deletedAt == null }
    fun byName(name: String): Shop? {
        val key = name.trim()
        return active().firstOrNull { it.name.trim().equals(key, ignoreCase = true) }
    }
    fun default(): Shop? = active().firstOrNull { it.isDefault }

    fun upsert(shop: Shop) {
        val now = System.currentTimeMillis()
        var list = shops.value.filter { it.id != shop.id }
        // Single-default invariant: promoting one shop to default demotes the others.
        if (shop.isDefault) list = list.map { if (it.isDefault && it.deletedAt == null) it.copy(isDefault = false, updatedAt = now) else it }
        shops.value = list + shop.copy(updatedAt = now)
        save()
    }

    fun delete(id: Long) {
        val s = shops.value.firstOrNull { it.id == id } ?: return
        // Soft-delete (tombstone) + drop the default flag so no ghost default survives.
        upsert(s.copy(deletedAt = System.currentTimeMillis(), isDefault = false))
    }

    fun setDefault(id: Long) {
        val now = System.currentTimeMillis()
        shops.value = shops.value.map { it.copy(isDefault = (it.id == id && it.deletedAt == null), updatedAt = now) }
        save()
    }

    fun replaceAll(newShops: List<Shop>) {
        shops.value = newShops
        save()
    }

    /** v2.05 (N37): stamp the per-shop cooldown clock after an arrival alert. */
    fun markArriveFired(id: Long, at: Long) {
        val s = shops.value.firstOrNull { it.id == id } ?: return
        shops.value = shops.value.map { if (it.id == id) s.copy(lastArriveFired = at) else it }
        save()
    }
}

// ---------------------------------------------------------------- v1.90 shop-mode stores
// Same house pattern as ShopStore: JSON file in filesDir, heal on load, soft-delete, Dirty flag.
// v1.90 scope note: these three are LOCAL-ONLY (no Firestore collection yet) — cloud transport
// is a queued follow-up so this release needs zero console steps from Krishna.

object CityStore {
    val cities = MutableStateFlow<List<City>>(emptyList())
    private val file get() = File(Stores.appContext.filesDir, "cities.json")

    fun load() {
        cities.value = runCatching {
            gson.fromJson(file.readText(), Array<City>::class.java).toList().map(::healCity)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(gson.toJson(cities.value)) }
            .onFailure { Logger.e(Stores.appContext, "CITY", it, "cities save failed") }
        Dirty.flag = true
    }

    fun active(): List<City> = cities.value.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }
    fun get(id: Long): City? = cities.value.firstOrNull { it.id == id && it.deletedAt == null }

    fun upsert(city: City) {
        cities.value = cities.value.filter { it.id != city.id } + city.copy(updatedAt = System.currentTimeMillis())
        save()
    }

    /** Soft-delete the city; its shops move to the Unassigned bucket (cityId nulled). */
    fun delete(id: Long) {
        val c = cities.value.firstOrNull { it.id == id } ?: return
        upsert(c.copy(deletedAt = System.currentTimeMillis()))
        ShopStore.replaceAll(shopsAfterCityDelete(ShopStore.shops.value, id))
    }

    fun replaceAll(newCities: List<City>) { cities.value = newCities; save() }
}

object ChainStore {
    val chains = MutableStateFlow<List<Chain>>(emptyList())
    private val file get() = File(Stores.appContext.filesDir, "chains.json")

    fun load() {
        chains.value = runCatching {
            gson.fromJson(file.readText(), Array<Chain>::class.java).toList().map(::healChain)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(gson.toJson(chains.value)) }
            .onFailure { Logger.e(Stores.appContext, "CHAIN", it, "chains save failed") }
        Dirty.flag = true
    }

    fun active(): List<Chain> = chains.value.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }
    fun get(id: Long): Chain? = chains.value.firstOrNull { it.id == id && it.deletedAt == null }

    fun upsert(chain: Chain) {
        chains.value = chains.value.filter { it.id != chain.id } + chain.copy(updatedAt = System.currentTimeMillis())
        save()
    }

    /**
     * Soft-delete the chain. [keepBranches] true → its branch shops survive as LOCAL shops
     * (chainId nulled); false → the branches are soft-deleted too (default flag dropped).
     */
    fun delete(id: Long, keepBranches: Boolean) {
        val c = chains.value.firstOrNull { it.id == id } ?: return
        upsert(c.copy(deletedAt = System.currentTimeMillis()))
        ShopStore.replaceAll(shopsAfterChainDelete(ShopStore.shops.value, id, keepBranches, System.currentTimeMillis()))
    }
}

object ProductStore {
    val products = MutableStateFlow<List<Product>>(emptyList())
    val links = MutableStateFlow<List<ProductLink>>(emptyList())
    private val pFile get() = File(Stores.appContext.filesDir, "products.json")
    private val lFile get() = File(Stores.appContext.filesDir, "productlinks.json")

    fun load() {
        products.value = runCatching {
            gson.fromJson(pFile.readText(), Array<Product>::class.java).toList().map(::healProduct)
        }.getOrDefault(emptyList())
        links.value = runCatching {
            gson.fromJson(lFile.readText(), Array<ProductLink>::class.java).toList().map(::healLink)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save() {
        runCatching { pFile.writeText(gson.toJson(products.value)) }
            .onFailure { Logger.e(Stores.appContext, "PRODUCT", it, "products save failed") }
        runCatching { lFile.writeText(gson.toJson(links.value)) }
            .onFailure { Logger.e(Stores.appContext, "PRODUCT", it, "product links save failed") }
        Dirty.flag = true
    }

    fun active(): List<Product> = products.value.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }
    fun get(id: Long): Product? = products.value.firstOrNull { it.id == id && it.deletedAt == null }
    fun categories(): List<String> =
        active().mapNotNull { it.category }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun linksFor(productId: Long): List<ProductLink> =
        links.value.filter { it.productId == productId && it.deletedAt == null }

    fun upsertProduct(p: Product) {
        products.value = products.value.filter { it.id != p.id } + p.copy(updatedAt = System.currentTimeMillis())
        save()
    }

    /** Soft-delete a product and its links. Buy items keep their title (link goes stale safely). */
    fun deleteProduct(id: Long) {
        val p = products.value.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        products.value = products.value.filter { it.id != id } + p.copy(deletedAt = now, updatedAt = now)
        links.value = links.value.map { if (it.productId == id) it.copy(deletedAt = it.deletedAt ?: now) else it }
        save()
    }

    /** Turn a product↔shop link on/off; optional price fields when turning on/editing. */
    fun setLink(productId: Long, shopId: Long, on: Boolean, price: Double? = null, unitPrice: Double? = null) {
        val now = System.currentTimeMillis()
        val rest = links.value.filter { !(it.productId == productId && it.shopId == shopId) }
        val prev = links.value.firstOrNull { it.productId == productId && it.shopId == shopId }
        links.value = if (on) {
            rest + ProductLink(
                productId = productId, shopId = shopId,
                lastPrice = price ?: prev?.lastPrice ?: 0.0,
                lastUnitPrice = unitPrice ?: prev?.lastUnitPrice ?: 0.0,
                lastAt = if (price != null || unitPrice != null) now else (prev?.lastAt ?: 0L),
                deletedAt = null
            )
        } else {
            rest + (prev ?: return).copy(deletedAt = now)
        }
        save()
    }

    /** Checkout write-back: remember what this product last cost at this shop. */
    fun recordPrice(productId: Long, shopId: Long, price: Double, unitPrice: Double, at: Long) {
        if (price <= 0.0 && unitPrice <= 0.0) return
        val rest = links.value.filter { !(it.productId == productId && it.shopId == shopId) }
        val prev = links.value.firstOrNull { it.productId == productId && it.shopId == shopId }
        links.value = rest + mergedLinkPrice(prev, productId, shopId, price, unitPrice, at)
        save()
    }
}

// ---------------------------------------------------------------- settings

object SettingsStore {
    val s = MutableStateFlow(AppSettings())
    private val file get() = File(Stores.appContext.filesDir, "settings.json")

    fun load() {
        val parsed = runCatching {
            gson.fromJson(file.readText(), AppSettings::class.java)
        }.onFailure {
            it.printStackTrace()
            runCatching { Logger.e(Stores.appContext, "LOAD", it, "settings.json failed to parse") }
        }.getOrNull() ?: AppSettings()
        s.value = migrate(healSettings(parsed))
    }

    /** Old settings files miss new fields (Gson leaves them false/0); fix them up once. */
    internal fun migrate(a: AppSettings): AppSettings {
        var s = a
        if (s.ver < 3) {
            val allowedDelays = listOf(0, 1, 3, 5, 7, 10, 12, 15, 18, 20)
            s = s.copy(
                navSwipeRightNext = true,
                gestureCardSwipe = true,
                gesturePageSwipe = true,
                gestureNavSwipe = true,
                moveDelaySec = if (s.moveDelaySec in allowedDelays) s.moveDelaySec else 20
            )
        }
        if (s.ver < 4) {
            s = s.copy(
                cardSwipeRightDone = true,
                pageSwipeRightDone = true,
                density = "COMFY",
                shopGroupByGroup = false,
                learnGroupByTopic = false,
                fsNav = 1f, fsCardTitle = 1f, fsCardDetail = 1f,
                fsGroupHeader = 1f, fsScreenHeader = 1f, fsButtons = 1f
            )
        }
        if (s.ver < 5) {
            // v1.5: new direction defaults apply even to imported backups; delay caps at 7 s.
            s = s.copy(
                pageSwipeRightDone = false,
                navSwipeRightNext = false,
                moveDelaySec = s.moveDelaySec.coerceAtMost(7),
                activeSwipeAction = "MOVE",
                doneSwipeAction = "MOVE",
                deleteStyle = "UNDO"
            )
        }
        if (s.ver < 6) {
            s = s.copy(
                tasksGroupByGroup = false,
                tasksGroups = emptyList(), shopGroups = emptyList(), learnTopics = emptyList(),
                groupsSeeded = false,
                defaultDueMinutes = 1080, callReminderMinutes = 1080
            )
        }
        if (s.ver < 7) {
            // v1.7: four presets → one percentage. 80% reproduces Comfortable exactly.
            s = s.copy(
                densityPct = when (s.density) {
                    "XTIGHT" -> 0.10f; "TIGHT" -> 0.30f; "COMPACT" -> 0.55f; else -> 0.80f
                }
            )
        }
        var out = s
        if (out.ver < 9) {
            // v1.9: per-tab Done delay + gestures inherit today's global values.
            out = out.copy(
                ver = 9,
                tasksMoveDelaySec = out.moveDelaySec, shopMoveDelaySec = out.moveDelaySec,
                learnMoveDelaySec = out.moveDelaySec, callsMoveDelaySec = out.moveDelaySec,
                tCardSwipe = out.gestureCardSwipe, tCardRightDone = out.cardSwipeRightDone,
                tPageSwipe = out.gesturePageSwipe, tPageRightDone = out.pageSwipeRightDone,
                tActiveAction = out.activeSwipeAction, tDoneAction = out.doneSwipeAction, tDeleteStyle = out.deleteStyle,
                sCardSwipe = out.gestureCardSwipe, sCardRightDone = out.cardSwipeRightDone,
                sPageSwipe = out.gesturePageSwipe, sPageRightDone = out.pageSwipeRightDone,
                sActiveAction = out.activeSwipeAction, sDoneAction = out.doneSwipeAction, sDeleteStyle = out.deleteStyle,
                lCardSwipe = out.gestureCardSwipe, lCardRightDone = out.cardSwipeRightDone,
                lPageSwipe = out.gesturePageSwipe, lPageRightDone = out.pageSwipeRightDone,
                lActiveAction = out.activeSwipeAction, lDoneAction = out.doneSwipeAction, lDeleteStyle = out.deleteStyle,
                cCardSwipe = out.gestureCardSwipe, cCardRightDone = out.cardSwipeRightDone,
                cPageSwipe = out.gesturePageSwipe, cPageRightDone = out.pageSwipeRightDone,
                cActiveAction = out.activeSwipeAction, cDoneAction = out.doneSwipeAction, cDeleteStyle = out.deleteStyle
            )
        }
        if (out.ver < 10) {
            // v1.10 inherit-from-global: seed the global defaults, then collapse
            // per-tab values equal to them into Inherit (delay/clear: -1, prefill: "INHERIT",
            // gestures: absent override-map key). Explicit divergences stay explicit.
            var m = out.copy(
                ver = 10,
                globalDoneClearDays = out.tasksDoneClearDays,
                globalNewDueMode = out.tasksNewDueMode,
                globalNewDueDays = out.tasksNewDueDays,
                globalNewDueMinutes = out.tasksNewDueMinutes
            )
            fun dl(v: Int) = if (v == m.moveDelaySec) -1 else v
            fun cl(v: Int) = if (v == m.globalDoneClearDays) -1 else v
            m = m.copy(
                tasksMoveDelaySec = dl(m.tasksMoveDelaySec), shopMoveDelaySec = dl(m.shopMoveDelaySec),
                learnMoveDelaySec = dl(m.learnMoveDelaySec), callsMoveDelaySec = dl(m.callsMoveDelaySec),
                tasksDoneClearDays = cl(m.tasksDoneClearDays), shopDoneClearDays = cl(m.shopDoneClearDays),
                learnDoneClearDays = cl(m.learnDoneClearDays), callsDoneClearDays = cl(m.callsDoneClearDays)
            )
            fun sameDue(mode: String, d: Int, mi: Int) =
                mode == m.globalNewDueMode && d == m.globalNewDueDays && mi == m.globalNewDueMinutes
            if (sameDue(m.tasksNewDueMode, m.tasksNewDueDays, m.tasksNewDueMinutes)) m = m.copy(tasksNewDueMode = "INHERIT")
            if (sameDue(m.shopNewDueMode, m.shopNewDueDays, m.shopNewDueMinutes)) m = m.copy(shopNewDueMode = "INHERIT")
            if (sameDue(m.learnNewDueMode, m.learnNewDueDays, m.learnNewDueMinutes)) m = m.copy(learnNewDueMode = "INHERIT")
            val ov = mutableMapOf<String, String>()
            fun bOv(p: String, key: String, v: Boolean?, g: Boolean) {
                val vv = v ?: g
                if (vv != g) ov[p + key] = if (vv) "ON" else "OFF"
            }
            fun tOv(p: String, key: String, v: String?, g: String) {
                val vv = v ?: g
                if (vv != g) ov[p + key] = vv
            }
            listOf(
                Triple("t", listOf(m.tCardSwipe, m.tCardRightDone, m.tPageSwipe, m.tPageRightDone), listOf(m.tActiveAction, m.tDoneAction, m.tDeleteStyle)),
                Triple("s", listOf(m.sCardSwipe, m.sCardRightDone, m.sPageSwipe, m.sPageRightDone), listOf(m.sActiveAction, m.sDoneAction, m.sDeleteStyle)),
                Triple("l", listOf(m.lCardSwipe, m.lCardRightDone, m.lPageSwipe, m.lPageRightDone), listOf(m.lActiveAction, m.lDoneAction, m.lDeleteStyle)),
                Triple("c", listOf(m.cCardSwipe, m.cCardRightDone, m.cPageSwipe, m.cPageRightDone), listOf(m.cActiveAction, m.cDoneAction, m.cDeleteStyle))
            ).forEach { (p, bools, strs) ->
                bOv(p, "CardSwipe", bools[0], m.gestureCardSwipe)
                bOv(p, "CardRightDone", bools[1], m.cardSwipeRightDone)
                bOv(p, "PageSwipe", bools[2], m.gesturePageSwipe)
                bOv(p, "PageRightDone", bools[3], m.pageSwipeRightDone)
                tOv(p, "ActiveAction", strs[0], m.activeSwipeAction)
                tOv(p, "DoneAction", strs[1], m.doneSwipeAction)
                tOv(p, "DeleteStyle", strs[2], m.deleteStyle)
            }
            out = m.copy(tabGestureOv = ov)
        }
        if (out.ver < 11) {
            fun sortFrom(byGroup: Boolean) = if (byGroup) "GROUP" else "DATE"
            out = out.copy(
                ver = 11,
                tasksSort = sortFrom(out.tasksGroupByGroup),
                shopSort = sortFrom(out.shopGroupByGroup),
                learnSort = sortFrom(out.learnGroupByTopic)
            )
        }
        if (out.ver < 12) out = out.copy(ver = 12)
        if (out.ver < 13) out = out.copy(ver = 13)
        if (out.ver < 14) out = out.copy(ver = 14)
        // v1.20 item 8: calendar sync starts fully off; events written under the old
        // defaults are torn down at startup (see RemindlyApp).
        if (out.ver < 15) out = out.copy(
            ver = 15, calSyncTasks = false, calSyncShop = false, calSyncLearn = false, calSyncCalls = false
        )
        // v1.21 item 1: birthdays removed — existing birthday reminders survive as ordinary
        // yearly recurring calls (the dropped field is simply ignored by Gson).
        // v1.21 item 3: no auto-selected calendar — an unchosen target stays 0.
        if (out.ver < 16) out = out.copy(ver = 16)
        // v1.22: new settings fields only — existing repeats stay unbounded by design.
        if (out.ver < 17) out = out.copy(ver = 17)
        if (out.ver < 18) out = out.copy(ver = 18)
        // v1.32: reverse-swipe delete added (default off). The old "swipe does Move/Delete"
        // setting is retired — reset the globals to MOVE and strip the per-tab
        // ActiveAction/DoneAction overrides so no stale delete-on-move survives.
        if (out.ver < 19) out = out.copy(
            ver = 19,
            activeSwipeAction = "MOVE",
            doneSwipeAction = "MOVE",
            tabGestureOv = out.tabGestureOv.filterKeys {
                !it.endsWith("ActiveAction") && !it.endsWith("DoneAction")
            }
        )
        // v1.35: new sync fields only (cloudSync off, settings stamp 0) — no data transform.
        if (out.ver < 20) out = out.copy(ver = 20)
        // v1.41: new visibility fields only (all default true) — no data transform.
        if (out.ver < 21) out = out.copy(ver = 21)
        // v1.44: new card-layout fields only (defaults) — no data transform.
        if (out.ver < 22) out = out.copy(ver = 22)
        // v1.45: calendarReadDays default only — no data transform.
        if (out.ver < 23) out = out.copy(ver = 23)
        // v1.46 Feature 4a: calendar writing is disabled — clear any stale "sync on" state so
        // nothing looks enabled. calendarTargetId is KEPT (the read-only view reads from it).
        if (out.ver < 24) out = out.copy(ver = 24, calendarSync = false)
        // v1.47: new-item due-time fields only (defaults keep current behaviour).
        if (out.ver < 25) out = out.copy(ver = 25)
        // v1.48: calendar window unit/months + Shop.area — defaults only, no transform.
        if (out.ver < 26) out = out.copy(ver = 26)
        // v1.49: calendar account+ids added. The account is derived from the legacy
        // calendarTargetId lazily (needs a provider query) — see CalSync.migrateSelectionIfNeeded.
        if (out.ver < 27) out = out.copy(ver = 27)
        // v1.50: calendarNone flag added (default false = unchanged behaviour).
        if (out.ver < 28) out = out.copy(ver = 28)
        // v1.52: callIncludeRejected added (default false = unchanged behaviour).
        if (out.ver < 29) out = out.copy(ver = 29)
        // v1.56: alerts phase 1 fields (defaults preserve behaviour); call ladder -> rules.
        if (out.ver < 30) out = out.copy(ver = 30)
        // v1.57: type-set fields (legacy style tokens retired in v1.69).
        if (out.ver < 31) out = out.copy(ver = 31)
        // v1.58: per-item extra-alert rules; "" defaults keep behaviour identical.
        if (out.ver < 32) out = out.copy(ver = 32)
        // v1.86 (N20/N25): missedAt on Item + schedule-kind defaults — pure additions with safe
        // json defaults; the bump only records that this build understands them.
        if (out.ver < 33) out = out.copy(ver = 33)
        // v1.87 (N17): sharing switches — pure additions with safe defaults.
        if (out.ver < 34) out = out.copy(ver = 34)
        // v1.90: two-mode layout + shop-mode entities. New fields are pure defaults (healed);
        // the one-time city seeding runs in Stores.init, not here — migrate stays pure.
        if (out.ver < 35) out = out.copy(ver = 35)
        // v2.00 (N31): layoutMode + showShop deleted; unknown keys in old JSON are ignored by
        // Gson, so a v1.90 device that was in Classic simply comes back as dual-mode.
        if (out.ver < 36) out = out.copy(ver = 36)
        // v2.02 (N33): mapProvider / geoLimit / indiaBilling — pure defaults, healed on load.
        if (out.ver < 37) out = out.copy(ver = 37)
        // v2.04 (N36): startMode deleted — the ☰ drawer owns the mode (always last used).
        if (out.ver < 38) out = out.copy(ver = 38)
        // v2.05 (N37): per-shop arrival alerts (default set + cooldown) — pure defaults.
        if (out.ver < 39) out = out.copy(ver = 39)
        // v2.7 (N43): group-header checkbox switches — pure defaults, healed.
        if (out.ver < 40) out = out.copy(ver = 40)
        // v2.8 (N44): ALERT_MUTED item state — no settings field, ver stamp only.
        if (out.ver < 41) out = out.copy(ver = 41)
        // v2.9 (N47/N45): updater + shopping-list settings — pure defaults, healed.
        if (out.ver < 42) out = out.copy(ver = 42)
        // v1.48: calendar window unit/months + Shop.area + discrete radius — defaults only.
        return out
    }

    @Synchronized
    private fun save() {
        runCatching { file.writeText(gson.toJson(s.value)) }
        Dirty.flag = true
    }

    /** Records the sync time without re-marking the data dirty. */
    fun silentLastSync(ts: Long) {
        s.value = s.value.copy(lastSyncAt = ts)
        runCatching { file.writeText(gson.toJson(s.value)) }
    }

    fun update(persist: Boolean = true, transform: (AppSettings) -> AppSettings) {
        s.value = transform(s.value).copy(settingsUpdatedAt = System.currentTimeMillis())
        if (persist) save()
    }

    /** v1.35: apply a settings doc pulled from the cloud WITHOUT re-bumping the stamp (avoids a push echo). */
    fun applyRemote(settings: AppSettings) {
        s.value = settings
        save()
    }

    fun persistNow() = save()

    fun replace(settings: AppSettings) {
        s.value = migrate(settings)
        save()
    }
}

// ---------------------------------------------------------------- PIN (encrypted)

/**
 * v2.02 (N33 C1/C5): on-device API keys (Google Geocoding key today). Same self-healing encrypted-
 * prefs pattern as PinStore but its OWN file and its OWN master-key alias, so a corrupt keyset here
 * never wipes the PIN store (and vice versa). Values are NEVER returned to UI, logged, exported or
 * backed up (see backup_rules / data_extraction_rules); only isSet()/set()/remove().
 */
/**
 * v2.05 (N38): the "Buy Now" view state — armed by a shop geofence arrival, hidden ONLY by the
 * user (Krishna's rule), replaced by the most recent trigger. Per-device view state in UiStore:
 * never synced, but it does survive restarts and reboots until hidden.
 */
object BuyNow {
    fun arm(context: Context, shopId: Long) {
        runCatching {
            UiStore.update { it.copy(buyNowShopId = shopId, buyNowAt = System.currentTimeMillis()) }
            Logger.e(context, "BUYNOW", null, "armed for shop $shopId")
        }.onFailure { Logger.e(context, "BUYNOW", it, "arm failed") }
    }

    fun hide(context: Context) {
        runCatching {
            UiStore.update { it.copy(buyNowShopId = null, buyNowAt = 0L) }
            Logger.e(context, "BUYNOW", null, "hidden by the user")
        }.onFailure { Logger.e(context, "BUYNOW", it, "hide failed") }
    }

    /** The armed shop, or null — auto-hides (and logs) when its shop is gone. */
    fun shop(context: Context): Shop? {
        val id = UiStore.s.value.buyNowShopId ?: return null
        val shop = ShopStore.get(id)
        if (shop == null) {
            runCatching {
                UiStore.update { it.copy(buyNowShopId = null, buyNowAt = 0L) }
                Logger.e(context, "BUYNOW", null, "auto-hidden: shop $id no longer exists")
            }
            return null
        }
        return shop
    }
}

object KeyStore {
    const val GOOGLE_GEOCODING = "google_geocoding"
    private const val FILE = "remindly_keys"
    private const val MASTER_ALIAS = "remindly_keys_master"

    @Volatile private var degraded = false
    @Volatile private var cached: android.content.SharedPreferences? = null

    private fun build(): android.content.SharedPreferences {
        val key = MasterKey.Builder(Stores.appContext, MASTER_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            Stores.appContext, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun wipe(context: Context) {
        runCatching {
            val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            java.io.File(dir, "$FILE.xml").delete(); java.io.File(dir, "$FILE.xml.bak").delete()
        }.onFailure { Logger.e(context, "KEYS", it, "key prefs wipe failed") }
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(MASTER_ALIAS)) ks.deleteEntry(MASTER_ALIAS)
        }.onFailure { Logger.e(context, "KEYS", it, "key master alias wipe failed") }
    }

    private fun prefsOrNull(): android.content.SharedPreferences? {
        cached?.let { return it }
        if (degraded) return null
        val ctx = Stores.appContext
        runCatching { build() }.onSuccess { cached = it; return it }
            .onFailure { first ->
                Logger.e(ctx, "KEYS", first, "key store open failed — wiping and retrying once")
                wipe(ctx)
                runCatching { build() }.onSuccess { cached = it; return it }
                    .onFailure { second ->
                        degraded = true
                        Logger.e(ctx, "KEYS", second, "key store unavailable — degraded (keys treated as not set)")
                    }
            }
        return null
    }

    fun isSet(name: String): Boolean = runCatching { !prefsOrNull()?.getString(name, null).isNullOrBlank() }.getOrDefault(false)

    /** Internal use only (Geo.kt) — never surface the value in UI or logs. */
    internal fun valueOrNull(name: String): String? = runCatching { prefsOrNull()?.getString(name, null)?.takeIf { it.isNotBlank() } }.getOrNull()

    fun set(name: String, value: String): Boolean = runCatching {
        val p = prefsOrNull() ?: return false
        p.edit().putString(name, value.trim()).commit()
    }.onFailure { Logger.e(Stores.appContext, "KEYS", it, "key save failed ($name)") }.getOrDefault(false)

    fun remove(name: String): Boolean = runCatching {
        val p = prefsOrNull() ?: return false
        p.edit().remove(name).commit()
    }.onFailure { Logger.e(Stores.appContext, "KEYS", it, "key remove failed ($name)") }.getOrDefault(false)
}

object PinStore {
    private const val FILE = "remindly_pin"
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    /** v1.22 item 10: true once both creation attempts have failed — the store degrades
     *  honestly instead of throwing into the UI. */
    @Volatile private var degraded = false
    @Volatile private var cached: android.content.SharedPreferences? = null

    private fun build(): android.content.SharedPreferences {
        val key = MasterKey.Builder(Stores.appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            Stores.appContext,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Wipes a keyset that can no longer be decrypted: the prefs file, its backup, and the
     *  Keystore alias. Called only after a decrypt failure — see wipeReason(). */
    internal fun wipeCorruptState(context: Context) {
        runCatching {
            val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            java.io.File(dir, "$FILE.xml").delete()
            java.io.File(dir, "$FILE.xml.bak").delete()
        }.onFailure { Logger.e(context, "pin", it, "prefs wipe failed") }
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
        }.onFailure { Logger.e(context, "pin", it, "master key wipe failed") }
    }

    /** True when the throwable means "this keyset cannot be decrypted" — the AEADBadTagException
     *  family Krishna hit after an Auto-Backup restore, plus its keystore/IO cousins. */
    internal fun isUnrecoverable(t: Throwable): Boolean =
        t is javax.crypto.AEADBadTagException ||
            t is java.security.GeneralSecurityException ||
            t is java.io.IOException ||
            t is IllegalArgumentException ||
            t is IllegalStateException ||
            (t.cause?.let { isUnrecoverable(it) } ?: false)

    /** Guarded, re-entrant accessor. A failed attempt never poisons later ones (the old
     *  `by lazy` re-threw forever), and a corrupt store repairs itself on first touch. */
    private fun prefsOrNull(): android.content.SharedPreferences? {
        cached?.let { return it }
        if (degraded) return null
        val ctx = Stores.appContext
        runCatching { build() }
            .onSuccess { cached = it; return it }
            .onFailure { first ->
                Logger.e(ctx, "pin", first, "encrypted prefs unreadable — repairing")
                if (!isUnrecoverable(first)) { degraded = true; return null }
                wipeCorruptState(ctx)
                runCatching { build() }
                    .onSuccess { cached = it; return it }
                    .onFailure { second ->
                        Logger.e(ctx, "pin", second, "repair failed — PIN storage degraded")
                        degraded = true
                    }
            }
        return null
    }

    /** For the UI: personal lock cannot be stored right now. */
    fun isDegraded(): Boolean = degraded

    private fun sha256(input: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun isSet(): Boolean = runCatching { prefsOrNull()?.contains("hash") == true }.getOrDefault(false)

    /** Returns false instead of throwing — every caller handles the failure honestly. */
    /**
     * v1.71 (N1): PBKDF2-HMAC-SHA256. SHA-256 is deliberately fast — a whole 4-digit keyspace
     * (10,000 values) falls in well under a second. Stretching the same primitive over
     * [PIN_ITERS] rounds makes each guess ~100 ms, so the same sweep costs hours instead. One
     * legitimate unlock pays that cost once and never notices it.
     */
    private const val PIN_ITERS = 120_000
    private const val PIN_FORMAT = 2

    private fun pbkdf2(pin: String, salt: String, iters: Int): String = runCatching {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(), iters, 256)
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }.onFailure { Logger.e(Stores.appContext, "pin", it, "pbkdf2 failed — falling back to legacy hash") }
        .getOrDefault("")

    fun setPin(pin: String): Boolean = runCatching {
        if (pin.isBlank()) return false
        val p = prefsOrNull() ?: return false
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        val strong = pbkdf2(pin, salt, PIN_ITERS)
        if (strong.isNotBlank()) {
            p.edit().putString("salt", salt).putString("hash", strong)
                .putInt("fmt", PIN_FORMAT).putInt("iters", PIN_ITERS).commit()
        } else {
            // Degrade honestly rather than refuse to set a PIN at all.
            val legacy = sha256(salt + pin)
            if (legacy.isBlank()) return false
            Logger.e(Stores.appContext, "pin", null, "PBKDF2 unavailable — stored legacy hash")
            p.edit().putString("salt", salt).putString("hash", legacy).remove("fmt").remove("iters").commit()
        }
    }.onFailure { Logger.e(Stores.appContext, "pin", it, "setPin failed") }.getOrDefault(false)

    fun check(pin: String): Boolean = runCatching {
        val p = prefsOrNull() ?: return false
        val salt = p.getString("salt", null) ?: return false
        val hash = p.getString("hash", null) ?: return false
        val fmt = p.getInt("fmt", 1)
        if (fmt >= PIN_FORMAT) {
            pbkdf2(pin, salt, p.getInt("iters", PIN_ITERS)) == hash
        } else {
            // Legacy salted SHA-256: verify, then silently upgrade so nobody is ever locked out.
            val ok = hash == sha256(salt + pin)
            if (ok) {
                Logger.e(Stores.appContext, "pin", null, "legacy PIN verified — upgrading to PBKDF2")
                setPin(pin)
            }
            ok
        }
    }.onFailure { Logger.e(Stores.appContext, "pin", it, "check failed") }.getOrDefault(false)

    /** Forget the PIN entirely (used by Reset and by the degraded-recovery path). */
    fun clear(): Boolean = runCatching {
        prefsOrNull()?.edit()?.remove("salt")?.remove("hash")?.commit() ?: false
    }.onFailure { Logger.e(Stores.appContext, "pin", it, "clear failed") }.getOrDefault(false)
}

// ---------------------------------------------------------------- backup

// ---------------------------------------------------------------- v1.10 error log
// Errors and exceptions ONLY — never info/debug noise. Written where the person can
// actually reach it: Documents/Remindly/remindly-errors.log (Android 8-9: app storage).

object Logger {
    private const val NAME = "remindly-errors.log"
    private const val MAX = 200_000
    const val SHOWN_PATH = "Documents/Remindly/remindly-errors.log"

    fun e(context: Context, tag: String, thr: Throwable? = null, msg: String? = null) = runCatching {
        val sb = StringBuilder()
        sb.append("[").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            .append("] v").append(appVersion(context)).append(" · Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" · ").append(tag)
        msg?.let { sb.append(" · ").append(it) }
        sb.append("\n")
        thr?.let { sb.append(android.util.Log.getStackTraceString(it)).append("\n") }
        sb.append("\n")
        append(context, sb.toString())
    }.let { }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun append(context: Context, entry: String) {
        val current = readAll(context)
        val next = (current + entry).let { if (it.length > MAX) it.takeLast(MAX / 2) else it }
        write(context, next)
    }

    fun readAll(context: Context): String = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            uriOf(context)?.let { uri ->
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } ?: ""
        } else legacyFile(context).takeIf { it.exists() }?.readText() ?: ""
    }.getOrDefault("")

    fun clear(context: Context) = runCatching {
        write(context, "")
        pendingFile(context).delete()
    }.let { }

    // ---------------- v1.22 item 11: crash capture ----------------

    /** A private, plain file — no MediaStore, no ContentResolver, no permissions — so a dying
     *  process can finish the write. The visible log is merged from it on the next launch. */
    internal fun pendingFile(context: Context): java.io.File =
        java.io.File(context.filesDir, "pending-crash.log")

    /** Formats one crash entry. Kept pure and total: a null message, a null cause, an empty
     *  stack or a monstrous message must all produce something readable, never a second crash. */
    fun crashEntry(context: Context, thr: Throwable, thread: String): String = runCatching {
        val sb = StringBuilder()
        sb.append("[").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            .append("] CRASH · v").append(appVersion(context))
            .append(" · Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")")
            .append(" · ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
            .append(" · thread ").append(thread).append("\n")
        sb.append("Where: ").append(crashLocation(thr)).append("\n")
        sb.append(thr.javaClass.name)
        thr.message?.take(2000)?.let { sb.append(": ").append(it) }
        sb.append("\n").append(android.util.Log.getStackTraceString(thr)).append("\n\n")
        sb.toString()
    }.getOrElse { "[?] CRASH · entry could not be formatted\n\n" }

    /** The topmost frame inside the app — what Krishna sees as "where it happened". */
    fun crashLocation(thr: Throwable?): String = runCatching {
        var t: Throwable? = thr
        val seen = HashSet<Throwable>()
        while (t != null && seen.add(t)) {
            t.stackTrace?.firstOrNull { it.className.startsWith("com.krishna.remindly") }?.let { f ->
                val cls = f.className.substringAfterLast('.').substringBefore('$')
                return "$cls.${f.methodName} (${f.fileName ?: "?"}:${f.lineNumber})"
            }
            t = t.cause
        }
        "unknown location"
    }.getOrDefault("unknown location")

    fun writePending(context: Context, text: String) = runCatching {
        pendingFile(context).appendText(text)
    }.let { }

    /** Called at startup: fold any captured crash into the visible log, then drop the file. */
    fun mergePending(context: Context) = runCatching {
        val f = pendingFile(context)
        if (!f.exists()) return@runCatching
        val text = runCatching { f.readText() }.getOrDefault("")
        if (text.isNotBlank()) append(context, text)
        f.delete()
    }.let { }

    fun shareUri(context: Context): android.net.Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 29) uriOf(context) else null

    private fun write(context: Context, content: String) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val uri = uriOf(context) ?: run {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Remindly/")
                }
                resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values)
            } ?: return
            resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
        } else {
            legacyFile(context).apply { parentFile?.mkdirs() }.writeText(content)
        }
    }

    private fun uriOf(context: Context): android.net.Uri? = runCatching {
        val resolver = context.contentResolver
        resolver.query(
            android.provider.MediaStore.Files.getContentUri("external"),
            arrayOf(android.provider.MediaStore.MediaColumns._ID),
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
            arrayOf(NAME, "Documents/Remindly/"), null
        )?.use { cur ->
            if (cur.moveToFirst()) android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Files.getContentUri("external"), cur.getLong(0)
            ) else null
        }
    }.getOrNull()

    private fun legacyFile(context: Context) =
        java.io.File(java.io.File(context.getExternalFilesDir(null), "log"), NAME)
}

object Backup {

    // ---------------- v1.9: split backups ----------------
    data class DataBlob(
        val kind: String = "remindly-data",
        val items: List<Item> = emptyList(),
        val calls: List<CallReminder> = emptyList(),
        val places: List<GeoPlace> = emptyList(),
        val tasksGroups: List<String> = emptyList(),
        val shopGroups: List<String> = emptyList(),
        val learnTopics: List<String> = emptyList()
    )

    data class SettingsBlob(val kind: String = "remindly-settings", val settings: AppSettings)

    fun exportDataJson(): String {
        val s = SettingsStore.s.value
        return gson.toJson(
            DataBlob(
                items = ItemStore.items.value, calls = CallStore.calls.value,
                places = PlaceStore.places.value,
                tasksGroups = s.tasksGroups, shopGroups = s.shopGroups, learnTopics = s.learnTopics
            )
        )
    }

    fun exportSettingsJson(): String = gson.toJson(SettingsBlob(settings = SettingsStore.s.value))

    /**
     * v1.34 (sync groundwork, Phase 1b will use this): the settings payload that is safe to sync
     * across devices. Device-local sync/transient bookkeeping is zeroed so it never clobbers the
     * other device. (The Personal PIN and OS permission state are NOT in AppSettings — they live
     * in EncryptedSharedPreferences / the OS — so they can never leak through here.)
     */
    fun settingsForSync(s: AppSettings): AppSettings = s.copy(
        cloudSync = false,
        lastSyncAt = 0L,
        lastDataBackupAt = 0L
    )

    private fun stamp(u: Long, c: Long) = if (u > 0) u else c

    /** Merge-import: nothing existing is deleted; duplicate ids resolve latest-wins. */
    fun mergeData(context: Context, blob: DataBlob) {
        val curItems = ItemStore.items.value.associateBy { it.id }.toMutableMap()
        blob.items.forEach { inc ->
            val ex = curItems[inc.id]
            if (ex == null || stamp(inc.updatedAt, inc.createdAt) >= stamp(ex.updatedAt, ex.createdAt)) curItems[inc.id] = inc
        }
        ItemStore.replaceAll(curItems.values.toList())
        val curCalls = CallStore.calls.value.associateBy { it.id }.toMutableMap()
        blob.calls.forEach { inc ->
            val ex = curCalls[inc.id]
            if (ex == null || stamp(inc.updatedAt, inc.createdAt) >= stamp(ex.updatedAt, ex.createdAt)) curCalls[inc.id] = inc
        }
        CallStore.replaceAll(curCalls.values.toList())
        val curPlaces = PlaceStore.places.value.associateBy { it.id }.toMutableMap()
        blob.places.forEach { inc ->
            // v1.35: full LWW like items/calls (was additive-only) — so place edits AND deletes
            // (deletedAt tombstones, which carry a fresh updatedAt) now propagate across devices.
            val ex = curPlaces[inc.id]
            if (ex == null || stamp(inc.updatedAt, 0L) >= stamp(ex.updatedAt, 0L)) curPlaces[inc.id] = inc
        }
        PlaceStore.replaceAll(curPlaces.values.toList())
        SettingsStore.update { s ->
            s.copy(
                tasksGroups = (s.tasksGroups + blob.tasksGroups).distinct(),
                shopGroups = (s.shopGroups + blob.shopGroups).distinct(),
                learnTopics = (s.learnTopics + blob.learnTopics).distinct()
            )
        }
        AlarmScheduler.rescheduleAll(context)
        Geofencer.registerAll(context)
    }

    fun replaceData(context: Context, blob: DataBlob) {
        ItemStore.replaceAll(blob.items)
        CallStore.replaceAll(blob.calls)
        PlaceStore.replaceAll(blob.places)
        SettingsStore.update { s ->
            s.copy(tasksGroups = blob.tasksGroups, shopGroups = blob.shopGroups, learnTopics = blob.learnTopics)
        }
        AlarmScheduler.rescheduleAll(context)
        Geofencer.registerAll(context)
    }

    fun applySettings(blob: SettingsBlob) {
        val keepT = SettingsStore.s.value.tasksGroups
        val keepS = SettingsStore.s.value.shopGroups
        val keepL = SettingsStore.s.value.learnTopics
        SettingsStore.replace(blob.settings.copy(tasksGroups = keepT, shopGroups = keepS, learnTopics = keepL))
    }

    /** Kind sniffing: "remindly-data" / "remindly-settings" / legacy full blob. */
    fun sniff(json: String): String? = runCatching {
        val map = gson.fromJson(json, Map::class.java)
        when {
            map["kind"] == "remindly-data" -> "data"
            map["kind"] == "remindly-settings" -> "settings"
            map.containsKey("items") && map.containsKey("places") -> "legacy"
            else -> null
        }
    }.getOrNull()

    fun parseData(json: String): DataBlob? = runCatching {
        gson.fromJson(json, DataBlob::class.java)?.let { b ->
            b.copy(
                items = (b.items ?: emptyList()).map(::healItem),
                calls = (b.calls ?: emptyList()).map(::healCall),
                places = (b.places ?: emptyList()).map(::healPlace),
                tasksGroups = b.tasksGroups ?: emptyList(),
                shopGroups = b.shopGroups ?: emptyList(),
                learnTopics = b.learnTopics ?: emptyList()
            )
        }
    }.getOrNull()
    fun parseSettings(json: String): SettingsBlob? = runCatching { gson.fromJson(json, SettingsBlob::class.java) }.getOrNull()

    /** v1.9 auto-backup: at most once per 24 h, keep the newest 7. */
    fun autoBackupIfDue(context: Context) {
        val s = SettingsStore.s.value
        val now = System.currentTimeMillis()
        if (now - s.lastDataBackupAt < 24L * 60 * 60 * 1000) return
        val name = "remindly-data-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now)) + ".json"
        val ok = writePublicBackup(context, name, exportDataJson())
        if (ok) {
            SettingsStore.update { it.copy(lastDataBackupAt = now) }
            SettingsStore.persistNow()
            pruneOldBackups(context)
        }
    }

    private fun writePublicBackup(context: Context, name: String, content: String): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val existing = resolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                arrayOf(name, "Documents/Remindly/"), null
            )
            existing?.use { cur ->
                if (cur.moveToFirst()) {
                    val id = cur.getLong(0)
                    val uri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Files.getContentUri("external"), id
                    )
                    resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                    return@runCatching true
                }
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Remindly/")
            }
            val uri = resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            true
        } else {
            val dir = java.io.File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
            java.io.File(dir, name).writeText(content)
            true
        }
    }.onFailure { Logger.e(context, "BACKUP", it, "writePublicBackup failed") }.getOrDefault(false)

    private fun pruneOldBackups(context: Context) = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val names = mutableListOf<Pair<Long, String>>()
            resolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND " +
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                arrayOf("remindly-data-%", "Documents/Remindly/"), null
            )?.use { cur ->
                while (cur.moveToNext()) names.add(cur.getLong(0) to cur.getString(1))
            }
            names.sortedByDescending { it.second }.drop(7).forEach { (id, _) ->
                resolver.delete(
                    android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Files.getContentUri("external"), id
                    ), null, null
                )
            }
        } else {
            val dir = java.io.File(context.getExternalFilesDir(null), "backups")
            dir.listFiles { f -> f.name.startsWith("remindly-data-") }
                ?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
        }
    }.let { }

    fun exportJson(): String = gson.toJson(
        BackupBlob(
            items = ItemStore.items.value,
            places = PlaceStore.places.value,
            calls = CallStore.calls.value,
            settings = SettingsStore.s.value
        )
    )

    /** Returns the parsed blob, or null if the file is not a Remindly backup. */
    fun parse(json: String): BackupBlob? = runCatching {
        val blob = gson.fromJson(json, BackupBlob::class.java)
        if (blob?.items == null || blob.places == null) null else blob
    }.getOrNull()

    fun apply(context: Context, blob: BackupBlob) {
        ItemStore.replaceAll(blob.items)
        PlaceStore.replaceAll(blob.places)
        CallStore.replaceAll(blob.calls ?: emptyList())
        SettingsStore.replace(blob.settings)
        AlarmScheduler.rescheduleAll(context)
        Geofencer.registerAll(context)
    }
}

/** v1.5: human-readable diff for the Settings leave-confirmation popup. */
fun settingsDiff(old: AppSettings?, new: AppSettings): List<Triple<String, String, String>> {
    if (old == null) return emptyList()
    fun onOff(b: Boolean) = if (b) "On" else "Off"
    fun pct(f: Float) = "${"%.0f".format(f * 100)}%"
    fun delay(s: Int) = if (s == 0) "Off" else "${s}s"

    val out = mutableListOf<Triple<String, String, String>>()
    fun add(name: String, o: String, n: String) { if (o != n) out.add(Triple(name, o, n)) }
    add("Move-to-Done delay", delay(old.moveDelaySec), delay(new.moveDelaySec))
    add("Cards swipe", onOff(old.gestureCardSwipe), onOff(new.gestureCardSwipe))
    add("Card swipe direction", if (old.cardSwipeRightDone) "Right → Done" else "Left → Done",
        if (new.cardSwipeRightDone) "Right → Done" else "Left → Done")
    add("Active swipe does", if (old.activeSwipeAction == "DELETE") "Delete" else "Move to Done",
        if (new.activeSwipeAction == "DELETE") "Delete" else "Move to Done")
    add("Done swipe does", if (old.doneSwipeAction == "DELETE") "Delete" else "Back to Active",
        if (new.doneSwipeAction == "DELETE") "Delete" else "Back to Active")
    add("Deleting cards", if (old.deleteStyle == "CONFIRM") "Ask every time" else "Instant with UNDO",
        if (new.deleteStyle == "CONFIRM") "Ask every time" else "Instant with UNDO")
    add("Blank-space swipe", onOff(old.gesturePageSwipe), onOff(new.gesturePageSwipe))
    add("Blank-space direction", if (old.pageSwipeRightDone) "Right shows Done" else "Right shows Active",
        if (new.pageSwipeRightDone) "Right shows Done" else "Right shows Active")
    add("Bottom-bar swipe", onOff(old.gestureNavSwipe), onOff(new.gestureNavSwipe))
    add("Bottom-bar direction", if (old.navSwipeRightNext) "Right → next" else "Right → previous",
        if (new.navSwipeRightNext) "Right → next" else "Right → previous")
    add("Font — everything", pct(old.fontScale), pct(new.fontScale))
    add("Font — bottom navigation", pct(old.fsNav), pct(new.fsNav))
    add("Font — card titles", pct(old.fsCardTitle), pct(new.fsCardTitle))
    add("Font — card details", pct(old.fsCardDetail), pct(new.fsCardDetail))
    add("Font — group headers", pct(old.fsGroupHeader), pct(new.fsGroupHeader))
    add("Font — screen headers", pct(old.fsScreenHeader), pct(new.fsScreenHeader))
    add("Font — buttons & inputs", pct(old.fsButtons), pct(new.fsButtons))
    add("Card density", pct(old.densityPct), pct(new.densityPct))
    fun theme(t: String) = when (t) { "LIGHT" -> "Light"; "DARK" -> "Dark"; else -> "Follow system" }
    add("Theme", theme(old.theme), theme(new.theme))
    if (old.badges != new.badges) out.add(Triple("Overdue badges", if (old.badges) "On" else "Off", if (new.badges) "On" else "Off"))
    add("Snooze option 1", "${old.snoozeM1} min", "${new.snoozeM1} min")
    fun style(v: String) = when (v) { "SILENT" -> "Silent"; "ALARM" -> "Ringing"; "NOTIF" -> "Notification"; else -> "Inherit" }
    fun d(v: Int) = when { v < 0 -> "Inherit"; v == 0 -> "Off"; else -> "$v s" }
    if (old.tasksMoveDelaySec != new.tasksMoveDelaySec) out.add(Triple("Tasks Done delay", d(old.tasksMoveDelaySec), d(new.tasksMoveDelaySec)))
    if (old.shopMoveDelaySec != new.shopMoveDelaySec) out.add(Triple("Shop Done delay", d(old.shopMoveDelaySec), d(new.shopMoveDelaySec)))
    if (old.learnMoveDelaySec != new.learnMoveDelaySec) out.add(Triple("Learn Done delay", d(old.learnMoveDelaySec), d(new.learnMoveDelaySec)))
    if (old.callsMoveDelaySec != new.callsMoveDelaySec) out.add(Triple("Calls Done delay", d(old.callsMoveDelaySec), d(new.callsMoveDelaySec)))
    fun addStyle(b: Boolean) = if (b) "Full editor" else "Quick add"
    if (old.tasksAddFull != new.tasksAddFull) out.add(Triple("Tasks adding", addStyle(old.tasksAddFull), addStyle(new.tasksAddFull)))
    if (old.shopAddFull != new.shopAddFull) out.add(Triple("Shop adding", addStyle(old.shopAddFull), addStyle(new.shopAddFull)))
    if (old.learnAddFull != new.learnAddFull) out.add(Triple("Learn adding", addStyle(old.learnAddFull), addStyle(new.learnAddFull)))
    fun clearLabel(d: Int) = when { d < 0 -> "Inherit"; d == 0 -> "Off"; else -> "$d days" }
    if (old.tasksDoneClearDays != new.tasksDoneClearDays) out.add(Triple("Tasks Done auto-clear", clearLabel(old.tasksDoneClearDays), clearLabel(new.tasksDoneClearDays)))
    if (old.shopDoneClearDays != new.shopDoneClearDays) out.add(Triple("Shop Done auto-clear", clearLabel(old.shopDoneClearDays), clearLabel(new.shopDoneClearDays)))
    if (old.learnDoneClearDays != new.learnDoneClearDays) out.add(Triple("Learn Done auto-clear", clearLabel(old.learnDoneClearDays), clearLabel(new.learnDoneClearDays)))
    if (old.callsDoneClearDays != new.callsDoneClearDays) out.add(Triple("Calls Done auto-clear", clearLabel(old.callsDoneClearDays), clearLabel(new.callsDoneClearDays)))
    fun dueMode(m: String, d: Int) = when (m) { "INHERIT" -> "Inherit"; "OFF" -> "Off"; "TODAY" -> "Today"; "IN_N" -> "In $d days"; else -> "Tomorrow" }
    if (old.tasksNewDueMode != new.tasksNewDueMode || old.tasksNewDueDays != new.tasksNewDueDays)
        out.add(Triple("Tasks new-item due", dueMode(old.tasksNewDueMode, old.tasksNewDueDays), dueMode(new.tasksNewDueMode, new.tasksNewDueDays)))
    if (old.shopNewDueMode != new.shopNewDueMode || old.shopNewDueDays != new.shopNewDueDays)
        out.add(Triple("Shop new-item due", dueMode(old.shopNewDueMode, old.shopNewDueDays), dueMode(new.shopNewDueMode, new.shopNewDueDays)))
    if (old.learnNewDueMode != new.learnNewDueMode || old.learnNewDueDays != new.learnNewDueDays)
        out.add(Triple("Learn new-item due", dueMode(old.learnNewDueMode, old.learnNewDueDays), dueMode(new.learnNewDueMode, new.learnNewDueDays)))
    if (old.globalDoneClearDays != new.globalDoneClearDays) out.add(Triple("Global Done auto-clear", clearLabel(old.globalDoneClearDays), clearLabel(new.globalDoneClearDays)))
    if (old.globalNewDueMode != new.globalNewDueMode || old.globalNewDueDays != new.globalNewDueDays)
        out.add(Triple("Global new-item due", dueMode(old.globalNewDueMode, old.globalNewDueDays), dueMode(new.globalNewDueMode, new.globalNewDueDays)))
    if (old.tabGestureOv != new.tabGestureOv) out.add(Triple("Per-tab gesture overrides", "${old.tabGestureOv.size} set", "${new.tabGestureOv.size} set"))
    fun nag(v: Int) = when { v < 0 -> "Inherit"; v == 0 -> "Off"; else -> "$v h" }
    if (old.globalOverdueNagHours != new.globalOverdueNagHours) out.add(Triple("Global overdue re-nag", nag(old.globalOverdueNagHours), nag(new.globalOverdueNagHours)))
    if (old.tasksOverdueNagHours != new.tasksOverdueNagHours) out.add(Triple("Tasks overdue re-nag", nag(old.tasksOverdueNagHours), nag(new.tasksOverdueNagHours)))
    if (old.shopOverdueNagHours != new.shopOverdueNagHours) out.add(Triple("Shop overdue re-nag", nag(old.shopOverdueNagHours), nag(new.shopOverdueNagHours)))
    if (old.learnOverdueNagHours != new.learnOverdueNagHours) out.add(Triple("Learn overdue re-nag", nag(old.learnOverdueNagHours), nag(new.learnOverdueNagHours)))
    if (old.callsOverdueNagHours != new.callsOverdueNagHours) out.add(Triple("Calls overdue re-nag", nag(old.callsOverdueNagHours), nag(new.callsOverdueNagHours)))
    if (old.tasksSort != new.tasksSort) out.add(Triple("Tasks sort", old.tasksSort.lowercase(), new.tasksSort.lowercase()))
    if (old.shopSort != new.shopSort) out.add(Triple("Shop sort", old.shopSort.lowercase(), new.shopSort.lowercase()))
    if (old.learnSort != new.learnSort) out.add(Triple("Learn sort", old.learnSort.lowercase(), new.learnSort.lowercase()))
    add("Default place radius", "${old.defaultRadius.toInt()} m", "${new.defaultRadius.toInt()} m")
    fun mins(m: Int): String {
        val h = m / 60; val mm = m % 60
        val ampm = if (h >= 12) "PM" else "AM"
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return "%d:%02d %s".format(h12, mm, ampm)
    }
    add("Default due time", mins(old.defaultDueMinutes), mins(new.defaultDueMinutes))
    add("Missed-call reminder time", mins(old.callReminderMinutes), mins(new.callReminderMinutes))
    fun listChange(name: String, o: List<String>, n: List<String>) {
        if (o != n) out.add(Triple(name, "edited (${o.size}", "${n.size} entries)"))
    }
    listChange("Tasks groups", old.tasksGroups, new.tasksGroups)
    listChange("Shop groups", old.shopGroups, new.shopGroups)
    listChange("Learn topics", old.learnTopics, new.learnTopics)
    add("Tasks new-item time", mins(old.tasksNewDueMinutes), mins(new.tasksNewDueMinutes))
    add("Shop new-item time", mins(old.shopNewDueMinutes), mins(new.shopNewDueMinutes))
    add("Learn new-item time", mins(old.learnNewDueMinutes), mins(new.learnNewDueMinutes))
    return out
}
