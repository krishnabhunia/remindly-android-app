package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.10 — N9 (every permission optional, asked in context, explained) + N6 pt2 gates.
 * Per N6: every assertion calls production code (Model.kt resolvers, the real manifest, the real sources).
 */
class V210Test {

    private val allStates = PermState.entries

    // ---------------- the resolver, across granted / not asked / denied / blocked ----------------

    @Test fun grantedWinsWhateverTheRecordSays() {
        for (asked in listOf(true, false)) for (again in listOf(true, false))
            assertEquals(PermState.GRANTED, resolvePermState(granted = true, asked = asked, canAskAgain = again))
    }

    @Test fun neverAskedIsNotBlocked() {
        // Android reports canAskAgain=false BEFORE the first ask too — only our record separates them.
        assertEquals(PermState.NOT_ASKED, resolvePermState(false, asked = false, canAskAgain = false))
        assertEquals(PermState.NOT_ASKED, resolvePermState(false, asked = false, canAskAgain = true))
    }

    @Test fun refusedOnceCanBeAskedAgain() =
        assertEquals(PermState.DENIED, resolvePermState(false, asked = true, canAskAgain = true))

    @Test fun refusedForGoodIsBlocked() =
        assertEquals(PermState.BLOCKED, resolvePermState(false, asked = true, canAskAgain = false))

    // ---------------- what "Allow" does ----------------

    @Test fun firstTimeShowsTheWhyBeforeTheSystemDialog() {
        assertEquals(PermAction.PRE_PROMPT, permAction(PermState.NOT_ASKED, prePromptSeen = false))
        assertEquals("the why is shown once", PermAction.SYSTEM_DIALOG, permAction(PermState.NOT_ASKED, prePromptSeen = true))
    }

    @Test fun blockedGoesToSettingsNeverASilentNoOp() {
        // the old bug: launching a request Android will not show does nothing at all
        for (seen in listOf(true, false)) assertEquals(PermAction.OPEN_SETTINGS, permAction(PermState.BLOCKED, seen))
    }

    @Test fun deniedAsksAgainAndGrantedDoesNothing() {
        for (seen in listOf(true, false)) {
            assertEquals(PermAction.SYSTEM_DIALOG, permAction(PermState.DENIED, seen))
            assertEquals(PermAction.NONE, permAction(PermState.GRANTED, seen))
        }
    }

    @Test fun everyStateHasALabelAndOnlyGrantedHasNoButton() {
        allStates.forEach { st ->
            assertTrue(permStateLabel(st).isNotBlank())
            assertEquals(st == PermState.GRANTED, permButtonLabel(st) == null)
        }
        assertEquals("Open settings", permButtonLabel(PermState.BLOCKED))
    }

    // ---------------- per-feature degrade: the tab stays usable ----------------

    @Test fun callsWithoutCallLogIsManualNotDead() {
        allStates.forEach { st ->
            assertEquals(if (st == PermState.GRANTED) CallDetect.AUTO else CallDetect.MANUAL_ONLY, callDetectMode(st))
        }
    }

    @Test fun callsIntroCardOnlyUntilTheUserDecides() {
        assertTrue(callsIntroVisible(PermState.NOT_ASKED, prePromptSeen = false))
        assertFalse("Not now → the card becomes a one-line note", callsIntroVisible(PermState.NOT_ASKED, prePromptSeen = true))
        assertTrue(callsNoteVisible(PermState.NOT_ASKED, prePromptSeen = true))
        for (st in listOf(PermState.DENIED, PermState.BLOCKED)) {
            assertFalse(callsIntroVisible(st, false)); assertTrue(callsNoteVisible(st, false))
        }
        assertFalse(callsIntroVisible(PermState.GRANTED, false)); assertFalse(callsNoteVisible(PermState.GRANTED, true))
        allStates.forEach { st -> for (seen in listOf(true, false))
            assertFalse("never both the card and the note", callsIntroVisible(st, seen) && callsNoteVisible(st, seen)) }
    }

    @Test fun namesOnlyWithContacts() = allStates.forEach { assertEquals(it == PermState.GRANTED, callNamesShown(it)) }

    @Test fun geofenceModesAcrossEveryCombination() {
        for (loc in allStates) for (bg in allStates) for (sdk in listOf(28, 29, 34)) {
            val m = geofenceMode(loc, bg, sdk)
            val want = when {
                loc != PermState.GRANTED -> GeoMode.OFF
                sdk < 29 || bg == PermState.GRANTED -> GeoMode.ALWAYS
                else -> GeoMode.WHILE_OPEN
            }
            assertEquals("loc=$loc bg=$bg sdk=$sdk", want, m)
            assertEquals("a note exactly when alerts are limited", m != GeoMode.ALWAYS, geofenceNote(m) != null)
        }
    }

    @Test fun notificationsOnlyMatterFromAndroid13() {
        allStates.forEach { st ->
            assertTrue(remindersVisible(st, 32))
            assertEquals(st == PermState.GRANTED, remindersVisible(st, 33))
        }
    }

    @Test fun oneStateWithTheDegradeBanner() {
        // N9 ⚑: never-asked → pre-prompt only, never also a banner
        assertFalse(permDegradeVisible(PermState.NOT_ASKED))
        assertFalse(permDegradeVisible(PermState.GRANTED))
        assertTrue(permDegradeVisible(PermState.DENIED))
        assertTrue(permDegradeVisible(PermState.BLOCKED))
    }

    // ---------------- the table: declared once, complete, honest ----------------

