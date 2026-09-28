package com.krishna.remindly

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * v2.10 (N9) — every permission optional, asked in context, explained.
 *
 * The rules live in Model.kt (resolvePermState / permAction / feature modes, RUNTIME_PERMISSIONS);
 * this file is the Android side: reading the OS, remembering what was asked, logging refusals, and
 * the three UI pieces every feature shares — the one-time pre-prompt, the in-context note, and the
 * Settings → Permissions page. Nothing here throws: every OS call is guarded and logged.
 */
object Perms {
    private const val TAG = "PERM"

    fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    fun isGranted(context: Context, key: String): Boolean = runCatching {
        val info = permInfo(key)
        if (Build.VERSION.SDK_INT < info.minSdk) return true
        info.checkPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }.getOrDefault(false)

    private fun canAskAgain(context: Context, key: String): Boolean = runCatching {
        val act = context.findActivity() ?: return false
        permsToRequest(permInfo(key), Build.VERSION.SDK_INT).any { ActivityCompat.shouldShowRequestPermissionRationale(act, it) }
    }.getOrDefault(false)

    fun state(context: Context, key: String): PermState =
        resolvePermState(isGranted(context, key), key in UiStore.s.value.permAsked, canAskAgain(context, key))

    fun prePromptSeen(key: String): Boolean = key in UiStore.s.value.permPrompted

    fun action(context: Context, key: String): PermAction = permAction(state(context, key), prePromptSeen(key))

    fun markAsked(key: String) = runCatching { UiStore.update { it.copy(permAsked = it.permAsked + key) } }
    fun markPrompted(key: String) = runCatching { UiStore.update { it.copy(permPrompted = it.permPrompted + key) } }

    /**
     * Anything granted right now counts as asked — so an install upgraded from before 2.10, or a
     * permission granted from system settings, reads as REVOKED (banner) if it is later taken away,
     * never as NOT_ASKED (pre-prompt). Called on start and resume.
     */
    fun syncGranted(context: Context) = runCatching {
        val granted = RUNTIME_PERMISSIONS.map { it.key }.filter { isGranted(context, it) }.toSet()
        if (!UiStore.s.value.permAsked.containsAll(granted)) UiStore.update { it.copy(permAsked = it.permAsked + granted) }
    }.onFailure { Logger.e(context, TAG, it, "syncGranted failed") }

    /** System dialog came back. Records the ask; a refusal is logged so "it stopped working" is answerable. */
    fun onResult(context: Context, key: String, result: Map<String, Boolean>) {
        markAsked(key)
        val ok = isGranted(context, key)
        runCatching {
            if (ok) Logger.e(context, TAG, null, "$key granted")
            else Logger.e(context, TAG, null, "$key refused (${result.filterValues { !it }.keys.joinToString { it.substringAfterLast('.') }}) → ${state(context, key)} — feature stays off with its note")
        }
        runCatching { Degrades.refresh(context) }
    }

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Logger.e(context, TAG, it, "open app settings failed") }
    }
}

/** Re-reads permission state whenever the screen resumes (the user may have come back from settings). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

/**
 * The one requester every feature uses. Returns a function to call from "Allow"-type buttons:
 *  GRANTED → [onResult](true) at once · first time → the pre-prompt (WHY, then the system dialog)
 *  · refused before → the system dialog · Android will no longer ask → a sheet offering system settings.
 */
@Composable
fun rememberPermRequest(key: String, onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val cb by rememberUpdatedState(onResult)
    var sheet by remember { mutableStateOf<PermAction?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        Perms.onResult(context, key, res)
        cb(Perms.isGranted(context, key))
    }
    fun system() {
        val perms = permsToRequest(permInfo(key), Build.VERSION.SDK_INT)
        if (perms.isEmpty()) { cb(true); return }
        runCatching { launcher.launch(perms.toTypedArray()) }
            .onFailure { Logger.e(context, "PERM", it, "$key request failed to launch") }
    }
    sheet?.let { a ->
        PermPrePromptSheet(
            info = permInfo(key), blocked = a == PermAction.OPEN_SETTINGS,
            onContinue = {
                sheet = null
                if (a == PermAction.OPEN_SETTINGS) Perms.openAppSettings(context)
                else { Perms.markPrompted(key); system() }
            },
            onNotNow = {
                sheet = null
                Perms.markPrompted(key)
                runCatching { Logger.e(context, "PERM", null, "$key declined at the pre-prompt — not asked") }
                cb(false)
            }
        )
    }
    return {
        when (val a = Perms.action(context, key)) {
            PermAction.NONE -> cb(true)
            PermAction.SYSTEM_DIALOG -> system()
            PermAction.PRE_PROMPT, PermAction.OPEN_SETTINGS -> sheet = a
        }
    }
}

