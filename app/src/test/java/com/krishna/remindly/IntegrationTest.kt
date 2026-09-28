package com.krishna.remindly

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Integration tests (Robolectric, sdk 28 so file paths — not MediaStore — are exercised):
 * store save→load round-trips with healing, the export→sniff→merge backup cycle,
 * and a boot smoke test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IntegrationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Fresh store files each test.
        File(context.filesDir, "items.json").delete()
        File(context.filesDir, "places.json").delete()
        File(context.filesDir, "calls.json").delete()
        File(context.filesDir, "settings.json").delete()
        Stores.init(context)
        ItemStore.replaceAll(emptyList())
        CallStore.replaceAll(emptyList())
        PlaceStore.replaceAll(emptyList())
    }

    // ---------------- store round-trip + healing ----------------

    @Test
    fun itemStore_roundTripPreservesRecurrence() {
        val item = Item(
            id = 100L, tab = Tab.TASKS, title = "round trip",
            repeatMode = "MONTHLY_ORD", repeatOrdList = listOf(11, 56)
        )
        ItemStore.upsert(item)
        ItemStore.load()
        val back = ItemStore.items.value.first { it.id == 100L }
        assertEquals(listOf(11, 56), back.repeatOrdList)
        assertTrue(back.updatedAt > 0) // upsert stamped it
    }

    @Test
    fun itemStore_legacyJsonOnDiskHealsOnLoad() {
        // Simulate a v1.8 store file: field repeatOrdList absent.
        File(context.filesDir, "items.json").writeText(
            """[{"id":5,"tab":"TASKS","title":"legacy","createdAt":1,"personal":false,
                "dueHasTime":true,"repeatMode":"DAILY","repeatDays":[],"repeatN":1,
                "repeatUnit":"D","repeatOrd":1,"repeatDow":1,"done":false}]""".trimIndent()
        )
        // pinpoint: raw Gson parse of the same file must succeed first
        val rawList = com.google.gson.Gson().fromJson(
            File(context.filesDir, "items.json").readText(), Array<Item>::class.java
        )
        org.junit.Assert.assertEquals("raw Gson parse", 1, rawList.size)
        org.junit.Assert.assertEquals(
            "filesDir identity", context.filesDir.absolutePath, Stores.appContext.filesDir.absolutePath
        )
        ItemStore.load()
        org.junit.Assert.assertEquals(
            "healed load (Logger says: " + Logger.readAll(context).takeLast(300) + ")",
            1, ItemStore.items.value.size
        )
        val it5 = ItemStore.items.value.first { it.id == 5L }
        val copied = it5.copy(done = true) // the v1.9 crash operation
        assertEquals("legacy", copied.title)
        assertEquals(emptyList<Int>(), copied.repeatOrdList)
    }

    // ---------------- backup cycle ----------------

    @Test
    fun backup_exportSniffMerge_latestWins() {
        val older = Item(id = 1L, tab = Tab.TASKS, title = "OLD title")
        val newer = Item(id = 1L, tab = Tab.TASKS, title = "NEW title")
        ItemStore.upsert(older) // stamps updatedAt now
        val exported = Backup.exportDataJson()
        assertEquals("data", Backup.sniff(exported))
        Thread.sleep(5)
        ItemStore.upsert(newer) // later stamp on the phone
        val blob = Backup.parseData(exported)!!
        Backup.mergeData(context, blob)
        // Phone copy is newer → survives the merge.
        assertEquals("NEW title", ItemStore.items.value.first { it.id == 1L }.title)
        assertEquals(1, ItemStore.items.value.size)
    }

    @Test
    fun backup_mergeAddsUnknownIds_neverDeletes() {
        ItemStore.upsert(Item(id = 1L, tab = Tab.TASKS, title = "mine"))
        val blob = Backup.DataBlob(
            items = listOf(Item(id = 2L, tab = Tab.SHOP, title = "theirs", updatedAt = 1L)),
            tasksGroups = listOf("Imported group")
        )
        Backup.mergeData(context, blob)
        assertEquals(2, ItemStore.items.value.size)
        assertTrue(SettingsStore.s.value.tasksGroups.contains("Imported group"))
    }

    @Test
    fun backup_settingsImportPreservesGroupLists() {
        SettingsStore.update { it.copy(tasksGroups = listOf("Keep me")) }
        val incoming = Backup.SettingsBlob(settings = AppSettings(theme = "DARK", tasksGroups = listOf("Foreign")))
        Backup.applySettings(incoming)
        assertEquals("DARK", SettingsStore.s.value.theme)
        assertEquals(listOf("Keep me"), SettingsStore.s.value.tasksGroups)
    }

    // ---------------- settings migration on real store ----------------

    @Test
    fun settings_v9FileOnDiskMigratesToV10() {
        File(context.filesDir, "settings.json").writeText(
            """{"ver":9,"moveDelaySec":3,"tasksMoveDelaySec":3,"shopMoveDelaySec":7}"""
        )
        // stage A: parse+migrate directly, bypassing the store's file plumbing
        val direct = SettingsStore.migrate(
            healSettings(com.google.gson.Gson().fromJson(
                File(context.filesDir, "settings.json").readText(), AppSettings::class.java
            ))
        )
        assertEquals("direct migrate", -1, direct.tasksMoveDelaySec)
        // stage B: through the store
        SettingsStore.load()
        val s = SettingsStore.s.value
        assertEquals(42, s.ver)
        assertEquals(-1, s.tasksMoveDelaySec)
        assertEquals(7, s.shopMoveDelaySec)
        assertEquals(3, delayFor(s, Tab.TASKS))
    }

    // ---------------- v1.11 soft-delete ----------------

    @Test
    fun bin_softDeleteHidesRestoreReturns_purgeRemoves() {
        val item = Item(id = 9L, tab = Tab.TASKS, title = "binme")
        ItemStore.upsert(item)
        ItemStore.upsert(item.copy(deletedAt = System.currentTimeMillis()))
        assertTrue(ItemStore.items.value.first { it.id == 9L }.deletedAt != null)
        // live filter used by lists/widgets/digest excludes it
        assertEquals(0, filterLive(ItemStore.items.value) { it.deletedAt }.count { (it as Item).id == 9L })
        // restore
        ItemStore.upsert(ItemStore.items.value.first { it.id == 9L }.copy(deletedAt = null))
        assertEquals(1, filterLive(ItemStore.items.value) { it.deletedAt }.count { (it as Item).id == 9L })
        // purge: stamp far in the past then apply the sweep condition
        ItemStore.upsert(ItemStore.items.value.first { it.id == 9L }.copy(deletedAt = 1L))
        val cut = purgeCutoff(System.currentTimeMillis())
        ItemStore.items.value.filter { (it.deletedAt ?: Long.MAX_VALUE) < cut }.forEach { ItemStore.delete(it.id) }
        assertEquals(0, ItemStore.items.value.count { it.id == 9L })
    }

    @Test
    fun v111_settingsMigration_seedsSortFromBooleans() {
        val m = SettingsStore.migrate(AppSettings(ver = 10, shopGroupByGroup = true, tasksGroupByGroup = false))
        assertEquals(42, m.ver)
        assertEquals("GROUP", m.shopSort)
        assertEquals("DATE", m.tasksSort)
    }

    // ---------------- v1.12 recurrence: complete → Done → resurrect → Active ----------------

    @Test
    fun v112_recurringCompleteLandsInDone_thenResurrects() {
        val zone = java.time.ZoneId.systemDefault()
        val due = java.time.LocalDate.now().minusDays(1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val item = Item(id = 77L, tab = Tab.TASKS, title = "daily standup", dueAt = due, repeatMode = "DAILY", dueHasTime = true)
        ItemStore.upsert(item)
        Engine.completeNow(context, 77L)
        val afterComplete = ItemStore.items.value.first { it.id == 77L }
        assertTrue("moves to Done", afterComplete.done)
        assertTrue("due advanced", (afterComplete.dueAt ?: 0L) > due)
        // midnight/catch-up sweep ON THE DUE DAY walks it back to Active —
        // simulate that day's clock (deterministic regardless of when the test runs)
        val revived = resurrectDue(ItemStore.items.value, afterComplete.dueAt!!)
        revived.forEach { ItemStore.upsert(it) }
        val afterSweep = ItemStore.items.value.first { it.id == 77L }
        assertEquals("back to Active", false, afterSweep.done)
        assertEquals(null, afterSweep.doneAt)
    }

    // ---------------- v1.14 manual call recurrence round-trip ----------------

    @Test
    fun v114_recurringCall_completeThenResurrect() {
        val zone = java.time.ZoneId.systemDefault()
        val at = java.time.LocalDate.now().atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val r = CallReminder(id = 88L, number = "98300", name = "Maa", source = CallSource.MANUAL,
            createdAt = at - 1000, repeatMode = "DAILY", recurAt = at)
        CallStore.upsert(r)
        CallEngine.markCleared(context, r, "Marked done")
        val done = CallStore.calls.value.first { it.id == 88L }
        assertTrue(done.done)
        assertTrue("recurAt advanced", (done.recurAt ?: 0L) > at)
        val revived = resurrectCallsDue(CallStore.calls.value, done.recurAt!!)
        revived.forEach { CallStore.upsert(it) }
        val back = CallStore.calls.value.first { it.id == 88L }
        assertEquals(false, back.done)
    }

    // ---------------- v1.15 SPACED ladder ----------------

    @Test
    fun v115_spacedComplete_advancesStep_thenResurrects() {
        val zone = java.time.ZoneId.systemDefault()
        val due = java.time.LocalDate.now().minusDays(1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val item = Item(id = 88L, tab = Tab.LEARN, title = "spaced topic", dueAt = due,
            repeatMode = "SPACED", spacedStep = 0, dueHasTime = true)
        ItemStore.upsert(item)
        Engine.completeNow(context, 88L)
        val after = ItemStore.items.value.first { it.id == 88L }
        assertTrue("in Done", after.done)
        assertEquals("step climbed", 1, after.spacedStep)
        assertTrue("due advanced by the 3-day gap", (after.dueAt ?: 0L) > due)
        val revived = resurrectDue(ItemStore.items.value, after.dueAt!!)
        revived.forEach { ItemStore.upsert(it) }
        assertEquals(false, ItemStore.items.value.first { it.id == 88L }.done)
    }

    // ---------------- boot smoke ----------------

    @Test
    fun smoke_storesLoadLoggerWritesAndReads() {
        ItemStore.load(); CallStore.load(); PlaceStore.load(); SettingsStore.load()
        Logger.e(context, "TEST", RuntimeException("smoke"), "boot smoke entry")
        val log = Logger.readAll(context)
        assertTrue(log.contains("boot smoke entry"))
        assertTrue(log.contains("RuntimeException"))
        Logger.clear(context)
        assertEquals("", Logger.readAll(context))
    }
}
