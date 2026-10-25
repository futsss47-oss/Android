package ru.overtime.app.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import ru.overtime.app.MainActivity
import ru.overtime.app.OvertimeApp
import ru.overtime.app.R
import ru.overtime.app.data.TimeConv
import ru.overtime.app.location.Perms
import ru.overtime.app.ui.MONTH_FMT
import ru.overtime.app.ui.formatMinutes
import ru.overtime.app.ui.formatRub
import ru.overtime.domain.Calculator
import ru.overtime.domain.ReminderTime
import ru.overtime.domain.Settings
import java.time.LocalDateTime
import java.time.YearMonth

/** Напоминание в 18:00 последнего дня месяца: итог и просьба сверить с расчётным листком. */
object MonthReminder {
    private const val REQUEST_CODE = 42
    private const val NOTIFICATION_ID = 2

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, MonthReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Запланировать ближайшее напоминание (или снять, если оно выключено). */
    fun schedule(context: Context, settings: Settings) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        alarms.cancel(pi)
        if (!settings.monthReminder) return
        val at = ReminderTime.next(LocalDateTime.now())
        // Неточный будильник: точность до нескольких минут, зато не нужно особое разрешение.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, TimeConv.toMillis(at), pi)
    }

    fun show(app: OvertimeApp) {
        val settings = app.settingsRepo.current()
        if (!settings.monthReminder || !Perms.hasNotifications(app)) return
        val now = LocalDateTime.now()
        val month = YearMonth.from(now)
        val spans = app.db.sessionDao().all().map { it.toSpan(now) }
        val report = Calculator.report(month.atDay(1), month.atEndOfMonth(), spans, settings)
        val summary = Calculator.monthSummary(month, report, settings)

        val text = "Сверхурочные и выходные за ${month.format(MONTH_FMT)}: ${formatMinutes(report.totalMinutes)}, " +
            "ожидается на руки ≈ ${formatRub(summary.net)}. Когда придёт расчётный листок, впишите фактическую " +
            "премию на вкладке «Месяц» и сверьте норму часов."
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, OvertimeApp.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle("Конец месяца: сверьте расчёт")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}

class MonthReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            try {
                MonthReminder.show(app)
            } finally {
                MonthReminder.schedule(app, app.settingsRepo.current())
                pending.finish()
            }
        }
    }
}
