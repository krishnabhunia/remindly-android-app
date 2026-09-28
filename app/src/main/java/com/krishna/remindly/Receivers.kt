package com.krishna.remindly

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

// ================================================================ alerts

object Alerts {
    const val CH_ALARM = "remindly_alarm"
    const val CH_REMIND = "remindly_reminders"
    const val CH_URGENT = "remindly_urgent"
    const val CH_HIGH = "remindly_high"

    /** v1.22 item 9: when the OS refuses the ringing service, the reminder still arrives. */
    fun fallbackAlarmNotification(context: Context, itemId: Long, kind: String) {
        runCatching {
            val open = PendingIntent.getActivity(
                context, 4801,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = ItemStore.get(itemId)?.title ?: "Reminder"
            val n = NotificationCompat.Builder(context, CH_URGENT)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText("Due now" + (if (kind.isNotBlank()) " · $kind" else ""))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(48000 + (itemId % 900).toInt(), n) }.onFailure { Logger.e(context, "notify", it) }
        }.onFailure { Logger.e(context, "alarmsvc", it, "fallback notification failed") }
    }

    fun channelFor(item: Item): String = when (item.priority) {
        Priority.URGENT -> CH_URGENT
        Priority.HIGH -> CH_HIGH
        else -> CH_REMIND
    }
    const val CH_INFO = "remindly_info"

    const val SHOP_NOTIF_ID = 7001

    fun notifId(itemId: Long): Int = (itemId % 100_000L).toInt() + 100

