package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.66 pruning pins, rewritten for the v1.68 per-item model. */
class V166Test {
    @Test fun defaultItemNotifies() {
        assertEquals("N", resolveAlertTypes(Item(id = 1, title = "x", tab = Tab.TASKS), AppSettings()))
    }
    @Test fun urgentNoLongerForcesAlarm() {
        val i = Item(id = 2, title = "x", tab = Tab.TASKS, priority = Priority.URGENT)
        assertEquals("N", resolveAlertTypes(i, AppSettings()))
    }
}
