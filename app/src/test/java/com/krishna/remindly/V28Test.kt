package com.krishna.remindly

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.8 (N44) — delete from Scheduled alerts. Krishna's rule: "Delete the item (to Bin) must delete
 * all future and all scheduler events." The pure mapping (which trigger a row deletion removes),
 * the muted item, and the STANDING INVARIANT — every alarm type a record can arm is cancelled by
 * its delete — proven against the real AlarmManager (shadowed) and the real Engine/CallEngine.
 * The sheets, swipe and Undo bar are device checks (section U).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V28Test {

    private lateinit var context: Context
    private lateinit var am: AlarmManager
    private val now = 1_950_000_000_000L

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        Stores.init(context)
        am = context.getSystemService(AlarmManager::class.java)
        shadowOf(am).scheduledAlarms.clear()
        ItemStore.replaceAll(emptyList()); CallStore.replaceAll(emptyList())
    }

    private fun codes(): List<Int> = shadowOf(am).scheduledAlarms.map { shadowOf(it.operation).requestCode }
    private fun codesFor(id: Long, types: List<Int>) = types.map { AlarmScheduler.requestCode(id, it) }

    // ---------------------------------------------------------------- the invariant (the point of N44)

    @Test fun deletingAnItem_cancelsEveryAlarmTypeAnItemCanArm() {
        val item = Item(id = 41L, tab = Tab.TASKS, title = "x", dueAt = System.currentTimeMillis() + 3600_000L)
        ItemStore.upsert(item)
        // arm EVERY declared item type (this is what schedule helpers may do across the app)
        AlarmScheduler.ITEM_ALARM_TYPES.forEach { t -> AlarmScheduler.scheduleAt(context, System.currentTimeMillis() + 60_000L, t, item.id) }
        // (the shadow also holds the app's own backup/midnight alarms — assert containment, not equality)
        assertTrue("all item types armed", codes().containsAll(codesFor(item.id, AlarmScheduler.ITEM_ALARM_TYPES)))
        Engine.delete(context, item)
        assertTrue("delete must leave NO alarm for the item", codes().none { it in codesFor(item.id, AlarmScheduler.ITEM_ALARM_TYPES) })
        assertTrue(ItemStore.get(item.id)?.deletedAt != null)
    }

    @Test fun deletingACallReminder_cancelsEveryAlarmTypeACallCanArm_theGapFixed() {
        val r = CallReminder(id = 42L, number = "911", name = "Ramesh", source = CallSource.MANUAL, repeatMode = "WEEKLY")
        CallStore.upsert(r)
        AlarmScheduler.CALL_ALARM_TYPES.forEach { t -> AlarmScheduler.scheduleAt(context, System.currentTimeMillis() + 60_000L, t, r.id) }
        assertTrue("all call types armed", codes().containsAll(codesFor(r.id, AlarmScheduler.CALL_ALARM_TYPES)))
        CallEngine.delete(context, r)
        // before 2.8 only CSNOOZE + CALL_DEMOTED were cancelled here; CRECUR and COVERDUE stayed armed
        assertTrue("delete must leave NO alarm for the reminder", codes().none { it in codesFor(r.id, AlarmScheduler.CALL_ALARM_TYPES) })
    }

    @Test fun declaredSets_coverTheTypesTheAppSchedules() {
        // the types the schedule helpers use for items and calls must be inside the declared sets
        assertTrue(AlarmScheduler.TYPE_DUE in AlarmScheduler.ITEM_ALARM_TYPES)
        assertTrue(AlarmScheduler.TYPE_LAPSE in AlarmScheduler.ITEM_ALARM_TYPES)
        assertTrue(AlarmScheduler.TYPE_OVERDUE in AlarmScheduler.ITEM_ALARM_TYPES)
        assertTrue(AlarmScheduler.TYPE_DUE_DEMOTED in AlarmScheduler.ITEM_ALARM_TYPES)
        assertTrue(AlarmScheduler.TYPE_EXPIRY in AlarmScheduler.ITEM_ALARM_TYPES)
        assertTrue(AlarmScheduler.TYPE_CRECUR in AlarmScheduler.CALL_ALARM_TYPES)
        assertTrue(AlarmScheduler.TYPE_COVERDUE in AlarmScheduler.CALL_ALARM_TYPES)
        assertTrue(AlarmScheduler.TYPE_CSNOOZE in AlarmScheduler.CALL_ALARM_TYPES)
        assertTrue(AlarmScheduler.TYPE_CALL_DEMOTED in AlarmScheduler.CALL_ALARM_TYPES)
    }

    @Test fun deletedItem_isNeverReArmedByRescheduleAll() {
        val item = Item(id = 43L, tab = Tab.TASKS, title = "x", dueAt = System.currentTimeMillis() + 3600_000L)
        ItemStore.upsert(item)
        Engine.delete(context, item)
        AlarmScheduler.rescheduleAll(context)
        assertTrue(codes().none { it in codesFor(item.id, AlarmScheduler.ITEM_ALARM_TYPES) })
    }

    // ---------------------------------------------------------------- muted items

    @Test fun mute_cancelsAndArmsNothing_unmuteRestoresTheType() {
        val item = Item(id = 44L, tab = Tab.TASKS, title = "m", dueAt = System.currentTimeMillis() + 3600_000L, alertType = "R")
        ItemStore.upsert(item)
        AlarmScheduler.scheduleForItem(context, item)
        assertTrue(codes().isNotEmpty())
        val prev = Engine.muteItem(context, item)
        assertEquals("R", prev)
        assertTrue(isMuted(ItemStore.get(44L)!!))
        assertTrue("nothing armed while muted", codes().none { it in codesFor(44L, AlarmScheduler.ITEM_ALARM_TYPES) })
        AlarmScheduler.scheduleForItem(context, ItemStore.get(44L)!!)                  // reboot path
        assertTrue("reboot must not re-arm a muted item", codes().none { it in codesFor(44L, AlarmScheduler.ITEM_ALARM_TYPES) })
        Engine.unmuteItem(context, 44L, prev)
        assertEquals("R", ItemStore.get(44L)!!.alertType)
        assertTrue(codes().any { it in codesFor(44L, AlarmScheduler.ITEM_ALARM_TYPES) })
    }

    @Test fun mutedItem_isSilentAndAbsentFromScheduledAlerts() {
        val m = Item(id = 45L, tab = Tab.TASKS, title = "m", dueAt = now + 3600_000L, alertType = ALERT_MUTED)
        assertEquals("", resolveAlertTypes(m, AppSettings()))
        assertEquals("Muted", alertTypeLabel(ALERT_MUTED))
        assertTrue(scheduledRows(listOf(m), emptyList(), emptyList(), emptyList(), AppSettings(), now).isEmpty())
        val live = m.copy(alertType = "N")
        assertEquals(1, scheduledRows(listOf(live), emptyList(), emptyList(), emptyList(), AppSettings(), now).size)
    }

    // ---------------------------------------------------------------- pure row → action mapping

    private fun row(src: SchedSource, kind: ComingUpKind? = null) =
        SchedRow(now, src, "r", "t", "N", emptyList(), itemId = 1, callId = 2, shopId = 3, placeId = 4, kind = kind)

    @Test fun deleteActionFor_mapsEveryRowType() {
        assertEquals(DeleteKind.MUTE_ITEM, deleteActionFor(row(SchedSource.TASKS, ComingUpKind.DUE)))
        assertEquals(DeleteKind.MUTE_ITEM, deleteActionFor(row(SchedSource.BUY, null)))
        assertEquals(DeleteKind.CANCEL_SNOOZE, deleteActionFor(row(SchedSource.LEARN, ComingUpKind.SNOOZE)))
        assertEquals(DeleteKind.CANCEL_RETURN, deleteActionFor(row(SchedSource.TASKS, ComingUpKind.RETURNS)))
        assertEquals(DeleteKind.CANCEL_RETURN, deleteActionFor(row(SchedSource.BUY, ComingUpKind.LAPSE_RETURN)))
        assertEquals(DeleteKind.DELETE_CALL, deleteActionFor(row(SchedSource.CALLS)))
        assertEquals(DeleteKind.SILENCE_SHOP, deleteActionFor(row(SchedSource.SHOP_ARRIVAL)))
        assertEquals(DeleteKind.DISABLE_PLACE, deleteActionFor(row(SchedSource.PLACE)))
        DeleteKind.values().forEach { assertTrue(deleteKindLabel(it).isNotBlank()) }
    }

    @Test fun rowsForRecord_collectsEveryTriggerOfTheSameRecord() {
        val a1 = SchedRow(now, SchedSource.TASKS, "due", "a", "N", emptyList(), itemId = 10, kind = ComingUpKind.DUE)
        val a2 = SchedRow(now + 1, SchedSource.TASKS, "returns", "a", "N", emptyList(), itemId = 10, kind = ComingUpKind.RETURNS)
        val b = SchedRow(now, SchedSource.TASKS, "due", "b", "N", emptyList(), itemId = 11)
        val c = SchedRow(now, SchedSource.CALLS, "rem", "c", "N", emptyList(), callId = 10)   // same number, different record kind
        assertEquals(listOf(a1, a2), rowsForRecord(listOf(a1, a2, b, c), a1))
        assertEquals(listOf(c), rowsForRecord(listOf(a1, a2, b, c), c))
    }

    @Test fun v28_schemaIs41() {
        assertEquals(41, AppSettings().ver)
        assertEquals(41, healSettings(AppSettings()).ver)
    }
}
