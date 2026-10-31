package ru.overtime.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
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
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.MonthCalculator
import ru.overtime.domain.Settings
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Общий предок всех виджетов: по системному запросу перерисовывает сразу все виджеты приложения
 * (данные одни и те же, считаются один раз).
 */
abstract class BaseOvertimeWidget : AppWidgetProvider() {
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

/**
 * Виджет 3×2 «Месяц»: на руки за месяц, сверхурочные месяца, сегодня или идущая, кнопка старт/стоп.
 * Имя класса не менять: виджет уже стоит на рабочих столах.
 */
class OvertimeWidget : BaseOvertimeWidget()

/** Виджет 1×1 «Кнопка»: круглая кнопка старт/стоп и таймер или итог дня под ней. */
class ButtonWidget : BaseOvertimeWidget()

/** Виджет 2×1 «Полоса» (растягивается до 4×1): статус или таймер, на руки за месяц, кнопка. */
class BarWidget : BaseOvertimeWidget()

/** Виджет 4×2 «Подробно»: на руки, аванс и остаток, сверхурочные месяца, сегодня, кнопка. */
class DetailWidget : BaseOvertimeWidget()

/** Нажатие на кнопку виджета: запускает или останавливает ручную сверхурочную. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_TOGGLE && action != ACTION_REFRESH) return
        val pending = goAsync()
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            try {
                if (action == ACTION_TOGGLE) {
                    // Сам перерисует виджеты и плитку в шторке.
                    app.manualToggle()
                } else {
                    WidgetUpdater.render(app, app.db.sessionDao().all(), app.settingsRepo.current())
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "ru.overtime.app.widget.TOGGLE_MANUAL"

        /** Перерисовать виджеты: таймер идущей сверхурочной раз в минуту. */
        const val ACTION_REFRESH = "ru.overtime.app.widget.REFRESH"
    }
}

object WidgetUpdater {

    private const val REQUEST_OPEN = 0
    private const val REQUEST_TOGGLE = 1
    private const val REQUEST_REFRESH = 2
    private const val REFRESH_MS = 60_000L

