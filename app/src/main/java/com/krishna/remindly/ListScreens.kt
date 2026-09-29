package com.krishna.remindly

import android.Manifest
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCartCheckout
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.compose.material3.Checkbox
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Mic
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority as GmsPriority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ================================================================ list page (one pager page: a tab in Active or Done view)

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ListPage(tab: Tab, isDone: Boolean, onSwitchDone: (Boolean) -> Unit,
             personalFilter: Boolean, setPersonalFilter: (Boolean) -> Unit,
             onOpenShops: () -> Unit = {},
             onOpenSettings: () -> Unit = {},
             // v2.05 (N38): the Buy Now view — a filter over ACTIVE scoped to the shop that last
             // triggered a geofence. Null shop = not armed; isDone stays false while it is shown.
             view: ListView = if (isDone) ListView.DONE else ListView.ACTIVE,
             onSwitchView: (ListView) -> Unit = {},
             buyNowShop: Shop? = null,
             // v2.11 (N48): the Buy page scoped to ONE list — a ShopList id, UNSORTED_LIST_ID or
             // BUY_NOW_LIST_ID (the cross-list arrival view). null = the Classic flat Buy page.
             openList: Long? = null,
             onBackToLists: (() -> Unit)? = null) {
    val pal = palFor(tab)
    val context = LocalContext.current
    val allItems by ItemStore.items.collectAsState()
    val settings by SettingsStore.s.collectAsState()
    val inList = tab == Tab.SHOP && openList != null
    val theList: ShopList? = if (inList) liveLists(settings.shopLists).firstOrNull { it.id == openList } else null
    // Items added OUTSIDE a real list (Classic, Unsorted is excluded on purpose, Buy Now) land in the default list.
    val addList: ShopList? = theList ?: if (tab == Tab.SHOP && openList != UNSORTED_LIST_ID)
        settings.shopDefaultListId?.let { d -> liveLists(settings.shopLists).firstOrNull { it.id == d } } else null
    val listShareSheet = remember { mutableStateOf(false) }
    var listMenuOpen by remember { mutableStateOf(false) }
    var listEditOpen by remember { mutableStateOf(false) }
    var listDeleteOpen by remember { mutableStateOf(false) }
    var listMergeOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    val gest = gesturesFor(settings, tab)
    val curSwitchDone by rememberUpdatedState(onSwitchDone)
    var editing by remember { mutableStateOf<Item?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var quickPrefill by remember { mutableStateOf<String?>(null) }
    var sharePrefill by remember { mutableStateOf<ShareInbox.Payload?>(null) }
    val shareP by ShareInbox.pending.collectAsState()
    LaunchedEffect(shareP) {
        val p = shareP ?: return@LaunchedEffect
        if (p.tab == tab && !isDone) {
            sharePrefill = p
            ShareInbox.pending.value = null
            editing = null
            showSheet = true
        }
    }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }

    // Quick add bar (always visible on Active pages since v1.4)
    var quickText by remember { mutableStateOf("") }
    // v2.11 (N48): inside a list the duplicate check is the LIST's; another list holding it warns too (L4).
    val quickDup = if (inList) dupActiveMatch(itemsInList(ItemStore.items.value, openList!!, settings.shopLists), tab, quickText)
                   else dupActiveMatch(ItemStore.items.value, tab, quickText)
    val quickOther = if (theList != null && settings.buyDupWarn && !quickDup)
        otherListHolding(ItemStore.items.value, quickText, theList.id, settings.shopLists) else null
    var quickRecent by remember { mutableStateOf<Item?>(null) }
    val quickRecents = remember(quickText, allItems, openList) {
        if (theList != null && quickRecent == null) recentInList(itemsInList(allItems, theList.id, settings.shopLists), quickText) else emptyList()
    }
    // v2.9 (N45): a product picked from the suggestions rides along into the new item.
    var quickProduct by remember { mutableStateOf<Product?>(null) }
    val products by ProductStore.products.collectAsState()
    val quickSuggestions = remember(quickText, products, settings.shopSuggest) {
        if (tab == Tab.SHOP && settings.shopSuggest && quickProduct == null) productSuggestions(quickText, products) else emptyList()
    }
    // v2.9 (N45): speech-to-text into the quick-add field (system recogniser; no library).
    val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        runCatching {
            val spoken = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) quickText = spoken
        }.onFailure { Logger.e(context, "VOICE", it, "speech result parse failed") }
    }
    fun startVoice() {
        runCatching {
            val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say the item")
            voiceLauncher.launch(i)
        }.onFailure { Logger.e(context, "VOICE", it, "no speech recogniser on this phone"); Feedback.toast(context, "Voice input isn't available on this phone") }
    }
    val quickFocus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val fm = LocalFocusManager.current
    val dens = densOfPct(settings.densityPct)

    // Shop specifics
    // v1.33 Q2 real fix: personalFilter is hoisted into ListSection (above the Active/Done
    // Crossfade) and passed in, so the two branches share ONE value and flipping Active↔Done
    // no longer resets it. The earlier v1.32 rememberSaveable-key change couldn't work because
    // Crossfade composes Active and Done as separate subtrees with independent state.
    val personalUnlocked by Engine.personalUnlocked.collectAsState()
    var showPinDialog by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var showLocations by remember { mutableStateOf(false) }
    var showTrip by remember { mutableStateOf(false) }
    var pinResetOpen by remember { mutableStateOf(false) }
    var forgotNeedsGoogle by remember { mutableStateOf(false) }

    val forgotLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(res.data)
        runCatching { task.getResult(ApiException::class.java) }
            .onSuccess { pinResetOpen = true }
    }

    // v1.40 bug#1/#2: exclude soft-deleted items so the "active · done" header counts only
    // what's actually in the list.
    val tabItems = allItems.filter { it.tab == tab && it.deletedAt == null }
        .let { if (inList) itemsInList(it, openList!!, settings.shopLists) else it }   // v2.11 (N48)
    // v1.79 (Q11): the header used to count tabItems while the list applied two further filters,
    // so "1 active" could sit above an empty screen. Header and list are now two projections of
    // ONE pipeline — any filter added later is picked up by both automatically.
    // v2.10 (N6 pt2): the pipeline is the pure listScope() so its test runs the real filter.
    fun scopeOf(doneFlag: Boolean): List<Item> = listScope(tabItems, tab, doneFlag, personalFilter, searchOpen, searchQ)
    // v2.05 (N38): Buy Now = ACTIVE, narrowed to the armed shop (pure buyNowItems).
    val visible = if (view == ListView.BUY_NOW && buyNowShop != null)
        scopeOf(false).let { active -> buyNowItems(active, buyNowShop) }
    else scopeOf(isDone)
    val groups = buildYearGroups(visible, isDone, { it.createdAt }) { groupBasis(it, isDone) }

    val fKey = "${tab.name}-${if (isDone) "D" else "A"}"
    var collapsedYears by remember(fKey) { mutableStateOf(UiStore.collapsedFor("cy-$fKey")) }
    var collapsedMonths by remember(fKey) { mutableStateOf(UiStore.collapsedFor("cm-$fKey")) }
    var collapsedDays by remember(fKey) { mutableStateOf(UiStore.collapsedFor("cd-$fKey")) }
    LaunchedEffect(fKey, collapsedYears) { UiStore.setCollapsed("cy-$fKey", collapsedYears) }
    LaunchedEffect(fKey, collapsedMonths) { UiStore.setCollapsed("cm-$fKey", collapsedMonths) }
    LaunchedEffect(fKey, collapsedDays) { UiStore.setCollapsed("cd-$fKey", collapsedDays) }
    val listState = remember(fKey) {
        val sv = UiStore.scrollFor("sc-$fKey")
        LazyListState(sv.getOrElse(0) { 0 }, sv.getOrElse(1) { 0 })
    }
    LaunchedEffect(fKey) {
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving) UiStore.setScroll(
                "sc-$fKey", listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset
            )
        }
    }
    var deleteTarget by remember { mutableStateOf<Item?>(null) }
    fun performDelete(item: Item) {
        if (gesturesFor(SettingsStore.s.value, tab).deleteStyle == "CONFIRM") {
            deleteTarget = item
        } else {
            Engine.delete(context, item)
            Ack.show("Deleted") { Engine.restore(context, item) }
        }
    }
    var clearTarget by remember { mutableStateOf<Pair<String, List<Item>>?>(null) }
    // v2.7 (N43): group-header checkbox — pending bulk action (label, items) and the Undo batch.
    var bulkTarget by remember { mutableStateOf<Pair<String, List<Item>>?>(null) }
    // v2.9 (N45): long-press a group header → Rename · Change icon · Share · Duplicate · Delete list.
    var groupMenu by remember { mutableStateOf<Pair<String, List<Item>>?>(null) }
    var undoBatch by remember { mutableStateOf<List<Item>?>(null) }
    val groupCheckOn = groupCheckFor(tab, settings)
    fun headerCheck(items: List<Item>): GroupCheckState? =
        if (!groupCheckOn) null else groupState(items, isDone, personalLocked = !personalUnlocked)
    fun headerTap(label: String, items: List<Item>): (() -> Unit)? =
        if (!groupCheckOn) null else ({ bulkTarget = label to items })
    LaunchedEffect(undoBatch) { if (undoBatch != null) { kotlinx.coroutines.delay(6_000); undoBatch = null } }
    // v1.79 (Q14): resets on every dismiss — a sticky destructive default causes accidents.
    var alsoRepeats by remember { mutableStateOf(false) }

    fun toggle(set: Set<String>, k: String) = if (k in set) set - k else set + k

    // v1.51: hoisted so the add affordances can be hidden in the read-only Calendar view.
    // v1.61: the addFull switch is gone — the unified bar (morphing +/✓ circle) serves every tab.
    val calendarMode = tab == Tab.TASKS && sortOf(settings, tab) == "CALENDAR"
    // v1.87 (N17): sharing state — group picked for sending, and the Sent|Receive hub.
    val shareOk = shareEnabledFor(tab, settings)
    var shareGroup by remember { mutableStateOf<Pair<String, List<Item>>?>(null) }
    var showSharedHub by remember { mutableStateOf(false) }
    val shareRecv by ShareStore.received.collectAsState()
    val shareBlocked by ShareStore.blocked.collectAsState()
    val sharePendingCount = if (shareOk)
        pendingReceived(shareRecv, shareBlocked).count { it.tab == (if (tab == Tab.SHOP) "SHOP" else "TASKS") }
    else 0
    shareGroup?.let { (gName, gItems) ->
        ShareListSheet(
            tab = tab, listName = gName, items = gItems,
            isLocked = { it.personal && !personalUnlocked },
            pal = pal
        ) { shareGroup = null }
    }
    if (showSharedHub) SharedHubSheet(tab, pal) { showSharedHub = false }
    // v1.79 (Q11): CalendarPane owns the event list; the count is lifted here so the header can
    // report it. Deliberately NOT recomputed — a second parallel computation is the very bug
    // being fixed.
    var calEventCount by remember(calendarMode, isDone) { mutableStateOf<Int?>(null) }
    fun quickAddNow() {
        val t = quickText.trim()
        if (t.isEmpty()) return
        val due = defaultNewDue(tab, settings)
        Engine.addOrUpdate(
            context,
            Item(
                id = Ids.next(), tab = tab, title = t,
                // v1.47 Feature 2c: date-only when the tab/global setting says "no due time".
                dueAt = due, dueHasTime = due != null && newDueTimedFor(tab, settings),
                priority = Priority.MEDIUM,
                personal = tab == Tab.SHOP && (personalFilter || addList?.personal == true),
                // v2.11 (N48): the open list (or the default list outside one) replaces N45's default group;
                // a "recently bought in this list" pick brings its quantity, unit, product and shop along.
                group = if (tab == Tab.SHOP) addList?.name else null,
                listId = if (tab == Tab.SHOP) addList?.id else null,
                productId = quickProduct?.id ?: quickRecent?.productId,
                quantity = quickRecent?.quantity, unit = quickRecent?.unit,
                shopName = if (tab == Tab.SHOP) (quickRecent?.shopName ?: addList?.usualShopId?.let { ShopStore.get(it)?.name }) else null,
                shopId = if (tab == Tab.SHOP) (quickRecent?.shopId ?: addList?.usualShopId) else null
            )
        )
        quickText = ""; quickProduct = null; quickRecent = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(gest.pageSwipe, gest.pageRightDone) {
                if (!gest.pageSwipe) return@pointerInput
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, amount -> total += amount },
                    onDragEnd = {
                        val threshold = 90.dp.toPx()
                        if (total > threshold) curSwitchDone(gest.pageRightDone)
                        else if (total < -threshold) curSwitchDone(!gest.pageRightDone)
                    }
                )
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            GradientHeader(
                title = when {
                    theList != null -> (theList.icon?.let { "$it " } ?: "") + theList.name
                    openList == UNSORTED_LIST_ID -> "Unsorted"
                    openList == BUY_NOW_LIST_ID -> "Buy Now"
                    else -> tab.title
                },
                // v1.79 (Q11): the subtitle describes WHAT IS ON SCREEN. In calendar mode the
                // list is Google Calendar events, not items, so counting items there was simply
                // unrelated information.
                subtitle = if (calendarMode) {
                    val d = calendarReadWindowDays(settings)
                    when (calEventCount) {
                        null -> "loading…"
                        else -> "$calEventCount event${if (calEventCount == 1) "" else "s"} · " +
                            (if (isDone) "last $d days" else "next $d days")
                    }
                } else if (inList) {
                    "${scopeOf(false).size} to buy · ${scopeOf(true).size} done"
                } else {
                    "${scopeOf(false).size} active · ${scopeOf(true).size} done"
                },
                pal = pal,
                leading = {
                    // v2.04 (N36): ☰ = the mode drawer on every top-level list screen (both modes).
                    // v2.11 (N48): inside a list it is ← back to the Lists screen.
                    if (onBackToLists != null) IconButton(onClick = onBackToLists) {
                        Icon(Icons.Filled.ArrowBack, "Back to lists", tint = Color.White)
                    } else ModeDrawerButton()
                },
                trailing = {
                  if (inList && openList != BUY_NOW_LIST_ID) {
                    // v2.11 (N48 S1): Share (preview sheet) + the one-tap WhatsApp icon, then ⋮.
                    val shareName = theList?.name ?: "Unsorted"
                    IconButton(onClick = { listShareSheet.value = true }) { Icon(Icons.Filled.Share, "Share list", tint = Color.White) }
                    if (settings.shareWaIcon) Box(
                        Modifier.size(34.dp).clip(CircleShape).background(WhatsAppGreen)
                            .combinedClickable(
                                onClick = { sendListToWhatsApp(context, shareName, shareOrderFor(visibleForShare(tabItems, personalUnlocked), settings.buyInnerSort, products), settings) },
                                onLongClick = { listShareSheet.value = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Chat, "Send to WhatsApp", tint = Color.White, modifier = Modifier.size(18.dp)) }   // WhatsApp: tap = send, long-press = preview
                    Box {
                        IconButton(onClick = { overflowOpen = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                        androidx.compose.material3.DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(text = { Text("Search") }, onClick = { overflowOpen = false; searchOpen = !searchOpen; if (!searchOpen) searchQ = "" })
                            if (theList != null) DropdownMenuItem(text = { Text("List options…") }, onClick = { overflowOpen = false; listMenuOpen = true })
                            DropdownMenuItem(text = { Text("Shopping trip") }, onClick = { overflowOpen = false; showTrip = true })
                            if (personalUnlocked) DropdownMenuItem(text = { Text("Lock personal items") }, onClick = { overflowOpen = false; Engine.lockPersonal() })
                            DropdownMenuItem(text = { Text("Buy settings") }, onClick = { overflowOpen = false; onOpenSettings() })
                        }
                    }
                  } else {
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQ = "" }) {
                        Icon(Icons.Filled.Search, "Search", tint = Color.White)
                    }
                    if (tab == Tab.SHOP) {
                        if (personalUnlocked) {
                            IconButton(onClick = { Engine.lockPersonal() }) {
                                Icon(Icons.Filled.Lock, "Lock personal items", tint = Color.White)
                            }
                        }
                        // v1.15 item 7: shopping-trip mode
                        IconButton(onClick = { showTrip = true }) {
                            Icon(Icons.Filled.ShoppingCartCheckout, "Shopping trip", tint = Color.White)
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, "${tab.title} settings", tint = Color.White)
                    }
                  }
                },
                bottomContent = {
                    Column {
                        if (searchOpen) {
                            OutlinedTextField(
                                value = searchQ, onValueChange = { searchQ = it },
                                placeholder = { Text("Search ${tab.title.lowercase()}…", color = Color.White.copy(alpha = 0.8f)) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.6f),
                                    cursorColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            )
                        }
                        // v1.30 item 1: Shop gets the compact toggle, packed left with its dropdowns.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // v1.86 (N18): calendar mode filters by TIME — the words follow.
                            ViewToggle(
                                view = view, pal = pal,
                                buyNowShopName = buyNowShop?.name,
                                compact = tab == Tab.SHOP || calendarMode,
                                labels = toggleLabels(calendarMode)
                            ) { onSwitchView(it) }
                            if (shareOk && !inList) {
                                Spacer(Modifier.width(6.dp))
                                // v1.87 (N17): "Via Shared" — Q9: no push; this dot IS the badge.
                                SharedHubButton(sharePendingCount, tint = Color.White) { showSharedHub = true }
                            }
                            if (tab == Tab.SHOP && theList?.personal == true) {
                                // v2.11 (N48 G2): a Private list is Personal throughout — no General/Personal switch.
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Filled.Lock, "Private list", tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            } else if (tab == Tab.SHOP) {
                                Spacer(Modifier.width(8.dp))
                                HeaderDropdown(
                                    icon = if (personalFilter) Icons.Filled.Lock else Icons.Filled.Person,
                                    options = listOf("General", "Personal"),
                                    selected = if (personalFilter) 1 else 0
                                ) { i ->
                                    if (i == 0) setPersonalFilter(false)
                                    else when {
                                        !PinStore.isSet() -> showSetPin = true
                                        personalUnlocked -> setPersonalFilter(true)
                                        else -> showPinDialog = true
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            // v1.9: sort control is a dropdown, right-aligned on the same row.
                            val altLabel = if (tab == Tab.LEARN) "By Topic" else "By Group"
                            val curSort = sortOf(settings, tab)
                            if (inList) {
                                // v2.11 (N48): inside a list — every item is in the same list, so "By Group" gives
                                // way to By Shop / By Category (+ Priority, Date, none). Buy Now groups by list.
                                if (openList != BUY_NOW_LIST_ID) {
                                    val keys = listOf("SHOP", "CATEGORY", "PRIORITY", "DATE", "NONE")
                                    HeaderDropdown(
                                        icon = Icons.Filled.SwapVert,
                                        options = listOf("By Shop", "By Category", "By Priority", "By Date", "No Group"),
                                        selected = keys.indexOf(settings.buyInnerSort).coerceAtLeast(0)
                                    ) { i -> SettingsStore.update { it.copy(buyInnerSort = keys[i]) } }
                                }
                            } else {
                            fun writeSort(v: String) = SettingsStore.update {
                                when (tab) {
                                    Tab.SHOP -> it.copy(shopSort = v, shopGroupByGroup = v == "GROUP")
                                    Tab.LEARN -> it.copy(learnSort = v, learnGroupByTopic = v == "GROUP")
                                    else -> it.copy(tasksSort = v, tasksGroupByGroup = v == "GROUP")
                                }
                            }
                            HeaderDropdown(
                                icon = Icons.Filled.SwapVert,
                                // v1.25: the fourth option — a flat list in the order things were added.
                                // v1.43 Shop "By Shop"; v1.45 Tasks "From Calendar".
                                options = when (tab) {
                                    Tab.SHOP -> listOf("By Date", "By Group", "By Priority", "By Shop", "No Group")
                                    Tab.TASKS -> listOf("By Date", "By Group", "By Priority", "No Group", "From Calendar")
                                    else -> listOf("By Date", altLabel, "By Priority", "No Group")
                                },
                                selected = when (tab) {
                                    Tab.SHOP -> when (curSort) { "GROUP" -> 1; "PRIORITY" -> 2; "SHOP" -> 3; "NONE" -> 4; else -> 0 }
                                    Tab.TASKS -> when (curSort) { "GROUP" -> 1; "PRIORITY" -> 2; "NONE" -> 3; "CALENDAR" -> 4; else -> 0 }
                                    else -> when (curSort) { "GROUP" -> 1; "PRIORITY" -> 2; "NONE" -> 3; else -> 0 }
                                }
                            ) { i ->
                                val values = when (tab) {
                                    Tab.SHOP -> listOf("DATE", "GROUP", "PRIORITY", "SHOP", "NONE")
                                    Tab.TASKS -> listOf("DATE", "GROUP", "PRIORITY", "NONE", "CALENDAR")
                                    else -> listOf("DATE", "GROUP", "PRIORITY", "NONE")
                                }
                                writeSort(values[i])
                            }
                            }
                        }
                    }
                }
            )

            // v1.71 (N2): live degrades surface here, not only in Error Logs.
            DegradeBanner(tab) { onOpenSettings() }
            undoBatch?.let { batch ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp)).background(InkPrimary).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("${batch.size} item${if (batch.size == 1) "" else "s"} → Done", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        runCatching { batch.forEach { Engine.undoDone(context, it) }; Feedback.toast(context, "Restored ${batch.size}") }
                            .onFailure { Logger.e(context, "BULK", it, "bulk undo failed") }
                        undoBatch = null
                    }) { Text("Undo", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
            // v2.05 (N38): the Buy Now banner names the shop and carries the ONLY way to hide it.
            if (view == ListView.BUY_NOW && buyNowShop != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp)).background(pal.chipBg)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.LocationOn, null, tint = pal.onChip, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Arrived at ${buyNowShop.name}", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold, color = pal.onChip, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Showing only this shop's items · tap an item to complete it",
                            style = MaterialTheme.typography.bodySmall, color = InkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { BuyNow.hide(context); onSwitchView(ListView.ACTIVE) }) {
                        Text("Hide ✕", fontWeight = FontWeight.Bold, color = pal.onChip)
                    }
                }
                // v2.7 (N43): the arrival view is one shop group — one header, one checkbox.
                if (groupCheckOn && visible.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
                        GroupCheck(headerCheck(visible) ?: GroupCheckState.NONE, onDark = false, accent = pal.accent) {
                            bulkTarget = "Bought everything at ${buyNowShop.name}" to visible
                        }
                        Text("Bought everything here", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = pal.onChip)
                        Spacer(Modifier.weight(1f))
                        Text("${visible.size}", style = MaterialTheme.typography.labelLarge, color = InkSubtle)
                    }
                }
            }
            val calMode = calendarMode
            if (!calMode && groups.isEmpty()) {
                EmptyState(
                    when {
                        view == ListView.BUY_NOW -> "Everything here is done 🎉  Tap Hide ✕ to close Buy Now."
                        isDone -> "Nothing in Done yet."
                        tab == Tab.TASKS -> "No tasks yet. Tap + to add your first one."
                        theList != null -> "Nothing to buy on ${theList.name} yet — add items below."
                        openList == UNSORTED_LIST_ID -> "Nothing unsorted. Every item has a list."
                        tab == Tab.SHOP && personalFilter -> "No personal items. Tap + and mark an item Personal."
                        tab == Tab.SHOP -> "Your shopping list is empty. Tap + to add an item."
                        else -> "Nothing queued to learn. Tap + to add a course or topic."
                    }
                )
            }

            // v2.11 (N48): inside a list the Buy inner sort applies; the cross-list Buy Now view groups by LIST.
            val sortMode = when {
                openList == BUY_NOW_LIST_ID -> "LIST"
                inList -> settings.buyInnerSort
                else -> sortOf(settings, tab)
            }
            // v1.24 item 7: "No Group" is one flat list in insertion order — first added on top.
            val altMode = sortMode != "DATE" && sortMode != "NONE"
            val basisFn: (Item) -> Long = { groupBasis(it, isDone) }
            fun sortIn(l: List<Item>) = when {
                sortMode == "NONE" -> l.sortedWith(compareBy({ it.createdAt }, { it.id }))
                isDone -> l.sortedByDescending(basisFn)
                else -> l.sortedBy(basisFn)
            }
            val flatGroups: List<Pair<String, List<Item>>> = when (sortMode) {
                "PRIORITY" -> {
                    val names = mapOf(0 to "Urgent", 1 to "High", 2 to "Medium", 3 to "Low")
                    visible.groupBy { rankOf(it.priority) }.entries
                        .sortedBy { it.key }
                        .map { names[it.key]!! to sortIn(it.value) }
                }
                "GROUP" -> {
                val byLabel = visible.groupBy {
                    (if (tab == Tab.LEARN) it.topic else it.group)?.trim()
                        .takeUnless { g -> g.isNullOrBlank() }
                }
                byLabel.filterKeys { it != null }.entries
                    .sortedWith(compareBy(
                        { e -> if (tab == Tab.SHOP) settings.shopGroupOrder.indexOf(e.key!!).let { if (it < 0) Int.MAX_VALUE else it } else 0 },
                        { e -> e.key!!.lowercase() }
                    ))
                    .map { it.key!! to sortIn(it.value) } +
                        (byLabel[null]?.let {
                            listOf((if (tab == Tab.LEARN) "No Topic" else "No Group") to sortIn(it))
                        } ?: emptyList())
                }
                "SHOP" -> shopGroupsOf(visible).map { (k, v) -> k to sortIn(v) }
                "CATEGORY" -> categoryGroupsOf(visible, products.associateBy { it.id }).map { (k, v) -> k to sortIn(v) }   // v2.11 (N48)
                "LIST" -> listGroupsOf(visible, settings.shopLists).map { (k, v) -> k to sortIn(v) }                     // v2.11 (N48)
                else -> emptyList()
            }

            if (calMode) CalendarPane(calendarReadWindowDays(settings), isDone, Modifier.weight(1f)) { calEventCount = it } else
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 10.dp, bottom = 96.dp)
            ) {
                if (altMode) {
                    flatGroups.forEach { (gLabel, gItems) ->
                        val gKey = "g-$gLabel"
                        item(key = gKey) {
                            Box(Modifier.animateItemPlacement()) {
                                MonthHeader(
                                    // v2.9 (N45): the group's emoji icon leads the label
                                    (if (sortMode == "GROUP") groupIconFor(gLabel, settings)?.let { "$it " } ?: "" else "") +
                                    gLabel + (if (tab == Tab.SHOP) spendLabel(gItems) else if (tab == Tab.LEARN) hoursLabel(gItems) else ""),
                                    gItems.size, gKey in collapsedMonths, pal,
                                    onToggle = { collapsedMonths = toggle(collapsedMonths, gKey) },
                                    onClear = if (isDone && settings.showDeleteOnDone) ({ clearTarget = gLabel to gItems }) else null,
                                    // v1.87 (N17): active Tasks/Shop groups can be shared as a list.
                                    onShare = if (shareOk && !isDone) ({ shareGroup = gLabel to gItems }) else null,
                                    check = headerCheck(gItems), onCheck = headerTap(gLabel, gItems),   // v2.7 (N43)
                                    onLongPress = if (sortMode == "GROUP" && !isDone) ({ groupMenu = gLabel to gItems }) else null   // v2.9 (N45)
                                )
                            }
                        }
                        if (gKey !in collapsedMonths) {
                            items(gItems, key = { it.id }) { item ->
                                SwipeMoveRow(
                                    isDoneList = isDone,
                                    enabled = gest.cardSwipe,
                                    rightIsDone = gest.cardRightDone,
                                    vPad = dens.outerV,
                                    deleteEnabled = gest.revSwipeDelete,
                                    onComplete = { Engine.startComplete(context, item) },
                                    onRevive = { Engine.startRevive(context, item) },
                                    onDelete = { performDelete(item) },
                                    modifier = Modifier.animateItemPlacement()
                                ) {
                                    ItemCard(item, pal, isDone) {
                                        editing = it
                                        showSheet = true
                                    }
                                }
                            }
                        }
                    }
                } else if (sortMode == "NONE") {
                    // v1.24 item 7: no headers at all — every card in the order it was added.
                    items(sortIn(visible), key = { it.id }) { item ->
                        SwipeMoveRow(
                            isDoneList = isDone,
                            enabled = gest.cardSwipe,
                            rightIsDone = gest.cardRightDone,
                            vPad = dens.outerV,
                            deleteEnabled = gest.revSwipeDelete,
                            onComplete = { Engine.startComplete(context, item) },
                            onRevive = { Engine.startRevive(context, item) },
                            onDelete = { performDelete(item) },
                            modifier = Modifier.animateItemPlacement()
                        ) {
                            ItemCard(item, pal, isDone) {
                                editing = item
                                showSheet = true
                            }
                        }
                    }
                } else groups.forEach { year ->
                    item(key = "y-${year.key}") {
                        Box(Modifier.animateItemPlacement()) {
                            YearHeader(
                                year.label + (if (tab == Tab.SHOP) spendLabel(year.allItems()) else if (tab == Tab.LEARN) hoursLabel(year.allItems()) else ""),
                                year.count(), year.key in collapsedYears, pal,
                                onToggle = { collapsedYears = toggle(collapsedYears, year.key) },
                                onClear = if (isDone && settings.showDeleteOnDone) ({ clearTarget = year.label to year.allItems() }) else null,
                                check = headerCheck(year.allItems()), onCheck = headerTap(year.label, year.allItems())   // v2.7 (N43)
                            )
                        }
                    }
                    if (year.key !in collapsedYears) {
                        year.months.forEach { month ->
                            item(key = "m-${month.key}") {
                                Box(Modifier.animateItemPlacement()) {
                                    MonthHeader(
                                        month.label + monthDecor(tab, month.key, month.days.flatMap { it.items }, settings),
                                        month.count(), month.key in collapsedMonths, pal,
                                        onToggle = { collapsedMonths = toggle(collapsedMonths, month.key) },
                                        onClear = if (isDone && settings.showDeleteOnDone) ({
                                            clearTarget = "${month.label} ${year.label}" to month.days.flatMap { it.items }
                                        }) else null,
                                        check = headerCheck(month.days.flatMap { it.items }),                                 // v2.7 (N43)
                                        onCheck = headerTap("${month.label} ${year.label}", month.days.flatMap { it.items })
                                    )
                                }
                            }
                            if (month.key !in collapsedMonths) {
                                month.days.forEach { day ->
                                    item(key = "d-${day.key}") {
                                        Box(Modifier.animateItemPlacement()) {
                                            DayHeader(
                                                day.label + (if (tab == Tab.SHOP) spendLabel(day.items) else if (tab == Tab.LEARN) hoursLabel(day.items) else ""),
                                                day.items.size, day.key in collapsedDays, pal,
                                                check = headerCheck(day.items), onCheck = headerTap(day.label, day.items)   // v2.7 (N43)
                                            ) { collapsedDays = toggle(collapsedDays, day.key) }
                                        }
                                    }
                                    if (day.key !in collapsedDays) {
                                        items(day.items, key = { it.id }) { item ->
                                            SwipeMoveRow(
                                                isDoneList = isDone,
                                                enabled = gest.cardSwipe,
                                                rightIsDone = gest.cardRightDone,
                                                vPad = dens.outerV,
                                                deleteEnabled = gest.revSwipeDelete,
                                                onComplete = { Engine.startComplete(context, item) },
                                                onRevive = { Engine.startRevive(context, item) },
                                                onDelete = { performDelete(item) },
                                                modifier = Modifier.animateItemPlacement()
                                            ) {
                                                ItemCard(item, pal, isDone) {
                                                    editing = it
                                                    showSheet = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!isDone && !calendarMode) {
            // v1.60: WhatsApp-style quick-add (Krishna, with screenshot) — borderless text on a
            // full pill, subtle leading icon, ✕ inside, and the add action detached as a filled
            // accent circle. Dark mode finally gets a real dark surface here (was hardcoded white).
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // The borderless pill has no supportingText slot — the warning lives above the bar.
                if (quickDup) Text(
                    if (theList != null) "Already on this list" else "Already on your list",
                    color = DangerSoft,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 18.dp, bottom = 2.dp)
                )
                // v2.11 (N48 L4): the same item open in another list — so it isn't bought twice.
                quickOther?.let { o -> Text(
                    "Already in ${o.icon?.let { "$it " } ?: ""}${o.name}",
                    color = AmberInk,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 18.dp, bottom = 2.dp)
                ) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val darkBar = isSystemInDarkTheme()
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        color = if (darkBar) Color(0xFF1F1F28) else SurfaceCard,   // hex-ok(Q2): darkBar is a user STYLE choice; the else-branch follows the theme (Q6)
                        shadowElevation = 10.dp
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Edit, null, tint = GreyIcon)
                            TextField(
                                value = quickText,
                                onValueChange = { quickText = it },
                                placeholder = {
                                    Text(
                                        when {
                                            theList != null -> "Add to ${theList.name}…"
                                            tab == Tab.SHOP && personalFilter -> "Quick add personal item…"
                                            tab == Tab.SHOP -> "Quick add item…"
                                            tab == Tab.LEARN -> "Quick add course/topic…"
                                            else -> "Quick add task…"
                                        },
                                        color = GreyIcon
                                    )
                                },
                                textStyle = LocalTextStyle.current.fs(settings.fsButtons),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(quickFocus),
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    focusedTextColor = if (darkBar) Color.White else InkPrimary,
                                    unfocusedTextColor = if (darkBar) Color.White else InkPrimary,
                                    cursorColor = pal.accent
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
                                keyboardActions = KeyboardActions(onDone = { quickAddNow() })
                            )
                            if (tab == Tab.SHOP && settings.shopVoiceAdd) IconButton(onClick = { startVoice() }) {
                                Icon(Icons.Filled.Mic, "Speak the item", tint = pal.accent)   // v2.9 (N45)
                            }
                            IconButton(onClick = {
                                // v1.4: ✕ only hides the keyboard — the draft stays untouched.
                                kb?.hide()
                                fm.clearFocus()
                            }) {
                                Icon(Icons.Filled.Close, "Hide keyboard", tint = GreyIcon)
                            }
                        }
                        // v2.9 (N45): suggestions from the Products database while typing.
                        if (quickSuggestions.isNotEmpty() || quickRecents.isNotEmpty()) Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // v2.11 (N48 L4): "Recently bought in this list" ranks first.
                            quickRecents.forEach { r ->
                                AssistChip(
                                    onClick = { quickText = r.title; quickRecent = r },
                                    label = { Text(r.title + (qtySegment(r)?.let { " · $it" } ?: "") + " · bought here") },
                                    leadingIcon = { Icon(Icons.Filled.Repeat, null, tint = pal.accent, modifier = Modifier.size(16.dp)) }
                                )
                            }
                            quickSuggestions.forEach { p ->
                                AssistChip(
                                    onClick = { quickText = p.name; quickProduct = p },
                                    label = { Text(p.name + (p.category?.let { " · $it" } ?: "")) },
                                    leadingIcon = { Icon(Icons.Filled.Add, null, tint = pal.accent, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // v1.61: the circle MORPHS like WhatsApp's mic→send — "+" on an empty field
                    // opens the full editor; "✓" once text is typed quick-adds. Long-press (either
                    // state) opens the full editor with the draft PREFILLED as the title.
                    val plusMode = quickCircleIsPlus(quickText)
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(pal.accent)
                            .combinedClickable(
                                onClick = {
                                    if (plusMode) { quickPrefill = null; editing = null; showSheet = true }
                                    else quickAddNow()
                                },
                                onLongClick = {
                                    quickPrefill = quickText.trim().ifBlank { null }
                                    quickText = ""
                                    editing = null
                                    showSheet = true
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (plusMode) Icons.Filled.Add else Icons.Filled.Check,
                            if (plusMode) "Open full editor" else "Add",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        // v1.8: navigation order mirrors the on-screen list — date order, or group order in group mode.
        val navSort = sortOf(settings, tab)
        val ordered = if (navSort == "GROUP")
            visible.sortedWith(compareBy(
                { ((if (tab == Tab.LEARN) it.topic else it.group) ?: "\uffff").lowercase() },
                { if (isDone) -(it.doneAt ?: it.createdAt) else (it.dueAt ?: it.createdAt) }))
        else
            if (navSort == "PRIORITY")
                visible.sortedWith(
                    if (isDone) compareBy({ rankOf(it.priority) }, { -(it.doneAt ?: it.createdAt) })
                    else compareBy({ rankOf(it.priority) }, { it.dueAt ?: Long.MAX_VALUE }, { it.createdAt })
                )
            else visible.sortedBy { if (isDone) -(it.doneAt ?: it.createdAt) else (it.dueAt ?: it.createdAt) }
        val idx = editing?.let { e -> ordered.indexOfFirst { it.id == e.id } } ?: -1
        key(editing?.id) {
            AddEditSheet(tab, editing, pal,
                prefillTitle = sharePrefill?.title ?: quickPrefill,
                prefillUrl = sharePrefill?.url,
                prefillPlatform = sharePrefill?.platform,
                listPrefill = if (tab == Tab.SHOP) addList else null,
                prevItem = if (idx > 0) ordered[idx - 1] else null,
                nextItem = if (idx >= 0 && idx < ordered.size - 1) ordered[idx + 1] else null,
                onNav = { nxt -> editing = nxt },
                onSaveOnly = { item -> Engine.addOrUpdate(context, item) },
                onDuplicate = { tpl ->
                    Engine.addOrUpdate(context, tpl)
                    Ack.show("Duplicated") { Engine.deleteForever(context, tpl) }
                    showSheet = false
                },
                onDismiss = { showSheet = false; quickPrefill = null; sharePrefill = null },
                onSave = { item ->
                    // v2.11 (N48 L8): saving into a different list moves the card — say where, with Undo.
                    val before = editing
                    Engine.addOrUpdate(context, item)
                    if (tab == Tab.SHOP && before != null && before.listId != item.listId && item.id == before.id) {
                        val dest = liveLists(settings.shopLists).firstOrNull { it.id == item.listId }
                        Ack.show("Moved to " + (dest?.let { (it.icon?.let { i -> "$i " } ?: "") + it.name } ?: "Unsorted")) {
                            Engine.addOrUpdate(context, item.copy(listId = before.listId, group = before.group, personal = before.personal))
                        }
                    }
                    if (quickPrefill != null) { quickText = ""; quickPrefill = null }
                    sharePrefill = null
                    showSheet = false
                },
                // v1.24 item 1: the editor's Delete button was the last door around the rule —
                // it only appears when deletion is actually allowed for this card.
                onDelete = editing?.let { e ->
                    {
                        Engine.delete(context, e)
                        showSheet = false
                    }
                })
        }
    }
    if (showPinDialog) {
        PinDialog(
            "Personal items",
            onDismiss = { showPinDialog = false },
            onForgot = {
                if (GoogleSync.email(context) != null) {
                    forgotLauncher.launch(GoogleSync.signInIntent(context))
                } else {
                    forgotNeedsGoogle = true
                }
            }
        ) {
            Engine.personalUnlocked.value = true
            setPersonalFilter(true)
        }
    }
    if (showSetPin) {
        SetPinDialog(requireOld = false, onDismiss = { showSetPin = false }) {
            Engine.personalUnlocked.value = true
            setPersonalFilter(true)
        }
    }
    if (pinResetOpen) {
        SetPinDialog(requireOld = false, onDismiss = { pinResetOpen = false }) {
            Engine.personalUnlocked.value = true
        }
    }
    if (forgotNeedsGoogle) {
        AlertDialog(
            onDismissRequest = { forgotNeedsGoogle = false },
            title = { Text("PIN recovery needs Google") },
            text = {
                Text(
                    "Sign in with Google first (Settings → Google account & sync). " +
                            "Recovery re-confirms your account and then lets you set a fresh PIN. " +
                            "The one-time GOOGLE-SETUP.md steps must be completed for sign-in to work."
                )
            },
            confirmButton = { TextButton(onClick = { forgotNeedsGoogle = false }) { Text("OK") } }
        )
    }
    if (showLocations) {
        LocationsSheet(pal) { showLocations = false }
    }
    if (showTrip) {
        TripSheet(pal, personalFilter) { showTrip = false }
    }
    // v2.7 (N43): bulk complete / restore from a group header — standard sheet chrome, one Undo.
    groupMenu?.let { (gName, gItems) -> GroupMenuSheet(tab, gName, gItems, pal, onShare = { shareGroup = gName to gItems }, onCompleteAll = { bulkTarget = gName to gItems }) { groupMenu = null } }
    bulkTarget?.let { (label, items) ->
        val ids = bulkTargets(items, isDone)
        val targets = items.filter { it.id in ids }
        EditorSheet(
            title = if (isDone) "Restore all in $label?" else "Complete all in $label?",
            accent = pal.accent, onDismiss = { bulkTarget = null },
            actions = {
                EditorActionRow(
                    accent = pal.accent, onCancel = { bulkTarget = null }, saveEnabled = targets.isNotEmpty(),
                    saveLabel = (if (isDone) "Restore all" else "Complete all") + " (${targets.size})",
                    onSave = {
                        runCatching {
                            if (isDone) targets.forEach { Engine.undoDone(context, it) }
                            else targets.forEach { Engine.completeNow(context, it.id) }
                            Logger.e(context, "BULK", null, (if (isDone) "restored" else "completed") + " ${targets.size} in \"$label\" (${tab.name})")
                            undoBatch = if (isDone) null else targets
                            Feedback.toast(context, "${targets.size} item${if (targets.size == 1) "" else "s"} → ${if (isDone) "Active" else "Done"}")
                        }.onFailure { Logger.e(context, "BULK", it, "bulk action failed for \"$label\"") }
                        bulkTarget = null
                    }
                )
            }
        ) {
            Text("${targets.size} item${if (targets.size == 1) "" else "s"} " + (if (isDone) "return to Active" else "move to Done · Undo available for a few seconds"),
                style = MaterialTheme.typography.bodySmall, color = InkSubtle)
            Spacer(Modifier.height(6.dp))
            targets.take(8).forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
            if (targets.size > 8) Text("… and ${targets.size - 8} more", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
            if (tab == Tab.SHOP && !isDone && settings.shopCheckoutCalc)
                Text("Checkout calculator is skipped for a group — prices can be added from Done.", style = MaterialTheme.typography.bodySmall, color = InkHint, modifier = Modifier.padding(top = 6.dp))
        }
    }
    clearTarget?.let { (label, itemsToClear) ->
        // v1.79 (Q14): a recurring item sitting in Done is NOT finished — it is waiting to return.
        // Clearing the group used to delete it silently, ending the whole series. The repeats are
        // now opt-in, and the old "can't be undone" text was wrong: Engine.delete is a soft delete.
        val repeats = itemsToClear.filter { it.repeatMode != "OFF" }
        val plain = itemsToClear.size - repeats.size
        val willDelete = if (alsoRepeats) itemsToClear.size else plain
        ConfirmDialog(
            title = "Clear $label?",
            text = buildString {
                append("${itemsToClear.size} item${if (itemsToClear.size == 1) "" else "s"}")
                if (repeats.isNotEmpty()) {
                    append(if (repeats.size == itemsToClear.size) {
                        if (itemsToClear.size == 1) " · it repeats" else " · all repeat"
                    } else " · ${repeats.size} repeat${if (repeats.size == 1) "s" else ""}")
                }
                append(".\n")
                if (repeats.size == itemsToClear.size && !alsoRepeats) {
                    append("Nothing will be cleared unless you tick the box below.")
                } else {
                    append("Moved to Recently Deleted (kept 30 days).")
                }
            },
            confirmLabel = "Clear",
            checkboxLabel = if (repeats.isEmpty()) null else "Also delete repeating items",
            checkboxNote = if (repeats.isEmpty()) null else "Repeats will end and won't return.",
            checked = alsoRepeats,
            onCheckedChange = if (repeats.isEmpty()) null else ({ v: Boolean -> alsoRepeats = v }),
            confirmEnabled = willDelete > 0,
            onConfirm = {
                val doomed = bulkClearTargets(itemsToClear, alsoRepeats)   // v2.10 (N6 pt2): pure, tested
                if (alsoRepeats && repeats.isNotEmpty()) Logger.e(
                    context, "DELETE", null,
                    "bulk clear of \"$label\" ended ${repeats.size} repeating item(s)"
                )
                doomed.forEach { Engine.delete(context, it) }
            },
            onDismiss = { clearTarget = null; alsoRepeats = false }
        )
    }

    // v2.11 (N48): the list's own sheets, reachable from inside it.
    if (listShareSheet.value) ListTextShareSheet(
        theList?.name ?: "Unsorted", shareOrderFor(tabItems, settings.buyInnerSort, products), pal,
        isLocked = { it.personal && !personalUnlocked }
    ) { listShareSheet.value = false }
    theList?.let { l ->
        if (listMenuOpen) ListMenuSheet(l, tabItems, settings, pal,
            onDismiss = { listMenuOpen = false },
            onEdit = { listMenuOpen = false; listEditOpen = true },
            onShare = { listMenuOpen = false; listShareSheet.value = true },
            onDelete = { listMenuOpen = false; listDeleteOpen = true },
            onMerge = { listMenuOpen = false; listMergeOpen = true })
        if (listEditOpen) ListEditorSheet(l, "", pal, onDismiss = { listEditOpen = false }) { listEditOpen = false }
        if (listDeleteOpen) DeleteListSheet(l, pal) { listDeleteOpen = false }
        if (listMergeOpen) MergeListSheet(l, pal) { listMergeOpen = false }
    }
    deleteTarget?.let { item ->
        ConfirmDialog(
            title = "Delete permanently?",
            text = "\"${item.title}\" will be removed for good. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { Engine.delete(context, item); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun ShopFilterToggle(personal: Boolean, onGeneral: () -> Unit, onPersonal: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (!personal) Color.White else Color.Transparent)
                .clickable(onClick = onGeneral)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                "General",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (!personal) ShopPal.g1 else Color.White
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (personal) Color.White else Color.Transparent)
                .clickable(onClick = onPersonal)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Lock, null,
                tint = if (personal) ShopPal.g1 else Color.White,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Personal",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (personal) ShopPal.g1 else Color.White
            )
        }
    }
}

@Composable
private fun GroupModeToggle(byAlt: Boolean, altLabel: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(3.dp)
    ) {
        listOf(false to "By Date", true to altLabel).forEach { (alt, label) ->
            val selected = byAlt == alt
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onChange(alt) }
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) settingsPalC().g1 else SurfaceCard
                )
            }
        }
    }
}

// ================================================================ add / edit sheet

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditSheet(
    tab: Tab,
    existing: Item?,
    pal: TabPalette,
    prefillTitle: String? = null,
    prefillUrl: String? = null,
    prefillPlatform: String? = null,
    // v2.11 (N48): a NEW Buy item opened from inside a list lands in that list (shop + Private follow it).
    listPrefill: ShopList? = null,
    prevItem: Item? = null,
    nextItem: Item? = null,
    onNav: ((Item) -> Unit)? = null,
    onSaveOnly: ((Item) -> Unit)? = null,
    onDuplicate: ((Item) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val settings = SettingsStore.s.collectAsState().value
    val fsB = settings.fsButtons
    val fieldStyle = LocalTextStyle.current.fs(fsB)
    var confirmDelete by remember { mutableStateOf(false) }
    val isDoneItem = existing?.done == true

    var title by remember { mutableStateOf(existing?.title ?: prefillTitle ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    val newDefault = if (existing == null) defaultNewDue(tab, settings) else null
    var dueDate by remember { mutableStateOf(existing?.dueAt?.let(::startOfDayMs) ?: newDefault?.let(::startOfDayMs)) }
    var dueTimeMin by remember {
        mutableStateOf(
            when {
                existing?.dueAt != null && existing.dueHasTime -> minutesOfDayOf(existing.dueAt)
                existing == null && newDefault != null && newDueTimedFor(tab, settings) -> minutesOfDayOf(newDefault)
                else -> null
            }
        )
    }
    // v1.8 recurrence state
    // v1.86 (N24/N25): the kind a fresh editor OPENS on — an existing item states its truth
    // (dateless + non-repeating = No Reminders), a new one follows the per-tab/global default.
    val seedKind = remember { existing?.let { schedKindOf(it) } ?: schedDefaultFor(settings, tab) }
    var repMode by remember { mutableStateOf(existing?.repeatMode
        ?: if (seedKind == SchedKind.REPEAT) "DAILY" else "OFF") }
    var repDays by remember { mutableStateOf(existing?.repeatDays?.toSet() ?: emptySet()) }
    var repN by remember { mutableStateOf((existing?.repeatN ?: 1).toString()) }
    var repUnit by remember { mutableStateOf(existing?.repeatUnit ?: "D") }
    var repOrd by remember { mutableStateOf(existing?.repeatOrd ?: 1) }
    var repDow by remember { mutableStateOf(existing?.repeatDow ?: 1) }
    var repOrdList by remember { mutableStateOf(existing?.repeatOrdList ?: emptyList()) }
    var repCount by remember { mutableStateOf(existing?.repeatCount) }   // v1.24 item 5
    var schedKind by remember { mutableStateOf(seedKind) }
    var progress by remember { mutableStateOf(existing?.progress ?: 0) }
    var hoursText by remember { mutableStateOf(existing?.hoursSpent?.takeIf { it > 0 }?.toString() ?: "") }
    var unitSel by remember { mutableStateOf(existing?.unit) }
    var stapleOn by remember { mutableStateOf(existing?.staple ?: false) }
    var showRepeat by remember { mutableStateOf(false) }
    var priority by remember { mutableStateOf<Priority?>(existing?.priority ?: Priority.MEDIUM) }
    var alertType by remember(existing?.id) { mutableStateOf(existing?.alertType ?: "N") }   // v1.68
    var group by remember { mutableStateOf(existing?.group ?: (if (tab == Tab.SHOP) listPrefill?.name else null) ?: "") }
    var topic by remember { mutableStateOf(existing?.topic ?: "") }

    var quantity by remember { mutableStateOf(existing?.quantity ?: "") }
    var price by remember { mutableStateOf(existing?.price ?: "") }
    // v1.38: pre-fill the default shop when creating a new shop item; remove City entirely.
    // v2.11 (N48): a list's usual shop wins over the default shop for new items in that list.
    var shopName by remember { mutableStateOf(existing?.shopName ?: (if (existing == null) (listPrefill?.usualShopId?.let { ShopStore.get(it)?.name } ?: ShopStore.default()?.name) else null) ?: "") }
    var productId by remember { mutableStateOf(existing?.productId) }   // v1.90 product link
    var lapseText by remember { mutableStateOf(existing?.lapseValue?.toString() ?: "") }
    var lapseUnit by remember { mutableStateOf(existing?.lapseUnit ?: LapseUnit.DAYS) }
    var personal by remember { mutableStateOf(existing?.personal ?: (listPrefill?.personal ?: false)) }

    var platform by remember { mutableStateOf(existing?.platform ?: prefillPlatform ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: prefillUrl ?: "") }
    var platformExpanded by remember { mutableStateOf(false) }

    var titleError by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }

    val buildCurrent: () -> Item? = build@{
        if (title.isBlank()) { titleError = true; return@build null }
        val lapseValue = lapseText.toIntOrNull()?.takeIf { it > 0 }
            ?.coerceAtMost(if (lapseUnit == LapseUnit.MONTHS) 12 else 999)
        // v1.85 (N16): rebase on the LIVE record. `existing` is the open-time snapshot; a full
        // copy from it resurrected a snooze cancelled in this very editor (Save -> upsert ->
        // scheduleForItem re-armed it), and clobbered done/deletedAt changed meanwhile.
        val liveNow = existing?.let { ItemStore.get(it.id) }
        if (existing != null && liveNow != null) staleItemFields(existing, liveNow)
            .takeIf { it.isNotEmpty() }
            ?.let { Logger.e(context, "SAVE", null, "item ${existing.id}: merged live $it over stale snapshot") }
        val base = editorSaveBase(existing, liveNow) ?: Item(id = Ids.next(), tab = tab, title = "")
        val shopDone = tab == Tab.SHOP && isDoneItem
        val newDueAt: Long?
        val newHasTime: Boolean
        when {
            dueDate != null && dueTimeMin != null -> {
                newDueAt = combineDayTime(dueDate!!, dueTimeMin!!); newHasTime = true
            }
            dueDate != null -> {
                newDueAt = combineDayTime(dueDate!!, SettingsStore.s.value.defaultDueMinutes)
                newHasTime = false
            }
            dueTimeMin != null -> {
                val today = startOfDayMs(System.currentTimeMillis())
                val candidate = combineDayTime(today, dueTimeMin!!)
                newDueAt = if (candidate > System.currentTimeMillis()) candidate
                else candidate + 24L * 60 * 60 * 1000
                newHasTime = true
            }
            else -> { newDueAt = null; newHasTime = true }
        }
        // v1.14 (item 5): Repeating computes its own due — the first upcoming occurrence.
        val effMode = if (schedKind == SchedKind.REPEAT) (repMode.takeIf { it != "OFF" } ?: "DAILY") else "OFF"
        val repDueAt: Long? = if (schedKind == SchedKind.REPEAT) {
            val timeMin = dueTimeMin ?: SettingsStore.s.value.defaultDueMinutes
            // v1.15 item 3: the chosen "Starting from" anchors every mode.
            val anchorDay = dueDate ?: startOfDayMs(System.currentTimeMillis())
            val anchor = combineDayTime(anchorDay, timeMin)
            previewOccurrences(
                effMode, repDays, repN.toIntOrNull() ?: 1, repUnit,
                repOrd, repDow, repOrdList, anchor, 1,
                startFrom = anchor, spacedStep = base.spacedStep
            ).firstOrNull() ?: anchor
        } else null
        val built = base.copy(
            title = title.trim(),
            notes = notes.trim(),
            dueAt = if (shopDone) base.dueAt else (if (schedKind == SchedKind.REPEAT) repDueAt else newDueAt),
            dueHasTime = if (shopDone) base.dueHasTime else (if (schedKind == SchedKind.REPEAT) true else newHasTime),
            repeatMode = if (shopDone) base.repeatMode else effMode,
            repeatDays = if (shopDone) base.repeatDays else repDays.toList(),
            repeatN = if (shopDone) base.repeatN else (repN.toIntOrNull() ?: 1).coerceIn(1, 365),
            repeatUnit = if (shopDone) base.repeatUnit else repUnit,
            repeatOrd = if (shopDone) base.repeatOrd else repOrd,
            repeatDow = if (shopDone) base.repeatDow else repDow,
            repeatOrdList = if (shopDone) base.repeatOrdList else repOrdList,
            repeatCount = if (shopDone) base.repeatCount else sanitizeRepeatCount(repCount),  // v1.24 item 5
            progress = progress.coerceIn(0, 100),
            hoursSpent = hoursText.toDoubleOrNull() ?: 0.0,
            unit = unitSel,
            staple = stapleOn,
            priority = priority,
            alertType = alertType,
            group = if (tab == Tab.SHOP || tab == Tab.TASKS) group.trim().ifBlank { null } else base.group,
            topic = if (tab == Tab.LEARN) topic.trim().ifBlank { null } else base.topic,
            quantity = if (tab == Tab.SHOP) quantity.trim().ifBlank { null } else base.quantity,
            price = if (tab == Tab.SHOP) price.trim().ifBlank { null } else base.price,
            shopName = if (tab == Tab.SHOP) shopName.trim().ifBlank { null } else base.shopName,
            // v1.90: structured links ride along — shopId resolves from the (possibly free-text)
            // shop name so grouping/prices can use registered shops; both stay null-safe.
            productId = if (tab == Tab.SHOP) productId else base.productId,
            shopId = if (tab == Tab.SHOP) runCatching { ShopStore.byName(shopName)?.id }.getOrNull() else base.shopId,
            lapseValue = if (shopDone) lapseValue else base.lapseValue,
            lapseUnit = if (shopDone) (if (lapseValue != null) lapseUnit else null) else base.lapseUnit,
            returnAt = if (shopDone) {
                lapseValue?.let {
                    computeReturnAt(base.doneAt ?: System.currentTimeMillis(), it, lapseUnit)
                }
            } else base.returnAt,
            // v2.11 (N48): the List field IS the group; a Private list makes the item Personal.
            listId = if (tab == Tab.SHOP) listNamed(settings.shopLists, group)?.id else base.listId,
            personal = if (tab == Tab.SHOP) (personal || listNamed(settings.shopLists, group)?.personal == true) else false,
            platform = if (tab == Tab.LEARN) platform.trim().ifBlank { null } else base.platform,
            url = if (tab == Tab.LEARN) url.trim().ifBlank { null } else base.url
        )
        // v1.86 (N24): "No Reminders" saves through ONE gate — clearSchedule zeroes due/snooze/
        // repeat (⚑ a live snooze dies here; a never-alerting item must not ring — logged) and
        // preserves alertType, missedAt, returnAt, expiryAt. Shop can't reach NONE (D1 clamp).
        if (schedKind == SchedKind.NONE) {
            if (built.snoozedUntil != null || existing?.snoozedUntil != null)
                Logger.e(context, "EDITOR", null, "No Reminders save cleared a live snooze on '${built.title}'")
            clearSchedule(built)
        } else built
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SurfaceCard, windowInsets = sheetWindowInsets()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                when {
                    existing != null -> "Edit ${tab.title.lowercase().dropLast(1)}"
                    tab == Tab.TASKS -> "New task"
                    tab == Tab.SHOP -> "New shop item"
                    else -> "New learning"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = pal.g1
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleError = false },
                label = { Text("Title") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                isError = titleError,
                supportingText = { if (titleError) Text("Give it a title first", color = OverdueRed) },
                singleLine = true,
                textStyle = fieldStyle,
                modifier = Modifier.fillMaxWidth()
            )

            when (tab) {
                Tab.TASKS -> {
                    GroupDropdown("Group (optional)", group, settings.tasksGroups,
                        onPick = { group = it },
                        onAddNew = { g ->
                            SettingsStore.update { it.copy(tasksGroups = (it.tasksGroups + g).distinct()) }
                            group = g
                        })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    }
                    ScheduleBox(
                        schedKind, showNone = true, { schedKind = it },
                        dueDate, { dueDate = it }, dueTimeMin, { dueTimeMin = it },
                        repMode, repDays, repN, repUnit, repOrd, repDow, repOrdList,
                        spacedStep = existing?.spacedStep ?: 0,
                        accent = pal.accent,
                        repeatCount = repCount, repeatDone = existing?.repeatDone ?: 0,
                        onRepeatClick = { showRepeat = true }
                    )
                    GroupBox("Priority") { PriorityPicker(priority) { priority = it } }
                    // v1.86 (N24): a No-Reminders item HAS no alert to type — the box hides with it.
                    if (schedKind != SchedKind.NONE) GroupBox("Reminder Type") {
                        // v1.68: THE per-item channel — Notify default.
                        ChoiceChips(listOf("Alarm", "Ring", "Notify"),
                            listOf("A", "R", "N").indexOf(alertType).coerceAtLeast(0), pal.accent) { ix ->
                            alertType = listOf("A", "R", "N")[ix]
                        }
                    }
                    // v1.80 (Q17): its own box below Reminder Type — the rows are triggers, not types.
                    existing?.let { ComingUpBox(it) }
                    existing?.let { MissedAlertsBox(it) }   // v1.86 (N20)
                }
                Tab.SHOP -> {
                    // v1.90: product autocomplete — typing a title offers matching catalogue
                    // products; picking one links productId + fills unit and the best-price shop.
                    // Free text stays first-class: guarded so a store hiccup never blocks typing.
                    run {
                        val products = ProductStore.products.collectAsState().value
                        val sugg = if (existing == null && title.isNotBlank() && productId == null)
                            runCatching {
                                products.filter { it.deletedAt == null && productMatches(it, title) }
                                    .sortedBy { it.name.lowercase() }.take(3)
                            }.getOrDefault(emptyList())
                        else emptyList()
                        if (sugg.isNotEmpty()) Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            sugg.forEach { p ->
                                AssistChip(onClick = {
                                    runCatching {
                                        productId = p.id
                                        title = p.name
                                        p.defaultUnit?.let { unitSel = it }
                                        val liveIds = ShopStore.active().map { it.id }.toSet()
                                        cheapestLink(ProductStore.linksFor(p.id), liveIds)?.let { best ->
                                            ShopStore.get(best.shopId)?.let { shopName = it.name }
                                        }
                                    }.onFailure { Logger.e(context, "PRODUCT", it, "product pick failed") }
                                }, label = { Text("📦 ${p.name}") })
                            }
                        }
                        if (productId != null) {
                            val linked = products.firstOrNull { it.id == productId }
                            if (linked != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                                Text("Linked to product: ${linked.name}", style = MaterialTheme.typography.labelSmall, color = ShopInk)
                                TextButton(onClick = { productId = null }) { Text("Unlink", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                    ShopPicker(value = shopName, onPick = { shopName = it })
                    // v1.42 Phase C: cheapest-shop suggestion from purchase history (one-tap apply).
                    // v1.90: gated by the Shop Settings toggle; when the item is linked to a
                    // product, the product's shop-link price memory takes precedence.
                    if (settings.shopCheapestHint) run {
                        val linkRec = productId?.let { pid ->
                            runCatching {
                                val liveById = ShopStore.active().associateBy { it.id }
                                cheapestLink(ProductStore.linksFor(pid), liveById.keys)?.let { l ->
                                    liveById[l.shopId]?.let { s ->
                                        val up = if (l.lastUnitPrice > 0.0) l.lastUnitPrice else l.lastPrice
                                        ShopRec(shop = s.name, perBase = up, unit = unitSel ?: "unit",
                                            unitPrice = up, at = l.lastAt, family = "")
                                    }
                                }
                            }.getOrNull()
                        }
                        val rec = linkRec ?: cheapestShop(ItemStore.items.collectAsState().value, title, unitSel)
                        if (rec != null && title.isNotBlank() && !rec.shop.equals(shopName.trim(), ignoreCase = true)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GreenSoft)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "💡 Cheapest: ₹${fmtUnitPrice(rec.unitPrice)}/${rec.unit} at ${rec.shop}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold, color = ShopInk
                                    )
                                    Text(
                                        "last purchased ${formatDateTime(rec.at)}",
                                        style = MaterialTheme.typography.labelSmall, color = InkSubtle
                                    )
                                }
                                TextButton(onClick = { shopName = rec.shop }) {
                                    Text("Use", fontWeight = FontWeight.Bold, color = ShopInk)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = quantity, onValueChange = { quantity = it },
                            label = { Text("Quantity") }, singleLine = true,
                            textStyle = fieldStyle, modifier = Modifier.weight(1f)
                        )
                        // v1.15 item 15: unit dropdown
                        var unitOpen by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = unitOpen, onExpandedChange = { unitOpen = it }, modifier = Modifier.width(96.dp)) {
                            OutlinedTextField(
                                value = unitSel ?: "—", onValueChange = {}, readOnly = true,
                                label = { Text("Unit") }, singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitOpen) },
                                textStyle = fieldStyle, modifier = Modifier.menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = unitOpen, onDismissRequest = { unitOpen = false }) {
                                (listOf(null) + listOf("kg", "g", "L", "ml", "pcs", "dozen")).forEach { u ->
                                    DropdownMenuItem(text = { Text(u ?: "—") }, onClick = { unitSel = u; unitOpen = false })
                                }
                            }
                        }
                        OutlinedTextField(
                            value = price, onValueChange = { price = it },
                            label = { Text("Price (₹)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = fieldStyle, modifier = Modifier.weight(1f)
                        )
                    }
                    // v1.15 item 8: rise nudge vs last purchase
                    run {
                        val last = existing?.let { lastPrice(it) }
                        val typed = price.replace(",", "").replace("₹", "").trim().toDoubleOrNull()
                        val d = priceDelta(typed, last)
                        if (last != null) {
                            if (d != null && d > 0.0)
                                Text("↑ ₹${fmtExact(d)} vs last (₹${fmtExact(last)})", style = MaterialTheme.typography.bodySmall, color = DangerSoft)
                            else
                                Text("Last: ₹${fmtExact(last)}", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                        }
                    }
                    // v1.15 item 13: staple flag
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Staple ⭐", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text("One tap re-adds all staples from the Shop header", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                        }
                        Switch(checked = stapleOn, onCheckedChange = { stapleOn = it })
                    }
                    // v2.11 (N48 L8): "Group" is the LIST in Shop mode — a picker with "New list…".
                    GroupDropdown("List", group, liveLists(settings.shopLists).map { it.name },
                        onPick = { group = it },
                        onAddNew = { g -> ShopListStore.create(context, g, null, null, null, false)?.let { group = it.name } },
                        blankLabel = "Unsorted", addLabel = "+ New list…")
                    if (!isDoneItem) {
                        // Active filter: planning the purchase — the buy date lives here.
                        ScheduleBox(
                            schedKind, showNone = false, { schedKind = it },
                            dueDate, { dueDate = it }, dueTimeMin, { dueTimeMin = it },
                            repMode, repDays, repN, repUnit, repOrd, repDow, repOrdList,
                            spacedStep = existing?.spacedStep ?: 0,
                            accent = pal.accent, dateLabel = "Buy date",
                            repeatCount = repCount, repeatDone = existing?.repeatDone ?: 0,
                            onRepeatClick = { showRepeat = true }
                        )
                    } else {
                        // Done filter: bought — lapse and expiry live here.
                        Text(
                            "Lapse — it returns to your list this much after buying",
                            style = MaterialTheme.typography.bodySmall, color = InkSubtle
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = lapseText,
                                onValueChange = { if (it.length <= 3 && it.all { ch -> ch.isDigit() }) lapseText = it },
                                label = { Text("Every") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = fieldStyle, modifier = Modifier.width(110.dp)
                            )
                            ChoiceChips(
                                options = LapseUnit.entries.map { it.label },
                                selected = LapseUnit.entries.indexOf(lapseUnit),
                                accent = pal.accent
                            ) { lapseUnit = LapseUnit.entries[it] }
                        }
                        if (lapseUnit == LapseUnit.MONTHS) {
                            Text("Months can be 1–12", style = MaterialTheme.typography.labelSmall, color = InkHint)
                        }
                    }
                    GroupBox("Priority") { PriorityPicker(priority) { priority = it } }
                    GroupBox("Reminder Type") {
                        // v1.68: THE per-item channel — Notify default.
                        ChoiceChips(listOf("Alarm", "Ring", "Notify"),
                            listOf("A", "R", "N").indexOf(alertType).coerceAtLeast(0), pal.accent) { ix ->
                            alertType = listOf("A", "R", "N")[ix]
                        }
                    }
                    // v1.80 (Q17): its own box below Reminder Type — the rows are triggers, not types.
                    existing?.let { ComingUpBox(it) }
                    existing?.let { MissedAlertsBox(it) }   // v1.86 (N20)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = PillDark, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Personal (PIN locked)",
                            style = MaterialTheme.typography.bodyLarge.fs(fsB),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = personal,
                            onCheckedChange = { want ->
                                if (want && !PinStore.isSet()) showSetPin = true else personal = want
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = pal.accent)
                        )
                    }
                }
                Tab.LEARN -> {
                    GroupDropdown("Topic (optional)", topic, settings.learnTopics,
                        onPick = { topic = it },
                        onAddNew = { g ->
                            SettingsStore.update { it.copy(learnTopics = (it.learnTopics + g).distinct()) }
                            topic = g
                        })
                    ScheduleBox(
                        schedKind, showNone = true, { schedKind = it },
                        dueDate, { dueDate = it }, dueTimeMin, { dueTimeMin = it },
                        repMode, repDays, repN, repUnit, repOrd, repDow, repOrdList,
                        spacedStep = existing?.spacedStep ?: 0,
                        accent = pal.accent,
                        repeatCount = repCount, repeatDone = existing?.repeatDone ?: 0,
                        onRepeatClick = { showRepeat = true }
                    )
                    // v1.15 item 4: Learn mechanics — progress + hours.
                    Text("Progress: $progress%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = progress.toFloat(), onValueChange = { progress = it.toInt().coerceIn(0, 100) },
                        valueRange = 0f..100f
                    )
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { s -> if (s.length <= 6 && s.all { it.isDigit() || it == '.' }) hoursText = s },
                        label = { Text("Hours spent") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = fieldStyle, modifier = Modifier.fillMaxWidth()
                    )
                    ExposedDropdownMenuBox(
                        expanded = platformExpanded,
                        onExpandedChange = { platformExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = platform,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Platform") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = platformExpanded) },
                            textStyle = fieldStyle,
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = platformExpanded,
                            onDismissRequest = { platformExpanded = false }
                        ) {
                            PLATFORM_OPTIONS.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = { platform = opt; platformExpanded = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Course / URL (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        textStyle = fieldStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                    GroupBox("Priority") { PriorityPicker(priority) { priority = it } }
                    // v1.86 (N24): a No-Reminders item HAS no alert to type — the box hides with it.
                    if (schedKind != SchedKind.NONE) GroupBox("Reminder Type") {
                        // v1.68: THE per-item channel — Notify default.
                        ChoiceChips(listOf("Alarm", "Ring", "Notify"),
                            listOf("A", "R", "N").indexOf(alertType).coerceAtLeast(0), pal.accent) { ix ->
                            alertType = listOf("A", "R", "N")[ix]
                        }
                    }
                    // v1.80 (Q17): its own box below Reminder Type — the rows are triggers, not types.
                    existing?.let { ComingUpBox(it) }
                    existing?.let { MissedAlertsBox(it) }   // v1.86 (N20)
                }
            }


            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) { Text("Delete", fontWeight = FontWeight.Bold, color = OverdueRed, maxLines = 1, style = MaterialTheme.typography.bodyLarge.fs(fsB)) }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                ) { Text("Cancel", fontWeight = FontWeight.Bold, color = InkSubtle, maxLines = 1, style = MaterialTheme.typography.bodyLarge.fs(fsB)) }
                Button(
                    onClick = { buildCurrent()?.let(onSave) },
                    colors = ButtonDefaults.buttonColors(containerColor = pal.accent),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                ) {
                    Text(
                        if (existing == null) "Add" else "Save",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyLarge.fs(fsB)
                    )
                }
            }

            if (existing != null) {
                // v1.8: Save/Discard & Previous/Next — order mirrors the on-screen list.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { buildCurrent()?.let { onSaveOnly?.invoke(it); prevItem?.let { p -> onNav?.invoke(p) } } },
                        enabled = prevItem != null && onNav != null,
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Icon(Icons.Filled.ChevronLeft, null, tint = pal.accent)
                        Text("Save & Prev", color = pal.accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { buildCurrent()?.let { onSaveOnly?.invoke(it); nextItem?.let { n -> onNav?.invoke(n) } } },
                        enabled = nextItem != null && onNav != null,
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Text("Save & Next", color = pal.accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Icon(Icons.Filled.ChevronRight, null, tint = pal.accent)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { prevItem?.let { p -> onNav?.invoke(p) } },
                        enabled = prevItem != null && onNav != null,
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Icon(Icons.Filled.ChevronLeft, null, tint = InkSubtle)
                        Text("Skip", color = InkSubtle, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { nextItem?.let { n -> onNav?.invoke(n) } },
                        enabled = nextItem != null && onNav != null,
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Text("Skip", color = InkSubtle, maxLines = 1)
                        Icon(Icons.Filled.ChevronRight, null, tint = InkSubtle)
                    }
                }
                if (onDuplicate != null) {
                    OutlinedButton(
                        onClick = {
                            buildCurrent()?.let { cur ->
                                onDuplicate(cur.copy(id = Ids.next(), title = cur.title + " (copy)", done = false, doneAt = null, returnAt = null))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, tint = pal.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Duplicate", color = pal.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                minLines = 2,
                textStyle = fieldStyle,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            SheetBottomSpace()   // v2.01 (N32): Option D clearance
            Spacer(Modifier.height(18.dp))
        }
    }

    if (showRepeat) {
        RepeatDialog(
            repMode, repDays, repN, repUnit, repOrd, repDow, repOrdList, pal.accent,
            showSpaced = tab == Tab.LEARN,
            count0 = repCount,
            onDone = { m, d, n, u, o, w, pl, cnt ->
                repMode = m; repDays = d; repN = n; repUnit = u; repOrd = o; repDow = w; repOrdList = pl
                repCount = cnt
                showRepeat = false
            },
            onCancel = { showRepeat = false }
        )
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Delete permanently?",
            text = "\"${title.ifBlank { "This item" }}\" will be removed for good. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }

    if (showSetPin) {
        SetPinDialog(requireOld = false, onDismiss = { showSetPin = false }) {
            personal = true
            Engine.personalUnlocked.value = true
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityPicker(priority: Priority?, onChange: (Priority?) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Priority.entries.forEach { p ->
            val sel = priority == p
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) priorityColor(p) else priorityContainer(p))
                    .clickable { onChange(if (sel) null else p) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    p.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) Color.White else priorityColor(p),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ---- v1.8 recurrence UI ----

val ANCHORED_MODES = setOf("EVERY_N", "QUARTERLY", "HALFYEARLY", "YEARLY")

/** v1.15 items 9+12: month-header decorations for the Shop tab — spend, budget, untracked. */
fun monthDecor(tab: Tab, monthKey: String, items: List<Item>, s: AppSettings): String {
    if (tab == Tab.LEARN) return hoursLabel(items)
    if (tab != Tab.SHOP) return ""
    val spend = shopSpend(items)
    val untracked = s.shopUntracked[monthKey] ?: 0.0
    val cur = monthKey == monthKey(System.currentTimeMillis())
    val base = when {
        cur && s.shopBudgetMonthly > 0 -> " · ₹${fmtExact(spend)}/₹${fmtExact(s.shopBudgetMonthly.toDouble())}" +
            (if (spend > s.shopBudgetMonthly) " ⚠" else "")
        spend > 0 -> " · ₹${fmtExact(spend)}"
        else -> ""
    }
    return base + (if (untracked > 0) " (+₹${fmtExact(untracked)} untracked)" else "")
}

/** v1.15 item 26: the Schedule box — radios + Time on row 1, date/controller on row 2,
 *  preview + View-all on row 3. Time never owns a full row. */
@Composable
fun ScheduleBox(
    // v1.86 (N24): tri-state kind replaces the Boolean. showNone gates the third radio —
    // Tasks + Learn offer it, Shop and Calls never see it (D1/D2).
    kind: SchedKind, showNone: Boolean, onKind: (SchedKind) -> Unit,
    dueDate: Long?, onDate: (Long?) -> Unit,
    timeMin: Int?, onTime: (Int?) -> Unit,
    mode: String, days: Set<Int>, nStr: String, unit: String,
    ord: Int, dow: Int, ordList: List<Int>, spacedStep: Int,
    accent: Color, dateLabel: String = "Due date",
    repeatCount: Int? = null, repeatDone: Int = 0,
    onRepeatClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            // v1.16 item 3: border matches the editor's other outlined elements (theme-aware).
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // v1.16 item 1: radios alone on row 1; Date + Time share row 2;
        // the repeat controller gets its own row; preview + View all close the box.
        ScheduleKindRadio(kind, showNone, accent, onKind)
        // v1.86 (N24): under "No Reminders" the pickers grey out and swallow taps — the fields
        // stay visible (nothing jumps), the hint says why. DateField/TimeField have no enabled
        // param, so an overlay eats the clicks.
        Box {
            // v1.18 item 1: Date and Time split the row 50/50.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.alpha(if (kind == SchedKind.NONE) 0.38f else 1f)
            ) {
                Box(Modifier.weight(1f)) {
                    DateField(if (kind == SchedKind.REPEAT) "Starting from" else dateLabel, dueDate, accent) { onDate(it) }
                }
                Box(Modifier.weight(1f)) { TimeField("Time", timeMin, accent) { onTime(it) } }
            }
            if (kind == SchedKind.NONE) Box(
                Modifier
                    .matchParentSize()
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
            )
        }
        if (kind == SchedKind.NONE) Text(
            "This item never alerts.",
            style = MaterialTheme.typography.labelSmall, color = InkHint
        )
        if (kind == SchedKind.REPEAT) {
            RepeatRow(mode, days, nStr, unit, ord, dow, ordList, accent, onRepeatClick)
            var showAll by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    RepeatPreviewLine(mode, days, nStr, unit, ord, dow, ordList, dueDate, timeMin, spacedStep, repeatCount, repeatDone)
                }
                TextButton(onClick = { showAll = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("View all", color = accent, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (showAll) ViewAllDialog(mode, days, nStr, unit, ord, dow, ordList, dueDate, timeMin, spacedStep, repeatCount, repeatDone) { showAll = false }
        }
    }
}

/** v1.15 item 2: every occurrence for the next 365 days, month-grouped. */
@Composable
fun ViewAllDialog(
    mode: String, days: Set<Int>, nStr: String, unit: String,
    ord: Int, dow: Int, ordList: List<Int>, dateAnchor: Long?, timeMin: Int?, spacedStep: Int,
    repeatCount: Int? = null, repeatDone: Int = 0,
    onClose: () -> Unit
) {
    val zone = java.time.ZoneId.systemDefault()
    val anchorDate = dateAnchor ?: System.currentTimeMillis()
    val anchor = anchorDate.toLocalDate()
        .atTime((timeMin ?: SettingsStore.s.value.defaultDueMinutes) / 60, (timeMin ?: SettingsStore.s.value.defaultDueMinutes) % 60)
        .atZone(zone).toInstant().toEpochMilli()
    val until = System.currentTimeMillis() + 365L * 86_400_000L
    // v1.25: a repeat that ends after N must not advertise a full year of dates.
    val occ = remember(mode, days, nStr, unit, ord, dow, ordList, anchor, repeatCount, repeatDone) {
        val all = occurrencesWithin(mode, days, nStr.toIntOrNull() ?: 1, unit, ord, dow, ordList, anchor, anchor, until, 400, spacedStep)
        val remaining = repeatCount?.let { (it - repeatDone).coerceAtLeast(0) }
        if (remaining == null) all else all.take(remaining)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (repeatCount != null) "Ends after $repeatCount · ${occ.size} left"
                else "Next 365 days · ${occ.size} times",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(Modifier.heightIn(max = 420.dp)) {
                LazyColumn {
                    var lastMonth = ""
                    occ.forEach { ms ->
                        val mk = formatMonth(ms) + " " + ms.toLocalDate().year
                        if (mk != lastMonth) {
                            lastMonth = mk
                            item { Text(mk, fontWeight = FontWeight.Bold, color = PillTextIdle,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                        }
                        item { Text(formatDayTime(ms), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
fun ScheduleKindRadio(kind: SchedKind, showNone: Boolean, accent: Color, onChange: (SchedKind) -> Unit) {
    // v1.86 (N24): "No Reminders" joins Tasks + Learn (Krishna D1: ②); "Repeating" is renamed
    // "Repeat" in ALL sheets — one radio composable, so the rename cannot drift per-tab.
    val opts = buildList {
        if (showNone) add(SchedKind.NONE to "No Reminders")
        add(SchedKind.ONCE to "One-time")
        add(SchedKind.REPEAT to "Repeat")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        opts.forEach { (k, label) ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onChange(k) }) {
                RadioButton(selected = kind == k, onClick = { onChange(k) },
                    colors = RadioButtonDefaults.colors(selectedColor = accent))
                Text(label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (kind == k) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** v1.14 (item 5): the next 3 occurrences, live under the Repeat row. */
@Composable
fun RepeatPreviewLine(
    mode: String, days: Set<Int>, nStr: String, unit: String,
    ord: Int, dow: Int, ordList: List<Int>, dateAnchor: Long?, timeMin: Int?, spacedStep: Int = 0,
    repeatCount: Int? = null, repeatDone: Int = 0
) {
    if (mode == "OFF") return
    val tm = timeMin ?: SettingsStore.s.value.defaultDueMinutes
    // v1.15 item 3: the chosen start date anchors EVERY mode — first hit on/after it.
    val anchorDay = dateAnchor ?: startOfDayMs(System.currentTimeMillis())
    val anchor = combineDayTime(anchorDay, tm)
    val remaining = repeatCount?.let { (it - repeatDone).coerceAtLeast(0) }
    val raw = previewOccurrences(mode, days, nStr.toIntOrNull() ?: 1, unit, ord, dow, ordList,
        anchor, 3, startFrom = anchor, spacedStep = spacedStep)
    // v1.25: never show more occurrences than the repeat has left to give.
    val next = if (remaining == null) raw else raw.take(remaining)
    if (next.isEmpty()) {
        if (remaining == 0) Text(
            "This repeat has finished — no occurrences left.",
            style = MaterialTheme.typography.bodySmall, color = InkSubtle
        )
        return
    }
    val endsHere = remaining != null && next.size >= remaining
    Text(
        "Next: " + next.joinToString("  ·  ") { formatDay(it) } +
            (if (repeatCount != null) "  ·  ${repeatDone + next.size} of $repeatCount" else "") +
            (if (endsHere) " · ends" else ""),
        style = MaterialTheme.typography.bodySmall, color = ShopInk,
        fontWeight = FontWeight.Medium
    )
}

@Composable
fun RepeatRow(mode: String, days: Set<Int>, n: String, unit: String, ord: Int, dow: Int, ordList: List<Int>, accent: Color, onClick: () -> Unit) {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val label = when (mode) {
        "DAILY" -> "Daily"
        "WEEKLY" -> "Weekly · " + days.sorted().joinToString(",") { names[(it - 1).coerceIn(0, 6)] }.ifBlank { "—" }
        "MONTHLY_DAY" -> {
            val ds = days.filter { it in 1..31 }.sorted()
            if (ds.isEmpty()) "Monthly · same day" else "Monthly · " + ds.joinToString(",")
        }
        "MONTHLY_ORD" -> {
            val ords = listOf("First", "Second", "Third", "Fourth", "Last")
            val pats = ordList.ifEmpty { listOf(ord * 10 + dow) }
            pats.joinToString(" + ") { p -> ords[((p / 10) - 1).coerceIn(0, 4)] + " " + names[((p % 10) - 1).coerceIn(0, 6)] }
        }
        "QUARTERLY" -> "Quarterly"
        "HALFYEARLY" -> "Half-yearly"
        "YEARLY" -> "Yearly"
        "EVERY_N" -> "Every ${n.ifBlank { "1" }} " + when (unit) { "W" -> "week(s)"; "M" -> "month(s)"; else -> "day(s)" }
        else -> "Off"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceSubtle)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Icon(Icons.Filled.Repeat, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Repeat", style = MaterialTheme.typography.labelMedium, color = GreyIcon)
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = GreyIcon)
    }
}

@Composable
fun RepeatDialog(
    mode0: String, days0: Set<Int>, n0: String, unit0: String, ord0: Int, dow0: Int, ordList0: List<Int>, accent: Color,
    showSpaced: Boolean = false,
    count0: Int? = null,
    onDone: (String, Set<Int>, String, String, Int, Int, List<Int>, Int?) -> Unit,
    onCancel: () -> Unit
) {
    // v1.24 item 5: the engine could already end a repeat after N occurrences — until now
    // there was no way to say so. N starts at 2 and has no ceiling.
    var ends by remember { mutableStateOf(count0 != null) }
    var countStr by remember { mutableStateOf((count0 ?: 2).toString()) }
    var mode by remember { mutableStateOf(mode0) }
    var wdays by remember { mutableStateOf(if (mode0 == "WEEKLY") days0 else emptySet()) }
    var mdays by remember { mutableStateOf(if (mode0 == "MONTHLY_DAY") days0.filter { it in 1..31 }.toSet() else emptySet()) }
    var n by remember { mutableStateOf(n0) }
    var unit by remember { mutableStateOf(unit0) }
    var ord by remember { mutableStateOf(ord0) }
    var dow by remember { mutableStateOf(dow0) }
    var pats by remember {
        mutableStateOf(if (mode0 == "MONTHLY_ORD") ordList0.ifEmpty { listOf(ord0 * 10 + dow0) } else emptyList())
    }
    var monthlyOrdinal by remember { mutableStateOf(mode0 == "MONTHLY_ORD") }
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val ordNames = listOf("First", "Second", "Third", "Fourth", "Last")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Repeat", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                @Composable fun chip(label: String, sel: Boolean, onC: () -> Unit) {
                    Text(
                        label,
                        color = if (sel) Color.White else InkStrong,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (sel) accent else SurfaceSubtle)
                            .clickable(onClick = onC)
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chip("Off", mode == "OFF") { mode = "OFF" }
                    chip("Daily", mode == "DAILY") { mode = "DAILY" }
                    chip("Weekly", mode == "WEEKLY") { mode = "WEEKLY" }
                    chip("Monthly", mode.startsWith("MONTHLY")) { mode = if (monthlyOrdinal) "MONTHLY_ORD" else "MONTHLY_DAY" }
                    chip("Quarterly", mode == "QUARTERLY") { mode = "QUARTERLY" }
                    chip("Half-yearly", mode == "HALFYEARLY") { mode = "HALFYEARLY" }
                    chip("Yearly", mode == "YEARLY") { mode = "YEARLY" }
                    chip("Every N", mode == "EVERY_N") { mode = "EVERY_N" }
                    if (showSpaced) chip("Spaced revisit", mode == "SPACED") { mode = "SPACED" }
                }
                if (mode == "SPACED") Text(
                    "Learning ladder: it resurfaces after 3 → 7 → 14 → 30 days, then every 30. Each completion climbs a step.",
                    style = MaterialTheme.typography.bodySmall, color = ShopInk
                )
                when {
                    mode == "WEEKLY" -> {
                        Text("On these days:", style = MaterialTheme.typography.labelLarge)
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            dayNames.forEachIndexed { i, d ->
                                chip(d, (i + 1) in wdays) {
                                    wdays = if ((i + 1) in wdays) wdays - (i + 1) else wdays + (i + 1)
                                }
                            }
                        }
                    }
                    mode.startsWith("MONTHLY") -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chip("Days of month", !monthlyOrdinal) { monthlyOrdinal = false; mode = "MONTHLY_DAY" }
                            chip("Weekday patterns", monthlyOrdinal) { monthlyOrdinal = true; mode = "MONTHLY_ORD" }
                        }
                        if (!monthlyOrdinal) {
                            Text("Pick the dates (e.g. 11, 17 and 29):", style = MaterialTheme.typography.labelLarge)
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                (1..31).forEach { d ->
                                    chip(d.toString(), d in mdays) {
                                        mdays = if (d in mdays) mdays - d else mdays + d
                                    }
                                }
                            }
                            Text(
                                "Empty = the due date's own day. A 29/30/31 lands on shorter months' last day.",
                                style = MaterialTheme.typography.bodySmall, color = GreyIcon
                            )
                        } else {
                            Text("Build patterns, add as many as you like:", style = MaterialTheme.typography.labelLarge)
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ordNames.forEachIndexed { i, o -> chip(o, ord == i + 1) { ord = i + 1 } }
                            }
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                dayNames.forEachIndexed { i, d -> chip(d, dow == i + 1) { dow = i + 1 } }
                            }
                            OutlinedButton(
                                onClick = {
                                    val p = ord * 10 + dow
                                    if (p !in pats) pats = pats + p
                                },
                                modifier = Modifier.height(38.dp)
                            ) { Text("+ Add " + ordNames[ord - 1] + " " + dayNames[dow - 1], color = accent, fontWeight = FontWeight.SemiBold) }
                            if (pats.isNotEmpty()) {
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    pats.forEach { p ->
                                        val lbl = ordNames[((p / 10) - 1).coerceIn(0, 4)] + " " + dayNames[((p % 10) - 1).coerceIn(0, 6)]
                                        chip("$lbl ✕", true) { pats = pats - p }
                                    }
                                }
                            }
                            Text("e.g. First Monday + Last Saturday — the earliest hit wins each month.",
                                style = MaterialTheme.typography.bodySmall, color = GreyIcon)
                        }
                    }
                    mode == "QUARTERLY" || mode == "HALFYEARLY" || mode == "YEARLY" -> {
                        Text(
                            when (mode) {
                                "QUARTERLY" -> "Every 3 months from the due date."
                                "HALFYEARLY" -> "Every 6 months from the due date."
                                else -> "Every year on the due date (29 Feb lands on 28 Feb off leap years)."
                            },
                            style = MaterialTheme.typography.bodySmall, color = GreyIcon
                        )
                    }
                    mode == "EVERY_N" -> {
                        // v1.9: the number field gets its own row; the unit chips get theirs.
                        OutlinedTextField(
                            value = n, onValueChange = { v -> n = v.filter { it.isDigit() }.take(3) },
                            label = { Text("Every") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            chip("days", unit == "D") { unit = "D" }
                            chip("weeks", unit == "W") { unit = "W" }
                            chip("months", unit == "M") { unit = "M" }
                        }
                    }
                }
                if (mode != "OFF") {
                    Text(
                        "Completing sends it to Done for today — it returns to Active at midnight of the next occurrence day.",
                        style = MaterialTheme.typography.bodySmall, color = ShopInk
                    )
                }
                if (mode != "OFF") {
                    Spacer(Modifier.padding(3.dp))
                    Text("End", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Never ends", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = !ends, onCheckedChange = { on -> ends = !on })
                    }
                    if (ends) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ends after", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { countStr = ((countStr.toIntOrNull() ?: 2) - 1).coerceAtLeast(2).toString() },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("−") }
                        OutlinedTextField(
                            value = countStr,
                            onValueChange = { v -> countStr = v.filter { ch -> ch.isDigit() }.take(4) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(84.dp)
                        )
                        OutlinedButton(
                            onClick = { countStr = ((countStr.toIntOrNull() ?: 2) + 1).coerceAtLeast(2).toString() },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Text("+") }
                        Text("times", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (ends) Text(
                        "It finishes in the Done list after the last one — nothing disappears.",
                        style = MaterialTheme.typography.labelSmall, color = InkHint
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = when (mode) {
                    "WEEKLY" -> wdays.ifEmpty { setOf(1) }
                    "MONTHLY_DAY" -> mdays
                    else -> emptySet()
                }
                val pl = if (mode == "MONTHLY_ORD") pats.ifEmpty { listOf(ord * 10 + dow) } else emptyList()
                onDone(mode, d, n.ifBlank { "1" }, unit, ord, dow, pl,
                    if (ends) sanitizeRepeatCount(countStr.toIntOrNull() ?: 2) else null)
            }) { Text("Set", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

// ---- v1.6 date/time helpers ----

fun startOfDayMs(t: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = t
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

fun minutesOfDayOf(t: Long): Int {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = t
    return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
}

fun combineDayTime(dayMs: Long, minutes: Int): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = dayMs
    cal.set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
    cal.set(java.util.Calendar.MINUTE, minutes % 60)
    cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** v1.6: pick a group from the predefined list, Blank, or add a new one inline. */
/**
 * v1.39 Phase B — the checkout calculator shown when a Shop item is completed. Requires a shop;
 * fills the missing one of {unit price, qty, cost}; optional buy price yields discount + %.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopCompleteDialog(
    item: Item,
    onDismiss: () -> Unit,
    onConfirm: (shop: String, calc: ShopCalc, unit: String?) -> Unit
) {
    val accent = ShopPal.accent
    var shop by remember { mutableStateOf(item.shopName ?: ShopStore.default()?.name ?: "") }
    var unit by remember { mutableStateOf(item.unit) }
    var unitOpen by remember { mutableStateOf(false) }
    var unitPriceT by remember { mutableStateOf("") }
    var qtyT by remember { mutableStateOf(item.quantity ?: "") }
    var costT by remember { mutableStateOf(item.price ?: "") }
    var paidT by remember { mutableStateOf("") }

    fun d(s: String): Double? = s.replace(",", "").replace("₹", "").trim().toDoubleOrNull()
    val calc = computeShopCalc(d(unitPriceT), d(qtyT), d(costT), d(paidT))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Complete purchase", fontWeight = FontWeight.Bold, color = accent) },
        text = {
            Column {
                Text(item.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(3.dp))
                ShopPicker(value = shop, onPick = { shop = it })
                Spacer(Modifier.padding(2.dp))
                // unit
                ExposedDropdownMenuBox(expanded = unitOpen, onExpandedChange = { unitOpen = it }) {
                    OutlinedTextField(
                        value = unit ?: "—", onValueChange = {}, readOnly = true,
                        label = { Text("Unit") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = unitOpen, onDismissRequest = { unitOpen = false }) {
                        (listOf(null) + listOf("kg", "g", "L", "ml", "pcs", "dozen")).forEach { u ->
                            DropdownMenuItem(text = { Text(u ?: "—") }, onClick = { unit = u; unitOpen = false })
                        }
                    }
                }
                Spacer(Modifier.padding(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unitPriceT, onValueChange = { unitPriceT = it },
                        label = { Text("Unit price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = qtyT, onValueChange = { qtyT = it },
                        label = { Text("Quantity") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = costT, onValueChange = { costT = it },
                    label = { Text("Cost / MRP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = paidT, onValueChange = { paidT = it },
                    label = { Text("Buy price (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.padding(3.dp))
                // live computed summary
                val parts = buildList {
                    calc.cost?.let { add("Cost ₹%.2f".format(it)) }
                    if (calc.unitPrice != null && unit != null) add("₹${fmtUnitPrice(calc.unitPrice)}/$unit")
                    calc.discountPct?.let { add("Disc %.1f%% (₹%.2f)".format(it, calc.discountAmt ?: 0.0)) }
                }
                if (parts.isNotEmpty()) {
                    Text(parts.joinToString("  ·  "), color = accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Enter any two of unit price / quantity / cost.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = shop.trim().isNotEmpty() && calc.cost != null,
                onClick = { onConfirm(shop.trim(), calc, unit) }
            ) { Text("Complete", fontWeight = FontWeight.Bold, color = accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CalendarPane(days: Int, isDone: Boolean, modifier: Modifier, onCount: (Int?) -> Unit = {}) {
    val context = LocalContext.current
    var events by remember(days, isDone) { mutableStateOf<List<CalEvent>?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    // v1.54: watch the calendar provider so the list updates itself while it is on screen —
    // every change (Google sync, an edit on the phone, a deletion) lands in this provider. Also
    // re-read on ON_RESUME, covering changes that arrived while the pane was disposed.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) {
                if (!refreshing) { refreshing = true; refreshTick++ }
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                android.provider.CalendarContract.CONTENT_URI, true, observer
            )
        }.onFailure { Logger.e(context, "CALREAD", it, "calendar observer registration failed") }.isSuccess
        val lc = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !refreshing) {
                refreshing = true; refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(lc)
        onDispose {
            if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
            lifecycleOwner.lifecycle.removeObserver(lc)
        }
    }

    // v1.50: Active reads forward, Done reads the same span backwards.
    LaunchedEffect(days, isDone, refreshTick) {
        events = withContext(Dispatchers.IO) {
            // v1.56 Plan B: cloud first (bypasses device sync); provider read is the fallback.
            CalCloud.fetch(context, days, past = isDone)
                ?: (if (isDone) CalSync.readPast(context, days) else CalSync.readNext(context, days))
        }
        refreshing = false
    }
    Column(modifier) {
        val ev = events
        // v1.79 (Q11): report the count up so the header describes what is on screen.
        LaunchedEffect(ev?.size) { onCount(ev?.size) }
        // v1.53: selection is account-based since v1.49; the old targetCalendarId check told a
        // fresh-install user "pick a calendar" forever even after they had picked an account.
        val ready = CalSync.hasReadPerm(context) && SettingsStore.s.collectAsState().value.calendarAccount != null
        when {
            !ready -> EmptyState("Pick a calendar under Settings → Calendar (and grant calendar access) to see your events here — read-only.")
            ev == null -> Box(
                Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            ev.isEmpty() -> EmptyState(if (isDone) "No calendar events in the last $days days." else "No calendar events in the next $days days.")
            else -> {
                // v1.50: past days read newest-first; future stays soonest-first.
                val byDay = calEventsByDay(ev).let { if (isDone) it.reversed() else it }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (refreshing) "Refreshing…" else "Pull down or tap refresh to re-read your calendar",
                        style = MaterialTheme.typography.labelSmall, color = InkHint,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        refreshing = true
                        CalSync.requestAccountSync(context)   // ask Android to pull new data from Google
                        refreshTick++
                    }) { Text("Refresh") }
                }
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // v1.50: pull-down at the top re-reads (and asks Android to sync first).
                            var drag = 0f
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (drag > 180f && !refreshing) {
                                        refreshing = true
                                        CalSync.requestAccountSync(context)
                                        refreshTick++
                                    }
                                    drag = 0f
                                }
                            ) { _, dy -> drag += dy }
                        },
                    contentPadding = PaddingValues(top = 10.dp, bottom = 96.dp)
                ) {
                    byDay.forEach { (day, dayEvents) ->
                        item(key = "cal-day-$day") {
                            MonthHeader(formatDate(day), dayEvents.size, false, TasksPal, onToggle = {}, onClear = null)
                        }
                        items(dayEvents, key = { "cal-${it.id}-${it.start}" }) { e -> CalEventRow(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalEventRow(e: CalEvent) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Event, null, tint = TasksPal.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (e.allDay) "All day" else formatDateTime(e.start),
                style = MaterialTheme.typography.bodySmall, color = InkSubtle
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShopPicker(value: String, onPick: (String) -> Unit) {
    val shops by ShopStore.shops.collectAsState()
    val names = shops.filter { it.deletedAt == null }.map { it.name }.sortedBy { it.lowercase() }
    var expanded by remember { mutableStateOf(false) }
    var showType by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value.ifBlank { "Choose shop" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Shop") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("None", color = GreyIcon) },
                onClick = { onPick(""); expanded = false }
            )
            names.forEach { n ->
                DropdownMenuItem(text = { Text(n) }, onClick = { onPick(n); expanded = false })
            }
            DropdownMenuItem(
                text = { Text("+ Other (one-off)…", color = SettingsAccent, fontWeight = FontWeight.SemiBold) },
                onClick = { expanded = false; typed = value; showType = true }
            )
        }
    }
    if (showType) {
        AlertDialog(
            onDismissRequest = { showType = false },
            title = { Text("Shop name (one-off)", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Used on this item only. To reuse a shop (and add a geofence), register it in the Shops menu.",
                        style = MaterialTheme.typography.bodySmall, color = InkSubtle,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onPick(typed.trim()); showType = false }) {
                    Text("Use", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showType = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDropdown(
    label: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
    onAddNew: (String) -> Unit,
    blankLabel: String = "Blank",
    addLabel: String = "+ Add new…"
) {
    var expanded by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value.ifBlank { blankLabel },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(blankLabel, color = GreyIcon) },
                onClick = { onPick(""); expanded = false }
            )
            options.sorted().forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onPick(opt); expanded = false })
            }
            DropdownMenuItem(
                text = { Text(addLabel, color = SettingsAccent, fontWeight = FontWeight.SemiBold) },
                onClick = { expanded = false; newName = ""; showAdd = true }
            )
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New name", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val g = newName.trim()
                    if (g.isNotBlank()) onAddNew(g)
                    showAdd = false
                }) { Text("Add", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

// ================================================================ locations manager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsSheet(pal: TabPalette, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val allPlaces by PlaceStore.places.collectAsState()
    val places = allPlaces.filter { it.deletedAt == null }
    val settings by SettingsStore.s.collectAsState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var adding by remember { mutableStateOf(places.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(TriggerType.LEAVE) }
    var radius by remember { mutableStateOf(settings.defaultRadius) }
    var address by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showMap by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // v2.10 (N9): location, then "all the time" — each with its own reason, each optional.
    val resumeTick = rememberResumeTick()
    val ui by UiStore.s.collectAsState()
    val geoMode = remember(resumeTick, ui.permAsked) {
        geofenceMode(Perms.state(context, PermKeys.LOCATION), Perms.state(context, PermKeys.BG_LOCATION), Build.VERSION.SDK_INT)
    }
    val bgReq = rememberPermRequest(PermKeys.BG_LOCATION) { Geofencer.registerAll(context) }
    val locReq = rememberPermRequest(PermKeys.LOCATION) { ok ->
        if (ok && Build.VERSION.SDK_INT >= 29 && !Geofencer.hasBackgroundLocation(context)) bgReq()
        Geofencer.registerAll(context)
    }

    fun requestLocationPerms() = locReq()

    val pm = context.getSystemService(PowerManager::class.java)
    val batteryOk = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SurfaceCard, windowInsets = sheetWindowInsets()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Shopping places",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = pal.g1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { adding = !adding }) {
                    Text(if (adding) "Close form" else "+ Add place", color = pal.accent, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "Your shop list rings when you leave or arrive at these places.",
                style = MaterialTheme.typography.bodySmall,
                color = InkSubtle
            )

            geofenceNote(geoMode)?.let { note ->
                PermNote(note, if (geoMode == GeoMode.OFF) "Allow" else "Allow all the time", pal.accent,
                    onAction = { if (geoMode == GeoMode.OFF) requestLocationPerms() else bgReq() })
            }

            if (!batteryOk) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = UrgentSoft),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BatteryAlert, null, tint = OverdueRed)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Keep alarms alive",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "Samsung kills background apps aggressively. Exempt Remindly from battery optimisation.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }) { Text("Fix", color = OverdueRed, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            places.sortedBy { it.name.lowercase() }.forEach { place ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceSubtle),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        var bindFor by remember { mutableStateOf(false) }
                        Column(Modifier.weight(1f)) {
                            Text(place.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${place.trigger.label} · ${place.radius.toInt()} m" +
                                    (if (place.groupFilter.isNotEmpty()) " · " + place.groupFilter.joinToString(", ") else " · all groups"),
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSubtle,
                                modifier = Modifier.clickable { bindFor = true }
                            )
                        }
                        // v1.15 item 17: bind this place to specific groups.
                        if (bindFor) {
                            val allGroups = settings.shopGroups
                            AlertDialog(
                                onDismissRequest = { bindFor = false },
                                title = { Text("Groups at ${place.name}", fontWeight = FontWeight.Bold) },
                                text = {
                                    Column {
                                        Text(
                                            "Its alert mentions only these groups' items. None selected = everything.",
                                            style = MaterialTheme.typography.bodySmall, color = InkSubtle
                                        )
                                        allGroups.forEach { g ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(
                                                    checked = g in place.groupFilter,
                                                    onCheckedChange = { on ->
                                                        val nf = if (on) place.groupFilter + g else place.groupFilter - g
                                                        PlaceStore.upsert(place.copy(groupFilter = nf.distinct()))
                                                    }
                                                )
                                                Text(g)
                                            }
                                        }
                                    }
                                },
                                confirmButton = { TextButton(onClick = { bindFor = false }) { Text("Done") } }
                            )
                        }
                        Switch(
                            checked = place.enabled,
                            onCheckedChange = {
                                PlaceStore.upsert(place.copy(enabled = it))
                                Geofencer.registerAll(context)
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = pal.accent)
                        )
                        IconButton(onClick = {
                            PlaceStore.delete(place.id)
                            Geofencer.registerAll(context)
                        }) { Icon(Icons.Filled.Delete, "Delete", tint = OverdueRed) }
                    }
                }
            }

            if (adding) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Place name (Home, Big Bazaar…)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                ChoiceChips(
                    options = TriggerType.entries.map { it.label },
                    selected = TriggerType.entries.indexOf(trigger),
                    accent = pal.accent
                ) { trigger = TriggerType.entries[it] }
                // v2.02 (N33 C3/C4): the 13-stop dropdown replaces the slider (standing rule, all apps).
                RadiusDropdown(value = radius, accent = pal.accent) { radius = it }
                OutlinedButton(
                    onClick = {
                        if (!Geofencer.hasFineLocation(context)) {
                            requestLocationPerms(); return@OutlinedButton
                        }
                        busy = true
                        status = null
                        try {
                            val fused = LocationServices.getFusedLocationProviderClient(context)
                            fused.getCurrentLocation(
                                GmsPriority.PRIORITY_HIGH_ACCURACY,
                                CancellationTokenSource().token
                            ).addOnSuccessListener { loc ->
                                busy = false
                                if (loc != null) {
                                    picked = loc.latitude to loc.longitude
                                    status = "Got your current position ✓"
                                } else status = "Couldn't get a fix — step outdoors and retry"
                            }.addOnFailureListener {
                                busy = false
                                status = "Location failed — is GPS on?"
                            }
                        } catch (_: SecurityException) {
                            busy = false
                            status = "Location permission missing"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Filled.MyLocation, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Use my current location")
                }
                OutlinedButton(
                    onClick = { showMap = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AddLocationAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pick on the map")
                }
                if (showMap) {
                    MapPickerDialog(
                        initialLat = picked?.first, initialLng = picked?.second,   // null → opens at the GPS fix (C2)
                        accent = pal.accent,
                        radius = radius, onRadius = { radius = it },
                        onDismiss = { showMap = false }
                    ) { lat, lng ->
                        picked = lat to lng
                        status = "Pinned on the map ✓"
                        showMap = false
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it },
                        label = { Text("…or type an address") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        if (address.isBlank()) return@OutlinedButton
                        busy = true; status = null
                        // v2.02 (N33): Geo.lookup = Google (counted against the limit) → OSM fallback.
                        scope.launch {
                            val hit = runCatching { Geo.lookup(context, address) }.getOrNull()
                            busy = false
                            if (hit != null) {
                                picked = hit.lat to hit.lng
                                status = "Found via ${if (hit.provider == PROVIDER_GOOGLE) "Google" else "OSM"}: ${hit.label} ✓"
                            } else status = "Address not found — try adding the city"
                        }
                    }) {
                        Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp))
                    }
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("✓")) ShopInk else OverdueRed
                    )
                }
                Button(
                    onClick = {
                        val p = picked ?: return@Button
                        PlaceStore.upsert(
                            GeoPlace(
                                id = Ids.next(), name = name.trim(),
                                lat = p.first, lng = p.second,
                                radius = radius, trigger = trigger
                            )
                        )
                        Geofencer.registerAll(context)
                        name = ""; address = ""; picked = null; status = null; adding = false
                    },
                    enabled = name.isNotBlank() && picked != null,
                    colors = ButtonDefaults.buttonColors(containerColor = pal.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) { Text("Save place", fontWeight = FontWeight.Bold, color = Color.White) }
                Spacer(Modifier.height(8.dp))
                SheetBottomSpace()   // v2.01 (N32): Option D clearance
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}


// ================================================================ v1.15 item 7/11/12: shopping-trip mode

@Composable
fun TripSheet(pal: TabPalette, personal: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    val all by ItemStore.items.collectAsState()
    val live = all.filter { it.tab == Tab.SHOP && !it.done && it.deletedAt == null && it.personal == personal }
    var scope by remember { mutableStateOf<String?>(null) } // null = everything, else group
    val groups = live.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }.distinct()
        .sortedWith(compareBy({ settings.shopGroupOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.lowercase() }))
    val rows = (if (scope == null) live else live.filter { (it.group ?: "") == scope })
        .sortedWith(compareBy(
            { settings.shopGroupOrder.indexOf(it.group ?: "").let { i -> if (i < 0) Int.MAX_VALUE else i } },
            { (it.group ?: "\uffff").lowercase() }, { it.title.lowercase() }
        ))
    var ticked by remember { mutableStateOf(setOf<Long>()) }
    var oos by remember { mutableStateOf(setOf<Long>()) }
    var showFinish by remember { mutableStateOf(false) }
    val tickedSum = rows.filter { it.id in ticked }
        .sumOf { it.price?.replace(",", "")?.replace("₹", "")?.trim()?.toDoubleOrNull() ?: 0.0 }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = SurfaceSubtle) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(pal.accent)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Shopping trip", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "${ticked.size} of ${rows.size} · ₹${fmtExact(tickedSum)}",
                                color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.titleMedium
                            )
                        }
                        // v1.19 item 2: staples + share live at the shopping moment.
                        IconButton(onClick = {
                            val staples = all.filter { it.tab == Tab.SHOP && it.staple && it.done && it.deletedAt == null }
                            if (staples.isEmpty()) Ack.show("No done staples to re-add") {}
                            else {
                                staples.forEach { st -> Engine.addOrUpdate(context, st.copy(done = false, doneAt = null)) }
                                Ack.show("Re-added ${staples.size} staples") {}
                            }
                        }) { Icon(Icons.Filled.Star, "Add staples", tint = Color.White) }
                        IconButton(onClick = {
                            val text = buildString {
                                append("Shopping list — ").append(formatDay(System.currentTimeMillis())).append("\n")
                                live.groupBy { it.group?.takeIf { g -> g.isNotBlank() } ?: "No Group" }
                                    .toSortedMap()
                                    .forEach { (g, items) ->
                                        append("\n").append(g).append(":\n")
                                        items.forEach { i ->
                                            append("- ").append(i.title)
                                            i.quantity?.takeIf { q -> q.isNotBlank() }?.let { q ->
                                                append(" × ").append(q); i.unit?.let { u -> append(" ").append(u) }
                                            }
                                            append("\n")
                                        }
                                    }
                            }
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, text)
                            runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share shopping list")) }
                                .onFailure { Ack.show("No app can share that") {} }
                        }) { Icon(Icons.Filled.Share, "Share list", tint = Color.White) }
                        TextButton(onClick = onDismiss) { Text("Exit", color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf<String?>(null) + groups).forEach { g ->
                            val sel = scope == g
                            Text(
                                g ?: "Everything",
                                color = if (sel) pal.accent else Color.White,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) Color.White else Color.White.copy(alpha = 0.18f))
                                    .clickable { scope = g }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
                    var lastG = "\u0000"
                    rows.forEach { item ->
                        val g = item.group?.takeIf { it.isNotBlank() } ?: "No Group"
                        if (scope == null && g != lastG) {
                            lastG = g
                            item(key = "tg-$g") {
                                Text(g, fontWeight = FontWeight.Bold, color = PillTextIdle,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                            }
                        }
                        item(key = item.id) {
                            val isTicked = item.id in ticked
                            val isOos = item.id in oos || isOosToday(item)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceCard)
                                    .clickable {
                                        // v1.15 item 7: instant tick — no delay, no confirm.
                                        ticked = if (isTicked) ticked - item.id else ticked + item.id
                                        if (!isTicked) oos = oos - item.id
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    if (isTicked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    null, tint = if (isTicked) pal.accent else InkFaint,
                                    modifier = Modifier.size(30.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title, style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isOos) DangerSoft else InkPrimary
                                    )
                                    val sub = listOfNotNull(
                                        item.quantity?.takeIf { it.isNotBlank() }?.let { q -> q + (item.unit?.let { " $it" } ?: "") },
                                        item.price?.takeIf { it.isNotBlank() }?.let { "₹$it" },
                                        if (isOos) "OOS today" else null
                                    ).joinToString(" · ")
                                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                                }
                                // v1.15 item 11: third state — not available today.
                                TextButton(onClick = {
                                    oos = if (item.id in oos) oos - item.id else oos + item.id
                                    if (item.id in oos) ticked = ticked - item.id
                                }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("OOS", color = if (isOos) DangerSoft else InkHint,
                                        fontWeight = if (isOos) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { showFinish = true },
                    enabled = ticked.isNotEmpty() || oos.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) { Text("Finish trip", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (showFinish) {
        var billText by remember { mutableStateOf("") }
        val bill = billText.replace(",", "").replace("₹", "").trim().toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { showFinish = false },
            title = { Text("Finish trip", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Completing ${ticked.size} items · ₹${fmtExact(tickedSum)}")
                    if (oos.isNotEmpty()) Text("${oos.size} marked not available — they stay on the list for tomorrow.",
                        style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    // v1.15 item 12: receipt reconciliation.
                    OutlinedTextField(
                        value = billText, onValueChange = { billText = it },
                        label = { Text("Bill total (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    if (bill != null) {
                        val delta = bill - tickedSum
                        Text(
                            if (delta > 0) "₹${fmtExact(delta)} untracked — it joins this month's header."
                            else if (delta < 0) "Bill is ₹${fmtExact(-delta)} under your item prices."
                            else "Bill matches your items exactly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (delta > 0) AmberInk else ShopInk
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    rows.filter { it.id in ticked }.forEach { Engine.completeNow(context, it.id) }
                    rows.filter { it.id in oos }.forEach { Engine.addOrUpdate(context, it.copy(oosAt = now)) }
                    val delta = bill?.let { it - tickedSum } ?: 0.0
                    if (delta > 0) {
                        val mk = monthKey(now)
                        SettingsStore.update { s ->
                            s.copy(shopUntracked = s.shopUntracked + (mk to (s.shopUntracked[mk] ?: 0.0) + delta))
                        }
                    }
                    Ack.show("Trip done · ${ticked.size} completed") {}
                    showFinish = false
                    onDismiss()
                }) { Text("Complete all", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showFinish = false }) { Text("Back") } }
        )
    }
}


/**
 * v2.9 (N45): the group ("list") options menu — Rename (across every item in the group and the
 * settings list), Change icon (emoji), Share, Duplicate (copies the items into "<name> copy"),
 * Complete all (N43), Delete list (items → Bin, name removed). Standard sheet chrome.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupMenuSheet(tab: Tab, name: String, items: List<Item>, pal: TabPalette, onShare: () -> Unit, onCompleteAll: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    var mode by remember { mutableStateOf("menu") }
    var text by remember { mutableStateOf(name) }
    val icons = listOf("🛒", "🏠", "💊", "🎁", "🧹", "🍎", "👕", "📚", "🔧", "🐾", "🎉", "✈️", "💼", "🍼", "🧴", "⭐")
    // v2.11 (N48): in Shop the group IS a list — rename/icon/delete/duplicate go through ShopListStore
    // (otherwise the next reconcile would stamp the old list name straight back onto the items).
    val shopList = if (tab == Tab.SHOP) listNamed(settings.shopLists, name) else null
    fun setGroups(edit: (List<String>) -> List<String>) = SettingsStore.update {
        when (tab) { Tab.SHOP -> it.copy(shopGroups = edit(it.shopGroups)); Tab.TASKS -> it.copy(tasksGroups = edit(it.tasksGroups)); Tab.LEARN -> it }
    }
    EditorSheet(title = (groupIconFor(name, settings)?.let { "$it " } ?: "") + name, accent = pal.accent, onDismiss = onDismiss, actions = {
        if (mode == "rename") EditorActionRow(accent = pal.accent, onCancel = { mode = "menu" }, saveEnabled = text.isNotBlank() && text.trim() != name, saveLabel = "Rename", onSave = {
            val nn = text.trim()
            if (shopList != null) { ShopListStore.edit(context, shopList, nn, shopList.icon, shopList.usualShopId, shopList.shoppingDay, shopList.personal); onDismiss(); return@EditorActionRow }
            runCatching {
                items.forEach { i -> ItemStore.upsert(i.copy(group = nn)) }
                setGroups { g -> g.map { if (it == name) nn else it }.distinct() }
                SettingsStore.update { it.copy(groupIcons = it.groupIcons - name + (it.groupIcons[name]?.let { ic -> nn to ic }?.let { mapOf(it) } ?: emptyMap()), shopDefaultGroup = if (it.shopDefaultGroup == name) nn else it.shopDefaultGroup) }
                Logger.e(context, "GROUP", null, "renamed '$name' → '$nn' (${items.size} items)")
            }.onFailure { Logger.e(context, "GROUP", it, "rename failed") }
            onDismiss()
        })
        else if (mode == "delete") EditorActionRow(accent = OverdueRed, onCancel = { mode = "menu" }, saveLabel = "Delete list (${items.size})", onSave = {
            if (shopList != null) {
                val gone = ShopListStore.delete(context, shopList, ListDeleteMode.DELETE_ITEMS, null)
                Ack.show("Deleted “$name”") { ShopListStore.undoDelete(context, shopList, gone, ListDeleteMode.DELETE_ITEMS) }
                onDismiss(); return@EditorActionRow
            }
            runCatching {
                items.forEach { Engine.delete(context, it) }
                setGroups { g -> g.filter { it != name } }
                SettingsStore.update { it.copy(groupIcons = it.groupIcons - name, shopDefaultGroup = if (it.shopDefaultGroup == name) "" else it.shopDefaultGroup) }
                Logger.e(context, "GROUP", null, "deleted list '$name' (${items.size} items → Bin)")
                Feedback.toast(context, "“$name” deleted · items are in the Bin")
            }.onFailure { Logger.e(context, "GROUP", it, "delete list failed") }
            onDismiss()
        })
        else EditorActionRow(accent = pal.accent, onCancel = onDismiss, saveEnabled = false, saveLabel = "", onSave = {})
    }) {
        when (mode) {
            "rename" -> OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("List name") }, modifier = Modifier.fillMaxWidth())
            "icon" -> {
                Text("Pick an icon", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    icons.forEach { ic ->
                        FilterChip(selected = groupIconFor(name, settings) == ic, onClick = {
                            if (shopList != null) ShopListStore.upsert(shopList.copy(icon = ic))
                            else SettingsStore.update { it.copy(groupIcons = it.groupIcons + (name to ic)) }
                            mode = "menu"
                        }, label = { Text(ic, style = MaterialTheme.typography.titleMedium) })
                    }
                    FilterChip(selected = groupIconFor(name, settings) == null, onClick = {
                        if (shopList != null) ShopListStore.upsert(shopList.copy(icon = null))
                        else SettingsStore.update { it.copy(groupIcons = it.groupIcons - name) }
                        mode = "menu"
                    }, label = { Text("None") })
                }
            }
            "delete" -> Text("${items.size} item${if (items.size == 1) "" else "s"} go to the Bin (restorable). The list name is removed.", style = MaterialTheme.typography.bodyMedium)
            else -> Column {
                Text("${items.size} items · ${items.count { it.done }} done", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                TextButton(onClick = { text = name; mode = "rename" }) { Text("✏️  Rename") }
                TextButton(onClick = { mode = "icon" }) { Text("🎨  Change icon") }
                TextButton(onClick = { onDismiss(); onShare() }) { Text("📤  Share list") }
                TextButton(onClick = {
                    if (shopList != null) {
                        ShopListStore.duplicate(context, shopList)?.let { Feedback.toast(context, "Duplicated as “${it.name}”") }
                        onDismiss(); return@TextButton
                    }
                    val nn = "$name copy"
                    runCatching {
                        items.filter { it.deletedAt == null }.forEach { i -> ItemStore.upsert(i.copy(id = Ids.next(), group = nn, done = false, doneAt = null, snoozedUntil = null)) }
                        setGroups { g -> if (nn in g) g else g + nn }
                        groupIconFor(name, settings)?.let { ic -> SettingsStore.update { it.copy(groupIcons = it.groupIcons + (nn to ic)) } }
                        Logger.e(context, "GROUP", null, "duplicated '$name' → '$nn' (${items.size} items)")
                        Feedback.toast(context, "Duplicated as “$nn”")
                    }.onFailure { Logger.e(context, "GROUP", it, "duplicate failed") }
                    onDismiss()
                }) { Text("📑  Duplicate list") }
                TextButton(onClick = { onDismiss(); onCompleteAll() }) { Text("☑  Complete all") }
                TextButton(onClick = { mode = "delete" }) { Text("🗑  Delete list", color = OverdueRed) }
            }
        }
    }
}
