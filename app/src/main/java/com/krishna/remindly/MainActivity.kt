package com.krishna.remindly

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** v1.8: share-into-Remindly hand-off between the intent and the tab UIs. */
object ShareInbox {
    data class Payload(val tab: Tab, val title: String, val url: String? = null, val platform: String? = null)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Payload?>(null)
    fun guessPlatform(url: String): String = when {
        "youtube" in url || "youtu.be" in url -> "YouTube"
        "udemy" in url -> "Udemy"
        "coursera" in url -> "Coursera"
        else -> "Website"
    }
}

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        // v1.81 (Q15): a tap arriving while the app is already open lands here, not in onCreate.
        NotifyOpen.request(intent.getLongExtra("openItemId", 0L))
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        val url = Regex("https?://\\S+").find(text)?.value
        if (url != null) {
            ShareInbox.pending.value = ShareInbox.Payload(
                Tab.LEARN,
                title = text.removePrefix(url).trim().ifBlank { url },
                url = url,
                platform = ShareInbox.guessPlatform(url.lowercase())
            )
        } else {
            ShareInbox.pending.value = ShareInbox.Payload(Tab.TASKS, title = text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stores.init(this)
        SystemBars.install(this)   // v2.01 (N32): real nav-bar/IME geometry for every bottom sheet
        val openTab = intent?.getIntExtra("openTab", 0) ?: 0
        // v2.05 (N37/N38): an arrival alert opens Shop mode → Buy → the Buy Now view for that shop.
        runCatching {
            if (intent?.getStringExtra("openMode") == "SHOP") {
                val shopId = intent.getLongExtra("buyNowShopId", 0L)
                UiStore.update {
                    it.copy(
                        appMode = "SHOP",
                        lastShopTab = intent.getIntExtra("openShopTab", 0).coerceIn(0, 3),
                        buyNowShopId = if (shopId > 0L) shopId else it.buyNowShopId,
                        buyNowAt = if (shopId > 0L) System.currentTimeMillis() else it.buyNowAt
                    )
                }
            }
        }.onFailure { Logger.e(this, "BUYNOW", it, "arrival deep link failed") }
        // v1.81 (Q15): a Notify tap opens the item's own quiet card, not merely its tab.
        NotifyOpen.request(intent?.getLongExtra("openItemId", 0L) ?: 0L)
        handleShare(intent)
        if (intent?.getBooleanExtra("quickadd", false) == true) {
            UiStore.update { it.copy(lastTab = 0) }
        }
        setContent {
            RemindlyTheme {
                NotifyCard()   // v1.81 (Q15): shown when a Notify tap asked for an item
                MainScaffold(openTab)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // v1.36: Personal shop items lock again whenever the app leaves the screen.
        // (Drive auto-upload retired — cross-device sync is now live via Firestore/Cloud sync.)
        Engine.lockPersonal()
    }
}

private data class NavEntry(val label: String, val icon: @Composable () -> Unit, val pal: TabPalette)

/**
 * v1.3 navigation: five bottom tabs. No window sliding anywhere —
 * swiping blank space inside a tab flips the Active/Done filter in place
 * (crossfade), swiping the bottom bar walks across tabs (direction is a
 * Settings choice), and card swipes move the items themselves.
 */
@Composable
fun MainScaffold(initialTab: Int) {
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(UiStore.s.value.lastTab.coerceIn(0, 4)) }
    var settingsSnapshot by remember { mutableStateOf<AppSettings?>(null) }
    // v1.8: per-tab settings opened from the gear
    val appContext = LocalContext.current.applicationContext
    var crashText by remember {
        mutableStateOf(
            runCatching {
                val f = java.io.File(appContext.filesDir, "last_crash.txt")
                if (f.exists()) f.readText().also { f.delete() } else null
            }.getOrNull()
        )
    }
    // v2.6.1 (N39): declared here (was further down) so the shared navigation helpers below can
    // land on a Shop-mode tab — Kotlin locals must exist before the local functions that use them.
    var selectedShopTab by rememberSaveable { mutableIntStateOf(UiStore.s.value.lastShopTab.coerceIn(0, 3)) }
    var gearTab by remember { mutableStateOf<String?>(null) }
    var gearSnapshot by remember { mutableStateOf<AppSettings?>(null) }
    var gearLeaveDiff by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }
    // v2.6.1 (N39): the gear overlay can now hand over to ANY destination — task tab, shop tab or a
    // mode flip — not just a task tab (that limit was half of why Shop-mode taps looked frozen).
    var gearNavTarget by remember { mutableStateOf<NavTarget?>(null) }
    fun openGear(k: String) { gearSnapshot = SettingsStore.s.value; gearTab = k }
    /** v2.6.1 (N39): apply a handover target after the gear overlay closes. Total by construction. */
    fun applyNavTarget(t: NavTarget?) {
        if (t == null) return
        runCatching {
            t.mode?.let { m -> UiStore.update { it.copy(appMode = modeNormalized(m)) } }
            t.taskTab?.let { selectedTab = it.coerceIn(0, 4) }
            t.shopTab?.let { selectedShopTab = it.coerceIn(0, 3) }
        }.onFailure { Logger.e(context, "NAV", it, "navigation handover failed after closing settings") }
    }

    fun requestCloseGear(navTo: NavTarget? = null) {
        val diff = settingsDiff(gearSnapshot, SettingsStore.s.value)
        if (diff.isEmpty()) {
            gearTab = null; gearSnapshot = null
            applyNavTarget(navTo)
        } else {
            gearNavTarget = navTo
            gearLeaveDiff = diff
        }
    }
    var pendingTab by remember { mutableStateOf<Int?>(null) }
    var pendingDiff by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    // v2.00 (N31): Shop Settings leave-guard — same snapshot/diff/popup as Task-mode Settings.
    var shopSettingsSnapshot by remember { mutableStateOf<AppSettings?>(null) }
    var pendingShopTab by remember { mutableStateOf<Int?>(null) }
    var pendingMode by remember { mutableStateOf<String?>(null) }

    fun requestTab(target: Int) {
        // v1.9 bug fix: a tab tap while per-tab settings are open runs the leave-guard,
        // then closes the settings AND lands on the tapped tab.
        // v2.6.1 (N39): a gear page is an OVERLAY, not a destination — close it first, always.
        if (gearTab != null) {
            requestCloseGear(navTo = NavTarget(taskTab = target))
            return
        }
        if (target == selectedTab) return
        if (selectedTab == 4) {
            val diff = settingsDiff(settingsSnapshot, SettingsStore.s.value)
            if (diff.isNotEmpty()) {
                pendingDiff = diff
                pendingTab = target
                return
            }
            settingsSnapshot = null
        }
        selectedTab = target
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AlarmScheduler.rescheduleAll(context)
        runCatching { Degrades.refresh(context) }   // v1.71 (N2)
        Geofencer.registerAll(context)
        // v2.6.2 (N41): NO catch-up scan on app start. The call log is read only when a call
        // actually ends (CallStateReceiver) or when the user taps the manual scan — both bounded
        // to the 5-minute live window. An app-start sweep is what froze the Calls tab on first
        // install while it ingested history one ring per number.
        CallEngine.ensureWatermark(context)
    }

    // v1.90 two-mode system; v2.00 (N31): dual-mode ONLY — TASK mode hides the Shop tab, SHOP mode
    // gets its own four-surface nav. There is no Classic layout any more.
    val ui by UiStore.s.collectAsState()
    val shopMode = ui.appMode == "SHOP"
    LaunchedEffect(Unit) {
        // v2.04 (N36): always reopen the last used mode (the ☰ drawer is the only switch).
        runCatching { UiStore.update { it.copy(appMode = resolveStartMode(it.appMode)) } }
            .onFailure { Logger.e(context, "MODE", it, "mode restore failed — keeping last mode") }
    }
    LaunchedEffect(selectedShopTab) { UiStore.update { it.copy(lastShopTab = selectedShopTab) } }
    // v2.7 (N42): Settings → "Open scheduled alerts" hands over to the gear host.
    val schedOpen by SchedNav.open.collectAsState()
    LaunchedEffect(schedOpen) { if (schedOpen) { SchedNav.open.value = false; openGear("SCHED") } }
    // v2.02 (N33): one-time notices (radius scale snap, Google limit lock) surface once as a toast.
    LaunchedEffect(ui.pendingNotice) {
        ui.pendingNotice?.let { n ->
            runCatching { Feedback.toast(context, n) }
            UiStore.update { it.copy(pendingNotice = null) }
        }
    }
    // Leaving the Buy surface locks Personal just like leaving the Shop tab does.
    LaunchedEffect(shopMode, selectedShopTab) { if (shopMode && selectedShopTab != 0) Engine.lockPersonal() }
    LaunchedEffect(shopMode, selectedShopTab) {
        if (shopMode && selectedShopTab == 3 && shopSettingsSnapshot == null) shopSettingsSnapshot = SettingsStore.s.value
    }
    fun requestShopTab(target: Int) {
        val t = target.coerceIn(0, 3)
        // v2.6.1 (N39, Krishna's report): with Buy ⚙ / Shops ⚙ / Products ⚙ open, this used to set the
        // tab UNDERNEATH the full-screen overlay, so the settings window appeared frozen. Same
        // contract as requestTab now: close the overlay (leave-guard included) and land on the tab.
        if (gearTab != null) {
            requestCloseGear(navTo = NavTarget(shopTab = t))
            return
        }
        if (t == selectedShopTab) return
        if (selectedShopTab == 3) {
            val diff = settingsDiff(shopSettingsSnapshot, SettingsStore.s.value)
            if (diff.isNotEmpty()) { pendingDiff = diff; pendingShopTab = t; return }
            shopSettingsSnapshot = null
        }
        selectedShopTab = t
    }
    /**
     * v2.00: every mode flip goes through here (the header chip via LocalModeFlipGuard, the
     * "General settings" row, share routing). Returns false when a leave-guard popup took over;
     * the flip (and optional landing tab) is then applied by the popup's Confirm/Discard.
     */
    fun requestModeFlip(target: String, thenTab: Int? = null, thenShopTab: Int? = null): Boolean {
        val goal = modeNormalized(target)
        // v2.6.1 (N39, same class): flipping mode with a gear page open used to leave the overlay
        // covering the OTHER mode. Hand the whole target to the overlay's own leave-guard.
        if (gearTab != null) {
            requestCloseGear(navTo = NavTarget(taskTab = thenTab, shopTab = thenShopTab, mode = goal))
            return false
        }
        if (goal != ui.appMode) {
            val leavingShopSettings = shopMode && selectedShopTab == 3
            val leavingTaskSettings = !shopMode && selectedTab == 4 && gearTab == null
            val diff = when {
                leavingShopSettings -> settingsDiff(shopSettingsSnapshot, SettingsStore.s.value)
                leavingTaskSettings -> settingsDiff(settingsSnapshot, SettingsStore.s.value)
                else -> emptyList()
            }
            if (diff.isNotEmpty()) {
                pendingDiff = diff; pendingMode = goal; pendingTab = thenTab; pendingShopTab = thenShopTab
                return false
            }
            if (leavingShopSettings) shopSettingsSnapshot = null
            if (leavingTaskSettings) settingsSnapshot = null
            runCatching { UiStore.update { it.copy(appMode = goal) } }
                .onFailure { Logger.e(context, "MODE", it, "mode flip failed") }
        }
        thenTab?.let { selectedTab = it }
        thenShopTab?.let { selectedShopTab = it.coerceIn(0, 3) }
        return true
    }

    // Auto-lock Personal the instant navigation leaves the Shop tab.
    val sharePayload by ShareInbox.pending.collectAsState()
    LaunchedEffect(sharePayload) {
        sharePayload?.let { p ->
            if (p.tab == Tab.SHOP) {
                // A shared shopping list opens in Shop mode's Buy surface.
                runCatching { UiStore.update { it.copy(appMode = "SHOP") } }
                selectedShopTab = 0
            } else {
                runCatching { UiStore.update { it.copy(appMode = "TASK") } }
                selectedTab = if (p.tab == Tab.LEARN) 2 else 0
            }
        }
    }

    LaunchedEffect(selectedTab, shopMode) {
        if (selectedTab != 1) Engine.lockPersonal()
        if (!shopMode && selectedTab == 4 && settingsSnapshot == null) settingsSnapshot = SettingsStore.s.value
        UiStore.update { it.copy(lastTab = selectedTab) }
    }

    // v1.5: if the app closes while on Settings with unsaved-confirmed changes,
    // they're already persisted live — record them so next open shows the notice.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && selectedTab == 4) {
                val diff = settingsDiff(settingsSnapshot, SettingsStore.s.value)
                if (diff.isNotEmpty()) {
                    UiStore.update { u ->
                        u.copy(savedNotice = diff.map { d -> "${d.first}: ${d.second} → ${d.third}" })
                    }
                    settingsSnapshot = SettingsStore.s.value
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // v1.41 Feature 1: per-tab visibility (Settings is always shown). Pure helpers in Model.kt.
    // v1.90/v2.00: Task mode hides index 1 (Shop lives in Shop mode) — pure helpers, unit-tested.
    fun tabVisible(i: Int) = taskModeTabVisible(settings, i)
    fun firstVisibleTab() = firstVisibleTaskTab(settings)
    fun nextVisibleTab(from: Int, forward: Boolean) = nextVisibleTaskTab(settings, from, forward)
    LaunchedEffect(settings.showTasks, settings.showLearn, settings.showCalls) {
        if (!tabVisible(selectedTab)) selectedTab = firstVisibleTab()
    }

    val entries = listOf(
        NavEntry("Tasks", { Icon(Icons.Filled.Checklist, "Tasks") }, TasksPal),
        NavEntry("Shop", { Icon(Icons.Filled.ShoppingCart, "Shop") }, ShopPal),
        NavEntry("Learn", { Icon(Icons.Filled.School, "Learn") }, LearnPal),
        NavEntry("Calls", { Icon(Icons.Filled.Call, "Calls") }, CallPal),
        NavEntry("Settings", { Icon(Icons.Filled.Settings, "Settings") }, SettingsPal)
    )
    // v1.90 Shop-mode nav: Buy · Shops · Products · Settings — all on the Shop palette.
    val shopEntries = listOf(
        NavEntry("Buy", { Icon(Icons.Filled.ShoppingCart, "Buy") }, ShopPal),
        NavEntry("Shops", { Icon(Icons.Filled.Store, "Shops") }, ShopPal),
        NavEntry("Products", { Icon(Icons.Filled.Inventory2, "Products") }, ShopPal),
        NavEntry("Settings", { Icon(Icons.Filled.Settings, "Settings") }, ShopPal)
    )

    // v2.04 (N36): the mode switch is a top-level ☰ drawer (Task Mode / Shop Mode). Flips still go
    // through requestModeFlip so the settings leave-guards cover them.
    val modeDrawerState = rememberDrawerState(DrawerValue.Closed)
    val modeDrawerScope = rememberCoroutineScope()
    fun closeModeDrawer() { modeDrawerScope.launch { runCatching { modeDrawerState.close() } } }
    CompositionLocalProvider(
        LocalModeFlipGuard provides { target -> requestModeFlip(target) },
        LocalModeDrawerOpen provides { modeDrawerScope.launch { runCatching { modeDrawerState.open() } } }
    ) {
    ModalNavigationDrawer(
        drawerState = modeDrawerState,
        gesturesEnabled = modeDrawerState.isOpen,   // never intercept the app's own horizontal swipes
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
                    Text("Remindly", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                    Text("MODE", style = MaterialTheme.typography.labelSmall, color = InkHint, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
                    listOf("TASK" to "Task Mode", "SHOP" to "Shop Mode").forEach { (key, label) ->
                        val on = ui.appMode == key
                        val pal = if (key == "SHOP") ShopPal else TasksPal
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) pal.chipBg else Color.Transparent)
                                .clickable { closeModeDrawer(); requestModeFlip(key) }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Icon(if (key == "SHOP") Icons.Filled.ShoppingCart else Icons.Filled.Checklist, null,
                                tint = if (on) pal.onChip else InkSubtle)
                            Spacer(Modifier.width(10.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                color = if (on) pal.onChip else InkPrimary, modifier = Modifier.weight(1f))
                            if (on) Text("✓", fontWeight = FontWeight.Bold, color = pal.onChip)
                        }
                    }
                    Text("The mode you were in opens next time you launch the app.",
                        style = MaterialTheme.typography.bodySmall, color = InkSubtle,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                }
            }
        }
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceCard,
                modifier = Modifier.pointerInput(settings.gestureNavSwipe, settings.navSwipeRightNext) {
                    if (!settings.gestureNavSwipe) return@pointerInput
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onHorizontalDrag = { _, amount -> total += amount },
                        onDragEnd = {
                            val threshold = 55.dp.toPx()
                            if (kotlin.math.abs(total) > threshold) {
                                val forward =
                                    if (settings.navSwipeRightNext) total > 0 else total < 0
                                if (shopMode) {
                                    // Shop mode: 4 fixed surfaces, swipe cycles 0..3 (leave-guard aware).
                                    requestShopTab((selectedShopTab + (if (forward) 1 else 3)) % 4)
                                } else {
                                    requestTab(nextVisibleTab(selectedTab, forward))
                                }
                            }
                        }
                    )
                }
            ) {
                (if (shopMode) shopEntries else entries).forEachIndexed { i, entry ->
                    if (shopMode || tabVisible(i)) {
                    NavigationBarItem(
                        selected = if (shopMode) selectedShopTab == i else selectedTab == i,
                        onClick = { if (shopMode) requestShopTab(i) else requestTab(i) },
                        icon = {
                            val bounce by animateFloatAsState(
                                targetValue = if ((if (shopMode) selectedShopTab else selectedTab) == i) 1.14f else 1f,
                                animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
                                label = "navBounce$i"
                            )
                            val settingsEntry = if (shopMode) i == 3 else i == 4
                            val badgeCount = if (!settings.badges && !settingsEntry) 0 else run {
                                // v1.40 bug#3: "pending today" = due today or overdue (not tomorrow+),
                                // excluding soft-deleted. No-due-date items aren't counted.
                                val tomorrow = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                                    .plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault())
                                    .toInstant().toEpochMilli()
                                val items = ItemStore.items.collectAsState().value
                                fun dueToday(t: Tab) = pendingTodayCount(items, t, tomorrow)
                                if (shopMode) when (i) {
                                    0 -> dueToday(Tab.SHOP)     // Buy carries the pending count
                                    3 -> Degrades.active.collectAsState().value.size
                                    else -> 0
                                } else when (i) {
                                    0 -> dueToday(Tab.TASKS)
                                    1 -> dueToday(Tab.SHOP)
                                    2 -> dueToday(Tab.LEARN)
                                    3 -> CallStore.calls.collectAsState().value.count { !it.done && it.deletedAt == null }
                                    // v1.71 (N2): Settings carries a count of live degrades so a
                                    // silent fallback is visible even when its banner was dismissed.
                                    4 -> Degrades.active.collectAsState().value.size
                                    else -> 0
                                }
                            }
                            BadgedBox(badge = {
                                if (badgeCount > 0) Badge { Text(if (badgeCount > 99) "99+" else "$badgeCount") }
                            }) {
                                Box(Modifier.scale(bounce)) { entry.icon() }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = entry.pal.accent,
                            unselectedIconColor = entry.pal.accent.copy(alpha = 0.45f),
                            selectedTextColor = entry.pal.accent,
                            unselectedTextColor = entry.pal.accent.copy(alpha = 0.55f),
                            indicatorColor = entry.pal.accent.copy(alpha = 0.14f)
                        ),
                        label = {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.labelMedium.fs(settings.fsNav)
                            )
                        }
                    )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (shopMode) when (selectedShopTab) {
                // v2.04: ☰ = mode drawer everywhere; each tab's ⚙ opens ITS OWN settings page (N35).
                0 -> ListSection(Tab.SHOP, onOpenShops = { openGear("BUY") }) { openGear("BUY") }
                1 -> ShopsScreen(onOpenGear = { openGear("SHOPS") })
                2 -> ProductsScreen(onOpenGear = { openGear("PRODUCTS") })
                else -> SettingsScreen()      // the General settings page, same as Task mode
            } else when (selectedTab) {
                0 -> ListSection(Tab.TASKS) { openGear("TASKS") }
                // Index 1 is never visible in Task mode; kept total — routes to Shop mode if ever hit.
                1 -> ListSection(Tab.SHOP, onOpenShops = { requestModeFlip("SHOP", thenShopTab = 1) }) { openGear("BUY") }
                2 -> ListSection(Tab.LEARN) { openGear("LEARN") }
                3 -> CallSection { openGear("CALLS") }
                else -> SettingsScreen()
            }
            // v1.39 Phase B: checkout calculator before a Shop item completes.
            val shopComplete by ShopCompletePrompt.pending.collectAsState()
            shopComplete?.let { sc ->
                ShopCompleteDialog(
                    item = sc,
                    onDismiss = { ShopCompletePrompt.clear() },
                    onConfirm = { shopN, calc, u -> Engine.finishShopComplete(context, sc, shopN, calc, u) }
                )
            }
            gearTab?.let { gt ->
                val gearAccent = when (gt) {
                    "SCHED" -> SettingsPal.accent
                    "SHOP", "BUY", "SHOPS", "PRODUCTS" -> ShopPal.accent
                    "LEARN" -> LearnPal.accent
                    "CALLS" -> CallPal.accent
                    else -> TasksPal.accent
                }
                val gearTitle = when (gt) {
                    "SHOP", "BUY" -> "Buy"; "SHOPS" -> "Shops"; "PRODUCTS" -> "Products"
                    "LEARN" -> "Learn"; "CALLS" -> "Calls"; "SCHED" -> "Scheduled"; else -> "Tasks"
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) {
                        if (gt != "SCHED") Row(   // v2.7 (N42): the Scheduled page draws its own header
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(gearAccent)
                                .statusBarsPadding()
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            IconButton(onClick = { requestCloseGear() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                            }
                            Text(
                                "$gearTitle settings",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        // v2.7 (N42): the Scheduled-alerts page rides the gear host (overlay + leave rules).
                        if (gt == "SCHED") ScheduledAlertsScreen(onBack = { requestCloseGear() })
                        else SettingsScreen(filterKey = gt)
                    }
                }
                BackHandler { requestCloseGear() }
            }
            AckChip(Modifier.align(Alignment.BottomCenter))
        }
    }

    crashText?.let { trace ->
        AlertDialog(
            onDismissRequest = { crashText = null },
            title = { Text("Remindly hit a crash last time", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "A screenshot of this popup is exactly what's needed to fix it — it vanishes when closed.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(trace.take(5000), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { crashText = null }) { Text("Close", fontWeight = FontWeight.Bold) } }
        )
    }

    gearLeaveDiff?.let { diff ->
        AlertDialog(
            onDismissRequest = { gearLeaveDiff = null },
            title = { Text("Save settings changes?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    diff.forEach { d -> Text("• ${d.first}: ${d.second} → ${d.third}", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    SettingsStore.persistNow()
                    gearLeaveDiff = null; gearTab = null; gearSnapshot = null
                    applyNavTarget(gearNavTarget); gearNavTarget = null
                }) { Text("Confirm", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    gearSnapshot?.let { snap -> SettingsStore.update { snap } }
                    gearLeaveDiff = null; gearTab = null; gearSnapshot = null
                    Logger.e(context, "NAV", null, "settings page left with changes discarded by navigation")
                    applyNavTarget(gearNavTarget); gearNavTarget = null
                }) { Text("Discard") }
            }
        )
    }

    if (pendingTab != null || pendingShopTab != null || pendingMode != null) {
        // One popup for all three exits: Task-Settings tab change, Shop-Settings tab change, mode flip.
        fun finishGuard() {
            pendingMode?.let { m -> runCatching { UiStore.update { it.copy(appMode = m) } } }
            settingsSnapshot = null; shopSettingsSnapshot = null
            pendingTab?.let { selectedTab = it }
            pendingShopTab?.let { selectedShopTab = it.coerceIn(0, 3) }
            pendingTab = null; pendingShopTab = null; pendingMode = null; pendingDiff = emptyList()
        }
        AlertDialog(
            onDismissRequest = { pendingTab = null; pendingShopTab = null; pendingMode = null },
            title = { Text("Save settings changes?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    pendingDiff.forEach { d ->
                        Text(
                            "${d.first}: ${d.second} → ${d.third}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    SettingsStore.persistNow()
                    finishGuard()
                }) { Text("Confirm", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    (if (shopMode) shopSettingsSnapshot else settingsSnapshot)?.let { snap ->
                        SettingsStore.update { snap }
                        SettingsStore.persistNow()
                    }
                    finishGuard()
                }) { Text("Discard", color = UrgentInk, fontWeight = FontWeight.Bold) }
            }
        )
    }
    }
    }
}

