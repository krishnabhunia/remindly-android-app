package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.62 duration widgets, re-pinned to the v1.68 hard 3 s – 3 min band. */
class V162Test {

    // ---- label rendering ----

    @Test fun labels_are_exact() {
        assertEquals("7 s", ringDurationLabel(0))       // legacy zero renders as the default
        assertEquals("7 s", ringDurationLabel(7))
        assertEquals("45 s", ringDurationLabel(45))
        assertEquals("1 min", ringDurationLabel(60))
        assertEquals("1 min 30 s", ringDurationLabel(90))
        assertEquals("3 min", ringDurationLabel(180))
    }

    // ---- min+sec → seconds, with honest bounds ----

    @Test fun valid_combinations_parse() {
        assertEquals(45, minSecToSeconds("", "45"))
        assertEquals(90, minSecToSeconds("1", "30"))
        assertEquals(120, minSecToSeconds("2", ""))
        assertEquals(180, minSecToSeconds("3", "0"))
        assertEquals(3, minSecToSeconds("", "3"))
    }

    @Test fun out_of_bounds_is_rejected_not_clamped() {
        assertNull("below 3 s", minSecToSeconds("", "2"))
        assertNull("blank blank = 0", minSecToSeconds("", ""))
        assertNull("above 3 min", minSecToSeconds("3", "1"))
        assertNull("sec must be 0-59", minSecToSeconds("1", "60"))
    }

    @Test fun junk_is_rejected() {
        assertNull(minSecToSeconds("x", "5"))
        assertNull(minSecToSeconds("1", "y"))
    }
}
