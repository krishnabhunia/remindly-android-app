package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.7 — N43 (checkbox on every group header), N42 ("Scheduled alerts" page) and N40 (items that
 * alarm from a surface you cannot reach). Everything a unit can prove is proven here: the per-tab
 * override precedence, the header state machine, bulk targets per view, item reachability, the
 * orphaned-tab sweep, the scheduled-rows builder (sources, flags, ordering, horizons), the section
 * table, reset scopes and schema 40. The checkbox glyphs, sheets, the page itself and the hide
 * warning are device checks (section T).
 */
class V27Test {

    private val now = 1_900_000_000_000L
    private fun item(id: Long, tab: Tab = Tab.TASKS, done: Boolean = false, personal: Boolean = false,
                     dueAt: Long? = now + 3600_000L, deleted: Long? = null, snooze: Long? = null, alert: String = "N") =
        Item(id = id, tab = tab, title = "i$id", done = done, personal = personal, dueAt = dueAt, deletedAt = deleted, snoozedUntil = snooze, alertType = alert)

    // ---------------------------------------------------------------- N43: switch precedence

    @Test fun groupCheck_perTabOverridesGlobal() {
        val on = AppSettings(groupHeaderCheck = true)
        assertTrue(groupCheckFor(Tab.TASKS, on))
        assertFalse(groupCheckFor(Tab.TASKS, on.copy(tasksGroupCheck = "OFF")))
        val off = AppSettings(groupHeaderCheck = false)
        assertFalse(groupCheckFor(Tab.LEARN, off))
        assertTrue(groupCheckFor(Tab.LEARN, off.copy(learnGroupCheck = "ON")))
        assertTrue(groupCheckFor(Tab.SHOP, on.copy(shopGroupCheck = "junk")))   // unknown → inherit
    }

    @Test fun groupCheck_defaultsOn_perTabInherit() {
        val d = AppSettings()
        assertTrue(d.groupHeaderCheck)
        assertEquals("INHERIT", d.tasksGroupCheck); assertEquals("INHERIT", d.learnGroupCheck); assertEquals("INHERIT", d.shopGroupCheck)
    }

    // ---------------------------------------------------------------- N43: header state + targets

    @Test fun groupState_activeView() {
        val none = listOf(item(1), item(2))
        assertEquals(GroupCheckState.NONE, groupState(none, isDoneView = false, personalLocked = true))
        val mixed = listOf(item(1), item(2, done = true))
        assertEquals(GroupCheckState.MIXED, groupState(mixed, false, true))
        val all = listOf(item(1, done = true), item(2, done = true))
        assertEquals(GroupCheckState.ALL, groupState(all, false, true))
        assertEquals(GroupCheckState.EMPTY, groupState(emptyList(), false, true))
        assertEquals(GroupCheckState.EMPTY, groupState(listOf(item(1, deleted = 5L)), false, true))
    }

    @Test fun groupState_doneViewIsAlwaysAll_andPersonalLocks() {
        assertEquals(GroupCheckState.ALL, groupState(listOf(item(1, done = true)), isDoneView = true, personalLocked = true))
        assertEquals(GroupCheckState.LOCKED, groupState(listOf(item(1, tab = Tab.SHOP, personal = true)), false, personalLocked = true))
        assertEquals(GroupCheckState.NONE, groupState(listOf(item(1, tab = Tab.SHOP, personal = true)), false, personalLocked = false))
    }

    @Test fun bulkTargets_completeOnlyPending_restoreOnlyDone_skipDeleted() {
        val items = listOf(item(1), item(2, done = true), item(3, deleted = 9L), item(4))
        assertEquals(listOf(1L, 4L), bulkTargets(items, isDoneView = false))
        assertEquals(listOf(2L), bulkTargets(items, isDoneView = true))
    }

    // ---------------------------------------------------------------- N40: reachability

    @Test fun itemReachable_hiddenTab_orLockedPersonal_isNotReachable() {
        val s = AppSettings(showLearn = false)
        assertTrue(itemReachable(item(1, Tab.TASKS), s))
        assertFalse("Learn hidden", itemReachable(item(2, Tab.LEARN), s))
        assertTrue("Shop is always reachable (Shop mode)", itemReachable(item(3, Tab.SHOP), s))
        assertFalse("personal + locked", itemReachable(item(4, Tab.SHOP, personal = true), s, personalLocked = true))
        assertTrue("personal + unlocked", itemReachable(item(4, Tab.SHOP, personal = true), s, personalLocked = false))
    }

    @Test fun orphanedAlertTabs_findsHiddenTabsThatStillAlert() {
        val items = listOf(item(1, Tab.LEARN), item(2, Tab.TASKS), item(3, Tab.LEARN, done = true))
        assertEquals(listOf(Tab.LEARN), orphanedAlertTabs(items, AppSettings(showLearn = false)))
        assertTrue("silenced tab is not an orphan", orphanedAlertTabs(items, AppSettings(showLearn = false, learnAlertsOn = "OFF")).isEmpty())
        assertTrue(orphanedAlertTabs(items, AppSettings()).isEmpty())
        assertTrue("no due date = nothing armed", orphanedAlertTabs(listOf(item(9, Tab.LEARN, dueAt = null)), AppSettings(showLearn = false)).isEmpty())
    }

    // ---------------------------------------------------------------- N42: scheduled rows

    private fun shop(id: Long, name: String, fence: Boolean = true, types: String? = null) =
        Shop(id = id, name = name, lat = if (fence) 1.0 else null, lng = if (fence) 2.0 else null, arriveTypes = types)

