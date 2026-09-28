package com.krishna.remindly

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.border
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import java.time.ZoneId
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ================================================================ gradient header

@Composable
fun GradientHeader(
    title: String,
    subtitle: String,
    pal: TabPalette,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    bottomContent: @Composable () -> Unit = {}
) {
    val fsH = SettingsStore.s.collectAsState().value.fsScreenHeader
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
            .background(Brush.linearGradient(listOf(pal.g1, pal.g2)))
            .drawBehind {
                val r = size.minDimension / 5f
                val dot = Color.White.copy(alpha = 0.07f)
                var y = -r / 2
                var row = 0
                while (y < size.height + r) {
                    var x = if (row % 2 == 0) -r / 2 else r / 2
                    while (x < size.width + r) {
                        drawCircle(dot, radius = r / 2.1f, center = Offset(x, y))
                        x += r * 1.5f
                    }
                    y += r * 1.2f
                    row++
                }
            }
            .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading()
            Row(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium.fs(fsH),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.alignByBaseline()
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium.fs(fsH),
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline()
                    )
                }
            }
            trailing()
        }
        bottomContent()
    }
}


// ================================================================ Active | Done toggle

@Composable
fun ActiveDoneToggle(
    showDone: Boolean, pal: TabPalette, compact: Boolean = false,
    // v1.86 (N18), wording v1.89 (N28): calendar mode renames the SAME control — "Upcoming" | "Past" — because
    // the calendar filter divides by TIME, not by done-state. Labels only; behaviour identical.
    labels: Pair<String, String> = "Active" to "Done",
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(if (compact) 2.dp else 4.dp)
    ) {
        listOf(false to labels.first, true to labels.second).forEach { (isDone, label) ->
            val selected = showDone == isDone
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onChange(isDone) }
                    .padding(
                        horizontal = if (compact) 10.dp else 22.dp,
                        vertical = if (compact) 5.dp else 7.dp
                    )
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) pal.g1 else Color.White
                )
            }
        }
    }
}

/**
 * v2.05 (N38): the same pill control with an optional THIRD segment — Active | Buy Now · <shop> |
 * Done. The middle segment exists only while a shop arrival has armed it (UiStore.buyNowShopId);
 * its label is ellipsised by buyNowLabel() so the row can never wrap. Selecting is the only job
 * here — arming/hiding lives in BuyNow.
 */
@Composable
fun ViewToggle(
    view: ListView,
    pal: TabPalette,
    buyNowShopName: String?,
    compact: Boolean = false,
    labels: Pair<String, String> = "Active" to "Done",
    onChange: (ListView) -> Unit
) {
    val options = buildList {
        add(ListView.ACTIVE to labels.first)
        if (buyNowShopName != null) add(ListView.BUY_NOW to buyNowLabel(buyNowShopName))
        add(ListView.DONE to labels.second)
    }
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(if (compact) 2.dp else 4.dp)
    ) {
        options.forEach { (v, label) ->
            val selected = view == v
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(
                        horizontal = if (compact) 8.dp else 14.dp,
                        vertical = if (compact) 5.dp else 7.dp
                    )
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) pal.g1 else Color.White
                )
            }
        }
    }
}

// ================================================================ group headers

/**
 * v2.7 (N43): the checkbox on every group header. It is its own clickable so a tap on it never
 * toggles the header's collapse. State drives the glyph: NONE empty · ALL ✓ · MIXED – · LOCKED 🔒.
 */
@Composable
fun GroupCheck(state: GroupCheckState, onDark: Boolean, accent: Color, onTap: () -> Unit) {
    val enabled = state != GroupCheckState.LOCKED && state != GroupCheckState.EMPTY
    val fill = when (state) {
        GroupCheckState.ALL -> if (onDark) Color.White else accent
        else -> Color.Transparent
    }
    val stroke = when {
        state == GroupCheckState.LOCKED -> (if (onDark) Color.White else InkHint).copy(alpha = 0.45f)
        onDark -> Color.White
        else -> accent
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .border(2.dp, stroke, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Text(
            when (state) {
                GroupCheckState.ALL -> "✓"; GroupCheckState.MIXED -> "–"
                GroupCheckState.LOCKED -> "🔒"; else -> ""
            },
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = when {
                state == GroupCheckState.ALL && onDark -> accent
                state == GroupCheckState.ALL -> Color.White
                onDark -> Color.White
                else -> accent
            }
        )
    }
    Spacer(Modifier.width(10.dp))
}

@Composable
fun YearHeader(
    label: String,
    count: Int,
    collapsed: Boolean,
    pal: TabPalette,
    onToggle: () -> Unit,
    onClear: (() -> Unit)? = null,
    check: GroupCheckState? = null,        // v2.7 (N43)
    onCheck: (() -> Unit)? = null
) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "yearChevron")
    val fsG = SettingsStore.s.collectAsState().value.fsGroupHeader
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(pal.g1, pal.g2)))
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (check != null && onCheck != null) GroupCheck(check, onDark = true, accent = pal.g1, onTap = onCheck)
        Text(
            label,
            style = MaterialTheme.typography.titleLarge.fs(fsG),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        if (onClear != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.DeleteSweep, "Clear year", tint = Color.White)
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Icon(
            Icons.Filled.ExpandMore, contentDescription = null,
            tint = Color.White, modifier = Modifier.rotate(rotation)
        )
    }
}

