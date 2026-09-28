package com.krishna.remindly

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/* v1.87 (N17) — the sharing surfaces, built to the delivered mockups. */

// ---------------------------------------------------------------- the header entry point

/** The "Via Shared" entry: inbox icon + red pending dot. Rendered only when sharing is on. */
@Composable
fun SharedHubButton(pendingCount: Int, tint: Color, onClick: () -> Unit) {
    Box {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.Inbox, "Shared lists", tint = tint)
        }
        if (pendingCount > 0) Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-2).dp, y = 2.dp)
                .size(9.dp)
                .background(OverdueRed, CircleShape)
        )
    }
}

// ---------------------------------------------------------------- the share sheet (sender)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareListSheet(
    tab: Tab,
    listName: String,
    items: List<Item>,
    isLocked: (Item) -> Boolean,
    pal: TabPalette,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = SurfaceCard, windowInsets = sheetWindowInsets()) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text("Share \u201c$listName\u201d", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!ShareStore.signedIn()) {
                // companion 1: a signed-out state gets a prompt, never a crash.
                Spacer(Modifier.height(10.dp))
                Text(
                    "Sharing needs your Google sign-in (Settings \u2192 Cloud sync \u2192 Sign in). " +
                        "Both sides sign in once; lists travel by exact Google email.",
                    style = MaterialTheme.typography.bodyMedium, color = InkSubtle
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Close", fontWeight = FontWeight.Bold) }
                SheetBottomSpace()   // v2.01 (N32): Option D clearance
                return@Column
            }
            var email by remember { mutableStateOf("") }
            var selected by remember { mutableStateOf(defaultShareSelection(items, isLocked)) }
            var sending by remember { mutableStateOf(false) }
            val recents = remember { ShareStore.recents(context) }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Recipient's Google email") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (recents.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recents.take(3).forEach { r ->
                        FilterChip(selected = email.trim().lowercase() == r, onClick = { email = r },
                            label = { Text(r, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Items (${selected.size} of ${items.size} \u00b7 cap $SHARE_ITEM_CAP)",
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items.forEach { i ->
                    val locked = isLocked(i)
                    FilterChip(
                        selected = i.id in selected,
                        enabled = !locked,
                        onClick = {
                            selected = if (i.id in selected) selected - i.id else selected + i.id
                        },
                        label = {
                            Text(
                                (if (locked) "\uD83D\uDD12 " else "") + i.title,
                                maxLines = 1, style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = if (locked) ({ Icon(Icons.Filled.Lock, null, Modifier.size(14.dp)) }) else null
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "A frozen copy \u2014 your later edits never flow. Prices are never shared.",
                style = MaterialTheme.typography.labelSmall, color = InkHint
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val body = shareAsText(listName, sharedItemsOf(items.filter { it.id in selected }, tab))
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, body)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share as text")) }
                        .onFailure { Logger.e(context, "SHARE", it, "share-as-text chooser failed") }
                }, modifier = Modifier.height(50.dp)) { Text("Share as text") }
                Spacer(Modifier.weight(1f))
                Button(
                    modifier = Modifier.height(50.dp),
                    enabled = !sending && plausibleEmail(email) && validShareCount(selected.size),
                    onClick = {
                        sending = true
                        val chosen = items.filter { it.id in selected }
                        ShareStore.send(context, tab, listName, email, sharedItemsOf(chosen, tab)) { ok ->
                            sending = false
                            if (ok) { Feedback.toast(context, "Shared \u201c$listName\u201d with ${email.trim()}"); onDismiss() }
                            else Feedback.toast(context, "Couldn\u2019t send \u2014 nothing was shared. Check the email and your connection, then retry.")
                        }
                    }
                ) { Text(if (sending) "Sending\u2026" else "Send") }
            }
            // v1.88 (N27): the house pattern — last child clears the Android nav buttons so
            // "Share as text" and Send are always tappable (3-button and gesture nav alike).
            SheetBottomSpace()   // v2.01 (N32): Option D clearance
        }
    }
}

// ---------------------------------------------------------------- the hub (Sent | Receive)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedHubSheet(tab: Tab, pal: TabPalette, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sentAll by ShareStore.sent.collectAsState()
    val recvAll by ShareStore.received.collectAsState()
    val blocked by ShareStore.blocked.collectAsState()
    val prefs = remember { context.getSharedPreferences("share_store", android.content.Context.MODE_PRIVATE) }
    var showSent by remember {
        mutableStateOf(prefs.getString("view_${tab.name}", "RECEIVE") == "SENT")
    }
    fun setView(sentV: Boolean) {
        showSent = sentV
        prefs.edit().putString("view_${tab.name}", if (sentV) "SENT" else "RECEIVE").apply()
    }
    var openRec by remember { mutableStateOf<ShareRecord?>(null) }
    val tabKey = if (tab == Tab.SHOP) "SHOP" else "TASKS"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = SurfaceCard, windowInsets = sheetWindowInsets()) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text("Shared \u00b7 ${tab.title}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ActiveDoneToggle(
                showDone = showSent, pal = pal, compact = true,
                labels = "Receive" to "Sent"
            ) { setView(it) }
            Spacer(Modifier.height(10.dp))

            if (!ShareStore.signedIn()) {
                Text("Sign in with Google (Settings \u2192 Cloud sync) to send and receive lists.",
                    style = MaterialTheme.typography.bodyMedium, color = InkSubtle)
                SheetBottomSpace()   // v2.01 (N32): Option D clearance
                return@Column
            }

            if (!showSent) {
                val pending = pendingReceived(recvAll, blocked).filter { it.tab == tabKey }
                val accepted = acceptedReceived(recvAll, blocked).filter { it.tab == tabKey }
                if (pending.isEmpty() && accepted.isEmpty())
                    Text("Nothing shared with you yet.", style = MaterialTheme.typography.bodyMedium, color = InkSubtle)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pending, key = { it.id }) { r -> PendingCard(r) }
                    items(accepted, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(pal.chipBg, RoundedCornerShape(12.dp))
                                .clickable { openRec = r }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.listName, fontWeight = FontWeight.Bold, color = pal.onChip)
                                Text("from ${r.fromEmail} \u00b7 ${r.items.size} items \u00b7 ${formatDateTime(r.sentAt)}",
                                    style = MaterialTheme.typography.labelSmall, color = pal.onChip)
                            }
                        }
                    }
                }
            } else {
                val mine = visibleSent(sentAll).filter { it.tab == tabKey }
                if (mine.isEmpty())
                    Text("You haven\u2019t shared any ${tab.title} lists.", style = MaterialTheme.typography.bodyMedium, color = InkSubtle)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mine, key = { it.id }) { r -> SentRow(r) }
                }
            }
            SheetBottomSpace()   // v2.01 (N32): Option D clearance
        }
    }
    openRec?.let { ReceivedListSheet(it) { openRec = null } }
}

