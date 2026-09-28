package com.krishna.remindly

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stores.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val mode = intent.getStringExtra(AlarmService.EXTRA_MODE) ?: AlarmService.MODE_ITEM
        val itemId = intent.getLongExtra(AlarmService.EXTRA_ITEM_ID, 0L)
        val kind = intent.getStringExtra(AlarmService.EXTRA_KIND) ?: ""
        val place = intent.getStringExtra(AlarmService.EXTRA_PLACE) ?: ""

        val act = this
        setContent {
            RemindlyTheme {
                // v1.56 items 2+4: partial card (~62% height) over a dim scrim instead of a full
                // takeover; tap OUTSIDE = snooze (default 90 min, customizable in settings).
                AlarmCardShell(onOutside = {
                    // v1.84 (N13, option 1): double-tap is the SAME quiet gesture as the Quiet
                    // button, so it takes the same transition — alertType -> N, snoozedUntil
                    // persisted. (v1.68 left the record untouched, which predates Q17: the defer
                    // was invisible in Coming up and died on reboot.) Calls keep v1.68 behaviour.
                    // v1.86 (N19): a TEST card must leave ZERO footprint — the old path would
                    // have armed a junk TYPE_DUE_DEMOTED alarm for id 0 on an outside tap.
                    if (mode == AlarmService.MODE_TEST) {
                        Feedback.toast(act, "Test \u2014 no action taken")
                        act.stopAndClose()
                        return@AlarmCardShell
                    }
                    if (mode == AlarmService.MODE_SHOP) {
                        // v2.05: no item/call record behind a shop card — quiet = just close.
                        Feedback.toast(act, "Shop reminder dismissed")
                        act.stopAndClose()
                        return@AlarmCardShell
                    }
                    val fireAt = snoozeTargetMs(SettingsStore.s.value)
                    when (mode) {
                        AlarmService.MODE_CALL ->
                            AlarmScheduler.scheduleAt(act, fireAt, AlarmScheduler.TYPE_CALL_DEMOTED, itemId)
                        else -> runCatching {
                            AlarmScheduler.scheduleAt(act, fireAt, AlarmScheduler.TYPE_DUE_DEMOTED, itemId)
                            ItemStore.get(itemId)?.let { ItemStore.upsert(quietDemote(it, fireAt)) }
                        }.onFailure {
                            // Never leave the item silenced with nothing scheduled.
                            Logger.e(act, "SNOOZE", it, "double-tap quiet persist failed \u2014 re-fire still scheduled")
                        }
                    }
                    Feedback.toast(act, quietSnoozeToast(fireAt))
                    Logger.e(act, "ALERT", null, "double-tap-outside \u2192 quiet re-fire in " +
                        Alerts.snoozeLabel(snoozeMinutes(SettingsStore.s.value)))
                    act.stopAndClose()
                }) {
                    when (mode) {
                        AlarmService.MODE_CALL -> CallAlarmScreen(itemId)
                        // v2.05 (N37): a shop arrival — itemId is the SHOP id.
                        AlarmService.MODE_SHOP -> ShopArrivalAlarmScreen(itemId)
                        // v1.86 (N19): the third sample — the REAL alarm card, same layout,
                        // gradient, pill and four buttons; every action is toast + close.
                        AlarmService.MODE_TEST -> TestAlarmScreen()
                        else -> ItemAlarmScreen(itemId, kind)
                    }
                }
            }
        }
    }
}

private fun Activity.stopAndClose() {
    AlarmService.stop(this)
    finish()
}

/** v1.56: scrim + centered card. The card swallows its own taps; only true outside-taps snooze. */
@Composable
private fun AlarmCardShell(onOutside: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                // v1.62: a single graze must not snooze — only a deliberate DOUBLE tap does.
                detectTapGestures(onDoubleTap = { onOutside() })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.62f)
                .clip(RoundedCornerShape(28.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) { content() }
    }
}

private fun snoozeLbl(m: Int) = if (m < 60) "$m min" else "${m / 60} h" + (if (m % 60 != 0) " ${m % 60} m" else "")

// ================================================================ single item

