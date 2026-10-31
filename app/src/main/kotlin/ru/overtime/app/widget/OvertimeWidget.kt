package ru.overtime.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.launch
import ru.overtime.app.MainActivity
import ru.overtime.app.OvertimeApp
import ru.overtime.app.R
import ru.overtime.app.data.SessionEntity
import ru.overtime.app.data.TimeConv
import ru.overtime.app.ui.TIME_FMT
import ru.overtime.app.ui.formatMinutes
import ru.overtime.app.ui.formatRub
import ru.overtime.domain.Calculator
import ru.overtime.domain.Settings
import java.time.LocalDateTime
import java.time.YearMonth

/** Виджет: сверхурочная сегодня (или идущая сейчас), итог месяца и ожидаемая сумма на руки. */
class OvertimeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            try {
                WidgetUpdater.render(app, app.db.sessionDao().all(), app.settingsRepo.current())
            } finally {
                pending.finish()
            }
        }
    }
}

object WidgetUpdater {

    /** Пересчитать и перерисовать виджет в фоне (данные берутся из базы). */
    fun request(context: Context) {
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            render(app, app.db.sessionDao().all(), app.settingsRepo.current())
        }
    }

    /** Вызывать не из главного потока: считает отчёт за месяц. */
    fun render(context: Context, sessions: List<SessionEntity>, settings: Settings) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, OvertimeWidget::class.java))
        if (ids.isEmpty()) return

        val now = LocalDateTime.now()
        val month = YearMonth.from(now)
        val spans = sessions.map { it.toSpan(now) }
        val report = Calculator.report(month.atDay(1), month.atEndOfMonth(), spans, settings)
        val summary = Calculator.monthSummary(month, report, settings)
        val today = report.days.firstOrNull { it.date == now.toLocalDate() }
        val open = sessions.filter { it.endMillis == null }.minByOrNull { it.startMillis }

        val views = RemoteViews(context.packageName, R.layout.widget_overtime)
        if (open != null) {
            val start = TimeConv.toLocal(open.startMillis)
            views.setTextViewText(R.id.w_status, "Идёт с ${start.format(TIME_FMT)}")
        } else {
            views.setTextViewText(R.id.w_status, "Сегодня: " + formatMinutes(today?.countedMinutes ?: 0))
        }
        views.setTextViewText(R.id.w_today_money, today?.let { "≈ +" + formatRub(it.net) + " на руки" } ?: "")
        views.setTextViewText(
            R.id.w_month,
            "Месяц: ${formatMinutes(report.totalMinutes)} · +${formatRub(report.totalNet)}",
        )
        views.setTextViewText(R.id.w_net, "На руки ≈ " + formatRub(summary.net))

        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.w_root, launch)
        manager.updateAppWidget(ids, views)
    }
}
