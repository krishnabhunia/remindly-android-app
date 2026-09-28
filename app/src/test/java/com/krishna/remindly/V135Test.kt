package com.krishna.remindly

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.35 — cross-device sync (Phase 1b) pure/merge coverage.
 *
 * The Firestore transport (SyncRepo/SyncAuth) needs a live Firebase backend and a device, so it
 * is NOT unit-tested here — that is a device checklist. What IS covered: the merge/tombstone
 * behaviour sync relies on (places now full-LWW with soft-delete), and the settings strip that
 * keeps the sync toggle and bookkeeping out of the cloud payload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V135Test {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "places.json").delete()
        Stores.init(context)
        PlaceStore.replaceAll(emptyList())
    }

    private fun place(id: Long, name: String) = GeoPlace(
        id = id, name = name, lat = 1.0, lng = 2.0, radius = 100f, trigger = TriggerType.LEAVE
    )

    // ---------- places soft-delete + tombstone visibility ----------

    @Test fun delete_softDeletes_place_and_hides_it_but_keeps_a_tombstone() {
        PlaceStore.upsert(place(1, "Home"))
        assertNotNull(PlaceStore.get(1))
        assertEquals(1, PlaceStore.active().size)

        PlaceStore.delete(1)

        assertNull("get() ignores tombstones", PlaceStore.get(1))
        assertTrue("active() excludes tombstones", PlaceStore.active().isEmpty())
        val raw = PlaceStore.places.value.first { it.id == 1L }
        assertNotNull("tombstone kept for sync", raw.deletedAt)
        assertTrue("tombstone carries a fresh stamp", raw.updatedAt > 0)
    }

    @Test fun upsert_stamps_updatedAt() {
        PlaceStore.upsert(place(2, "Work"))
        assertTrue(PlaceStore.get(2)!!.updatedAt > 0)
    }

    // ---------- places full-LWW merge (was additive-only before v1.35) ----------

    @Test fun mergeData_places_takes_the_newer_edit() {
        val old = place(3, "Gym").copy(updatedAt = 1000L)
        PlaceStore.replaceAll(listOf(old))
        val newer = place(3, "Gym (renamed)").copy(updatedAt = 2000L)
        Backup.mergeData(context, Backup.DataBlob(places = listOf(newer)))
        assertEquals("Gym (renamed)", PlaceStore.get(3)!!.name)
    }

    @Test fun mergeData_places_keeps_local_when_incoming_is_older() {
        val local = place(4, "Local").copy(updatedAt = 5000L)
        PlaceStore.replaceAll(listOf(local))
        val stale = place(4, "Stale").copy(updatedAt = 1000L)
        Backup.mergeData(context, Backup.DataBlob(places = listOf(stale)))
        assertEquals("Local", PlaceStore.get(4)!!.name)
    }

    @Test fun mergeData_places_propagates_a_remote_delete() {
        PlaceStore.replaceAll(listOf(place(5, "ToDelete").copy(updatedAt = 1000L)))
        assertNotNull(PlaceStore.get(5))
        // a tombstone arriving from another device (higher stamp) must win → place disappears
        val tombstone = place(5, "ToDelete").copy(updatedAt = 2000L, deletedAt = 2000L)
        Backup.mergeData(context, Backup.DataBlob(places = listOf(tombstone)))
        assertNull("remote delete propagated", PlaceStore.get(5))
    }

    // ---------- settings strip keeps the toggle + bookkeeping out of the cloud ----------

    @Test fun settingsForSync_strips_cloudSync_and_bookkeeping() {
        val s = AppSettings(
            cloudSync = true, lastSyncAt = 1L, lastDataBackupAt = 2L, 
            theme = "DARK"
        )
        val out = Backup.settingsForSync(s)
        assertFalse("sync toggle never leaves the device", out.cloudSync)
        assertEquals(0L, out.lastSyncAt)
        assertEquals(0L, out.lastDataBackupAt)
        assertEquals("real prefs still sync", "DARK", out.theme)
    }
}