@Composable
fun MonthHeader(
    label: String,
    count: Int,
    collapsed: Boolean,
    pal: TabPalette,
    onToggle: () -> Unit,
    onClear: (() -> Unit)? = null,
    // v1.87 (N17): the group header's Share entry (mockups' ⋮ realised as a direct icon,
    // matching the existing onClear pattern). Non-null only on active Tasks/Shop groups.
    onShare: (() -> Unit)? = null,
    check: GroupCheckState? = null,        // v2.7 (N43)
    onCheck: (() -> Unit)? = null
) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "monthChevron")
    val fsG = SettingsStore.s.collectAsState().value.fsGroupHeader
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 14.dp, top = 3.dp, bottom = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(pal.chipBg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (check != null && onCheck != null) GroupCheck(check, onDark = false, accent = pal.onChip, onTap = onCheck)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.fs(fsG),
            fontWeight = FontWeight.Bold,
            color = pal.onChip,
            modifier = Modifier.weight(1f)
        )
        if (onShare != null) {
            IconButton(onClick = onShare, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Share, "Share list", tint = pal.onChip)
            }
            Spacer(Modifier.width(6.dp))
        }
        if (onClear != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.DeleteSweep, "Clear month", tint = pal.onChip)
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = pal.onChip
        )
        Icon(
            Icons.Filled.ExpandMore, contentDescription = null,
            tint = pal.onChip, modifier = Modifier.rotate(rotation)
        )
    }
}

@Composable
fun DayHeader(
    label: String, count: Int, collapsed: Boolean, pal: TabPalette,
    check: GroupCheckState? = null, onCheck: (() -> Unit)? = null,     // v2.7 (N43)
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "dayChevron")
    val fsG = SettingsStore.s.collectAsState().value.fsGroupHeader
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp, end = 14.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (check != null && onCheck != null) GroupCheck(check, onDark = false, accent = pal.accent, onTap = onCheck)
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(pal.accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall.fs(fsG),
            fontWeight = FontWeight.SemiBold,
            color = InkStrong,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            color = InkSubtle
        )
        Icon(
            Icons.Filled.ExpandMore, contentDescription = null,
            tint = InkSubtle, modifier = Modifier.rotate(rotation)
        )
    }
}

// ================================================================ small pieces


/**
 * v1.80 (Q17): "Coming up" — every pending trigger for this item, so the user can see WHEN it
 * fires next and WHY, and cancel it. Not just the snooze: a quiet re-fire, a Shop lapse-return
 * and a recurring return all answer the same question.
 * Cancel is TYPE-AWARE: cancelling a snooze must restore the original due time, or the reminder
 * would be silenced permanently.
 */
