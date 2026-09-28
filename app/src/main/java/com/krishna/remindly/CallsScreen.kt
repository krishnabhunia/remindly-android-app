package com.krishna.remindly

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Whatsapp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ================================================================ calls page

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallPage(isDone: Boolean, onSwitchDone: (Boolean) -> Unit, onOpenSettings: () -> Unit = {}) {
    var searchOpen by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    val pal = CallPal
    val context = LocalContext.current
    val all by CallStore.calls.collectAsState()
    val settings by SettingsStore.s.collectAsState()
    val gest = gesturesFor(settings, null)
    val curSwitchDone by rememberUpdatedState(onSwitchDone)
    var showAdd by remember { mutableStateOf(false) }
    var clearTarget by remember { mutableStateOf<Pair<String, List<CallReminder>>?>(null) }

    // v2.10 (N9): permissions asked in context, explained, and optional. The intro card below IS
    // the pre-prompt for call log; after the user decides it shrinks to a one-line note.
    val resumeTick = rememberResumeTick()
    val ui by UiStore.s.collectAsState()
    val callState = remember(resumeTick, ui.permAsked) { Perms.state(context, PermKeys.CALL_LOG) }
    val callPrompted = PermKeys.CALL_LOG in ui.permPrompted
    val contactsReq = rememberPermRequest(PermKeys.CONTACTS)
    val callLogReq = rememberPermRequest(PermKeys.CALL_LOG) { ok ->
        // v2.6.2 (N41): the watermark is anchored the moment the engine first runs and the
        // window is 5 minutes, so this can only ever pick up a call from the last few minutes —
        // never history. (Before N41 this call could ingest the whole log on some OEMs.)
        if (ok) {
            CallEngine.process(context)
            // names are the natural next question — asked once, with its own reason
            if (Perms.state(context, PermKeys.CONTACTS) == PermState.NOT_ASKED) contactsReq()
        }
    }
    fun allowCallLog() { Perms.markPrompted(PermKeys.CALL_LOG); callLogReq() }

    var labelFilter by remember { mutableStateOf<String?>(null) }
    val labelsInUse = all.filter { it.deletedAt == null }.mapNotNull { it.label?.takeIf { l -> l.isNotBlank() } }.distinct().sorted()
    val visible = all.filter { it.deletedAt == null }.filter { it.done == isDone }
        .let { l -> if (labelFilter == null) l else l.filter { it.label == labelFilter } }
        .let { l ->
            val q = searchQ.trim().lowercase()
            if (!searchOpen || q.isEmpty()) l else l.filter { r ->
                (r.name ?: "").lowercase().contains(q) || r.number.contains(q) || (r.note ?: "").lowercase().contains(q)
            }
        }
    // v1.15 item 24: Calls stacks newest activity on top in BOTH windows.
    val groups = buildYearGroups(visible, true, { it.createdAt }) { callBasis(it, isDone) }

    val fKey = "CALLS-${if (isDone) "D" else "A"}"
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
    var confirmSwipeDelete by remember { mutableStateOf<CallReminder?>(null) }
    fun toggle(set: Set<String>, k: String) = if (k in set) set - k else set + k

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
                leading = { ModeDrawerButton() },
                title = "Calls",
                subtitle = "${all.count { !it.done && it.deletedAt == null }} to call back · ${all.count { it.done && it.deletedAt == null }} done",
                pal = pal,
                trailing = {
                    // v1.52: never depend solely on the PHONE_STATE receiver — OEM battery
                    // managers suppress it, so give the user a manual catch-up scan.
                    IconButton(onClick = {
                        if (CallEngine.hasCallLog(context)) {
                            CallEngine.process(context)
                            Ack.show("Call log scanned — see Error Logs for details") {}
                        } else allowCallLog()
                    }) {
                        Icon(Icons.Filled.Refresh, "Scan call log now", tint = Color.White)
                    }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQ = "" }) {
                        Icon(Icons.Filled.Search, "Search", tint = Color.White)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, "Calls settings", tint = Color.White)
                    }
                },
                bottomContent = {
                    Column {
                        // v1.52 → v2.10 (N9): after the user has decided, a one-line note replaces the card —
                        // the tab stays fully usable for manual call-backs.
                        if (callsNoteVisible(callState, callPrompted)) {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Auto-detection is off — add call-backs with +.",
                                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { allowCallLog() }) {
                                    Text(if (callState == PermState.BLOCKED) "Settings" else "Enable", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (searchOpen) {
                            OutlinedTextField(
                                value = searchQ, onValueChange = { searchQ = it },
                                placeholder = { Text("Search calls…", color = Color.White.copy(alpha = 0.8f)) },
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ActiveDoneToggle(isDone, pal) { onSwitchDone(it) }
                        }
                    }
                }
            )

            if (callsIntroVisible(callState, callPrompted)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = pal.chipBg)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Let Remindly watch for missed calls",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = pal.onChip
                        )
                        Text(
                            permInfo(PermKeys.CALL_LOG).enables + " Everything stays on your phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = pal.onChip,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            "Optional. " + permInfo(PermKeys.CALL_LOG).withoutIt,
                            style = MaterialTheme.typography.bodySmall,
                            color = pal.onChip,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { allowCallLog() },
                                colors = ButtonDefaults.buttonColors(containerColor = pal.accent)
                            ) { Text("Allow access", color = Color.White, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = {
                                Perms.markPrompted(PermKeys.CALL_LOG)
                                runCatching { Logger.e(context, "PERM", null, "CALL_LOG declined on the Calls intro card — manual entry only") }
                            }) { Text("Not now", color = pal.onChip, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }

            if (groups.isEmpty()) {
                EmptyState(
                    if (isDone) "No cleared call reminders yet."
                    else "No pending call-backs. Missed calls will land here automatically."
                )
            }

            if (labelsInUse.isNotEmpty()) {
                // v1.23 item 1: outlined chips that scroll sideways instead of stacking, with
                // "All" leading and theme colours so dark mode reads properly.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (listOf<String?>(null) + labelsInUse).forEach { lb ->
                        val sel = labelFilter == lb
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (sel) pal.accent else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (sel) pal.accent else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(50)
                                )
                                .clickable { labelFilter = lb }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (lb != null) {
                                Icon(
                                    Icons.Filled.Sell, null,
                                    tint = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                lb ?: "All",
                                color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 10.dp, bottom = 110.dp)
            ) {
                groups.forEach { year ->
                    item(key = "y-${year.key}") {
                        Box(Modifier.animateItemPlacement()) {
                            YearHeader(
                                year.label, year.count(), year.key in collapsedYears, pal,
                                onToggle = { collapsedYears = toggle(collapsedYears, year.key) },
                                onClear = if (isDone) ({ clearTarget = year.label to year.allItems() }) else null
                            )
                        }
                    }
                    if (year.key !in collapsedYears) {
                        year.months.forEach { month ->
                            item(key = "m-${month.key}") {
                                Box(Modifier.animateItemPlacement()) {
                                    MonthHeader(
                                        month.label, month.count(), month.key in collapsedMonths, pal,
                                        onToggle = { collapsedMonths = toggle(collapsedMonths, month.key) },
                                        onClear = if (isDone && settings.showDeleteOnDone) ({
                                            clearTarget = "${month.label} ${year.label}" to month.days.flatMap { it.items }
                                        }) else null
                                    )
                                }
                            }
                            if (month.key !in collapsedMonths) {
                                month.days.forEach { day ->
                                    item(key = "d-${day.key}") {
                                        Box(Modifier.animateItemPlacement()) {
                                            DayHeader(day.label, day.items.size, day.key in collapsedDays, pal) {
                                                collapsedDays = toggle(collapsedDays, day.key)
                                            }
                                        }
                                    }
                                    if (day.key !in collapsedDays) {
                                        // v1.20 item 3: one contact, one day → one card.
                                        val merged = day.items
                                            .groupBy { normalizePhone(it.number) }
                                            .values.toList()
                                        items(merged, key = { it.first().id }) { grp ->
                                            val r = grp.first()
                                            SwipeMoveRow(
                                                isDoneList = isDone,
                                                enabled = gest.cardSwipe,
                                                rightIsDone = gest.cardRightDone,
                                                vPad = densOfPct(settings.densityPct).outerV,
                                                deleteEnabled = gest.revSwipeDelete,
                                                onComplete = { grp.forEach { CallEngine.startComplete(context, it) } },
                                                onRevive = { grp.forEach { CallEngine.startRevive(context, it) } },
                                                onDelete = { performCallDelete(context, r) { confirmSwipeDelete = it } },
                                                modifier = Modifier.animateItemPlacement()
                                            ) {
                                                CallCard(r, pal, isDone, peers = grp.drop(1))
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

        if (!isDone) {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = pal.accent,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) { Icon(Icons.Filled.Add, "Add call reminder") }
        }
    }

    if (showAdd) {
        AddCallSheet(pal) { showAdd = false }
    }
    clearTarget?.let { (label, toClear) ->
        ConfirmDialog(
            title = "Clear $label?",
            text = "${toClear.size} cleared call reminder(s) will be deleted permanently.",
            confirmLabel = "Clear",
            onConfirm = { toClear.forEach { CallEngine.delete(context, it) } },
            onDismiss = { clearTarget = null }
        )
    }

    confirmSwipeDelete?.let { r ->
        ConfirmDialog(
            title = "Delete permanently?",
            text = "The reminder for ${r.display} will be removed for good.",
            confirmLabel = "Delete",
            onConfirm = { CallEngine.delete(context, r); confirmSwipeDelete = null },
            onDismiss = { confirmSwipeDelete = null }
        )
    }
}

// ================================================================ call card

@OptIn(ExperimentalLayoutApi::class)
/** v1.23 item 1: the base label as a tinted pill — Auto or Manual, red when overdue. */
@Composable
private fun LabelPill(labels: String, overdue: Boolean, source: CallSource) {
    val bg = when {
        overdue -> OverdueRed.copy(alpha = 0.12f)
        source == CallSource.AUTO -> CallBlue.copy(alpha = 0.12f)
        else -> SourceManual.copy(alpha = 0.12f)
    }
    val fg = when {
        overdue -> OverdueRed
        source == CallSource.AUTO -> SourceAuto
        else -> SourceManual
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Icon(
            if (source == CallSource.AUTO) Icons.Filled.CallMissed else Icons.Filled.Edit,
            null, tint = fg, modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (overdue) "Overdue · $labels" else labels,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}

/** The user's own label, styled exactly like the filter chips so it reads the same everywhere. */
@Composable
private fun LabelChip(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Icon(
            Icons.Filled.Sell, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** v1.26 item 5: the phonebook groups chosen when this caller was saved — shown as accent chips, capped with +N. */
@Composable
private fun SavedGroupChips(groups: List<String>) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.take(3).forEach { g ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, callPalC().accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Icon(Icons.Filled.Group, null, tint = callPalC().accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(g, style = MaterialTheme.typography.labelSmall, color = callPalC().accent)
            }
        }
        if (groups.size > 3) Text(
            "+${groups.size - 3}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallCard(r: CallReminder, pal: TabPalette, isDoneList: Boolean, modifier: Modifier = Modifier, peers: List<CallReminder> = emptyList()) {
    val context = LocalContext.current
    var expanded by remember(r.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<CallReminder?>(null) }
    var saveFor by remember { mutableStateOf<CallReminder?>(null) }
    val pending by CallEngine.pendingDone.collectAsState()
    val endsAt = pending[r.id]
    val s = SettingsStore.s.collectAsState().value
    // v1.48 (fixes the v1.44 gap): Calls now honours Card Layout too. Calls is the null tab -> "c" prefix.
    val cf = cardFieldsFor(s, null)
    val dens = densOfPct(s.densityPct)
    // v1.20 items 2+3: one card can carry several records for the same contact and day.
    val records = remember(r, peers) { listOf(r) + peers }
    val labels = remember(records) { records.map { callBaseLabel(it) }.distinct().joinToString(" · ") }
    val overdue = !isDoneList && records.any { callOverdue(it, false, System.currentTimeMillis()) }

    FxCard(id = r.id, pal = pal, modifier = modifier, vPad = dens.outerV) {
        Column(Modifier.padding(start = 6.dp, end = 10.dp, top = dens.innerV, bottom = dens.innerV)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cf.checkbox) {
                Checkbox(
                    checked = isDoneList || endsAt != null,
                    onCheckedChange = { checked ->
                        if (isDoneList) {
                            if (!checked) CallEngine.startRevive(context, r)
                        } else {
                            if (checked) CallEngine.startComplete(context, r) else CallEngine.cancelPending(r.id)
                        }
                    },
                    colors = CheckboxDefaults.colors(checkedColor = pal.accent)
                )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded }) {
                    Text(
                        r.display,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.fs(s.fsCardTitle),
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDoneList) GreyIcon else InkPrimary,
                        textDecoration = if (isDoneList) TextDecoration.LineThrough else null
                    )
                    // v1.19 item 9: the collapsed card is minimal — details per Card-Details settings.
                    // v1.67: company line under the name when present.
                    if (r.company != null) {
                        Text(
                            r.company,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSubtle
                        )
                    }
                    if (r.name != null && (expanded || s.callCardShowNumber)) {
                        Text(
                            r.number,
                            style = MaterialTheme.typography.bodySmall,
                            color = GreyIcon
                        )
                    }
                    if (isDoneList) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                listOfNotNull(
                                    labels, r.clearedNote, r.doneAt?.takeIf { cf.dateTime }?.let { formatTime(it) }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = ShopInk
                            )
                            if (r.missedCount > 1) {
                                MetaChip("×${r.missedCount} missed", UrgentSoft, OverdueRed)
                            }
                        }
                    } else {
                        val missed = r.lastMissedAt
                        val stampTime = missed ?: r.recurAt ?: r.createdAt
                        // v1.20 item 2: the day already lives in the group header — the card
                        // carries its base label and the time alone.
                        // v1.48: Card Layout can hide the time on Calls cards too.
                        val stampText = if (cf.dateTime) formatTime(stampTime) else ""
                        @OptIn(ExperimentalLayoutApi::class)
                        if (expanded) FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            LabelPill(labels, overdue, r.source)
                            Text(
                                stampText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (r.missedCount > 1) {
                                MetaChip("×${r.missedCount} missed", UrgentSoft, OverdueRed)
                            }
                        } else {
                            if (s.callCardShowMissedAt) Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                LabelPill(labels, overdue, r.source)
                                Text(
                                    stampText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (r.missedCount > 1 && s.callCardShowMissedCount) {
                                MetaChip("×${r.missedCount} missed", UrgentSoft, OverdueRed)
                            }
                        }
                    }
                    if (expanded || s.callCardShowNote) r.note?.takeIf { it.isNotBlank() }?.let { n ->
                        Text(
                            "✎ " + n,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSubtle,
                            maxLines = 2
                        )
                    }
                    if ((expanded || s.callCardShowLabel) && !r.label.isNullOrBlank()) {
                        LabelChip(r.label)
                    }
                    if ((expanded || s.callCardShowLabel) && r.savedGroups.isNotEmpty()) {
                        SavedGroupChips(r.savedGroups)
                    }
                    // v1.14: recurring manual calls — where the cycle stands.
                    if (r.repeatMode != "OFF" && r.recurAt != null && (expanded || s.callCardShowNext || isDoneList)) {
                        Text(
                            (if (isDoneList) "↻ returns " else "↻ next ") + formatDay(r.recurAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = ShopTeal,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // v1.16 item 2: the expanded icon row lives INSIDE this column, so the
                    // first icon aligns exactly under the name's first letter.
                    if (expanded) Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ActionDot(CallBlue, Icons.Filled.Phone, "Call") { CallActions.dial(context, r.number) }
                        ActionDot(Color(0xFF5C6BC0), Icons.Filled.Sms, "SMS") { CallActions.sms(context, r.number) }   // hex-ok(Q2): brand/action dot
                        ActionDot(Color(0xFF25D366), Icons.Filled.Whatsapp, "WhatsApp") { CallActions.waChat(context, r.number) }   // hex-ok(Q2): brand/action dot
                        ActionDot(InkSubtle, Icons.Filled.Edit, "Note") { noteFor = r }
                        if (!isDoneList) {
                            ActionDot(Color(0xFF8E6C00), Icons.Filled.Snooze, "Snooze " + Alerts.snoozeLabel(snoozeMinutes(SettingsStore.s.value))) { CallEngine.snooze(context, r) }   // hex-ok(Q2): brand/action dot
                        }
                        if (r.name.isNullOrBlank()) {
                            // v1.19 item 7: unknown caller → save to contacts.
                            ActionDot(Color(0xFF2E7D32), Icons.Filled.PersonAdd, "Save contact") { saveFor = r }   // hex-ok(Q2): brand/action dot
                        }
                        // v1.22 item 3: delete belongs to the Done list — or to a card still
                        // inside its first two hours.
                        ActionDot(DangerAccent, Icons.Filled.Delete, "Delete") {
                            if (gesturesFor(SettingsStore.s.value, null).deleteStyle == "CONFIRM") confirmDelete = true
                            else {
                                CallEngine.delete(context, r)
                                Ack.show("Deleted") { CallEngine.restore(context, r) }
                            }
                        }
                    }
                }

                // v1.15 item 25: collapsed shows one primary dot; the edit state moves ALL
                // icons to their own full-width row below, so the name never wraps.
                if (!expanded) Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isDoneList) {
                        ActionDot(CallBlue, Icons.Filled.Phone, "Call") {
                            CallActions.dial(context, r.number)
                        }
                    } else {
                        // v1.22 item 3: delete belongs to the Done list — or to a card still
                        // inside its first two hours.
                        ActionDot(DangerAccent, Icons.Filled.Delete, "Delete") {
                            if (gesturesFor(SettingsStore.s.value, null).deleteStyle == "CONFIRM") confirmDelete = true
                            else {
                                CallEngine.delete(context, r)
                                Ack.show("Deleted") { CallEngine.restore(context, r) }
                            }
                        }
                    }
                }
            }

            if (endsAt != null && !isDoneList) {
                CountdownRow(endsAt, pal) { CallEngine.cancelPending(r.id) }
            }
        }
    }

    // ---------------- v1.19 item 7: save an unknown caller into contacts ----------------
    saveFor?.let { target ->
        var first by remember(target.id) { mutableStateOf("") }
        var last by remember(target.id) { mutableStateOf("") }
        val now = remember { System.currentTimeMillis() }
        var delMonth by remember { mutableStateOf(now.toLocalDate().monthValue) }
        var delYear by remember { mutableStateOf(now.toLocalDate().year + 1) }
        fun delLabel() = "Delete After " + java.time.Month.of(delMonth).name.lowercase()
            .replaceFirstChar { it.uppercase() }.take(3) + " " + delYear
        val labels = remember(target.id) { mutableStateListOf<String>() }
        var company by remember(target.id) { mutableStateOf("") }
        var showMonthPick by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun persist() {
            val chosen = labels.toSet()
            val full = ContactSaver.save(context, target.number, first, last, company, chosen)
            if (full != null) {
                // v1.85 (N16, class sweep): third site of the same shape — this sheet also held
                // a tap-time snapshot and wrote a full copy from it.
                val liveP = CallStore.get(target.id)
                if (liveP != null) staleCallFields(target, liveP).takeIf { it.isNotEmpty() }
                    ?.let { Logger.e(context, "SAVE", null, "call ${target.id}: merged live $it over stale snapshot (contact-save)") }
                CallStore.upsert((editorSaveBase(target, liveP) ?: target).copy(name = full, savedGroups = chosen.toList()))
                Ack.show("Saved to contacts" + if (chosen.isNotEmpty()) " · ${chosen.size} label${if (chosen.size > 1) "s" else ""}" else "") {}
                saveFor = null
            } else Ack.show("Could not save — check the name") {}
        }
        // v2.10 (N9): explained first; a refusal leaves the sheet usable and says why nothing was saved.
        val saveReq = rememberPermRequest(PermKeys.SAVE_CONTACT) { ok ->
            if (ok) persist() else Ack.show("Not saved — Remindly isn't allowed to add contacts (Settings → Permissions)") {}
        }
        fun doSave() = saveReq()
        // v1.28 item 2: Save Contact now uses the same white bottom sheet as the edit popup.
        ModalBottomSheet(onDismissRequest = { saveFor = null }, sheetState = sheetState, containerColor = SurfaceCard, windowInsets = sheetWindowInsets()) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Text(
                        "Save contact",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = callPalC().accent, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        target.number,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = first, onValueChange = { first = it },
                    label = { Text("First name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = last, onValueChange = { last = it },
                    label = { Text("Last name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = company, onValueChange = { company = it },
                    label = { Text("Company") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Label", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Temp Saved Names", "Jobs", delLabel()).forEach { lb ->
                        val isDel = lb.startsWith("Delete After")
                        val sel = if (isDel) labels.any { CallLabels.isDeleteAfter(it) } else labels.contains(lb)
                        Text(
                            lb,
                            color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (sel) callPalC().accent else Color.Transparent)
                                .border(1.dp, if (sel) callPalC().accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                                .clickable {
                                    if (isDel) {
                                        if (sel) {
                                            val nl = CallLabels.clearDeleteAfter(labels.toSet())
                                            labels.clear(); labels.addAll(nl)
                                        } else showMonthPick = true
                                    } else {
                                        val nl = CallLabels.toggle(labels.toSet(), lb)
                                        labels.clear(); labels.addAll(nl)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Text(
                    "Saved into the account your phonebook syncs with. \"Delete After\" is a label only — nothing is deleted automatically.",
                    style = MaterialTheme.typography.labelSmall, color = InkHint
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { saveFor = null },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("Cancel", fontWeight = FontWeight.Bold, color = InkSubtle) }
                    Button(
                        onClick = { doSave() },
                        colors = ButtonDefaults.buttonColors(containerColor = callPalC().accent),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("Save", fontWeight = FontWeight.Bold, color = Color.White) }
                }
                Spacer(Modifier.height(8.dp))
                SheetBottomSpace()   // v2.01 (N32): Option D clearance
                Spacer(Modifier.height(40.dp))
            }
        }
        if (showMonthPick) AlertDialog(
            onDismissRequest = { showMonthPick = false },
            title = { Text("Delete After…", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..12).forEach { m ->
                            val sel = delMonth == m
                            Text(
                                java.time.Month.of(m).name.lowercase().replaceFirstChar { it.uppercase() }.take(3),
                                color = if (sel) Color.White else InkStrong,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) callPalC().accent else SurfaceSubtle)
                                    .clickable { delMonth = m }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { delYear-- }) { Text("−") }
                        Text("$delYear", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { delYear++ }) { Text("+") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                val nl = CallLabels.setDeleteAfter(labels.toSet(), delLabel())
                labels.clear(); labels.addAll(nl); showMonthPick = false
            }) { Text("Done") } }
        )
    }

    noteFor?.let { target ->
        var txt by remember(target.id) { mutableStateOf(target.note ?: "") }
        var rMode by remember(target.id) { mutableStateOf(target.repeatMode) }
        var rDays by remember(target.id) { mutableStateOf(target.repeatDays.toSet()) }
        var rN by remember(target.id) { mutableStateOf(target.repeatN.toString()) }
        var rUnit by remember(target.id) { mutableStateOf(target.repeatUnit) }
        var rOrd by remember(target.id) { mutableStateOf(target.repeatOrd) }
        var rDow by remember(target.id) { mutableStateOf(target.repeatDow) }
        var rOrdList by remember(target.id) { mutableStateOf(target.repeatOrdList) }
        var rCount by remember(target.id) { mutableStateOf(target.repeatCount) }   // v1.24 item 5
        var rTime by remember(target.id) {
            mutableStateOf(target.recurAt?.let { minuteOfDay(it) } ?: SettingsStore.s.value.callReminderMinutes)
        }
        var showRep by remember { mutableStateOf(false) }
        var lbl by remember(target.id) { mutableStateOf(target.label ?: "") }
        var cFirst by remember(target.id) { mutableStateOf(target.firstName ?: "") }   // v1.67
        var cLast by remember(target.id) { mutableStateOf(target.lastName ?: "") }
        var cCompany by remember(target.id) { mutableStateOf(target.company ?: "") }
        var cType by remember(target.id) { mutableStateOf(target.alertType) }   // v1.68
        // v1.79 (N4): optional WhatsApp text; blank means no Send WhatsApp action.
        var cMsg by remember(target.id) { mutableStateOf(target.message ?: "") }
        var rStart by remember(target.id) { mutableStateOf<Long?>(target.recurAt?.let { startOfDayMs(it) }) }
        val manual = target.source == CallSource.MANUAL
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun saveNow() {
            // v1.85 (N16 part B): identical mechanism to the item editor — `target` is the
            // snapshot captured at tap. Rebase on the live record so a snooze made from the
            // card, or done/cleared from a notification, survives this Save.
            val liveT = CallStore.get(target.id)
            if (liveT != null) staleCallFields(target, liveT).takeIf { it.isNotEmpty() }
                ?.let { Logger.e(context, "SAVE", null, "call ${target.id}: merged live $it over stale snapshot") }
            val saveB = editorSaveBase(target, liveT) ?: target
            var upd = saveB.copy(note = txt.trim().ifBlank { null }, label = lbl.trim().ifBlank { null }, firstName = cFirst.trim().ifBlank { null }, lastName = cLast.trim().ifBlank { null }, company = cCompany.trim().ifBlank { null }, alertType = cType, message = cMsg.trim().ifBlank { null })
            if (manual) {
                val mode = rMode
                val anchor = combineDayTime(rStart ?: startOfDayMs(System.currentTimeMillis()), rTime)
                val recur = if (mode != "OFF") {
                    // v1.15 item 3: "Starting from" anchors calls too.
                    previewOccurrences(mode, rDays, rN.toIntOrNull() ?: 1, rUnit, rOrd, rDow, rOrdList, anchor, 1, startFrom = anchor)
                        .firstOrNull() ?: anchor
                } else if (rStart != null) anchor else null   // v1.23: a plain one-time call date
                upd = upd.copy(
                    repeatCount = sanitizeRepeatCount(rCount),
                    repeatMode = mode, repeatDays = rDays.toList(),
                    repeatN = (rN.toIntOrNull() ?: 1).coerceIn(1, 365), repeatUnit = rUnit,
                    repeatOrd = rOrd, repeatDow = rDow, repeatOrdList = rOrdList, recurAt = recur
                )
            }
            CallStore.upsert(upd)
            // v1.59: one-offs (and rule changes) take effect immediately, not on the next miss.
            if (manual) AlarmScheduler.scheduleCallRecur(context, upd)
            noteFor = null
        }
        // v1.27 item 2: the Calls editor is now the same white bottom sheet the item editors use,
        // leading with the contact name and grouping its controls, for true visual parity.
        ModalBottomSheet(onDismissRequest = { noteFor = null }, sheetState = sheetState, containerColor = SurfaceCard, windowInsets = sheetWindowInsets()) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Text(
                        target.display,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = callPalC().accent, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        (if (manual) "Manual reminder" else "Auto reminder") +
                            (if (target.name != null) " · " + target.number else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = txt, onValueChange = { txt = it },
                    label = { Text("Note") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lbl, onValueChange = { lbl = it },
                    label = { Text("Label") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                val lblSuggest = CallStore.calls.value
                    .filter { it.deletedAt == null }
                    .mapNotNull { it.label?.takeIf { l -> l.isNotBlank() } }
                    .distinct().sorted()
                if (lblSuggest.isNotEmpty()) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        lblSuggest.take(6).forEach { s ->
                            val sel = lbl == s
                            Text(
                                s,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (sel) callPalC().accent else Color.Transparent)
                                    .border(1.dp, if (sel) callPalC().accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                                    .clickable { lbl = s }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                if (manual) {
                    Text("Schedule", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    // v1.23 item 2: manual calls get the very same ScheduleBox the item tabs use.
                    // v1.86 (N24): the shared box speaks SchedKind now; a manual call is
                    // two-state by design (D2) — showNone stays false, NONE is unreachable.
                    ScheduleBox(
                        kind = if (rMode != "OFF") SchedKind.REPEAT else SchedKind.ONCE,
                        showNone = false,
                        onKind = { k -> rMode = if (k == SchedKind.REPEAT) (if (rMode == "OFF") "DAILY" else rMode) else "OFF" },
                        dueDate = rStart, onDate = { rStart = it },
                        timeMin = rTime, onTime = { rTime = it ?: rTime },
                        mode = rMode, days = rDays, nStr = rN, unit = rUnit,
                        ord = rOrd, dow = rDow, ordList = rOrdList, spacedStep = 0,
                        accent = callPalC().accent, dateLabel = "Call on",
                        repeatCount = rCount, repeatDone = target.repeatDone
                    ) { showRep = true }
                }
                // v1.59: per-reminder ONE-OFF alerts — engine shipped in v1.58, UI lands here.
                // v1.67: structured contact fields — auto-filled from device contacts, editable here.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cFirst, { cFirst = it }, Modifier.weight(1f), singleLine = true, label = { Text("First name") })
                    OutlinedTextField(cLast, { cLast = it }, Modifier.weight(1f), singleLine = true, label = { Text("Last name") })
                }
                OutlinedTextField(cCompany, { cCompany = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Company") })
                // v1.69: one-off alerts RETIRED — the per-call Reminder Type drives the detection alert.
                GroupBox("Reminder Type") {
                    ChoiceChips(listOf("Alarm", "Ring", "Notify"),
                        listOf("A", "R", "N").indexOf(cType).coerceAtLeast(0), callPalC().accent) { ix ->
                        cType = listOf("A", "R", "N")[ix]
                    }
                }
                // v1.79 (N4): WhatsApp cannot be sent automatically — the reminder offers a
                // "Send WhatsApp" action that opens the chat with this text already typed.
                OutlinedTextField(
                    cMsg, { cMsg = it }, Modifier.fillMaxWidth(),
                    label = { Text("WhatsApp message (optional)") },
                    supportingText = { Text("The reminder gets a Send WhatsApp button. You still tap send.") },
                    minLines = 2
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { noteFor = null },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("Cancel", fontWeight = FontWeight.Bold, color = InkSubtle) }
                    Button(
                        onClick = { saveNow() },
                        colors = ButtonDefaults.buttonColors(containerColor = callPalC().accent),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("Save", fontWeight = FontWeight.Bold, color = Color.White) }
                }
                Spacer(Modifier.height(8.dp))
                SheetBottomSpace()   // v2.01 (N32): Option D clearance
                Spacer(Modifier.height(40.dp))
            }
        }
        if (showRep) RepeatDialog(
            rMode, rDays, rN, rUnit, rOrd, rDow, rOrdList, callPalC().accent,
            count0 = rCount,
            onDone = { m, d, n, u, o, w, ol, cnt ->
                rMode = m; rDays = d; rN = n; rUnit = u; rOrd = o; rDow = w; rOrdList = ol
                rCount = cnt; showRep = false
            },
            onCancel = { showRep = false }
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete permanently?",
            text = "The reminder for ${r.display} will be removed for good.",
            confirmLabel = "Delete",
            onConfirm = { CallEngine.delete(context, r); confirmDelete = false },
            onDismiss = { confirmDelete = false }
        )
    }
}

/** v1.5 swipe-delete for call cards, honouring the delete-style setting. */
private fun performCallDelete(
    context: android.content.Context,
    r: CallReminder,
    askConfirm: (CallReminder?) -> Unit
) {
    if (gesturesFor(SettingsStore.s.value, null).deleteStyle == "CONFIRM") askConfirm(r)
    else {
        CallEngine.delete(context, r)
        Ack.show("Deleted") { CallEngine.restore(context, r) }
    }
}

@Composable
private fun ActionDot(bg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

// ================================================================ add sheet (recents + contacts)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCallSheet(pal: TabPalette, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recents = remember { CallEngine.recentCalls(context) }

    fun added(ok: Boolean) {
        Toast.makeText(
            context,
            if (ok) "Call reminder added" else "Already on your list",
            Toast.LENGTH_SHORT
        ).show()
        if (ok) onDismiss()
    }

    val contactLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) {
            val (name, number) = CallEngine.readPickedContact(context, uri)
            if (number.isNullOrBlank()) {
                Toast.makeText(context, "That contact has no phone number", Toast.LENGTH_SHORT).show()
            } else {
                added(CallEngine.manualAdd(context, number, name))
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = SurfaceCard, windowInsets = sheetWindowInsets()) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(
                "Remind me to call…",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = InkPrimary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(pal.chipBg)
                    .clickable { contactLauncher.launch(null) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Contacts, null, tint = pal.onChip)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Pick from contacts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = pal.onChip
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
            ) {
                Icon(Icons.Filled.History, null, tint = InkSubtle, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Recent calls",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = InkSubtle
                )
            }
            if (recents.isEmpty()) {
                Text(
                    "No recent calls to show (grant call-log access on the Calls page first).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GreyIcon,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                items(recents, key = { it.number + it.date }) { rc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { added(CallEngine.manualAdd(context, rc.number, rc.name)) }
                            .padding(horizontal = 6.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(pal.chipBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (rc.name ?: rc.number).firstOrNull()?.uppercaseChar()?.toString() ?: "#",
                                color = pal.onChip, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                rc.name ?: rc.number,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = InkPrimary
                            )
                            Text(
                                (if (rc.name != null) rc.number + " · " else "") + formatDayTime(rc.date),
                                style = MaterialTheme.typography.bodySmall,
                                color = GreyIcon
                            )
                        }
                        Icon(Icons.Filled.Add, null, tint = pal.accent)
                    }
                }
            }
            // v1.88 (N27): house pattern — last child clears the Android nav buttons.
            SheetBottomSpace()   // v2.01 (N32): Option D clearance
        }
    }
}
