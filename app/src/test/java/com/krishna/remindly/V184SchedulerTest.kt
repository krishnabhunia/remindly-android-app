package com.krishna.remindly

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.84 (N14 defect A) — Robolectric, against the REAL AlarmScheduler and the REAL AlarmManager
 * shadow. `requestCode` was made internal precisely so these tests use the production formula
 * (N6: no mirrored copies).
 *
 * NEGATIVE CONTROL: with v1.83's `cancelForItem` (no TYPE_DUE_DEMOTED line) the first test FAILS —
 * the type-18 alarm survives the cancel. Run and recorded in the BACKLOG for this release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V184SchedulerTest {

    private lateinit var context: Context
    private lateinit var am: AlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        am = context.getSystemService(AlarmManager::class.java)
        shadowOf(am).scheduledAlarms.clear()
    }

    private fun scheduledCodes(): List<Int> =
        shadowOf(am).scheduledAlarms.map { shadowOf(it.operation).requestCode }

    // ---------------- THE bug: the Quiet re-fire must die with cancelForItem ----------------

    @Test fun cancelForItemKillsTheQuietReFire() {
        val id = 4242L
        val at = System.currentTimeMillis() + 90 * 60_000L
        AlarmScheduler.scheduleAt(context, at, AlarmScheduler.TYPE_DUE_DEMOTED, id)
        assertTrue(
            "precondition: the demoted alarm is armed",
            scheduledCodes().contains(AlarmScheduler.requestCode(id, AlarmScheduler.TYPE_DUE_DEMOTED))
        )

        AlarmScheduler.cancelForItem(context, id)

        assertTrue(
            "the type-18 quiet re-fire survived cancelForItem \u2014 the v1.83 bug",
            !scheduledCodes().contains(AlarmScheduler.requestCode(id, AlarmScheduler.TYPE_DUE_DEMOTED))
        )
    }

    /** The whole Coming-up x flow: demoted alarm armed, cancel, original due re-armed. */
    @Test fun cancelThenRestoreReArmsTheOriginalDueOnly() {
        val id = 7L
        val now = System.currentTimeMillis()
        val due = now + 6 * 60 * 60_000L
        val item = Item(id = id, tab = Tab.TASKS, title = "x", dueAt = due, alertType = "N",
            snoozedUntil = now + 90 * 60_000L)
        AlarmScheduler.scheduleAt(context, item.snoozedUntil!!, AlarmScheduler.TYPE_DUE_DEMOTED, id)

        val restored = item.copy(snoozedUntil = null)
        AlarmScheduler.cancelForItem(context, id)
        AlarmScheduler.scheduleForItem(context, restored)

        val codes = scheduledCodes()
        assertTrue("original due must be re-armed",
            codes.contains(AlarmScheduler.requestCode(id, AlarmScheduler.TYPE_DUE)))
        assertTrue("no demoted alarm may remain",
            !codes.contains(AlarmScheduler.requestCode(id, AlarmScheduler.TYPE_DUE_DEMOTED)))
        assertEquals("exactly one alarm for this item", 1,
            codes.count { it == AlarmScheduler.requestCode(id, AlarmScheduler.TYPE_DUE) })
    }

    /** Editing the due time uses the same cancel — the stale quiet re-fire must not survive it. */
    @Test fun rescheduleAfterEditKillsThePendingQuiet() {
        val id = 9L
        val now = System.currentTimeMillis()
        AlarmScheduler.scheduleAt(context, now + 90 * 60_000L, AlarmScheduler.TYPE_DUE_DEMOTED, id)
        val edited = Item(id = id, tab = Tab.TASKS, title = "x", dueAt = now + 24 * 60 * 60_000L)
        AlarmScheduler.cancelForItem(context, id)
        AlarmScheduler.scheduleForItem(context, edited)
        assertTrue(!scheduledCodes().contains(AlarmScheduler.requestCode(id, AlarmScheduler.TYPE_DUE_DEMOTED)))
    }

    // ---------------- same family: the call-card quiet ----------------

    @Test fun cancelCallSnoozeKillsBothCallDeferTypes() {
        val id = 11L
        val now = System.currentTimeMillis()
        AlarmScheduler.scheduleAt(context, now + 90 * 60_000L, AlarmScheduler.TYPE_CSNOOZE, id)
        AlarmScheduler.scheduleAt(context, now + 90 * 60_000L, AlarmScheduler.TYPE_CALL_DEMOTED, id)

        AlarmScheduler.cancelCallSnooze(context, id)

        val codes = scheduledCodes()
        assertTrue(!codes.contains(AlarmScheduler.requestCode(id, AlarmScheduler.TYPE_CSNOOZE)))
        assertTrue("type-19 had ZERO cancel sites before v1.84",
            !codes.contains(AlarmScheduler.requestCode(id, AlarmScheduler.TYPE_CALL_DEMOTED)))
    }

    /** Cancelling one item's alarms must not touch a neighbour's (request codes are per id+type). */
    @Test fun cancelIsScopedToTheItem() {
        val now = System.currentTimeMillis()
        AlarmScheduler.scheduleAt(context, now + 60_000L * 90, AlarmScheduler.TYPE_DUE_DEMOTED, 100L)
        AlarmScheduler.scheduleAt(context, now + 60_000L * 90, AlarmScheduler.TYPE_DUE_DEMOTED, 101L)
        AlarmScheduler.cancelForItem(context, 100L)
        val codes = scheduledCodes()
        assertTrue(!codes.contains(AlarmScheduler.requestCode(100L, AlarmScheduler.TYPE_DUE_DEMOTED)))
        assertTrue("neighbour must survive",
            codes.contains(AlarmScheduler.requestCode(101L, AlarmScheduler.TYPE_DUE_DEMOTED)))
    }
}
