package com.krishna.remindly

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** v1.8: compact launcher widget — today's load at a glance + one-tap add. */
class RemindlyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        Stores.init(context)
        val now = System.currentTimeMillis()
        val endOfDay = startOfDayMs(now) + 24L * 60 * 60 * 1000
        val items = ItemStore.items.value.filter { !it.done && it.deletedAt == null }
        val overdue = items.count { isOverdueDay(it.dueAt, now) }
        val today = items.count { val d = it.dueAt; d != null && d in now until endOfDay }
        ids.forEach { id ->
            val rv = RemoteViews(context.packageName, R.layout.widget_remindly)
            rv.setTextViewText(R.id.w_counts, "$today due today · $overdue overdue")
            val open = Intent(context, MainActivity::class.java)
            rv.setOnClickPendingIntent(
                R.id.w_open,
                PendingIntent.getActivity(context, 61, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            val add = Intent(context, MainActivity::class.java).putExtra("quickadd", true)
            rv.setOnClickPendingIntent(
                R.id.w_add,
                PendingIntent.getActivity(context, 62, add, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            mgr.updateAppWidget(id, rv)
        }
    }

    companion object {
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, RemindlyWidget::class.java))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, RemindlyWidget::class.java)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                )
            }
        }
    }
}
