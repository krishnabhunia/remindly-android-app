package com.krishna.remindly

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class AlarmService : Service() {

    companion object {
        const val MODE_ITEM = "ITEM"
        const val MODE_CALL = "CALL"
        const val MODE_TEST = "TEST"
        // v2.05 (N37): a shop geofence arrival — itemId carries the SHOP id.
        const val MODE_SHOP = "SHOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_PLACE = "place"
        const val EXTRA_RING_ONLY = "ringOnly"   // v1.57: sound without the card
        private const val NOTIF_ID = 42
        private const val AUTO_STOP_MS = 3 * 60_000L

        fun start(context: Context, mode: String, itemId: Long = 0L, kind: String = "", place: String = "", ringOnly: Boolean = false) {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_ITEM_ID, itemId)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_PLACE, place)
                .putExtra(EXTRA_RING_ONLY, ringOnly)
            // v1.22 item 9: Android 12+ refuses a background foreground-service start.
            // Degrade to a high-priority notification instead of dying.
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Logger.e(context, "alarmsvc", it, "foreground start refused — notifying instead")
                    Alerts.fallbackAlarmNotification(context, itemId, kind)
                }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }
    // v1.56 1.4: stop only the SOUND after the configured duration — the card/notification stay.
    private val soundStop = Runnable {
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { vibrator?.cancel() }
        Logger.e(this, "ALERT", null, "sound auto-stopped by duration setting (card/notification stays)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ITEM
        val itemId = intent?.getLongExtra(EXTRA_ITEM_ID, 0L) ?: 0L
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: ""
        val place = intent?.getStringExtra(EXTRA_PLACE) ?: ""
        val ringOnly = intent?.getBooleanExtra(EXTRA_RING_ONLY, false) ?: false

        val fullScreen = Intent(this, AlarmActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .putExtra(EXTRA_ITEM_ID, itemId)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_PLACE, place)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fsPi = PendingIntent.getActivity(
            this, 1, fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, AlarmReceiver::class.java)
            .setAction(AlarmScheduler.ACTION_STOP_RING)
        val stopPi = PendingIntent.getBroadcast(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title: String
        val text: String
        if (mode == MODE_TEST) {
            title = "Test alarm"
            text = "Everything works — sound, screen and vibration are all firing."
        } else if (mode == MODE_CALL) {
            val r = CallStore.calls.value.firstOrNull { it.id == itemId }
            title = "Call back"
            text = r?.display ?: "Missed call"
        } else if (mode == MODE_SHOP) {
            // v2.05 (N37): itemId is the shop id; the body lists that shop's pending buy items.
            val shop = runCatching { ShopStore.get(itemId) }.getOrNull()
            val pending = runCatching { buyNowItems(ItemStore.items.value, shop) }.getOrDefault(emptyList())
            title = "Arrived at " + (shop?.name ?: "your shop")
            text = if (pending.isEmpty()) "Your list here is clear"
            else "${pending.size} item${if (pending.size > 1) "s" else ""}: " +
                pending.filter { !it.personal }.take(3).joinToString(", ") { it.title } +
                (if (pending.count { it.personal } > 0) " · 🔒 personal" else "")
        } else {
            val item = ItemStore.get(itemId)
            title = Alerts.kindTitle(kind) + (item?.let { " · ${it.tab.title}" } ?: "")
            text = item?.title ?: "Reminder"
        }

        val notif = NotificationCompat.Builder(this, Alerts.CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            // v1.57 RING = continuous sound + this notification, NO card takeover.
            .apply { if (!ringOnly) setFullScreenIntent(fsPi, true) }
            .setContentIntent(fsPi)
            .apply {
                // v1.65: RING carries the real action set (3 = Android's hard cap on actions);
                // Dismiss stops the ring. Card modes keep a single Dismiss — the card has buttons.
                when {
                    ringOnly && mode == MODE_ITEM && itemId > 0L -> {
                        addAction(0, "Dismiss", stopPi)
                        addAction(0, "Done", ringDonePi(itemId))
                        run {
                            // v1.82 (Q19): the label AND the number were typed by hand here, so a
                            // changed setting left this one button disagreeing with every other path.
                            val m = snoozeMinutes(SettingsStore.s.value)
                            addAction(0, "Snooze " + Alerts.snoozeLabel(m),
                                ringPi(7_250_000, itemId, AlarmScheduler.ACTION_SNOOZE, m))
                        }
                    }
                    ringOnly && mode == MODE_CALL && itemId > 0L -> {
                        addAction(0, "Dismiss", stopPi)
                        addAction(0, "Done", ringCallDonePi(itemId))
                    }
                    else -> addAction(0, "Dismiss", stopPi)
                }
            }
            .build()

        // v1.22 item 9: API 34+ can refuse the foreground type — degrade, never die mid-ring.
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure { Logger.e(this, "alarmsvc", it, "startForeground refused") }.isSuccess
        if (!promoted) {
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, notif)
            }.onFailure { Logger.e(this, "alarmsvc", it, "fallback ring notification failed") }
        }

        startRinging(ringOnly)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, AUTO_STOP_MS)
        handler.removeCallbacks(soundStop)
        val ringSecs = coerceRingSeconds(SettingsStore.s.value.let { if (ringOnly) it.ringRingSeconds else it.alarmRingSeconds })   // v1.68
        ringSecs.takeIf { it > 0 }?.let {
            handler.postDelayed(soundStop, it * 1000L)   // 1.4: per-type duration; 0 = until dismissed
        }
        return START_NOT_STICKY
    }

    private fun ringPi(reqBase: Int, id: Long, action: String, minutes: Int? = null): PendingIntent =
        PendingIntent.getBroadcast(
            this, (reqBase + id).toInt(),
            Intent(this, AlarmReceiver::class.java).setAction(action)
                .putExtra(AlarmScheduler.EXTRA_ITEM_ID, id)
                .apply { if (minutes != null) putExtra("minutes", minutes) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ringDonePi(id: Long) = ringPi(7_100_000, id, AlarmScheduler.ACTION_MARK_DONE)
    private fun ringCallDonePi(id: Long) = ringPi(7_300_000, id, AlarmScheduler.ACTION_CALL_DONE)

    private fun startRinging(ringOnly: Boolean = false) {
        if (player != null) return
        // RING uses the phone ringtone; the alarm card keeps the alarm sound.
        val uri = if (ringOnly)
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        else
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }
        vibrator = getSystemService(Vibrator::class.java)
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 0)
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(soundStop)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        super.onDestroy()
    }
}
