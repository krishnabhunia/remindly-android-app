package com.krishna.remindly

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * v2.11 (N48 round 2) — sharing a Buy list as text, in Krishna's format:
 *   List_Name:-
 *
 *   1. Item - Quantity / Type - Urgent - Bought
 * S2: the Share icon opens this preview sheet (four switches, live text, WhatsApp · Share… · Copy,
 *     plus the N17 "send to a Remindly user" entry). S3: the WhatsApp icon sends straight away with
 *     the Settings defaults. Prices, shop, notes and locked Personal items never leave the phone.
 */

private const val WA = "com.whatsapp"
private const val WA_BIZ = "com.whatsapp.w4b"

/** S3: one tap → WhatsApp's own chat picker with the text filled in. Falls back to the other
 *  WhatsApp app, then to the Android share sheet (with a toast), exactly like N45. */
fun sendListToWhatsApp(context: Context, name: String, items: List<Item>, settings: AppSettings) {
    val text = listShareText(name, items, listShareOptsOf(settings))
    sendTextToWhatsApp(context, text, settings.shareWaApp)
    Logger.e(context, "SHARE", null, "list '$name' → WhatsApp (${items.count { !it.done }} open)")
}

fun sendListToWhatsApp(context: Context, list: ShopList, items: List<Item>, settings: AppSettings) =
    sendListToWhatsApp(context, list.name, items, settings)

fun sendTextToWhatsApp(context: Context, text: String, choice: String) {
    val order = if (choice == "BUSINESS") listOf(WA_BIZ, WA) else listOf(WA, WA_BIZ)
    for (pkg in order) {
        val ok = runCatching {
            context.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
    Logger.e(context, "SHARE", null, "WhatsApp not installed — falling back to the share sheet")
    Feedback.toast(context, "WhatsApp isn't installed — choose an app")
    shareTextViaChooser(context, text)
}

fun shareTextViaChooser(context: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share list").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Logger.e(context, "SHARE", it, "no app can share text")
        Feedback.toast(context, "Nothing on this phone can share text")
    }
}

/** S2: the preview sheet behind the Share icon (and long-press on the WhatsApp icon). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListTextShareSheet(name: String, items: List<Item>, pal: TabPalette, isLocked: (Item) -> Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    var includeBought by remember { mutableStateOf(settings.shareIncludeDone) }
    var urgent by remember { mutableStateOf(settings.shareUrgentTag) }
    var bought by remember { mutableStateOf(settings.shareBoughtTag) }
    var qty by remember { mutableStateOf(settings.shareIncludeQty) }
    var toRemindly by remember { mutableStateOf(false) }
    val shareable = items.filter { it.deletedAt == null && !isLocked(it) }
    val locked = items.count { it.deletedAt == null && isLocked(it) }
    val text = listShareText(name, shareable, ListShareOpts(includeBought, urgent, bought, qty, settings.shareHeadingSuffix))
    val lines = text.lines().count { it.firstOrNull()?.isDigit() == true }

    if (toRemindly) {
        // N17: a frozen copy to another Remindly user (needs Google sign-in; the sheet explains).
        ShareListSheet(tab = Tab.SHOP, listName = name, items = items.filter { !it.done && it.deletedAt == null }, isLocked = isLocked, pal = pal) {
            toRemindly = false; onDismiss()
        }
        return
    }
    EditorSheet(
        title = "Share “$name”", accent = pal.accent, onDismiss = onDismiss,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { sendTextToWhatsApp(context, text, settings.shareWaApp); onDismiss() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen)
                ) { Text("WhatsApp", color = Color.White, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { shareTextViaChooser(context, text); onDismiss() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Share…") }
                OutlinedButton(onClick = {
                    runCatching {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Remindly list", text))
                        Feedback.toast(context, "Copied")
                    }.onFailure { Logger.e(context, "SHARE", it, "copy failed") }
                    onDismiss()
                }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Copy") }
            }
        }
    ) {
        Text("$lines line${if (lines == 1) "" else "s"}" + (if (locked > 0) " · $locked locked Personal item${if (locked == 1) "" else "s"} left out" else ""),
            style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = InkPrimary,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                .background(SurfaceSubtle, RoundedCornerShape(10.dp))
                .border(1.dp, InkFaint, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .padding(10.dp)
        )
        Spacer(Modifier.height(6.dp))
        @Composable fun sw(label: String, v: Boolean, set: (Boolean) -> Unit) =
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked = v, onCheckedChange = set)
            }
        sw("Include bought items", includeBought) { includeBought = it }
        sw("Show “Urgent” tag", urgent) { urgent = it }
        sw("Show “Bought” status", bought) { bought = it }
        sw("Show quantity / type", qty) { qty = it }
        if (shareEnabledFor(Tab.SHOP, settings)) TextButton(onClick = { toRemindly = true }) {
            Text("Send to a Remindly user instead…", color = pal.accent)
        }
    }
}

/** Locked Personal items never leave the phone. */
fun visibleForShare(items: List<Item>, personalUnlocked: Boolean): List<Item> =
    items.filter { it.deletedAt == null && !(it.personal && !personalUnlocked) }
