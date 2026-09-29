package com.krishna.remindly

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.02 (N33) — Google Maps as default provider with OSM fallback, user-set limit + hard monthly
 * lock, the 13-stop radius scale, the API-keys deck rules. Everything a unit can prove is proven:
 * caps, Pacific month keys, usage roll-over, lock semantics, provider resolution truth tables,
 * Google status classification, key redaction, section visibility, reset scope, heals, schema 37.
 * The Google map itself, the GPS-centred picker, the sheets and the encrypted key store are
 * device-checklist section N (stated honestly).
 */
class V202Test {

    private val gson = Gson()

    // ---------------------------------------------------------------- caps & defaults

    @Test fun freeCaps_globalAndIndia() {
        assertEquals(10_000, freeCapFor(false))
        assertEquals(70_000, freeCapFor(true))
        assertEquals(8_000, defaultGeoLimitFor(false))
        assertEquals(56_000, defaultGeoLimitFor(true))
        assertEquals(defaultGeoLimitFor(true), AppSettings().geoLimit)   // shipped default = India 80%
        assertTrue(AppSettings().indiaBilling)
        assertEquals(PROVIDER_GOOGLE, AppSettings().mapProvider)         // Google is the default
    }

    // ---------------------------------------------------------------- month keys (Pacific)

    @Test fun monthKey_isPacific_notUtc() {
        // 2026-09-01 03:00 UTC = 2026-08-31 20:00 PDT → still August in Pacific time
        val utcSep1_0300 = 1_788_231_600_000L
        assertEquals("2026-08", monthKeyPacific(utcSep1_0300))
        // 2026-09-01 08:00 UTC = 2026-09-01 01:00 PDT → September
        assertEquals("2026-09", monthKeyPacific(utcSep1_0300 + 5L * 3600_000L))
    }

    @Test fun nextMonthLabel_wrapsYear() {
        assertEquals("1 Sep", nextMonthLabel("2026-08"))
        assertEquals("1 Jan 2027", nextMonthLabel("2026-12"))
        assertEquals("the 1st", nextMonthLabel("garbage"))
    }

    // ---------------------------------------------------------------- usage roll-over & lock

    @Test fun usage_resetsOnNewMonth_andClearsOldLock() {
        val aug = GeoUsage("2026-08", 56_000, lockedMonth = "2026-08")
        assertTrue(isGeoLocked(aug, "2026-08"))
        val sep = usageForMonth(aug, "2026-09")
        assertEquals(GeoUsage("2026-09", 0, null), sep)
        assertFalse(isGeoLocked(aug, "2026-09"))          // auto-unlock on the 1st
    }

    @Test fun usageAfterCall_countsAndLocksAtLimit() {
        var u = GeoUsage("2026-08", 0, null)
        repeat(4) { u = usageAfterCall(u, "2026-08", limit = 5) }
        assertEquals(4, u.count); assertNull(u.lockedMonth)
        u = usageAfterCall(u, "2026-08", limit = 5)
        assertEquals(5, u.count); assertEquals("2026-08", u.lockedMonth)   // locked at the limit
        // stale month object rolls forward before counting
        val rolled = usageAfterCall(GeoUsage("2026-07", 99, "2026-07"), "2026-08", limit = 100)
        assertEquals(GeoUsage("2026-08", 1, null), rolled)
    }

    // ---------------------------------------------------------------- provider resolution

    @Test fun geoProvider_truthTable() {
        val g = AppSettings(mapProvider = PROVIDER_GOOGLE, geoLimit = 100)
        val fresh = GeoUsage("2026-08", 0, null)
        assertEquals(PROVIDER_GOOGLE, resolveGeoProvider(g, fresh, "2026-08", keyPresent = true))
        assertEquals(PROVIDER_OSM, resolveGeoProvider(g, fresh, "2026-08", keyPresent = false))          // no key
        assertEquals(PROVIDER_OSM, resolveGeoProvider(g.copy(mapProvider = PROVIDER_OSM), fresh, "2026-08", true)) // manual OSM
        assertEquals(PROVIDER_OSM, resolveGeoProvider(g, GeoUsage("2026-08", 100, null), "2026-08", true))       // at limit
        assertEquals(PROVIDER_OSM, resolveGeoProvider(g, GeoUsage("2026-08", 3, "2026-08"), "2026-08", true))    // locked
        assertEquals(PROVIDER_GOOGLE, resolveGeoProvider(g, GeoUsage("2026-07", 100, "2026-07"), "2026-08", true)) // new month
        // an unknown provider string is coated to GOOGLE by mapProviderNormalized (the default)
        assertEquals(PROVIDER_GOOGLE, resolveGeoProvider(g.copy(mapProvider = "junk"), fresh, "2026-08", true))
    }

    @Test fun mapProvider_truthTable() {
        val g = AppSettings(mapProvider = PROVIDER_GOOGLE)
        val fresh = GeoUsage("2026-08", 0, null)
        assertEquals(PROVIDER_GOOGLE, resolveMapProvider(g, fresh, "2026-08", sdkKeyPresent = true, playServicesOk = true))
        assertEquals(PROVIDER_OSM, resolveMapProvider(g, fresh, "2026-08", sdkKeyPresent = false, playServicesOk = true))   // no SDK key (this build)
        assertEquals(PROVIDER_OSM, resolveMapProvider(g, fresh, "2026-08", sdkKeyPresent = true, playServicesOk = false))   // no Play Services
        assertEquals(PROVIDER_OSM, resolveMapProvider(g, GeoUsage("2026-08", 1, "2026-08"), "2026-08", true, true))          // locked → OSM everywhere
        assertEquals(PROVIDER_OSM, resolveMapProvider(g.copy(mapProvider = PROVIDER_OSM), fresh, "2026-08", true, true))
    }