/** v1.4: aesthetic "moved to Done" confirmation shown when the delay is Off. */
@Composable
private fun AckChip(modifier: Modifier = Modifier) {
    val ack by Ack.current.collectAsState()
    var last by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Ack.Data?>(null)
    }
    LaunchedEffect(ack) { if (ack != null) last = ack }
    AnimatedVisibility(
        visible = ack != null,
        modifier = modifier.padding(bottom = 86.dp),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
        val a = last ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(50),
            color = PillDark,
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    a.label,
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            a.undo()
                            Ack.dismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        "UNDO",
                        color = androidx.compose.ui.graphics.Color(0xFF9EC5FF),   // hex-ok(Q2): dark-bar accent
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ListSection(tab: Tab, onOpenShops: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    var isDone by remember { mutableStateOf(UiStore.doneFor(tab.name)) }
    LaunchedEffect(isDone) { UiStore.setDone(tab.name, isDone) }
    // v2.05 (N38): the Buy Now view lives beside Active/Done on the Shop list only. It is armed by
    // a geofence arrival (BuyNow.arm) and hidden ONLY by the user; arming auto-selects it.
    val ui by UiStore.s.collectAsState()
    val shops by ShopStore.shops.collectAsState()
    val buyNowShop = remember(ui.buyNowShopId, shops) {
        if (tab != Tab.SHOP) null else runCatching { BuyNow.shop(context) }.getOrNull()
    }
    var view by remember { mutableStateOf(if (isDone) ListView.DONE else ListView.ACTIVE) }
    LaunchedEffect(buyNowShop?.id, ui.buyNowAt) {
        if (buyNowShop != null) { view = ListView.BUY_NOW; isDone = false }      // arm/replace → show it
        else if (view == ListView.BUY_NOW) view = ListView.ACTIVE                // hidden or gone
    }
    // v1.33 Q2 fix: Shop's Personal/General lives ABOVE the Active/Done Crossfade so both
    // branches read one shared value — flipping Active↔Done no longer resets it. In-memory
    // only: leaving the tab disposes this (returns to General), and it never survives a
    // restart, so the PIN-lock model is untouched. Relock also clears it.
    var personalFilter by remember { mutableStateOf(false) }
    val personalUnlocked by Engine.personalUnlocked.collectAsState()
    LaunchedEffect(personalUnlocked) {
        if (!personalUnlocked && personalFilter) personalFilter = false
    }
    Crossfade(targetState = view, animationSpec = tween(220), label = "adFlip") { v ->
        ListPage(
            tab, isDone = v == ListView.DONE,
            onSwitchDone = { isDone = it; view = if (it) ListView.DONE else ListView.ACTIVE },
            personalFilter = personalFilter, setPersonalFilter = { personalFilter = it },
            onOpenShops = onOpenShops,
            onOpenSettings = onOpenSettings,
            view = v,
            onSwitchView = { nv -> view = nv; isDone = nv == ListView.DONE },
            buyNowShop = buyNowShop
        )
    }
}

@Composable
private fun CallSection(onOpenSettings: () -> Unit = {}) {
    var isDone by remember { mutableStateOf(UiStore.doneFor("CALLS")) }
    LaunchedEffect(isDone) { UiStore.setDone("CALLS", isDone) }
    Crossfade(targetState = isDone, animationSpec = tween(220), label = "callFlip") { done ->
        CallPage(isDone = done, onSwitchDone = { isDone = it }, onOpenSettings = onOpenSettings)
    }
}
