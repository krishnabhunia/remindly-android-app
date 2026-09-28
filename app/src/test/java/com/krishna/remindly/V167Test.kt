package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.67 — contact-enrichment display chain. The ContactsContract lookup, the dark palette,
 * and the purge's no-visible-change are device-checklist.
 */
class V167Test {

    @Test fun full_name_joins_and_trims() {
        assertEquals("Amit Sharma", callFullName(" Amit ", "Sharma"))
        assertEquals("Amit", callFullName("Amit", null))
        assertEquals("Sharma", callFullName("  ", "Sharma"))
        assertNull(callFullName("  ", null))
        assertNull(callFullName(null, null))
    }

    @Test fun display_chain_structured_beats_cached_beats_number() {
        assertEquals("Amit Sharma", callDisplayOf("Amit", "Sharma", "Old Cached", "+91999"))
        assertEquals("Old Cached", callDisplayOf(null, "", "Old Cached", "+91999"))
        assertEquals("+91999", callDisplayOf(null, null, "  ", "+91999"))
    }

    @Test fun reminder_display_getter_uses_the_chain() {
        val r = CallReminder(id = 1L, number = "+91999", name = "Cached",
            source = CallSource.MANUAL, firstName = "Amit", lastName = "Sharma")
        assertEquals("Amit Sharma", r.display)
        assertEquals("Cached", r.copy(firstName = null, lastName = null).display)
    }
}