    /** Пересчитать и перерисовать виджеты в фоне (данные берутся из базы). */
    fun request(context: Context) {
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            render(app, app.db.sessionDao().all(), app.settingsRepo.current())
        }
    }

    /** Вызывать не из главного потока: считает месяц и перерисовывает все виджеты. */
    fun render(context: Context, sessions: List<SessionEntity>, settings: Settings) {
        val manager = AppWidgetManager.getInstance(context)
        fun ids(cls: Class<*>): IntArray = manager.getAppWidgetIds(ComponentName(context, cls))
        val monthIds = ids(OvertimeWidget::class.java)
        val buttonIds = ids(ButtonWidget::class.java)
        val barIds = ids(BarWidget::class.java)
        val detailIds = ids(DetailWidget::class.java)
        if (monthIds.isEmpty() && buttonIds.isEmpty() && barIds.isEmpty() && detailIds.isEmpty()) return

        val state = WidgetState.build(sessions, settings)
        scheduleRefresh(context, state.runningStart != null)
        if (monthIds.isNotEmpty()) manager.updateAppWidget(monthIds, monthViews(context, state))
        if (buttonIds.isNotEmpty()) manager.updateAppWidget(buttonIds, buttonViews(context, state))
        if (barIds.isNotEmpty()) manager.updateAppWidget(barIds, barViews(context, state))
        if (detailIds.isNotEmpty()) manager.updateAppWidget(detailIds, detailViews(context, state))
    }

    /**
     * Пока идёт сверхурочная, таймер на виджетах обновляется раз в минуту.
     * Будильник не будит телефон (RTC без WAKEUP): при выключенном экране обновлений нет, и батарея не тратится.
     */
    private fun scheduleRefresh(context: Context, running: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH,
            Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (running) {
            alarms.set(AlarmManager.RTC, System.currentTimeMillis() + REFRESH_MS, pi)
        } else {
            alarms.cancel(pi)
        }
    }

    // ---- вёрстка отдельных виджетов ----

    private fun buttonViews(context: Context, s: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_button)
        views.setTextViewText(R.id.w_caption, s.timer ?: ("сегодня " + formatMinutes(s.todayMinutes)))
        bindCommon(context, views, s)
        return views
    }

    private fun barViews(context: Context, s: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_bar)
        views.setTextViewText(
            R.id.w_status,
            s.timer?.let { "Идёт · $it" } ?: ("Сегодня " + formatMinutes(s.todayMinutes)),
        )
        views.setTextViewText(R.id.w_net, "На руки " + formatRub(s.calc.summary.net))
        bindCommon(context, views, s)
        return views
    }

    private fun monthViews(context: Context, s: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_overtime)
        views.setTextViewText(R.id.w_net, formatRub(s.calc.summary.net))
        views.setTextViewText(R.id.w_month, monthOvertimeText(s))
        views.setTextViewText(R.id.w_status, statusText(s))
        bindCommon(context, views, s)
        return views
    }

    private fun detailViews(context: Context, s: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_detail)
        views.setTextViewText(R.id.w_net, formatRub(s.calc.summary.net))
        views.setTextViewText(R.id.w_advance, "Аванс " + formatRub(s.calc.advance.net))
        views.setTextViewText(R.id.w_rest, "Остаток " + formatRub(s.calc.advance.rest))
        views.setTextViewText(R.id.w_month, monthOvertimeText(s))
        views.setTextViewText(R.id.w_status, statusText(s))
        bindCommon(context, views, s)
        return views
    }

    /** «Сверхурочные 12 ч 30 мин · +8 400 ₽». */
    private fun monthOvertimeText(s: WidgetState): String {
        val report = s.calc.report
        val base = "Сверхурочные " + formatMinutes(report.totalMinutes)
        return if (report.totalNet > 0.5) base + " · +" + formatRub(report.totalNet) else base
    }

    /** «Идёт с 18:05 · 1:12» или «Сегодня 2 ч · +1 200 ₽». */
    private fun statusText(s: WidgetState): String {
        if (s.runningStart != null && s.timer != null) {
            return "Идёт с " + s.runningStart.format(TIME_FMT) + " · " + s.timer
        }
        val base = "Сегодня " + formatMinutes(s.todayMinutes)
        return if (s.todayNet > 0.5) base + " · +" + formatRub(s.todayNet) else base
    }

    /** Кнопка старт/стоп и нажатия: фон открывает приложение, кнопка переключает ручную сверхурочную. */
    private fun bindCommon(context: Context, views: RemoteViews, s: WidgetState) {
        if (s.manualRunning) {
            views.setImageViewResource(R.id.w_button, R.drawable.widget_button_stop)
            views.setContentDescription(R.id.w_button, context.getString(R.string.widget_stop))
        } else {
            views.setImageViewResource(R.id.w_button, R.drawable.widget_button_start)
            views.setContentDescription(R.id.w_button, context.getString(R.string.widget_start))
        }
        views.setOnClickPendingIntent(R.id.w_root, openApp(context))
        views.setOnClickPendingIntent(R.id.w_button, toggle(context))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun toggle(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_TOGGLE,
        Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_TOGGLE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Всё, что показывают виджеты, посчитанное один раз на перерисовку. */
internal class WidgetState(
    val calc: MonthCalc,
    val todayMinutes: Int,
    val todayNet: Double,
    /** Идёт ручная сверхурочная: кнопка показывает «стоп». */
    val manualRunning: Boolean,
    /** Начало идущей записи (ручной или автоматической), иначе null. */
    val runningStart: LocalDateTime?,
    /** Сколько идёт текущая запись, «1:12», иначе null. */
    val timer: String?,
) {
    companion object {
        fun build(sessions: List<SessionEntity>, settings: Settings): WidgetState {
            val now = LocalDateTime.now()
            val month = YearMonth.from(now)
            val spans = sessions.map { it.toSpan(now) }
            val calc = MonthCalculator.calc(month, spans, settings)
            val today = calc.report.days.firstOrNull { it.date == now.toLocalDate() }

            val openManual = sessions.firstOrNull { it.manual && it.endMillis == null }
            val running = openManual ?: sessions.filter { it.endMillis == null }.minByOrNull { it.startMillis }
            val timer = running?.let {
                val minutes = ((System.currentTimeMillis() - it.startMillis) / 60_000L).coerceAtLeast(0L)
                "${minutes / 60}:" + (minutes % 60).toString().padStart(2, '0')
            }
            return WidgetState(
                calc = calc,
                todayMinutes = today?.countedMinutes ?: 0,
                todayNet = today?.net ?: 0.0,
                manualRunning = openManual != null,
                runningStart = running?.let { TimeConv.toLocal(it.startMillis) },
                timer = timer,
            )
        }
    }
}
