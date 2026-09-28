package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.2 (N41) — Krishna's ruling: "the missed call will always be monitored and will be only
 * fetched when call comes and not from history. History must be completely ignored for all mobile
 * miss calls." These pin the three pure rules that make the Vivo/Redmi first-install flood
 * impossible: a live-only window, a per-row live check, and one alert per sweep. The receiver,
 * the retry ladder and the OEM behaviour itself are device checks (section S).
 */
class V262Test {

    private val now = 1_800_000_000_000L

    // ---------------------------------------------------------------- the live window

    @Test fun freshInstall_windowIsTheLiveWindow_notTheInstallDate() {
        // lastSeen 0 (no watermark yet) must NOT reach back to installation — only 5 minutes.
        assertEquals(now - LIVE_WINDOW_MS, liveScanWindow(anchorMs = 0L, now = now))
    }

    @Test fun oldWatermark_isClampedToTheLiveWindow() {
        // a watermark from six months ago (data cleared, restore, late permission grant) cannot
        // open a six-month window any more — this is the exact flood path.
        val sixMonths = now - 180L * 24 * 3600_000L
        assertEquals(now - LIVE_WINDOW_MS, liveScanWindow(sixMonths, now))
    }

    @Test fun anchorInsideTheWindow_wins_soPreAnchorHistoryStaysOut() {
        // an app installed two minutes ago must not read the three minutes before it existed
        val twoMinAgo = now - 2 * 60_000L
        assertEquals(twoMinAgo, liveScanWindow(twoMinAgo, now))
    }

    @Test fun theAnchorNeverAdvances_soALateWrittenRowIsStillCaught() {
        // the anchor is immutable: an old anchor leaves the full live window open every sweep, which
        // is what lets the retry ladder pick up a row the OEM wrote a few minutes late (the dedupe
        // ring stops the re-read from duplicating anything).
        val oldAnchor = now - 30L * 24 * 3600_000L
        assertEquals(now - LIVE_WINDOW_MS, liveScanWindow(oldAnchor, now))
        assertTrue(isLiveRow(now - 4 * 60_000L, now))
    }

    @Test fun liveWindowIsFiveMinutes_theAgreedDefault() {
        assertEquals(5L * 60_000L, LIVE_WINDOW_MS)
        assertEquals(listOf(10_000L, 60_000L), CALL_RETRY_DELAYS_MS)
    }

    // ---------------------------------------------------------------- per-row history guard

    @Test fun isLiveRow_acceptsOnlyTheLastFiveMinutes() {
        assertTrue("just now", isLiveRow(now, now))
        assertTrue("4 min ago", isLiveRow(now - 4 * 60_000L, now))
        assertFalse("6 min ago", isLiveRow(now - 6 * 60_000L, now))
        assertFalse("yesterday", isLiveRow(now - 24 * 3600_000L, now))
        assertFalse("last year", isLiveRow(now - 365L * 24 * 3600_000L, now))
    }

    @Test fun isLiveRow_toleratesSmallClockSkewForward() {
        assertTrue("30 s ahead (provider/device clock skew)", isLiveRow(now + 30_000L, now))
        assertFalse("2 min ahead is not a real row", isLiveRow(now + 120_000L, now))
    }

    @Test fun everyHistoricalRow_isRejected_whateverTheProviderReturns() {
        // the flood was hundreds of rows in one cursor; not one of them may pass now.
        var passed = 0
        for (daysAgo in 1..200) if (isLiveRow(now - daysAgo * 24L * 3600_000L, now)) passed++
        assertEquals(0, passed)
    }

    // ---------------------------------------------------------------- the storm guard

    @Test fun oneSweepRaisesAtMostOneAlert() {
        assertEquals(0, alertsForSweep(0))
        assertEquals(1, alertsForSweep(1))
        assertEquals(1, alertsForSweep(2))
        assertEquals(1, alertsForSweep(37))
        assertEquals(1, alertsForSweep(SCAN_ROW_CAP))   // even a capped full sweep = ONE alert
    }

    @Test fun stormGuard_isTotal_onGarbage() {
        assertEquals(0, alertsForSweep(-5))
    }

    // ---------------------------------------------------------------- upgrade purge selector

    @Test fun preAnchorAutoReminders_arePurged_manualOnesAreKept() {
        val anchor = now
        val calls = listOf(
            CallReminder(id = 1, number = "111", source = CallSource.AUTO, createdAt = anchor - 5_000L, lastMissedAt = anchor - 5_000L),
            CallReminder(id = 2, number = "222", source = CallSource.MANUAL, createdAt = anchor - 5_000L, lastMissedAt = anchor - 5_000L),
            CallReminder(id = 3, number = "333", source = CallSource.AUTO, createdAt = anchor + 5_000L, lastMissedAt = anchor + 5_000L)
        )
        val (kept, purged) = purgePreInstall(calls, anchor)
        assertEquals(listOf(1L), purged.map { it.id })          // the flood's junk
        assertTrue(kept.any { it.id == 2L })                     // manual reminders survive
        assertTrue(kept.any { it.id == 3L })                     // post-anchor auto survives
    }

    @Test fun v262_schemaUnchanged_bugFixOnly() {
        assertEquals(41, AppSettings().ver)
    }
}