@Composable
private fun PendingCard(r: ShareRecord) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("${r.fromEmail} shared \u201c${r.listName}\u201d", fontWeight = FontWeight.Bold)
        Text("${r.items.size} items \u00b7 ${formatDateTime(r.sentAt)}",
            style = MaterialTheme.typography.labelSmall, color = InkSubtle)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ShareStore.accept(context, r) }, modifier = Modifier.height(50.dp)) { Text("Accept") }
            OutlinedButton(onClick = { ShareStore.decline(context, r) }, modifier = Modifier.height(50.dp)) { Text("Decline") }
            TextButton(onClick = {
                ShareStore.block(context, r.fromEmail)
                Feedback.toast(context, "Blocked ${r.fromEmail} \u2014 they just see \u201cDeclined\u201d.")
            }) {
                Icon(Icons.Filled.Block, null, Modifier.size(14.dp), tint = OverdueRed)
                Spacer(Modifier.width(4.dp)); Text("Block", color = OverdueRed)
            }
        }
    }
}

@Composable
private fun SentRow(r: ShareRecord) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.listName, fontWeight = FontWeight.Bold)
            Text("to ${r.toEmail} \u00b7 ${r.items.size} items \u00b7 ${statusLabel(r.status)}",
                style = MaterialTheme.typography.labelSmall, color = InkSubtle)
        }
        if (r.status == SHARE_PENDING) TextButton(onClick = { ShareStore.revoke(context, r) }) { Text("Revoke") }
        IconButton(onClick = {
            ShareStore.senderDelete(context, r)
            if (r.status == SHARE_ACCEPTED) Feedback.toast(context, "Removed from your Sent \u2014 their copy stays.")
        }, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Filled.Delete, "Delete record", tint = OverdueRed)
        }
    }
}

fun statusLabel(s: String): String = when (s) {
    SHARE_PENDING -> "Pending"; SHARE_ACCEPTED -> "Accepted"
    SHARE_DECLINED -> "Declined"; SHARE_REVOKED -> "Revoked"; else -> s
}

// ---------------------------------------------------------------- the read-only copy

@Composable
fun ReceivedListSheet(r: ShareRecord, onClose: () -> Unit) {
    val context = LocalContext.current
    val ticksV by ShareStore.ticksVersion.collectAsState()
    val ticks = remember(ticksV, r.id) { ShareStore.ticks(context, r.id) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete my copy", color = OverdueRed) }
        },
        title = { Text(r.listName, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("from ${r.fromEmail} \u00b7 read-only \u00b7 ticks stay on this phone",
                    style = MaterialTheme.typography.labelSmall, color = InkHint)
                Spacer(Modifier.height(8.dp))
                r.items.forEachIndexed { idx, it ->
                    val ticked = idx in ticks
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { ShareStore.toggleTick(context, r.id, idx) }
                            .padding(vertical = 5.dp)
                    ) {
                        Text(
                            it.title + (listOf(it.quantity, it.unit).filter { s -> s.isNotBlank() }
                                .joinToString(" ").takeIf { s -> s.isNotBlank() }?.let { s -> "  \u00b7 $s" } ?: ""),
                            textDecoration = if (ticked) TextDecoration.LineThrough else null,
                            color = if (ticked) InkSubtle else Color.Unspecified
                        )
                        if (it.note.isNotBlank()) Text(it.note,
                            style = MaterialTheme.typography.labelSmall, color = InkSubtle,
                            textDecoration = if (ticked) TextDecoration.LineThrough else null)
                    }
                }
            }
        }
    )
    if (confirmDelete) ConfirmDialog(
        title = "Delete your copy?",
        text = "\u201c${r.listName}\u201d disappears from this account. ${r.fromEmail}\u2019s original is untouched.",
        confirmLabel = "Delete",
        onConfirm = { confirmDelete = false; ShareStore.receiverDelete(context, r); onClose() },
        onDismiss = { confirmDelete = false }
    )
}

// ---------------------------------------------------------------- Settings: blocked senders

@Composable
fun BlockedSendersBlock() {
    val context = LocalContext.current
    val blocked by ShareStore.blocked.collectAsState()
    Text("Blocked senders", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    if (blocked.isEmpty()) {
        Text("Nobody is blocked. Blocking hides a sender\u2019s shares; they just see \u201cDeclined\u201d.",
            style = MaterialTheme.typography.bodySmall, color = InkSubtle)
    } else blocked.sorted().forEach { e ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { ShareStore.unblock(context, e) }) { Text("Unblock") }
        }
    }
}
