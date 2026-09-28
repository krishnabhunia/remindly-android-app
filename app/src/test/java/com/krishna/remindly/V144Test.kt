package com.krishna.remindly

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.44 UI 1 — per-card element visibility resolver. Global defaults apply unless a per-tab
 * override (ON/OFF) is set; an absent override inherits the global. Name is always shown (not a field).
 */
class V144Test {

    @Test fun defaults_show_everything() {
        val cf = cardFieldsFor(AppSettings(), Tab.TASKS)
        assertTrue(cf.priority); assertTrue(cf.dateTime); assertTrue(cf.checkbox); assertTrue(cf.repeat)
    }

    @Test fun global_default_off_hides_across_tabs_when_inheriting() {
        val s = AppSettings(cardShowCheckbox = false)
        assertFalse(cardFieldsFor(s, Tab.TASKS).checkbox)
        assertFalse(cardFieldsFor(s, Tab.SHOP).checkbox)
    }

    @Test fun per_tab_override_wins_over_global() {
        // global priority ON, but Shop overridden OFF
        val s = AppSettings(cardShowPriority = true, tabCardOv = mapOf("sCardPriority" to "OFF"))
        assertTrue("Tasks inherits global ON", cardFieldsFor(s, Tab.TASKS).priority)
        assertFalse("Shop overridden OFF", cardFieldsFor(s, Tab.SHOP).priority)
    }

    @Test fun per_tab_override_can_show_when_global_off() {
        val s = AppSettings(cardShowRepeat = false, tabCardOv = mapOf("lCardRepeat" to "ON"))
        assertFalse("Tasks inherits global OFF", cardFieldsFor(s, Tab.TASKS).repeat)
        assertTrue("Learn overridden ON", cardFieldsFor(s, Tab.LEARN).repeat)
    }

    @Test fun calls_tab_uses_c_prefix() {
        val s = AppSettings(tabCardOv = mapOf("cCardDateTime" to "OFF"))
        assertFalse(cardFieldsFor(s, null).dateTime)          // Calls = null tab, prefix "c"
        assertTrue(cardFieldsFor(s, Tab.TASKS).dateTime)      // unaffected
    }
}
