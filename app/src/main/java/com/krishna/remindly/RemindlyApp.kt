package com.krishna.remindly

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager

class RemindlyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // v1.8.1: if the app ever crashes, keep the exact trace so the next launch can show it.
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    java.time.LocalDateTime.now().toString() + "\n" + android.util.Log.getStackTraceString(e)
                )
            } catch (_: Throwable) { }
            try { Logger.e(this, "CRASH", e) } catch (_: Throwable) { }
            prevHandler?.uncaughtException(t, e)
        }
        Stores.init(this)
        // v1.35: resume cloud sync if the user opted in and a session persists. Guarded — a
        // Firebase/Play-services hiccup must never block app startup.
        runCatching {
            SyncRepo.startIfEnabled(this)          // persisted Firebase session
            ShareStore.startIfSignedIn(this)       // v1.87 (N17): sharing rides auth, not the sync toggle
            SyncAuth.linkFromGoogle(this)          // refresh the Firebase link from Google, then start
        }
        // v1.8: per-tab Done auto-clear (Shop items with a live lapse-return or expiry survive).
        run {
            val s = SettingsStore.s.value
            val now = System.currentTimeMillis()
            fun cutoff(days: Int) = if (days <= 0) null else now - days * 24L * 60 * 60 * 1000
            val cuts = mapOf(
                Tab.TASKS to cutoff(clearFor(s, Tab.TASKS)),
                Tab.SHOP to cutoff(clearFor(s, Tab.SHOP)),
                Tab.LEARN to cutoff(clearFor(s, Tab.LEARN))
            )
            ItemStore.items.value
                .filter { it.done }
                .filter { i ->
                    val cut = cuts[i.tab] ?: return@filter false
                    val doneAt = i.doneAt ?: return@filter false
                    if (doneAt >= cut) return@filter false
                    val protectedShop = i.tab == Tab.SHOP &&
                        ((i.returnAt ?: 0L) > now || (i.expiryAt?.let { atHourOfDay(it, 9) > now } == true))
                    !protectedShop
                }
                .forEach { ItemStore.delete(it.id) }
            cutoff(clearFor(s, null))?.let { cut ->
                CallStore.calls.value
                    .filter { it.done && (it.doneAt ?: Long.MAX_VALUE) < cut }
                    .forEach { CallStore.delete(it.id) }
            }
        }
        RemindlyWidget.refresh(this)
        TodayWidget.refresh(this)
        // v1.9: daily data auto-backup (kept for the last 7 days) + tomorrow's alarm.
        Backup.autoBackupIfDue(this)
        AlarmScheduler.scheduleDailyBackup(this)
        // v1.11: bin purge (30 days), midnight widget refresh, optional morning digest.
        run {
            val cut = purgeCutoff(System.currentTimeMillis())
            ItemStore.items.value.filter { (it.deletedAt ?: Long.MAX_VALUE) < cut }
                .forEach { ItemStore.delete(it.id) }
            CallStore.calls.value.filter { (it.deletedAt ?: Long.MAX_VALUE) < cut }
                .forEach { CallStore.delete(it.id) }
        }
        // v1.86 (N20): the app-open leg of the roll trio (midnight / boot / open) — a phone
        // that slept through midnight catches up the moment Remindly opens.
        Alerts.rollAllMissed(this)
        // v1.12 catch-up: if midnight passed while the phone was off, resurrect now.
        resurrectDue(ItemStore.items.value, System.currentTimeMillis()).forEach { revived ->
            ItemStore.upsert(revived)
            AlarmScheduler.scheduleForItem(this, revived)
        }
        // v1.22 items 11+12: crash capture before anything else can fail.
        CrashGuard.install(this)
        Logger.mergePending(this)
        PHONE_IS_24H = android.text.format.DateFormat.is24HourFormat(this)
        runCatching { Degrades.refresh(this) }   // v1.71 (N2)
        TIME_FORMAT = SettingsStore.s.value.timeFormat

        // v1.15 item 11: yesterday's OOS marks clear at the new day.
        clearStaleOos(ItemStore.items.value, System.currentTimeMillis()).forEach { ItemStore.upsert(it) }
        // v1.20 item 8: tabs switched off (or the v15 migration) leave stale calendar
        // events behind — clear them once at startup.
        CalSync.pruneDisabled(this)
        resurrectCallsDue(CallStore.calls.value, System.currentTimeMillis()).forEach { rc ->
            CallStore.upsert(rc)
            AlarmScheduler.scheduleCallRecur(this, rc)
        }
        // v1.81 (Q18): v1.80 wired the snooze to a field whose default was 10, silently turning a
        // 90-minute snooze into 10. Changing the default alone fixes NOTHING — the value is already
        // persisted in settings.json, so Gson keeps reading 10. Rewrite it once. Safe because the
        // field never had a UI, so any stored 10 is the accidental default, never a user choice.
        runCatching {
            val cur = SettingsStore.s.value
            if (!cur.snoozeFixed90) {
                val old = cur.snoozeM1
                SettingsStore.update { it.copy(snoozeM1 = if (old == 10) 90 else old, snoozeFixed90 = true) }
                Logger.e(this, "MIGRATE", null, "snooze duration $old -> ${SettingsStore.s.value.snoozeM1} min (Q18)")
            }
        }.onFailure { Logger.e(this, "MIGRATE", it, "snooze migration failed — stored value untouched") }
        AlarmScheduler.scheduleMidnightRefresh(this)
        UiStore.init(this)
        // v1.6: seed the predefined group lists from whatever items already carry,
        // so nothing ever shows an "unlisted" group. One-time.
        val st = SettingsStore.s.value
        if (!st.groupsSeeded) {
            val items = ItemStore.items.value
            fun distinct(tab: Tab, pick: (Item) -> String?) =
                items.filter { it.tab == tab }.mapNotNull { pick(it)?.trim() }
                    .filter { it.isNotBlank() }.distinct().sorted()
            SettingsStore.update {
                it.copy(
                    tasksGroups = (it.tasksGroups + distinct(Tab.TASKS) { i -> i.group }).distinct(),
                    shopGroups = (it.shopGroups + distinct(Tab.SHOP) { i -> i.group }).distinct(),
                    learnTopics = (it.learnTopics + distinct(Tab.LEARN) { i -> i.topic }).distinct(),
                    groupsSeeded = true
                )
            }
            SettingsStore.persistNow()
        }
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Full-screen alarm channel — silent, because AlarmService plays the ringtone itself.
        val alarm = NotificationChannel(
            Alerts.CH_ALARM, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen ringing reminders"
            setSound(null, null)
            enableVibration(false)
        }

        val soundAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val remind = NotificationChannel(
            Alerts.CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Due, expiry and shopping reminders"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), soundAttrs)
            enableVibration(true)
        }

        val info = NotificationChannel(
            Alerts.CH_INFO, "Updates", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Lapse cycles and other app updates"
        }

        val urgent = NotificationChannel(
            Alerts.CH_URGENT, "Urgent reminders", android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Urgent-priority items — give this tier its own sound here" }
        val high = NotificationChannel(
            Alerts.CH_HIGH, "High-priority reminders", android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "High-priority items" }
        nm.createNotificationChannels(listOf(alarm, remind, info, urgent, high))
    }
}