@Composable
fun ComingUpBox(item: Item, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    // v1.84 (N14 defect B): resolve the LIVE item. The editor hands this box a SNAPSHOT taken
    // when it opened, so cancelling the snooze cleared the store while the row kept rendering
    // from stale data — to the user, "nothing happened". Reading the store here makes all three
    // editors self-heal with zero call-site changes.
    val live = ItemStore.items.collectAsState().value.firstOrNull { it.id == item.id } ?: item
    val now = System.currentTimeMillis()
    // v1.84 (N14): rows come from the PURE builder in Model.kt — this box renders, it does not decide.
    val rows = comingUpRows(live, now)
    if (rows.isEmpty()) return

    var confirm by remember { mutableStateOf(false) }
    GroupBox("Coming up") {
        rows.take(5).forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.at?.let { formatDateTime(it) } ?: "\u2014",
                    Modifier.width(150.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(r.what, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                if (r.cancellable) IconButton(onClick = { confirm = true }) {
                    Icon(Icons.Filled.Close, "Cancel", tint = DangerInk, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (confirm) ConfirmDialog(
        title = "Cancel the snooze?",
        text = "The reminder returns to its original due time. If that time has already passed it simply shows as overdue.",
        // v1.84 (N15): Yes/No — the old pair was "Cancel snooze" vs "Cancel", two buttons both
        // starting with the word Cancel, one of which cancelled the cancelling.
        confirmLabel = "Yes",
        dismissLabel = "No",
        onConfirm = {
            // restore the ORIGINAL due alarm rather than leaving the item silent
            runCatching {
                ItemStore.get(live.id)?.let { cur ->
                    val restored = cur.copy(snoozedUntil = null)
                    ItemStore.upsert(restored)
                    AlarmScheduler.cancelForItem(context, live.id)
                    AlarmScheduler.scheduleForItem(context, restored)
                    Logger.e(context, "SNOOZE", null,
                        "snooze cancelled for ${live.id}; dueAt=${cur.dueAt} re-armed=${(cur.dueAt ?: 0) > now}")
                }
            }.onFailure { Logger.e(context, "SNOOZE", it, "cancel failed \u2014 snooze left in place") }
            onChanged()
        },
        onDismiss = { confirm = false }
    )
}

/** v1.80: one source of truth for the A/R/N label, shared by chips, Coming up and the editor. */
fun alertTypeLabel(letter: String): String = when (letter) {
    "A" -> "Alarm"; "R" -> "Ring"; ALERT_MUTED -> "Muted"; else -> "Notify"
}

@Composable
fun MetaChip(text: String, bg: Color, fg: Color, icon: @Composable (() -> Unit)? = null) {
    val fsD = SettingsStore.s.collectAsState().value.fsCardDetail
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.invoke()
        Text(text, style = MaterialTheme.typography.labelMedium.fs(fsD), color = fg, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(
    options: List<String>,
    selected: Int,
    accent: Color,
    onSelect: (Int) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) accent else SurfaceSubtle)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) Color.White else PillTextIdle,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    // v1.79 (Q14): optional opt-in checkbox. Defaults keep all nine existing call sites unchanged.
    checkboxLabel: String? = null,
    checkboxNote: String? = null,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    confirmEnabled: Boolean = true,
    // v1.84 (N15): optional, defaulted — every existing call site keeps its current buttons.
    dismissLabel: String = "Cancel"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text)
                if (checkboxLabel != null && onCheckedChange != null) {
                    Spacer(Modifier.padding(4.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                        Column(Modifier.weight(1f)) {
                            Text(checkboxLabel, style = MaterialTheme.typography.bodyMedium)
                            if (checkboxNote != null) Text(
                                checkboxNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSubtle
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, enabled = confirmEnabled) {
                Text(confirmLabel, color = if (confirmEnabled) OverdueRed else InkHint)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}

// ================================================================ card FX wrapper

/**
 * Shared celebrate / revive / highlight visuals for cards.
 * Wraps any card content; drives alpha, scale, slide and the green check overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaybeShrunk(shrink: Boolean, content: @Composable () -> Unit) {
    if (!shrink) { content(); return }
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        Box(Modifier.scale(0.85f)) { content() }
    }
}

@Composable
fun FxCard(
    id: Long,
    pal: TabPalette,
    modifier: Modifier = Modifier,
    vPad: Dp = 4.dp,
    content: @Composable () -> Unit
) {
    val celebratingSet by Fx.celebrating.collectAsState()
    val revivingSet by Fx.reviving.collectAsState()
    val revivedSet by Fx.justRevived.collectAsState()
    val cel = id in celebratingSet
    val rev = id in revivingSet
    val celP by animateFloatAsState(if (cel) 1f else 0f, tween(380), label = "cel")
    val revP by animateFloatAsState(if (rev) 1f else 0f, tween(340), label = "rev")
    val highlight = id in revivedSet
    val cardBg by animateColorAsState(if (highlight) pal.chipBg else SurfaceCard, tween(1100), label = "hl")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = vPad)
            .graphicsLayer {
                alpha = (1f - 0.9f * celP - 0.85f * revP).coerceIn(0f, 1f)
                val s = 1f - 0.10f * celP
                scaleX = s
                scaleY = s
                translationX = revP * 260f
            }
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box {
            content()
            if (celP > 0.02f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SuccessGreen.copy(alpha = 0.80f * celP)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check, null, tint = Color.White,
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer { scaleX = celP; scaleY = celP }
                    )
                }
            }
        }
    }
}

// ================================================================ swipe-to-move wrapper

/**
 * v1.2: swiping a card moves the item — LEFT on an Active card completes it
 * (standard countdown), RIGHT on a Done card revives it. The wrong direction
 * is disabled, so the card just springs back. The box never actually
 * dismisses; the item's own animated flows handle the visual move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeMoveRow(
    isDoneList: Boolean,
    enabled: Boolean = true,          // move gesture (cardSwipe)
    rightIsDone: Boolean = true,
    vPad: Dp = 4.dp,
    deleteEnabled: Boolean = false,   // v1.32: reverse-swipe delete for this tab
    onComplete: () -> Unit,
    onRevive: () -> Unit,
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Neither gesture armed → the card is inert.
    if (!enabled && !deleteEnabled) {
        Box(modifier) { content() }
        return
    }
    val curComplete by rememberUpdatedState(onComplete)
    val curRevive by rememberUpdatedState(onRevive)
    val curDelete by rememberUpdatedState(onDelete)
    val settings by SettingsStore.s.collectAsState()

    // v1.23: the swipe is ours. A plain horizontal drag lets shouldCommitSwipe() — the pure,
    // unit-tested rule — govern distance and flick speed.
    // v1.32: both directions are live. The move side stays as before (Active completes,
    // Done revives); the opposite side deletes when deleteEnabled. swipeOutcome() (pure,
    // unit-tested) decides which, so a wrong/disarmed side simply springs back.
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableStateOf(1f) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val ctx = LocalContext.current
    var ticked by remember { mutableStateOf(false) }

    // Which physical side is the MOVE side (Active completes, Done revives).
    val moveToRight = if (isDoneList) !rightIsDone else rightIsDone
    // A side may travel only if its own gesture is armed.
    val rightArmed = if (moveToRight) enabled else deleteEnabled
    val leftArmed = if (moveToRight) deleteEnabled else enabled

    fun outcomeFor(sign: Int): SwipeOutcome =
        swipeOutcome(sign, moveToRight, enabled, deleteEnabled)

    fun fire(sign: Int) {
        runCatching {
            when (outcomeFor(sign)) {
                SwipeOutcome.MOVE -> if (isDoneList) curRevive() else curComplete()
                SwipeOutcome.DELETE -> curDelete()
                SwipeOutcome.NONE -> {}
            }
        }.onFailure { Logger.e(ctx, "SwipeMoveRow", it, "swipe commit failed (sign=$sign, done=$isDoneList)") }
    }

    // Sign the coloured backdrop reacts to (follows the finger).
    val sign = when { offset.value > 0f -> 1; offset.value < 0f -> -1; else -> 0 }
    val shown = outcomeFor(sign)

    Box(modifier) {
        // The coloured intent behind the card, revealed only as far as the finger has moved.
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = 14.dp, vertical = vPad)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when (shown) {
                        SwipeOutcome.DELETE -> OverdueRed
                        SwipeOutcome.MOVE -> if (isDoneList) SettingsAccent else SuccessGreen
                        SwipeOutcome.NONE -> Color.Transparent
                    }
                )
                .padding(horizontal = 24.dp),
            contentAlignment = if (sign > 0) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            when (shown) {
                SwipeOutcome.DELETE -> Icon(Icons.Filled.Delete, "Delete", tint = Color.White)
                SwipeOutcome.MOVE ->
                    if (isDoneList) Icon(Icons.Filled.Undo, "Back to Active", tint = Color.White)
                    else Icon(Icons.Filled.Check, "Complete", tint = Color.White)
                SwipeOutcome.NONE -> {}
            }
        }

        Box(
            Modifier
                .offset { IntOffset(offset.value.toInt(), 0) }
                .onSizeChanged { if (it.width > 0) width = it.width.toFloat() }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // Clamp to the armed sides only; a disarmed side stays pinned at 0.
                        val raw = offset.value + delta
                        val next = raw
                            .let { if (!rightArmed) it.coerceAtMost(0f) else it }
                            .let { if (!leftArmed) it.coerceAtLeast(0f) else it }
                        scope.launch { offset.snapTo(next) }
                        // One haptic tick, exactly when the distance rule is met.
                        val past = kotlin.math.abs(next) >= width * (settings.swipeDistancePct.coerceIn(15, 70) / 100f)
                        if (past && !ticked) {
                            ticked = true
                            if (settings.swipeHaptic) runCatching {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        } else if (!past) ticked = false
                    },
                    onDragStarted = { ticked = false },
                    onDragStopped = { velocityPxPerSec ->
                        // draggable hands us real velocity in px/s; the rule wants dp/s.
                        val vDp = with(density) { velocityPxPerSec.toDp().value }
                        val endSign = when { offset.value > 0f -> 1; offset.value < 0f -> -1; else -> 0 }
                        val commit = shouldCommitSwipe(
                            offset.value, width, vDp,
                            settings.swipeDistancePct, settings.swipeVelocityDp
                        )
                        offset.animateTo(0f)
                        if (commit) fire(endSign)
                    }
                )
        ) { content() }
    }
}

// ================================================================ item card

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemCard(
    item: Item,
    pal: TabPalette,
    isDoneList: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (Item) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember(item.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pending by Engine.pendingDone.collectAsState()
    val endsAt = pending[item.id]
    val s = SettingsStore.s.collectAsState().value
    val cf = cardFieldsFor(s, item.tab)   // v1.44 UI 1: per-card element visibility
    val dens = densOfPct(s.densityPct)

    FxCard(id = item.id, pal = pal, modifier = modifier, vPad = dens.outerV) {
        Column(Modifier.padding(start = 6.dp, end = 10.dp, top = dens.innerV, bottom = dens.innerV)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cf.checkbox) {
                MaybeShrunk(dens.shrinkCheck) {
                Checkbox(
                    checked = isDoneList || endsAt != null,
                    onCheckedChange = { checked ->
                        if (isDoneList) {
                            if (!checked) Engine.startRevive(context, item)
                        } else {
                            if (checked) Engine.startComplete(context, item) else Engine.cancelPending(item.id)
                        }
                    },
                    colors = CheckboxDefaults.colors(checkedColor = pal.accent)
                )
                }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onOpen(item) }) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            // v2.8 (N44): a muted item (alert deleted from Scheduled alerts) is never a surprise.
                            (if (isMuted(item)) "🔕 " else "") + item.title,
                            style = MaterialTheme.typography.titleMedium.fs(s.fsCardTitle),
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDoneList) GreyIcon else InkPrimary,
                            textDecoration = if (isDoneList) TextDecoration.LineThrough else null
                        )
                        ItemChipList(item, pal, isDoneList)
                        // v1.15 item 4: quick +10% on active Learn cards; 100% nudges done.
                        if (item.tab == Tab.LEARN && !isDoneList) {
                            Text(
                                "+10%",
                                color = pal.accent, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        val np = (item.progress + 10).coerceAtMost(100)
                                        Engine.addOrUpdate(context, item.copy(progress = np))
                                        if (np >= 100) Ack.show("100% — mark done?") { Engine.startComplete(context, item.copy(progress = np)) }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    ItemMetaLine(item, isDoneList, s.fsCardDetail)
                }
                val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "cardChevron")
                Icon(
                    Icons.Filled.ExpandMore, contentDescription = "Expand",
                    tint = InkHint,
                    modifier = Modifier
                        .rotate(chevron)
                        .clickable { expanded = !expanded }
                )
            }

            if (endsAt != null && !isDoneList) {
                CountdownRow(endsAt, pal) { Engine.cancelPending(item.id) }
            }

            if (expanded) {
                ExpandedDetails(item, pal, isDoneList,
                    onEdit = { onOpen(item) },
                    onDelete = { confirmDelete = true },
                    onUndo = { Engine.startRevive(context, item) })
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete permanently?",
            text = "\"${item.title}\" will be removed for good. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { Engine.delete(context, item) },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun ItemMetaLine(item: Item, isDoneList: Boolean, fsD: Float) {
    val now = System.currentTimeMillis()
    if (isDoneList) {
        item.doneAt?.let {
            Text(
                "Done ${formatDateTime(it)}",
                style = MaterialTheme.typography.bodySmall.fs(fsD),
                color = GreyIcon
            )
        }
        item.returnAt?.let {
            Text(
                "Returns ${formatDate(it)}",
                style = MaterialTheme.typography.bodySmall.fs(fsD),
                color = ShopInk
            )
        }
    } else {
        val cf = cardFieldsFor(SettingsStore.s.collectAsState().value, item.tab)
        if (cf.dateTime) item.dueAt?.let {
            val overdue = isOverdueDay(it, now)
            Text(
                (if (overdue) "Overdue · " else "Due ") +
                        (if (item.dueHasTime) formatDateTime(it) else formatDate(it)),
                style = MaterialTheme.typography.bodySmall.fs(fsD),
                fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
                color = if (overdue) OverdueRed else InkSubtle
            )
        }
    }
}

@Composable
private fun ItemChipList(item: Item, pal: TabPalette, isDoneList: Boolean) {
    val chips = mutableListOf<@Composable () -> Unit>()
    val settings = SettingsStore.s.collectAsState().value
    val cf = cardFieldsFor(settings, item.tab)   // v1.44 UI 1
    // v1.49 item 4: never repeat as a chip what the list is already grouped by.
    val suppress = suppressedChip(sortOf(settings, item.tab), item.tab)
    // v1.49 item 2: Best U.P rank — labels only, the list is NOT reordered.
    val upRank = if (item.tab == Tab.SHOP)
        bestUpRanks(ItemStore.items.collectAsState().value)[item.id] else null
    // v1.80 (N5): Reminder Type chip — Alarm and Ring only; Notify is what every item already is.
    if (cf.alertType && showAlertChip(item.alertType)) {
        val hex = cardAlertColorFor(settings, item.tab, item.alertType)
        val fg = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(InkPrimary)
        val style = cardAlertStyleFor(settings, item.tab)
        chips.add {
            if (style == "TEXT") MetaChip(alertTypeLabel(item.alertType), fg.copy(alpha = 0.14f), fg)
            else MetaChip("", fg.copy(alpha = 0.14f), fg) {
                Icon(
                    if (item.alertType == "A") Icons.Filled.Alarm else Icons.Filled.VolumeUp,
                    alertTypeLabel(item.alertType), tint = fg, modifier = Modifier.size(14.dp)
                )
            }
        }
    }
    if (cf.repeat) repeatLabel(item)?.let { lbl0 ->
        val lbl = if (item.done && item.dueAt != null)
            "returns " + formatDay(item.dueAt) else lbl0
        chips.add { MetaChip("↻ " + lbl.substringBefore(" · "), GreenSoft, ShopTeal) }
    }
    if ((item.tab == Tab.SHOP || item.tab == Tab.TASKS) && !item.group.isNullOrBlank() && suppress != "GROUP") {
        chips.add { MetaChip(item.group, TasksSoft, PurpleInk) }
    }
    if (item.tab == Tab.LEARN && !item.topic.isNullOrBlank() && suppress != "TOPIC") {
        chips.add { MetaChip(item.topic, TasksSoft, PurpleInk) }
    }
    if (upRank != null) chips.add {
        if (upRank == 1) MetaChip(upRankLabel(upRank), GreenSoft, ShopInk)
        else MetaChip(upRankLabel(upRank), SurfaceSubtle, InkHint)
    }
    item.priority?.let { p ->
        // v1.15 item 5: the Medium chip is governed by settings (Urgent/High/Low always show).
        // v1.71 (Q9): Medium follows the global switch + this tab's override.
        val show = cf.priority && showPriorityTag(p, SettingsStore.s.value, item.tab)
        if (show) chips.add { MetaChip(p.label, priorityContainer(p), priorityColor(p)) }
    }
    if (item.tab == Tab.LEARN && item.progress > 0) {
        chips.add { MetaChip("${item.progress}%", BlueSoft, SettingsAccent) }
    }
    if (item.tab == Tab.SHOP && !item.done) {
        lastPrice(item)?.let { lp ->
            chips.add { MetaChip("last ₹${fmtExact(lp)}", LearnSoft, AmberInk) }
        }
        if (isOosToday(item)) chips.add { MetaChip("OOS today", UrgentSoft, DangerSoft) }
    }
    if (item.tab == Tab.SHOP) {
        val perUnit = run {
            // v1.15 item 15: ₹/unit when both parse and a unit is chosen.
            val p = item.price?.replace(",", "")?.replace("₹", "")?.trim()?.toDoubleOrNull()
            val q = item.quantity?.trim()?.takeWhile { it.isDigit() || it == '.' }?.toDoubleOrNull()
            if (p != null && q != null && q > 0 && item.unit != null) " · ₹${fmtUnitPrice(p / q)}/${item.unit}" else ""
        }
        val qtyPrice = listOfNotNull(
            item.quantity?.takeIf { it.isNotBlank() }?.let { "Qty $it" + (item.unit?.let { u -> " $u" } ?: "") },
            item.price?.takeIf { it.isNotBlank() }?.let { "₹$it" }
        ).joinToString(" · ") + perUnit
        if (qtyPrice.isNotBlank()) chips.add { MetaChip(qtyPrice, SurfaceSubtle, PillTextIdle) }
        val shop = item.shopName?.takeIf { it.isNotBlank() } ?: ""
        if (shop.isNotBlank() && suppress != "SHOP") chips.add { MetaChip(shop, pal.chipBg, pal.onChip) }
        // v1.39 Phase B: on completed items, surface the captured checkout — MRP, Buy, Discount, unit price.
        if (isDoneList) {
            val lp = item.priceHistory.lastOrNull()
            if (lp != null && lp.price > 0.0) {
                chips.add { MetaChip("MRP ₹${fmtExact(lp.price)}", SurfaceSubtle, PillTextIdle) }
                if (lp.paid > 0.0) chips.add { MetaChip("Buy ₹${fmtExact(lp.paid)}", GreenSoft, ShopInk) }
                if (lp.discountPct > 0.0) chips.add {
                    MetaChip("Disc ${fmtExact(lp.discountPct)}% (₹${fmtExact(lp.price - lp.paid)})", LearnSoft, AmberInk)
                }
                if (lp.unitPrice > 0.0 && lp.unit.isNotBlank()) chips.add {
                    MetaChip("₹${fmtUnitPrice(lp.unitPrice)}/${lp.unit}", TasksSoft, PurpleInk)
                }
            }
        }
        if (isDoneList && item.lapseValue != null && item.lapseUnit != null) {
            chips.add { MetaChip("Lapse ${lapseLabel(item.lapseValue, item.lapseUnit)}", BlueSoft, SettingsAccent) }
        }
        if (isDoneList) item.expiryAt?.let {
            chips.add { MetaChip("Exp ${formatDate(it)}", UrgentSoft, OverdueRed) }
        }
        if (item.personal) {
            chips.add {
                MetaChip("Personal", PillDark, Color.White, icon = {
                    Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                })
            }
        }
    }
    if (item.tab == Tab.LEARN) {
        item.platform?.takeIf { it.isNotBlank() }?.let {
            chips.add { MetaChip(it, pal.chipBg, pal.onChip) }
        }
    }
    chips.forEach { it() }
}

@Composable
fun CountdownRow(endsAt: Long, pal: TabPalette, onUndo: () -> Unit) {
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (true) {
            nowTick = System.currentTimeMillis()
            if (nowTick >= endsAt) break
            delay(200)
        }
    }
    val secs = ((endsAt - nowTick + 999) / 1000).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 46.dp, top = 6.dp, end = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(pal.chipBg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Alarm, null, tint = pal.onChip, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Moving to Done in ${secs}s",
            style = MaterialTheme.typography.labelLarge,
            color = pal.onChip,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onUndo) { Text("UNDO", fontWeight = FontWeight.Bold, color = pal.accent) }
    }
}

@Composable
private fun ExpandedDetails(
    item: Item,
    pal: TabPalette,
    isDoneList: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit
) {
    val context = LocalContext.current
    Column(Modifier.padding(start = 46.dp, top = 8.dp, end = 4.dp)) {
        if (item.notes.isNotBlank()) {
            Text(item.notes, style = MaterialTheme.typography.bodyMedium, color = PillTextIdle)
        }
        if (item.tab == Tab.LEARN && !item.url.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable {
                        val raw = item.url.trim()
                        val full = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full))) }
                    }
            ) {
                Icon(Icons.Filled.Link, null, tint = SettingsAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    item.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SettingsAccent,
                    textDecoration = TextDecoration.Underline
                )
            }
        }
        Text(
            "Added ${formatDateTime(item.createdAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = InkHint,
            modifier = Modifier.padding(top = 6.dp)
        )
        Row(modifier = Modifier.padding(top = 2.dp)) {
            if (isDoneList) {
                TextButton(onClick = onUndo) {
                    Icon(Icons.Filled.Undo, null, modifier = Modifier.size(16.dp), tint = pal.accent)
                    Spacer(Modifier.width(4.dp))
                    Text("Undo", color = pal.accent, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp), tint = pal.accent)
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", color = pal.accent, fontWeight = FontWeight.Bold)
                }
            }
            // v1.48: the card's own Delete is always available. The global "Show Clear-All Icon"
            // switch governs the BULK trash icon on Done group headers, not this button.
            // v1.86 (N21): every schedule fact about this item in one popup — future
            // occurrences (same engine rows as the editor preview), auto-rolled Missed
            // Alerts (N20) and the live Coming up box. Self-contained: no plumbing.
            var showViewAlert by remember { mutableStateOf(false) }
            TextButton(onClick = { showViewAlert = true }) {
                Icon(Icons.Filled.NotificationsActive, null, modifier = Modifier.size(16.dp), tint = pal.accent)
                Spacer(Modifier.width(4.dp))
                Text("View Alert", color = pal.accent, fontWeight = FontWeight.Bold)
            }
            if (showViewAlert) ViewAlertDialog(item) { showViewAlert = false }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp), tint = OverdueRed)
                Spacer(Modifier.width(4.dp))
                Text("Delete", color = OverdueRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ================================================================ PIN dialogs

@Composable
fun PinDialog(
    title: String,
    onDismiss: () -> Unit,
    onForgot: (() -> Unit)? = null,
    onSuccess: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Enter your 4-digit PIN.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) { pin = it; error = false } },
                    label = { Text("PIN") },
                    isError = error,
                    supportingText = { if (error) Text("Wrong PIN, try again", color = OverdueRed) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                if (onForgot != null) {
                    TextButton(onClick = { onDismiss(); onForgot() }) {
                        Text("Forgot PIN?", color = SettingsAccent)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin.length == 4 && PinStore.check(pin)) { onSuccess(); onDismiss() } else error = true
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SetPinDialog(requireOld: Boolean, onDismiss: () -> Unit, onDone: () -> Unit = {}) {
    var old by remember { mutableStateOf("") }
    var one by remember { mutableStateOf("") }
    var two by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun digitsOnly(s: String) = s.length <= 4 && s.all { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (requireOld) "Change PIN" else "Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (requireOld) {
                    OutlinedTextField(
                        value = old, onValueChange = { if (digitsOnly(it)) old = it },
                        label = { Text("Current PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
                OutlinedTextField(
                    value = one, onValueChange = { if (digitsOnly(it)) one = it },
                    label = { Text("New 4-digit PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = two, onValueChange = { if (digitsOnly(it)) two = it },
                    label = { Text("Repeat new PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                error?.let { Text(it, color = OverdueRed, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    requireOld && !PinStore.check(old) -> error = "Current PIN is wrong"
                    one.length != 4 -> error = "PIN must be 4 digits"
                    one != two -> error = "PINs don't match"
                    else -> { PinStore.setPin(one); onDone(); onDismiss() }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ================================================================ date & time pickers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(
    label: String,
    value: Long?,
    accent: Color,
    onChange: (Long?) -> Unit
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<Long?>(null) }

    PickerRow(label, value?.let { formatDateTime(it) }, accent,
        onClick = { showDate = true },
        onClear = if (value != null) ({ onChange(null) }) else null)

    if (showDate) {
        // v1.8 crash fix: brand-new picker state on EVERY open — never reuse across openings.
        key(showDate, value) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.let { toUtcDateMillis(it) } ?: toUtcDateMillis(System.currentTimeMillis())
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = state.selectedDateMillis
                    showDate = false
                    if (pickedDate != null) showTime = true
                }) { Text("Next") }
            },
            dismissButton = {
                Row {
                    // v1.22 item 5: one tap back from a distant month.
                    TextButton(onClick = {
                        runCatching { state.selectedDateMillis = toUtcDateMillis(System.currentTimeMillis()) }
                    }) { Text("Today") }
                    TextButton(onClick = { showDate = false }) { Text("Cancel") }
                }
            }
        ) { DatePicker(state = state) }
        }
    }

    if (showTime) {
        val init = value?.toLocalDateTime()
        val ts = rememberTimePickerState(
            initialHour = init?.hour ?: 9, initialMinute = init?.minute ?: 0,
            is24Hour = use24Now()   // v1.70 (Q8): follow the app setting, not the OS clock
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(label) },
            text = { TimePicker(state = ts) },
            confirmButton = {
                TextButton(onClick = {
                    showTime = false
                    pickedDate?.let { onChange(combineDateTime(it, ts.hour, ts.minute)) }
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    minutes: Int?,
    accent: Color,
    onChange: (Int?) -> Unit
) {
    val context = LocalContext.current
    PickerRow(
        label, minutes?.let { formatMinutes(it) }, accent,
        onClick = {
            val h0 = (minutes ?: 1080) / 60
            val m0 = (minutes ?: 1080) % 60
            try {
                android.app.TimePickerDialog(
                    context, { _, h, m -> onChange(h * 60 + m) }, h0, m0, use24Now()
                ).show()
            } catch (t: Throwable) {
                android.widget.Toast.makeText(context, "Couldn't open the time picker — try again", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onClear = if (minutes != null) ({ onChange(null) }) else null,
        icon = Icons.Filled.Schedule
    )
}

/** v1.24 item 4: the Time field in every edit popup — it built AM/PM by hand and so
 *  ignored the global setting entirely, which is why the popups stayed 12-hour in all tabs. */
/** v1.70 (Q8): the app's Time Format setting, resolved once. PHONE falls back to the OS clock. */
fun use24Now(): Boolean = when (TIME_FORMAT) {
    "H24" -> true
    "H12" -> false
    else -> PHONE_IS_24H
}

fun formatMinutes(mins: Int): String {
    val h = ((mins / 60) % 24 + 24) % 24
    val m = ((mins % 60) + 60) % 60
    val use24 = use24Now()
    if (use24) return "%02d:%02d".format(h, m)
    val ampm = if (h >= 12) "PM" else "AM"
    val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
    return "%d:%02d %s".format(h12, m, ampm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: Long?,
    accent: Color,
    onChange: (Long?) -> Unit
) {
    // v1.8.1: classic platform DatePickerDialog — View-based, decades-stable, and
    // launched inside try/catch so a dialog failure can never take the app down.
    val context = LocalContext.current
    PickerRow(label, value?.let { formatDate(it) }, accent,
        onClick = {
            val init = (value ?: System.currentTimeMillis()).toLocalDate()
            try {
                android.app.DatePickerDialog(
                    context,
                    { _, y, m, d ->
                        try {
                            val picked = java.time.LocalDate.of(y, m + 1, d)
                                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            onChange(picked)
                        } catch (t: Throwable) {
                            Logger.e(context, "PICKER", t)
                            android.widget.Toast.makeText(context, "That date didn't stick — try again", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    init.year, init.monthValue - 1, init.dayOfMonth
                ).also { dlg ->
                    // v1.24 item 3: the Material3 picker got a Today link — this one needs it too.
                    runCatching {
                        dlg.setButton(
                            android.content.DialogInterface.BUTTON_NEUTRAL, "Today"
                        ) { _, _ ->
                            runCatching {
                                onChange(startOfDayMs(System.currentTimeMillis()))
                            }.onFailure { t -> Logger.e(context, "PICKER", t, "Today shortcut failed") }
                        }
                    }.onFailure { t -> Logger.e(context, "PICKER", t, "Today button unavailable") }
                }.show()
            } catch (t: Throwable) {
                Logger.e(context, "PICKER", t)
                android.widget.Toast.makeText(context, "Couldn't open the date picker — try again", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onClear = if (value != null) ({ onChange(null) }) else null)
}

@Composable
private fun PickerRow(
    label: String,
    valueText: String?,
    accent: Color,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Event
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceSubtle,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                valueText ?: label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (valueText != null) InkPrimary else GreyIcon,
                modifier = Modifier.weight(1f)
            )
            if (onClear != null) {
                IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, "Clear", tint = GreyIcon, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ================================================================ empty state

@Composable
fun EmptyState(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 60.dp), contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = InkHint)
    }
}


/** v1.9: translucent header dropdown chip (sort / Shop filter). */
@Composable
fun HeaderDropdown(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.22f))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                options.getOrElse(selected) { options.first() },
                color = Color.White, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(opt, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal)
                            if (i == selected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp), tint = ShopInk)
                            }
                        }
                    },
                    onClick = { open = false; onSelect(i) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- v1.58: shared extra-alerts editor


/** v1.68: a titled, bordered group — Krishna's group-box standard for the editor chips. */
@Composable
fun GroupBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, InkSubtle, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}



// ================================================================ v1.86 (N20/N21)

/** N20: the auto-roll log — newest first, capped at MISSED_CAP. Empty state says so plainly. */
@Composable
fun MissedAlertsBox(item: Item) {
    GroupBox("Missed Alerts") {
        val rows = missedRows(item)
        if (rows.isEmpty()) {
            Text("No missed alerts.", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        } else rows.forEach { t ->
            Text("Missed \u2022 " + formatDateTime(t), style = MaterialTheme.typography.bodySmall, color = InkSubtle)
        }
    }
}

/**
 * N21: the View Alert popup — Future Occurrences come from the SAME RepeatPreviewLine /
 * ViewAllDialog engine as the editor preview (one source of truth, per N6), then the N20
 * Missed Alerts log, then the live Coming up box. Read-only.
 */
@Composable
fun ViewAlertDialog(item: Item, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("View Alert", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (item.repeatMode != "OFF") {
                    val tm = item.dueAt?.takeIf { item.dueHasTime }?.let {
                        val c = java.util.Calendar.getInstance().apply { timeInMillis = it }
                        c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
                    }
                    GroupBox("Future Occurrences") {
                        RepeatPreviewLine(
                            item.repeatMode, item.repeatDays.toSet(), item.repeatN.toString(),
                            item.repeatUnit, item.repeatOrd, item.repeatDow, item.repeatOrdList,
                            item.dueAt, tm, item.spacedStep, item.repeatCount, item.repeatDone
                        )
                        var showAll by remember { mutableStateOf(false) }
                        TextButton(onClick = { showAll = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("View all", style = MaterialTheme.typography.labelLarge)
                        }
                        if (showAll) ViewAllDialog(
                            item.repeatMode, item.repeatDays.toSet(), item.repeatN.toString(),
                            item.repeatUnit, item.repeatOrd, item.repeatDow, item.repeatOrdList,
                            item.dueAt, tm, item.spacedStep, item.repeatCount, item.repeatDone
                        ) { showAll = false }
                    }
                }
                MissedAlertsBox(item)
                ComingUpBox(item)
            }
        }
    )
}
