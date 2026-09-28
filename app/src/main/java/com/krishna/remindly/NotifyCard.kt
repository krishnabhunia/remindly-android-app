package com.krishna.remindly

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v1.81 (Q15): which item a notification tap asked to open.
 *
 * Deliberately a tiny holder rather than state inside MainActivity, because the request arrives in
 * onCreate AND in onNewIntent, and both must reach the same composable.
 */
object NotifyOpen {
    val pending = MutableStateFlow(0L)
    fun request(id: Long) { if (id > 0L) pending.value = id }
    fun clear() { pending.value = 0L }
}

/**
 * v1.81 (Q15): the quiet card. Same look as the alarm card, none of the alarm machinery — no
 * AlarmService, no wake lock, no show-over-lockscreen. Choosing Notify is choosing not to be taken
 * over; this appears only because the user tapped, which is not a takeover.
 *
 * Three buttons, in the order Krishna specified: Dismiss, Done, Snooze.
 */
@Composable
fun NotifyCard() {
    val context = LocalContext.current
    val id by NotifyOpen.pending.collectAsState()
    if (id <= 0L) return
    val item = ItemStore.get(id)

    // The item may have been completed or deleted between the notification firing and the tap.
    if (item == null || item.deletedAt != null || item.done) {
        Feedback.toast(context, "That reminder is no longer on your list")
        runCatching {
            Logger.e(context, "NOTIFY", null, "tap targeted item $id which is gone or already done")
        }
        NotifyOpen.clear()
        return
    }

    Dialog(onDismissRequest = { NotifyOpen.clear() }) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color.Transparent) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(28.dp))
                    // v1.81: Q16's palette — indigo, because this is the quietest alert type.
                    .background(Brush.linearGradient(alertCardGradient(item.alertType)))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                item.dueAt?.let {
                    Text(formatDateTime(it), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
                Spacer(Modifier.padding(2.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { NotifyOpen.clear() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("Dismiss", color = alertActionColor("DISMISS"), fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = {
                            Engine.completeNow(context, item.id)
                            NotifyOpen.clear()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("Done", color = alertActionColor("DONE"), fontWeight = FontWeight.Bold) }
                }
                Button(
                    onClick = {
                        val mins = snoozeMinutes(SettingsStore.s.value)
                        val fireAt = snoozeTargetMs(SettingsStore.s.value)
                        AlarmScheduler.scheduleAt(context, fireAt, AlarmScheduler.TYPE_DUE, item.id)
                        // v1.81: same persistence as every other snooze path (Q17), so it shows in
                        // "Coming up" and can be cancelled there.
                        ItemStore.get(item.id)?.let { ItemStore.upsert(it.copy(snoozedUntil = fireAt)) }
                        Logger.e(context, "SNOOZE", null, "item ${item.id} snoozed to $fireAt from the notify card")
                        Feedback.toast(context, "Snoozed \u00b7 " + Alerts.snoozeLabel(mins))
                        NotifyOpen.clear()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(
                        "Snooze " + Alerts.snoozeLabel(snoozeMinutes(SettingsStore.s.value)),
                        color = alertActionColor("SNOOZE"), fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