    /**
     * v1.81 (Q15): a tap must open the ITEM, not just its tab. The id travels in the extra, and
     * the REQUEST CODE includes it — with FLAG_UPDATE_CURRENT a shared code would let every
     * notification on a tab overwrite each other's extras and every tap would open the last one.
     */
    internal fun mainIntentForItem(context: Context, item: Item): PendingIntent = PendingIntent.getActivity(
        context, (8_500_000 + item.id).toInt(),
        Intent(context, MainActivity::class.java)
            .putExtra("openTab", item.tab.ordinal)
            .putExtra("openItemId", item.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal fun mainIntentIndex(context: Context, tabIndex: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("openTab", tabIndex)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 500 + tabIndex, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun mainIntent(context: Context, tab: Tab): PendingIntent =
        mainIntentIndex(context, tab.ordinal)

    internal fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * v1.83 (N10): plain MINUTES, always. The old body did `m / 60` with INTEGER division, so 90
     * rendered as "1 h" (remainder discarded) and 60 rendered "1 h" too — two different durations
     * collapsing to one string, which is why the settings chip row showed "1 h" twice and the
     * alert card claimed an hour while actually deferring 90 minutes. Krishna, 08-Aug-2026:
     * "display everything in minutes". Delegates so ONE function formats every snooze duration.
     */
    fun snoozeLabel(m: Int): String = snoozeMinLabel(m)


    /** v1.14: instant plain-notification test. */
    /** v1.64: real current button sets — labels track the user's snooze settings. */

    /** v1.64: RING channel preview — continuous ringtone + notification, NO card. */
    fun fireTestRing(context: Context) {
        AlarmService.start(context, AlarmService.MODE_TEST, 0L, "test", ringOnly = true)
    }

    /** v1.65: THE action set for item notifications — real builder and test sample share it. */
    val REAL_NOTIF_ACTIONS = listOf("Dismiss", "Done")   // v1.66: snoozes pruned

    private fun dismissNotifPi(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, (9_000_000 + id).toInt(),
        Intent(context, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_NOTIF_DISMISS)
            .putExtra(AlarmScheduler.EXTRA_ITEM_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** v1.65: ONE test sample — same builder shape and the SAME REAL_NOTIF_ACTIONS labels as a
     *  real reminder; actions only dismiss (TEST) and toast. */
    /**
     * v1.86 (N19): ONE builder for the reminder notification — the real N-path and the Settings
     * sample BOTH call this, so the sample can never drift from production again (v1.65 shared
     * only the action labels; the builder itself had already forked once).
     */
    fun buildReminderNotification(
        context: Context, channel: String, title: String, contentText: String, bigBody: String,
        contentPi: PendingIntent, dismissPi: PendingIntent, donePi: PendingIntent
    ): android.app.Notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(title)
        .setContentText(contentText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(contentPi)
        // v1.65 (Krishna 1.5): the fixed action set — labels come from REAL_NOTIF_ACTIONS.
        .addAction(0, REAL_NOTIF_ACTIONS[0], dismissPi)
        .addAction(0, REAL_NOTIF_ACTIONS[1], donePi)
        .build()

    fun fireTestNotificationSample(context: Context) {
        if (!canNotify(context)) return
        fun pi(reqId: Int): PendingIntent = PendingIntent.getBroadcast(
            context, reqId,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_TEST_DISMISS)
                .putExtra("nid", 46001),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // v1.86 (N19): built by the PRODUCTION builder — ⚑ the sample gains the BigTextStyle
        // body real notifications carry (it previously lacked it; that WAS drift).
        val notif = buildReminderNotification(
            context, CH_REMIND,
            title = "TEST \u00b7 Reminder \u00b7 Tasks",
            contentText = "Submit the report",
            bigBody = "Submit the report\nDue " + formatDateTime(System.currentTimeMillis()),
            contentPi = pi(46001), dismissPi = pi(46010), donePi = pi(46011)
        )
        runCatching { NotificationManagerCompat.from(context).notify(46001, notif) }.onFailure { Logger.e(context, "notify", it) }
    }

    /** v1.68: the call DETECTION notification — Call now + Done. */
    fun fireCallNotification(context: Context, r: CallReminder) {
        if (!canNotify(context)) return
        val donePi = PendingIntent.getBroadcast(
            context, (9_500_000 + r.id).toInt(),
            Intent(context, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_CALL_DONE)
                .putExtra(AlarmScheduler.EXTRA_ITEM_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val callPi = PendingIntent.getActivity(
            context, (9_600_000 + r.id).toInt(),
            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + r.number)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(context, CH_REMIND)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Call " + r.display)
            .setContentText(r.company ?: r.number)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(callPi)
            .addAction(0, "Call now", callPi)
            .addAction(0, "Done", donePi)
        // v1.79 (N4): WhatsApp cannot be sent programmatically — no public API, and automating
        // the UI violates their terms. This opens the chat with the text pre-filled; the user
        // taps send. One extra tap, zero ban risk.
        if (!r.message.isNullOrBlank()) {
            val waUrl = "https://wa.me/" + CallActions.waNumber(r.number) +
                "?text=" + android.net.Uri.encode(r.message)
            val waPi = PendingIntent.getActivity(
                context, (9_700_000 + r.id).toInt(),
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(waUrl)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, "Send WhatsApp", waPi)
        }
        runCatching { NotificationManagerCompat.from(context).notify(notifId(r.id), b.build()) }
            .onFailure { e -> Logger.e(context, "notify", e) }
    }

    fun kindTitle(kind: String): String = when (kind) {
        "expiry" -> "Expires today"
        "lapse" -> "Back on your list"
        else -> "Reminder"
    }


    fun fireItem(context: Context, item: Item, kind: String, typesOverride: String? = null) {
        // v1.56 1.1: per-tab alert master switch — suppressed fires are logged, never mute.
        if (!alertsEnabledFor(item.tab, SettingsStore.s.value)) {
            Logger.e(context, "ALERT", null, "suppressed: alerts off for ${item.tab.title}"); return
        }
        // v2.7 (N40) standing guard: an alert whose card cannot be reached (its tab is hidden) is
        // never silent-by-accident again — it is logged, and the app shows the way back once.
        runCatching {
            if (!itemReachable(item, SettingsStore.s.value, personalLocked = false)) {
                Logger.e(context, "ALERT", null, "UNREACHABLE: '${item.title}' alerts from the hidden ${item.tab.title} tab — show it in Settings → Appearance or silence it in Scheduled alerts")
                UiStore.update { u ->
                    if (u.pendingNotice == null)
                        u.copy(pendingNotice = "A reminder alerted from the hidden ${item.tab.title} tab. Settings → Scheduled alerts shows it (Show tab / Silence).")
                    else u
                }
            }
        }

        // v1.57 1.2: a SET of channels — A(larm card), R(ing sound-only), N(otification).
        // A takes sound precedence over R; N can accompany either; empty = silent (logged).
        val types = typesOverride ?: resolveAlertTypes(item, SettingsStore.s.value)
        if (types.isEmpty()) {
            Logger.e(context, "ALERT", null, "silent: empty type set for ${item.title}"); return
        }
        when {
            'A' in types -> AlarmService.start(context, AlarmService.MODE_ITEM, item.id, kind)
            'R' in types -> AlarmService.start(context, AlarmService.MODE_ITEM, item.id, kind, ringOnly = true)
        }
        if ('N' !in types) return
        if (!canNotify(context)) return
        val doneIntent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmScheduler.ACTION_MARK_DONE)
            .putExtra(AlarmScheduler.EXTRA_ITEM_ID, item.id)
        val donePi = PendingIntent.getBroadcast(
            context, notifId(item.id), doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = buildString {
            append(item.title)
            item.dueAt?.let { append("\nDue ").append(formatDateTime(it)) }
            item.priority?.let { append("\nPriority: ").append(it.label) }
        }
        // v1.86 (N19): the shared builder — see buildReminderNotification.
        val notif = buildReminderNotification(
            context, channelFor(item),
            title = "${kindTitle(kind)} · ${item.tab.title}",
            contentText = item.title,
            bigBody = body,
            contentPi = mainIntentForItem(context, item),
            dismissPi = dismissNotifPi(context, item.id),
            donePi = donePi
        )
        runCatching { NotificationManagerCompat.from(context).notify(notifId(item.id), notif) }.onFailure { Logger.e(context, "notify", it) }
    }

    /**
     * v1.86 (N20): the auto-roll sweep — every missed occurrence of an active repeat is logged
     * into missedAt and the item lands on today/future; alarms re-arm on the new due. Runs at
     * midnight, on boot and on app open (same trio as resurrectDue).
     */
    fun rollAllMissed(context: Context) {
        val rolled = rollMissedRepeats(ItemStore.items.value, System.currentTimeMillis())
        rolled.forEach { r ->
            ItemStore.upsert(r)
            AlarmScheduler.scheduleForItem(context, r)
            Logger.e(context, "ROLL", null,
                "'${r.title}' rolled to ${r.dueAt?.let { formatDateTime(it) }} — ${r.missedAt.size} missed logged")
        }
    }

    /** Location-triggered (or deferred) shopping reminder. */
    fun fireShopReminder(context: Context, sourceLabel: String, groupFilter: List<String> = emptyList(), shopFilter: String? = null, shopId: Long? = null) {
        if (!alertsEnabledFor(Tab.SHOP, SettingsStore.s.value)) {
            Logger.e(context, "ALERT", null, "suppressed: alerts off for Shop"); return
        }
        var pending = ItemStore.pendingShopItems()
        // v1.15 item 17: place→group binding scopes the announcement.
        if (groupFilter.isNotEmpty()) pending = pending.filter { (it.group ?: "") in groupFilter }
        // v1.43 Phase D: a shop-bound arrival mentions only items tagged with that shop.
        if (shopFilter != null) pending = pending.filter {
            (it.shopName ?: "").trim().equals(shopFilter.trim(), ignoreCase = true)
        }
        if (pending.isEmpty()) return

        if (!canNotify(context)) return
        val general = pending.filter { !it.personal }
        val personalCount = pending.size - general.size
        val scoped = if (groupFilter.isEmpty()) "" else " · " + groupFilter.joinToString(", ")
        val lines = buildString {
            general.take(8).forEachIndexed { i, it ->
                if (i > 0) append("\n")
                append("• ").append(it.title)
            }
            if (general.size > 8) append("\n… and ${general.size - 8} more")
            if (personalCount > 0) {
                if (isNotEmpty()) append("\n")
                append("🔒 $personalCount personal item${if (personalCount > 1) "s" else ""} (open app)")
            }
        }
        val notif = NotificationCompat.Builder(context, CH_REMIND)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("Shopping reminder · $sourceLabel")
            .setContentText("${pending.size} item${if (pending.size > 1) "s" else ""} on your list")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            // v2.05 (N38): a shop arrival opens the Buy Now view for that shop; other sources
            // (place fences) keep the plain Shop-list intent.
            .setContentIntent(if (shopId != null) buyNowPendingIntent(context, shopId) else mainIntent(context, Tab.SHOP))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SHOP_NOTIF_ID, notif) }.onFailure { Logger.e(context, "notify", it) }
    }

    /** v2.05 (N38): open Shop mode → Buy → the Buy Now view for [shopId]. */
    internal fun buyNowIntent(context: Context, shopId: Long): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra("openMode", "SHOP")
            .putExtra("openShopTab", 0)
            .putExtra("buyNowShopId", shopId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun buyNowPendingIntent(context: Context, shopId: Long): PendingIntent =
        PendingIntent.getActivity(
            context, (9_600_000 + shopId).toInt(), buyNowIntent(context, shopId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** v2.05 (N37): re-arm ONE arrival alert after the cooldown (Ring/Alarm "Snooze"). */
    fun snoozeShopArrival(context: Context, shopId: Long) {
        val mins = SettingsStore.s.value.shopArriveCooldownMin.coerceAtLeast(1)
        AlarmScheduler.scheduleAt(context, System.currentTimeMillis() + mins * 60_000L,
            AlarmScheduler.TYPE_SHOP_ARRIVE, shopId)
        Feedback.toast(context, "Shop reminder snoozed for $mins min")
        Logger.e(context, "ALERT", null, "shop arrival snoozed ${mins}m for shop $shopId")
    }

    /**
     * v2.05 (N37): the whole arrival path for ONE shop — per-shop alert set (N/R/A) with the
     * Shops ⚙ default, master gates, pending check, per-shop cooldown, and the N38 Buy Now arm.
     * Every branch is guarded; a suppressed or silent fire is logged, never silent-by-accident.
     */
    fun fireShopArrival(context: Context, shop: Shop, snoozed: Boolean = false) {
        runCatching {
            val settings = SettingsStore.s.value
            val pending = buyNowItems(ItemStore.items.value, shop)
            if (pending.isEmpty()) {
                Logger.e(context, "ALERT", null, "arrival at ${shop.name}: nothing pending — no alert, no Buy Now")
                return
            }
            // N38: the view arms even when the ALERT is muted — the view is not the alert.
            BuyNow.arm(context, shop.id)
            if (!settings.shopArriveAlert) {
                Logger.e(context, "ALERT", null, "suppressed: shop arrive alerts off (${shop.name}) — Buy Now armed")
                return
            }
            if (!alertsEnabledFor(Tab.SHOP, settings)) {
                Logger.e(context, "ALERT", null, "suppressed: alerts off for Shop (${shop.name}) — Buy Now armed")
                return
            }
            val now = System.currentTimeMillis()
            if (!snoozed && !arriveCooldownPassed(shop, settings, now)) {
                Logger.e(context, "ALERT", null, "skipped: ${shop.name} within the arrival cooldown")
                return
            }
            ShopStore.markArriveFired(shop.id, now)
            val types = resolveShopArriveTypes(shop, settings)
            if (types.isEmpty()) {
                Logger.e(context, "ALERT", null, "silent: empty arrival type set for ${shop.name} — Buy Now armed")
                return
            }
            when {
                'A' in types -> AlarmService.start(context, AlarmService.MODE_SHOP, shop.id, "shop", shop.name)
                'R' in types -> AlarmService.start(context, AlarmService.MODE_SHOP, shop.id, "shop", shop.name, ringOnly = true)
            }
            if ('N' in types) fireShopReminder(context, "Arrived at ${shop.name}", shopFilter = shop.name, shopId = shop.id)
        }.onFailure { Logger.e(context, "ALERT", it, "shop arrival failed for ${shop.name}") }
    }

    fun infoNotification(context: Context, title: String, text: String, tab: Tab) {
        if (!canNotify(context)) return
        val notif = NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context, tab))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 90_000).toInt() + 10_000, notif) }.onFailure { Logger.e(context, "notify", it) }
    }
}

// ================================================================ alarm receiver

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Stores.init(context)
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> handleFire(context, intent)
            TodayWidget.ACTION_ROW -> {
                val rid = intent.getLongExtra(TodayWidget.EXTRA_ROW_ID, -1L)
                val done = intent.getBooleanExtra(TodayWidget.EXTRA_ROW_DONE, false)
                if (rid > 0) {
                    if (done) Engine.completeNow(context, rid)
                    else runCatching {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { Logger.e(context, "widget", it, "row tap could not open the app") }
                    TodayWidget.refresh(context)
                } else if (rid < 0) {
                    // v1.15 item 23: call rows — checkbox completes through the notification-Done path.
                    if (done) CallEngine.completeFromNotification(context, -rid)
                    else runCatching {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { Logger.e(context, "widget", it, "row tap could not open the app") }
                    TodayWidget.refresh(context)
                }
            }
            AlarmScheduler.ACTION_SNOOZE -> {
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
                AlarmScheduler.cancelOverdue(context, id)
                val minutes = intent.getIntExtra("minutes", 10)
                if (id > 0) {
                    NotificationManagerCompat.from(context).cancel(Alerts.notifId(id))
                    val fireAt = System.currentTimeMillis() + minutes * 60_000L
                    AlarmScheduler.scheduleAt(context, fireAt, AlarmScheduler.TYPE_DUE, id)
                    // v1.80 (Q17): persist it so it survives a reboot and shows in "Coming up".
                    ItemStore.get(id)?.let { ItemStore.upsert(it.copy(snoozedUntil = fireAt)) }
                    Feedback.toast(context, snoozeToast(minutes, fireAt))
                    AlarmService.stop(context)   // v1.65: snoozing from a RING silences it
                }
                return
            }
            AlarmScheduler.ACTION_NOTIF_DISMISS -> {
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
                if (id > 0) {
                    NotificationManagerCompat.from(context).cancel(Alerts.notifId(id))
                    val itD = ItemStore.get(id)
                    Feedback.toast(context, itD?.let { dismissToast(it.title) } ?: "Dismissed")
                }
            }
            AlarmScheduler.ACTION_TEST_DISMISS -> {
                val nid = intent.getIntExtra("nid", 0)
                // buttons carry nid*10+i; base id recovers the notification either way
                val base = if (nid >= 460010) nid / 10 else nid
                NotificationManagerCompat.from(context).cancel(base)
                android.widget.Toast.makeText(context, "Test — no action taken", android.widget.Toast.LENGTH_SHORT).show()
            }
            AlarmScheduler.ACTION_CALL_CLOSE -> {
                val id2 = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
                if (id2 > 0) {
                    NotificationManagerCompat.from(context).cancel(Alerts.notifId(id2))
                    Feedback.toast(context, closeCallToast())
                }
            }
            AlarmScheduler.ACTION_CALL_DONE -> {
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
                if (id > 0) {
                    NotificationManagerCompat.from(context).cancel(Alerts.notifId(id))
                    CallEngine.completeFromNotification(context, id)
                    AlarmService.stop(context)   // v1.65: Done from a RING silences it
                    // v1.63 honest note: no CallStore.get exists, so the notification path
                    // confirms without the contact name (the card path names them).
                    Feedback.toast(context, "\u2713 Call reminder marked done")
                }
                return
            }
            AlarmScheduler.ACTION_MARK_DONE -> {
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, 0L)
                val itemDone = ItemStore.get(id)
                Engine.completeNow(context, id)
                NotificationManagerCompat.from(context).cancel(Alerts.notifId(id))
                AlarmService.stop(context)
                itemDone?.let { Feedback.toast(context, doneToastItem(it.title, it.tab)) }
            }
            AlarmScheduler.ACTION_STOP_RING -> {
                AlarmService.stop(context)
                Feedback.toast(context, stopToastText())
            }
        }
    }

    private fun handleFire(context: Context, intent: Intent) {
        val type = intent.getIntExtra(AlarmScheduler.EXTRA_TYPE, 0)
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, 0L)
        when (type) {
            AlarmScheduler.TYPE_DUE -> {
                // v1.80 (Q17): the snooze has now been served — clear it.
                ItemStore.get(id)?.takeIf { it.snoozedUntil != null }
                    ?.let { ItemStore.upsert(it.copy(snoozedUntil = null)) }
                val item = ItemStore.get(id) ?: return
                if (item.done) return
                Alerts.fireItem(context, item, "due")
            }
            AlarmScheduler.TYPE_LAPSE -> {
                val item = ItemStore.get(id) ?: return
                if (!item.done) return
                val revived = item.copy(
                    done = false, doneAt = null, returnAt = null,
                    createdAt = System.currentTimeMillis(),
                    dueAt = null, expiryAt = null
                )
                ItemStore.upsert(revived)
            }
            AlarmScheduler.TYPE_BACKUP -> {
                Backup.autoBackupIfDue(context)
                AlarmScheduler.scheduleDailyBackup(context)
            }
            AlarmScheduler.TYPE_MIDNIGHT -> {
                // v1.12: recurring resurrection — due day arrived, walk Done → Active.
                resurrectDue(ItemStore.items.value, System.currentTimeMillis()).forEach { revived ->
                    ItemStore.upsert(revived)
                    AlarmScheduler.scheduleForItem(context, revived)
                }
                clearStaleOos(ItemStore.items.value, System.currentTimeMillis()).forEach { ItemStore.upsert(it) }
                // v1.14: recurring manual calls resurrect the same way.
                resurrectCallsDue(CallStore.calls.value, System.currentTimeMillis()).forEach { rc ->
                    CallStore.upsert(rc)
                    AlarmScheduler.scheduleCallRecur(context, rc)
                }
                // v1.86 (N20): roll missed repeats forward at the same midnight tick.
                Alerts.rollAllMissed(context)
                RemindlyWidget.refresh(context)
                TodayWidget.refresh(context)
                AlarmScheduler.scheduleMidnightRefresh(context)
            }
            AlarmScheduler.TYPE_DUE_DEMOTED -> {
                // v1.68: the double-tap-outside quiet re-fire — Notification regardless of type.
                val itemD = ItemStore.get(id) ?: return
                if (itemD.done || itemD.deletedAt != null) return
                Logger.e(context, "ALERT", null, "quiet re-fire (demoted) for '${itemD.title}'")
                Alerts.fireItem(context, itemD, "due", typesOverride = "N")
            }
            AlarmScheduler.TYPE_SHOP_ARRIVE -> {
                // v2.05 (N37): a snoozed shop arrival — re-fire once if items are still pending.
                val shopS = ShopStore.get(id) ?: return
                Alerts.fireShopArrival(context, shopS, snoozed = true)
            }
            AlarmScheduler.TYPE_CALL_DEMOTED -> {
                val rD = CallStore.get(id) ?: return
                if (rD.done || rD.deletedAt != null) return
                Alerts.fireCallNotification(context, rD)
            }
            AlarmScheduler.TYPE_CRECUR -> {
                val r = CallStore.get(id)
                if (r != null && !r.done && r.deletedAt == null) CallEngine.fireDetection(context, r)
            }
            AlarmScheduler.TYPE_CSNOOZE -> {
                val r = CallStore.get(id)
                if (r != null && !r.done && r.deletedAt == null) CallEngine.fireDetection(context, r)
            }
            else -> Logger.e(context, "ALERT", null, "unknown alarm type=$type — ignored (stale purge-era PendingIntent)")
        }
    }
}

// ================================================================ boot receiver

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Stores.init(context)
        AlarmScheduler.rescheduleAll(context)
        // v1.86 (N20): a phone that was OFF overnight still rolls its missed repeats on boot.
        Alerts.rollAllMissed(context)
        Geofencer.registerAll(context)
    }
}

