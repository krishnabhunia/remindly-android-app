package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56 phase 1 — alerts on/off resolution and the Calls rule engine.
 * The alarm card, cloud fetch and sound timer are framework-bound: device checklist.
 */
class V156Test {

    // ---- 1.1 alertsEnabledFor ----

    @Test fun alerts_default_on_everywhere() {
        val s = AppSettings()
        assertTrue(alertsEnabledFor(Tab.TASKS, s)); assertTrue(alertsEnabledFor(Tab.SHOP, s))
        assertTrue(alertsEnabledFor(Tab.LEARN, s)); assertTrue(alertsEnabledFor(null, s))
    }

    @Test fun per_tab_off_wins_over_global_on() {
        val s = AppSettings(shopAlertsOn = "OFF")
        assertTrue(!alertsEnabledFor(Tab.SHOP, s))
        assertTrue("other tabs unaffected", alertsEnabledFor(Tab.TASKS, s))
    }

    @Test fun per_tab_on_wins_over_global_off() {
        val s = AppSettings(alertsEnabled = false, callsAlertsOn = "ON")
        assertTrue(alertsEnabledFor(null, s))
        assertTrue("inheriting tabs follow the master off", !alertsEnabledFor(Tab.TASKS, s))
    }

    @Test fun rule_labels_are_human() {
        // v1.86 (N11): offsets speak MINUTES app-wide — "After 90 m", matching snoozeMinLabel.
        // (The v1.56 assertion self-defeated: it .replace()d the very drift it meant to catch.)
        assertEquals("After 60 m", callRuleLabel('O' to 60))
        assertEquals("After 90 m", callRuleLabel('O' to 90))
        assertEquals("At 18:00", callRuleLabel('C' to 1080))
    }

    // ---- schema ----

    @Test fun schema_is_30_and_defaults_are_safe() {
        val s = AppSettings()
        assertEquals(41, s.ver)   // v1.90: two-mode + shop entities
        assertEquals(7, s.alarmRingSeconds)     // v1.68: 7 s default, hard 3 s–3 min band
        assertTrue(s.alertsEnabled)
    }
}
