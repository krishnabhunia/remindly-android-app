package com.krishna.remindly

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.46 Feature 4a — the calendar WRITE path is disabled and hidden (code kept intact behind
 * CalSync.WRITE_ENABLED). Reading stays active. Actual calendar I/O is device-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V146Test {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        Stores.init(context)
    }

    @Test fun write_path_is_disabled() {
        assertFalse("calendar writing must stay off until explicitly re-enabled", CalSync.WRITE_ENABLED)
    }

    @Test fun migration_clears_stale_sync_flag() {
        val old = AppSettings(ver = 23, calendarSync = true, calendarTargetId = 42L)
        val m = SettingsStore.migrate(old)
        assertEquals(41, m.ver)
        assertFalse("stale 'sync on' must be cleared", m.calendarSync)
    }

    @Test fun migration_keeps_the_chosen_calendar_for_reading() {
        val old = AppSettings(ver = 23, calendarSync = true, calendarTargetId = 42L)
        val m = SettingsStore.migrate(old)
        assertEquals("the read-only view still needs the chosen calendar", 42L, m.calendarTargetId)
    }

    @Test fun read_window_default_and_heal() {
        assertEquals(7, AppSettings().calendarReadDays)
        assertEquals(7, healSettings(AppSettings(calendarReadDays = 0)).calendarReadDays)
        assertEquals(30, healSettings(AppSettings(calendarReadDays = 30)).calendarReadDays)
    }

    @Test fun write_calls_are_safe_no_ops_while_disabled() {
        // Guarded entry points must return without touching the provider (no exception).
        val item = Item(id = 1, tab = Tab.TASKS, title = "x", dueAt = System.currentTimeMillis())
        CalSync.syncItem(context, item)
        CalSync.removeItem(context, item)
        CalSync.backfill(context)
        CalSync.teardown(context)
        CalSync.backfillTab(context, Tab.TASKS)
        CalSync.teardownTab(context, Tab.TASKS)
        CalSync.backfillCalls(context)
        CalSync.teardownCalls(context)
    }
}
