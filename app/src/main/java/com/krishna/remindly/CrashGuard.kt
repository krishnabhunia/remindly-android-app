package com.krishna.remindly

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * v1.22 items 11 + 12.
 *
 * Krishna's rule: the app must never show Android's "Remindly has stopped" — it must show its
 * own window naming where the failure happened, and the trace must always reach Error Logs.
 *
 * The handler works in a deliberate order:
 *   1. write the trace to a private file (fast, permission-free, survives a dying process),
 *   2. silence any ringing alarm, so the phone is never left ringing with no UI to stop it,
 *   3. show the error — an Activity when allowed, a high-priority notification when the crash
 *      came from the background, where Android forbids launching Activities.
 * Then it delegates to the platform handler, so the process ends as Android expects.
 */
object CrashGuard {

    const val EXTRA_LOCATION = "loc"
    const val EXTRA_TYPE = "type"
    const val EXTRA_MESSAGE = "msg"
    const val EXTRA_SCREEN = "screen"
    const val NOTIF_ID = 47001

    /** Set by the UI so the error screen can say which tab was in view. */
    @Volatile var currentScreen: String = "—"

    @Volatile private var foregroundCount = 0
    val isForeground: Boolean get() = foregroundCount > 0

    /** Guards against a crash inside the crash handler turning into a loop. */
    @Volatile private var handling = false

    fun install(app: Application) {
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { foregroundCount++ }
                override fun onActivityStopped(activity: Activity) {
                    if (foregroundCount > 0) foregroundCount--
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })

            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, thr ->
                if (!handling) {
                    handling = true
                    runCatching { Logger.writePending(app, Logger.crashEntry(app, thr, thread.name)) }
                    runCatching { AlarmService.stop(app) }
                    runCatching { show(app, thr) }
                }
                previous?.uncaughtException(thread, thr)
            }
        }.onFailure { runCatching { Logger.e(app, "crashguard", it, "handler install failed") } }
    }

    private fun show(context: Context, thr: Throwable) {
        val location = Logger.crashLocation(thr)
        val type = thr.javaClass.simpleName
        val message = (thr.message ?: "No further detail.").take(400)
        if (isForeground) {
            val i = Intent(context, ErrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_LOCATION, location)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_SCREEN, currentScreen)
            runCatching { context.startActivity(i) }
                .onFailure { notifyInstead(context, location, type, message) }
        } else {
            notifyInstead(context, location, type, message)
        }
    }

    /** Background crashes cannot always open a window — Android restricts that. */
    private fun notifyInstead(context: Context, location: String, type: String, message: String) {
        runCatching {
            val i = Intent(context, ErrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_LOCATION, location)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_SCREEN, "background")
            val pi = android.app.PendingIntent.getActivity(
                context, 4701, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val n = androidx.core.app.NotificationCompat.Builder(context, Alerts.CH_URGENT)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Remindly hit an error")
                .setContentText(location)
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText("$type at $location — tap for details.")
                )
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            androidx.core.app.NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        }.onFailure { runCatching { Logger.e(context, "crashguard", it, "error notification failed") } }
    }

    fun restartIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
