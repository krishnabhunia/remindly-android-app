package com.krishna.remindly

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Cake
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val DELAY_OPTIONS = listOf(0, 1, 3, 5, 7)

@Composable
/**
 * v2.00 (N31): [embedded] renders the filtered sections WITHOUT the header and WITHOUT their own
 * scroll container so the SHOP page can sit inside Shop Settings' scrolling column (Q1a).
 */
fun SettingsScreen(filterKey: String? = null, embedded: Boolean = false) {
    // v2.01 (N32): the page→section table is a pure function in Model.kt (unit-tested).
    fun vis(key: String): Boolean = settingsSectionVisible(filterKey, key)
    val pal = SettingsPal
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.s.collectAsState()
    val items by ItemStore.items.collectAsState()
    val calls by CallStore.calls.collectAsState()

    // Exactly one section open at a time (About Me is fixed open and not part of this).
    // v1.5: the open section survives tab switches and app restarts.
    val openKey = UiStore.s.collectAsState().value.settingsOpenKey
    fun toggleKey(k: String) {
        UiStore.update { it.copy(settingsOpenKey = if (it.settingsOpenKey == k) "" else k) }
    }

    var pendingImport by remember { mutableStateOf<BackupBlob?>(null) }
    var importError by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var pinResetOpen by remember { mutableStateOf(false) }

    // Google state
    var googleEmail by remember { mutableStateOf(GoogleSync.email(context)) }
    var googleMsg by remember { mutableStateOf<String?>(null) }
    var resetAfterSignIn by remember { mutableStateOf(false) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(res.data)
        try {
            val acct = task.getResult(ApiException::class.java)
            googleEmail = acct.email
            googleMsg = "Signed in as ${acct.email}"
            // v1.35: also establish a Firebase Auth session so cloud sync can attach to /users/{uid}.
            SyncAuth.linkFromGoogle(context)
            if (resetAfterSignIn) { resetAfterSignIn = false; pinResetOpen = true }
        } catch (e: ApiException) {
            googleMsg = when (e.statusCode) {
                12501, 12502 -> null // cancelled
                7 -> "No internet connection — try again."
                10 -> "Google Cloud setup isn't finished yet. Follow GOOGLE-SETUP.md (add the SHA-1 + package to an Android OAuth client), then retry."
                else -> "Sign-in failed (code ${e.statusCode})."
            }
            resetAfterSignIn = false
        }
    }

    var exportTarget by remember { mutableStateOf("DATA") }
    var importTarget by remember { mutableStateOf("DATA") }
    var pendingDataImport by remember { mutableStateOf<Backup.DataBlob?>(null) }
    var showLogView by remember { mutableStateOf(false) }
    var legacySettings by remember { mutableStateOf<AppSettings?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(
                        (if (exportTarget == "SETTINGS") Backup.exportSettingsJson() else Backup.exportDataJson()).toByteArray()
                    )
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val text = runCatching {
                context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            }.getOrNull()
            if (text == null) { importError = true; Logger.e(context, "IMPORT", msg = "file could not be read"); return@let }
            when (Backup.sniff(text)) {
                "data" -> {
                    val blob = Backup.parseData(text)
                    if (blob != null && importTarget == "DATA") { legacySettings = null; pendingDataImport = blob } else importError = true
                }
                "settings" -> {
                    val blob = Backup.parseSettings(text)
                    if (blob != null && importTarget == "SETTINGS") {
                        Backup.applySettings(blob)
                        UiStore.update { u -> u.copy(savedNotice = listOf("Settings imported from file")) }
                    } else importError = true
                }
                "legacy" -> {
                    val blob = Backup.parse(text)
                    if (blob == null) { importError = true } else if (importTarget == "SETTINGS") {
                        Backup.applySettings(Backup.SettingsBlob(settings = blob.settings))
                        UiStore.update { u -> u.copy(savedNotice = listOf("Settings imported from a v1.x combined backup")) }
                    } else {
                        legacySettings = blob.settings
                        pendingDataImport = Backup.DataBlob(
                            items = blob.items, calls = blob.calls ?: emptyList(), places = blob.places,
                            tasksGroups = blob.settings.tasksGroups, shopGroups = blob.settings.shopGroups,
                            learnTopics = blob.settings.learnTopics
                        )
                    }
                }
                else -> { importError = true; Logger.e(context, "IMPORT", msg = "unrecognized backup file") }
            }
        }
    }

    if (showLogView) {
        AlertDialog(
            onDismissRequest = { showLogView = false },
            title = { Text("Error log", fontWeight = FontWeight.Bold) },
            text = {
                val log = remember { Logger.readAll(context).takeLast(100_000).ifBlank { "The log is empty — no errors recorded." } }
                Box(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLogView = false }) { Text("Close") } }
        )
    }

    pendingDataImport?.let { blob ->
        AlertDialog(
            onDismissRequest = { pendingDataImport = null; legacySettings = null },
            title = { Text("Import data", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "${blob.items.size} items · ${blob.calls.size} calls · ${blob.places.size} places found.\n\n" +
                        "Merge keeps everything already on this phone — duplicates resolve to whichever copy was saved last. " +
                        "Replace wipes current data first." +
                        (if (legacySettings != null) "\n\nThis is a v1.x combined file — its settings can be applied too." else "")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Backup.mergeData(context, blob)
                    legacySettings?.let { Backup.applySettings(Backup.SettingsBlob(settings = it)) }
                    pendingDataImport = null; legacySettings = null
                    UiStore.update { u -> u.copy(savedNotice = listOf("Data merged from backup")) }
                }) { Text("Merge", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        Backup.replaceData(context, blob)
                        legacySettings?.let { Backup.applySettings(Backup.SettingsBlob(settings = it)) }
                        pendingDataImport = null; legacySettings = null
                    }) { Text("Replace", color = OverdueRed) }
                    TextButton(onClick = { pendingDataImport = null; legacySettings = null }) { Text("Cancel") }
                }
            }
        )
    }

    Column(if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
        if (!embedded) GradientHeader(
            title = "Settings",
            // v2.00 (N31, Q5a): this page = General + Task-mode; Shop has its own Settings tab.
            subtitle = if (filterKey == null) "General · Task mode" else "",
            pal = pal,
            leading = { ModeDrawerButton() }
        )

        Column(
            modifier = if (embedded) Modifier.fillMaxWidth().padding(top = 4.dp)
            else Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 90.dp)
        ) {
            // -------------------------------------------------- alerts
            val notice = UiStore.s.collectAsState().value.savedNotice
            if (notice.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BlueSoft),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Settings saved when the app closed",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = HeaderBlue
                        )
                        notice.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, color = InkStrong)
                        }
                        TextButton(onClick = { UiStore.update { it.copy(savedNotice = emptyList()) } }) {
                            Text("Got it", color = SettingsAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---------------------------------------------- v2.01 (N32 B1/B2): Shop-mode sections
            // as accordions. "shop-mode" is on the General page (first) AND the SHOP deck; the other
            // three are SHOP-only. Same SettingsSection, same open-key, same look as every section.
            if (vis("maps")) SettingsSection("Maps & Location", { Icon(Icons.Filled.LocationOn, null, tint = sectionTint("backup")) },
                expanded = openKey == "maps", onToggle = { toggleKey("maps") }) { MapsLocationSection() }
            if (vis("api-keys")) SettingsSection("API keys", { Icon(Icons.Filled.Lock, null, tint = sectionTint("errlog")) },
                expanded = openKey == "api-keys", onToggle = { toggleKey("api-keys") }) { ApiKeysSection() }

            if (vis("b-lists")) SettingsSection("Lists", { Icon(Icons.Filled.ShoppingCart, null, tint = ShopPal.accent) },
                expanded = openKey == "b-lists", onToggle = { toggleKey("b-lists") }) {
                // v2.11 (N48): the Buy tab opens on Lists; Classic keeps the old flat view.
                Text("Buy tab opens on", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = settings.buyOpensOn != "CLASSIC", onClick = { SettingsStore.update { it.copy(buyOpensOn = "LISTS") } }, label = { Text("Lists") })
                    FilterChip(selected = settings.buyOpensOn == "CLASSIC", onClick = { SettingsStore.update { it.copy(buyOpensOn = "CLASSIC") } }, label = { Text("Classic (one flat list)") })
                }
                SettingRowSwitch("Reopen last list", "Skip the Lists screen when you come back", settings.buyReopenLast) { on -> SettingsStore.update { it.copy(buyReopenLast = on) } }
                SettingRowSwitch("Show Unsorted card", "Items without a list (hides itself when empty)", settings.buyShowUnsorted) { on -> SettingsStore.update { it.copy(buyShowUnsorted = on) } }
                SettingRowSwitch("Card shows estimated total", "“≈ ₹” from prices and price memory", settings.buyCardTotal) { on -> SettingsStore.update { it.copy(buyCardTotal = on) } }
                SettingRowSwitch("Warn on duplicates across lists", "“Already in Monthly stock” while adding", settings.buyDupWarn) { on -> SettingsStore.update { it.copy(buyDupWarn = on) } }
                Text("Default list", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text("Where items added outside a list go (Classic view, Buy Now)", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = settings.shopDefaultListId == null, onClick = { SettingsStore.update { mirrorListsIntoSettings(it.copy(shopDefaultListId = null)) } }, label = { Text("Unsorted") })
                    liveLists(settings.shopLists).sortedBy { it.name.lowercase() }.forEach { l ->
                        FilterChip(selected = settings.shopDefaultListId == l.id, onClick = { SettingsStore.update { mirrorListsIntoSettings(it.copy(shopDefaultListId = l.id)) } },
                            label = { Text((l.icon?.let { "$it " } ?: "") + l.name) })
                    }
                }
                SettingRowSwitch("Voice input on quick add", "Mic button on the add bar", settings.shopVoiceAdd) { on -> SettingsStore.update { it.copy(shopVoiceAdd = on) } }
                SettingRowSwitch("Suggestions from Products", "While typing an item", settings.shopSuggest) { on -> SettingsStore.update { it.copy(shopSuggest = on) } }
            }
            if (vis("sharing")) SettingsSection("Sharing a list", { Icon(Icons.Filled.Share, null, tint = ShopPal.accent) },
                expanded = openKey == "sharing", onToggle = { toggleKey("sharing") }) {
                // v2.11 (N48 S6): Krishna's format — "List_Name:-" then "1. Item - Qty / Type - Urgent - Bought".
                SettingRowSwitch("WhatsApp icon in list header", "One tap sends · long-press previews", settings.shareWaIcon) { on -> SettingsStore.update { it.copy(shareWaIcon = on) } }
                Text("WhatsApp app", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = settings.shareWaApp != "BUSINESS", onClick = { SettingsStore.update { it.copy(shareWaApp = "WHATSAPP") } }, label = { Text("WhatsApp") })
                    FilterChip(selected = settings.shareWaApp == "BUSINESS", onClick = { SettingsStore.update { it.copy(shareWaApp = "BUSINESS") } }, label = { Text("WhatsApp Business") })
                }
                SettingRowSwitch("Include bought items", "Listed last", settings.shareIncludeDone) { on -> SettingsStore.update { it.copy(shareIncludeDone = on) } }
                SettingRowSwitch("Show “Urgent” tag", "For items with priority Urgent", settings.shareUrgentTag) { on -> SettingsStore.update { it.copy(shareUrgentTag = on) } }
                SettingRowSwitch("Show “Bought” status", "On bought lines", settings.shareBoughtTag) { on -> SettingsStore.update { it.copy(shareBoughtTag = on) } }
                SettingRowSwitch("Show quantity / type", "e.g. “2 / L”", settings.shareIncludeQty) { on -> SettingsStore.update { it.copy(shareIncludeQty = on) } }
                Text("Heading suffix", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(":-", ":", "").forEach { sfx ->
                        FilterChip(selected = settings.shareHeadingSuffix == sfx, onClick = { SettingsStore.update { it.copy(shareHeadingSuffix = sfx) } },
                            label = { Text(if (sfx.isEmpty()) "None" else "“$sfx”") })
                    }
                }
                val sample = listShareText("Groceries", listOf(
                    Item(id = 1, tab = Tab.SHOP, title = "Milk", quantity = "2", unit = "L", priority = Priority.URGENT),
                    Item(id = 2, tab = Tab.SHOP, title = "Eggs", quantity = "12", unit = "pcs"),
                    Item(id = 3, tab = Tab.SHOP, title = "Onions", quantity = "1", unit = "kg", done = true)
                ), listShareOptsOf(settings))
                Text(sample, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = InkStrong,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(SurfaceSubtle, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(10.dp))
            }
            if (vis("shop-buy")) SettingsSection("Buy list", { Icon(Icons.Filled.ShoppingCart, null, tint = ShopPal.accent) },
                expanded = openKey == "shop-buy", onToggle = { toggleKey("shop-buy") }) {
                SettingRowSwitch("Group by shop", "Buy items grouped under their shop", settings.shopSort == "SHOP") { on ->
                    SettingsStore.update { it.copy(shopSort = if (on) "SHOP" else "DATE") }
                }
                SettingRowSwitch("Checkout calculator on complete", "Prices & discount before an item completes", settings.shopCheckoutCalc) { on ->
                    SettingsStore.update { it.copy(shopCheckoutCalc = on) }
                }
                SettingRowSwitch("Cheapest-shop suggestions", "Hint in the editor from price memory", settings.shopCheapestHint) { on ->
                    SettingsStore.update { it.copy(shopCheapestHint = on) }
                }
            }
            if (vis("shop-geo")) SettingsSection("Geofence alerts", { Icon(Icons.Filled.LocationOn, null, tint = ShopPal.accent) },
                expanded = openKey == "shop-geo", onToggle = { toggleKey("shop-geo") }) {
                SettingRowSwitch("Alert on arriving at a shop", "When it has pending buy items", settings.shopArriveAlert) { on ->
                    SettingsStore.update { it.copy(shopArriveAlert = on) }
                }
                // v2.05 (N37): the default alert set for shops left on "Default", plus the cooldown.
                Text("Default arrival alert", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text("Used by every shop set to “Default” · now: ${alertTypesLabel(settings.shopArriveTypes)}",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf('N' to "🔔 Notify", 'R' to "🔊 Ring", 'A' to "⏰ Alarm").forEach { (c, label) ->
                        val cur = normalizeAlertTypes(settings.shopArriveTypes) ?: "N"
                        FilterChip(
                            selected = cur.contains(c),
                            onClick = {
                                val next = normalizeAlertTypes(if (cur.contains(c)) cur.filter { it != c } else cur + c) ?: ""
                                SettingsStore.update { it.copy(shopArriveTypes = next) }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Text("Re-alert cooldown per shop", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text("Ignore repeat arrivals at the same shop within ${settings.shopArriveCooldownMin} min",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 5, 10, 15, 30, 60).forEach { m ->
                        FilterChip(selected = settings.shopArriveCooldownMin == m,
                            onClick = { SettingsStore.update { it.copy(shopArriveCooldownMin = m) } },
                            label = { Text(if (m == 0) "Off" else "$m min") })
                    }
                }
                Spacer(Modifier.height(6.dp))
                // STANDING RULE (v2.02 C3/C4): the 13-stop scale as a dropdown — never continuous.
                RadiusDropdown(value = settings.shopNewRadius, accent = ShopPal.accent, label = "Default radius for new shops") { v ->
                    SettingsStore.update { it.copy(shopNewRadius = v) }
                }
            }
            if (vis("shop-data")) SettingsSection("Data", { Icon(Icons.Filled.SaveAlt, null, tint = ShopPal.accent) },
                expanded = openKey == "shop-data", onToggle = { toggleKey("shop-data") }) {
                Text("Export shops & products", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("Cities, chains, shops, products and price links as JSON — via the share sheet.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                OutlinedButton(onClick = {
                    runCatching {
                        val payload = mapOf(
                            "kind" to "remindly-shopdata",
                            "cities" to CityStore.cities.value.filter { it.deletedAt == null },
                            "chains" to ChainStore.chains.value.filter { it.deletedAt == null },
                            "shops" to ShopStore.shops.value.filter { it.deletedAt == null },
                            "products" to ProductStore.products.value.filter { it.deletedAt == null },
                            "links" to ProductStore.links.value.filter { it.deletedAt == null }
                        )
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_TEXT, gson.toJson(payload))
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Export shop data"))
                    }.onFailure { Logger.e(context, "SHOP", it, "shop data export failed") }
                }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(50.dp)) {
                    Icon(Icons.Filled.Share, null, tint = ShopPal.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export as JSON", color = ShopPal.accent, fontWeight = FontWeight.SemiBold)
                }
            }

            if (vis("permissions")) SettingsSection("Permissions", { Icon(Icons.Filled.Security, null, tint = sectionTint("alerts")) },
                expanded = openKey == "permissions", onToggle = { toggleKey("permissions") }) { PermissionsSection() }   // v2.10 (N9)
            if (vis("updates")) SettingsSection("Updates", { Icon(Icons.Filled.SystemUpdate, null, tint = sectionTint("backup")) },
                expanded = openKey == "updates", onToggle = { toggleKey("updates") }) { UpdatesSection() }
            if (vis("sched")) SettingsSection("Scheduled alerts", { Icon(Icons.Filled.Alarm, null, tint = sectionTint("alerts")) },
                expanded = openKey == "sched", onToggle = { toggleKey("sched") }) {
                // v2.7 (N42): the one place that lists every armed alarm / ring / notification.
                val now = System.currentTimeMillis()
                val rows = remember(ItemStore.items.collectAsState().value, CallStore.calls.collectAsState().value, ShopStore.shops.collectAsState().value, PlaceStore.places.collectAsState().value, settings) {
                    runCatching { scheduledRows(ItemStore.items.value, CallStore.calls.value, ShopStore.shops.value, PlaceStore.places.value, settings, now) }.getOrDefault(emptyList())
                }
                val next = rows.firstOrNull { it.at != null && it.at > now }
                val flagged = rows.count { it.unreachable }
                Text("${rows.size} trigger${if (rows.size == 1) "" else "s"} armed" + (next?.let { " · next ${formatDateTime(it.at!!)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
                if (flagged > 0) Text("⚠️ $flagged armed on hidden or locked surfaces", style = MaterialTheme.typography.bodySmall, color = AmberInk, fontWeight = FontWeight.SemiBold)
                else Text("Every armed alert has a card you can open.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                OutlinedButton(onClick = { SchedNav.open.value = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(50.dp)) {
                    Text("Open scheduled alerts", fontWeight = FontWeight.SemiBold)
                }
            }
            if (vis("lists")) SettingsSection("Lists", { Icon(Icons.Filled.Checklist, null, tint = sectionTint("done")) },
                expanded = openKey == "lists", onToggle = { toggleKey("lists") }) {
                // v2.7 (N43): checkbox on every group header — global switch; per-tab override on each tab's ⚙.
                SettingRowSwitch("Group header checkboxes", "Complete or restore a whole group from its header", settings.groupHeaderCheck) { on ->
                    SettingsStore.update { it.copy(groupHeaderCheck = on) }
                }
                Text("Per-tab override: Tasks ⚙ · Learn ⚙ · Buy ⚙ (Inherit / On / Off).", style = MaterialTheme.typography.bodySmall, color = InkHint)
            }
            if (vis("alerts")) SettingsSection("Alerts & Reminders (Global)", { Icon(Icons.Filled.Alarm, null, tint = sectionTint("alerts")) },
                expanded = openKey == "alerts", onToggle = { toggleKey("alerts") }) {
                // v1.81 (Q18): the snooze duration finally has a control. Previously it was
                // hardcoded, then wired to a field with no UI — unchangeable either way.
                Text("Snooze", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("How long Snooze defers a reminder. The button label follows this value.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                run {
                    // v1.83 (N10): a stored value outside the list made indexOf return -1, which
                    // .coerceAtLeast(0) turned into chip 0 — the screen said "10 m" while the app
                    // used something else. The real value now appears as its own chip instead.
                    val base = listOf(10, 30, 60, 90, 120)
                    val stored = snoozeMinutes(settings)
                    val offList = stored !in base
                    if (offList) LaunchedEffect(stored) {
                        Logger.e(context, "SETTINGS", msg = "snoozeM1=$stored is outside $base - shown as its own chip")
                    }
                    val opts = if (offList) (base + stored).sorted() else base
                    ChoiceChips(opts.map { Alerts.snoozeLabel(it) },
                        opts.indexOf(stored), sectionTint("alerts")) { ix ->
                        SettingsStore.update { it.copy(snoozeM1 = opts[ix]) }
                    }
                }
                Spacer(Modifier.padding(2.dp))
                Text("When Something Is Due", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                // v1.56 1.1: global master. Per-tab Inherit follows this.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Alerts (Master)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("Off silences every tab that is set to Inherit — each suppressed alert is written to Error Logs.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    }
                    Switch(checked = settings.alertsEnabled, onCheckedChange = { on -> SettingsStore.update { it.copy(alertsEnabled = on) } })
                }
                Spacer(Modifier.padding(3.dp))
                // v1.62: display + dialog — the row always shows the TRUE stored value; editing is
                // an explicit Save/Cancel act (the old inline "Set" link smeared set and edit).
                DurationSettingRow(
                    "Alarm Ring Duration",
                    "How long the alarm sound plays before stopping on its own — the card stays until you act.",
                    settings.alarmRingSeconds
                ) { v -> SettingsStore.update { it.copy(alarmRingSeconds = v) } }
                Spacer(Modifier.padding(3.dp))
                DurationSettingRow(
                    "Ring Duration",
                    "For the RING channel — continuous ringtone without the card.",
                    settings.ringRingSeconds
                ) { v -> SettingsStore.update { it.copy(ringRingSeconds = v) } }





                TimeSettingRow(
                    label = "Default due time",
                    subtitle = "Date-only items ring at this time (locked in when you save the item).",
                    minutes = settings.defaultDueMinutes
                ) { m -> SettingsStore.update { it.copy(defaultDueMinutes = m) } }


                if (Build.VERSION.SDK_INT >= 31) {
                    Spacer(Modifier.padding(4.dp))
                    val exact = AlarmScheduler.canExact(context)
                    Text(
                        if (exact) "Exact alarms: allowed ✓" else "Exact alarms are blocked — reminders may run late.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exact) ShopInk else OverdueRed
                    )
                    if (!exact) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }) { Text("Allow exact alarms", color = TasksPal.accent, fontWeight = FontWeight.Bold) }
                    }
                }
                Text(
                    "Expiry dates show on the item's card; a done Shop item is kept in Done until 9 AM on its expiry day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSubtle
                )
            }

            // -------------------------------------------------- completed

            // -------------------------------------------------- gestures & navigation
            if (vis("adding")) SettingsSection("Adding Items (Global)", { Icon(Icons.Filled.AddCircleOutline, null, tint = sectionTint("adding")) },
                expanded = openKey == "gadd", onToggle = { toggleKey("gadd") }) {
                Text(
                    "Tabs set to Inherit follow these. A tab can diverge in its own gear.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                Text("New-Item Due Date", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ChoiceChips(
                    options = listOf("Off", "Today", "Tomorrow", "In ${settings.globalNewDueDays} days"),
                    selected = when (settings.globalNewDueMode) { "OFF" -> 0; "TODAY" -> 1; "IN_N" -> 3; else -> 2 },
                    accent = TasksPal.accent
                ) { i -> SettingsStore.update { it.copy(globalNewDueMode = listOf("OFF", "TODAY", "TOMORROW", "IN_N")[i]) } }
                if (settings.globalNewDueMode == "IN_N") ChoiceChips(
                    options = listOf("2", "3", "5", "7"),
                    selected = when (settings.globalNewDueDays) { 3 -> 1; 5 -> 2; 7 -> 3; else -> 0 },
                    accent = TasksPal.accent
                ) { i -> SettingsStore.update { it.copy(globalNewDueDays = listOf(2, 3, 5, 7)[i]) } }
                // v1.86 (N25): which schedule kind a NEW item opens on — the editor seed.
                Text("Default Schedule", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ChoiceChips(
                    options = listOf("No Reminders", "One-time", "Repeat"),
                    selected = when (settings.schedDefault) { "NONE" -> 0; "REPEAT" -> 2; else -> 1 },
                    accent = TasksPal.accent
                ) { i -> SettingsStore.update { it.copy(schedDefault = listOf("NONE", "ONCE", "REPEAT")[i]) } }
                Text(
                    "Shop always opens on One-time or Repeat — a purchase can't be a No-Reminders item.",
                    style = MaterialTheme.typography.labelSmall, color = InkHint
                )
                TimeSettingRow(
                    label = "New-item due time",
                    subtitle = "Fresh items land at this time.",
                    minutes = settings.globalNewDueMinutes
                ) { m -> SettingsStore.update { it.copy(globalNewDueMinutes = m) } }
                // v1.47 Feature 2c: default due time OR no due time.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Give New Items A Due Time", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.globalNewDueTimed)
                                "New items get a due date and the time above."
                            else "New items are date-only — no clock time. They ring at the \"Default due time\".",
                            style = MaterialTheme.typography.bodySmall, color = InkSubtle
                        )
                    }
                    Switch(checked = settings.globalNewDueTimed, onCheckedChange = { on ->
                        SettingsStore.update { it.copy(globalNewDueTimed = on) }
                    })
                }
            }

            if (vis("done")) SettingsSection("Done Items (Global)", { Icon(Icons.Filled.Timer, null, tint = sectionTint("housekeeping")) },
                expanded = openKey == "gdone", onToggle = { toggleKey("gdone") }) {
                Text(
                    "Tabs set to Inherit follow these. A tab can diverge in its own gear.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                Text("Move-To-Done Delay", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "After ticking the box, wait this long before moving to Done",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                ChoiceChips(
                    options = DELAY_OPTIONS.map { if (it == 0) "Off" else "${it}s" },
                    selected = DELAY_OPTIONS.indexOf(settings.moveDelaySec).coerceAtLeast(0),
                    accent = ShopPal.accent
                ) { i -> SettingsStore.update { it.copy(moveDelaySec = DELAY_OPTIONS[i]) } }
                Text(
                    "Off moves items instantly — no countdown chip, the green tick still plays.",
                    style = MaterialTheme.typography.labelSmall, color = InkHint
                )
                Spacer(Modifier.padding(3.dp))
                Text("Auto-Clear Done Items", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ChoiceChips(
                    options = listOf("Off", "7 days", "30 days", "90 days"),
                    selected = when (settings.globalDoneClearDays) { 7 -> 1; 30 -> 2; 90 -> 3; else -> 0 },
                    accent = ShopPal.accent
                ) { i -> SettingsStore.update { it.copy(globalDoneClearDays = listOf(0, 7, 30, 90)[i]) } }
            }

            if (vis("gestures")) SettingsSection("Gestures & Navigation (Global)", { Icon(Icons.Filled.Swipe, null, tint = sectionTint("gestures")) },
                expanded = openKey == "gestures", onToggle = { toggleKey("gestures") }) {
                Text(
                    "These are the global defaults — every tab set to Inherit follows them; a tab can diverge in its own gear.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                GestureSwitchRow("Swipe cards to move items", "Drag a card sideways", settings.gestureCardSwipe) { on ->
                    SettingsStore.update { it.copy(gestureCardSwipe = on) }
                }
                if (settings.gestureCardSwipe) {
                    ChoiceChips(
                        options = listOf("Right → Done", "Left → Done"),
                        selected = if (settings.cardSwipeRightDone) 0 else 1,
                        accent = CallPal.accent
                    ) { i -> SettingsStore.update { it.copy(cardSwipeRightDone = i == 0) } }
                }
                GestureSwitchRow(
                    "Delete on reverse swipe",
                    "The opposite direction to the move deletes the card (honours the Deleting-Cards style below)",
                    settings.revSwipeDelete
                ) { on -> SettingsStore.update { it.copy(revSwipeDelete = on) } }
                GestureSwitchRow("Swipe blank space to flip Active ↔ Done", "The pill on top always works too", settings.gesturePageSwipe) { on ->
                    SettingsStore.update { it.copy(gesturePageSwipe = on) }
                }
                if (settings.gesturePageSwipe) ChoiceChips(
                    options = listOf("Right shows Done", "Right shows Active"),
                    selected = if (settings.pageSwipeRightDone) 0 else 1,
                    accent = CallPal.accent
                ) { i -> SettingsStore.update { it.copy(pageSwipeRightDone = i == 0) } }
                Text("Deleting Cards", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ChoiceChips(
                    options = listOf("Instant with UNDO", "Ask every time"),
                    selected = if (settings.deleteStyle == "CONFIRM") 1 else 0,
                    accent = CallPal.accent
                ) { i -> SettingsStore.update { it.copy(deleteStyle = if (i == 1) "CONFIRM" else "UNDO") } }
                Spacer(Modifier.padding(2.dp))
                GestureSwitchRow(
                    title = "Slide the bottom bar to change tabs",
                    subtitle = "A horizontal slide on the navigation bar",
                    checked = settings.gestureNavSwipe
                ) { on -> SettingsStore.update { it.copy(gestureNavSwipe = on) } }
                if (settings.gestureNavSwipe) ChoiceChips(
                    options = listOf("Right → next tab", "Right → previous tab"),
                    selected = if (settings.navSwipeRightNext) 0 else 1,
                    accent = CallPal.accent
                ) { i -> SettingsStore.update { it.copy(navSwipeRightNext = i == 0) } }
            }

            // -------------------------------------------------- font
            if (vis("appearance")) SettingsSection("Appearance", { Icon(Icons.Filled.FormatSize, null, tint = sectionTint("appearance")) },
                expanded = openKey == "font", onToggle = { toggleKey("font") }) {
                Spacer(Modifier.padding(3.dp))
                Text("Theme", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ChoiceChips(
                    options = listOf("Follow system", "Light", "Dark"),
                    selected = when (settings.theme) { "LIGHT" -> 1; "DARK" -> 2; else -> 0 },
                    accent = LearnPal.accent
                ) { i -> SettingsStore.update { it.copy(theme = listOf("SYSTEM", "LIGHT", "DARK")[i]) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show The 'Medium' Tag", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("Medium is the everyday default, so its chip is hidden. Urgent, High and Low always show. Each tab can override this below.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    }
                    Switch(checked = settings.showMediumTag, onCheckedChange = { on -> SettingsStore.update { it.copy(showMediumTag = on) } })
                }
                Spacer(Modifier.padding(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Count Badges On Tabs", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("A small red count on each tab for items due today (or overdue).", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    }
                    Switch(checked = settings.badges, onCheckedChange = { on -> SettingsStore.update { it.copy(badges = on) } })
                }
                Spacer(Modifier.padding(3.dp))
                Text("Visible Tabs", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                // v2.00 (N31): Task-mode tabs only — Shop lives in Shop mode (flip the chip).
                Text("Hide tabs you don't use — Settings always stays. Shop lives in Shop mode — flip the chip.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                // v2.7 (N40): hiding a tab used to leave its reminders ALARMING with no card to reach —
                // Krishna's "alarms with no item". Now hiding a tab that still has armed reminders asks
                // first: hide + silence, hide anyway, or cancel. (The Scheduled-alerts page flags the rest.)
                var hideAsk by remember { mutableStateOf<Pair<String, Int>?>(null) }   // tab key, armed count
                val liveItems = ItemStore.items.collectAsState().value
                fun armedCount(t: Tab) = liveItems.count { it.tab == t && it.deletedAt == null && !it.done && (it.dueAt ?: 0L) > System.currentTimeMillis() }
                fun armedCalls() = CallStore.calls.value.count { it.deletedAt == null && !it.done }
                fun hideTab(key: String, on: Boolean) {
                    if (on) { SettingsStore.update { when (key) { "TASKS" -> it.copy(showTasks = true); "LEARN" -> it.copy(showLearn = true); else -> it.copy(showCalls = true) } }; return }
                    val n = when (key) { "TASKS" -> armedCount(Tab.TASKS); "LEARN" -> armedCount(Tab.LEARN); else -> armedCalls() }
                    if (n > 0) hideAsk = key to n
                    else SettingsStore.update { when (key) { "TASKS" -> it.copy(showTasks = false); "LEARN" -> it.copy(showLearn = false); else -> it.copy(showCalls = false) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tasks", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.showTasks, onCheckedChange = { on -> hideTab("TASKS", on) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Learn", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.showLearn, onCheckedChange = { on -> hideTab("LEARN", on) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Calls", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.showCalls, onCheckedChange = { on -> hideTab("CALLS", on) })
                }
                hideAsk?.let { (key, n) ->
                    val label = when (key) { "TASKS" -> "Tasks"; "LEARN" -> "Learn"; else -> "Calls" }
                    AlertDialog(
                        onDismissRequest = { hideAsk = null },
                        title = { Text("Hide $label?", fontWeight = FontWeight.Bold) },
                        text = { Text("$n upcoming reminder${if (n == 1) "" else "s"} on $label would keep alerting with no card to open. Hide and silence them, or hide anyway?") },
                        confirmButton = {
                            TextButton(onClick = {
                                SettingsStore.update {
                                    when (key) {
                                        "TASKS" -> it.copy(showTasks = false, tasksAlertsOn = "OFF")
                                        "LEARN" -> it.copy(showLearn = false, learnAlertsOn = "OFF")
                                        else -> it.copy(showCalls = false, callsAlertsOn = "OFF")
                                    }
                                }
                                Logger.e(context, "NAV", null, "tab $label hidden + alerts silenced ($n armed)")
                                hideAsk = null
                            }) { Text("Hide + silence", fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = {
                                    SettingsStore.update { when (key) { "TASKS" -> it.copy(showTasks = false); "LEARN" -> it.copy(showLearn = false); else -> it.copy(showCalls = false) } }
                                    Logger.e(context, "NAV", null, "tab $label hidden with $n armed reminders still alerting (user chose Hide anyway)")
                                    hideAsk = null
                                }) { Text("Hide anyway", color = OverdueRed) }
                                TextButton(onClick = { hideAsk = null }) { Text("Cancel") }
                            }
                        }
                    )
                }
                Spacer(Modifier.padding(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show Clear-All Icon On Done Headers", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("The bulk trash icon on Done year/month/group headers, on every tab. Each card keeps its own Delete.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    }
                    Switch(checked = settings.showDeleteOnDone, onCheckedChange = { on -> SettingsStore.update { it.copy(showDeleteOnDone = on) } })
                }
                Spacer(Modifier.padding(3.dp))
                Text("Card Layout (defaults)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("What shows on each item card. The item name always shows. Each tab can override these in its own gear (Inherit follows here).", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Priority tag", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.cardShowPriority, onCheckedChange = { on -> SettingsStore.update { it.copy(cardShowPriority = on) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Date & time", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.cardShowDateTime, onCheckedChange = { on -> SettingsStore.update { it.copy(cardShowDateTime = on) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Checkbox", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.cardShowCheckbox, onCheckedChange = { on -> SettingsStore.update { it.copy(cardShowCheckbox = on) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat tag", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.cardShowRepeat, onCheckedChange = { on -> SettingsStore.update { it.copy(cardShowRepeat = on) } })
                }
                // v1.80 (N5): Reminder Type chip — Tasks/Shop/Learn only, Calls excluded.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reminder type tag", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = settings.cardShowAlertType, onCheckedChange = { on -> SettingsStore.update { it.copy(cardShowAlertType = on) } })
                }
                if (settings.cardShowAlertType) {
                    Text("Tag style", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    ChoiceChips(listOf("Icon", "Text"),
                        if (settings.cardAlertTypeStyle == "TEXT") 1 else 0, SettingsAccent) { i ->
                        SettingsStore.update { it.copy(cardAlertTypeStyle = if (i == 1) "TEXT" else "ICON") }
                    }
                    Text("Only Alarm and Ring show a tag — Notify is the default, so a tag would say nothing.",
                        style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                }
                Spacer(Modifier.padding(2.dp))
                val openSubs = UiStore.s.collectAsState().value.openSubs
                SubGroup("Overall size", "ap-overall", openSubs) {
                Text(
                    "Everything — ${"%.0f".format(settings.fontScale * 100)}% · scales the whole app at once",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = settings.fontScale,
                    onValueChange = { v ->
                        val snapped = (v / 0.05f).roundToInt() * 0.05f
                        SettingsStore.update(persist = false) { it.copy(fontScale = snapped) }
                    },
                    onValueChangeFinished = { SettingsStore.persistNow() },
                    valueRange = 0.85f..1.4f,
                    steps = 10
                )
                Text(
                    "The quick brown fox — live preview",
                    style = MaterialTheme.typography.bodyLarge
                )
                }

                SubGroup("Per-element sizes", "ap-roles", openSubs) {
                Text(
                    "Fine-tune each element on top of the overall size:",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                FsRow("Bottom navigation", settings.fsNav) { v -> SettingsStore.update(persist = false) { it.copy(fsNav = v) } }
                FsRow("Card titles", settings.fsCardTitle) { v -> SettingsStore.update(persist = false) { it.copy(fsCardTitle = v) } }
                FsRow("Card details & chips", settings.fsCardDetail) { v -> SettingsStore.update(persist = false) { it.copy(fsCardDetail = v) } }
                FsRow("Group headers", settings.fsGroupHeader) { v -> SettingsStore.update(persist = false) { it.copy(fsGroupHeader = v) } }
                FsRow("Screen headers", settings.fsScreenHeader) { v -> SettingsStore.update(persist = false) { it.copy(fsScreenHeader = v) } }
                FsRow("Buttons & input fields", settings.fsButtons) { v -> SettingsStore.update(persist = false) { it.copy(fsButtons = v) } }
                }

                SubGroup("Card density", "ap-density", openSubs) {
                Text(
                    "Card density — ${"%.0f".format(settings.densityPct * 100)}%",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = settings.densityPct,
                    onValueChange = { v ->
                        val snapped = (v / 0.05f).roundToInt() * 0.05f
                        SettingsStore.update(persist = false) { it.copy(densityPct = snapped) }
                    },
                    onValueChangeFinished = { SettingsStore.persistNow() },
                    valueRange = 0f..1f,
                    steps = 19
                )
                Text(
                    "One number, set to your convenience — 0% razor-thin · 80% = the old Comfortable · 100% extra roomy. Live everywhere, calls included.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                }
            }

            if (vis("t-groups")) SettingsSection("Tasks Groups", { Icon(Icons.Filled.Folder, null, tint = sectionTint("groups")) },
                expanded = openKey == "groups", onToggle = { toggleKey("groups") }) {
                Text(
                    "Deleting is blocked while any item still uses a name; renaming updates every item carrying it.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                GroupListEditor("Tasks groups", settings.tasksGroups,
                    inUseCount = { g -> ItemStore.items.value.count { it.tab == Tab.TASKS && it.group == g } },
                    onAdd = { g -> SettingsStore.update { it.copy(tasksGroups = (it.tasksGroups + g).distinct()) } },
                    onDelete = { g -> SettingsStore.update { it.copy(tasksGroups = it.tasksGroups - g) } },
                    onRename = { o, n ->
                        SettingsStore.update { it.copy(tasksGroups = it.tasksGroups.map { x -> if (x == o) n else x }.distinct()) }
                        ItemStore.items.value.filter { it.tab == Tab.TASKS && it.group == o }
                            .forEach { Engine.addOrUpdate(context, it.copy(group = n)) }
                    })
            }
            if (vis("s-groups")) SettingsSection("Lists (order & names)", { Icon(Icons.Filled.Folder, null, tint = sectionTint("groups")) },
                expanded = openKey == "groups", onToggle = { toggleKey("groups") }) {
                // v2.11 (N48): Shop groups ARE the Buy lists now — this editor works on the list records
                // (order = the Lists screen's Custom order, which the Trip mode and Classic view also use).
                val ordered = sortedLists(settings.shopLists, "CUSTOM") { 0L }
                if (ordered.size > 1) {
                    Text("Order (Custom sort, Trip Mode & Classic view)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    ordered.forEachIndexed { idx, l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((l.icon?.let { "$it " } ?: "") + l.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(enabled = idx > 0, onClick = { ShopListStore.move(context, l, up = true) }) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                            IconButton(enabled = idx < ordered.size - 1, onClick = { ShopListStore.move(context, l, up = false) }) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                        }
                    }
                    Spacer(Modifier.padding(3.dp))
                }
                GroupListEditor("Lists", ordered.map { it.name },
                    inUseCount = { g -> listNamed(settings.shopLists, g)?.let { ShopListStore.itemsOf(it.id).size } ?: 0 },
                    onAdd = { g -> ShopListStore.create(context, g, null, null, null, false) },
                    // Removing a list here keeps its items (they move to Unsorted) — the safe L6 default.
                    onDelete = { g -> listNamed(settings.shopLists, g)?.let { ShopListStore.delete(context, it, ListDeleteMode.KEEP_UNSORTED, null) } },
                    onRename = { o, n ->
                        listNamed(settings.shopLists, o)?.let { l -> ShopListStore.edit(context, l, n, l.icon, l.usualShopId, l.shoppingDay, l.personal) }
                    })
            }
            if (vis("l-groups")) SettingsSection("Learn Topics", { Icon(Icons.Filled.Folder, null, tint = sectionTint("groups")) },
                expanded = openKey == "groups", onToggle = { toggleKey("groups") }) {
                GroupListEditor("Learn topics", settings.learnTopics,
                    inUseCount = { g -> ItemStore.items.value.count { it.tab == Tab.LEARN && it.topic == g } },
                    onAdd = { g -> SettingsStore.update { it.copy(learnTopics = (it.learnTopics + g).distinct()) } },
                    onDelete = { g -> SettingsStore.update { it.copy(learnTopics = it.learnTopics - g) } },
                    onRename = { o, n ->
                        SettingsStore.update { it.copy(learnTopics = it.learnTopics.map { x -> if (x == o) n else x }.distinct()) }
                        ItemStore.items.value.filter { it.tab == Tab.LEARN && it.topic == o }
                            .forEach { Engine.addOrUpdate(context, it.copy(topic = n)) }
                    })
            }

            if (vis("t-add")) AddHousekeepingSection(Tab.TASKS, settings, openKey) { toggleKey(it) }
            if (vis("s-add")) AddHousekeepingSection(Tab.SHOP, settings, openKey) { toggleKey(it) }
            if (vis("l-add")) AddHousekeepingSection(Tab.LEARN, settings, openKey) { toggleKey(it) }
            // v1.19 item 9: what the collapsed call card may show.
            if (vis("c-add")) SettingsSection("Adding Calls", { Icon(Icons.Filled.AddCircleOutline, null, tint = sectionTint("adding")) },
                expanded = openKey == "c-add", onToggle = { toggleKey("c-add") }) {
                Text("New-Call Date", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ChoiceChips(
                    options = listOf("Off", "Today", "Tomorrow", "In ${settings.callsNewDueDays} days"),
                    selected = when (settings.callsNewDueMode) { "OFF" -> 0; "TOMORROW" -> 2; "NDAYS" -> 3; else -> 1 },
                    accent = CallPal.accent
                ) { i ->
                    val m = when (i) { 0 -> "OFF"; 2 -> "TOMORROW"; 3 -> "NDAYS"; else -> "TODAY" }
                    SettingsStore.update { it.copy(callsNewDueMode = m) }
                }
                Text(
                    "What a manually added call reminder starts with. \"Off\" leaves it unscheduled — it waits in Active until you give it a time, and never fires.",
                    style = MaterialTheme.typography.labelSmall, color = InkHint
                )
            }

            if (vis("c-hk")) SettingsSection("Card Details", { Icon(Icons.Filled.Tune, null, tint = sectionTint("appearance")) },
                expanded = openKey == "c-card", onToggle = { toggleKey("c-card") }) {
                Text(
                    "The collapsed card shows the name alone; tick anything else it may carry. Tapping a card always reveals everything.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                @Composable
                fun row(label: String, on: Boolean, set: (Boolean) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Switch(checked = on, onCheckedChange = set)
                    }
                }
                row("Number", settings.callCardShowNumber) { v -> SettingsStore.update { it.copy(callCardShowNumber = v) } }
                row("Missed Date & Time", settings.callCardShowMissedAt) { v -> SettingsStore.update { it.copy(callCardShowMissedAt = v) } }
                row("Missed Count", settings.callCardShowMissedCount) { v -> SettingsStore.update { it.copy(callCardShowMissedCount = v) } }
                row("Note", settings.callCardShowNote) { v -> SettingsStore.update { it.copy(callCardShowNote = v) } }
                row("Label", settings.callCardShowLabel) { v -> SettingsStore.update { it.copy(callCardShowLabel = v) } }
                row("Next Occurrence", settings.callCardShowNext) { v -> SettingsStore.update { it.copy(callCardShowNext = v) } }
            }

            if (vis("c-hk")) SettingsSection("Housekeeping", { Icon(Icons.Filled.Timer, null, tint = sectionTint("housekeeping")) },
                expanded = openKey == "hk", onToggle = { toggleKey("hk") }) {
                // v1.52: a declined call is REJECTED_TYPE in the call log, not MISSED_TYPE, so it is
                // ignored by default. Users who decline calls expect them captured — let them opt in.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Treat Declined Calls As Missed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Android records a call you decline as \"rejected\", not \"missed\". Turn this on to get a call-back reminder for those too.",
                            style = MaterialTheme.typography.bodySmall, color = InkSubtle
                        )
                    }
                    Switch(checked = settings.callIncludeRejected, onCheckedChange = { on ->
                        SettingsStore.update { it.copy(callIncludeRejected = on) }
                    })
                }
                Spacer(Modifier.padding(3.dp))
                Text("Auto-Clear Done Call Reminders", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ClearChips(null, settings, CallPal.accent)
                Spacer(Modifier.padding(3.dp))
                DelayChips(null, settings, CallPal.accent)
            }

            if (vis("c-al")) SettingsSection("Alerts", { Icon(Icons.Filled.NotificationsActive, null, tint = sectionTint("alerts")) },
                expanded = openKey == "cal2", onToggle = { toggleKey("cal2") }) {
                TabAlertsOnOff(null, settings, CallPal.accent)
                Spacer(Modifier.padding(3.dp))
            }

            if (vis("c-ge")) SettingsSection("Gestures", { Icon(Icons.Filled.Swipe, null, tint = sectionTint("gestures")) },
                expanded = openKey == "cge2", onToggle = { toggleKey("cge2") }) {
                TabGesturesBlock(null, settings, CallPal.accent)
            }

            // v1.48: fixes the v1.44 gap — Calls had no Card Layout section at all.
            if (vis("c-cl")) SettingsSection("Card Layout", { Icon(Icons.Filled.ViewAgenda, null, tint = sectionTint("cardlayout")) },
                expanded = openKey == "c-cl", onToggle = { toggleKey("c-cl") }) {
                TabCardBlock(null, settings, CallPal.accent)
            }

            // v1.20 item 7: "Reset" belongs to the per-tab gear pages, under its own name.
            // On the main Settings tab its row joins the single Backup & Restore card below.
            if (filterKey != null) SettingsSection("Reset", { Icon(Icons.Filled.RestartAlt, null, tint = sectionTint("reset")) },
                expanded = openKey == "reset", onToggle = { toggleKey("reset") }) {
                ResetDefaultsRow(filterKey)
            }

            // -------------------------------------------------- location
            if (vis("location")) SettingsSection("Location & Battery", { Icon(Icons.Filled.LocationOn, null, tint = sectionTint("location")) },
                expanded = openKey == "location", onToggle = { toggleKey("location") }) {
                // v1.19 item 2: the Places sheet moved here from the Shop header.
                var showPlaces by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showPlaces = true }
                    .padding(vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.AddLocationAlt, null, tint = ShopPal.accent)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Shopping Places", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("Saved shops, geofence alerts, and group bindings", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    }
                }
                if (showPlaces) LocationsSheet(ShopPal) { showPlaces = false }
                Spacer(Modifier.padding(2.dp))
                Text("Default radius for new places: ${settings.defaultRadius.toInt()} m", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.defaultRadius,
                    onValueChange = { v ->
                        val snapped = (v / 50f).roundToInt() * 50f
                        SettingsStore.update(persist = false) { it.copy(defaultRadius = snapped) }
                    },
                    onValueChangeFinished = { SettingsStore.persistNow() },
                    valueRange = 100f..1000f,
                    steps = 17
                )
                val pm = context.getSystemService(PowerManager::class.java)
                val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                Text(
                    if (exempt) "Battery optimisation: exempted ✓"
                    else "Samsung may silently kill location alarms. Exempt Remindly from battery optimisation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exempt) ShopInk else OverdueRed
                )
                if (!exempt) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }) { Text("Exempt now", color = ShopPal.accent, fontWeight = FontWeight.Bold) }
                }
            }

            // -------------------------------------------------- PIN
            if (vis("pin")) SettingsSection("Personal PIN", { Icon(Icons.Filled.Lock, null, tint = PillDark) },
                expanded = openKey == "pin", onToggle = { toggleKey("pin") }) {
                if (PinStore.isSet()) {
                    Text("A PIN protects your Personal shop items.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    Row {
                        TextButton(onClick = { showChangePin = true }) {
                            Text("Change PIN", color = TasksPal.accent, fontWeight = FontWeight.Bold)
                        }
                        if (googleEmail != null) {
                            TextButton(onClick = {
                                resetAfterSignIn = true
                                signInLauncher.launch(GoogleSync.signInIntent(context))
                            }) { Text("Reset via Google", color = SettingsAccent, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Text(
                        if (googleEmail != null)
                            "Forgot it? Reset via Google re-confirms your account, then lets you set a fresh PIN."
                        else
                            "Tip: sign in with Google below to enable PIN recovery.",
                        style = MaterialTheme.typography.labelSmall, color = InkHint
                    )
                } else {
                    Text("No PIN yet. Set one to unlock the Personal shop filter.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    TextButton(onClick = { showSetPin = true }) {
                        Text("Set PIN", color = TasksPal.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // -------------------------------------------------- google account & sync

            // -------------------------------------------------- data
            if (vis("sharing")) SettingsSection("List Sharing", { Icon(Icons.Filled.Share, null, tint = sectionTint("housekeeping")) },
                expanded = openKey == "sharing", onToggle = { toggleKey("sharing") }) {
                Text(
                    "Send a Tasks or Shop group to another Remindly account as a FROZEN, read-only copy. " +
                        "Both sides sign in with Google once; lists travel by exact email. No push \u2014 a red dot on the Shared icon marks arrivals.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("List Sharing", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(if (settings.shareOn) "On \u2014 the Shared icon and group Share buttons show." else "Off \u2014 all sharing UI is hidden.",
                            style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                    }
                    Switch(checked = settings.shareOn, onCheckedChange = { on ->
                        SettingsStore.update { it.copy(shareOn = on) }
                        if (on) ShareStore.startIfSignedIn(context) else ShareStore.stop()
                    })
                }
                Text("Per tab", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                listOf(Tab.TASKS to settings.tasksShareOn, Tab.SHOP to settings.shopShareOn).forEach { (t0, cur) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t0.title, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                        ChoiceChips(
                            options = listOf("Inherit", "On", "Off"),
                            selected = when (cur) { "ON" -> 1; "OFF" -> 2; else -> 0 },
                            accent = TasksPal.accent
                        ) { i ->
                            val v = listOf("INHERIT", "ON", "OFF")[i]
                            SettingsStore.update { st -> if (t0 == Tab.SHOP) st.copy(shopShareOn = v) else st.copy(tasksShareOn = v) }
                        }
                    }
                }
                BlockedSendersBlock()
            }

            if (vis("health")) SettingsSection("Alarm Reliability", { Icon(Icons.Filled.HealthAndSafety, null, tint = sectionTint("health")) },
                expanded = openKey == "health", onToggle = { toggleKey("health") }) {
                val am = context.getSystemService(android.app.AlarmManager::class.java)
                val exactOk = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                val notifOk = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                val battOk = pm.isIgnoringBatteryOptimizations(context.packageName)
                @Composable fun statusRow(ok: Boolean, title: String, sub: String, action: () -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(if (ok) "✅" else "⚠️", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                        }
                        if (!ok) TextButton(onClick = action) { Text("Fix") }
                    }
                }
                statusRow(
                    exactOk, "Exact alarms",
                    if (exactOk) "Reminders fire to the minute." else "Android revoked exact alarms — reminders may drift.",
                    action = {
                        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(android.net.Uri.parse("package:" + context.packageName))
                            )
                        }
                    }
                )
                statusRow(
                    notifOk, "Notifications",
                    if (notifOk) "Notifications are allowed." else "Notifications are blocked — nothing can appear.",
                    action = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }
                    }
                )
                statusRow(
                    battOk, "Battery optimization",
                    if (battOk) "Remindly is exempt — alarms survive deep sleep." else "The OS may kill background alarms.",
                    action = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(android.net.Uri.parse("package:" + context.packageName))
                            )
                        }
                    }
                )
                Text(
                    "Some phone brands (Xiaomi, Oppo, Vivo, OnePlus…) also need Autostart enabled in their own battery settings.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
            }

            if (vis("tests")) SettingsSection("Alert Tests", { Icon(Icons.Filled.NotificationsActive, null, tint = sectionTint("tests")) },
                expanded = openKey == "tests", onToggle = { toggleKey("tests") }) {
                Text(
                    "Three real samples — the Alarm card, the Ring and the Notification: the exact surfaces real reminders use, TEST-labelled, never touching your data. 1-second fuse.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ Alerts.fireTestNotificationSample(context) }, 1000)
                        UiStore.update { u -> u.copy(savedNotice = listOf("In 1 second: ONE test notification — the real buttons, test-only actions.")) }
                    }) { Text("Test Notification") }
                    OutlinedButton(onClick = {
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ Alerts.fireTestRing(context) }, 1000)
                        UiStore.update { u -> u.copy(savedNotice = listOf("In 1 second: ONE test ring — continuous ringtone + notification, NO card. Dismiss is on the notification.")) }
                    }) { Text("Test Ring") }
                    // v1.86 (N19): the third sample — the REAL full-screen alarm card (sound +
                    // screen + the four buttons); every test action just closes it.
                    OutlinedButton(onClick = {
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ AlarmService.start(context, AlarmService.MODE_TEST) }, 1000)
                        UiStore.update { u -> u.copy(savedNotice = listOf("In 1 second: ONE test alarm — the real card and sound; buttons only close it.")) }
                    }) { Text("Test Alarm") }
                }
                Text(
                    "Test buttons only dismiss — nothing touches your reminders.",
                    style = MaterialTheme.typography.labelSmall, color = InkHint
                )
            }

            if (vis("bin")) SettingsSection("Recently Deleted", { Icon(Icons.Filled.DeleteOutline, null, tint = sectionTint("bin")) },
                expanded = openKey == "bin", onToggle = { toggleKey("bin") }) {
                val binItems = items.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
                val binCalls = calls.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
                // v1.19 item 4: hidden by default — count + Show/Hide + Clear.
                var showBin by remember { mutableStateOf(false) }
                var confirmClear by remember { mutableStateOf(false) }
                val binCount = binItems.size + binCalls.size
                Text(
                    "$binCount items · clears after 30 days",
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold
                )
                if (binCount == 0) {
                    Text("Nothing here.", style = MaterialTheme.typography.bodyMedium, color = InkHint)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (binCount > 0) OutlinedButton(onClick = { showBin = !showBin }) { Text(if (showBin) "Hide" else "Show") }
                    if (binCount > 0) OutlinedButton(onClick = { confirmClear = true }) { Text("Clear", color = OverdueRed) }
                }
                if (confirmClear) AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Permanently delete $binCount items?", fontWeight = FontWeight.Bold) },
                    text = { Text("This empties the bin now — nothing can be restored afterwards.") },
                    confirmButton = {
                        TextButton(onClick = {
                            binItems.forEach { Engine.deleteForever(context, it) }
                            binCalls.forEach { CallEngine.deleteForever(context, it) }
                            confirmClear = false; showBin = false
                        }) { Text("Clear", color = OverdueRed, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
                )
                if (showBin && binCount > 0) {
                    binItems.take(30).forEach { bi ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(bi.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Text(bi.tab.title, style = MaterialTheme.typography.labelSmall, color = InkHint)
                            }
                            TextButton(onClick = { Engine.restore(context, bi) }) { Text("Restore") }
                            TextButton(onClick = { Engine.deleteForever(context, bi) }) { Text("Delete", color = OverdueRed) }
                        }
                    }
                    binCalls.take(30).forEach { bc ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(bc.display, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Text("Calls", style = MaterialTheme.typography.labelSmall, color = InkHint)
                            }
                            TextButton(onClick = { CallEngine.restore(context, bc.copy(deletedAt = null)) }) { Text("Restore") }
                            TextButton(onClick = { CallEngine.deleteForever(context, bc) }) { Text("Delete", color = OverdueRed) }
                        }
                    }
                    OutlinedButton(onClick = {
                        binItems.forEach { Engine.deleteForever(context, it) }
                        binCalls.forEach { CallEngine.deleteForever(context, it) }
                    }) { Text("Empty bin", color = OverdueRed) }
                }
            }

            if (vis("backup")) SettingsSection("Backup & Restore", { Icon(Icons.Filled.SaveAlt, null, tint = sectionTint("backup")) },
                expanded = openKey == "data", onToggle = { toggleKey("data") }) {
                Text("Data Backup", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Runs by itself every 24 h into Documents/Remindly, keeping the last 7 days. Importing MERGES by default — nothing existing is deleted; a duplicate keeps whichever copy was saved last.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { exportTarget = "DATA"; exportLauncher.launch("remindly-data.json") }) { Text("Export data") }
                    OutlinedButton(onClick = {
                        importTarget = "DATA"
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    }) { Text("Import data") }
                }
                Spacer(Modifier.padding(4.dp))
                Text("Settings Backup (Manual Only)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Preferences only — your items, groups, PIN and places are never inside this file.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { exportTarget = "SETTINGS"; exportLauncher.launch("remindly-settings.json") }) { Text("Export settings") }
                    OutlinedButton(onClick = {
                        importTarget = "SETTINGS"
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    }) { Text("Import settings") }
                }
                // v1.20 item 7: Reset lives here on the main tab — one card, no twin.
                Spacer(Modifier.padding(3.dp))
                Text("Reset", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ResetDefaultsRow(filterKey)
            }

            if (vis("errlog")) SettingsSection("Error Logs", { Icon(Icons.Filled.BugReport, null, tint = sectionTint("errlog")) },
                expanded = openKey == "errlog", onToggle = { toggleKey("errlog") }) {
                Text(
                    "Only errors and exceptions land here — crashes and any failures the app catches. Share it with a bug report.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                Text(Logger.SHOWN_PATH, style = MaterialTheme.typography.labelSmall, color = InkHint)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showLogView = true }) { Text("View logs") }
                    OutlinedButton(onClick = {
                        val uri = Logger.shareUri(context)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            if (uri != null) {
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } else {
                                putExtra(android.content.Intent.EXTRA_TEXT, Logger.readAll(context).takeLast(100_000))
                            }
                        }
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share error log")) }
                    }) { Text("Share log") }
                    OutlinedButton(onClick = { Logger.clear(context) }) { Text("Clear") }
                }
            }



            // -------------------------------------------------- v1.22 item 6: one clock for the app
            // v1.79 (N4): wa.me needs a full international number. This replaces the hardcoded
            // "91" that waNumber() used to prepend to every 10-digit number.
            if (vis("wa")) SettingsSection("WhatsApp", { Icon(Icons.Filled.Chat, null, tint = sectionTint("google")) },
                expanded = openKey == "wa", onToggle = { toggleKey("wa") }) {
                Text(
                    "A reminder with a message gets a Send WhatsApp button. Tapping it opens the chat " +
                        "with your text ready \u2014 you still tap send. WhatsApp allows no other way.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                var cc by remember { mutableStateOf(settings.defaultCountryCode) }
                OutlinedTextField(
                    cc,
                    { v -> cc = v.filter { ch -> ch.isDigit() }.take(4)
                           SettingsStore.update { it.copy(defaultCountryCode = cc.ifBlank { "91" }) } },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Default country code") },
                    supportingText = { Text("Added to 10-digit numbers. Numbers already carrying a code are left alone.") }
                )
            }
            if (vis("clock")) SettingsSection("Time Format", { Icon(Icons.Filled.Schedule, null, tint = sectionTint("appearance")) },
                expanded = openKey == "clock", onToggle = { toggleKey("clock") }) {
                Text(
                    "Governs every time the app prints — cards, previews, pickers, notifications and both widgets.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                ChoiceChips(
                    options = listOf("Follow Phone", "12-Hour", "24-Hour"),
                    selected = when (settings.timeFormat) { "H12" -> 1; "H24" -> 2; else -> 0 },
                    accent = SettingsAccent
                ) { i ->
                    val v = when (i) { 1 -> "H12"; 2 -> "H24"; else -> "PHONE" }
                    SettingsStore.update { it.copy(timeFormat = v) }
                    TIME_FORMAT = v
                }
                Text(
                    "Now showing: " + formatTime(System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold
                )
            }

            // -------------------------------------------------- v1.22 item 13: swipe physics
            if (vis("swipe")) SettingsSection("Swipe Controls", { Icon(Icons.Filled.Swipe, null, tint = sectionTint("gestures")) },
                expanded = openKey == "swipe", onToggle = { toggleKey("swipe") }) {
                Text(
                    "How far and how firmly a card must be pushed before it flips between Active and Done. What a swipe does in each tab stays in that tab's own Gestures section.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                Text("Swipe Distance", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ChoiceChips(
                    options = listOf("Short 25%", "Medium 35%", "Long 50%"),
                    selected = when (settings.swipeDistancePct) { 25 -> 0; 50 -> 2; else -> 1 },
                    accent = SettingsAccent
                ) { i ->
                    SettingsStore.update { s -> s.copy(swipeDistancePct = when (i) { 0 -> 25; 2 -> 50; else -> 35 }) }
                }
                val dens = LocalDensity.current
                val widthDp = LocalConfiguration.current.screenWidthDp - 24
                val mm = remember(settings.swipeDistancePct, widthDp) {
                    // 1 dp = 1/160 inch = 0.15875 mm
                    (widthDp * (settings.swipeDistancePct / 100f) * 0.15875f)
                }
                Text(
                    "About ${"%.1f".format(mm)} mm of travel on this screen.",
                    style = MaterialTheme.typography.labelSmall, color = InkHint
                )
                Spacer(Modifier.padding(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Haptic Tick At Commit Point", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "A small buzz the moment the swipe has gone far enough, so your finger knows before it lifts.",
                            style = MaterialTheme.typography.bodySmall, color = InkSubtle
                        )
                    }
                    Switch(checked = settings.swipeHaptic, onCheckedChange = { on ->
                        SettingsStore.update { it.copy(swipeHaptic = on) }
                    })
                }
                Spacer(Modifier.padding(2.dp))
                Text("Flick Speed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "A quick flick commits a swipe before it reaches the distance above. Set it Off and only distance counts — the cure for accidental flicks.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                ChoiceChips(
                    options = listOf("Off", "Sensitive", "Normal", "Firm"),
                    selected = when (settings.swipeVelocityDp) { 0 -> 0; 80 -> 1; 200 -> 3; else -> 2 },
                    accent = SettingsAccent
                ) { i ->
                    SettingsStore.update { s -> s.copy(swipeVelocityDp = when (i) { 0 -> 0; 1 -> 80; 3 -> 200; else -> 125 }) }
                }
                Spacer(Modifier.padding(2.dp))
                OutlinedButton(onClick = {
                    SettingsStore.update { it.copy(swipeDistancePct = 35, swipeVelocityDp = 125, swipeHaptic = true) }
                }) { Text("Reset Swipe Defaults") }
            }

            // -------------------------------------------------- calendar sync (v1.21 item 2)
            if (vis("t-cal")) SettingsSection("Calendar (Read-Only)", { Icon(Icons.Filled.EditCalendar, null, tint = SettingsAccent) },
                expanded = openKey == "t-cal", onToggle = { toggleKey("t-cal") }) {
                // v2.10 (N9): through the shared requester — explained once, and a blocked permission
                // opens Android settings instead of a button that silently does nothing.
                val calReq = rememberPermRequest(PermKeys.CALENDAR) { granted ->
                    // v1.46 Feature 4a: read-only — granting no longer switches on any writing.
                    if (granted) {
                        UiStore.update { u -> u.copy(savedNotice = listOf("Calendar access granted — choose a calendar to read from.")) }
                    } else {
                        UiStore.update { u -> u.copy(savedNotice = listOf("Calendar permission denied — the calendar view stays empty.")) }
                    }
                }
                val chosenId = settings.calendarTargetId
                val target = remember(chosenId, settings.calendarSync) { if (chosenId > 0L) CalSync.targetInfo(context) else null }
                val anyTab = settings.calSyncTasks || settings.calSyncShop || settings.calSyncLearn || settings.calSyncCalls
                var showCalPick by remember { mutableStateOf(false) }

                // v1.21 item 3a: the destination is always on show, toggle on or off.
                Text("1 · Account & Calendars To Read", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // v1.49: selection is an ACCOUNT plus a set of its calendars (empty = all).
                    val acct = settings.calendarAccount
                    Text(
                        if (acct != null)
                            "→ $acct · " + (if (settings.calendarNone) "none selected" else if (settings.calendarIds.isEmpty()) "all calendars" else "${settings.calendarIds.size} calendar(s)")
                        else "No account chosen yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (acct != null) PillTextIdle else InkHint,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (CalSync.hasReadPerm(context)) { CalSync.migrateSelectionIfNeeded(context); showCalPick = true }
                        else calReq()
                    }) { Text(if (acct != null) "Change" else "Choose") }
                }
                if (showCalPick) {
                    // v1.49: TWO-STEP picker. Step 1 lists ACCOUNT NAMES only; step 2 lists that
                    // account's calendars with all selected by default. Body is scrollable — the
                    // old plain Column made long lists unreachable.
                    var reloadTick by remember { mutableStateOf(0) }
                    val cals = remember(reloadTick, showCalPick) { CalSync.readableCalendars(context) }
                    val accounts = calendarAccountsOf(cals)
                    var step2Account by remember { mutableStateOf(settings.calendarAccount) }
                    AlertDialog(
                        onDismissRequest = { showCalPick = false },
                        title = {
                            Text(
                                if (step2Account == null) "Choose Account" else "Calendars in ${step2Account}",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                if (cals.isEmpty()) Text("No calendars found on this phone.")
                                Text(
                                    "${cals.size} calendars across ${accounts.size} account(s).",
                                    style = MaterialTheme.typography.labelSmall, color = InkSubtle
                                )
                                Text(
                                    "Missing an account? Android only shows accounts that have Calendar sync switched ON in Settings \u2192 Accounts.",
                                    style = MaterialTheme.typography.labelSmall, color = InkHint
                                )
                                TextButton(onClick = { reloadTick++ }) { Text("Refresh list") }

                                val acct = step2Account
                                if (acct == null) {
                                    // ---- step 1: account names only ----
                                    accounts.forEach { a ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                                            .fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                // Picking an account selects ALL its calendars (default-all).
                                                SettingsStore.update { it.copy(calendarAccount = a, calendarIds = emptySet(), calendarNone = false) }
                                                step2Account = a
                                            }.padding(vertical = 10.dp)
                                        ) {
                                            Text(a, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                            if (a == settings.calendarAccount) Text("current", style = MaterialTheme.typography.labelSmall, color = SettingsAccent)
                                        }
                                    }
                                } else {
                                    // ---- step 2: that account's calendars, all ticked by default ----
                                    val inAcct = cals.filter { it.account == acct }
                                    val selected = settings.calendarIds
                                    val noneMode = settings.calendarNone
                                    Text(
                                        when {
                                            noneMode -> "No calendars selected."
                                            selected.isEmpty() -> "All calendars selected."
                                            else -> "${selected.size} of ${inAcct.size} selected."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (noneMode) InkHint else ShopInk
                                    )
                                    // v1.50: empty set means ALL, so "Select None" needs its own flag —
                                    // otherwise deselecting everything would select everything.
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = {
                                            SettingsStore.update { it.copy(calendarIds = emptySet(), calendarCloudIds = emptySet(), calendarNone = false) }
                                        }) { Text("Select All") }
                                        TextButton(onClick = {
                                            SettingsStore.update { it.copy(calendarIds = emptySet(), calendarCloudIds = emptySet(), calendarNone = true) }
                                        }) { Text("Select None") }
                                    }
                                    inAcct.forEach { ci ->
                                        val isOn = !noneMode && (selected.isEmpty() || ci.id in selected)
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                                            .fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                val current = when {
                                                    noneMode -> emptySet()
                                                    selected.isEmpty() -> inAcct.map { c -> c.id }.toSet()
                                                    else -> selected
                                                }
                                                val next = if (isOn) current - ci.id else current + ci.id
                                                // Everything ticked is stored as "empty = all"; nothing
                                                // ticked sets the explicit None flag.
                                                SettingsStore.update {
                                                    when {
                                                        next.isEmpty() -> it.copy(calendarIds = emptySet(), calendarNone = true)
                                                        next.size == inAcct.size -> it.copy(calendarIds = emptySet(), calendarNone = false)
                                                        else -> it.copy(calendarIds = next, calendarNone = false)
                                                    }
                                                }
                                            }.padding(vertical = 6.dp)
                                        ) {
                                            Checkbox(checked = isOn, onCheckedChange = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(ci.name, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                    // v1.57: Plan-B narrowing. These CLOUD calendars are what the
                                    // direct Google read uses; the rows above only affect the
                                    // offline provider fallback. Empty = all.
                                    var cloudCals by remember(acct) { mutableStateOf<List<Pair<String, String>>?>(null) }
                                    LaunchedEffect(acct) {
                                        cloudCals = withContext(Dispatchers.IO) { CalCloud.listCalendars(context) }
                                    }
                                    Spacer(Modifier.padding(4.dp))
                                    Text("Cloud Calendars (direct Google read)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    when (val cc = cloudCals) {
                                        null -> Text("Checking Google\u2026", style = MaterialTheme.typography.labelSmall, color = InkHint)
                                        else -> if (cc.isEmpty()) Text(
                                            "Cloud list unavailable (sign-in, scope or console) — the direct read uses ALL calendars until this works.",
                                            style = MaterialTheme.typography.labelSmall, color = InkHint
                                        ) else {
                                            val cSel = settings.calendarCloudIds
                                            cc.forEach { (cid, cname) ->
                                                val onC = !noneMode && (cSel.isEmpty() || cid in cSel)
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            val cur = when {
                                                                noneMode -> emptySet()
                                                                cSel.isEmpty() -> cc.map { it.first }.toSet()
                                                                else -> cSel
                                                            }
                                                            val next = if (onC) cur - cid else cur + cid
                                                            SettingsStore.update {
                                                                when {
                                                                    next.isEmpty() -> it.copy(calendarCloudIds = emptySet(), calendarNone = true)
                                                                    next.size == cc.size -> it.copy(calendarCloudIds = emptySet(), calendarNone = false)
                                                                    else -> it.copy(calendarCloudIds = next, calendarNone = false)
                                                                }
                                                            }
                                                        }.padding(vertical = 6.dp)
                                                ) {
                                                    Checkbox(checked = onC, onCheckedChange = null)
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(cname, style = MaterialTheme.typography.bodyLarge)
                                                }
                                            }
                                        }
                                    }
                                    TextButton(onClick = { step2Account = null }) { Text("\u2190 Change account") }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showCalPick = false }) { Text("Done") } }
                    )
                }

                // v1.46 Feature 4a: the WRITE path is disabled and hidden. Everything inside this
                // gate is intact — flipping CalSync.WRITE_ENABLED back to true restores the UI.
                if (CalSync.WRITE_ENABLED) {
                // v1.21 item 3c: tabs come second, and only once a calendar exists.
                Spacer(Modifier.padding(3.dp))
                Text("2 · What Syncs", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (chosenId <= 0L) Text(
                    "Choose a calendar first.",
                    style = MaterialTheme.typography.labelSmall, color = InkHint
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data class C(val label: String, val on: Boolean, val flip: () -> Unit)
                    val allOn = settings.calSyncTasks && settings.calSyncShop &&
                        settings.calSyncLearn && settings.calSyncCalls
                    listOf(
                        C("All Tabs", allOn) {
                            val now = !allOn
                            SettingsStore.update {
                                it.copy(calSyncTasks = now, calSyncShop = now, calSyncLearn = now, calSyncCalls = now)
                            }
                            if (settings.calendarSync) { if (now) CalSync.backfill(context) else CalSync.teardown(context) }
                        },
                        C("Tasks", settings.calSyncTasks) {
                            val now = !settings.calSyncTasks
                            SettingsStore.update { it.copy(calSyncTasks = now) }
                            if (settings.calendarSync) { if (now) CalSync.backfillTab(context, Tab.TASKS) else CalSync.teardownTab(context, Tab.TASKS) }
                        },
                        C("Shop", settings.calSyncShop) {
                            val now = !settings.calSyncShop
                            SettingsStore.update { it.copy(calSyncShop = now) }
                            if (settings.calendarSync) { if (now) CalSync.backfillTab(context, Tab.SHOP) else CalSync.teardownTab(context, Tab.SHOP) }
                        },
                        C("Learn", settings.calSyncLearn) {
                            val now = !settings.calSyncLearn
                            SettingsStore.update { it.copy(calSyncLearn = now) }
                            if (settings.calendarSync) { if (now) CalSync.backfillTab(context, Tab.LEARN) else CalSync.teardownTab(context, Tab.LEARN) }
                        },
                        C("Calls", settings.calSyncCalls) {
                            val now = !settings.calSyncCalls
                            SettingsStore.update { it.copy(calSyncCalls = now) }
                            if (settings.calendarSync) { if (now) CalSync.backfillCalls(context) else CalSync.teardownCalls(context) }
                        }
                    ).forEach { ch ->
                        val enabled = chosenId > 0L
                        Text(
                            ch.label,
                            color = if (!enabled) InkFaint else if (ch.on) Color.White else InkStrong,
                            fontWeight = if (ch.on && enabled) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (ch.on && enabled) SettingsAccent else SurfaceSubtle)
                                .then(if (enabled) Modifier.clickable { ch.flip() } else Modifier)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Text(
                    "Calls sync covers scheduled and recurring calls — missed calls have no date, so they never sync.",
                    style = MaterialTheme.typography.labelSmall, color = InkHint
                )
                }

                // v1.45 Feature 4c: read-only import window.
                Spacer(Modifier.padding(3.dp))
                Text("2 · How Far Ahead To Read", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "The Tasks tab's \"From Calendar\" sort shows the chosen calendar's events for the next N days. Remindly only reads — it never creates, edits or deletes calendar events.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle
                )
                run {
                    ChoiceChips(
                        options = listOf("Days", "Months"),
                        selected = if (settings.calendarReadUnit == "MONTHS") 1 else 0,
                        accent = SettingsAccent
                    ) { i ->
                        SettingsStore.update { it.copy(calendarReadUnit = listOf("DAYS", "MONTHS")[i]) }
                    }
                    if (settings.calendarReadUnit == "MONTHS") {
                        val mOpts = listOf(3, 6, 12, 18, 24)
                        ChoiceChips(
                            options = mOpts.map { "$it mo" },
                            selected = mOpts.indexOf(settings.calendarReadMonths).coerceAtLeast(0),
                            accent = SettingsAccent
                        ) { i -> SettingsStore.update { it.copy(calendarReadMonths = mOpts[i]) } }
                    } else {
                        val dOpts = listOf(3, 7, 14, 30, 60)
                        ChoiceChips(
                            options = dOpts.map { "$it days" },
                            selected = dOpts.indexOf(settings.calendarReadDays).coerceAtLeast(0),
                            accent = SettingsAccent
                        ) { i -> SettingsStore.update { it.copy(calendarReadDays = dOpts[i]) } }
                    }
                }

                // v1.21 item 3c: the switch unlocks last, once both steps are done.
                if (CalSync.WRITE_ENABLED) {
                Spacer(Modifier.padding(3.dp))
                val canSync = chosenId > 0L && anyTab
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("3 · Sync", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (canSync) Color.Unspecified else InkHint)
                        Text(
                            if (!canSync) (if (chosenId <= 0L) "Choose a calendar above to enable." else "Tick at least one tab to enable.")
                            else "Writes the ticked tabs into the chosen calendar. Turning it off removes every event Remindly created — anything it did not create is left alone.",
                            style = MaterialTheme.typography.bodySmall, color = InkSubtle
                        )
                    }
                    Switch(checked = settings.calendarSync, enabled = canSync, onCheckedChange = { on ->
                        if (on) {
                            if (CalSync.hasPerms(context)) {
                                SettingsStore.update { it.copy(calendarSync = true) }
                                CalSync.backfill(context)
                            } else calReq()
                        } else {
                            CalSync.teardown(context)
                            SettingsStore.update { it.copy(calendarSync = false) }
                        }
                    })
                }
                }
            }

            // -------------------------------------------------- app details
            if (vis("google")) SettingsSection("Google Account", { Icon(Icons.Filled.CloudSync, null, tint = SettingsAccent) },
                expanded = openKey == "google", onToggle = { toggleKey("google") }) {
                Spacer(Modifier.padding(3.dp))
                if (googleEmail == null) {
                    Text(
                        "Sign in to back up your data into your Google Drive's private app folder, restore it on a new phone, and unlock Forgot-PIN recovery. Nothing is shared — the backup is visible only to Remindly.",
                        style = MaterialTheme.typography.bodySmall, color = InkSubtle
                    )
                    Button(
                        onClick = { signInLauncher.launch(GoogleSync.signInIntent(context)) },
                        colors = ButtonDefaults.buttonColors(containerColor = SettingsAccent)
                    ) {
                        Icon(Icons.Filled.AccountCircle, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with Google", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "One-time setup needed first: follow GOOGLE-SETUP.md (free, ~10 minutes). Until then sign-in will refuse politely.",
                        style = MaterialTheme.typography.labelSmall, color = InkHint
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AccountCircle, null, tint = SettingsAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(googleEmail ?: "", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.padding(3.dp))
                    GestureSwitchRow(
                        "Cloud sync (beta)",
                        "Keep Data & Settings live-synced across your devices on this account. Personal PIN and permissions stay on each device.",
                        settings.cloudSync
                    ) { on ->
                        SettingsStore.update { it.copy(cloudSync = on) }
                        if (on) SyncRepo.startIfEnabled(context) else SyncRepo.stop()
                    }
                    Text(
                        "For a manual offline copy, use Backup & Restore below (export/import a file).",
                        style = MaterialTheme.typography.labelSmall, color = InkHint
                    )
                    TextButton(onClick = {
                        SyncAuth.signOut(context)
                        GoogleSync.signOut(context)
                        googleEmail = null
                        googleMsg = "Signed out."
                    }) { Text("Sign out", color = OverdueRed, fontWeight = FontWeight.Bold) }
                }
                googleMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("✓") || it.startsWith("Signed in")) ShopInk else OverdueRed)
                }
            }

            if (vis("details")) SettingsSection("App Details", { Icon(Icons.Filled.Info, null, tint = sectionTint("details")) },
                expanded = openKey == "about", onToggle = { toggleKey("about") }) {
                val para = MaterialTheme.typography.bodyMedium.copy(textIndent = TextIndent(firstLine = 18.sp))
                Text(
                    "Tasks ring at their due time with a full alarm or a notification, keep nagging at your chosen " +
                            "frequency while overdue, and offer snooze right on the alarm screen.",
                    style = para
                )
                Text(
                    "Shop rings by location: save your home and favourite shops, and the pending list pops up when " +
                            "you leave home or arrive at the market, with defer options from 5 minutes to 3 hours. " +
                            "Items with a lapse return automatically after the days or months you set, expiry dates " +
                            "warn you at 9 AM that day, and Personal items hide behind your 4-digit PIN — with a lock " +
                            "button in the header and auto-lock whenever you leave the tab.",
                    style = para
                )
                Text(
                    "Learn holds courses and videos with their platform and a tappable link, and Tasks, Shop and " +
                            "Learn can all regroup their lists by your own predefined groups or topics — the lists " +
                            "themselves live in Settings → Groups, with add, rename and safe delete.",
                    style = para
                )
                Text(
                    "Calls quietly logs truly missed calls and reminds you once at the same time on the next working " +
                            "ladder: two hours after the miss, again at your chosen time that day, and once more on the " +
                            "next rule-set day at the original miss time — then silence. A real conversation of 10 " +
                            "seconds or more still clears everything by itself. Active cards show a call button " +
                            "beside the name; tapping reveals SMS and WhatsApp; Done cards lead with delete.",
                    style = para
                )
                Text(
                    "Everything is grouped by year, month and date, with a quick-add bar always ready at the bottom " +
                            "of every Active page. Ticking a box starts a short countdown before it moves to Done — " +
                            "or, with the delay Off, it moves instantly and a small UNDO chip appears. Swipe cards, " +
                            "blank space or the bottom bar to move around; every gesture has its own switch and its " +
                            "own direction in Gestures & navigation, and Appearance controls font sizes per element " +
                            "plus how tightly the cards pack together.",
                    style = para
                )
                Text(
                    "Your data stays on the phone, exports to a JSON file anytime, and can sync privately to your " +
                            "own Google Drive with PIN recovery once you sign in.",
                    style = para
                )
            }

            // -------------------------------------------------- about me (fixed, always open)
            if (vis("about")) AboutMeCard()
        }
    }

    if (showChangePin) SetPinDialog(requireOld = true, onDismiss = { showChangePin = false })
    if (showSetPin) SetPinDialog(requireOld = false, onDismiss = { showSetPin = false })
    if (pinResetOpen) SetPinDialog(requireOld = false, onDismiss = { pinResetOpen = false })

    pendingImport?.let { blob ->
        ConfirmDialog(
            title = "Replace everything?",
            text = "This backup" +
                    " holds ${blob.items.size} items, ${blob.places.size} places and ${(blob.calls ?: emptyList()).size} call reminders. Importing replaces all current data.",
            confirmLabel = "Import",
            onConfirm = { Backup.apply(context, blob) },
            onDismiss = { pendingImport = null }
        )
    }
    if (importError) {
        ConfirmDialog(
            title = "Not a Remindly backup",
            text = "That file couldn't be read. Pick a JSON file exported from Remindly.",
            confirmLabel = "OK",
            onConfirm = {},
            onDismiss = { importError = false }
        )
    }
}

// ================================================================ about me (standing format across all my apps)

@Composable
fun AboutMeCard() {
    var showMailKind by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // v1.15 item 21: guided email composer.
    if (showMailKind) {
        val kinds = listOf("New feature request", "Bug found", "Crash / error report", "Feedback", "Other")
        AlertDialog(
            onDismissRequest = { showMailKind = false },
            title = { Text("What's this email about?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    kinds.forEach { k ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showMailKind = false
                                    val ver = runCatching {
                                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                                    }.getOrNull() ?: "?"
                                    val subject = "[Remindly v$ver] $k"
                                    val body = buildString {
                                        append("App: Remindly v").append(ver).append("\n")
                                        append("Data schema: v").append(SettingsStore.s.value.ver).append("\n")
                                        append("Device: ").append(android.os.Build.MANUFACTURER).append(" ")
                                            .append(android.os.Build.MODEL).append("\n")
                                        append("Android: ").append(android.os.Build.VERSION.RELEASE)
                                            .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
                                        if (k.startsWith("Bug") || k.startsWith("Crash")) {
                                            val tail = Logger.readAll(context).lines().takeLast(20).joinToString("\n")
                                            if (tail.isNotBlank()) append("\nRecent error log:\n").append(tail).append("\n")
                                        }
                                        append("\n--- Describe below ---\n\n")
                                    }
                                    // v1.18 item 2: Gmail ignores EXTRAS on mailto — the subject and
                                    // body must ride inside the URI itself, URL-encoded.
                                    val mailUri = Uri.parse(
                                        "mailto:kri.subsc@gmail.com" +
                                            "?subject=" + Uri.encode(subject) +
                                            "&body=" + Uri.encode(body)
                                    )
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_SENDTO, mailUri)
                                                .putExtra(Intent.EXTRA_SUBJECT, subject) // fallback for clients that read extras
                                                .putExtra(Intent.EXTRA_TEXT, body)
                                        )
                                    }
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        ) { Text(k, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMailKind = false }) { Text("Cancel") } }
        )
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.7"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, null, tint = TasksPal.accent)
                Spacer(Modifier.width(10.dp))
                Text("About Me", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text("Krishna Dipayan Bhunia", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Suggestion / Feedback / Bugs / Error",
                style = MaterialTheme.typography.labelMedium, color = InkSubtle
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showMailKind = true }
                    .padding(vertical = 2.dp)
            ) {
                Icon(Icons.Filled.Email, null, tint = SettingsAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("kri.subsc@gmail.com", color = SettingsAccent, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/krishnabhunia/"))
                            )
                        }
                    }
                    .padding(vertical = 2.dp)
            ) {
                Icon(Icons.Filled.Link, null, tint = Color(0xFF0A66C2), modifier = Modifier.size(16.dp))   // hex-ok(Q2): LinkedIn brand
                Spacer(Modifier.width(8.dp))
                Text("LinkedIn — linkedin.com/in/krishnabhunia", color = Color(0xFF0A66C2), style = MaterialTheme.typography.bodyMedium)   // hex-ok(Q2): LinkedIn brand
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/kdbhunia/"))
                            )
                        }
                    }
                    .padding(vertical = 2.dp)
            ) {
                Icon(Icons.Filled.Link, null, tint = Color(0xFF1877F2), modifier = Modifier.size(16.dp))   // hex-ok(Q2): Facebook brand
                Spacer(Modifier.width(8.dp))
                Text("Facebook — facebook.com/kdbhunia", color = Color(0xFF1877F2), style = MaterialTheme.typography.bodyMedium)   // hex-ok(Q2): Facebook brand
            }
            Text(
                "Remindly · Version $versionName",
                style = MaterialTheme.typography.labelMedium, color = PillTextIdle, fontWeight = FontWeight.Medium
            )
            Text(
                "Released on 08-Aug-2026",
                style = MaterialTheme.typography.labelSmall, color = InkHint
            )
        }
    }
}

