package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.48 — queued batch. Pure logic only; every visual change (trash icon, Calls card layout,
 * settings relocation, sliders) is device-checklist, which is the acceptance criterion after the
 * v1.44/v1.41 lessons about testing helpers instead of pixels.
 */
class V148Test {

    // ---- geofence radius: ALWAYS discrete. v2.02 (N33 C3): the 13-stop scale replaces 50 m steps ----

    @Test fun radius_snaps_to_the_nearest_stop() {
        assertEquals(150f, snapRadius(149f))
        assertEquals(150f, snapRadius(151f))
        assertEquals(200f, snapRadius(180f))     // nearest stop
        assertEquals(100f, snapRadius(120f))
        assertEquals(750f, snapRadius(700f))     // old 700 m → 750 m
    }

    @Test fun radius_tie_rounds_down_and_range_clamps() {
        assertEquals("tie → lower stop", 300f, snapRadius(350f))
        assertEquals("below min clamps up", 50f, snapRadius(0f))
        assertEquals(50f, snapRadius(10f))
        assertEquals("above max clamps down", 3000f, snapRadius(5000f))
        assertEquals(3000f, snapRadius(3000f))
        assertEquals(2000f, snapRadius(2000f))
        assertEquals(1500f, snapRadius(1400f))
    }

    @Test fun every_snapped_value_is_one_of_the_13_stops() {
        var v = -100f
        while (v <= 4000f) {
            val r = snapRadius(v)
            assertTrue("stop", r in RADIUS_STOPS)
            assertTrue("within range", r in RADIUS_MIN..RADIUS_MAX)
            v += 7.3f
        }
        assertEquals(13, RADIUS_STOPS.size)
        assertEquals(RADIUS_STOPS, RADIUS_STOPS.sorted())
    }

    @Test fun radius_label_uses_km_above_1000() {
        assertEquals("950 m", radiusLabel(950f))
        assertEquals("1 km", radiusLabel(1000f))
        assertEquals("1.5 km", radiusLabel(1500f))
        assertEquals("2 km", radiusLabel(2000f))
    }

    @Test fun healShop_snaps_a_legacy_radius() {
        // v1.37 shops could store any continuous value (old slider was 75..500, continuous)
        assertEquals(100f, healShop(Shop(id = 1, name = "X", radius = 87f)).radius)
        // radius 0 falls back to the 150 default, which is already a legal mark
        assertEquals(150f, healShop(Shop(id = 1, name = "X", radius = 0f)).radius)
    }

    // ---- calendar read window: days OR months, no silent truncation ----

    @Test fun window_defaults_to_seven_days() {
        assertEquals(7, calendarReadWindowDays(AppSettings()))
    }

    @Test fun days_mode_allows_up_to_60() {
        assertEquals(60, calendarReadWindowDays(AppSettings(calendarReadUnit = "DAYS", calendarReadDays = 60)))
    }

    @Test fun months_mode_converts_and_is_not_clamped_to_90() {
        val s = AppSettings(calendarReadUnit = "MONTHS", calendarReadMonths = 24)
        val days = calendarReadWindowDays(s)
        assertEquals(24 * 31, days)
        assertTrue("24 months must exceed the old 90-day clamp", days > 90)
    }

    @Test fun months_are_bounded_to_3_24() {
        assertEquals(3 * 31, calendarReadWindowDays(AppSettings(calendarReadUnit = "MONTHS", calendarReadMonths = 1)))
        assertEquals(24 * 31, calendarReadWindowDays(AppSettings(calendarReadUnit = "MONTHS", calendarReadMonths = 99)))
    }

    // ---- Shop optional locator ----

    @Test fun shop_area_is_trimmed_and_blank_becomes_null() {
        assertEquals("Kolkata", healShop(Shop(id = 1, name = "S", area = "  Kolkata ")).area)
        assertNull(healShop(Shop(id = 1, name = "S", area = "   ")).area)
        assertNull(healShop(Shop(id = 1, name = "S", area = null)).area)
    }

    @Test fun item_still_has_no_city_field_reintroduced() {
        // v1.38 removed shopCity from Item; the v1.48 locator lives on Shop, not Item.
        val fields = Item::class.java.declaredFields.map { it.name }
        assertFalse("shopCity must stay removed from Item", fields.any { it.equals("shopCity", true) })
    }

    // ---- Calls card layout (the v1.44 gap) ----

    @Test fun calls_card_fields_resolve_from_the_c_prefix() {
        val s = AppSettings(tabCardOv = mapOf("cCardCheckbox" to "OFF"))
        assertFalse("Calls checkbox hidden", cardFieldsFor(s, null).checkbox)
        assertTrue("other tabs unaffected", cardFieldsFor(s, Tab.TASKS).checkbox)
    }

    @Test fun calls_inherits_global_card_defaults() {
        val s = AppSettings(cardShowDateTime = false)
        assertFalse(cardFieldsFor(s, null).dateTime)
    }

    // ---- the header clear-all switch ----

    @Test fun clear_all_icon_defaults_to_shown() {
        assertTrue(AppSettings().showDeleteOnDone)
    }
}
