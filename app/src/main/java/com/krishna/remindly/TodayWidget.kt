package com.krishna.remindly

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.Instant
import java.time.ZoneId

/**
 * v1.11 Today widget — a scrollable list of today's + overdue items across all tabs,
 * with a tap-to-complete checkbox per row (recurrence bounces correctly, same path
 * as the notification Done button). The counts widget stays alongside.
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val rv = RemoteViews(context.packageName, R.layout.widget_today)
            val svc = Intent(context, TodayWidgetService::class.java)
            rv.setRemoteAdapter(R.id.today_list, svc)
            rv.setEmptyView(R.id.today_list, R.id.today_empty)
            // template for row taps; fill-ins choose done-vs-open per row
            val tmpl = Intent(context, AlarmReceiver::class.java).setAction(ACTION_ROW)
            rv.setPendingIntentTemplate(
                R.id.today_list,
                PendingIntent.getBroadcast(
                    context, 43_000, tmpl,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )
            val open = PendingIntent.getActivity(
                context, 43_001, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.today_title, open)
            mgr.updateAppWidget(id, rv)
        }
    }

    companion object {
        const val ACTION_ROW = "com.krishna.remindly.TODAY_ROW"
        const val EXTRA_ROW_ID = "row_id"
        const val EXTRA_ROW_DONE = "row_done"

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.today_list)
        }
    }
}

class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TodayFactory(applicationContext)
}

private class TodayFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}

    override fun onDataSetChanged() {
        Stores.init(context)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val endOfToday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val itemRows = ItemStore.items.value
            .filter { it.deletedAt == null && !it.done && it.dueAt != null && it.dueAt < endOfToday }
            .map { Row(it.id, it.title, it.tab.title, it.dueAt!!, false) }
        // v1.15 item 23: calls join the widget — recurring due today + uncleared missed.
        val callRows = CallStore.calls.value
            .filter { it.deletedAt == null && !it.done }
            .mapNotNull { r ->
                when {
                    r.repeatMode != "OFF" && r.recurAt != null && r.recurAt < endOfToday ->
                        Row(-r.id, r.display, "Calls", r.recurAt, true)
                    r.lastMissedAt != null ->
                        Row(-r.id, r.display, "Calls · missed" + (if (r.missedCount > 1) " ×${r.missedCount}" else ""), r.lastMissedAt, true)
                    else -> null
                }
            }
        rows = (itemRows + callRows).sortedBy { it.at }
    }

    data class Row(val id: Long, val title: String, val tabLabel: String, val at: Long, val isCall: Boolean)

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_today_row)
        val rv = RemoteViews(context.packageName, R.layout.widget_today_row)
        rv.setTextViewText(R.id.row_title, row.title)
        // v1.20 item 4: items are all-day (overdue next day); calls keep their clock.
        val overdue = if (row.isCall) row.at <= System.currentTimeMillis()
            else isOverdueDay(row.at, System.currentTimeMillis())
        val t = Instant.ofEpochMilli(row.at).atZone(ZoneId.systemDefault())
        // v1.24 item 4: the widget follows the global Time Format too.
        val time = formatTime(row.at)
        rv.setTextViewText(
            R.id.row_sub,
            if (row.tabLabel.startsWith("Calls · missed")) row.tabLabel
            else row.tabLabel + " · " + (if (overdue) "overdue" else time)
        )
        rv.setOnClickFillInIntent(
            R.id.row_check,
            Intent().putExtra(TodayWidget.EXTRA_ROW_ID, row.id).putExtra(TodayWidget.EXTRA_ROW_DONE, true)
        )
        rv.setOnClickFillInIntent(
            R.id.row_title,
            Intent().putExtra(TodayWidget.EXTRA_ROW_ID, row.id).putExtra(TodayWidget.EXTRA_ROW_DONE, false)
        )
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
