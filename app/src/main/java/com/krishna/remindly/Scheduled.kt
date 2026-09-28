package com.krishna.remindly

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v2.7 (N42) — "Scheduled alerts": every armed alarm / ring / notification in ONE chronological
 * list, built by the pure scheduledRows() (the same truth the per-item "Coming up" box shows).
 * Amber rows are armed but UNREACHABLE (hidden tab, personal-locked, alerts off, shop deleted) —
 * the N40 case — and their row menu offers the cure right there: Show the tab / Silence that tab.
 * Read-only otherwise; the page renders, it does not decide.
 */

/** Settings → "Open scheduled alerts" → MainActivity hosts the page as a gear overlay ("SCHED"). */
object SchedNav { val open = MutableStateFlow(false) }

private fun tabOf(src: SchedSource): Tab? = when (src) {
    SchedSource.TASKS -> Tab.TASKS; SchedSource.LEARN -> Tab.LEARN; SchedSource.BUY -> Tab.SHOP; else -> null
}
private fun sourceLabel(src: SchedSource) = when (src) {
    SchedSource.TASKS -> "Tasks"; SchedSource.LEARN -> "Learn"; SchedSource.BUY -> "Buy"
    SchedSource.CALLS -> "Calls"; SchedSource.SHOP_ARRIVAL -> "Shop arrival"; SchedSource.PLACE -> "Place"
}
private fun styleIcon(style: String) = when {
    'A' in style -> "⏰"; 'R' in style -> "🔊"; 'N' in style -> "🔔"; else -> "🔕"
}
private fun flagLabel(f: SchedFlag) = when (f) {
    SchedFlag.HIDDEN_TAB -> "hidden tab"; SchedFlag.PERSONAL -> "personal 🔒"
    SchedFlag.ALERTS_OFF -> "alerts off"; SchedFlag.SHOP_DELETED -> "shop deleted"
}

@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ScheduledAlertsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsStore.s.collectAsState()
    val items by ItemStore.items.collectAsState()
    val calls by CallStore.calls.collectAsState()
    val shops by ShopStore.shops.collectAsState()
    val places by PlaceStore.places.collectAsState()
    val unlocked by Engine.personalUnlocked.collectAsState()
    val now = System.currentTimeMillis()

    var horizon by rememberSaveable { mutableStateOf(1) }            // 0 = 24 h, 1 = 7 days, 2 = all
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<SchedRow?>(null) }
    // v2.8 (N44): pending deletion (row, fullRecord) and a 6-second Undo.
    var deleting by remember { mutableStateOf<Pair<SchedRow, Boolean>?>(null) }
    var undo by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    androidx.compose.runtime.LaunchedEffect(undo) { if (undo != null) { kotlinx.coroutines.delay(6_000); undo = null } }

    val all = remember(items, calls, shops, places, settings, unlocked) {
        runCatching { scheduledRows(items, calls, shops, places, settings, now, personalLocked = !unlocked) }
            .onFailure { Logger.e(context, "SCHED", it, "scheduled rows failed") }.getOrDefault(emptyList())
    }
    val horizonMs = when (horizon) { 0 -> 24L * 3600_000L; 1 -> 7L * 24 * 3600_000L; else -> null }
    val rows = schedWithin(all, now, horizonMs).filter { source == null || it.source.name == source }
    val flagged = all.count { it.unreachable }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = "Scheduled alerts",
            subtitle = "${all.size} armed" + (if (flagged > 0) " · $flagged need attention" else " · all reachable"),
            pal = SettingsPal,
            leading = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) } }
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Next 24 h", "7 days", "All").forEachIndexed { i, l ->
                FilterChip(selected = horizon == i, onClick = { horizon = i }, label = { Text(l) })
            }
            Text("·", color = InkHint, modifier = Modifier.padding(horizontal = 2.dp))
            FilterChip(selected = source == null, onClick = { source = null }, label = { Text("All sources") })
            SchedSource.values().forEach { src ->
                FilterChip(selected = source == src.name, onClick = { source = if (source == src.name) null else src.name },
                    label = { Text(sourceLabel(src)) })
            }
        }
        undo?.let { (label, action) ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp)).background(InkPrimary).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { runCatching { action() }.onFailure { Logger.e(context, "SCHED", it, "undo failed") }; undo = null }) {
                    Text("Undo", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (all.isEmpty()) "Nothing is armed" else "Nothing in this window", fontWeight = FontWeight.Bold, color = InkPrimary)
                Text(if (flagged == 0) "Every armed alert has a card you can open 🎉" else "Widen the window to see the flagged ones.",
                    style = MaterialTheme.typography.bodySmall, color = InkSubtle)
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
            var lastDay = ""
            rows.forEach { r ->
                val day = r.at?.let { formatDayLabel(it, now) } ?: "LOCATION TRIGGERS"
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") {
                        Text(day.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = InkHint, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 2.dp))
                    }
                }
                item(key = "${r.source}-${r.itemId ?: r.callId ?: r.shopId ?: r.placeId}-${r.kind}-${r.at}") {
                    // v2.8 (N44): swipe left = "Delete this alert" (same confirm as the menu).
                    SwipeDeleteRow(onDelete = { deleting = r to false }) { SchedRowView(r, now) { menuFor = r } }
                }
            }
        }
    }

    menuFor?.let { r -> SchedRowMenu(r, onDismiss = { menuFor = null }, onDelete = { full -> menuFor = null; deleting = r to full }) }
    deleting?.let { (r, full) ->
        DeleteConfirmSheet(
            row = r, full = full, allRows = all,
            onDismiss = { deleting = null },
            onDone = { label, undoAction -> deleting = null; undo = label to undoAction }
        )
    }
}