/** The WHY, shown before the system dialog — or, once Android stops asking, the way to settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermPrePromptSheet(info: PermInfo, blocked: Boolean, onContinue: () -> Unit, onNotNow: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onNotNow, sheetState = state, containerColor = SurfaceCard, windowInsets = sheetWindowInsets()) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("What it's for", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = SuccessGreen)
            Text(info.enables, style = MaterialTheme.typography.bodyMedium)
            Text("If you say no", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AmberInk)
            Text(info.withoutIt, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (blocked) "Android won't show its permission question again, so this opens Remindly's page in Android settings → Permissions."
                else "Everything else in Remindly works either way. You can change this any time in Settings → Permissions.",
                style = MaterialTheme.typography.bodySmall, color = InkSubtle
            )
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (blocked) "Open Android settings" else "Continue", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Not now") }
            SheetBottomSpace()   // standing rule: 25% clearance on every bottom sheet
        }
    }
}

/** A one-line in-context note: what is off and a way to turn it on — never a dead screen. */
@Composable
fun PermNote(text: String, action: String?, accent: Color, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LearnSoft).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Info, null, tint = AmberInk, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = InkPrimary, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = accent, fontWeight = FontWeight.Bold) }
    }
}

/** Settings → Permissions: every permission, its true state, what it enables, and a way to change it. */
@Composable
fun PermissionsSection() {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val ui by UiStore.s.collectAsState()
    Text("Remindly asks for each permission only when you first use the feature that needs it. Everything is optional except notifications, which a reminder app can't do without.",
        style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    RUNTIME_PERMISSIONS.filter { Build.VERSION.SDK_INT >= it.minSdk }.forEach { info ->
        val st = remember(tick, ui.permAsked) { Perms.state(context, info.key) }
        val request = rememberPermRequest(info.key)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(permStateLabel(st), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = if (st == PermState.GRANTED) SuccessGreen else AmberInk)
            }
            permButtonLabel(st)?.let { lbl ->
                // BG location is only meaningful once location itself is allowed.
                val needsLoc = info.key == PermKeys.BG_LOCATION && !Perms.isGranted(context, PermKeys.LOCATION)
                TextButton(enabled = !needsLoc, onClick = request) { Text(lbl, fontWeight = FontWeight.Bold) }
            }
        }
        Text(info.enables, style = MaterialTheme.typography.bodySmall)
        if (st != PermState.GRANTED) Text("Without it: " + info.withoutIt, style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    }
    // Special access — not runtime permissions, each lives on its own Android screen.
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text("Special access", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    val exactOk = remember(tick) {
        Build.VERSION.SDK_INT < 31 || runCatching { context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true }.getOrDefault(false)
    }
    val batteryOk = remember(tick) {
        runCatching { context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false)
    }
    val installOk = remember(tick) { Updater.canInstall(context) }
    SpecialRow("Exact alarms", "Reminders ring at the exact minute.", exactOk) {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Logger.e(context, "PERM", it, "exact alarm settings failed"); Perms.openAppSettings(context) }
    }
    SpecialRow("Battery — unrestricted", "Stops the phone from delaying alarms and geofences to save battery.", batteryOk) {
        runCatching {
            @Suppress("BatteryLife")
            context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Logger.e(context, "PERM", it, "battery settings failed"); Perms.openAppSettings(context) }
    }
    SpecialRow("Install updates", "Lets Settings → Updates install new versions of Remindly.", installOk) { Updater.openInstallPermission(context) }
    Spacer(Modifier.height(4.dp))
    OutlinedButton(onClick = { Perms.openAppSettings(context) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(50.dp)) {
        Text("Open Remindly in Android settings")
    }
}

@Composable
private fun SpecialRow(title: String, what: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(if (ok) "Allowed" else "Off", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (ok) SuccessGreen else AmberInk)
            Text(what, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok) TextButton(onClick = onFix) { Text("Open settings", fontWeight = FontWeight.Bold) }
    }
}

/**
 * v2.10 (N9): the geofence note, for any screen where a geofence exists but can't fire as expected.
 * Renders nothing when location is fully allowed. Location first, then "all the time".
 */
@Composable
fun GeofencePermNote(accent: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val ui by UiStore.s.collectAsState()
    val mode = remember(tick, ui.permAsked) {
        geofenceMode(Perms.state(context, PermKeys.LOCATION), Perms.state(context, PermKeys.BG_LOCATION), Build.VERSION.SDK_INT)
    }
    val bgReq = rememberPermRequest(PermKeys.BG_LOCATION) { Geofencer.registerAll(context) }
    val locReq = rememberPermRequest(PermKeys.LOCATION) { ok ->
        if (ok && Build.VERSION.SDK_INT >= 29 && !Geofencer.hasBackgroundLocation(context)) bgReq()
        Geofencer.registerAll(context)
    }
    geofenceNote(mode)?.let { note ->
        PermNote(note, if (mode == GeoMode.OFF) "Allow" else "Allow all the time", accent,
            onAction = { if (mode == GeoMode.OFF) locReq() else bgReq() }, modifier = modifier)
    }
}