// ================================================================ accordion section

@Composable
private fun SettingsSection(
    title: String,
    icon: @Composable () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "sectionChevron")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Filled.ExpandMore, null, tint = InkHint, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { content() }
        }
    }
}

@Composable
private fun AddHousekeepingSection(tab: Tab, settings: AppSettings, openKey: String?, toggle: (String) -> Unit) {
    val pal = when (tab) { Tab.SHOP -> ShopPal; Tab.LEARN -> LearnPal; else -> TasksPal }
    val p = tabPrefix(tab)

    // -------- card 1: Adding items --------
    SettingsSection("Adding Items", { Icon(Icons.Filled.AddCircleOutline, null, tint = sectionTint("adding")) },
        expanded = openKey == p + "add2", onToggle = { toggle(p + "add2") }) {
        // v2.7 (N43): per-tab override for the group-header checkbox (Inherit / On / Off).
        Text("Group header checkboxes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        val gc = when (tab) { Tab.SHOP -> settings.shopGroupCheck; Tab.LEARN -> settings.learnGroupCheck; else -> settings.tasksGroupCheck }
        ChoiceChips(
            options = listOf("Inherit", "On", "Off"),
            selected = when (gc) { "ON" -> 1; "OFF" -> 2; else -> 0 },
            accent = pal.accent,
            onSelect = { i ->
                val v = listOf("INHERIT", "ON", "OFF")[i]
                SettingsStore.update { when (tab) { Tab.SHOP -> it.copy(shopGroupCheck = v); Tab.LEARN -> it.copy(learnGroupCheck = v); else -> it.copy(tasksGroupCheck = v) } }
            }
        )
        if (gc == "INHERIT") Text("Following the global switch (${if (settings.groupHeaderCheck) "On" else "Off"}).", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        Spacer(Modifier.height(8.dp))
        Text("New-Item Due Date", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        val mode = when (tab) { Tab.SHOP -> settings.shopNewDueMode; Tab.LEARN -> settings.learnNewDueMode; else -> settings.tasksNewDueMode }
        val nDays = when (tab) { Tab.SHOP -> settings.shopNewDueDays; Tab.LEARN -> settings.learnNewDueDays; else -> settings.tasksNewDueDays }
        ChoiceChips(
            options = listOf("Inherit", "Off", "Today", "Tomorrow", "In $nDays days"),
            selected = when (mode) { "INHERIT" -> 0; "OFF" -> 1; "TODAY" -> 2; "IN_N" -> 4; else -> 3 },
            accent = pal.accent
        ) { i ->
            val m = listOf("INHERIT", "OFF", "TODAY", "TOMORROW", "IN_N")[i]
            SettingsStore.update {
                when (tab) {
                    Tab.SHOP -> it.copy(shopNewDueMode = m)
                    Tab.LEARN -> it.copy(learnNewDueMode = m)
                    else -> it.copy(tasksNewDueMode = m)
                }
            }
        }
        if (mode == "INHERIT") Text(
            "Following the global default (Settings tab).",
            style = MaterialTheme.typography.labelSmall, color = InkHint
        )
        if (mode == "IN_N") {
            ChoiceChips(
                options = listOf("2", "3", "5", "7"),
                selected = when (nDays) { 3 -> 1; 5 -> 2; 7 -> 3; else -> 0 },
                accent = pal.accent
            ) { i ->
                val d = listOf(2, 3, 5, 7)[i]
                SettingsStore.update {
                    when (tab) {
                        Tab.SHOP -> it.copy(shopNewDueDays = d)
                        Tab.LEARN -> it.copy(learnNewDueDays = d)
                        else -> it.copy(tasksNewDueDays = d)
                    }
                }
            }
        }
        if (mode != "INHERIT") {
            val minsNow = when (tab) { Tab.SHOP -> settings.shopNewDueMinutes; Tab.LEARN -> settings.learnNewDueMinutes; else -> settings.tasksNewDueMinutes }
            TimeSettingRow(
                label = "New-item due time",
                subtitle = "Fresh items land at this time.",
                minutes = minsNow
            ) { m ->
                SettingsStore.update {
                    when (tab) {
                        Tab.SHOP -> it.copy(shopNewDueMinutes = m)
                        Tab.LEARN -> it.copy(learnNewDueMinutes = m)
                        else -> it.copy(tasksNewDueMinutes = m)
                    }
                }
            }
        }
        // v1.86 (N25): per-tab Default Schedule — Inherit follows the global; Shop has no
        // No-Reminders option by design (D1: a purchase always has a buy plan).
        Text("Default Schedule", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        run {
            val cur = when (tab) {
                Tab.SHOP -> settings.shopSchedDefault
                Tab.LEARN -> settings.learnSchedDefault
                else -> settings.tasksSchedDefault
            }
            val opts = if (tab == Tab.SHOP)
                listOf("Inherit", "One-time", "Repeat") else
                listOf("Inherit", "No Reminders", "One-time", "Repeat")
            val vals = if (tab == Tab.SHOP)
                listOf("INHERIT", "ONCE", "REPEAT") else
                listOf("INHERIT", "NONE", "ONCE", "REPEAT")
            ChoiceChips(
                options = opts,
                selected = vals.indexOf(cur).coerceAtLeast(0),
                accent = pal.accent
            ) { i ->
                val v = vals[i]
                SettingsStore.update {
                    when (tab) {
                        Tab.SHOP -> it.copy(shopSchedDefault = v)
                        Tab.LEARN -> it.copy(learnSchedDefault = v)
                        else -> it.copy(tasksSchedDefault = v)
                    }
                }
            }
            if (cur == "INHERIT") Text(
                "Following the global default (Settings tab).",
                style = MaterialTheme.typography.labelSmall, color = InkHint
            )
        }
        // v1.47 Feature 2c: per-tab "due time / no due time" (Inherit follows the global).
        Text("Give New Items A Due Time", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        run {
            val cur = when (tab) {
                Tab.SHOP -> settings.shopNewDueTimed
                Tab.LEARN -> settings.learnNewDueTimed
                else -> settings.tasksNewDueTimed
            }
            ChoiceChips(
                options = listOf("Inherit", "Yes", "No (date only)"),
                selected = when (cur) { "ON" -> 1; "OFF" -> 2; else -> 0 },
                accent = pal.accent
            ) { i ->
                val v = listOf("INHERIT", "ON", "OFF")[i]
                SettingsStore.update {
                    when (tab) {
                        Tab.SHOP -> it.copy(shopNewDueTimed = v)
                        Tab.LEARN -> it.copy(learnNewDueTimed = v)
                        else -> it.copy(tasksNewDueTimed = v)
                    }
                }
            }
            if (cur == "INHERIT") Text(
                "Following the global default: " + (if (settings.globalNewDueTimed) "Yes" else "No (date only)"),
                style = MaterialTheme.typography.labelSmall, color = InkHint
            )
        }
        // v1.71 (Q9): per-tab Medium-tag override.
        Spacer(Modifier.padding(3.dp))
        Text("Show The 'Medium' Tag", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        run {
            val raw = when (tab) {
                Tab.SHOP -> settings.shopShowMediumTag
                Tab.LEARN -> settings.learnShowMediumTag
                else -> settings.tasksShowMediumTag
            }
            val vals = listOf(-1, 1, 0)
            ChoiceChips(
                options = listOf("Inherit", "Show", "Hide"),
                selected = vals.indexOf(raw).coerceAtLeast(0),
                accent = pal.accent
            ) { i ->
                val v = vals[i]
                SettingsStore.update {
                    when (tab) {
                        Tab.SHOP -> it.copy(shopShowMediumTag = v)
                        Tab.LEARN -> it.copy(learnShowMediumTag = v)
                        else -> it.copy(tasksShowMediumTag = v)
                    }
                }
            }
            if (raw != 1 && raw != 0) Text(
                "Following the global default: " + (if (settings.showMediumTag) "Show" else "Hide"),
                style = MaterialTheme.typography.labelSmall, color = InkHint
            )
        }
    }

    // -------- card 2: Housekeeping --------
    SettingsSection("Housekeeping", { Icon(Icons.Filled.Timer, null, tint = sectionTint("housekeeping")) },
        expanded = openKey == p + "hk2", onToggle = { toggle(p + "hk2") }) {
        Spacer(Modifier.padding(3.dp))
        Text("Auto-Clear Done Items", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Older Done items vanish for good — Shop items still carrying a future lapse-return or expiry are kept.",
            style = MaterialTheme.typography.bodySmall, color = InkSubtle
        )
        ClearChips(tab, settings, pal.accent)
        Spacer(Modifier.padding(3.dp))
        DelayChips(tab, settings, pal.accent)
    }

    // -------- card 3: Alerts --------
    SettingsSection("Alerts", { Icon(Icons.Filled.NotificationsActive, null, tint = sectionTint("alerts")) },
        expanded = openKey == p + "al2", onToggle = { toggle(p + "al2") }) {
        TabAlertsOnOff(tab, settings, pal.accent)
        Spacer(Modifier.padding(3.dp))
    }

    // -------- card 4: Gestures --------
    SettingsSection("Gestures", { Icon(Icons.Filled.Swipe, null, tint = sectionTint("gestures")) },
        expanded = openKey == p + "ge2", onToggle = { toggle(p + "ge2") }) {
        TabGesturesBlock(tab, settings, pal.accent)
    }

    // -------- card 5: Card Layout (v1.44 UI 1) --------
    SettingsSection("Card Layout", { Icon(Icons.Filled.ViewAgenda, null, tint = sectionTint("cardlayout")) },
        expanded = openKey == p + "cl2", onToggle = { toggle(p + "cl2") }) {
        TabCardBlock(tab, settings, pal.accent)
    }
}

@Composable
private fun TabCardBlock(tab: Tab?, settings: AppSettings, accent: Color) {
    val p = tabPrefix(tab)
    val ov = settings.tabCardOv
    fun set(key: String, value: String?) = SettingsStore.update { s ->
        val m = s.tabCardOv.toMutableMap()
        if (value == null) m.remove(p + key) else m[p + key] = value
        s.copy(tabCardOv = m)
    }
    @Composable fun tri(label: String, key: String, globalDefault: Boolean) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        val cur = ov[p + key]
        ChoiceChips(
            options = listOf("Inherit", "Show", "Hide"),
            selected = when (cur) { "ON" -> 1; "OFF" -> 2; else -> 0 },
            accent = accent
        ) { i -> set(key, listOf(null, "ON", "OFF")[i]) }
        if (cur == null) Text(
            "Following the global default: " + (if (globalDefault) "Show" else "Hide"),
            style = MaterialTheme.typography.labelSmall, color = InkHint
        )
    }
    Text(
        "Inherit follows the global Card Layout; pick Show/Hide to diverge on this tab only. The item name always shows.",
        style = MaterialTheme.typography.bodySmall, color = InkSubtle
    )
    tri("Priority tag", "CardPriority", settings.cardShowPriority)
    tri("Date & time", "CardDateTime", settings.cardShowDateTime)
    tri("Checkbox", "CardCheckbox", settings.cardShowCheckbox)
    tri("Repeat tag", "CardRepeat", settings.cardShowRepeat)
    // v1.80 (N5): per-tab override for the Reminder Type chip, inheriting the global.
    tri("Reminder type tag", "CardAlertType", settings.cardShowAlertType)
}


@Composable
private fun ClearChips(tab: Tab?, settings: AppSettings, accent: Color) {
    val raw = when (tab) {
        Tab.SHOP -> settings.shopDoneClearDays; Tab.LEARN -> settings.learnDoneClearDays
        Tab.TASKS -> settings.tasksDoneClearDays; else -> settings.callsDoneClearDays
    }
    ChoiceChips(
        options = listOf("Inherit", "Off", "7 days", "30 days", "90 days"),
        selected = when (raw) { -1 -> 0; 7 -> 2; 30 -> 3; 90 -> 4; 0 -> 1; else -> 0 },
        accent = accent
    ) { i ->
        val d = listOf(-1, 0, 7, 30, 90)[i]
        SettingsStore.update {
            when (tab) {
                Tab.SHOP -> it.copy(shopDoneClearDays = d)
                Tab.LEARN -> it.copy(learnDoneClearDays = d)
                Tab.TASKS -> it.copy(tasksDoneClearDays = d)
                else -> it.copy(callsDoneClearDays = d)
            }
        }
    }
    if (raw < 0) Text(
        "Following the global default: " + (if (settings.globalDoneClearDays == 0) "Off" else "${settings.globalDoneClearDays} days"),
        style = MaterialTheme.typography.labelSmall, color = InkHint
    )
}

@Composable
private fun DelayChips(tab: Tab?, settings: AppSettings, accent: Color) {
    Text("Move-To-Done Delay", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    val raw = when (tab) {
        Tab.SHOP -> settings.shopMoveDelaySec; Tab.LEARN -> settings.learnMoveDelaySec
        Tab.TASKS -> settings.tasksMoveDelaySec; else -> settings.callsMoveDelaySec
    }
    ChoiceChips(
        options = listOf("Inherit") + DELAY_OPTIONS.map { if (it == 0) "Off" else "${it}s" },
        selected = if (raw < 0) 0 else DELAY_OPTIONS.indexOf(raw).coerceAtLeast(0) + 1,
        accent = accent
    ) { i ->
        val v = if (i == 0) -1 else DELAY_OPTIONS[i - 1]
        SettingsStore.update {
            when (tab) {
                Tab.SHOP -> it.copy(shopMoveDelaySec = v)
                Tab.LEARN -> it.copy(learnMoveDelaySec = v)
                Tab.TASKS -> it.copy(tasksMoveDelaySec = v)
                else -> it.copy(callsMoveDelaySec = v)
            }
        }
    }
    if (raw < 0) Text(
        "Following the global default: " + (if (settings.moveDelaySec == 0) "Off" else "${settings.moveDelaySec} s"),
        style = MaterialTheme.typography.labelSmall, color = InkHint
    )
}



@Composable
private fun TabGesturesBlock(tab: Tab?, settings: AppSettings, accent: Color) {
    val p = tabPrefix(tab)
    val ov = settings.tabGestureOv
    val g = gesturesFor(settings, tab)
    fun set(key: String, value: String?) = SettingsStore.update { s ->
        val m = s.tabGestureOv.toMutableMap()
        if (value == null) m.remove(p + key) else m[p + key] = value
        s.copy(tabGestureOv = m)
    }
    @Composable fun tri(label: String, key: String, onLbl: String, offLbl: String, onVal: String, offVal: String) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        val cur = ov[p + key]
        ChoiceChips(
            options = listOf("Inherit", onLbl, offLbl),
            selected = when (cur) { onVal -> 1; offVal -> 2; else -> 0 },
            accent = accent
        ) { i -> set(key, listOf(null, onVal, offVal)[i]) }
    }
    Text(
        "Inherit follows the global Gestures section; pick On/Off (or a value) to diverge on this tab only.",
        style = MaterialTheme.typography.bodySmall, color = InkSubtle
    )
    tri("Swipe cards to move items", "CardSwipe", "On", "Off", "ON", "OFF")
    if (g.cardSwipe) {
        tri("Card swipe direction", "CardRightDone", "Right → Done", "Left → Done", "ON", "OFF")
    }
    tri("Delete on reverse swipe", "RevSwipeDelete", "On", "Off", "ON", "OFF")
    tri("Swipe blank space to flip Active ↔ Done", "PageSwipe", "On", "Off", "ON", "OFF")
    if (g.pageSwipe) tri("Blank-space direction", "PageRightDone", "Right shows Done", "Right shows Active", "ON", "OFF")
    tri("Deleting cards", "DeleteStyle", "Instant with UNDO", "Ask every time", "UNDO", "CONFIRM")
}

/**
 * v2.02 (N33): "Maps & Location" — provider chips (Google default / OSM), usage vs limit, India 7×
 * toggle, limit slider capped at the free tier, default radius dropdown, and the live "Now using"
 * line. Google is disabled while the month is locked (Krishna's hard-lock rule).
 */
@Composable
private fun MapsLocationSection() {
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    val ui by UiStore.s.collectAsState()
    val nowKey = Geo.nowMonthKey()
    val usage = usageForMonth(ui.geoUsage, nowKey)
    val locked = usage.lockedMonth == nowKey
    val cap = freeCapFor(settings.indiaBilling)
    val limit = settings.geoLimit.coerceIn(0, cap)
    val mapProv = remember(settings.mapProvider, ui.geoUsage) { Geo.mapProviderNow(context) }
    val geoProv = remember(settings.mapProvider, ui.geoUsage, settings.geoLimit) { Geo.geoProviderNow() }

    Text("Provider", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        FilterChip(selected = settings.mapProvider == PROVIDER_GOOGLE, enabled = !locked,
            onClick = { SettingsStore.update { it.copy(mapProvider = PROVIDER_GOOGLE) } }, label = { Text("Google") })
        FilterChip(selected = settings.mapProvider == PROVIDER_OSM,
            onClick = { SettingsStore.update { it.copy(mapProvider = PROVIDER_OSM) } }, label = { Text("OSM") })
    }
    if (locked) {
        Text("Locked until ${nextMonthLabel(nowKey)} — limit reached (${usage.count} / $limit)", style = MaterialTheme.typography.bodyMedium,
            color = OverdueRed, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text("Google cannot be selected until the monthly reset. Map and address lookups use OSM meanwhile. Your Cloud Console quota cap is the second guard.",
            style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    } else {
        Text("Google · Geocoding this month: ${usage.count} / $limit", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
    LinearProgressIndicator(
        progress = { if (limit <= 0) 1f else (usage.count.toFloat() / limit).coerceIn(0f, 1f) },
        color = if (locked) OverdueRed else SuccessGreen, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
    Text("Resets on the 1st (Pacific midnight, same as Google). Map loads are unlimited; only address lookups count. Falls back to OSM on any Google error.",
        style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    SettingRowSwitch("India billing (7× free tier)", "Free cap ${"%,d".format(freeCapFor(true))} / month · off = ${"%,d".format(freeCapFor(false))}", settings.indiaBilling) { on ->
        SettingsStore.update { it.copy(indiaBilling = on, geoLimit = it.geoLimit.coerceIn(0, freeCapFor(on))) }
    }
    Text("Monthly limit for Google geocoding", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    Text("${"%,d".format(limit)} of ${"%,d".format(cap)} free", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    Slider(
        value = limit.toFloat(),
        onValueChange = { v -> SettingsStore.update { it.copy(geoLimit = (v / 1000f).toInt().times(1000).coerceIn(0, cap)) } },
        valueRange = 0f..cap.toFloat(), steps = (cap / 1000 - 1).coerceAtLeast(0)
    )
    Text("Range 0 – free cap; cannot exceed the free tier, so raising it never creates a charge.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    Spacer(Modifier.height(8.dp))
    RadiusDropdown(value = settings.shopNewRadius, accent = SettingsAccent, label = "Default radius for new shops") { v ->
        SettingsStore.update { it.copy(shopNewRadius = v) }
    }
    Spacer(Modifier.height(8.dp))
    val gName: (String) -> String = { if (it == PROVIDER_GOOGLE) "Google" else "OSM" }
    Text("Now using · Map: ${gName(mapProv)} · Address lookup: ${gName(geoProv)}" + (if (geoProv == PROVIDER_GOOGLE) " → OSM fallback" else ""),
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Geo.fallbackReason(context, forMap = false)?.let { r ->
        if (settings.mapProvider == PROVIDER_GOOGLE) Text("Address lookup on OSM because: $r", style = MaterialTheme.typography.bodySmall, color = AmberInk)
    }
    Geo.fallbackReason(context, forMap = true)?.let { r ->
        if (settings.mapProvider == PROVIDER_GOOGLE) Text("Map on OSM because: $r", style = MaterialTheme.typography.bodySmall, color = AmberInk)
    }
    Text("Keys live in the “API keys” card below.", style = MaterialTheme.typography.bodySmall, color = InkHint)
}

/**
 * v2.02 (N33 C1/C5/C6/C7): the API-keys deck. Level 1 = one row per key with STATUS ONLY (SET /
 * NOT SET / IN APP — never a character of any key). Level 2 = that key's controls: "Add new" when
 * not set (masked input, no eye icon), "Remove" when set. Every write = Confirm 1 + Confirm 2 with
 * a TYPED word (SAVE / Remove). Read-only keys (Maps SDK, Firebase) show status, no controls.
 */
@Composable
private fun ApiKeysSection() {
    val context = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    var geoSet by remember { mutableStateOf(KeyStore.isSet(KeyStore.GOOGLE_GEOCODING)) }
    var draft by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(0) }          // 0 idle · 1 confirm1 · 2 confirm2
    var pendingRemove by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val sdkPresent = remember { Geo.sdkKeyPresent(context) }

    @Composable
    fun keyRow(id: String, title: String, sub: String, status: String, statusColor: Color, expandable: Boolean, level2: @Composable () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
            .clickable(enabled = expandable) { open = if (open == id) null else id }.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
            }
            Text(status, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = statusColor,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
            if (expandable) Text(if (open == id) "  ▾" else "  ▸", color = InkHint)
        }
        if (open == id) Column(Modifier.padding(start = 10.dp, bottom = 6.dp)) { level2() }
        HorizontalDivider(color = InkHint.copy(alpha = 0.25f))
    }

    keyRow("geo", "Google Geocoding API key", "Address lookups · stored on this phone only · never shown",
        if (geoSet) "SET" else "NOT SET", if (geoSet) SuccessGreen else AmberInk, expandable = true) {
        if (geoSet) {
            Text("••••••••••••••••••••••••", style = MaterialTheme.typography.bodyLarge, color = InkSubtle)
            Text("Key is set. It cannot be viewed. To change it, remove it and add a new one.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
            OutlinedButton(onClick = { pendingRemove = true; stage = 1; typed = "" }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(50.dp)) {
                Icon(Icons.Filled.Delete, null, tint = OverdueRed, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text("Remove", color = OverdueRed, fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, singleLine = true,
                label = { Text("Paste key… (hidden while typing)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Button(enabled = draft.trim().length >= 20, onClick = { pendingRemove = false; stage = 1; typed = "" },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(50.dp)) { Text("Add new", fontWeight = FontWeight.Bold) }
        }
    }
    keyRow("sdk", "Google Maps SDK key", "Map picker · ships inside the app, Android-restricted",
        if (sdkPresent) "IN APP" else "NOT SET", if (sdkPresent) InkSubtle else AmberInk, expandable = true) {
        Text(if (sdkPresent) "Baked into this build (restricted to this app's package and signing key)."
             else "This build has no Maps SDK key — the map picker runs on OSM until a build carries the key.",
            style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    }
    keyRow("firebase", "Firebase (google-services)", "Sync · ships inside the app", "IN APP", InkSubtle, expandable = false) {}
    Text("Keys are never revealed — not on screen, not in logs, not in backups/exports.", style = MaterialTheme.typography.bodySmall, color = InkHint, modifier = Modifier.padding(top = 6.dp))

    val word = if (pendingRemove) "Remove" else "SAVE"
    if (stage == 1) AlertDialog(
        onDismissRequest = { stage = 0 },
        title = { Text(if (pendingRemove) "Confirm 1 of 2 — remove Google Geocoding key?" else "Confirm 1 of 2 — add Google Geocoding key?", fontWeight = FontWeight.Bold) },
        text = { Text(if (pendingRemove) "Address lookups will use OSM until a new key is added." else "The key will be stored on this phone only and used for address lookups.") },
        confirmButton = { TextButton(onClick = { stage = 2 }) { Text("Continue", fontWeight = FontWeight.Bold, color = if (pendingRemove) OverdueRed else SettingsAccent) } },
        dismissButton = { TextButton(onClick = { stage = 0 }) { Text("Cancel") } }
    )
    if (stage == 2) AlertDialog(
        onDismissRequest = { stage = 0 },
        title = { Text("Confirm 2 of 2 — type $word", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, label = { Text("Type $word to confirm") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = typed == word, onClick = {
                val ok = if (pendingRemove) KeyStore.remove(KeyStore.GOOGLE_GEOCODING) else KeyStore.set(KeyStore.GOOGLE_GEOCODING, draft)
                if (ok) {
                    geoSet = KeyStore.isSet(KeyStore.GOOGLE_GEOCODING); draft = ""
                    Logger.e(context, "KEYS", null, if (pendingRemove) "API key removed (Google Geocoding)" else "API key added (Google Geocoding)")
                    Feedback.toast(context, if (pendingRemove) "Google Geocoding key removed · using OSM for address lookups" else "Google Geocoding key added · Google active for address lookups")
                } else Feedback.toast(context, "Couldn't ${if (pendingRemove) "remove" else "save"} the key — see Error Logs")
                stage = 0; typed = ""
            }) { Text(if (pendingRemove) "Remove key" else "Add key", fontWeight = FontWeight.Bold, color = if (pendingRemove) OverdueRed else SettingsAccent) }
        },
        dismissButton = { TextButton(onClick = { stage = 0 }) { Text("Cancel") } }
    )
}

/**
 * v2.9 (N47): Updates — installed vs latest, Check now, Update (download → verify → installer),
 * the auto-check and Wi-Fi-only switches, and the one-time "install unknown apps" grant.
 */
@Composable
private fun UpdatesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.s.collectAsState()
    val ui by UiStore.s.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf<String?>(null) }
    val installed = Updater.installedName(context)
    val feed = remember(ui.updateFeedJson) { Updater.cachedFeed() }
    val newer = feed != null && updateAvailable(feed, Updater.installedCode(context))

    Text("Installed $installed" + (feed?.let { " · latest ${it.versionName}" } ?: ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(if (ui.updateLastCheck > 0L) "Last checked ${formatDateTime(ui.updateLastCheck)}" else "Not checked yet", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    if (newer && feed != null) {
        Text("Remindly ${feed.versionName} is available", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = SuccessGreen, modifier = Modifier.padding(top = 6.dp))
        if (feed.notes.isNotBlank()) Text(feed.notes, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        if (!Updater.canInstall(context)) {
            Text("Android needs a one-time permission for Remindly to install its own updates.", style = MaterialTheme.typography.bodySmall, color = AmberInk, modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = { Updater.openInstallPermission(context) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(50.dp)) { Text("Allow installing updates") }
        }
        Button(enabled = !busy, onClick = {
            busy = true; progress = 0; status = null
            scope.launch {
                val r = Updater.downloadAndInstall(context, feed) { p -> progress = p }
                busy = false
                status = when (r) {
                    is Updater.Result.Ok -> "Downloaded and verified — confirm the install on the system dialog."
                    is Updater.Result.Blocked -> r.reason
                    is Updater.Result.Failed -> r.reason
                }
            }
        }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(50.dp)) {
            Text(if (busy) "Downloading… ${progress.coerceAtLeast(0)}%" else "Update to ${feed.versionName} (${if (feed.sizeBytes > 0) "${feed.sizeBytes / 1_048_576} MB" else "APK"})", fontWeight = FontWeight.Bold)
        }
        if (busy && progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    } else if (feed != null) {
        Text("You have the latest version.", style = MaterialTheme.typography.bodyMedium, color = SuccessGreen, modifier = Modifier.padding(top = 6.dp))
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = InkSubtle, modifier = Modifier.padding(top = 4.dp)) }
    OutlinedButton(enabled = !busy, onClick = {
        busy = true; status = null
        scope.launch {
            val f = Updater.check(context, notify = false)
            busy = false
            status = if (f == null) "Couldn't reach the update feed — check your connection." else null
        }
    }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(50.dp)) { Text("Check now") }
    SettingRowSwitch("Check automatically", "Once a day, quietly; a notification when a new version exists", settings.updateAutoCheck) { on -> SettingsStore.update { it.copy(updateAutoCheck = on) } }
    SettingRowSwitch("Download on Wi-Fi only", "Mobile data is never used for the APK", settings.updateWifiOnly) { on -> SettingsStore.update { it.copy(updateWifiOnly = on) } }
    TextButton(onClick = { Updater.openReleasesPage(context) }) { Text("Open the releases page") }
}

/** v2.01 (N32): the house label+subtitle+Switch row (mirrors the badges row) for the shop-mode sections. */
@Composable
private fun SettingRowSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ResetDefaultsRow(filterKey: String?) {
    var ask by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { ask = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Icon(Icons.Filled.Refresh, null, tint = OverdueRed, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Reset these settings to defaults", color = OverdueRed, fontWeight = FontWeight.SemiBold)
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Reset to defaults?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Only preferences on this screen reset. Your items, groups, PIN, saved places and Google sign-in are untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // v2.01 (N32): scope is the pure resetSettingsFor(...) — unit-tested per page.
                    SettingsStore.update { cur -> resetSettingsFor(cur, filterKey) }
                    ask = false
                }) { Text("Reset", color = OverdueRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TimeSettingRow(label: String, subtitle: String, minutes: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                android.app.TimePickerDialog(
                    context, { _, h, m -> onChange(h * 60 + m) }, minutes / 60, minutes % 60, false
                ).show()
            }
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                formatMinutes(minutes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = SettingsAccent
            )
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    }
}

@Composable
private fun SubGroup(title: String, key: String, openSubs: Set<String>, content: @Composable ColumnScope.() -> Unit) {
    val open = key in openSubs
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceSubtle)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { UiStore.toggleSub(key) }
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null, tint = InkSubtle
            )
        }
        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun GroupListEditor(
    title: String,
    entries: List<String>,
    inUseCount: (String) -> Int,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit
) {
    var addOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var nameText by remember { mutableStateOf("") }
    var blockedMsg by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { nameText = ""; addOpen = true }) {
                Text("+ Add", fontWeight = FontWeight.Bold, color = SettingsAccent)
            }
        }
        if (entries.isEmpty()) {
            Text("Nothing yet — add your first one.", style = MaterialTheme.typography.bodySmall, color = InkHint)
        }
        entries.sorted().forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { renameTarget = g; nameText = g }) {
                    Icon(Icons.Filled.Edit, "Rename", tint = InkSubtle, modifier = Modifier.size(17.dp))
                }
                IconButton(onClick = {
                    val n = inUseCount(g)
                    if (n > 0) blockedMsg = "\"$g\" is in use by $n item(s) — change those items first."
                    else onDelete(g)
                }) {
                    Icon(Icons.Filled.Delete, "Delete", tint = OverdueRed, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
    if (addOpen || renameTarget != null) {
        AlertDialog(
            onDismissRequest = { addOpen = false; renameTarget = null },
            title = { Text(if (addOpen) "New name" else "Rename", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(value = nameText, onValueChange = { nameText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = nameText.trim()
                    if (v.isNotBlank()) {
                        if (addOpen) onAdd(v) else renameTarget?.let { o -> if (o != v) onRename(o, v) }
                    }
                    addOpen = false; renameTarget = null
                }) { Text(if (addOpen) "Add" else "Rename", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { addOpen = false; renameTarget = null }) { Text("Cancel") } }
        )
    }
    blockedMsg?.let {
        AlertDialog(
            onDismissRequest = { blockedMsg = null },
            title = { Text("Can't delete", fontWeight = FontWeight.Bold) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { blockedMsg = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun FsRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text(
            "$label — ${"%.0f".format(value * 100)}%",
            style = MaterialTheme.typography.labelLarge
        )
        Slider(
            value = value,
            onValueChange = { v -> onChange((v / 0.05f).roundToInt() * 0.05f) },
            onValueChangeFinished = { SettingsStore.persistNow() },
            valueRange = 0.85f..1.4f,
            steps = 10
        )
    }
}

@Composable
private fun GestureSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- v1.56 alerts phase 1

@Composable
private fun TabAlertsOnOff(tab: Tab?, settings: AppSettings, accent: Color) {
    Text("Alerts On This Tab", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    val cur = when (tab) {
        Tab.SHOP -> settings.shopAlertsOn; Tab.LEARN -> settings.learnAlertsOn
        Tab.TASKS -> settings.tasksAlertsOn; else -> settings.callsAlertsOn
    }
    ChoiceChips(listOf("Inherit", "On", "Off"), when (cur) { "ON" -> 1; "OFF" -> 2; else -> 0 }, accent) { i ->
        val v = listOf("INHERIT", "ON", "OFF")[i]
        SettingsStore.update {
            when (tab) {
                Tab.SHOP -> it.copy(shopAlertsOn = v); Tab.LEARN -> it.copy(learnAlertsOn = v)
                Tab.TASKS -> it.copy(tasksAlertsOn = v); else -> it.copy(callsAlertsOn = v)
            }
        }
    }
    if (cur == "INHERIT") Text(
        "Following the global master: " + (if (settings.alertsEnabled) "On" else "Off"),
        style = MaterialTheme.typography.labelSmall, color = InkHint
    )
    Spacer(Modifier.padding(3.dp))
}


@Composable
private fun TypePill(label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (on) accent else PillBgIdle)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (on) Color.White else PillTextIdle,
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * v1.62: display + dialog duration setting. The row shows the TRUE stored value; the dialog edits
 * with explicit Save/Cancel. Min + sec fields replace the old unit toggle — flipping units cannot
 * be done without silent rounding or silent meaning-change, and two fields express any value exactly.
 */
@Composable
private fun DurationSettingRow(label: String, subtitle: String, seconds: Int, onSet: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
            Text(
                ringDurationLabel(seconds),
                style = MaterialTheme.typography.bodyLarge,
                color = SettingsAccent, fontWeight = FontWeight.SemiBold
            )
        }
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Edit, "Edit $label", tint = InkSubtle)
        }
    }
    if (open) {
        var until by remember(seconds) { mutableStateOf(seconds <= 0) }
        var minTxt by remember(seconds) { mutableStateOf(if (seconds > 0) (seconds / 60).toString() else "") }
        var secTxt by remember(seconds) { mutableStateOf(if (seconds > 0) (seconds % 60).toString() else "") }
        val parsed = minSecToSeconds(minTxt, secTxt)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Until dismissed", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = until, onCheckedChange = { until = it })
                    }
                    if (!until) {
                        Spacer(Modifier.padding(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(minTxt, { minTxt = it.filter { c -> c.isDigit() }.take(2) },
                                modifier = Modifier.width(84.dp), singleLine = true, label = { Text("min") })
                            OutlinedTextField(secTxt, { secTxt = it.filter { c -> c.isDigit() }.take(2) },
                                modifier = Modifier.width(84.dp), singleLine = true, label = { Text("sec") })
                        }
                        Text(
                            if (parsed == null) "Enter 5 s to 30 min (sec 0–59)."
                            else "Sound stops after " + ringDurationLabel(parsed) + "; the card stays until you act.",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (parsed == null) DangerInk else InkHint
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = until || parsed != null,
                    onClick = { onSet(if (until) 0 else parsed!!); open = false }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        )
    }
}