    @Test fun scheduledRows_coversEverySource_sortedByTime_locationLast() {
        val items = listOf(item(1, Tab.TASKS, dueAt = now + 2 * 3600_000L), item(2, Tab.LEARN, dueAt = now + 3600_000L))
        val calls = listOf(CallReminder(id = 7, number = "111", name = "Ramesh", source = CallSource.AUTO, snoozedUntil = now + 30 * 60_000L))
        val shops = listOf(shop(3, "D-Mart", types = "A"), shop(4, "No fence", fence = false))
        val places = listOf(GeoPlace(id = 5, name = "Home", lat = 1.0, lng = 2.0, radius = 150f, trigger = TriggerType.LEAVE))
        val rows = scheduledRows(items, calls, shops, places, AppSettings(), now)
        assertEquals(listOf(SchedSource.CALLS, SchedSource.LEARN, SchedSource.TASKS, SchedSource.SHOP_ARRIVAL, SchedSource.PLACE), rows.map { it.source })
        assertEquals("Ramesh", rows[0].title); assertEquals("snoozed", rows[0].reason)
        assertNull("location rows have no time", rows[3].at)
        assertEquals("A", rows[3].style)                                   // resolved per-shop style
        assertTrue(rows[4].reason.startsWith("leave fence"))
        assertTrue("unfenced shop is not armed", rows.none { it.title == "No fence" })
    }

    @Test fun scheduledRows_flagsUnreachableAndSilenced() {
        val items = listOf(item(1, Tab.LEARN), item(2, Tab.SHOP, personal = true), item(3, Tab.TASKS))
        val s = AppSettings(showLearn = false, tasksAlertsOn = "OFF")
        val rows = scheduledRows(items, emptyList(), emptyList(), emptyList(), s, now, personalLocked = true)
        val learn = rows.first { it.source == SchedSource.LEARN }; val buy = rows.first { it.source == SchedSource.BUY }; val task = rows.first { it.source == SchedSource.TASKS }
        assertEquals(listOf(SchedFlag.HIDDEN_TAB), learn.flags)
        assertEquals(listOf(SchedFlag.PERSONAL), buy.flags)
        assertEquals(listOf(SchedFlag.ALERTS_OFF), task.flags)
        assertTrue(learn.unreachable && buy.unreachable && task.unreachable)
        val unlocked = scheduledRows(items, emptyList(), emptyList(), emptyList(), s, now, personalLocked = false)
        assertTrue(unlocked.first { it.source == SchedSource.BUY }.flags.isEmpty())
    }

    @Test fun scheduledRows_ignoresDoneDeletedAndPast() {
        val items = listOf(item(1, done = true), item(2, deleted = 5L), item(3, dueAt = now - 60_000L), item(4))
        val rows = scheduledRows(items, emptyList(), emptyList(), emptyList(), AppSettings(), now)
        assertEquals(listOf(4L), rows.map { it.itemId })
    }

    @Test fun schedWithin_horizons_keepLocationRows() {
        val rows = listOf(
            SchedRow(now + 3600_000L, SchedSource.TASKS, "due", "a", "N", emptyList()),
            SchedRow(now + 3L * 24 * 3600_000L, SchedSource.TASKS, "due", "b", "N", emptyList()),
            SchedRow(null, SchedSource.PLACE, "fence", "c", "N", emptyList())
        )
        assertEquals(listOf("a", "c"), schedWithin(rows, now, 24L * 3600_000L).map { it.title })
        assertEquals(listOf("a", "b", "c"), schedWithin(rows, now, 7L * 24 * 3600_000L).map { it.title })
        assertEquals(3, schedWithin(rows, now, null).size)
    }

    // ---------------------------------------------------------------- settings deck & schema

    @Test fun listsAndSched_sections_generalPageOnly() {
        assertTrue(settingsSectionVisible(null, "lists")); assertTrue(settingsSectionVisible(null, "sched"))
        listOf("BUY", "SHOPS", "PRODUCTS", "TASKS", "LEARN", "CALLS").forEach {
            assertFalse(settingsSectionVisible(it, "lists")); assertFalse(settingsSectionVisible(it, "sched"))
        }
    }

    @Test fun resets_restoreGroupCheckScopes() {
        val s = AppSettings(groupHeaderCheck = false, tasksGroupCheck = "OFF", learnGroupCheck = "ON", shopGroupCheck = "OFF")
        assertTrue(resetSettingsFor(s, null).groupHeaderCheck)
        assertEquals("OFF", resetSettingsFor(s, null).tasksGroupCheck)          // General leaves per-tab alone
        assertEquals("INHERIT", resetSettingsFor(s, "TASKS").tasksGroupCheck)
        assertEquals("INHERIT", resetSettingsFor(s, "LEARN").learnGroupCheck)
        assertEquals("INHERIT", resetSettingsFor(s, "BUY").shopGroupCheck)
        assertEquals("ON", resetSettingsFor(s, "TASKS").learnGroupCheck)        // scoped
    }

    @Test fun v27_schemaIs40_andHealCoatsOverrides() {
        assertEquals(41, AppSettings().ver)
        val legacy = com.google.gson.Gson().fromJson("{\"ver\":39}", AppSettings::class.java)
        val h = healSettings(legacy)
        assertEquals("INHERIT", h.tasksGroupCheck); assertEquals("INHERIT", h.shopGroupCheck)
        h.copy(groupHeaderCheck = false)
    }
}
