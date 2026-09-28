package com.krishna.remindly

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * v1.90 — Shop mode. Four surfaces behind the shop-mode bottom nav:
 *   Buy (the existing Tab.SHOP list, unchanged, hosted by MainActivity)
 *   Shops — Cities → shops-in-city → chains (this file)
 *   Products — the product database (this file)
 *   Shop Settings — mode/layout + shop-scoped behaviour (this file)
 * v2.01 (N32): every editor here is built on the shared EditorSheet chrome (SheetChrome.kt) —
 * one action row, one Option-D SheetBottomSpace, never an inset read inside the popup.
 */

/**
 * v2.00 (N31): every mode flip is routed through the scaffold's guard so the settings
 * leave-guard popups cover the chip too. Default (no provider) = flip immediately.
 */
val LocalModeFlipGuard = staticCompositionLocalOf<(String) -> Boolean> { { _ -> true } }

/**
 * v2.04 (N36): the mode switch lives in a top-left ☰ drawer, not a header chip. This is the button;
 * MainScaffold hosts the drawer (Task Mode / Shop Mode rows) and performs the guarded flip.
 */
val LocalModeDrawerOpen = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun ModeDrawerButton() {
    val open = LocalModeDrawerOpen.current
    IconButton(onClick = { runCatching { open() } }) { Icon(Icons.Filled.Menu, "Menu", tint = Color.White) }
}

// ================================================================ Shops tab (Cities → Shops → Chains)

private const val PAGE_CITIES = 0
private const val PAGE_CITY = 1
private const val PAGE_CHAINS = 2

