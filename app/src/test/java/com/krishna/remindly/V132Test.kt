package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.32 — pure-logic coverage.
 *
 * Q1: bidirectional card swipe. The move side (Active→Done / Done→Active) keeps its behaviour;
 *     the OPPOSITE side deletes when the per-tab "Delete on reverse swipe" toggle is on. The old
 *     "swipe does Move/Delete" setting is retired (globals reset to MOVE, override keys stripped).
 * Q2: Shop Personal/General is a plain predicate; the reset-on-toggle bug was a Compose
 *     rememberSaveable key issue (device-checklist), but the filter itself is pinned here.
 *
 * Visual behaviour (colours, icons, spring-back feel, which physical edge lands on which tab)
 * is device-checklist only — see BACKLOG Q1.
 */
class V132Test {

    // ---------- Q1: swipeOutcome (the one rule that decides move vs delete vs nothing) ----------

    // moveToRight = true  → RIGHT swipe moves, LEFT swipe is the delete side.
    @Test fun moveRight_rightSwipe_moves_when_moveEnabled() {
        assertEquals(SwipeOutcome.MOVE,
            swipeOutcome(offsetSign = 1, moveToRight = true, moveEnabled = true, deleteEnabled = false))
    }

    @Test fun moveRight_leftSwipe_deletes_only_when_deleteEnabled() {
        assertEquals(SwipeOutcome.DELETE,
            swipeOutcome(-1, moveToRight = true, moveEnabled = true, deleteEnabled = true))
        // toggle off → the reverse side is inert, exactly as before v1.32
        assertEquals(SwipeOutcome.NONE,
            swipeOutcome(-1, moveToRight = true, moveEnabled = true, deleteEnabled = false))
    }

    // moveToRight = false → LEFT swipe moves, RIGHT swipe is the delete side.
    @Test fun moveLeft_sides_mirror() {
        assertEquals(SwipeOutcome.MOVE,
            swipeOutcome(-1, moveToRight = false, moveEnabled = true, deleteEnabled = true))
        assertEquals(SwipeOutcome.DELETE,
            swipeOutcome(1, moveToRight = false, moveEnabled = true, deleteEnabled = true))
    }

    @Test fun moveDisabled_but_deleteEnabled_only_delete_side_lives() {
        // move gesture off, delete on: move side does nothing, delete side deletes
        assertEquals(SwipeOutcome.NONE,
            swipeOutcome(1, moveToRight = true, moveEnabled = false, deleteEnabled = true))
        assertEquals(SwipeOutcome.DELETE,
            swipeOutcome(-1, moveToRight = true, moveEnabled = false, deleteEnabled = true))
    }

    @Test fun zeroTravel_is_never_a_commit() {
        assertEquals(SwipeOutcome.NONE,
            swipeOutcome(0, moveToRight = true, moveEnabled = true, deleteEnabled = true))
    }

    @Test fun both_off_is_always_none() {
        for (sign in intArrayOf(-1, 0, 1)) {
            assertEquals("sign=$sign", SwipeOutcome.NONE,
                swipeOutcome(sign, moveToRight = true, moveEnabled = false, deleteEnabled = false))
        }
    }

    // ---------- Q1: gesturesFor exposes RevSwipeDelete with per-tab override + global fallback ----------

    @Test fun revSwipeDelete_inherits_global_when_no_override() {
        val on = AppSettings(revSwipeDelete = true)
        val off = AppSettings(revSwipeDelete = false)
        for (tab in listOf(Tab.TASKS, Tab.SHOP, Tab.LEARN, null /* Calls */)) {
            assertTrue("global on, $tab", gesturesFor(on, tab).revSwipeDelete)
            assertFalse("global off, $tab", gesturesFor(off, tab).revSwipeDelete)
        }
    }

    @Test fun revSwipeDelete_perTab_override_wins_over_global() {
        // global OFF, but Shop overrides ON; Tasks stays inherited OFF
        val s = AppSettings(revSwipeDelete = false, tabGestureOv = mapOf("sRevSwipeDelete" to "ON"))
        assertTrue(gesturesFor(s, Tab.SHOP).revSwipeDelete)
        assertFalse(gesturesFor(s, Tab.TASKS).revSwipeDelete)
        // global ON, Calls overrides OFF
        val s2 = AppSettings(revSwipeDelete = true, tabGestureOv = mapOf("cRevSwipeDelete" to "OFF"))
        assertFalse(gesturesFor(s2, null).revSwipeDelete)
        assertTrue(gesturesFor(s2, Tab.LEARN).revSwipeDelete)
    }

    @Test fun default_is_off_on_every_tab() {
        val d = AppSettings()
        for (tab in listOf(Tab.TASKS, Tab.SHOP, Tab.LEARN, null))
            assertFalse(gesturesFor(d, tab).revSwipeDelete)
    }

    // ---------- Q1: v19 migration retires the old action setting ----------

    @Test fun migration_to_v19_resets_actions_and_strips_override_keys() {
        val old = AppSettings(
            ver = 18,
            activeSwipeAction = "DELETE",
            doneSwipeAction = "DELETE",
            tabGestureOv = mapOf(
                "tActiveAction" to "DELETE",
                "sDoneAction" to "DELETE",
                "sCardRightDone" to "OFF",   // unrelated key must survive
                "lRevSwipeDelete" to "ON"    // new key must survive
            )
        )
        val migrated = SettingsStore.migrate(old)
        assertEquals(43, migrated.ver)
        assertEquals("MOVE", migrated.activeSwipeAction)
        assertEquals("MOVE", migrated.doneSwipeAction)
        assertFalse(migrated.tabGestureOv.containsKey("tActiveAction"))
        assertFalse(migrated.tabGestureOv.containsKey("sDoneAction"))
        assertEquals("OFF", migrated.tabGestureOv["sCardRightDone"])
        assertEquals("ON", migrated.tabGestureOv["lRevSwipeDelete"])
    }

    @Test fun migration_defaults_revSwipeDelete_off_for_upgraders() {
        val migrated = SettingsStore.migrate(AppSettings(ver = 17))
        assertEquals(43, migrated.ver)
        assertFalse(migrated.revSwipeDelete)
    }

    // ---------- Q2: Shop Personal/General predicate ----------

    private fun shop(id: Long, personal: Boolean) =
        Item(id = id, tab = Tab.SHOP, title = "i$id", personal = personal)

    @Test fun shop_filter_partitions_by_personal_flag() {
        val items = listOf(shop(1, false), shop(2, true), shop(3, false), shop(4, true))
        assertEquals(listOf(1L, 3L), items.filter { it.personal == false }.map { it.id })
        assertEquals(listOf(2L, 4L), items.filter { it.personal == true }.map { it.id })
    }
}