// ================================================================ geofencing

object Geofencer {

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(
            context, 9000, Intent(context, GeofenceReceiver::class.java), flags
        )
    }

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /** Re-registers every enabled place. Safe to call anytime. */
    fun registerAll(context: Context) {
        if (!hasFineLocation(context)) return
        val client = LocationServices.getGeofencingClient(context)
        val pi = pendingIntent(context)
        client.removeGeofences(pi)
        val places = PlaceStore.places.value.filter { it.enabled && it.deletedAt == null }
        val placeFences = places.map { p ->
            Geofence.Builder()
                .setRequestId(p.id.toString())
                .setCircularRegion(p.lat, p.lng, p.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    if (p.trigger == TriggerType.LEAVE) Geofence.GEOFENCE_TRANSITION_EXIT
                    else Geofence.GEOFENCE_TRANSITION_ENTER
                )
                .build()
        }
        // v1.43 Phase D: registered shops with a geofence fire an ARRIVE reminder listing that shop's items.
        val shopFences = ShopStore.active().filter { it.hasGeofence }.map { sh ->
            Geofence.Builder()
                .setRequestId("shop:${sh.id}")
                .setCircularRegion(sh.lat!!, sh.lng!!, sh.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }
        val fences = placeFences + shopFences
        if (fences.isEmpty()) return
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(fences)
            .build()
        try {
            client.addGeofences(request, pi)
        } catch (_: SecurityException) {
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Stores.init(context)
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val transition = event.geofenceTransition
        val fences = event.triggeringGeofences ?: return
        val now = System.currentTimeMillis()
        var firedPlace: GeoPlace? = null
        val firedShopIds = LinkedHashSet<Long>()
        for (fence in fences) {
            val rid = fence.requestId
            // v1.43 Phase D: shop geofences use "shop:<id>" and fire only on ARRIVE.
            if (rid.startsWith("shop:")) {
                if (transition != Geofence.GEOFENCE_TRANSITION_ENTER) continue
                val shop = ShopStore.get(rid.removePrefix("shop:").toLongOrNull() ?: continue) ?: continue
                firedShopIds.add(shop.id)
                continue
            }
            val place = PlaceStore.get(rid.toLongOrNull() ?: continue) ?: continue
            if (!place.enabled) continue
            val matches =
                (place.trigger == TriggerType.LEAVE && transition == Geofence.GEOFENCE_TRANSITION_EXIT) ||
                        (place.trigger == TriggerType.ARRIVE && transition == Geofence.GEOFENCE_TRANSITION_ENTER)
            if (!matches) continue
            if (now - place.lastFired < 10 * 60_000L) continue // 10-min cooldown per place
            PlaceStore.markFired(place.id)
            firedPlace = place
        }
        firedPlace?.let {
            val label = if (it.trigger == TriggerType.LEAVE) "Leaving ${it.name}" else "Arrived at ${it.name}"
            // v1.15 item 17: a bound place mentions only its groups' items.
            Alerts.fireShopReminder(context, label, it.groupFilter)
        }
        // v2.05 (N37/N38): one path per shop — per-shop alert set, cooldown, and the Buy Now arm.
        firedShopIds.forEach { id ->
            ShopStore.get(id)?.let { Alerts.fireShopArrival(context, it) }
        }
    }
}

/** v1.63: user-facing confirmation for alert actions — must NEVER crash an action path. */
object Feedback {
    fun toast(context: android.content.Context, msg: String) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }.onFailure { Logger.e(context, "ALERT", it, "toast failed (non-fatal)") }
    }
}

