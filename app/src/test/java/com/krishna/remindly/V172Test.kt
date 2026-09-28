package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.72: the heal* functions must PRESERVE by default.
 *
 * They used to rebuild each model field-by-field, so every field added after they were written
 * was silently replaced by its constructor default. On the sync read-back path that destroyed
 * the user's per-item Reminder Type seconds after saving it — Firestore fires its snapshot
 * listener on the device's own write, so no second device was even needed.
 *
 * Each test below pins a field that WAS being dropped.
 */
class V172Test {

    // ---------------- healItem ----------------

    @Test fun healItem_keepsAlertType() {
        listOf("A", "R", "N").forEach { t ->
            val i = Item(id = 1, tab = Tab.TASKS, title = "x", alertType = t)
            assertEquals("alertType $t", t, healItem(i).alertType)
        }
    }

    @Test fun healItem_keepsRepeatCounters() {
        val i = Item(id = 1, tab = Tab.TASKS, title = "x", repeatCount = 9, repeatDone = 4)
        val h = healItem(i)
        assertEquals(9, h.repeatCount)
        assertEquals(4, h.repeatDone)
    }

    @Test fun healItem_isIdentityOnAHealthyItem() {
        val i = Item(
            id = 5, tab = Tab.SHOP, title = "Rice", notes = "basmati",
            alertType = "R", repeatMode = "DAILY", repeatUnit = "D",
            repeatCount = 3, repeatDone = 1, shopName = "Big Bazaar",
            priority = Priority.HIGH, dueAt = 1_700_000_000_000L, dueHasTime = true
        )
        assertEquals(i, healItem(i))
    }

    @Test fun healItem_stillCoercesTheBrokenFields() {
        val i = Item(id = 1, tab = Tab.TASKS, title = "x", repeatMode = "OFF")
        val h = healItem(i)
        assertEquals("OFF", h.repeatMode)
        assertEquals("D", h.repeatUnit)
        assertTrue(h.repeatDays.isEmpty())
    }

    // ---------------- healCall ----------------

    @Test fun healCall_keepsAlertTypeAndContactFields() {
        val r = CallReminder(
            id = 2, number = "999", source = CallSource.AUTO, alertType = "A",
            firstName = "Krishna", lastName = "Bhunia", company = "Acme",
            repeatCount = 6, repeatDone = 2
        )
        val h = healCall(r)
        assertEquals("A", h.alertType)
        assertEquals("Krishna", h.firstName)
        assertEquals("Bhunia", h.lastName)
        assertEquals("Acme", h.company)
        assertEquals(6, h.repeatCount)
        assertEquals(2, h.repeatDone)
    }

    @Test fun healCall_isIdentityOnAHealthyCall() {
        val r = CallReminder(id = 2, number = "999", source = CallSource.MANUAL, alertType = "R")
        assertEquals(r, healCall(r))
    }

    // ---------------- healSettings ----------------

    @Test fun healSettings_keepsMediumTagSettings() {
        val a = AppSettings(
            showMediumTag = true, tasksShowMediumTag = 0,
            shopShowMediumTag = 1, learnShowMediumTag = 0
        )
        val h = healSettings(a)
        assertTrue(h.showMediumTag)
        assertEquals(0, h.tasksShowMediumTag)
        assertEquals(1, h.shopShowMediumTag)
        assertEquals(0, h.learnShowMediumTag)
    }

    @Test fun healSettings_keepsTabVisibilityAndCardToggles() {
        val a = AppSettings(
            showLearn = false, showCalls = false,
            cardShowDateTime = false, cardShowRepeat = false
        )
        val h = healSettings(a)
        assertEquals(false, h.showLearn)
        assertEquals(false, h.showCalls)
        assertEquals(false, h.cardShowDateTime)
        assertEquals(false, h.cardShowRepeat)
    }

    @Test fun healSettings_keepsPerTabAlertSwitches() {
        val a = AppSettings(shopAlertsOn = "OFF", callsAlertsOn = "ON")
        val h = healSettings(a)
        assertEquals("OFF", h.shopAlertsOn)
        assertEquals("ON", h.callsAlertsOn)
    }

    @Test fun healSettings_schemaVersionUntouched() {
        assertEquals(42, healSettings(AppSettings()).ver)
    }

    // ---------------- the exact reported bug ----------------

    @Test fun theReportedBug_alarmSurvivesASyncRoundTrip() {
        val saved = Item(id = 42, tab = Tab.TASKS, title = "Car Start", alertType = "A")
        // what Sync does: encode -> the device's own snapshot echo -> decode -> heal -> apply
        val echoed = gson.fromJson(gson.toJson(saved), Item::class.java)
        assertEquals("A", healItem(echoed).alertType)
    }

    // ---------------- the NPE trap that copy() introduces ----------------

    @Test fun legacyJsonWithNoAlertTypeDoesNotThrowAndDefaultsToNotify() {
        // Gson bypasses the Kotlin constructor, so an absent key lands as NULL even in a
        // non-null field. copy() reads every field as a default, so without the coercion in
        // healItem this line throws NPE — which is what the on-device repeat-bounce crash was.
        val legacy = """{"id":9,"tab":"TASKS","title":"legacy","repeatMode":"OFF"}"""
        val healed = healItem(gson.fromJson(legacy, Item::class.java))
        assertEquals("N", healed.alertType)
        assertEquals(emptyList<Int>(), healed.repeatOrdList)
        // and the follow-up copy that used to crash:
        assertEquals("N", healed.copy(dueAt = 1L).alertType)
    }

    @Test fun legacyCallJsonWithNoAlertTypeDoesNotThrow() {
        val legacy = """{"id":9,"number":"+911234567890","source":"AUTO"}"""
        val healed = healCall(gson.fromJson(legacy, CallReminder::class.java))
        assertEquals("N", healed.alertType)
        assertEquals("+911234567890", healed.copy(missedCount = 3).number)
    }
}
