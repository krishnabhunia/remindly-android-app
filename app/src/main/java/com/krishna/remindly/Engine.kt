package com.krishna.remindly

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * All user actions live here, so screens stay thin.
 * Also owns the tick-to-done countdown and the Personal-filter unlock flag.
 */
object Engine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** itemId -> epoch millis when the item will move to Done. */
    val pendingDone = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val jobs = HashMap<Long, Job>()

    /** Personal shop filter unlock (resets whenever the app goes to background). */
    val personalUnlocked = MutableStateFlow(false)

    fun lockPersonal() {
        personalUnlocked.value = false
    }

    // ------------------------------------------------------------ CRUD

    fun addOrUpdate(context: Context, item: Item) {
        CalSync.syncItem(context, item)
        ItemStore.upsert(item)
        AlarmScheduler.cancelForItem(context, item.id)
        AlarmScheduler.scheduleForItem(context, item)
        // Editing a Done shop item can change its lapse — re-arm the return alarm.
        if (item.done && item.returnAt != null) AlarmScheduler.scheduleLapseReturn(context, item)
    }

    fun delete(context: Context, item: Item) {
        // v1.11 soft-delete into the Recently-deleted bin (UNDO restores; purge after 30 days).
        AlarmScheduler.cancelForItem(context, item.id)
        CalSync.removeItem(context, item)
        ItemStore.upsert(item.copy(deletedAt = System.currentTimeMillis()))
        RemindlyWidget.refresh(context)
        TodayWidget.refresh(context)
    }

    /** v2.8 (N44): "Delete this alert" on an item — the record stays, nothing stays armed. */
    fun muteItem(context: Context, item: Item): String {
        val previous = item.alertType
        runCatching {
            AlarmScheduler.cancelForItem(context, item.id)
            ItemStore.upsert((ItemStore.get(item.id) ?: item).copy(alertType = ALERT_MUTED, snoozedUntil = null))
            Logger.e(context, "SCHED", null, "muted '${item.title}' (was $previous)")
        }.onFailure { Logger.e(context, "SCHED", it, "mute failed for ${item.id}") }
        return previous
    }

    fun unmuteItem(context: Context, id: Long, previousType: String) {
        runCatching {
            val cur = ItemStore.get(id) ?: return
            val restored = cur.copy(alertType = previousType.takeIf { it == "A" || it == "R" || it == "N" } ?: "N")
            ItemStore.upsert(restored)
            AlarmScheduler.scheduleForItem(context, restored)
            Logger.e(context, "SCHED", null, "unmuted '${cur.title}'")
        }.onFailure { Logger.e(context, "SCHED", it, "unmute failed for $id") }
    }

    /** v2.8 (N44): delete a shop AND every future event tied to it (fence, snoozed re-fire, Buy Now). */
    fun deleteShop(context: Context, shop: Shop) {
        runCatching {
            ShopStore.delete(shop.id)
            AlarmScheduler.cancelShopArrive(context, shop.id)
            if (UiStore.s.value.buyNowShopId == shop.id) BuyNow.hide(context)
            Geofencer.registerAll(context)
            Logger.e(context, "SCHED", null, "deleted shop '${shop.name}' + fence + snoozed re-fire")
        }.onFailure { Logger.e(context, "SCHED", it, "deleteShop failed for ${shop.id}") }
    }

    fun restore(context: Context, item: Item) {
        ItemStore.upsert(item.copy(deletedAt = null))
        CalSync.syncItem(context, item.copy(deletedAt = null))
        AlarmScheduler.scheduleForItem(context, item.copy(deletedAt = null))
        RemindlyWidget.refresh(context)
        TodayWidget.refresh(context)
    }

    fun deleteForever(context: Context, item: Item) {
        cancelPending(item.id)
        AlarmScheduler.cancelForItem(context, item.id)
        ItemStore.delete(item.id)
    }

    // ------------------------------------------------------------ complete flow

    /** Checkbox ticked: start the visible countdown, then move to Done. */
    fun startComplete(context: Context, item: Item) {
        if (pendingDone.value.containsKey(item.id)) return
        // v1.39 Phase B: completing a Shop item first requires the checkout calculator
        // (shop + prices). The dialog collects it, then calls finishShopComplete.
        if (item.tab == Tab.SHOP && !item.done && item.deletedAt == null &&
            SettingsStore.s.value.shopCheckoutCalc) {   // v1.90: global toggle (Shop Settings)
            ShopCompletePrompt.request(item)
            return
        }
        val delaySec = delayFor(SettingsStore.s.value, item.tab).coerceIn(0, 300)
        if (delaySec == 0) {
            val app = context.applicationContext
            scope.launch {
                Fx.celebrate(item.id)
                completeNow(app, item.id)
                Ack.show("Moved to Done") {
                    ItemStore.items.value.firstOrNull { it.id == item.id && it.done }
                        ?.let { startRevive(app, it) }
                }
            }
            return
        }
        val endsAt = System.currentTimeMillis() + delaySec * 1000L
        pendingDone.value = pendingDone.value + (item.id to endsAt)
        val job = scope.launch {
            delay(delaySec * 1000L)
            pendingDone.value = pendingDone.value - item.id
            jobs.remove(item.id)
            Fx.celebrate(item.id)
            completeNow(context, item.id)
        }
        jobs[item.id] = job
    }

    /** Undo pressed during the countdown: keep the item active. */
    fun cancelPending(id: Long) {
        jobs.remove(id)?.cancel()
        pendingDone.value = pendingDone.value - id
    }

    /** Finalize: mark done, stop its alarms, and arm the lapse-return cycle if set. */
    fun completeNow(context: Context, id: Long, shopStamp: Boolean = true) {
        val item = ItemStore.get(id) ?: return
        if (item.done) return
        cancelPending(id)
        AlarmScheduler.cancelForItem(context, id)
        // v1.80 (Q17): the snooze belonged to THIS occurrence — it must not linger on the Done
        // card, nor carry into the next occurrence of a recurring item.
        if (item.snoozedUntil != null) ItemStore.upsert(ItemStore.get(id)!!.copy(snoozedUntil = null))
        // v1.12 (Krishna's redesign): completing a recurring item DOES send it to Done —
        // the due date advances to the next occurrence, and at midnight of that day the
        // resurrection sweep walks it back into Active (mirrors the Shop lapse-return).
        if (item.repeatMode != "OFF") {
            // v1.22 item 7: a repeat may end after N occurrences. The tally moves only on a
            // real completion, and an exhausted repeat finishes in Done rather than vanishing.
            val tally = item.repeatDone + 1
            if (repeatExhausted(item.repeatCount, tally)) {
                val finished = item.copy(
                    done = true, doneAt = System.currentTimeMillis(),
                    repeatDone = tally, returnAt = null
                )
                ItemStore.upsert(finished)
                CalSync.removeItem(context, finished)
                RemindlyWidget.refresh(context)
                TodayWidget.refresh(context)
                Ack.show("Done · repeat finished") { ItemStore.upsert(item) }
                return
            }
            val next = nextOccurrence(item, System.currentTimeMillis())
            if (next != null) {
                val completed = item.copy(
                    done = true, doneAt = System.currentTimeMillis(),
                    dueAt = next, dueHasTime = true, returnAt = null,
                    repeatDone = tally,
                    spacedStep = if (item.repeatMode == "SPACED") item.spacedStep + 1 else item.spacedStep,
                    priceHistory = if (item.tab == Tab.SHOP && shopStamp)
                        (item.price?.replace(",", "")?.replace("₹", "")?.trim()?.toDoubleOrNull())
                            ?.let { p -> pushPrice(item.priceHistory, System.currentTimeMillis(), p) } ?: item.priceHistory
                    else item.priceHistory
                )
                ItemStore.upsert(completed)
                CalSync.syncItem(context, completed)
                RemindlyWidget.refresh(context)
                TodayWidget.refresh(context)
                Ack.show("Done · returns " + formatDayTime(next)) {
                    ItemStore.upsert(item)
                    AlarmScheduler.cancelForItem(context, item.id)
                    AlarmScheduler.scheduleForItem(context, item)
                    RemindlyWidget.refresh(context)
                    TodayWidget.refresh(context)
                }
                return
            }
        }
        var updated = item.copy(done = true, doneAt = System.currentTimeMillis(), returnAt = null)
        // v1.15: Shop completions stamp the price history (last 12 purchases).
        if (item.tab == Tab.SHOP && shopStamp) {
            item.price?.replace(",", "")?.replace("₹", "")?.trim()?.toDoubleOrNull()?.let { p ->
                updated = updated.copy(priceHistory = pushPrice(item.priceHistory, System.currentTimeMillis(), p))
            }
        }
        if (item.tab == Tab.SHOP && item.lapseValue != null && item.lapseValue > 0 && item.lapseUnit != null) {
            val returnAt = computeReturnAt(System.currentTimeMillis(), item.lapseValue, item.lapseUnit)
            updated = updated.copy(returnAt = returnAt)
        }
        ItemStore.upsert(updated)
        CalSync.removeItem(context, updated)
        // Expiry lives on Done items now (v1.4) — re-arm it after the due alarm is gone.
        AlarmScheduler.scheduleForItem(context, updated)
        updated.returnAt?.let { AlarmScheduler.scheduleLapseReturn(context, updated) }
    }

    /** Animated return from the Done list back to Active. */
    fun startRevive(context: Context, item: Item) {
        scope.launch {
            Fx.reviveOut(item.id)
            undoDone(context, item)
            Fx.markRevived(scope, item.id)
        }
    }

    /** Undo from the Done list: item returns to its original deck. */
    fun undoDone(context: Context, item: Item) {
        val revived = item.copy(done = false, doneAt = null, returnAt = null)
        ItemStore.upsert(revived)
        AlarmScheduler.cancelForItem(context, item.id)
        AlarmScheduler.scheduleForItem(context, revived)
    }

    /**
     * v1.39 Phase B: apply the checkout calculator's captured values to the item, record the
     * enriched purchase (last 12), then complete WITHOUT the legacy price re-stamp. Guarded.
     */
    fun finishShopComplete(context: Context, item: Item, shop: String, calc: ShopCalc, unit: String?) {
        runCatching {
            val now = System.currentTimeMillis()
            val point = PricePoint(
                at = now,
                price = calc.cost ?: 0.0,
                shop = shop.trim(),
                qty = calc.qty ?: 0.0,
                unit = unit ?: item.unit ?: "",
                unitPrice = calc.unitPrice ?: 0.0,
                paid = calc.paid ?: 0.0,
                discountPct = calc.discountPct ?: 0.0
            )
            val enriched = item.copy(
                shopName = shop.trim().ifBlank { item.shopName },
                unit = unit ?: item.unit,
                quantity = calc.qty?.let { trimNum(it) } ?: item.quantity,
                price = calc.cost?.let { trimNum(it) } ?: item.price,
                priceHistory = pushPurchase(item.priceHistory, point)
            )
            ItemStore.upsert(enriched)
            // v1.90: product price memory — a linked product remembers what it last cost at
            // this shop, powering the Products best-price line and editor suggestions.
            runCatching {
                val pid = item.productId
                val sid = ShopStore.byName(shop)?.id
                if (pid != null && sid != null) {
                    ProductStore.recordPrice(pid, sid,
                        price = calc.cost ?: 0.0,
                        unitPrice = calc.unitPrice ?: 0.0, at = now)
                }
            }.onFailure { Logger.e(context, "PRODUCT", it, "checkout price write-back failed") }
        }.onFailure { Logger.e(context, "SHOP", it, "finishShopComplete apply failed") }
        ShopCompletePrompt.clear()
        val app = context.applicationContext
        scope.launch {
            Fx.celebrate(item.id)
            completeNow(app, item.id, shopStamp = false)
            Ack.show("Moved to Done") {
                ItemStore.items.value.firstOrNull { it.id == item.id && it.done }?.let { startRevive(app, it) }
            }
        }
    }
}

