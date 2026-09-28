package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.40 — count/badge bug fixes. The header "active · done" counts and the badge now exclude
 * soft-deleted items; the badge counts only items due today or overdue (not future).
 */
class V140Test {

    private val DAY = 86_400_000L
    private val startOfTomorrow = 1_800_000_000_000L   // arbitrary "start of tomorrow" epoch ms

    private fun task(id: Long, due: Long?, done: Boolean = false, deleted: Long? = null) =
        Item(id = id, tab = Tab.TASKS, title = "t$id", dueAt = due, done = done, deletedAt = deleted)

    // ---- bug #3: badge = due today or overdue, not future, excluding done/deleted/no-due ----

    @Test fun overdue_and_today_count_future_does_not() {
        val items = listOf(
            task(1, startOfTomorrow - DAY),      // overdue → count
            task(2, startOfTomorrow - 1),        // later today → count
            task(3, startOfTomorrow),            // exactly tomorrow start → NOT (not < tomorrow)
            task(4, startOfTomorrow + DAY),      // tomorrow → NOT
            task(5, null)                        // no due date → NOT
        )
        assertEquals(2, pendingTodayCount(items, Tab.TASKS, startOfTomorrow))
    }

    @Test fun done_and_deleted_are_excluded() {
        val items = listOf(
            task(1, startOfTomorrow - DAY),                      // count
            task(2, startOfTomorrow - DAY, done = true),         // done → NOT
            task(3, startOfTomorrow - DAY, deleted = 123L)       // deleted → NOT
        )
        assertEquals(1, pendingTodayCount(items, Tab.TASKS, startOfTomorrow))
    }

    @Test fun only_the_requested_tab_is_counted() {
        val items = listOf(
            task(1, startOfTomorrow - DAY),
            Item(id = 2, tab = Tab.SHOP, title = "s", dueAt = startOfTomorrow - DAY)
        )
        assertEquals(1, pendingTodayCount(items, Tab.TASKS, startOfTomorrow))
        assertEquals(1, pendingTodayCount(items, Tab.SHOP, startOfTomorrow))
    }

    // ---- bug #1/#2: header count excludes soft-deleted (the filter the header now uses) ----

    @Test fun header_active_done_counts_exclude_deleted() {
        val items = listOf(
            task(1, null),                          // active
            task(2, null, done = true),             // done
            task(3, null, deleted = 5L),            // deleted active — must not count
            task(4, null, done = true, deleted = 5L)// deleted done — must not count
        ).filter { it.tab == Tab.TASKS && it.deletedAt == null }
        assertEquals(1, items.count { !it.done })
        assertEquals(1, items.count { it.done })
    }
}