@Composable
private fun ItemAlarmScreen(itemId: Long, kind: String) {
    val context = LocalContext.current
    val activity = context as Activity
    val item = ItemStore.get(itemId)
    val pal = palFor(item?.tab)

    Column(
        modifier = Modifier
            .fillMaxSize()
            // v1.80 (Q16): background says the TYPE that fired; the tab survives as the icon tint.
            .background(Brush.linearGradient(alertCardGradient(item?.alertType ?: "A")))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Alarm, null, tint = Color.White, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            Alerts.kindTitle(kind),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.85f)
        )
        Text(
            item?.title ?: "Reminder",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        item?.dueAt?.let {
            Text(
                "Due ${formatDateTime(it)}",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        item?.priority?.let {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(it.label, color = priorityColor(it), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(30.dp))
        if (item != null && !item.done) {
            // v1.68 (Amendment 5): FOUR buttons, two rows. White containers deliberate — this
            // card is dark-by-design (v1.67's SurfaceCard here was a latent dark-mode bug, fixed).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val fireAt = snoozeTargetMs(SettingsStore.s.value)
                    AlarmScheduler.scheduleAt(context, fireAt, AlarmScheduler.TYPE_DUE, item.id)
                    ItemStore.get(item.id)?.let { ItemStore.upsert(it.copy(snoozedUntil = fireAt)) }
                    Logger.e(context, "SNOOZE", null, "item ${item.id} snoozed to $fireAt")
                    // v1.83 (N10): was snoozeToast(90, ...) — a bare literal that happened to be
                    // right only while the setting was 90. Reads the configured value like every
                    // other path, so the toast can never contradict the alarm it just scheduled.
                    Feedback.toast(context, snoozeToast(snoozeMinutes(SettingsStore.s.value), fireAt))
                    activity.stopAndClose()
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Snooze " + Alerts.snoozeLabel(snoozeMinutes(SettingsStore.s.value)), color = alertActionColor("SNOOZE"), fontWeight = FontWeight.Bold)
                }
                Button(onClick = {
                    // v1.82 (N8): was "fire a notification RIGHT NOW and defer nothing", which is
                    // close to a renamed Dismiss — the user has just been looking at the card, so
                    // the notification carried no new information. Now it silences AND defers,
                    // returning quietly as a notification instead of an alarm.
                    val fireAt = snoozeTargetMs(SettingsStore.s.value)
                    runCatching {
                        AlarmScheduler.scheduleAt(context, fireAt, AlarmScheduler.TYPE_DUE_DEMOTED, item.id)
                        // v1.84 (N13): persist the demote on the RECORD, not just the intent —
                        // the editor chip and Coming up read the item, which used to keep
                        // claiming Alarm/Ring while the schedule quietly said Notify.
                        ItemStore.get(item.id)?.let { ItemStore.upsert(quietDemote(it, fireAt)) }
                        Logger.e(context, "SNOOZE", null, "item ${item.id} quiet: alertType -> N, re-fires $fireAt (N13)")
                    }.onFailure {
                        // Never leave the item silenced with nothing scheduled.
                        Logger.e(context, "SNOOZE", it, "quiet defer failed — alert left as-is")
                    }
                    Feedback.toast(context, "Quiet · " + Alerts.snoozeLabel(snoozeMinutes(SettingsStore.s.value)))
                    activity.stopAndClose()
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Quiet " + Alerts.snoozeLabel(snoozeMinutes(SettingsStore.s.value)),
                        color = alertActionColor("DEMOTE"), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    Feedback.toast(context, dismissToast(item.title))
                    activity.stopAndClose()
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Dismiss", color = alertActionColor("DISMISS"), fontWeight = FontWeight.Bold)
                }
                Button(onClick = {
                    Engine.completeNow(context, item.id)
                    Feedback.toast(context, doneToastItem(item.title, item.tab))
                    activity.stopAndClose()
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Done", color = alertActionColor("DONE"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = {
                Feedback.toast(context, item?.let { dismissToast(it.title) } ?: "Dismissed \\u2014 reminder stays as it was")
                activity.stopAndClose()
            }, colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Dismiss", color = alertActionColor("DISMISS"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnoozeChips(onSnooze: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(5, 10, 15, 30, 60).forEach { m ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.25f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                TextButton(onClick = { onSnooze(m) }, modifier = Modifier.height(20.dp)) {
                    Text(
                        if (m < 60) "$m min" else "1 h",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ================================================================ shop list



@Composable
private fun CallAlarmScreen(callId: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val r = CallStore.calls.value.firstOrNull { it.id == callId }
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Call back", style = MaterialTheme.typography.titleMedium, color = Color(0xFFB0B0C0))   // hex-ok(Q2): alarm card is dark-by-design
        Spacer(Modifier.height(8.dp))
        Text(
            r?.display ?: "Missed call",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(30.dp))
        Button(onClick = {
            AlarmService.stop(context)
            r?.let { CallActions.dial(context, it.number) }
            (context as? android.app.Activity)?.finish()
        }) { Text("Call now", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            AlarmService.stop(context)
            r?.let {
                CallEngine.completeFromNotification(context, it.id)
                Feedback.toast(context, doneToastCall(it.display))
            }
            (context as? android.app.Activity)?.finish()
        }) { Text("Done") }
        Spacer(Modifier.height(10.dp))
        // v1.66: snoozes pruned — Call now · Done · Dismiss.
        OutlinedButton(onClick = {
            AlarmService.stop(context)
            Feedback.toast(context, closeCallToast())
            (context as? android.app.Activity)?.finish()
        }) { Text("Dismiss") }
    }
}




/**
 * v2.05 (N37): the shop-arrival card. Same shell as the item/call cards; the body is the shop name
 * and its pending buy items. "Open list" hands over to the Buy Now view (N38) for that shop;
 * "Snooze" re-arms one arrival alert after the cooldown; "Dismiss" just closes.
 */
@Composable
private fun ShopArrivalAlarmScreen(shopId: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shop = runCatching { ShopStore.get(shopId) }.getOrNull()
    val pending = runCatching { buyNowItems(ItemStore.items.value, shop) }.getOrDefault(emptyList())
    val general = pending.filter { !it.personal }
    val personal = pending.size - general.size
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ARRIVED AT", style = MaterialTheme.typography.titleMedium, color = Color(0xFFB0B0C0))   // hex-ok(Q2): alarm card is dark-by-design
        Spacer(Modifier.height(8.dp))
        Text(
            shop?.name ?: "Your shop",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            color = Color.White, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (pending.isEmpty()) "Your list here is clear"
            else "${pending.size} buy item${if (pending.size > 1) "s" else ""} waiting",
            style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD6D6E2)   // hex-ok(Q2)
        )
        if (general.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            general.take(5).forEach {
                Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            if (general.size > 5) Text("… and ${general.size - 5} more",
                style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD6D6E2))   // hex-ok(Q2)
        }
        if (personal > 0) Text("🔒 $personal personal item${if (personal > 1) "s" else ""} (open app)",
            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD6D6E2))       // hex-ok(Q2)
        Spacer(Modifier.height(30.dp))
        Button(onClick = {
            AlarmService.stop(context)
            runCatching { BuyNow.arm(context, shopId) }
            runCatching { context.startActivity(Alerts.buyNowIntent(context, shopId)) }
                .onFailure { Logger.e(context, "ALERT", it, "open Buy Now failed") }
            (context as? android.app.Activity)?.finish()
        }) { Text("Open list", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            AlarmService.stop(context)
            runCatching { Alerts.snoozeShopArrival(context, shopId) }
                .onFailure { Logger.e(context, "ALERT", it, "shop arrival snooze failed") }
            (context as? android.app.Activity)?.finish()
        }) { Text("Snooze") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            AlarmService.stop(context)
            Feedback.toast(context, "Shop reminder dismissed")
            (context as? android.app.Activity)?.finish()
        }) { Text("Dismiss") }
    }
}

/**
 * v1.86 (N19): the Settings "Test Alarm" card — the EXACT ItemAlarmScreen layout (gradient,
 * icon, kind line, title, due line, priority pill, the same four buttons with the same labels
 * and colours) over synthetic content. Every button and the outside tap: toast + close, nothing
 * scheduled, nothing touched. TEST-labelled so it can never be mistaken for a real reminder.
 */
@Composable
private fun TestAlarmScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val mins = snoozeMinutes(SettingsStore.s.value)
    fun testTap() {
        Feedback.toast(context, "Test \u2014 no action taken")
        activity.stopAndClose()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(alertCardGradient("A")))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Alarm, null, tint = Color.White, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            "TEST \u00b7 Reminder",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.85f)
        )
        Text(
            "Submit the report",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            "Due ${formatDateTime(System.currentTimeMillis())}",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 6.dp)
        )
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Text(Priority.URGENT.label, color = priorityColor(Priority.URGENT), fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { testTap() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Snooze " + Alerts.snoozeLabel(mins), color = alertActionColor("SNOOZE"), fontWeight = FontWeight.Bold)
            }
            Button(onClick = { testTap() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Quiet " + Alerts.snoozeLabel(mins), color = alertActionColor("DEMOTE"), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { testTap() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Dismiss", color = alertActionColor("DISMISS"), fontWeight = FontWeight.Bold)
            }
            Button(onClick = { testTap() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Done", color = alertActionColor("DONE"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Test buttons only close this card \u2014 nothing touches your reminders.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center
        )
    }
}
