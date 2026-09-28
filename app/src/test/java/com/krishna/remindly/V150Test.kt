package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.50 — no numeric rounding in any displayed value. The drawer's one-attribute-per-row layout
 * is device-checklist; this covers the formatter that every ₹ display now routes through.
 */
class V150Test {

    @Test fun the_reported_bug_is_gone() {
        // %.0f used to turn 45.60 into "46"
        assertEquals("45.6", fmtExact(45.60))
        assertTrue(fmtExact(45.60) != "46")
    }

    @Test fun whole_numbers_have_no_decimal_tail() {
        assertEquals("45", fmtExact(45.0))
        assertEquals("0", fmtExact(0.0))
        assertEquals("1000", fmtExact(1000.0))
    }

    @Test fun trailing_zeros_are_trimmed() {
        assertEquals("45.5", fmtExact(45.50))
        assertEquals("45.25", fmtExact(45.2500))
    }

    @Test fun small_values_survive_to_four_decimals() {
        assertEquals("0.0625", fmtExact(0.0625))
        assertEquals("0.0001", fmtExact(0.0001))
    }

    @Test fun never_rounds_up_at_the_fourth_decimal() {
        // truncation, not rounding: 0.00019 must not become 0.0002
        assertEquals("0.0001", fmtExact(0.00019))
        assertEquals("45.9999", fmtExact(45.99999))
    }

    @Test fun negative_and_non_finite_are_safe() {
        assertEquals("-12.5", fmtExact(-12.5))
        assertEquals("0", fmtExact(Double.NaN))
        assertEquals("0", fmtExact(Double.POSITIVE_INFINITY))
    }

    @Test fun unit_price_trims_padded_zeros() {   // v1.51 correction: was fixed 4dp
        // Krishna's earlier spec: unit price renders as i.dddd
        assertEquals("45.6", fmtUnitPrice(45.6))
        assertEquals("0.0625", fmtUnitPrice(0.0625))
    }

    // ---- v1.50: "Select None" must not collapse into "Select All" ----

    private val calsForNone = listOf(
        CalSync.CalInfo(1, "Personal", "a@gmail.com"),
        CalSync.CalInfo(2, "Holidays", "a@gmail.com"),
        CalSync.CalInfo(3, "Work", "b@company.com")
    )

    @Test fun select_all_is_stored_as_empty_and_reads_everything() {
        val s = AppSettings(calendarAccount = "a@gmail.com", calendarIds = emptySet(), calendarNone = false)
        assertEquals(listOf(1L, 2L), selectedCalendarIds(s, calsForNone))
    }

    @Test fun select_none_reads_nothing_despite_the_empty_set() {
        val s = AppSettings(calendarAccount = "a@gmail.com", calendarIds = emptySet(), calendarNone = true)
        assertTrue("None must not behave like All", selectedCalendarIds(s, calsForNone).isEmpty())
    }

    @Test fun none_flag_overrides_even_an_explicit_selection() {
        val s = AppSettings(calendarAccount = "a@gmail.com", calendarIds = setOf(1L), calendarNone = true)
        assertTrue(selectedCalendarIds(s, calsForNone).isEmpty())
    }

    @Test fun narrowed_selection_still_works_with_none_off() {
        val s = AppSettings(calendarAccount = "a@gmail.com", calendarIds = setOf(2L), calendarNone = false)
        assertEquals(listOf(2L), selectedCalendarIds(s, calsForNone))
    }

    @Test fun default_settings_do_not_select_none() {
        assertTrue(!AppSettings().calendarNone)
    }

    @Test fun unit_price_and_money_formatters_agree() {
        listOf(0.0, 45.0, 45.6, 0.0625, 1234.5678).forEach {
            assertEquals("both must trim identically", fmtExact(it), fmtUnitPrice(it))
        }
    }
}
