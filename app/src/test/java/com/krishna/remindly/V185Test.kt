package com.krishna.remindly

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.85 (N16) — Save must never write from the open-time snapshot.
 *
 * Standing-rule compliance:
 *  - END-STATE TESTS in the reporter's terms: "after cancel THEN save, snoozedUntil == null".
 *  - NEGATIVE CONTROL: with `editorSaveBase` reverted to pre-fix semantics (`= snapshot`), the
 *    resurrection tests FAIL. Run and recorded in the BACKLOG.
 *  - Per N6: real stores, real helper — no mirrored copies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V185Test {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "items.json").delete()
        File(context.filesDir, "places.json").delete()
        File(context.filesDir, "calls.json").delete()
        File(context.filesDir, "settings.json").delete()
        Stores.init(context)
        ItemStore.replaceAll(emptyList())
        CallStore.replaceAll(emptyList())
        PlaceStore.replaceAll(emptyList())
    }

    // ---------------- the pure helper ----------------

    @Test fun helperPrefersLiveFallsBackToSnapshot() {
        val snap = Item(id = 1L, tab = Tab.TASKS, title = "snap")
        val live = snap.copy(title = "live")
        assertEquals(live, editorSaveBase(snap, live))
        assertEquals(snap, editorSaveBase(snap, null))
        assertNull(editorSaveBase(null, live))
        assertNull(editorSaveBase<Item>(null, null))
    }

    // ---------------- THE report, in Krishna's terms ----------------

    /** "cancel the snooze, Save, reopen — the row is back". After the fix: it is not. */
    @Test fun afterCancelThenSaveSnoozeStaysNull() {
        val old = System.currentTimeMillis() + 90 * 60_000L
        val snapshot = Item(id = 5L, tab = Tab.TASKS, title = "t",
            alertType = "N", snoozedUntil = old)          // what the editor captured at open
        ItemStore.upsert(snapshot.copy(snoozedUntil = null)) // the x -> Yes cancel, in the store

        val base = editorSaveBase(snapshot, ItemStore.get(5L))!!
        val saved = base.copy(title = "edited")           // what Save writes
        ItemStore.upsert(saved)

        assertNull("after cancel THEN save, snoozedUntil == null", ItemStore.get(5L)!!.snoozedUntil)
        assertTrue("the row must stay gone",
            comingUpRows(ItemStore.get(5L)!!, System.currentTimeMillis()).none { it.cancellable })
    }

    /** The mirror: a snooze made from the card while the editor sat open must SURVIVE Save. */
    @Test fun freshSnoozeSurvivesASaveFromAStaleSnapshot() {
        val snapshot = Item(id = 6L, tab = Tab.TASKS, title = "t")          // no snooze at open
        val fresh = System.currentTimeMillis() + 90 * 60_000L
        ItemStore.upsert(quietDemote(snapshot, fresh))                       // Quiet on the card

        val base = editorSaveBase(snapshot, ItemStore.get(6L))!!
        ItemStore.upsert(base.copy(title = "edited"))

        val after = ItemStore.get(6L)!!
        assertEquals("the fresh snooze must survive the Save", fresh, after.snoozedUntil)
        assertEquals("the quiet demote must survive too", "N", after.alertType)
    }

    /** Done from the notification action mid-edit: Save must not resurrect not-done. */
    @Test fun doneFromNotificationSurvivesSave() {
        val snapshot = Item(id = 7L, tab = Tab.TASKS, title = "t")
        ItemStore.upsert(snapshot.copy(done = true, doneAt = 123L))

        val base = editorSaveBase(snapshot, ItemStore.get(7L))!!
        ItemStore.upsert(base.copy(title = "edited"))

        assertTrue(ItemStore.get(7L)!!.done)
        assertEquals(123L, ItemStore.get(7L)!!.doneAt)
    }

    /** A delete that lands mid-edit must not be resurrected by Save. */
    @Test fun deletionSurvivesSave() {
        val snapshot = Item(id = 8L, tab = Tab.TASKS, title = "t")
        ItemStore.upsert(snapshot.copy(deletedAt = 999L))

        val base = editorSaveBase(snapshot, ItemStore.get(8L))!!
        ItemStore.upsert(base.copy(title = "edited"))

        assertEquals(999L, ItemStore.get(8L)!!.deletedAt)
    }

    // ---------------- part B: the Calls editors ----------------

    /** A call snoozed from the card must keep its snooze through a note-sheet Save. */
    @Test fun callSnoozeSurvivesNoteSheetSave() {
        val snapshot = CallReminder(id = 21L, number = "123", source = CallSource.MANUAL)
        val fresh = System.currentTimeMillis() + 90 * 60_000L
        CallStore.upsert(snapshot.copy(snoozedUntil = fresh))               // CallEngine.snooze

        val base = editorSaveBase(snapshot, CallStore.get(21L))!!
        CallStore.upsert(base.copy(note = "edited"))

        assertEquals(fresh, CallStore.get(21L)!!.snoozedUntil)
    }

    /** Cleared-from-notification must survive both call sheets' Save. */
    @Test fun callClearedStateSurvivesSave() {
        val snapshot = CallReminder(id = 22L, number = "456", source = CallSource.AUTO)
        CallStore.upsert(snapshot.copy(done = true, doneAt = 5L, clearedNote = "spoke"))

        val base = editorSaveBase(snapshot, CallStore.get(22L))!!
        CallStore.upsert(base.copy(savedGroups = listOf("Work")))           // the contact-save shape

        val after = CallStore.get(22L)!!
        assertTrue(after.done)
        assertEquals("spoke", after.clearedNote)
        assertEquals(listOf("Work"), after.savedGroups)
    }

    // ---------------- companion 2: the merge is visible ----------------

    @Test fun staleFieldListsNameExactlyWhatDiffers() {
        val a = Item(id = 1L, tab = Tab.TASKS, title = "t", snoozedUntil = 1L)
        val b = a.copy(snoozedUntil = null, done = true)
        assertEquals(listOf("snoozedUntil", "done"), staleItemFields(a, b))
        assertTrue(staleItemFields(a, a).isEmpty())

        val c = CallReminder(id = 2L, number = "1", source = CallSource.AUTO, snoozedUntil = 9L)
        val d = c.copy(snoozedUntil = null, clearedNote = "x")
        assertEquals(listOf("snoozedUntil", "clearedNote"), staleCallFields(c, d))
        assertTrue(staleCallFields(c, c).isEmpty())
    }
}
