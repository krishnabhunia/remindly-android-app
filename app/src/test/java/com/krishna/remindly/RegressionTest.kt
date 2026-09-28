package com.krishna.remindly

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests — every past bug becomes a test.
 * Bug #1 (v1.9, Krishna's crash-reporter trace, 22 Jul 2026):
 * completing a pre-v1.9 recurring item threw NPE on Item.copy(repeatOrdList)
 * because Gson skips Kotlin defaults for fields absent from stored JSON.
 */
class RegressionTest {

    private val gson = Gson()

    /** JSON exactly as a v1.8-era store would have written it — no repeatOrdList, no updatedAt. */
    private val legacyItemJson = """
        {"id":42,"tab":"TASKS","title":"Old recurring","notes":"","createdAt":1750000000000,
         "dueAt":1753166000000,"personal":false,"dueHasTime":true,
         "repeatMode":"WEEKLY","repeatDays":[1,3],"repeatN":1,"repeatUnit":"D",
         "repeatOrd":1,"repeatDow":1,"done":false}
    """.trimIndent()

    @Test
    fun bug1_gsonLeavesNewListFieldNull_documentedBehaviour() {
        val raw = gson.fromJson(legacyItemJson, Item::class.java)
        // The dangerous state this bug depends on: Gson bypassed the Kotlin default.
        @Suppress("USELESS_ELVIS", "SENSELESS_COMPARISON")
        assertTrue(raw.repeatOrdList == null)
    }

    @Test
    fun bug1_healItemRestoresDefaults_andCopySurvives() {
        val healed = healItem(gson.fromJson(legacyItemJson, Item::class.java))
        assertNotNull(healed.repeatOrdList)
        assertEquals(emptyList<Int>(), healed.repeatOrdList)
        // The exact operation that crashed on-device — the repeat bounce's copy():
        val bounced = healed.copy(dueAt = (healed.dueAt ?: 0L) + 86_400_000L)
        assertEquals(listOf(1, 3), bounced.repeatDays)
    }

    @Test
    fun heal_callReminderSurvivesLegacyJson() {
        val json = """{"id":7,"number":"+911234567890","source":"AUTO","missedCount":2,"done":false,"nagFired":false}"""
        val healed = healCall(gson.fromJson(json, CallReminder::class.java))
        val copied = healed.copy(missedCount = 3)
        assertEquals("+911234567890", copied.number)
    }

    // ---------------- backup logic (pure) ----------------

    @Test
    fun sniff_recognisesAllThreeKinds() {
        assertEquals("data", Backup.sniff("""{"kind":"remindly-data","items":[]}"""))
        assertEquals("settings", Backup.sniff("""{"kind":"remindly-settings","settings":{}}"""))
        assertEquals("legacy", Backup.sniff("""{"items":[],"places":[],"calls":[],"settings":{}}"""))
        assertNull(Backup.sniff("""{"hello":"world"}"""))
        assertNull(Backup.sniff("not json at all"))
    }

    @Test
    fun parseData_healsRecordsInsideTheBlob() {
        val blobJson = """{"kind":"remindly-data","items":[$legacyItemJson],"calls":[],"places":[]}"""
        val blob = Backup.parseData(blobJson)!!
        assertEquals(emptyList<Int>(), blob.items.first().repeatOrdList)
        assertEquals(emptyList<String>(), blob.tasksGroups)
    }

    // ---------------- settings migration v9 → v10 (pure via SettingsStore.migrate) ----------------

    @Test
    fun migration_v10CollapsesEqualValuesToInherit_keepsDivergence() {
        val v9 = AppSettings(
            ver = 9,
            moveDelaySec = 3,
            tasksMoveDelaySec = 3,   // equal → should collapse to -1
            shopMoveDelaySec = 7,    // divergent → stays 7
            gestureCardSwipe = true,
            tCardSwipe = true,       // equal → no override entry
            sCardSwipe = false       // divergent → override entry sCardSwipe=OFF
        )
        val m = SettingsStore.migrate(v9)
        assertEquals(41, m.ver)
        assertEquals(-1, m.tasksMoveDelaySec)
        assertEquals(7, m.shopMoveDelaySec)
        assertNull(m.tabGestureOv["tCardSwipe"])
        assertEquals("OFF", m.tabGestureOv["sCardSwipe"])
        // global prefill seeded from tasks; tasks trio equal → INHERIT
        assertEquals("INHERIT", m.tasksNewDueMode)
        assertEquals("TOMORROW", m.globalNewDueMode)
    }

    @Test
    fun migration_v10Resolvers_endToEnd() {
        val m = SettingsStore.migrate(AppSettings(ver = 9, moveDelaySec = 5, tasksMoveDelaySec = 5))
        assertEquals(5, delayFor(m, Tab.TASKS)) // -1 sentinel resolves back to global 5
    }
}