/** v1.39 Phase B: signals the UI to show the Shop checkout calculator before a Shop item completes. */
object ShopCompletePrompt {
    val pending = MutableStateFlow<Item?>(null)
    fun request(item: Item) { pending.value = item }
    fun clear() { pending.value = null }
}

/** Format a Double as an integer string when whole (2.0 → "2"), else keep decimals. */
fun trimNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else ((Math.round(d * 100.0) / 100.0).toString())

/** Bottom acknowledgment chip shown when the move-to-Done delay is Off. */
object Ack {
    data class Data(val label: String, val undo: () -> Unit)

    val current = MutableStateFlow<Data?>(null)
    private val ackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun show(label: String, undo: () -> Unit) {
        job?.cancel()
        current.value = Data(label, undo)
        job = ackScope.launch {
            delay(2600)
            current.value = null
        }
    }

    fun dismiss() {
        job?.cancel()
        current.value = null
    }
}

/** Transient visual states shared by item and call cards. */
object Fx {
    val celebrating = MutableStateFlow<Set<Long>>(emptySet())
    val reviving = MutableStateFlow<Set<Long>>(emptySet())
    val justRevived = MutableStateFlow<Set<Long>>(emptySet())

    suspend fun celebrate(id: Long) {
        celebrating.value = celebrating.value + id
        delay(430)
        celebrating.value = celebrating.value - id
    }

    suspend fun reviveOut(id: Long) {
        reviving.value = reviving.value + id
        delay(380)
        reviving.value = reviving.value - id
    }

    fun markRevived(scope: CoroutineScope, id: Long) {
        scope.launch {
            justRevived.value = justRevived.value + id
            delay(1500)
            justRevived.value = justRevived.value - id
        }
    }
}
