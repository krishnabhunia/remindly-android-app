package com.krishna.remindly

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v1.71 (N2): surfacing non-fatal degrades.
 *
 * Remindly guards every risky call and carries on when one fails, writing the reason to
 * Error Logs. That is correct engineering but invisible: an alarm that silently became
 * inexact still looks like the app simply being unreliable. This makes the live ones visible
 * where the user actually is, while Error Logs stays the full historical record.
 *
 * A degrade is raised either by an OS probe ([refresh]) or at the point of failure
 * ([raise] from a catch block), and clears itself the moment the condition resolves.
 */
object Degrades {

    // ---- codes ----
    const val EXACT_ALARM = "EXACT_ALARM"
    const val BATTERY = "BATTERY"
    const val CONTACTS = "CONTACTS"
    const val NOTIFS = "NOTIFS"
    const val SYNC = "SYNC"
    const val CALENDAR = "CALENDAR"

    val active = MutableStateFlow<Set<String>>(emptySet())

    /** Dismissing hides the strip for this session only; the Settings dot stays until fixed. */
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    fun raise(context: Context, code: String, why: String) {
        if (code in active.value) return
        active.value = active.value + code
        runCatching { Logger.e(context, "DEGRADE", null, "$code raised — $why") }
    }

    fun clear(code: String) {
        if (code !in active.value) return
        active.value = active.value - code
        dismissed.value = dismissed.value - code
    }

    fun dismiss(code: String) { dismissed.value = dismissed.value + code }

    /** Codes worth showing on [tab] right now (dismissed ones excluded). */
    fun visibleFor(tab: Tab?): List<String> {
        val d = dismissed.value
        return active.value.filter { it !in d && tab in tabsFor(it) }.sorted()
    }

    private fun tabsFor(code: String): Set<Tab?> = when (code) {
        CONTACTS -> setOf(null)                                   // Calls only
        CALENDAR -> setOf(Tab.TASKS)
        else -> setOf(Tab.TASKS, Tab.SHOP, Tab.LEARN, null)       // affects everything
    }

    fun label(code: String): String = when (code) {
        EXACT_ALARM -> "Alarms may fire late — exact alarms are switched off for Remindly."
        BATTERY -> "Alarms may be delayed — battery optimisation is on for Remindly."
        CONTACTS -> "Missed calls will show numbers, not names — contacts access is off."
        NOTIFS -> "Reminders can't be shown — notifications are blocked for Remindly."
        SYNC -> "Cloud sync isn't running — the last attempt failed."
        CALENDAR -> "Calendar items aren't loading — the last read failed."
        else -> "Something is degraded — see Error Logs."
    }

    /**
     * OS-checkable conditions, re-probed on start and on resume. Each probe is guarded on its
     * own so one unavailable API can never suppress the others.
     */
    fun refresh(context: Context) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val am = context.getSystemService(android.app.AlarmManager::class.java)
                if (am?.canScheduleExactAlarms() == false) {
                    raise(context, EXACT_ALARM, "canScheduleExactAlarms=false")
                } else clear(EXACT_ALARM)
            }
        }.onFailure { Logger.e(context, "DEGRADE", it, "exact-alarm probe failed") }

        runCatching {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(context.packageName) == false) {
                raise(context, BATTERY, "not exempt from battery optimisation")
            } else clear(BATTERY)
        }.onFailure { Logger.e(context, "DEGRADE", it, "battery probe failed") }

        runCatching {
            val ok = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!ok) raise(context, CONTACTS, "READ_CONTACTS not granted") else clear(CONTACTS)
        }.onFailure { Logger.e(context, "DEGRADE", it, "contacts probe failed") }

        runCatching {
            val on = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!on) raise(context, NOTIFS, "notifications disabled") else clear(NOTIFS)
        }.onFailure { Logger.e(context, "DEGRADE", it, "notification probe failed") }
    }
}

/**
 * v1.71 (N2): one amber strip above the list. Tapping it opens Error Logs; the X hides it for
 * this session. Only the first live degrade is shown so the list is never buried.
 */
@Composable
fun DegradeBanner(tab: Tab?, onOpenLogs: () -> Unit) {
    val live by Degrades.active.collectAsState()
    if (live.isEmpty()) return
    val codes = Degrades.visibleFor(tab)
    val code = codes.firstOrNull() ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LearnSoft)
            .clickable { onOpenLogs() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.WarningAmber, null, tint = AmberInk, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Degrades.label(code),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = InkPrimary
            )
            Text(
                "Tap for details in Error Logs" + if (codes.size > 1) " · ${codes.size - 1} more" else "",
                style = MaterialTheme.typography.labelSmall,
                color = InkSubtle
            )
        }
        Icon(
            Icons.Filled.Close, "Dismiss", tint = GreyIcon,
            modifier = Modifier.size(18.dp).clickable { Degrades.dismiss(code) }
        )
    }
}
