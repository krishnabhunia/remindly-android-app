package com.krishna.remindly

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * v2.11 (N48) — the Buy tab's Lists screen and its sheets (design/n48-lists-first-design-v1.html):
 *   L1 Lists home · L2 new/edit list · L5 list menu · L6 delete · merge · L7 starters · L12 restart.
 * Buy Now (N38) shows as a banner here; the cross-list view it opens is ListPage(BUY_NOW_LIST_ID).
 */

/** The N45 icon set, reused for lists. */
val LIST_ICONS = listOf("🛒", "📦", "💊", "🎁", "🏠", "🧹", "🍎", "👕", "🪔", "🎉", "📚", "🔧", "🐾", "✈️", "💼", "🍼", "🧴", "⭐")

/** L7 starters: name, icon, one-line hint. */
private val STARTERS = listOf(
    Triple("Groceries", "🛒", "Weekly essentials"),
    Triple("Monthly stock", "📦", "Restart it each month"),
    Triple("Medicines", "💊", ""),
    Triple("Party / occasion", "🎉", "")
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShopListsScreen(
    onOpenList: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    buyNowShop: Shop?
) {
    val pal = ShopPal
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    val allItems by ItemStore.items.collectAsState()
    val shops by ShopStore.shops.collectAsState()
    val personalUnlocked by Engine.personalUnlocked.collectAsState()
    val shopsById = remember(shops) { shops.filter { it.deletedAt == null }.associate { it.id to it.name } }
    val now = System.currentTimeMillis()

    val lists = settings.shopLists
    val buyItems = remember(allItems) { allItems.filter { it.tab == Tab.SHOP && it.deletedAt == null } }
    val stats = remember(buyItems, lists, shopsById) {
        liveLists(lists).associate { l -> l.id to listStats(itemsInList(buyItems, l.id, lists), shopsById, l.updatedAt) }
    }
    val ordered = sortedLists(lists, settings.buyListSort) { stats[it.id]?.lastActivity ?: 0L }
    val unsorted = remember(buyItems, lists) { itemsInList(buyItems, UNSORTED_LIST_ID, lists) }
    val totalToBuy = buyItems.count { !it.done }

    var editorFor by remember { mutableStateOf<ShopList?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorPrefill by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<ShopList?>(null) }
    var deleteFor by remember { mutableStateOf<ShopList?>(null) }
    var mergeFor by remember { mutableStateOf<ShopList?>(null) }
    var shareFor by remember { mutableStateOf<ShopList?>(null) }
    var pinFor by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }
    val shareOk = shareEnabledFor(Tab.SHOP, settings)
    var showSharedHub by remember { mutableStateOf(false) }
    val shareRecv by ShareStore.received.collectAsState()
    val shareBlocked by ShareStore.blocked.collectAsState()
    val sharePending = if (shareOk) pendingReceived(shareRecv, shareBlocked).count { it.tab == "SHOP" } else 0

    fun open(l: ShopList) {
        if (l.personal && !personalUnlocked && PinStore.isSet()) { pinFor = l.id; return }
        onOpenList(l.id)
    }
    fun createNamed(name: String, icon: String? = null) {
        val l = ShopListStore.create(context, name, icon, null, null, false) ?: return
        onOpenList(l.id)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GradientHeader(
                title = "Buy",
                subtitle = "${liveLists(lists).size} list${if (liveLists(lists).size == 1) "" else "s"} · $totalToBuy to buy",
                pal = pal,
                leading = { ModeDrawerButton() },
                trailing = {
                    if (shareOk) SharedHubButton(sharePending, tint = Color.White) { showSharedHub = true }
                    if (personalUnlocked) IconButton(onClick = { Engine.lockPersonal() }) {
                        Icon(Icons.Filled.Lock, "Lock personal items", tint = Color.White)
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Buy settings", tint = Color.White) }
                },
                bottomContent = {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("RECENT" to "Recent", "AZ" to "A–Z", "CUSTOM" to "Custom").forEach { (k, label) ->
                            val on = settings.buyListSort == k
                            Text(
                                label,
                                color = if (on) pal.onChip else Color.White,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (on) Color.White else Color.White.copy(alpha = 0.22f))
                                    .clickable { SettingsStore.update { it.copy(buyListSort = k) } }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            )
            DegradeBanner(Tab.SHOP) { onOpenSettings() }
            // L9: Buy Now — the arrival banner opens the cross-list view.
            if (buyNowShop != null) {
                val here = buyNowItems(buyItems.filter { !it.done }, buyNowShop)
                val nLists = here.map { listIdOf(it, lists) }.distinct().size
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp)).background(pal.chipBg)
                        .clickable { onOpenList(BUY_NOW_LIST_ID) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.LocationOn, null, tint = pal.onChip, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("At ${buyNowShop.name}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = pal.onChip, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${here.size} item${if (here.size == 1) "" else "s"} across $nLists list${if (nLists == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    }
                    TextButton(onClick = { onOpenList(BUY_NOW_LIST_ID) }) { Text("Open", fontWeight = FontWeight.Bold, color = pal.onChip) }
                    TextButton(onClick = { BuyNow.hide(context) }) { Text("✕", fontWeight = FontWeight.Bold, color = pal.onChip) }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (ordered.isEmpty() && unsorted.isEmpty()) {
                    item(key = "starters") { Starters(pal) { name, icon -> createNamed(name, icon) } }
                }
                items(ordered, key = { it.id }) { l ->
                    val st = stats[l.id] ?: ListStats(0, 0, null, emptyList(), 0L)
                    ListCard(
                        l, st, settings, now, pal,
                        showMove = settings.buyListSort == "CUSTOM",
                        onOpen = { open(l) },
                        onMenu = { menuFor = l },
                        modifier = Modifier.animateItemPlacement()
                    )
                }
                if (settings.buyShowUnsorted && unsorted.isNotEmpty()) item(key = "unsorted") {
                    UnsortedCard(unsorted.count { !it.done }, unsorted.size, pal) { onOpenList(UNSORTED_LIST_ID) }
                }
            }
        }
        // The bottom bar creates a LIST here (inside a list it adds items).
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(50), color = SurfaceCard, shadowElevation = 10.dp) {
                TextField(
                    value = newName, onValueChange = { newName = it },
                    placeholder = { Text(if (ordered.isEmpty()) "Name your own list…" else "New list…", color = GreyIcon) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = InkPrimary, unfocusedTextColor = InkPrimary, cursorColor = pal.accent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
                    keyboardActions = KeyboardActions(onDone = {
                        val n = newName.trim(); if (n.isNotEmpty()) { newName = ""; createNamed(n) }
                    }),
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(56.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(pal.accent)
                    .clickable { editorPrefill = newName.trim(); newName = ""; editorFor = null; showEditor = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Add, "New list", tint = Color.White) }
        }
    }

    if (showEditor) ListEditorSheet(editorFor, editorPrefill, pal, onDismiss = { showEditor = false }) { saved ->
        showEditor = false
        if (editorFor == null && saved != null) onOpenList(saved.id)
    }
    menuFor?.let { l ->
        ListMenuSheet(
            l, ShopListStore.itemsOf(l.id), settings, pal,
            onDismiss = { menuFor = null },
            onEdit = { menuFor = null; editorFor = l; editorPrefill = ""; showEditor = true },
            onShare = { menuFor = null; shareFor = l },
            onDelete = { menuFor = null; deleteFor = l },
            onMerge = { menuFor = null; mergeFor = l }
        )
    }
    deleteFor?.let { l -> DeleteListSheet(l, pal) { deleteFor = null } }
    mergeFor?.let { l -> MergeListSheet(l, pal) { mergeFor = null } }
    shareFor?.let { l ->
        ListTextShareSheet(l.name, ShopListStore.itemsOf(l.id), pal, isLocked = { it.personal && !personalUnlocked }) { shareFor = null }
    }
    if (showSharedHub) SharedHubSheet(Tab.SHOP, pal) { showSharedHub = false }
    pinFor?.let { id ->
        PinDialog("Private list", onDismiss = { pinFor = null }) {
            Engine.personalUnlocked.value = true; pinFor = null; onOpenList(id)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListCard(
    l: ShopList, st: ListStats, settings: AppSettings, now: Long, pal: TabPalette,
    showMove: Boolean, onOpen: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val total = st.toBuy + st.done
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceCard)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(pal.chipBg), contentAlignment = Alignment.Center) {
            Text(l.icon ?: "🛒", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = InkPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (l.pinned) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.PushPin, "Pinned", tint = pal.accent, modifier = Modifier.size(14.dp)) }
                if (l.personal) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Lock, "Private", tint = InkSubtle, modifier = Modifier.size(14.dp)) }
            }
            Text(listCardSubtitle(st, now), style = MaterialTheme.typography.bodySmall, color = InkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (total > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { st.done.toFloat() / total }, color = pal.accent, trackColor = PillBgIdle,
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                )
            }
            val pills = buildList {
                st.shops.forEach { add(it to false) }
                if (settings.buyCardTotal) estLabel(st.estTotal)?.let { add(it to true) }
                l.shoppingDay?.let { add(("Due " + formatDay(it)) to true) }
            }
            if (pills.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pills.take(3).forEach { (t, neutral) ->
                        Text(t, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                            color = if (neutral) InkStrong else pal.onChip,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (neutral) PillBgIdle else pal.chipBg)
                                .padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (st.toBuy > 0) pal.accent else PillBgIdle)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(if (st.toBuy > 0) "${st.toBuy}" else "✓", color = if (st.toBuy > 0) Color.White else InkSubtle,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
            }
            if (showMove) Row {
                Text("▲", color = InkSubtle, modifier = Modifier.clickable { ShopListStore.move(context, l, up = true) }.padding(4.dp))
                Text("▼", color = InkSubtle, modifier = Modifier.clickable { ShopListStore.move(context, l, up = false) }.padding(4.dp))
            } else IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "List options", tint = GreyIcon) }
        }
    }
}

