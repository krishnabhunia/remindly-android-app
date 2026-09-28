package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.34 — sync groundwork (Firebase-independent, so fully unit-testable here).
 *
 * S2: device-tagged ids — the precondition for collision-free multi-device merge.
 * Groundwork: GeoPlace merge-stamp/tombstone fields; Backup.settingsForSync strip.
 * (The Firestore transport itself — S1/S3-wire/S4-wire/S5 — needs a real Firebase project
 * and is not buildable/verifiable in this environment; it is Phase 1b.)
 */
class V134Test {

    // ---------- S2: device-tagged ids (Ids.composeId is pure) ----------

    @Test fun tag_is_packed_into_high_bits_and_recoverable() {
        val now = 1_800_000_000_000L   // a plausible current-era millis
        val id = Ids.composeId(now, prev = 0L, tag = 1234)
        assertEquals(1234, Ids.tagOf(id))
    }

    @Test fun legacy_untagged_id_reads_as_tag_zero() {
        // ids minted before v1.34 are plain millis → tag 0
        assertEquals(0, Ids.tagOf(1_726_000_000_000L))
    }

    @Test fun two_devices_never_collide_over_a_tight_burst() {
        // Simulate two devices minting ids at the SAME millisecond stream, different tags.
        var prevA = 0L
        var prevB = 0L
        val a = HashSet<Long>()
        val b = HashSet<Long>()
        val now = 1_800_000_000_000L
        for (k in 0 until 5000) {
            // same instant repeatedly → forces the monotonic +1 path on both
            val ia = Ids.composeId(now, prevA, tag = 1); prevA = ia; a.add(ia)
            val ib = Ids.composeId(now, prevB, tag = 2); prevB = ib; b.add(ib)
        }
        assertEquals("device A ids all unique", 5000, a.size)
        assertEquals("device B ids all unique", 5000, b.size)
        assertTrue("no id shared across the two devices", a.intersect(b).isEmpty())
    }

    @Test fun monotonic_bump_stays_within_the_devices_tag_range() {
        val now = 1_800_000_000_000L
        var prev = 0L
        for (k in 0 until 1000) {
            val id = Ids.composeId(now, prev, tag = 7)
            assertEquals("stays in tag 7's range even under +1 bumps", 7, Ids.tagOf(id))
            assertTrue("strictly increasing", id > prev)
            prev = id
        }
    }

    @Test fun later_time_produces_a_larger_id_same_tag() {
        val a = Ids.composeId(1_800_000_000_000L, 0L, tag = 3)
        val b = Ids.composeId(1_800_000_001_000L, a, tag = 3)
        assertTrue(b > a)
        assertEquals(3, Ids.tagOf(a)); assertEquals(3, Ids.tagOf(b))
    }

    // ---------- Groundwork: GeoPlace carries stamps/tombstone through heal ----------

    @Test fun healPlace_preserves_updatedAt_and_deletedAt() {
        val p = GeoPlace(
            id = 5L, name = "Home", lat = 1.0, lng = 2.0, radius = 100f,
            trigger = TriggerType.LEAVE, updatedAt = 999L, deletedAt = 111L
        )
        val healed = healPlace(p)
        assertEquals(999L, healed.updatedAt)
        assertEquals(111L, healed.deletedAt)
    }

    @Test fun geoPlace_defaults_are_zero_and_null() {
        val p = GeoPlace(id = 1L, name = "x", lat = 0.0, lng = 0.0, radius = 50f, trigger = TriggerType.ARRIVE)
        assertEquals(0L, p.updatedAt)
        assertNull(p.deletedAt)
    }

    // ---------- Groundwork: settingsForSync strips device-local bookkeeping ----------

    @Test fun settingsForSync_zeroes_local_bookkeeping_only() {
        val s = AppSettings(
            lastSyncAt = 123L, lastDataBackupAt = 456L, 
            activeSwipeAction = "MOVE", theme = "DARK", revSwipeDelete = true
        )
        val out = Backup.settingsForSync(s)
        assertEquals(0L, out.lastSyncAt)
        assertEquals(0L, out.lastDataBackupAt)
        // everything genuinely user-facing is untouched
        assertEquals("DARK", out.theme)
        assertTrue(out.revSwipeDelete)
        assertNotEquals(s.lastSyncAt, out.lastSyncAt)
    }
}