@Composable
fun ShopsScreen(onOpenGear: () -> Unit) {
    val context = LocalContext.current
    val cities by CityStore.cities.collectAsState()
    val chains by ChainStore.chains.collectAsState()
    val shops by ShopStore.shops.collectAsState()
    val allItems by ItemStore.items.collectAsState()

    var page by rememberSaveable { mutableStateOf(PAGE_CITIES) }
    var openCityId by rememberSaveable { mutableStateOf<Long?>(null) }

    var cityEditing by remember { mutableStateOf<City?>(null) }
    var showCityEditor by remember { mutableStateOf(false) }
    var cityDeleting by remember { mutableStateOf<City?>(null) }
    var shopEditing by remember { mutableStateOf<Shop?>(null) }
    var showShopEditor by remember { mutableStateOf(false) }
    var shopDeleting by remember { mutableStateOf<Shop?>(null) }
    var chainEditing by remember { mutableStateOf<Chain?>(null) }
    var showChainEditor by remember { mutableStateOf(false) }
    var chainDeleting by remember { mutableStateOf<Chain?>(null) }
    var chainAddCity by remember { mutableStateOf<Chain?>(null) }

    val liveCities = cities.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }
    val liveShops = shops.filter { it.deletedAt == null }
    val liveChains = chains.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }
    val unassigned = liveShops.filter { it.cityId == null || CityStore.get(it.cityId) == null }
    val openCity = openCityId?.let { id -> liveCities.firstOrNull { it.id == id } }

    Column(Modifier.fillMaxSize()) {
        val (title, subtitle) = when {
            page == PAGE_CITY -> (openCity?.name ?: "Unassigned") to run {
                val n = if (openCity == null) unassigned.size
                else liveShops.count { it.cityId == openCity.id }
                if (n == 1) "1 shop" else "$n shops"
            }
            page == PAGE_CHAINS -> "Chains" to run {
                val b = liveShops.count { it.chainId != null }
                "${liveChains.size} chains · $b branches"
            }
            else -> "Shops" to "${liveCities.size} cities · ${liveShops.size} shops"
        }
        GradientHeader(
            title = title, subtitle = subtitle, pal = ShopPal,
            leading = {
                // v2.04 (N36): ☰ on the top-level page, ← inside a city / the chains list.
                if (page != PAGE_CITIES) IconButton(onClick = { page = PAGE_CITIES; openCityId = null }) {
                    Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
                } else ModeDrawerButton()
            },
            trailing = {
                // v2.04 (N35): the Shops tab owns its settings (geofence alerts, location & battery).
                IconButton(onClick = { onOpenGear() }) { Icon(Icons.Filled.Settings, "Shops settings", tint = Color.White) }
            }
        )

        when (page) {
            PAGE_CITIES -> LazyColumn(Modifier.padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 96.dp)) {
                item {
                    // v2.10 (N9): a geofenced shop that can't alert says so here — with the fix.
                    if (liveShops.any { it.hasGeofence }) GeofencePermNote(ShopPal.accent, Modifier.padding(bottom = 8.dp))
                    Button(onClick = { cityEditing = null; showCityEditor = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add city")
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (liveCities.isEmpty() && unassigned.isEmpty()) item {
                    Text("No cities yet. Add one, then add shops inside it.",
                        style = MaterialTheme.typography.bodyMedium, color = InkHint,
                        modifier = Modifier.padding(top = 12.dp))
                }
                items(liveCities, key = { "cy${it.id}" }) { city ->
                    val n = liveShops.count { it.cityId == city.id }
                    val b = liveShops.count { it.cityId == city.id && it.chainId != null }
                    RowCard(onClick = { openCityId = city.id; page = PAGE_CITY }) {
                        Icon(Icons.Filled.LocationCity, null, tint = ShopPal.accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(city.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                (if (n == 1) "1 shop" else "$n shops") + (if (b > 0) " · $b chain branch${if (b == 1) "" else "es"}" else ""),
                                style = MaterialTheme.typography.labelSmall, color = InkSubtle
                            )
                        }
                        IconButton(onClick = { cityEditing = city; showCityEditor = true }) { Icon(Icons.Filled.Edit, "Edit", tint = SettingsAccent) }
                        IconButton(onClick = { cityDeleting = city }) { Icon(Icons.Filled.Delete, "Delete", tint = OverdueRed) }
                    }
                }
                if (unassigned.isNotEmpty()) item {
                    RowCard(onClick = { openCityId = null; page = PAGE_CITY }) {
                        Column(Modifier.weight(1f)) {
                            Text("Unassigned", fontWeight = FontWeight.SemiBold, color = AmberInk)
                            Text("${unassigned.size} shop${if (unassigned.size == 1) "" else "s"} without a city — tap to assign",
                                style = MaterialTheme.typography.labelSmall, color = InkSubtle)
                        }
                    }
                }
                item {
                    RowCard(bg = ShopPal.chipBg, onClick = { page = PAGE_CHAINS }) {
                        Icon(Icons.Filled.Store, null, tint = ShopPal.onChip)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Chains", fontWeight = FontWeight.SemiBold, color = ShopPal.onChip)
                            Text(
                                if (liveChains.isEmpty()) "none yet — D-Mart, Reliance Smart Bazaar…"
                                else liveChains.joinToString(" · ") { it.name },
                                style = MaterialTheme.typography.labelSmall, color = InkSubtle,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            PAGE_CITY -> LazyColumn(Modifier.padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 96.dp)) {
                item {
                    Button(onClick = { shopEditing = null; showShopEditor = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp))
                        Text(if (openCity != null) "Add shop in ${openCity.name}" else "Add shop")
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val inCity = (if (openCity != null) liveShops.filter { it.cityId == openCity.id } else unassigned)
                    .sortedBy { it.name.lowercase() }
                if (inCity.isEmpty()) item {
                    Text("No shops here yet.", style = MaterialTheme.typography.bodyMedium, color = InkHint,
                        modifier = Modifier.padding(top = 12.dp))
                }
                items(inCity, key = { "sh${it.id}" }) { shop ->
                    val chainName = shop.chainId?.let { ChainStore.get(it)?.name }
                    RowCard {
                        IconButton(onClick = { ShopStore.setDefault(shop.id) }) {
                            if (shop.isDefault) Icon(Icons.Filled.Star, "Default", tint = ShopPal.accent)
                            else Icon(Icons.Filled.StarBorder, "Set default", tint = InkHint)
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // v2.04 (N35 B1): name yields width to the tag instead of squeezing it.
                                Text(shop.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(6.dp))
                                TagPill(if (chainName != null) "Chain" else "Local",
                                    bg = if (chainName != null) ShopPal.chipBg else PillBgIdle,
                                    fg = if (chainName != null) ShopPal.onChip else PillTextIdle)
                            }
                            val n = shopItemCount(allItems, shop.name)
                            val arriveLabel = if (shop.hasGeofence)
                                " · " + alertTypesLabel(resolveShopArriveTypes(shop, SettingsStore.s.value)) else ""
                            Text(
                                (if (shop.hasGeofence) "geofenced · ${radiusLabel(shop.radius)}$arriveLabel" else "no geofence") +
                                    " · " + (if (n == 1) "1 buy item" else "$n buy items") +
                                    (if (shop.isDefault) " · default" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (shop.hasGeofence) ShopInk else InkHint
                            )
                        }
                        IconButton(onClick = { shopEditing = shop; showShopEditor = true }) { Icon(Icons.Filled.Edit, "Edit", tint = SettingsAccent) }
                        IconButton(onClick = { shopDeleting = shop }) { Icon(Icons.Filled.Delete, "Delete", tint = OverdueRed) }
                    }
                }
            }

            PAGE_CHAINS -> LazyColumn(Modifier.padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 96.dp)) {
                item {
                    Button(onClick = { chainEditing = null; showChainEditor = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add chain")
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (liveChains.isEmpty()) item {
                    Text("No chains yet. A chain is a brand whose branches live in many cities.",
                        style = MaterialTheme.typography.bodyMedium, color = InkHint,
                        modifier = Modifier.padding(top = 12.dp))
                }
                items(liveChains, key = { "ch${it.id}" }) { chain ->
                    val branches = liveShops.filter { it.chainId == chain.id }
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp)).background(SurfaceSubtle).padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(chain.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                                Text("${branches.size} branch${if (branches.size == 1) "" else "es"}",
                                    style = MaterialTheme.typography.labelSmall, color = InkSubtle)
                            }
                            IconButton(onClick = { chainEditing = chain; showChainEditor = true }) { Icon(Icons.Filled.Edit, "Edit", tint = SettingsAccent) }
                            IconButton(onClick = { chainDeleting = chain }) { Icon(Icons.Filled.Delete, "Delete", tint = OverdueRed) }
                        }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            branches.take(3).forEach { b ->
                                val cn = b.cityId?.let { CityStore.get(it)?.name } ?: "Unassigned"
                                TagPill("$cn ✓", bg = ShopPal.chipBg, fg = ShopPal.onChip)
                            }
                            if (branches.size > 3) TagPill("+${branches.size - 3}", bg = PillBgIdle, fg = PillTextIdle)
                            TagPill("＋ add to city", bg = PillBgIdle, fg = PillTextIdle,
                                modifier = Modifier.clickable { chainAddCity = chain })
                        }
                    }
                }
            }
        }
    }

    // ---- dialogs & sheets ----
    if (showCityEditor) CityEditorDialog(
        existing = cityEditing,
        onDismiss = { showCityEditor = false },
        onSave = { name ->
            runCatching {
                CityStore.upsert((cityEditing ?: City(Ids.next(), name)).copy(name = name.trim()))
            }.onFailure { Logger.e(context, "CITY", it, "city save failed") }
            showCityEditor = false
        }
    )
    cityDeleting?.let { city ->
        AlertDialog(
            onDismissRequest = { cityDeleting = null },
            title = { Text("Delete \u201c${city.name}\u201d?", fontWeight = FontWeight.Bold) },
            text = { Text("The city is removed. Its shops are kept and move to Unassigned.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { CityStore.delete(city.id) }.onFailure { Logger.e(context, "CITY", it, "city delete failed") }
                    cityDeleting = null
                }) { Text("Delete", color = OverdueRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { cityDeleting = null }) { Text("Cancel") } }
        )
    }

    if (showShopEditor) ShopEditorSheet(
        existing = shopEditing,
        presetCityId = if (shopEditing == null) openCity?.id else shopEditing?.cityId,
        onDismiss = { showShopEditor = false },
        onSaved = { showShopEditor = false },
        onDelete = { shopDeleting = shopEditing; showShopEditor = false }   // v2.01: Delete slot → same confirm
    )
    shopDeleting?.let { shop ->
        AlertDialog(
            onDismissRequest = { shopDeleting = null },
            title = { Text("Delete \u201c${shop.name}\u201d?", fontWeight = FontWeight.Bold) },
            text = { Text("The shop is removed. Buy items already tagged with it keep their name.") },
            confirmButton = {
                TextButton(onClick = {
                    Engine.deleteShop(context, shop)   // v2.8 (N44): fence + snoozed re-fire + Buy Now too
                    shopDeleting = null
                }) { Text("Delete", color = OverdueRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { shopDeleting = null }) { Text("Cancel") } }
        )
    }

    if (showChainEditor) CityEditorDialog(   // same one-field editor shape, chain-labelled
        existing = chainEditing?.let { City(it.id, it.name) },
        titleAdd = "Add chain", titleEdit = "Edit chain", label = "Chain name (e.g. D-Mart)",
        onDismiss = { showChainEditor = false },
        onSave = { name ->
            runCatching {
                ChainStore.upsert((chainEditing ?: Chain(Ids.next(), name)).copy(name = name.trim()))
            }.onFailure { Logger.e(context, "CHAIN", it, "chain save failed") }
            showChainEditor = false
        }
    )
    chainDeleting?.let { chain ->
        AlertDialog(
            onDismissRequest = { chainDeleting = null },
            title = { Text("Delete \u201c${chain.name}\u201d?", fontWeight = FontWeight.Bold) },
            text = { Text("Keep its branch shops as local shops, or delete the branches too?") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { ChainStore.delete(chain.id, keepBranches = true); Geofencer.registerAll(context) }
                        .onFailure { Logger.e(context, "CHAIN", it, "chain delete failed") }
                    chainDeleting = null
                }) { Text("Keep branches", fontWeight = FontWeight.Bold, color = ShopPal.accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    runCatching { ChainStore.delete(chain.id, keepBranches = false); Geofencer.registerAll(context) }
                        .onFailure { Logger.e(context, "CHAIN", it, "chain delete failed") }
                    chainDeleting = null
                }) { Text("Delete branches", color = OverdueRed) }
            }
        )
    }

    chainAddCity?.let { chain ->
        val candidates = liveCities.filter { c -> liveShops.none { it.chainId == chain.id && it.cityId == c.id } }
        AlertDialog(
            onDismissRequest = { chainAddCity = null },
            title = { Text("Add ${chain.name} branch", fontWeight = FontWeight.Bold, color = ShopPal.accent) },
            text = {
                Column {
                    if (candidates.isEmpty()) Text("Every city already has a ${chain.name} branch. Add a city first.", color = InkSubtle)
                    candidates.forEach { c ->
                        Text(
                            c.name, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable {
                                runCatching {
                                    ShopStore.upsert(Shop(
                                        id = Ids.next(), name = branchAutoName(chain.name, c.name),
                                        cityId = c.id, chainId = chain.id,
                                        radius = snapRadius(SettingsStore.s.value.shopNewRadius)
                                    ))
                                }.onFailure { Logger.e(context, "CHAIN", it, "branch create failed") }
                                chainAddCity = null
                            }.padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chainAddCity = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RowCard(bg: Color = SurfaceSubtle, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(bg)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
}

@Composable
private fun TagPill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    // v2.04 (N35 B1): a tag NEVER wraps — the long-name row squeezed "CHAIN" into "CH/AI/N".
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        modifier = modifier.clip(RoundedCornerShape(9.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/** v2.01 (N32 Q3): the one-field City/Chain editor uses the same sheet standard as every editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CityEditorDialog(
    existing: City?,
    titleAdd: String = "Add city", titleEdit: String = "Edit city", label: String = "City name",
    onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    EditorSheet(
        title = if (existing == null) titleAdd else titleEdit, accent = ShopPal.accent, onDismiss = onDismiss,
        actions = {
            EditorActionRow(accent = ShopPal.accent, onCancel = onDismiss,
                onSave = { onSave(name.trim()) }, saveEnabled = name.trim().isNotEmpty())
        }
    ) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/** The shop editor bottom sheet — Local shop | Chain branch, city, geofence, default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopEditorSheet(existing: Shop?, presetCityId: Long?, onDismiss: () -> Unit, onSaved: () -> Unit, onDelete: (() -> Unit)? = null) {
    val context = LocalContext.current
    val settings = SettingsStore.s.collectAsState().value
    val liveCities = CityStore.active()
    val liveChains = ChainStore.active()

    var isBranch by remember { mutableStateOf(existing?.chainId != null) }
    var chainId by remember { mutableStateOf(existing?.chainId ?: liveChains.firstOrNull()?.id) }
    var cityId by remember { mutableStateOf(existing?.cityId ?: presetCityId) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var nameTouched by remember { mutableStateOf(existing != null) }
    var lat by remember { mutableStateOf(existing?.lat) }
    var lng by remember { mutableStateOf(existing?.lng) }
    var radius by remember { mutableStateOf(snapRadius(existing?.radius ?: settings.shopNewRadius)) }
    var isDefault by remember { mutableStateOf(existing?.isDefault ?: false) }
    // v2.05 (N37): per-shop arrival alert — null = follow the Shops ⚙ default.
    var arriveTypes by remember { mutableStateOf(normalizeAlertTypes(existing?.arriveTypes)) }
    var showMap by remember { mutableStateOf(false) }
    var cityMenu by remember { mutableStateOf(false) }
    var chainMenu by remember { mutableStateOf(false) }

    val chainName = chainId?.let { id -> liveChains.firstOrNull { it.id == id }?.name } ?: ""
    val cityName = cityId?.let { id -> liveCities.firstOrNull { it.id == id }?.name } ?: ""
    if (isBranch && !nameTouched) name = branchAutoName(chainName, cityName)

    EditorSheet(
        title = if (existing == null) "Add shop" else "Edit shop", accent = ShopPal.accent, onDismiss = onDismiss,
        actions = {
            EditorActionRow(
                accent = ShopPal.accent, onCancel = onDismiss, saveEnabled = name.trim().isNotEmpty(),
                onDelete = if (existing != null) onDelete else null,
                onSave = {
                    runCatching {
                        val base = existing ?: Shop(id = Ids.next(), name = name.trim())
                        ShopStore.upsert(base.copy(
                            name = name.trim(),
                            cityId = cityId,
                            chainId = if (isBranch) chainId else null,
                            lat = lat, lng = lng, radius = snapRadius(radius),
                            isDefault = isDefault,
                            arriveTypes = if (lat != null && lng != null) arriveTypes else null
                        ))
                        Geofencer.registerAll(context)
                    }.onFailure { Logger.e(context, "SHOP", it, "shop save failed") }
                    onSaved()
                }
            )
        }
    ) {
        run {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !isBranch, onClick = { isBranch = false }, label = { Text("Local shop") })
                FilterChip(
                    selected = isBranch,
                    onClick = { if (liveChains.isNotEmpty()) { isBranch = true; nameTouched = false } },
                    label = { Text(if (liveChains.isEmpty()) "Chain branch (add a chain first)" else "Chain branch") }
                )
            }
            if (isBranch) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { chainMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (chainName.isEmpty()) "Pick chain" else "Chain: $chainName")
                }
                if (chainMenu) PickDialog("Chain", liveChains.map { it.id to it.name },
                    onPick = { chainId = it; nameTouched = false; chainMenu = false }, onDismiss = { chainMenu = false })
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { cityMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (cityName.isEmpty()) "City: Unassigned" else "City: $cityName")
            }
            if (cityMenu) PickDialog("City", listOf<Pair<Long?, String>>(null to "Unassigned") + liveCities.map { it.id as Long? to it.name },
                onPick = { cityId = it; nameTouched = nameTouched && !isBranch; cityMenu = false }, onDismiss = { cityMenu = false })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it; nameTouched = true },
                label = { Text(if (isBranch) "Branch name (auto, editable)" else "Shop name") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text("Geofence (optional)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showMap = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.LocationOn, null); Spacer(Modifier.width(6.dp))
                    Text(if (lat != null && lng != null) "Location set ✓" else "Pick on map")
                }
                if (lat != null) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { lat = null; lng = null }) { Text("Clear", color = OverdueRed) }
                }
            }
            if (lat != null && lng != null) {
                // STANDING RULE (v2.02): the 13-stop scale, as a dropdown — never continuous.
                Spacer(Modifier.height(6.dp))
                RadiusDropdown(value = radius, accent = ShopPal.accent) { radius = it }
                GeofencePermNote(ShopPal.accent, Modifier.padding(top = 8.dp))   // v2.10 (N9)
                // v2.05 (N37): the arrival alert is only meaningful once a geofence exists.
                Spacer(Modifier.height(10.dp))
                Text("On arrival", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("How this shop alerts you when you arrive with pending buy items",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = arriveTypes == null, onClick = { arriveTypes = null },
                        label = { Text("Default") })
                    listOf('N' to "🔔 Notify", 'R' to "🔊 Ring", 'A' to "⏰ Alarm").forEach { (c, label) ->
                        val on = arriveTypes?.contains(c) == true
                        FilterChip(
                            selected = on,
                            onClick = {
                                val cur = arriveTypes ?: resolveShopArriveTypes(
                                    existing ?: Shop(id = 0L, name = ""), SettingsStore.s.value
                                )
                                arriveTypes = normalizeAlertTypes(
                                    if (cur.contains(c)) cur.filter { it != c } else cur + c
                                ) ?: ""
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    when (val t = arriveTypes) {
                        null -> "Follows the default in Shops ⚙ (${alertTypesLabel(SettingsStore.s.value.shopArriveTypes)})"
                        "" -> "Silent — Buy Now still appears when you arrive"
                        else -> alertTypesLabel(t) + " · Alarm shows a full-screen card, Ring keeps sounding"
                    },
                    style = MaterialTheme.typography.bodySmall, color = InkHint
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Default shop", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Text("Auto-filled on new buy items", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                }
                Switch(checked = isDefault, onCheckedChange = { isDefault = it })
            }
        }
    }

    if (showMap) {
        MapPickerDialog(
            initialLat = lat, initialLng = lng,          // null → opens at the GPS fix (C2)
            accent = ShopPal.accent,
            radius = radius, onRadius = { radius = it },
            onDismiss = { showMap = false },
            onPick = { la, ln -> lat = la; lng = ln; showMap = false }
        )
    }
}

@Composable
private fun <T> PickDialog(title: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, color = ShopPal.accent) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text("Nothing to pick yet.", color = InkSubtle)
                options.forEach { (v, label) ->
                    Text(label, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(v) }.padding(vertical = 9.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ================================================================ Products tab

@Composable
fun ProductsScreen(onOpenGear: () -> Unit) {
    val context = LocalContext.current
    val products by ProductStore.products.collectAsState()
    val links by ProductStore.links.collectAsState()
    val shops by ShopStore.shops.collectAsState()

    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Product?>(null) }

    val live = products.filter { it.deletedAt == null }
    val liveShopsById = shops.filter { it.deletedAt == null }.associateBy { it.id }
    val cats = ProductStore.categories()
    val visible = live.filter { productMatches(it, q) && (cat == null || (it.category ?: "").equals(cat, ignoreCase = true)) }
        .sortedBy { it.name.lowercase() }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = "Products",
            subtitle = "${live.size} product${if (live.size == 1) "" else "s"} · ${cats.size} categories",
            pal = ShopPal,
            leading = { ModeDrawerButton() },
            trailing = {
                // v2.04 (N35): the Products tab owns its settings (data export).
                IconButton(onClick = { onOpenGear() }) { Icon(Icons.Filled.Settings, "Products settings", tint = Color.White) }
            }
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = InkHint) },
                placeholder = { Text("Search products…") }, modifier = Modifier.fillMaxWidth()
            )
            if (cats.isNotEmpty()) Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("All") })
                cats.forEach { c -> FilterChip(selected = cat.equals(c, true), onClick = { cat = if (cat.equals(c, true)) null else c }, label = { Text(c) }) }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { editing = null; showEditor = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add product")
            }
        }
        LazyColumn(Modifier.padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 96.dp)) {
            if (visible.isEmpty()) item {
                Text(if (live.isEmpty()) "No products yet — this is your product database. Buy items can link to it."
                    else "No products match.", style = MaterialTheme.typography.bodyMedium, color = InkHint,
                    modifier = Modifier.padding(top = 12.dp))
            }
            items(visible, key = { "pr${it.id}" }) { p ->
                val pLinks = links.filter { it.productId == p.id && it.deletedAt == null && it.shopId in liveShopsById.keys }
                val best = cheapestLink(pLinks, liveShopsById.keys)
                RowCard {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                p.category,
                                p.defaultUnit,
                                "at ${pLinks.size} shop${if (pLinks.size == 1) "" else "s"}"
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = InkSubtle
                        )
                        if (best != null) {
                            val bShop = liveShopsById[best.shopId]?.name ?: "?"
                            val priceTxt = if (best.lastUnitPrice > 0.0)
                                "₹${trimNum(best.lastUnitPrice)}/${p.defaultUnit ?: "unit"}"
                            else "₹${trimNum(best.lastPrice)}"
                            Text("best $priceTxt @ $bShop", style = MaterialTheme.typography.labelSmall,
                                color = SuccessGreen, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    IconButton(onClick = { editing = p; showEditor = true }) { Icon(Icons.Filled.Edit, "Edit", tint = SettingsAccent) }
                    IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Delete", tint = OverdueRed) }
                }
            }
        }
    }

    if (showEditor) ProductEditorSheet(existing = editing, onDismiss = { showEditor = false },
        onDelete = { deleting = editing; showEditor = false })   // v2.01: Delete slot → same confirm
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \u201c${p.name}\u201d?", fontWeight = FontWeight.Bold) },
            text = { Text("The product and its shop links are removed. Buy items keep their text.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { ProductStore.deleteProduct(p.id) }.onFailure { Logger.e(context, "PRODUCT", it, "product delete failed") }
                    deleting = null
                }) { Text("Delete", color = OverdueRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

private val UNIT_OPTIONS = listOf("kg", "g", "L", "ml", "pcs", "dozen", "strip", "pack")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditorSheet(existing: Product?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    val context = LocalContext.current
    val liveShops = ShopStore.active()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var unit by remember { mutableStateOf(existing?.defaultUnit ?: "") }
    val startLinks = remember(existing?.id) {
        existing?.let { ProductStore.linksFor(it.id).associateBy { l -> l.shopId } } ?: emptyMap()
    }
    val checked = remember { mutableStateOf(startLinks.keys.toSet()) }
    val priceTxt = remember { mutableStateOf(startLinks.mapValues { (_, l) -> if (l.lastUnitPrice > 0.0) trimNum(l.lastUnitPrice) else if (l.lastPrice > 0.0) trimNum(l.lastPrice) else "" }) }

    EditorSheet(
        title = if (existing == null) "Add product" else "Edit product", accent = ShopPal.accent, onDismiss = onDismiss,
        actions = {
            EditorActionRow(
                accent = ShopPal.accent, onCancel = onDismiss, saveEnabled = name.trim().isNotEmpty(),
                onDelete = if (existing != null) onDelete else null,
                onSave = {
                    runCatching {
                        val p = (existing ?: Product(Ids.next(), name.trim())).copy(
                            name = name.trim(),
                            category = category.trim().takeIf { it.isNotBlank() },
                            defaultUnit = unit.trim().takeIf { it.isNotBlank() }
                        )
                        ProductStore.upsertProduct(p)
                        liveShops.forEach { shop ->
                            val on = shop.id in checked.value
                            val entered = priceTxt.value[shop.id]?.toDoubleOrNull()?.takeIf { it > 0.0 }
                            val was = startLinks.containsKey(shop.id)
                            if (on || was) ProductStore.setLink(p.id, shop.id, on, unitPrice = if (on) entered else null)
                        }
                    }.onFailure { Logger.e(context, "PRODUCT", it, "product save failed") }
                    onDismiss()
                }
            )
        }
    ) {
        run {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Product name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = category, onValueChange = { category = it },
                    label = { Text("Category") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = unit, onValueChange = { unit = it },
                    label = { Text("Unit") }, singleLine = true, modifier = Modifier.width(110.dp))
            }
            Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UNIT_OPTIONS.forEach { u ->
                    FilterChip(selected = unit.equals(u, true), onClick = { unit = u }, label = { Text(u) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Available at (price optional)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (liveShops.isEmpty()) Text("No shops registered yet — add shops in the Shops tab.",
                style = MaterialTheme.typography.bodySmall, color = InkHint, modifier = Modifier.padding(top = 4.dp))
            liveShops.forEach { shop ->
                val on = shop.id in checked.value
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { c ->
                        checked.value = if (c) checked.value + shop.id else checked.value - shop.id
                    })
                    Text(shop.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = if (on) InkPrimary else InkHint)
                    OutlinedTextField(
                        value = priceTxt.value[shop.id] ?: "",
                        onValueChange = { v -> priceTxt.value = priceTxt.value + (shop.id to v.filter { it.isDigit() || it == '.' }) },
                        enabled = on, singleLine = true,
                        label = { Text("₹/${unit.ifBlank { "unit" }}") },
                        modifier = Modifier.width(120.dp)
                    )
                }
            }
        }
    }
}