    @Test fun mapProviderNormalized_defaultsToGoogle() {
        assertEquals(PROVIDER_GOOGLE, mapProviderNormalized(null))
        assertEquals(PROVIDER_GOOGLE, mapProviderNormalized("banana"))
        assertEquals(PROVIDER_OSM, mapProviderNormalized("OSM"))
    }

    // ---------------------------------------------------------------- Google status classes

    @Test fun classifyGeoStatus_lockOnlyOnQuotaOrDenied() {
        assertEquals(GeoOutcome.OK, classifyGeoStatus("OK", 200))
        assertEquals(GeoOutcome.EMPTY, classifyGeoStatus("ZERO_RESULTS", 200))
        assertEquals(GeoOutcome.QUOTA_LOCK, classifyGeoStatus("OVER_QUERY_LIMIT", 200))
        assertEquals(GeoOutcome.QUOTA_LOCK, classifyGeoStatus("OVER_DAILY_LIMIT", 200))
        assertEquals(GeoOutcome.QUOTA_LOCK, classifyGeoStatus(null, 429))
        assertEquals(GeoOutcome.DENIED_LOCK, classifyGeoStatus("REQUEST_DENIED", 200))
        assertEquals(GeoOutcome.DENIED_LOCK, classifyGeoStatus(null, 403))
        assertEquals(GeoOutcome.TRANSIENT, classifyGeoStatus("UNKNOWN_ERROR", 200))
        assertEquals(GeoOutcome.TRANSIENT, classifyGeoStatus(null, 503))
        assertEquals(GeoOutcome.TRANSIENT, classifyGeoStatus(null, 0))     // no response
    }

    @Test fun redactKey_stripsKeyParameter() {
        val u = "https://maps.googleapis.com/maps/api/geocode/json?address=x&key=AIzaSECRET123&foo=1"
        val r = redactKey(u)
        assertFalse(r.contains("AIzaSECRET123"))
        assertTrue(r.contains("key=<redacted>"))
        assertTrue(r.endsWith("&foo=1"))
    }

    // ---------------------------------------------------------------- 13-stop scale (standing rule)

    @Test fun radiusScale_isExactlyKrishnasList() {
        assertEquals(listOf(50f, 100f, 150f, 200f, 250f, 300f, 400f, 500f, 750f, 1000f, 1500f, 2000f, 3000f), RADIUS_STOPS)
        RADIUS_STOPS.forEach { assertEquals(it, snapRadius(it)) }          // every stop maps to itself
        assertEquals(50f, snapRadius(Float.NaN)); assertEquals(50f, snapRadius(-1f))
        assertEquals(50f, snapRadius(Float.POSITIVE_INFINITY))                // non-finite → smallest stop (total function)
        assertEquals("1.5 km", radiusLabel(1500f)); assertEquals("3 km", radiusLabel(3000f)); assertEquals("750 m", radiusLabel(750f))
    }

    @Test fun heals_snapStoredRadii_andCoatProviderFields() {
        val legacyShop = gson.fromJson("{\"id\":1,\"name\":\"A\",\"radius\":350}", Shop::class.java)
        assertEquals(300f, healShop(legacyShop).radius)                     // tie → lower stop
        val legacyPlace = gson.fromJson("{\"id\":2,\"name\":\"P\",\"lat\":1.0,\"lng\":2.0,\"radius\":700}", GeoPlace::class.java)
        assertEquals(750f, healPlace(legacyPlace).radius)
        val legacySettings = gson.fromJson("{\"ver\":36,\"geoLimit\":999999,\"indiaBilling\":false}", AppSettings::class.java)
        val h = healSettings(legacySettings)
        assertEquals(PROVIDER_GOOGLE, h.mapProvider)                          // null String coated
        assertEquals(10_000, h.geoLimit)                                      // clamped to the (global) cap
        h.copy(mapProvider = PROVIDER_OSM)                                    // copy() must not throw
    }

    // ---------------------------------------------------------------- settings deck & reset

    @Test fun mapsAndApiKeys_sections_generalPageOnly() {
        assertTrue(settingsSectionVisible(null, "maps")); assertTrue(settingsSectionVisible(null, "api-keys"))
        listOf("SHOP", "TASKS", "LEARN", "CALLS").forEach {
            assertFalse("maps on $it", settingsSectionVisible(it, "maps"))
            assertFalse("api-keys on $it", settingsSectionVisible(it, "api-keys"))
        }
    }

    @Test fun generalReset_restoresProviderAndLimit_neverTouchesKeys() {
        val s = AppSettings(mapProvider = PROVIDER_OSM, geoLimit = 1000, indiaBilling = false, defaultRadius = 750f)
        val r = resetSettingsFor(s, null)
        assertEquals(PROVIDER_GOOGLE, r.mapProvider); assertEquals(56_000, r.geoLimit); assertTrue(r.indiaBilling)
        assertEquals(AppSettings().defaultRadius, r.defaultRadius)
        // keys are not part of AppSettings at all — a reset cannot reach them by construction
        assertFalse(gson.toJson(r).contains("google_geocoding"))
    }

    @Test fun v205_schemaIs39_afterN37() {
        assertEquals(43, AppSettings().ver)
        assertEquals(43, healSettings(AppSettings()).ver)
    }
}
