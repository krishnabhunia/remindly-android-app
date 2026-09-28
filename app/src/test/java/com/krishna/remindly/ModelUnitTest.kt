package com.krishna.remindly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure-logic unit tests — recurrence engine, alert matrix, inherit resolvers.
 * These run on the plain JVM: no Android, no Robolectric.
 */
class ModelUnitTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(y: Int, m: Int, d: Int, h: Int = 10, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun date(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun item(mode: String, due: Long, days: List<Int> = emptyList(), ordList: List<Int> = emptyList(),
                     n: Int = 1, unit: String = "D", ord: Int = 1, dow: Int = 1) =
        Item(id = 1L, tab = Tab.TASKS, title = "t", dueAt = due, repeatMode = mode,
            repeatDays = days, repeatOrdList = ordList, repeatN = n, repeatUnit = unit,
            repeatOrd = ord, repeatDow = dow)

    // ---------------- monthly day-SET ----------------

    @Test fun monthlyDaySet_picksNextLaterDayInSameMonth() {
        val due = at(2026, 7, 11)
        val next = nextOccurrence(item("MONTHLY_DAY", due, days = listOf(11, 17, 29)), due)!!
        assertEquals(LocalDate.of(2026, 7, 17), date(next))
    }

    @Test fun monthlyDaySet_rollsToFirstOfNextMonthAfterLastDay() {
        val due = at(2026, 7, 29)
        val next = nextOccurrence(item("MONTHLY_DAY", due, days = listOf(11, 17, 29)), due)!!
        assertEquals(LocalDate.of(2026, 8, 11), date(next))
    }

    @Test fun monthlyDaySet_day31ClampsInShortMonths() {
        val due = at(2026, 1, 31)
        val next = nextOccurrence(item("MONTHLY_DAY", due, days = listOf(31)), due)!!
        assertEquals(LocalDate.of(2026, 2, 28), date(next)) // 2026 not a leap year
    }

    @Test fun monthlyDaySet_emptySetFallsBackToDuesOwnDay() {
        val due = at(2026, 7, 15)
        val next = nextOccurrence(item("MONTHLY_DAY", due, days = emptyList()), due)!!
        assertEquals(LocalDate.of(2026, 8, 15), date(next))
    }

    // ---------------- monthly ordinal-pattern SET ----------------

    @Test fun ordinalPatternSet_earliestHitWins() {
        // From Wed 1 Jul 2026: First Monday = 6 Jul, Last Saturday = 25 Jul → 6 Jul first.
        val due = at(2026, 7, 1)
        val next = nextOccurrence(item("MONTHLY_ORD", due, ordList = listOf(11, 56)), due)!!
        assertEquals(LocalDate.of(2026, 7, 6), date(next))
    }

    @Test fun ordinalPatternSet_secondHitOfSameMonth() {
        // From 6 Jul (after First Monday fired): Last Saturday 25 Jul is the next hit.
        val after = at(2026, 7, 6)
        val next = nextOccurrence(item("MONTHLY_ORD", after, ordList = listOf(11, 56)), after)!!
        assertEquals(LocalDate.of(2026, 7, 25), date(next))
    }

    @Test fun ordinalPattern_emptyListUsesLegacySinglePair() {
        // Legacy: Second Tuesday (ord 2, dow 2). From 1 Jul 2026 → 14 Jul 2026.
        val due = at(2026, 7, 1)
        val next = nextOccurrence(item("MONTHLY_ORD", due, ord = 2, dow = 2), due)!!
        assertEquals(LocalDate.of(2026, 7, 14), date(next))
    }

    // ---------------- quarterly / half-yearly / yearly ----------------

    @Test fun quarterly_addsThreeMonths() {
        val due = at(2026, 1, 15)
        val next = nextOccurrence(item("QUARTERLY", due), due)!!
        assertEquals(LocalDate.of(2026, 4, 15), date(next))
    }

    @Test fun halfYearly_addsSixMonths_withClamp() {
        val due = at(2026, 8, 31)
        val next = nextOccurrence(item("HALFYEARLY", due), due)!!
        assertEquals(LocalDate.of(2027, 2, 28), date(next)) // 31 Aug + 6mo clamps to 28 Feb
    }

    @Test fun yearly_leapDayLandsOn28InOffYears() {
        val due = at(2028, 2, 29) // 2028 is a leap year
        val next = nextOccurrence(item("YEARLY", due), due)!!
        assertEquals(LocalDate.of(2029, 2, 28), date(next))
    }








    // ---------------- inherit resolvers (v1.10) ----------------

    @Test fun delay_inheritSentinelFallsThroughToGlobal() {
        val st = AppSettings(moveDelaySec = 7, tasksMoveDelaySec = -1, shopMoveDelaySec = 3)
        assertEquals(7, delayFor(st, Tab.TASKS))
        assertEquals(3, delayFor(st, Tab.SHOP))
    }

    @Test fun clear_inheritSentinelFallsThroughToGlobal() {
        val st = AppSettings(globalDoneClearDays = 30, tasksDoneClearDays = -1, learnDoneClearDays = 7)
        assertEquals(30, clearFor(st, Tab.TASKS))
        assertEquals(7, clearFor(st, Tab.LEARN))
    }

    @Test fun gestures_overrideMapWinsAbsentKeyInherits() {
        val st = AppSettings(
            gestureCardSwipe = true, deleteStyle = "UNDO",
            tabGestureOv = mapOf("sCardSwipe" to "OFF", "sDeleteStyle" to "CONFIRM")
        )
        assertTrue(gesturesFor(st, Tab.TASKS).cardSwipe)          // inherited
        assertEquals("UNDO", gesturesFor(st, Tab.TASKS).deleteStyle)
        assertEquals(false, gesturesFor(st, Tab.SHOP).cardSwipe)  // overridden
        assertEquals("CONFIRM", gesturesFor(st, Tab.SHOP).deleteStyle)
    }

    @Test fun prefill_inheritUsesGlobalTrio() {
        val st = AppSettings(
            tasksNewDueMode = "INHERIT",
            globalNewDueMode = "TODAY", globalNewDueMinutes = 9 * 60
        )
        val due = defaultNewDue(Tab.TASKS, st)!!
        val d = Instant.ofEpochMilli(due).atZone(zone)
        assertEquals(LocalDate.now(), d.toLocalDate())
        assertEquals(9, d.hour)
    }

    // ---------------- v1.11 helpers ----------------

    @Test fun v111_priorityRank_ordersUrgentFirst_nullAsMedium() {
        assertEquals(0, rankOf(Priority.URGENT))
        assertEquals(1, rankOf(Priority.HIGH))
        assertEquals(2, rankOf(null))
        assertEquals(2, rankOf(Priority.MEDIUM))
        assertEquals(3, rankOf(Priority.LOW))
    }


    @Test fun v111_sortOf_readsPerTab() {
        val st = AppSettings(tasksSort = "PRIORITY", shopSort = "GROUP")
        assertEquals("PRIORITY", sortOf(st, Tab.TASKS))
        assertEquals("GROUP", sortOf(st, Tab.SHOP))
        assertEquals("DATE", sortOf(st, Tab.LEARN))
    }

    @Test fun v111_purgeCutoff_thirtyDays() {
        val now = 100L * 24 * 60 * 60 * 1000
        assertEquals(70L * 24 * 60 * 60 * 1000, purgeCutoff(now))
    }

    // ---------------- v1.12 recurrence resurrection ----------------

    private fun doneRec(id: Long, dueAt: Long, mode: String = "DAILY", deleted: Long? = null, done: Boolean = true) =
        Item(id = id, tab = Tab.TASKS, title = "r$id", dueAt = dueAt, repeatMode = mode,
            done = done, doneAt = if (done) dueAt - 1000 else null, deletedAt = deleted)

    @Test fun v112_resurrect_revivesWhenDueDayArrived() {
        val zone = ZoneId.systemDefault()
        val todayNoon = LocalDate.now().atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val midnight = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val revived = resurrectDue(listOf(doneRec(1, todayNoon)), midnight)
        assertEquals(1, revived.size)
        assertEquals(false, revived[0].done)
        assertEquals(null, revived[0].doneAt)
    }

    @Test fun v112_resurrect_skipsTomorrowNonRecurringDeletedActive() {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowNoon = LocalDate.now().plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val todayNoon = LocalDate.now().atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val out = resurrectDue(
            listOf(
                doneRec(1, tomorrowNoon),                       // due tomorrow → wait
                doneRec(2, todayNoon, mode = "OFF"),            // not recurring → never
                doneRec(3, todayNoon, deleted = 5L),            // binned → never
                doneRec(4, todayNoon, done = false)             // already active → no-op
            ), now
        )
        assertEquals(0, out.size)
    }

    @Test fun v112_resurrect_catchesUpOverdueDays() {
        val zone = ZoneId.systemDefault()
        val yesterdayNoon = LocalDate.now().minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDate.now().atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(1, resurrectDue(listOf(doneRec(1, yesterdayNoon)), now).size)
    }

    // ---------------- v1.14 calls recurrence + preview + spend ----------------

    @Test fun v114_nextOccurrenceCall_weeklyAdvances() {
        val zone = ZoneId.systemDefault()
        val anchor = LocalDate.now().atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val r = CallReminder(id = 1, number = "9", name = "X", source = CallSource.MANUAL,
            createdAt = anchor, repeatMode = "DAILY", recurAt = anchor)
        val next = nextOccurrenceCall(r, anchor)
        assertTrue(next != null && next > anchor)
        assertEquals(anchor + 24L * 3_600_000, next)
    }

    @Test fun v114_previewOccurrences_chainsThree() {
        val zone = ZoneId.systemDefault()
        val anchor = LocalDate.now().atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val p = previewOccurrences("DAILY", emptySet(), 1, "D", 1, 1, emptyList(), anchor, 3)
        assertEquals(3, p.size)
        assertTrue(p[0] < p[1] && p[1] < p[2])
        assertEquals(24L * 3_600_000, p[1] - p[0])
    }

    @Test fun v114_resurrectCallsDue_paritySemantics() {
        val zone = ZoneId.systemDefault()
        val todayNoon = LocalDate.now().atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val tomorrow = LocalDate.now().plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        fun rc(id: Long, at: Long?, mode: String = "WEEKLY", done: Boolean = true, del: Long? = null) =
            CallReminder(id = id, number = "9", name = "c$id", source = CallSource.MANUAL,
                createdAt = 1L, repeatMode = mode, recurAt = at, done = done,
                doneAt = if (done) 2L else null, deletedAt = del)
        val out = resurrectCallsDue(
            listOf(rc(1, todayNoon), rc(2, tomorrow), rc(3, todayNoon, mode = "OFF"),
                rc(4, todayNoon, del = 5L), rc(5, todayNoon, done = false)),
            LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertEquals(listOf(1L), out.map { it.id })
        assertEquals(false, out[0].done)
    }

    @Test fun v114_shopSpend_parsesAndSums() {
        fun sp(p: String?) = Item(id = 1, tab = Tab.SHOP, title = "x", price = p)
        assertEquals(350.0, shopSpend(listOf(sp("100"), sp("₹150"), sp("1,00"), sp(null), sp("junk"))), 0.01)
        assertEquals(" · ₹350", spendLabel(listOf(sp("200"), sp("150"))))
        assertEquals("", spendLabel(listOf(sp(null))))
    }

    // ---------------- v1.15 helpers ----------------

    @Test fun v115_spacedGaps_ladderThenThirty() {
        assertEquals(3, spacedGapDays(0)); assertEquals(7, spacedGapDays(1))
        assertEquals(14, spacedGapDays(2)); assertEquals(30, spacedGapDays(3))
        assertEquals(30, spacedGapDays(9))
    }

    @Test fun v115_startFrom_firstHitOnOrAfterDate_neverPast() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().plusDays(10).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val first = previewOccurrences("WEEKLY", setOf(1), 1, "D", 1, 1, emptyList(), start, 1, startFrom = start).firstOrNull()
        assertTrue("on/after start", first != null && first >= start)
        assertEquals(java.time.DayOfWeek.MONDAY, first!!.toLocalDate().dayOfWeek)
        val past = LocalDate.now().minusDays(30).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val firstPast = previewOccurrences("DAILY", emptySet(), 1, "D", 1, 1, emptyList(), past, 1, startFrom = past).firstOrNull()
        assertTrue("never in the past", firstPast != null && firstPast > System.currentTimeMillis())
    }

    @Test fun v115_occurrencesWithin_horizonAndCap() {
        val zone = ZoneId.systemDefault()
        val anchor = LocalDate.now().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val until = System.currentTimeMillis() + 30L * 86_400_000L
        val occ = occurrencesWithin("DAILY", emptySet(), 1, "D", 1, 1, emptyList(), anchor, anchor, until, cap = 400)
        assertTrue("about a month of dailies", occ.size in 28..31)
        assertTrue(occ.all { it <= until })
        val capped = occurrencesWithin("DAILY", emptySet(), 1, "D", 1, 1, emptyList(), anchor, anchor, until, cap = 5)
        assertEquals(5, capped.size)
    }

    @Test fun v169_mediumAndNullTagsAreHidden() {
        assertEquals(false, showPriorityTag(Priority.MEDIUM, AppSettings(), Tab.TASKS))
        assertEquals(false, showPriorityTag(null, AppSettings(), Tab.TASKS))
        assertTrue(showPriorityTag(Priority.URGENT, AppSettings(), Tab.TASKS))
    }

    @Test fun v115_priceHistory_capAndDelta() {
        var h = emptyList<PricePoint>()
        (1..15).forEach { h = pushPrice(h, it.toLong(), it.toDouble()) }
        assertEquals(12, h.size)
        assertEquals(15.0, h.last().price, 0.0)
        assertEquals(5.0, priceDelta(20.0, 15.0)!!, 0.0)
        assertEquals(null, priceDelta(null, 15.0))
    }

    @Test fun v115_hoursLabel_formats() {
        assertEquals("", hoursLabel(listOf(Item(id = 1, tab = Tab.LEARN, title = "x"))))
        assertEquals(" · 3 h", hoursLabel(listOf(Item(id = 1, tab = Tab.LEARN, title = "x", hoursSpent = 3.0))))
        assertEquals(" · 3.5 h", hoursLabel(listOf(Item(id = 1, tab = Tab.LEARN, title = "x", hoursSpent = 3.5))))
    }

    @Test fun v115_oos_todayOnly_staleCleared() {
        val now = System.currentTimeMillis()
        val today = Item(id = 1, tab = Tab.SHOP, title = "a", oosAt = now)
        val yest = Item(id = 2, tab = Tab.SHOP, title = "b", oosAt = now - 86_400_000L)
        assertTrue(isOosToday(today, now))
        assertEquals(false, isOosToday(yest, now))
        val cleared = clearStaleOos(listOf(today, yest), now)
        assertEquals(listOf(2L), cleared.map { it.id })
        assertEquals(null, cleared[0].oosAt)
    }

    @Test fun v115_dupMatch_caseInsensitiveActiveOnly() {
        val items = listOf(
            Item(id = 1, tab = Tab.SHOP, title = "Rice"),
            Item(id = 2, tab = Tab.SHOP, title = "Atta", done = true),
            Item(id = 3, tab = Tab.TASKS, title = "Rice")
        )
        assertTrue(dupActiveMatch(items, Tab.SHOP, "  rice "))
        assertEquals(false, dupActiveMatch(items, Tab.SHOP, "atta"))
        assertEquals(false, dupActiveMatch(items, Tab.SHOP, ""))
    }

    @Test fun v115_rrule_cleanPatternsOnly() {
        fun item(mode: String, days: List<Int> = emptyList()) =
            Item(id = 1, tab = Tab.TASKS, title = "x", repeatMode = mode, repeatDays = days)
        assertEquals("FREQ=DAILY", CalSync.rruleFor(item("DAILY")))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", CalSync.rruleFor(item("WEEKLY", listOf(1, 3))))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", CalSync.rruleFor(item("MONTHLY_DAY", listOf(15))))
        assertEquals(null, CalSync.rruleFor(item("MONTHLY_DAY", listOf(5, 20)))) // multi-set → single-next
        assertEquals("FREQ=YEARLY", CalSync.rruleFor(item("YEARLY")))
        assertEquals(null, CalSync.rruleFor(item("EVERY_N")))
        assertEquals(null, CalSync.rruleFor(item("SPACED")))
    }

    @Test fun v115_monthKey_format() {
        val zone = ZoneId.systemDefault()
        val ms = LocalDate.of(2026, 7, 23).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-07", monthKey(ms))
    }

    @Test fun v120_allDay_overdue_starts_next_day() {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        fun at(offDays: Long, h: Int) = LocalDate.now().plusDays(offDays).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        // due today 9am, now is later today → NOT overdue (all-day model)
        assertEquals(false, isOverdueDay(at(0, 9), now))
        assertEquals(false, isOverdueDay(at(0, 23), now))
        assertTrue(isOverdueDay(at(-1, 23), now))   // yesterday → overdue
        assertEquals(false, isOverdueDay(at(1, 0), now))
        assertEquals(false, isOverdueDay(null, now))
    }

    @Test fun v120_callBasis_groups_by_occurrence_day() {
        val zone = ZoneId.systemDefault()
        fun at(offDays: Long) = LocalDate.now().plusDays(offDays).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val base = CallReminder(id = 1L, number = "9990001111", source = CallSource.MANUAL, createdAt = at(-10))
        // recurring: groups under the CURRENT occurrence, not the creation day
        val daily = base.copy(repeatMode = "DAILY", recurAt = at(0), lastMissedAt = at(-10))
        assertEquals(at(0), callBasis(daily, false))
        // yearly recurring: same rule — the current occurrence day
        val yearly = base.copy(repeatMode = "YEARLY", recurAt = at(1))
        assertEquals(at(1), callBasis(yearly, false))
        // one-off missed: unchanged
        val once = base.copy(lastMissedAt = at(-2))
        assertEquals(at(-2), callBasis(once, false))
        // overdue once the occurrence day has passed
        assertTrue(callOverdue(base.copy(repeatMode = "DAILY", recurAt = at(-1)), false, System.currentTimeMillis()))
        assertEquals(false, callOverdue(daily, false, System.currentTimeMillis()))
        // labels come from the source alone
        assertEquals(CallSource.MANUAL.label, callBaseLabel(base))
    }

    // ---------------- v1.22 ----------------

    @Test fun v122_repeatCount_endsAfterN() {
        assertEquals(false, repeatExhausted(null, 99))   // unbounded never ends
        assertEquals(false, repeatExhausted(5, 4))
        assertTrue(repeatExhausted(5, 5))
        assertTrue(repeatExhausted(2, 3))
        assertEquals(null, sanitizeRepeatCount(null))
        assertEquals(2, sanitizeRepeatCount(1))          // never below 2
        assertEquals(2, sanitizeRepeatCount(-7))
        assertEquals(9, sanitizeRepeatCount(9))
    }

    @Test fun v122_shouldCommitSwipe_distanceAndFlick() {
        val w = 1000f
        // distance rule at each preset
        assertEquals(false, shouldCommitSwipe(240f, w, 0f, 25, 125))
        assertTrue(shouldCommitSwipe(260f, w, 0f, 25, 125))
        assertEquals(false, shouldCommitSwipe(340f, w, 0f, 35, 125))
        assertTrue(shouldCommitSwipe(360f, w, 0f, 35, 125))
        assertTrue(shouldCommitSwipe(520f, w, 0f, 50, 125))
        // a flick commits early — unless flick speed is off
        assertTrue(shouldCommitSwipe(100f, w, 200f, 35, 125))
        assertEquals(false, shouldCommitSwipe(100f, w, 200f, 35, 0))
        // direction-agnostic magnitude
        assertTrue(shouldCommitSwipe(-400f, w, 0f, 35, 125))
        // nonsense input is safe
        assertEquals(false, shouldCommitSwipe(400f, 0f, 0f, 35, 125))
        assertEquals(false, shouldCommitSwipe(Float.NaN, w, 0f, 35, 125))
        // out-of-range percentages are clamped, not obeyed blindly
        assertTrue(shouldCommitSwipe(710f, w, 0f, 999, 125))
    }

    @Test fun v122_timeFormat_twelveTwentyFourAndEdges() {
        val zone = ZoneId.systemDefault()
        fun at(h: Int, m: Int) = LocalDate.now().atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val f12 = timeFormatterFor("H12", false)
        val f24 = timeFormatterFor("H24", false)
        fun s(ms: Long, f: java.time.format.DateTimeFormatter) =
            java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().format(f)
        assertEquals("12:00 AM", s(at(0, 0), f12))
        assertEquals("00:00", s(at(0, 0), f24))
        assertEquals("12:00 PM", s(at(12, 0), f12))
        assertEquals("12:00", s(at(12, 0), f24))
        assertEquals("6:05 PM", s(at(18, 5), f12))
        assertEquals("18:05", s(at(18, 5), f24))
        // follow-phone honours the device flag
        assertEquals(s(at(18, 5), f24), s(at(18, 5), timeFormatterFor("PHONE", true)))
        assertEquals(s(at(18, 5), f12), s(at(18, 5), timeFormatterFor("PHONE", false)))
    }

    @Test fun v122_settings_roundTripKeepsNewFields() {
        val s = AppSettings(
            ver = 17, timeFormat = "H24", swipeDistancePct = 50, swipeHaptic = false,
            callsNewDueMode = "NDAYS", callsNewDueDays = 4
        )
        val healed = healSettings(s)
        assertEquals(17, healed.ver)
        assertEquals("H24", healed.timeFormat)
        assertEquals(50, healed.swipeDistancePct)
        assertEquals(false, healed.swipeHaptic)
        assertEquals("NDAYS", healed.callsNewDueMode)
        assertEquals(4, healed.callsNewDueDays)
    }

    @Test fun v123_flickSpeed_presetsAndOff() {
        val w = 1000f
        // Sensitive commits on a gentle flick that Firm ignores
        assertTrue(shouldCommitSwipe(80f, w, 90f, 35, 80))
        assertEquals(false, shouldCommitSwipe(80f, w, 90f, 35, 200))
        // Normal sits between them
        assertTrue(shouldCommitSwipe(80f, w, 130f, 35, 125))
        assertEquals(false, shouldCommitSwipe(80f, w, 120f, 35, 125))
        // Off ignores velocity entirely, however hard the flick
        assertEquals(false, shouldCommitSwipe(80f, w, 5000f, 35, 0))
        // ...but distance still commits when Off
        assertTrue(shouldCommitSwipe(400f, w, 0f, 35, 0))
        // negative velocity (leftward flick) counts by magnitude
        assertTrue(shouldCommitSwipe(-80f, w, -300f, 35, 125))
    }

    @Test fun v123_manualCall_oneTimeAndRepeatSchedules() {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now().plusDays(3)
        val anchor = day.atTime(18, 30).atZone(zone).toInstant().toEpochMilli()
        // a plain one-time manual call keeps exactly the date and time chosen
        assertEquals(anchor, combineDayTime(day.atStartOfDay(zone).toInstant().toEpochMilli(), 18 * 60 + 30))
        // every repeat mode still produces a first occurrence from that anchor
        for (mode in listOf("DAILY", "WEEKLY", "MONTHLY_DAY", "YEARLY")) {
            val days = if (mode == "WEEKLY") setOf(day.dayOfWeek.value) else emptySet()
            val first = previewOccurrences(mode, days, 1, "D", 1, day.dayOfWeek.value, emptyList(), anchor, 1, startFrom = anchor)
                .firstOrNull()
            assertTrue("$mode produced no occurrence", first != null)
            assertTrue("$mode went backwards", first!! >= anchor)
        }
        // EVERY_N honours its unit
        val everyThree = previewOccurrences("EVERY_N", emptySet(), 3, "D", 1, 1, emptyList(), anchor, 2, startFrom = anchor)
        assertEquals(2, everyThree.size)
        assertEquals(3L, (everyThree[1] - everyThree[0]) / (24 * 60 * 60 * 1000))
    }

    @Test fun v124_timeFormat_reachesEveryHelper() {
        val zone = ZoneId.systemDefault()
        fun at(h: Int, m: Int) = LocalDate.now().atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val saved = TIME_FORMAT
        try {
            // 24-hour: nothing anywhere may print AM or PM
            TIME_FORMAT = "H24"
            assertTrue(formatTime(at(18, 5)).contains("18:05"))
            assertEquals(false, formatDateTime(at(18, 5)).contains("PM"))
            assertEquals(false, formatDateTime(at(9, 5)).contains("AM"))
            assertTrue(formatDateTime(at(0, 0)).contains("00:00"))
            // 12-hour
            TIME_FORMAT = "H12"
            assertTrue(formatTime(at(18, 5)).contains("6:05 PM"))
            assertTrue(formatDateTime(at(18, 5)).contains("PM"))
            assertTrue(formatDateTime(at(0, 0)).contains("12:00 AM"))
            assertTrue(formatDateTime(at(12, 0)).contains("12:00 PM"))
            // the date half survives either way
            assertTrue(formatDateTime(at(12, 0)).contains(LocalDate.now().year.toString()))
        } finally {
            TIME_FORMAT = saved
        }
    }

    @Test fun v124_noGroup_ordersByInsertion() {
        val base = Item(id = 1, title = "a", tab = Tab.TASKS, createdAt = 300)
        val list = listOf(
            base.copy(id = 3, createdAt = 300),
            base.copy(id = 1, createdAt = 100),
            base.copy(id = 2, createdAt = 200)
        )
        val ordered = list.sortedWith(compareBy({ it.createdAt }, { it.id }))
        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.id })
        // identical timestamps fall back to id, so the order is never arbitrary
        val tied = listOf(base.copy(id = 9, createdAt = 500), base.copy(id = 4, createdAt = 500))
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
        assertEquals(listOf(4L, 9L), tied.map { it.id })
        assertEquals(0, emptyList<Item>().sortedWith(compareBy({ it.createdAt }, { it.id })).size)
    }

    @Test fun v124_repeatCount_uiValuesAreSanitised() {
        assertEquals(2, sanitizeRepeatCount(2))
        assertEquals(2, sanitizeRepeatCount(1))     // the UI's floor
        assertEquals(2, sanitizeRepeatCount(0))
        assertEquals(2, sanitizeRepeatCount(-5))
        assertEquals(9999, sanitizeRepeatCount(9999))   // no ceiling
        assertEquals(null, sanitizeRepeatCount(null))   // "never ends"
        // and the engine agrees about when it is spent
        assertEquals(false, repeatExhausted(sanitizeRepeatCount(2), 1))
        assertTrue(repeatExhausted(sanitizeRepeatCount(2), 2))
    }

    // ---------------- labels ----------------

    @Test fun labels_daySetAndPatternsRender() {
        assertEquals("Monthly · 11,17,29", repeatLabel(item("MONTHLY_DAY", at(2026, 7, 1), days = listOf(29, 11, 17))))
        assertEquals("First Mon + Last Sat", repeatLabel(item("MONTHLY_ORD", at(2026, 7, 1), ordList = listOf(11, 56))))
        assertEquals("Quarterly", repeatLabel(item("QUARTERLY", at(2026, 7, 1))))
    }
}
