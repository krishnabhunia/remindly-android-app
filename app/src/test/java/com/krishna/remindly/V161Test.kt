package com.krishna.remindly

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.61 — the morphing quick-add circle. Everything visual/interactive is device-checklist;
 * this pins the one pure dispatch rule.
 */
class V161Test {
    @Test fun blank_draft_shows_plus_and_opens_the_full_editor() {
        assertTrue(quickCircleIsPlus(""))
        assertTrue("whitespace is still a blank draft", quickCircleIsPlus("   "))
    }

    @Test fun typed_draft_shows_check_and_quick_adds() {
        assertTrue(!quickCircleIsPlus("Buy milk"))
        assertTrue(!quickCircleIsPlus(" x "))
    }
}
