package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.26 unit coverage (pure logic only — the visual/behavioural parts are device-checklist):
 *  - item 5: CallLabels multi-select set logic + the single "Delete After" invariant.
 *  - item 5: savedGroups persists through healCall and defaults empty for old records.
 *  - item 5: schema default is 20 and migrate lifts old settings to 20.
 * (item 3's removal and item 4's mic-removal are verified by a source scan at build; item 4's
 *  FAB-vs-bar swap is Compose UI and lives on the device checklist.)
 */
class V126Test {

    // ---------------- item 5: CallLabels ----------------

    @Test fun toggle_adds_then_removes() {
        assertEquals(setOf("Jobs"), CallLabels.toggle(emptySet(), "Jobs"))
        assertEquals(emptySet<String>(), CallLabels.toggle(setOf("Jobs"), "Jobs"))
        assertEquals(setOf("Jobs", "Temp Saved Names"),
            CallLabels.toggle(setOf("Jobs"), "Temp Saved Names"))
    }

    @Test fun empty_set_is_valid_no_labels() {
        // "I don't add any labels" — toggling one on then off returns to empty.
        val once = CallLabels.toggle(emptySet(), "Jobs")
        assertEquals(emptySet<String>(), CallLabels.toggle(once, "Jobs"))
    }

    @Test fun delete_after_stays_single() {
        val s1 = CallLabels.setDeleteAfter(setOf("Jobs"), "Delete After Mar 2027")
        assertEquals(setOf("Jobs", "Delete After Mar 2027"), s1)
        // Changing the month must not leave two dated entries.
        val s2 = CallLabels.setDeleteAfter(s1, "Delete After Apr 2028")
        assertEquals(setOf("Jobs", "Delete After Apr 2028"), s2)
        assertEquals(1, s2.count { CallLabels.isDeleteAfter(it) })
    }

    @Test fun clear_delete_after_leaves_the_rest() {
        val s = setOf("Jobs", "Temp Saved Names", "Delete After Jan 2026")
        assertEquals(setOf("Jobs", "Temp Saved Names"), CallLabels.clearDeleteAfter(s))
    }

    @Test fun is_delete_after_recognises_only_dated_labels() {
        assertTrue(CallLabels.isDeleteAfter("Delete After Jan 2026"))
        assertFalse(CallLabels.isDeleteAfter("Jobs"))
        assertFalse(CallLabels.isDeleteAfter("Temp Saved Names"))
    }

    // ---------------- item 5: savedGroups persistence via healCall ----------------

    @Test fun healCall_preserves_savedGroups() {
        val r = CallReminder(
            id = 1, number = "999", source = CallSource.MANUAL,
            savedGroups = listOf("Jobs", "Temp Saved Names")
        )
        assertEquals(listOf("Jobs", "Temp Saved Names"), healCall(r).savedGroups)
    }

    @Test fun healCall_defaults_savedGroups_empty_for_old_records() {
        val r = CallReminder(id = 2, number = "888", source = CallSource.MANUAL)
        assertEquals(emptyList<String>(), healCall(r).savedGroups)
    }

    // ---------------- item 5: schema ----------------

    @Test fun schema_default_is_18() {
        assertEquals(43, AppSettings().ver)   // v2.04 schema
    }

    @Test fun migrate_lifts_old_settings_to_18() {
        assertEquals(43, SettingsStore.migrate(AppSettings(ver = 17)).ver)
        assertEquals(43, SettingsStore.migrate(AppSettings(ver = 17)).ver)
    }
}
