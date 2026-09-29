package com.krishna.remindly

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AlarmScheduler {

    const val ACTION_FIRE = "com.krishna.remindly.action.FIRE"
    const val ACTION_MARK_DONE = "com.krishna.remindly.action.MARK_DONE"
    const val ACTION_STOP_RING = "com.krishna.remindly.action.STOP_RING"

    const val EXTRA_TYPE = "type"
    const val EXTRA_ITEM_ID = "itemId"

    const val TYPE_DUE = 1
    const val ACTION_SNOOZE = "com.krishna.remindly.SNOOZE"
    const val TYPE_BACKUP = 9
    const val TYPE_TEST = 10
    const val TYPE_OVERDUE = 11
    const val TYPE_MIDNIGHT = 13
    const val TYPE_CRECUR = 15
    const val TYPE_COVERDUE = 16
    const val TYPE_CSNOOZE = 17
    const val TYPE_DUE_DEMOTED = 18   // v1.68 quiet re-fire (items)
    const val TYPE_CALL_DEMOTED = 19  // v1.68 same, call card
    const val TYPE_SHOP_ARRIVE = 20   // v2.05 (N37): snoozed shop-arrival re-fire (id = shop id)
    const val TYPE_SHOP_DAY = 21      // v2.11 (N48): a Buy list's shopping-day reminder (id = list id)
    const val ACTION_CALL_DONE = "com.krishna.remindly.CALL_DONE"
    const val ACTION_CALL_CLOSE = "com.krishna.remindly.CALL_CLOSE"
    const val ACTION_TEST_DISMISS = "com.krishna.remindly.TEST_DISMISS"
    // v1.65: fixed notification action set
    const val ACTION_NOTIF_DISMISS = "com.krishna.remindly.NOTIF_DISMISS"
    const val TYPE_EXPIRY = 2
    const val TYPE_LAPSE = 3
    // v1.56: rule-based call nags use a code block of their own (up to 8 rules).

    const val EXPIRY_HOUR = 9 // expiry alerts ring at 9 AM on the expiry day

    private fun am(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || am(context).canScheduleExactAlarms()

    // v1.84 (N14): internal so the Robolectric cancel tests use THE production formula (N6 rule:
    // tests call real code, never a mirrored copy).
    internal fun requestCode(id: Long, type: Int): Int =
        // v1.11: widened to 32 slots — the old *8 scheme collided once types passed 7
        // (e.g. backup id 777/type 9 shared a code with item 778/type 1). Old pending
        // intents are orphaned once on upgrade; rescheduleAll recreates everything.
        (((id % 1_000_000L).toInt()) * 32) + type

    private fun firePending(context: Context, id: Long, type: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_TYPE, type)
            .putExtra(EXTRA_ITEM_ID, id)
        return PendingIntent.getBroadcast(
            context, requestCode(id, type), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleAt(context: Context, at: Long, type: Int, id: Long) {
        if (at <= System.currentTimeMillis()) return
        val pi = firePending(context, id, type)
        val mgr = am(context)
        try {
            if (canExact(context)) {
                mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(context: Context, id: Long, type: Int) {
        am(context).cancel(firePending(context, id, type))
    }

    // ------------------------------------------------------------ per item

    /** (Re)schedule an item's alarms — v1.66: pruned to the due alert only. The cancel loop
     *  stays one era so previously-armed extra-alert alarms die on next touch/boot; expiry
     *  alerts removed per Krishna's decision. */
    /**
     * v1.80 (Q17): a live snooze WINS over dueAt. Reboot, undo-done and reopen all used to lose
     * the snooze because this function treated dueAt as the only source of truth — one fix here
     * and rescheduleAll, undoDone and the Ack-undo lambda all inherit the correct behaviour.
     */
    fun scheduleForItem(context: Context, item: Item) {
        // v2.8 (N44): a muted item (alert deleted) arms nothing — not even on reboot.
        // v2.10 (N6 pt2): the decision is the pure itemFireAt() — the test calls the same function.
        itemFireAt(item, System.currentTimeMillis())?.let { scheduleAt(context, it, TYPE_DUE, item.id) }
    }

    fun scheduleLapseReturn(context: Context, item: Item) {
        item.returnAt?.let { scheduleAt(context, it, TYPE_LAPSE, item.id) }
    }

    /**
     * v2.8 (N44) STANDING INVARIANT: every alarm type a record can arm is listed here, and its
     * cancel iterates the SAME list — so "delete the record" can never leave a trigger behind.
     * A unit test schedules each type and asserts the delete leaves nothing (V28Test).
     */
    val ITEM_ALARM_TYPES: List<Int> = listOf(TYPE_DUE, TYPE_EXPIRY, TYPE_LAPSE, TYPE_OVERDUE, TYPE_DUE_DEMOTED)
    val CALL_ALARM_TYPES: List<Int> = listOf(TYPE_CRECUR, TYPE_COVERDUE, TYPE_CSNOOZE, TYPE_CALL_DEMOTED)

    fun cancelForItem(context: Context, id: Long) {
        // v1.84 (N14): the Quiet re-fire was once the ONE armed type this function did not cancel;
        // v2.8: the set is declared once above and iterated here — no type can be forgotten.
        ITEM_ALARM_TYPES.forEach { cancel(context, id, it) }
    }

    /** v2.8 (N44): a deleted shop must leave no snoozed arrival re-fire behind. */
    fun cancelShopArrive(context: Context, shopId: Long) = cancel(context, shopId, TYPE_SHOP_ARRIVE)

    fun scheduleShoppingDay(context: Context, listId: Long, at: Long) = scheduleAt(context, at, TYPE_SHOP_DAY, listId)
    fun cancelShoppingDay(context: Context, listId: Long) = cancel(context, listId, TYPE_SHOP_DAY)

    fun cancelOverdue(context: Context, id: Long) = cancel(context, id, TYPE_OVERDUE)

    fun scheduleCallSnooze(context: Context, id: Long) =
        scheduleAt(context, snoozeTargetMs(SettingsStore.s.value), TYPE_CSNOOZE, id)

    /** v1.86 (N26): EVERY alarm species a call reminder can own, in one sweep — recurrence
     *  chain, overdue nag, snooze, demoted quiet re-fire. Used by the migration purge so a
     *  deleted junk reminder can never ring from a still-armed alarm. */
    fun cancelForCall(context: Context, id: Long) {
        CALL_ALARM_TYPES.forEach { cancel(context, id, it) }   // v2.8 (N44): iterate the declared set
    }

    fun cancelCallSnooze(context: Context, id: Long) {
        cancel(context, id, TYPE_CSNOOZE)
        // v1.84 (N14): TYPE_CALL_DEMOTED had ZERO cancel sites — a call-card double-tap quiet
        // could never be cancelled. Both call-defer types now die together everywhere this is
        // called (snooze re-arm, markCleared, done).
        cancel(context, id, TYPE_CALL_DEMOTED)
    }

    fun scheduleCallRecur(context: Context, r: CallReminder) {
        r.recurAt?.let { if (r.repeatMode != "OFF" && !r.done) scheduleAt(context, it, TYPE_CRECUR, r.id) }
    }

    fun scheduleMidnightRefresh(context: Context) {
        val zone = java.time.ZoneId.systemDefault()
        val next = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        scheduleAt(context, next + 1_000, TYPE_MIDNIGHT, 780L)
    }



    fun scheduleDailyBackup(context: Context) {
        val next = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        scheduleAt(context, next, TYPE_BACKUP, 777L)
    }



    // ------------------------------------------------------------ shop defer


    // ------------------------------------------------------------ global

    /** Called on boot, app update and after a backup import. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for (item in ItemStore.items.value) {
            // v1.79 (Q12): a soft-deleted item keeps done=false, so without this guard boot and
            // app-start re-armed alarms for things the user had already deleted — Engine.delete
            // cancels at delete time, but rescheduleAll put them straight back.
            // v2.10 (N6 pt2): the decision is the pure rearmKind().
            when (rearmKind(item, now)) {
                Rearm.DUE -> scheduleForItem(context, item)
                Rearm.LAPSE -> scheduleLapseReturn(context, item)
                null -> Unit
            }
        }
        ShopListStore.rescheduleShoppingDays(context)   // v2.11 (N48)
    }
}
