package com.krishna.remindly

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.70 Q10: an older writer must never narrow a record.
 *
 * v2.10 (N6 pt2): these call overlayOlderWriter — the function Sync.mergeRecord itself uses —
 * so the rule is pinned on production code without needing Firestore.
 */
class V170Test {

    private val nulls = GsonBuilder().serializeNulls().create()

    private fun <T : Any> merge(remoteJson: String, local: T, cls: Class<T>): T =
        overlayOlderWriter(remoteJson, local, cls).first

    @Test fun keptFieldsAreReported() {
        val local = Item(id = 7, tab = Tab.TASKS, title = "x", alertType = "A")
        val (_, kept) = overlayOlderWriter("""{"id":7,"tab":"TASKS","title":"y"}""", local, Item::class.java)
        assertTrue("alertType was kept from the local record", "alertType" in kept)
        assertTrue("title came from the remote", "title" !in kept)
    }

    // ---- the exact bug: iOS v1.52 has no alertType ----

    @Test fun olderWriterCannotWipeAlertType() {
        val local = Item(id = 7, tab = Tab.TASKS, title = "Car Start", alertType = "A", updatedAt = 100L)
        val legacy = """{"id":7,"tab":"TASKS","title":"Car Start","updatedAt":200}"""
        val merged = merge(legacy, local, Item::class.java)
        assertEquals("A", merged.alertType)
        assertEquals(200L, merged.updatedAt)
    }

    @Test fun plainDeserialiseIsWhatUsedToLoseIt() {
        val legacy = """{"id":7,"tab":"TASKS","title":"Car Start","updatedAt":200}"""
        val naive = gson.fromJson(legacy, Item::class.java)
        // Gson bypasses the Kotlin constructor, so an absent key does not even get its declared
        // default — it lands as null. Either way the "A" the user chose is gone, which is exactly
        // what the wholesale replace used to write back over the local record.
        assertNotEquals("A", naive.alertType)
    }

    @Test fun remoteStillWinsOnKeysItActuallyCarries() {
        val local = Item(id = 7, tab = Tab.TASKS, title = "old", alertType = "A")
        val remote = """{"id":7,"tab":"TASKS","title":"new","alertType":"R"}"""
        val merged = merge(remote, local, Item::class.java)
        assertEquals("new", merged.title)
        assertEquals("R", merged.alertType)
    }

    @Test fun explicitNullStillClears() {
        val local = Item(id = 7, tab = Tab.TASKS, title = "x", group = "Home")
        val remote = """{"id":7,"tab":"TASKS","title":"x","group":null}"""
        assertEquals(null, merge(remote, local, Item::class.java).group)
    }

    @Test fun callAlertTypeSurvivesAnOlderWriter() {
        val local = CallReminder(id = 3, number = "123", source = CallSource.AUTO, alertType = "R")
        val legacy = """{"id":3,"number":"123"}"""
        assertEquals("R", merge(legacy, local, CallReminder::class.java).alertType)
    }

    @Test fun settingsFromOlderWriterKeepNewerKeys() {
        val local = AppSettings(alarmRingSeconds = 30, ringRingSeconds = 45)
        val legacy = """{"ver":32,"theme":"DARK"}"""
        val merged = merge(legacy, local, AppSettings::class.java)
        assertEquals(30, merged.alarmRingSeconds)
        assertEquals(45, merged.ringRingSeconds)
        assertEquals("DARK", merged.theme)
    }

    // ---- Q8: Time Format resolution ----

    @Test fun timeFormatMappingIsExhaustive() {
        val cases = listOf(
            Triple("H24", true, true), Triple("H24", false, true),
            Triple("H12", true, false), Triple("H12", false, false),
            Triple("PHONE", true, true), Triple("PHONE", false, false)
        )
        cases.forEach { (setting, phone24, expected) ->
            TIME_FORMAT = setting
            PHONE_IS_24H = phone24
            assertEquals("$setting/phone24=$phone24", expected, use24Now())
        }
        TIME_FORMAT = "PHONE"; PHONE_IS_24H = false
    }

    @Test fun unknownTokenFallsBackToPhone() {
        TIME_FORMAT = "GIBBERISH"; PHONE_IS_24H = true
        assertTrue(use24Now())
        TIME_FORMAT = "PHONE"; PHONE_IS_24H = false
    }
}
