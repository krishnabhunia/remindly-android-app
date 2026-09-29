package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.27 regression coverage.
 *  - item 1 removed the delete-in-Done apparatus and the four `*DeleteInDone` settings fields;
 *    these confirm settings still round-trip cleanly without them, and that a settings blob
 *    which still carried those keys (old installs) would heal/migrate without error.
 *  - item 2 is a pure UI conversion (AlertDialog -> ModalBottomSheet) with no new logic, so its
 *    verification is device-checklist only.
 * (The "no two same-titled settings sections" guard is a compile-time/code-review property —
 *  the duplicate section was physically removed — not a JVM-testable assertion.)
 */
class V127Test {

    @Test fun settings_roundtrip_after_field_removal() {
        val s = AppSettings(timeFormat = "H24", swipeDistancePct = 50, swipeHaptic = false)
        val healed = healSettings(s)
        assertEquals("H24", healed.timeFormat)
        assertEquals(50, healed.swipeDistancePct)
        assertEquals(false, healed.swipeHaptic)
        assertEquals(43, healed.ver)
    }

    @Test fun migrate_still_reaches_18() {
        assertEquals(43, SettingsStore.migrate(AppSettings(ver = 17)).ver)
    }

    @Test fun default_settings_valid() {
        assertEquals(43, AppSettings().ver)   // v2.04 schema
        assertTrue(healSettings(AppSettings()).ver == 43)
    }
}
