package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.71: Q9 Medium-tag inheritance and N2 degrade routing. */
class V171Test {

    // ---------------- Q9: global switch + per-tab override ----------------

    @Test fun mediumHiddenByDefaultEverywhere() {
        val s = AppSettings()
        listOf(Tab.TASKS, Tab.SHOP, Tab.LEARN).forEach { t ->
            assertFalse("$t", showPriorityTag(Priority.MEDIUM, s, t))
        }
    }

    @Test fun globalSwitchShowsMediumOnInheritingTabs() {
        val s = AppSettings(showMediumTag = true)
        listOf(Tab.TASKS, Tab.SHOP, Tab.LEARN).forEach { t ->
            assertTrue("$t", showPriorityTag(Priority.MEDIUM, s, t))
        }
    }

    @Test fun perTabOverrideBeatsGlobal_bothDirections() {
        val on = AppSettings(showMediumTag = true, shopShowMediumTag = 0)
        assertFalse("Hide beats global ON", showPriorityTag(Priority.MEDIUM, on, Tab.SHOP))
        assertTrue("other tabs still inherit", showPriorityTag(Priority.MEDIUM, on, Tab.TASKS))

        val off = AppSettings(showMediumTag = false, learnShowMediumTag = 1)
        assertTrue("Show beats global OFF", showPriorityTag(Priority.MEDIUM, off, Tab.LEARN))
        assertFalse("other tabs still inherit", showPriorityTag(Priority.MEDIUM, off, Tab.TASKS))
    }

    @Test fun fullTruthTable() {
        listOf(true, false).forEach { global ->
            listOf(-1 to global, 1 to true, 0 to false).forEach { (raw, expected) ->
                val s = AppSettings(showMediumTag = global, tasksShowMediumTag = raw)
                assertEquals("global=$global raw=$raw", expected, showMediumTagFor(s, Tab.TASKS))
            }
        }
    }

    @Test fun corruptOverrideCoercesToInherit() {
        val s = AppSettings(showMediumTag = true, tasksShowMediumTag = 99)
        assertTrue(showMediumTagFor(s, Tab.TASKS))
        val t = AppSettings(showMediumTag = false, tasksShowMediumTag = -7)
        assertFalse(showMediumTagFor(t, Tab.TASKS))
    }

    @Test fun otherPrioritiesAlwaysShowAndNullNever() {
        val s = AppSettings(showMediumTag = false, tasksShowMediumTag = 0)
        listOf(Priority.URGENT, Priority.HIGH, Priority.LOW).forEach {
            assertTrue("$it", showPriorityTag(it, s, Tab.TASKS))
        }
        assertFalse(showPriorityTag(null, s, Tab.TASKS))
        assertFalse(showPriorityTag(null, AppSettings(showMediumTag = true), Tab.TASKS))
    }

    @Test fun callsTabHasNoOverrideAndFollowsGlobal() {
        assertFalse(showMediumTagFor(AppSettings(tasksShowMediumTag = 1), null))
        assertTrue(showMediumTagFor(AppSettings(showMediumTag = true), null))
    }

    // ---------------- N2: degrade routing ----------------

    @Test fun degradeLabelsExistForEveryCode() {
        listOf(
            Degrades.EXACT_ALARM, Degrades.BATTERY, Degrades.CONTACTS,
            Degrades.NOTIFS, Degrades.SYNC, Degrades.CALENDAR
        ).forEach { assertTrue(it, Degrades.label(it).length > 10) }
    }

    @Test fun contactsOnlyReachesCallsAndCalendarOnlyTasks() {
        Degrades.active.value = setOf(Degrades.CONTACTS, Degrades.CALENDAR)
        assertEquals(listOf(Degrades.CONTACTS), Degrades.visibleFor(null))
        assertEquals(listOf(Degrades.CALENDAR), Degrades.visibleFor(Tab.TASKS))
        assertTrue(Degrades.visibleFor(Tab.SHOP).isEmpty())
        Degrades.active.value = emptySet()
    }

    @Test fun globalDegradeReachesEveryTab() {
        Degrades.active.value = setOf(Degrades.SYNC)
        listOf(Tab.TASKS, Tab.SHOP, Tab.LEARN, null).forEach {
            assertEquals("$it", listOf(Degrades.SYNC), Degrades.visibleFor(it))
        }
        Degrades.active.value = emptySet()
    }

    @Test fun dismissHidesBannerButClearResetsIt() {
        Degrades.active.value = setOf(Degrades.SYNC)
        Degrades.dismiss(Degrades.SYNC)
        assertTrue("dismissed hides the strip", Degrades.visibleFor(Tab.TASKS).isEmpty())
        assertTrue("but it stays live for the Settings dot", Degrades.SYNC in Degrades.active.value)
        Degrades.clear(Degrades.SYNC)
        Degrades.active.value = setOf(Degrades.SYNC)
        assertEquals("recurrence shows again", listOf(Degrades.SYNC), Degrades.visibleFor(Tab.TASKS))
        Degrades.active.value = emptySet()
    }

    @Test fun severalAtOnceShowOnlyTheFirst() {
        Degrades.active.value = setOf(Degrades.SYNC, Degrades.BATTERY, Degrades.NOTIFS)
        assertEquals(3, Degrades.visibleFor(Tab.TASKS).size)
        assertEquals(Degrades.BATTERY, Degrades.visibleFor(Tab.TASKS).first())
        Degrades.active.value = emptySet()
    }
}