    @Test fun keysAreUniqueAndEveryRowIsExplained() {
        assertEquals(RUNTIME_PERMISSIONS.size, RUNTIME_PERMISSIONS.map { it.key }.toSet().size)
        RUNTIME_PERMISSIONS.forEach {
            assertTrue(it.key, it.title.isNotBlank() && it.enables.isNotBlank() && it.withoutIt.isNotBlank())
            assertTrue(it.key, it.permissions.isNotEmpty())
            assertTrue("${it.key}: what is checked must be requested", it.permissions.containsAll(it.checkPermissions))
            assertNotNull(permInfo(it.key))
        }
    }

    @Test fun permsDoNotExistBelowTheirSdk() {
        assertTrue(permsToRequest(permInfo(PermKeys.NOTIFICATIONS), 32).isEmpty())
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), permsToRequest(permInfo(PermKeys.NOTIFICATIONS), 33))
        assertTrue(permsToRequest(permInfo(PermKeys.BG_LOCATION), 28).isEmpty())
        assertEquals(2, permsToRequest(permInfo(PermKeys.CALL_LOG), 26).size)
    }

    @Test fun onlyNotificationsIsNotOptional() {
        // every "without it" line says what still works — no permission turns a tab into a dead end
        RUNTIME_PERMISSIONS.filter { it.key != PermKeys.NOTIFICATIONS }.forEach {
            assertFalse("${it.key} must describe a degraded mode, not a dead end", it.withoutIt.contains("can't use", ignoreCase = true))
        }
    }

    private fun manifest(): String = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        .map { File(it) }.first { it.exists() }.readText()

    /** CLASS-WIDE GATE: a runtime permission added to the manifest without a row here fails the build. */
    @Test fun everyDangerousManifestPermissionIsInTheTable() {
        val dangerous = setOf(
            "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_PHONE_STATE", "CALL_PHONE", "READ_CONTACTS", "WRITE_CONTACTS",
            "GET_ACCOUNTS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "POST_NOTIFICATIONS", "READ_CALENDAR", "WRITE_CALENDAR", "CAMERA", "RECORD_AUDIO", "READ_SMS",
            "SEND_SMS", "RECEIVE_SMS", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
            "BODY_SENSORS", "ACTIVITY_RECOGNITION", "NEARBY_WIFI_DEVICES", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN"
        ).map { "android.permission.$it" }.toSet()
        val declared = Regex("uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest()).map { it.groupValues[1] }.toSet()
        val covered = RUNTIME_PERMISSIONS.flatMap { it.permissions }.toSet()
        val missing = declared.filter { it in dangerous && it !in covered }
        assertTrue("declared but never explained/asked through N9: $missing", missing.isEmpty())
        val undeclared = covered.filter { it !in declared }
        assertTrue("in the table but not in the manifest (typo?): $undeclared", undeclared.isEmpty())
    }

    private fun mainSources(): List<File> = listOf("src/main/java", "app/src/main/java").map { File(it) }
        .first { it.exists() }.walkTopDown().filter { it.extension == "kt" }.toList()

    /**
     * CLASS-WIDE GATE: every runtime permission request goes through rememberPermRequest (Perms.kt),
     * so none can skip the explanation or silently no-op once blocked. The single sanctioned exception
     * is the launch-time notification request (N9 ⚑3).
     */
    @Test fun noScreenRequestsAPermissionOnItsOwn() {
        val offenders = mainSources().filter { f ->
            val t = f.readText()
            val n = Regex("RequestPermission\\(\\)|RequestMultiplePermissions\\(\\)").findAll(t).count()
            when (f.name) { "Perms.kt", "MainActivity.kt" -> n > 1; else -> n > 0 }
        }.map { it.name }
        assertTrue("direct permission launchers outside the shared requester: $offenders", offenders.isEmpty())
    }

    // ---------------- N6 pt2 gate: mirrors stay gone ----------------

    private fun testSources(): List<File> = listOf("src/test/java", "app/src/test/java").map { File(it) }
        .first { it.exists() }.walkTopDown().filter { it.extension == "kt" && it.name != "V210Test.kt" }.toList()

    /** A test labelled "Mirrors …" re-implements production logic and passes even if production is deleted. */
    @Test fun noTestCarriesAMirrorOfProductionLogic() {
        val offenders = testSources().filter { f -> f.readLines().any { it.trim().startsWith("/** Mirrors") } }.map { it.name }
        assertTrue("mirrored logic in: $offenders", offenders.isEmpty())
    }

    @Test fun extractedFunctionsAreTheOnesProductionCalls() {
        // each N6 pt2 extraction must be referenced from its production call site, not just from tests
        val src = mainSources().associate { it.name to it.readText() }
        mapOf(
            "listScope(" to "ListScreens.kt", "bulkClearTargets(" to "ListScreens.kt",
            "rearmKind(" to "AlarmScheduler.kt", "itemFireAt(" to "AlarmScheduler.kt",
            "waDigits(" to "Calls.kt", "migrateSnooze90(" to "RemindlyApp.kt",
            "itemTapRequestCode(" to "Receivers.kt", "overlayOlderWriter(" to "Sync.kt"
        ).forEach { (fn, file) -> assertTrue("$fn is not used by $file", src[file]?.contains(fn) == true) }
    }

    @Test fun waDigitsAndRequestCodesBehave() {
        assertEquals("919876543210", waDigits("98765 43210", "+91"))
        assertEquals("447700900123", waDigits("+44 7700 900123", "91"))
        assertEquals(8_500_042, itemTapRequestCode(42L))
        assertNull(itemFireAt(Item(id = 1, tab = Tab.TASKS, title = "x", dueAt = 5L, done = true), 0L))
    }
}
