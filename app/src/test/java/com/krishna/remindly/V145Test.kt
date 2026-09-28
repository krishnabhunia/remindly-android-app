package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.45 Feature 4c — read-only calendar day-grouping (pure). The actual calendar read is
 * device-only (CalendarContract); this covers the bucketing/ordering logic.
 */
class V145Test {

    private val DAY = 86_400_000L

    private fun ev(id: Long, start: Long) = CalEvent(id = id, title = "e$id", start = start, end = start + 3_600_000L, allDay = false)

    @Test fun groups_by_day_ascending() {
        val d0 = startOfDayMs(1_000_000_000_000L)
        val events = listOf(ev(1, d0 + DAY + 5000), ev(2, d0 + 5000), ev(3, d0 + 2 * DAY))
        val g = calEventsByDay(events)
        assertEquals(listOf(d0, d0 + DAY, d0 + 2 * DAY), g.map { it.first })
    }

    @Test fun events_within_a_day_sorted_by_start() {
        val d0 = startOfDayMs(1_000_000_000_000L)
        val events = listOf(ev(1, d0 + 20_000), ev(2, d0 + 5_000), ev(3, d0 + 12_000))
        val g = calEventsByDay(events)
        assertEquals(1, g.size)
        assertEquals(listOf(2L, 3L, 1L), g.first().second.map { it.id })
    }

    @Test fun empty_input_gives_no_groups() {
        assertEquals(emptyList<Pair<Long, List<CalEvent>>>(), calEventsByDay(emptyList()))
    }
}
