package com.krishna.remindly

import android.database.MatrixCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.55 — regression tests for the dropped ACCOUNT_TYPE column.
 *
 * The v1.50 projection gained ACCOUNT_TYPE but the row was still built from three columns, so every
 * CalInfo.type was blank. requestAccountSync filtered on a non-blank type, matched nothing, and
 * returned without ever calling requestSync — so new Google Calendar events never reached the app.
 * No existing test built a CalInfo from a real cursor, which is why nothing caught it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V155Test {

    private fun cursorRow(id: Long, name: String, account: String, type: String) =
        MatrixCursor(arrayOf("_id", "name", "account", "type")).apply {
            addRow(arrayOf<Any?>(id, name, account, type))
            moveToFirst()
        }

    @Test fun account_type_is_read_from_the_row() {
        val info = CalSync.calInfoFrom(cursorRow(7, "Personal", "k@gmail.com", "com.google"))
        assertEquals("com.google", info.type)
    }

    @Test fun the_bug_itself_type_must_not_come_back_blank() {
        val info = CalSync.calInfoFrom(cursorRow(7, "Personal", "k@gmail.com", "com.google"))
        assertTrue("a blank type disables requestSync entirely", info.type.isNotBlank())
    }

    @Test fun all_four_fields_map_to_the_right_columns() {
        val info = CalSync.calInfoFrom(cursorRow(42, "Work", "k@company.com", "com.google"))
        assertEquals(42L, info.id)
        assertEquals("Work", info.name)
        assertEquals("k@company.com", info.account)
        assertEquals("com.google", info.type)
    }

    @Test fun a_missing_type_degrades_to_empty_not_a_crash() {
        val info = CalSync.calInfoFrom(cursorRow(1, "X", "a@b.com", ""))
        assertEquals("", info.type)
    }

    @Test fun projection_width_matches_what_the_row_reader_consumes() {
        // the exact drift that caused the bug: projection grew, the reader did not
        assertEquals("calInfoFrom reads columns 0..3", 4, CalSync.CAL_PROJECTION.size)
    }

    @Test fun projection_ends_with_account_type() {
        assertEquals(
            android.provider.CalendarContract.Calendars.ACCOUNT_TYPE,
            CalSync.CAL_PROJECTION.last()
        )
    }
}
