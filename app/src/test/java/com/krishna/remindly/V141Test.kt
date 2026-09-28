package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.41 Feature 1 — tab visibility helpers (pure). Settings (index 4) is always visible; the nav
 * hides hidden tabs and the app falls back to the first visible tab when the current one is hidden.
 */
class V141Test {

    @Test fun all_tabs_visible_by_default() {
        val s = AppSettings()
        // v2.00 (N31): index 1 (Shop) is NEVER in the Task-mode bar — Shop mode owns it.
        listOf(0, 2, 3, 4).forEach { assertTrue("tab $it visible", isTabVisible(s, it)) }
        assertFalse("Shop never in Task-mode bar", isTabVisible(s, 1))
        assertEquals(0, firstVisibleTab(s))
    }

    @Test fun settings_tab_is_always_visible() {
        val s = AppSettings(showTasks = false, showLearn = false, showCalls = false)
        assertFalse(isTabVisible(s, 0))
        assertTrue("Settings always visible", isTabVisible(s, 4))
        assertEquals("all content tabs hidden → Settings", 4, firstVisibleTab(s))
    }

    @Test fun first_visible_skips_hidden() {
        val s = AppSettings(showTasks = false)
        assertEquals(2, firstVisibleTab(s))   // Learn
    }

    @Test fun next_visible_forward_skips_hidden() {
        val s = AppSettings(showLearn = false)
        // from Tasks(0) forward → Shop(1) hidden, Learn(2) hidden → Calls(3)
        assertEquals(3, nextVisibleTab(s, 0, forward = true))
    }

    @Test fun next_visible_backward_skips_hidden() {
        val s = AppSettings()
        // from Learn(2) backward → Shop(1) always hidden (v2.00) → Tasks(0)
        assertEquals(0, nextVisibleTab(s, 2, forward = false))
    }

    @Test fun next_visible_returns_source_when_none_beyond() {
        val s = AppSettings()
        // from Settings(4) forward → nothing beyond → stays 4
        assertEquals(4, nextVisibleTab(s, 4, forward = true))
    }
}