@Composable
private fun UnsortedCard(open: Int, all: Int, pal: TabPalette, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, InkFaint, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(PillBgIdle), contentAlignment = Alignment.Center) {
            Text("🗂", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Unsorted", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = InkPrimary)
            Text("$all item${if (all == 1) "" else "s"} without a list · tap to sort", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        }
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(PillBgIdle).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text("$open", color = InkSubtle, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun Starters(pal: TabPalette, onCreate: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🧺", style = MaterialTheme.typography.displayMedium)
            Text("Make your first list", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = InkPrimary)
            Text("Lists keep each trip separate. Pick a starter or name your own.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        }
        STARTERS.forEach { (name, icon, hint) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceCard)
                    .clickable { onCreate(name, icon) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = InkPrimary)
                    if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                }
                Text("+ Create", color = pal.accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- L2 new / edit list

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListEditorSheet(list: ShopList?, prefillName: String, pal: TabPalette, onDismiss: () -> Unit, onSaved: (ShopList?) -> Unit) {
    val context = LocalContext.current
    val shops by ShopStore.shops.collectAsState()
    val liveShops = shops.filter { it.deletedAt == null }.sortedBy { it.name.lowercase() }
    var name by remember { mutableStateOf(list?.name ?: prefillName) }
    var icon by remember { mutableStateOf(list?.icon ?: if (list == null) "🛒" else null) }
    var usualShop by remember { mutableStateOf(list?.usualShopId) }
    var day by remember { mutableStateOf(list?.shoppingDay) }
    var personal by remember { mutableStateOf(list?.personal ?: false) }
    var shopOpen by remember { mutableStateOf(false) }
    var needPin by remember { mutableStateOf(false) }

    fun save() {
        if (personal && !PinStore.isSet()) { needPin = true; return }
        val saved = if (list == null) ShopListStore.create(context, name, icon, usualShop, day, personal)
        else { ShopListStore.edit(context, list, name, icon, usualShop, day, personal); ShopListStore.get(list.id) }
        onSaved(saved)
    }

    EditorSheet(
        title = if (list == null) "New list" else "Edit list",
        accent = pal.accent, onDismiss = onDismiss,
        actions = {
            EditorActionRow(accent = pal.accent, onCancel = onDismiss, saveEnabled = name.isNotBlank(),
                saveLabel = if (list == null) "Create & add items" else "Save", onSave = { save() })
        }
    ) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("List name") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Text("Icon", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = InkSubtle)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LIST_ICONS.forEach { ic ->
                FilterChip(selected = icon == ic, onClick = { icon = ic }, label = { Text(ic, style = MaterialTheme.typography.titleMedium) })
            }
            FilterChip(selected = icon == null, onClick = { icon = null }, label = { Text("None") })
        }
        Spacer(Modifier.height(10.dp))
        ExposedDropdownMenuBox(expanded = shopOpen, onExpandedChange = { shopOpen = it }) {
            OutlinedTextField(
                value = liveShops.firstOrNull { it.id == usualShop }?.name ?: "Any",
                onValueChange = {}, readOnly = true,
                label = { Text("Usual shop") },
                supportingText = { Text("Pre-fills the shop on new items") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = shopOpen) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = shopOpen, onDismissRequest = { shopOpen = false }) {
                DropdownMenuItem(text = { Text("Any", color = GreyIcon) }, onClick = { usualShop = null; shopOpen = false })
                liveShops.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { usualShop = s.id; shopOpen = false }) }
            }
        }
        Spacer(Modifier.height(6.dp))
        DateField("Shopping day (reminds you at 9 AM)", day, pal.accent) { day = it }
        if (day != null) TextButton(onClick = { day = null }) { Text("Clear shopping day", color = pal.accent) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Private (Personal PIN)", style = MaterialTheme.typography.bodyLarge)
                Text("Every item in this list is Personal and PIN-locked", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
            }
            Switch(checked = personal, onCheckedChange = { personal = it })
        }
    }
    if (needPin) SetPinDialog(requireOld = false, onDismiss = { needPin = false }) {
        needPin = false; Engine.personalUnlocked.value = true; save()
    }
}

// ---------------------------------------------------------------- L5 list menu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListMenuSheet(
    l: ShopList, items: List<Item>, settings: AppSettings, pal: TabPalette,
    onDismiss: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onMerge: () -> Unit
) {
    val context = LocalContext.current
    val personalUnlocked by Engine.personalUnlocked.collectAsState()
    val open = markAllTargets(items); val bought = restartTargets(items)
    EditorSheet(
        title = (l.icon?.let { "$it " } ?: "") + l.name, accent = pal.accent, onDismiss = onDismiss,
        actions = { EditorActionRow(accent = pal.accent, onCancel = onDismiss, saveEnabled = false, saveLabel = "", onSave = {}) }
    ) {
        Text("${open.size} to buy · ${bought.size} done" + (if (l.createdAt > 0) " · created ${formatDate(l.createdAt)}" else ""),
            style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        // S5: the same two share actions as the list header, at the top of the menu.
        TextButton(onClick = {
            onDismiss(); sendListToWhatsApp(context, l, items.filter { !(it.personal && !personalUnlocked) }, settings)
        }) { Text("💬  Send to WhatsApp", color = WhatsAppGreenInk, fontWeight = FontWeight.Bold) }
        TextButton(onClick = onShare) { Text("📤  Share…") }
        TextButton(onClick = { ShopListStore.togglePin(context, l); onDismiss() }) { Text(if (l.pinned) "📌  Unpin" else "📌  Pin to top") }
        TextButton(onClick = onEdit) { Text("✏️  Rename, icon & details") }
        TextButton(onClick = {
            ShopListStore.duplicate(context, l)?.let { Feedback.toast(context, "Duplicated as “${it.name}”") }
            onDismiss()
        }) { Text("📑  Duplicate list") }
        if (bought.isNotEmpty()) TextButton(onClick = {
            val back = ShopListStore.restart(context, l)
            Ack.show("${back.size} item${if (back.size == 1) "" else "s"} back on the list") { ShopListStore.undoRestart(context, back) }
            onDismiss()
        }) { Text("🔁  Restart list (${bought.size} done → to buy)") }
        if (open.isNotEmpty()) TextButton(onClick = {
            runCatching { open.forEach { Engine.completeNow(context, it.id) } }.onFailure { Logger.e(context, "LISTS", it, "mark all bought failed") }
            Ack.show("${open.size} marked bought") { open.forEach { i -> ItemStore.get(i.id)?.let { Engine.undoDone(context, it) } } }
            onDismiss()
        }) { Text("☑  Mark all bought (${open.size})") }
        if (liveLists(settings.shopLists).size > 1) TextButton(onClick = onMerge) { Text("⇄  Merge into another list…") }
        TextButton(onClick = onDelete) { Text("🗑  Delete list…", color = OverdueRed) }
    }
}

/** The WhatsApp brand green, used ONLY for the WhatsApp action (brand colour, like N45's button). */
val WhatsAppGreen = Color(0xFF25D366)      // hex-ok: brand colour
val WhatsAppGreenInk = Color(0xFF1B7F46)   // hex-ok: brand colour, readable on white

// ---------------------------------------------------------------- L6 delete

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteListSheet(l: ShopList, pal: TabPalette, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    val items = ShopListStore.itemsOf(l.id)
    val others = liveLists(settings.shopLists).filter { it.id != l.id }
    var mode by remember { mutableStateOf(ListDeleteMode.KEEP_UNSORTED) }
    var target by remember { mutableStateOf(others.firstOrNull()) }
    EditorSheet(
        title = "Delete “${l.name}”?", accent = OverdueRed, onDismiss = onDismiss,
        actions = {
            EditorActionRow(accent = OverdueRed, onCancel = onDismiss, saveLabel = "Delete list",
                saveEnabled = mode != ListDeleteMode.MOVE_TO || target != null, onSave = {
                    val moved = ShopListStore.delete(context, l, mode, target)
                    Ack.show("Deleted “${l.name}”") { ShopListStore.undoDelete(context, l, moved, mode) }
                    onDismiss()
                })
        }
    ) {
        val open = items.count { !it.done }
        Text("It holds ${items.size} item${if (items.size == 1) "" else "s"} ($open to buy, ${items.size - open} done). What should happen to them?",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        @Composable fun opt(m: ListDeleteMode, title: String, sub: String, enabled: Boolean = true) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .border(if (mode == m) 2.dp else 1.dp, if (mode == m) pal.accent else InkFaint, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { mode = m }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == m, onClick = { if (enabled) mode = m }, enabled = enabled)
                Column { Text(title, fontWeight = FontWeight.SemiBold); Text(sub, style = MaterialTheme.typography.bodySmall, color = InkSubtle) }
            }
            Spacer(Modifier.height(6.dp))
        }
        opt(ListDeleteMode.KEEP_UNSORTED, "Keep items — move to Unsorted", "Nothing is lost; price history stays")
        opt(ListDeleteMode.MOVE_TO, "Move items to another list", if (others.isEmpty()) "No other list yet" else "Choose below", enabled = others.isNotEmpty())
        if (mode == ListDeleteMode.MOVE_TO) ListChips(others, target?.id) { target = it }
        opt(ListDeleteMode.DELETE_ITEMS, "Delete the items too", "They go to the Bin (restorable) · Undo for a few seconds")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListChips(lists: List<ShopList>, selected: Long?, onPick: (ShopList) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        lists.forEach { o -> FilterChip(selected = selected == o.id, onClick = { onPick(o) }, label = { Text((o.icon?.let { "$it " } ?: "") + o.name) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeListSheet(l: ShopList, pal: TabPalette, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    val others = liveLists(settings.shopLists).filter { it.id != l.id }
    var target by remember { mutableStateOf<ShopList?>(null) }
    EditorSheet(
        title = "Merge “${l.name}” into…", accent = pal.accent, onDismiss = onDismiss,
        actions = {
            EditorActionRow(accent = pal.accent, onCancel = onDismiss, saveEnabled = target != null, saveLabel = "Merge", onSave = {
                val t = target ?: return@EditorActionRow
                val moved = ShopListStore.delete(context, l, ListDeleteMode.MOVE_TO, t)
                Ack.show("Merged into “${t.name}”") { ShopListStore.undoDelete(context, l, moved, ListDeleteMode.MOVE_TO) }
                onDismiss()
            })
        }
    ) {
        Text("Every item moves to the chosen list and “${l.name}” is removed.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        ListChips(others, target?.id) { target = it }
    }
}
