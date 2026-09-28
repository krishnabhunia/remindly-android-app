package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.47 Feature 2 — task types.
 *  2a: a task with NO due date groups under the day it was added (already the behaviour; pinned here).
 *  2c: new items can default to a due time OR to date-only, globally and per tab.
 */
class V147Test {

    // ---- 2a: no-due-date items group under their creation day ----

    @Test fun undated_task_groups_under_the_day_it_was_added() {
        val created = 1_700_000_000_000L
        val undated = Item(id = 1, tab = Tab.TASKS, title = "x", dueAt = null, createdAt = created)
        assertEquals(created, groupBasis(undated, done = false))
    }

    @Test fun dated_task_groups_under_its_due_date() {
        val created = 1_700_000_000_000L
        val due = created + 5 * 86_400_000L
        val dated = Item(id = 2, tab = Tab.TASKS, title = "y", dueAt = due, createdAt = created)
        assertEquals(due, groupBasis(dated, done = false))
    }

    // ---- 2c: due time vs date-only ----

    @Test fun default_is_timed() {
        assertTrue(newDueTimedFor(Tab.TASKS, AppSettings()))
    }

    @Test fun global_off_makes_every_inheriting_tab_date_only() {
        val s = AppSettings(globalNewDueTimed = false)
        assertFalse(newDueTimedFor(Tab.TASKS, s))
        assertFalse(newDueTimedFor(Tab.SHOP, s))
        assertFalse(newDueTimedFor(Tab.LEARN, s))
    }

    @Test fun per_tab_override_wins_both_ways() {
        val s1 = AppSettings(globalNewDueTimed = true, tasksNewDueTimed = "OFF")
        assertFalse("Tasks overridden to date-only", newDueTimedFor(Tab.TASKS, s1))
        assertTrue("Shop still inherits ON", newDueTimedFor(Tab.SHOP, s1))

        val s2 = AppSettings(globalNewDueTimed = false, learnNewDueTimed = "ON")
        assertTrue("Learn overridden to timed", newDueTimedFor(Tab.LEARN, s2))
        assertFalse("Tasks still inherits OFF", newDueTimedFor(Tab.TASKS, s2))
    }

    @Test fun date_only_default_uses_the_global_default_due_time() {
        // Tasks must INHERIT for the global values to apply (tasksNewDueMode defaults to TOMORROW).
        // timed: uses the new-item time (09:00); date-only: falls back to defaultDueMinutes (18:00)
        val timed = AppSettings(
            tasksNewDueMode = "INHERIT",
            globalNewDueMode = "TODAY", globalNewDueMinutes = 540, defaultDueMinutes = 1080
        )
        val dateOnly = timed.copy(globalNewDueTimed = false)
        val a = defaultNewDue(Tab.TASKS, timed)!!
        val b = defaultNewDue(Tab.TASKS, dateOnly)!!
        assertEquals(540, minutesOfDayOf(a))
        assertEquals(1080, minutesOfDayOf(b))
    }

    @Test fun per_tab_settings_apply_when_not_inheriting() {
        // tasksNewDueMode defaults to TOMORROW, so the per-tab time (600) wins over the global.
        val s = AppSettings(globalNewDueMinutes = 540, tasksNewDueMinutes = 600)
        assertEquals(600, minutesOfDayOf(defaultNewDue(Tab.TASKS, s)!!))
    }

    @Test fun mode_off_still_means_no_due_date_at_all() {
        val s = AppSettings(globalNewDueMode = "OFF", tasksNewDueMode = "INHERIT")
        assertEquals(null, defaultNewDue(Tab.TASKS, s))
    }
}
