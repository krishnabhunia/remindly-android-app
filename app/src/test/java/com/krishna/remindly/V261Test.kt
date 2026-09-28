package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.6.1 (N39) — Krishna's report: "when in settings in shop mode and tabs are clicked such as Buy
 * or Shop it doesn't go to tab. It has the same window settings."
 *
 * The per-tab settings page is a FULL-SCREEN OVERLAY inside the content Box, not a tab destination.
 * Task-mode navigation always closed it first; the Shop-mode navigator (and the mode flip) did not,
 * so the tab changed underneath while the overlay stayed on screen. navAction() is now the single
 * decision every navigator asks, and these tests pin it per source. The overlay itself, the tap and
 * the leave-guard popup are device checks (section Q).
 */
class V261Test {

    // ---------------------------------------------------------------- the reported bug

    @Test fun shopTabTap_withGearOpen_closesTheOverlayFirst() {
        // exactly the report: on Buy ⚙ / Shops ⚙, tapping another tab must NOT just set the tab.
        assertEquals(NavAction.CLOSE_GEAR_THEN_NAV, navAction(gearOpen = true, alreadyThere = false, leavingSettings = false, hasDiff = false))
    }

    @Test fun gearOpen_winsEvenWhenTheTargetIsTheCurrentTab() {
        // tapping the tab you are already "on" while its ⚙ page covers the screen must still close it —
        // otherwise the only way out would be the back gesture, which is what made it feel frozen.
        assertEquals(NavAction.CLOSE_GEAR_THEN_NAV, navAction(gearOpen = true, alreadyThere = true, leavingSettings = false, hasDiff = false))
    }

    @Test fun gearOpen_withUnsavedChanges_stillRoutesThroughTheOverlaysOwnGuard() {
        // the gear page owns its Save/Discard popup; navAction hands over to it rather than duplicating.
        assertEquals(NavAction.CLOSE_GEAR_THEN_NAV, navAction(gearOpen = true, alreadyThere = false, leavingSettings = true, hasDiff = true))
    }

    // ---------------------------------------------------------------- unchanged behaviour

    @Test fun noGear_plainTabChange_navigatesImmediately() {
        assertEquals(NavAction.NAV_NOW, navAction(gearOpen = false, alreadyThere = false, leavingSettings = false, hasDiff = false))
    }

    @Test fun noGear_sameTab_isIgnored() {
        assertEquals(NavAction.IGNORE, navAction(gearOpen = false, alreadyThere = true, leavingSettings = false, hasDiff = false))
    }

    @Test fun leavingASettingsPageWithChanges_showsTheGuardPopup() {
        assertEquals(NavAction.GUARD_POPUP, navAction(gearOpen = false, alreadyThere = false, leavingSettings = true, hasDiff = true))
    }

    @Test fun leavingASettingsPageWithNoChanges_navigatesImmediately() {
        assertEquals(NavAction.NAV_NOW, navAction(gearOpen = false, alreadyThere = false, leavingSettings = true, hasDiff = false))
    }

    @Test fun everyCombination_isDecided_andGearAlwaysWins() {
        listOf(true, false).forEach { already ->
            listOf(true, false).forEach { leaving ->
                listOf(true, false).forEach { diff ->
                    assertEquals(
                        "gear open must always close first",
                        NavAction.CLOSE_GEAR_THEN_NAV,
                        navAction(gearOpen = true, alreadyThere = already, leavingSettings = leaving, hasDiff = diff)
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- the handover target

    @Test fun navTarget_canExpressEveryDestination() {
        assertEquals(4, NavTarget(taskTab = 4).taskTab)                 // task tab (the only 2.05 option)
        assertEquals(1, NavTarget(shopTab = 1).shopTab)                 // shop tab — the missing case
        assertEquals("SHOP", NavTarget(mode = "SHOP").mode)             // a mode flip from the ☰ drawer
        val both = NavTarget(taskTab = 4, mode = "TASK")
        assertEquals(4, both.taskTab); assertEquals("TASK", both.mode)
        val empty = NavTarget()
        assertEquals(null, empty.taskTab); assertEquals(null, empty.shopTab); assertEquals(null, empty.mode)
    }

    @Test fun navTarget_modeIsNormalizedByTheSameHelperAsEverywhereElse() {
        assertEquals("SHOP", modeNormalized(NavTarget(mode = "SHOP").mode))
        assertEquals("TASK", modeNormalized(NavTarget(mode = "junk").mode))
        assertEquals("TASK", modeNormalized(NavTarget().mode))
    }

    @Test fun v261_schemaUnchanged_thisIsABugFixOnly() {
        assertEquals(42, AppSettings().ver)
    }
}