/** v2.8 (N44): swipe left reveals Delete; the swipe never deletes by itself — the confirm does. */
@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun SwipeDeleteRow(onDelete: () -> Unit, content: @androidx.compose.runtime.Composable () -> Unit) {
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) onDelete()
            false   // always snap back; the confirm sheet owns the outcome
        }
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(OverdueRed).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Text("🗑 Delete", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    ) { content() }
}

/**
 * The confirm sheet. [full] = "Delete the item — and every future alert": the record goes to the Bin
 * and EVERY trigger tied to it (enumerated from the same rows the page shows) is cancelled.
 * Otherwise only the trigger goes (deleteActionFor). One Undo restores whichever was done.
 */
@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun DeleteConfirmSheet(row: SchedRow, full: Boolean, allRows: List<SchedRow>, onDismiss: () -> Unit, onDone: (String, () -> Unit) -> Unit) {
    val context = LocalContext.current
    val accent = when (row.source) { SchedSource.BUY, SchedSource.SHOP_ARRIVAL -> ShopPal.accent; SchedSource.CALLS -> CallPal.accent; SchedSource.LEARN -> LearnPal.accent; else -> TasksPal.accent }
    val kind = deleteActionFor(row)
    val tied = rowsForRecord(allRows, row)
    val title = if (full) when (row.source) {
        SchedSource.CALLS -> "Delete reminder “${row.title}”?"
        SchedSource.SHOP_ARRIVAL -> "Delete shop “${row.title}”?"
        SchedSource.PLACE -> "Delete place “${row.title}”?"
        else -> "Delete “${row.title}”?"
    } else "Delete this alert?"
    EditorSheet(
        title = title, accent = accent, onDismiss = onDismiss,
        actions = {
            EditorActionRow(
                accent = OverdueRed, onCancel = onDismiss,
                saveLabel = if (full) "Delete + all alerts" else "Delete alert",
                onSave = {
                    val result = runCatching { performDelete(context, row, full, kind) }
                        .onFailure { Logger.e(context, "SCHED", it, "delete failed (full=$full) for ${row.title}") }
                        .getOrNull()
                    if (result != null) {
                        Feedback.toast(context, result.first)
                        onDone(result.first, result.second)
                    } else onDismiss()
                }
            )
        }
    ) {
        Text("${sourceLabel(row.source)} · ${row.reason}", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        Spacer(Modifier.height(8.dp))
        if (full) {
            Text("This removes the record AND all of these:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            tied.forEach { t -> Text("• ${styleIcon(t.style)} ${t.reason}" + (t.at?.let { " · " + formatDateTime(it) } ?: ""), style = MaterialTheme.typography.bodyMedium) }
            if (row.source == SchedSource.SHOP_ARRIVAL) Text("• 🛒 Buy Now for this shop (if armed)", style = MaterialTheme.typography.bodyMedium)
            Text("Nothing of it will fire again. Restore from the Bin re-arms only what the record still has.", style = MaterialTheme.typography.bodySmall, color = InkHint, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text(kind?.let { deleteKindLabel(it) } ?: "Nothing to delete for this row.", style = MaterialTheme.typography.bodyMedium)
            Text("The record stays. Undo available for a few seconds.", style = MaterialTheme.typography.bodySmall, color = InkHint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Performs the deletion; returns (toast label, undo action). Every branch is total. */
private fun performDelete(context: android.content.Context, row: SchedRow, full: Boolean, kind: DeleteKind?): Pair<String, () -> Unit>? {
    if (full) return when (row.source) {
        SchedSource.CALLS -> {
            val r = row.callId?.let { CallStore.get(it) } ?: return null
            CallEngine.delete(context, r)
            "Deleted “${r.display}” + all alerts" to { CallEngine.restore(context, r.copy(deletedAt = null)); AlarmScheduler.rescheduleAll(context) }
        }
        SchedSource.SHOP_ARRIVAL -> {
            val sh = row.shopId?.let { ShopStore.get(it) } ?: return null
            Engine.deleteShop(context, sh)
            "Deleted shop “${sh.name}” + alerts" to { ShopStore.upsert(sh.copy(deletedAt = null)); Geofencer.registerAll(context) }
        }
        SchedSource.PLACE -> {
            val p = row.placeId?.let { PlaceStore.get(it) } ?: return null
            PlaceStore.upsert(p.copy(deletedAt = System.currentTimeMillis())); Geofencer.registerAll(context)
            "Deleted place “${p.name}”" to { PlaceStore.upsert(p.copy(deletedAt = null)); Geofencer.registerAll(context) }
        }
        else -> {
            val it0 = row.itemId?.let { ItemStore.get(it) } ?: return null
            Engine.delete(context, it0)
            "Deleted “${it0.title}” + all alerts" to { Engine.restore(context, it0) }
        }
    }
    return when (kind) {
        DeleteKind.MUTE_ITEM -> {
            val it0 = row.itemId?.let { ItemStore.get(it) } ?: return null
            val prev = Engine.muteItem(context, it0)
            "Alert deleted · “${it0.title}” is muted 🔕" to { Engine.unmuteItem(context, it0.id, prev) }
        }
        DeleteKind.CANCEL_SNOOZE -> {
            val cur = row.itemId?.let { ItemStore.get(it) } ?: return null
            val restored = cur.copy(snoozedUntil = null)
            ItemStore.upsert(restored); AlarmScheduler.cancelForItem(context, cur.id); AlarmScheduler.scheduleForItem(context, restored)
            "Snooze deleted" to { ItemStore.upsert(cur); AlarmScheduler.cancelForItem(context, cur.id); AlarmScheduler.scheduleForItem(context, cur) }
        }
        DeleteKind.CANCEL_RETURN -> {
            val cur = row.itemId?.let { ItemStore.get(it) } ?: return null
            val stopped = cur.copy(returnAt = null, repeatMode = "OFF")
            ItemStore.upsert(stopped); AlarmScheduler.cancelForItem(context, cur.id)
            "Return cancelled · recurrence stopped" to { ItemStore.upsert(cur); AlarmScheduler.scheduleLapseReturn(context, cur) }
        }
        DeleteKind.DELETE_CALL -> {
            val r = row.callId?.let { CallStore.get(it) } ?: return null
            CallEngine.delete(context, r)
            "Deleted “${r.display}”" to { CallEngine.restore(context, r.copy(deletedAt = null)); AlarmScheduler.rescheduleAll(context) }
        }
        DeleteKind.SILENCE_SHOP -> {
            val sh = row.shopId?.let { ShopStore.get(it) } ?: return null
            ShopStore.upsert(sh.copy(arriveTypes = ""))
            "Arrivals silenced at ${sh.name}" to { ShopStore.upsert(sh) }
        }
        DeleteKind.DISABLE_PLACE -> {
            val p = row.placeId?.let { PlaceStore.get(it) } ?: return null
            PlaceStore.upsert(p.copy(enabled = false)); Geofencer.registerAll(context)
            "Place disabled" to { PlaceStore.upsert(p); Geofencer.registerAll(context) }
        }
        null -> null
    }
}

private fun formatDayLabel(at: Long, now: Long): String {
    val dayMs = 24L * 3600_000L
    val startToday = java.util.Calendar.getInstance().apply {
        timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        at < startToday -> "Overdue"
        at < startToday + dayMs -> "Today"
        at < startToday + 2 * dayMs -> "Tomorrow"
        else -> formatDateTime(at).substringBefore(' ').ifBlank { "Later" }
    }
}

@Composable
private fun SchedRowView(r: SchedRow, now: Long, onMenu: () -> Unit) {
    val amber = r.unreachable
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (amber) Color(0xFFFFF4E5) else SurfaceCard)   // hex-ok: attention tint
            .clickable { onMenu() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(Modifier.width(78.dp)) {
            Text(r.at?.let { formatDateTime(it).substringAfter(' ').ifBlank { formatDateTime(it) } } ?: "—",
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = if (amber) AmberInk else SettingsAccent)
            r.at?.let { at ->
                val mins = (at - now) / 60_000L
                if (mins in 0..119) Text("in $mins min", style = MaterialTheme.typography.labelSmall, color = InkHint)
            }
        }
        Text(styleIcon(r.style), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                r.flags.forEach { f ->
                    Spacer(Modifier.width(4.dp))
                    Text(flagLabel(f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AmberInk,
                        maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFEBCC)).padding(horizontal = 6.dp, vertical = 1.dp))   // hex-ok: attention tint
                }
            }
            Text("${sourceLabel(r.source)} · ${r.reason} · ${alertTypesLabel(r.style)}",
                style = MaterialTheme.typography.bodySmall, color = InkSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("⋮", color = InkHint, style = MaterialTheme.typography.titleMedium)
    }
}

/** The row menu: open · cancel · and for an unreachable row the N40 cures. */
@Composable
private fun SchedRowMenu(r: SchedRow, onDismiss: () -> Unit, onDelete: (Boolean) -> Unit) {
    val context = LocalContext.current
    val tab = tabOf(r.source)
    fun tabLabel(t: Tab) = when (t) { Tab.TASKS -> "Tasks"; Tab.LEARN -> "Learn"; Tab.SHOP -> "Buy" }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.title, fontWeight = FontWeight.Bold) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
        Column {
            Text("${sourceLabel(r.source)} · ${r.reason}" + (if (r.unreachable) "\nArmed, but its card cannot be reached: " + r.flags.joinToString(", ") { flagLabel(it) } + "." else ""),
                style = MaterialTheme.typography.bodySmall, color = InkSubtle, modifier = Modifier.padding(bottom = 6.dp))
            if (r.itemId != null) TextButton(onClick = {
                runCatching {
                    val it = ItemStore.get(r.itemId)
                    if (it != null) {
                        UiStore.update { u -> u.copy(appMode = if (it.tab == Tab.SHOP) "SHOP" else "TASK") }
                        NotifyOpen.request(it.id)
                    }
                }.onFailure { Logger.e(context, "SCHED", it, "open item failed") }
                onDismiss()
            }) { Text("📄 Open the item") }
            if (r.itemId != null && r.kind == ComingUpKind.SNOOZE) TextButton(onClick = {
                runCatching {
                    ItemStore.get(r.itemId)?.let { cur ->
                        val restored = cur.copy(snoozedUntil = null)
                        ItemStore.upsert(restored)
                        AlarmScheduler.cancelForItem(context, cur.id)
                        AlarmScheduler.scheduleForItem(context, restored)
                        Logger.e(context, "SCHED", null, "snooze cancelled from Scheduled alerts for ${cur.id}")
                    }
                }.onFailure { Logger.e(context, "SCHED", it, "cancel snooze failed") }
                onDismiss()
            }) { Text("✖ Cancel this snooze") }
            if (tab != null && r.flags.contains(SchedFlag.HIDDEN_TAB)) {
                TextButton(onClick = {
                    SettingsStore.update {
                        when (tab) { Tab.TASKS -> it.copy(showTasks = true); Tab.LEARN -> it.copy(showLearn = true); Tab.SHOP -> it }
                    }
                    Feedback.toast(context, "${tabLabel(tab)} tab shown again"); onDismiss()
                }) { Text("👁 Show the ${tabLabel(tab)} tab") }
                TextButton(onClick = {
                    SettingsStore.update {
                        when (tab) { Tab.TASKS -> it.copy(tasksAlertsOn = "OFF"); Tab.LEARN -> it.copy(learnAlertsOn = "OFF"); Tab.SHOP -> it.copy(shopAlertsOn = "OFF") }
                    }
                    Logger.e(context, "SCHED", null, "alerts silenced for hidden tab ${tab.name} from Scheduled alerts")
                    Feedback.toast(context, "${tabLabel(tab)} alerts silenced"); onDismiss()
                }) { Text("🔕 Silence ${tabLabel(tab)} alerts") }
            }
            if (r.source == SchedSource.CALLS && r.flags.contains(SchedFlag.HIDDEN_TAB)) TextButton(onClick = {
                SettingsStore.update { it.copy(showCalls = true) }; Feedback.toast(context, "Calls tab shown again"); onDismiss()
            }) { Text("👁 Show the Calls tab") }
            // v2.8 (N44): the two deletions, labelled by consequence.
            deleteActionFor(r)?.let { k ->
                TextButton(onClick = { onDelete(false) }) { Text("🗑 Delete this alert (${deleteKindLabel(k).substringBefore(" (")})", color = OverdueRed) }
            }
            TextButton(onClick = { onDelete(true) }) {
                Text("🗑 Delete the ${when (r.source) { SchedSource.CALLS -> "reminder"; SchedSource.SHOP_ARRIVAL -> "shop"; SchedSource.PLACE -> "place"; else -> "item" }} — and every future alert", color = OverdueRed)
            }
        }
        }
    )
}
