package ru.patients.app.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.patients.app.MainActivity
import ru.patients.app.PatientsApp
import ru.patients.app.R
import ru.patients.core.Schedule
import ru.patients.core.Store
import ru.patients.core.Texts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Alarms {
    const val CHANNEL = "reminders"
    private const val ACTION_FIRE = "ru.patients.app.FIRE"
    const val ACTION_DONE = "ru.patients.app.DONE"
    const val EXTRA_PATIENT = "patient_id"

    /** Android разрешает приложению не больше 500 будильников, ставим ближайшие. */
    private const val WINDOW = 120

    private val FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Напоминания о пациентах", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Сроки анализов и приёмов"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun fireIntent(ctx: Context, key: String) =
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("patients://alarm/$key"))

    private fun pending(ctx: Context, key: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(ctx, 0, fireIntent(ctx, key), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun key(e: Schedule.Event) = "${e.reminderId}/${e.kind.name}"

    /** Снимает лишние будильники и ставит ближайшие по всей базе. */
    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    @Synchronized
    fun reschedule(ctx: Context, store: Store) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val settings = PatientsApp.settings(app)
        val events = Schedule.upcomingEvents(store, System.currentTimeMillis(), WINDOW)
        val wanted = events.associateBy { key(it) }

        for (old in settings.scheduledKeys - wanted.keys) {
            pending(app, old, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
        val exact = canExact(app)
        for ((k, e) in wanted) {
            val pi = pending(app, k, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.atMillis, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.atMillis, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.atMillis, pi)
            }
        }
        settings.scheduledKeys = wanted.keys
    }

    /** Проверочный будильник через минуту: проверяет и уведомления, и пробуждение телефона. */
    @SuppressLint("ScheduleExactAlarm")
    fun scheduleTest(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = pending(app, "test/TEST", PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = System.currentTimeMillis() + 60_000
        try {
            if (canExact(app)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    @SuppressLint("MissingPermission")
    fun showTest(ctx: Context) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF2E8B57.toInt())
            .setContentTitle("Иванов Иван Иванович")
            .setContentText("Проверка: уведомления приходят вовремя")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1, n)
        } catch (_: SecurityException) {
        }
    }

    fun format(ms: Long): String =
        FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun notificationId(reminderId: String) = reminderId.hashCode()

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, reminderId: String, kind: Schedule.Kind) {
        val store = PatientsApp.repo(ctx).state.value
        val r = store.visibleReminders().firstOrNull { it.id == reminderId } ?: return
        if (r.done) return
        val p = store.patient(r.patientId) ?: return
        val what = r.title.ifBlank { "Напоминание" }
        val text = when (kind) {
            Schedule.Kind.DUE -> "$what — срок ${format(r.atMillis)}"
            Schedule.Kind.ADVANCE -> "${Texts.advance(r.advanceMinutes)}: $what (${format(r.atMillis)})"
        }
        val fio = p.fio.ifBlank { "Пациент без ФИО" }

        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_PATIENT, p.id)
            .setData(Uri.parse("patients://open/${r.id}"))
        val openPi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val done = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DONE).setData(Uri.parse("patients://done/${r.id}"))
        val donePi = PendingIntent.getBroadcast(ctx, 0, done, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF2E8B57.toInt())
            .setContentTitle(fio)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(0, "Выполнено", donePi)
            .build()
        ensureChannel(ctx)
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId(r.id), n)
        } catch (_: SecurityException) {
            // уведомления запрещены пользователем
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments ?: return
        val repo = PatientsApp.repo(context)
        when (intent.action) {
            Alarms.ACTION_DONE -> {
                val id = parts.firstOrNull() ?: return
                repo.markDone(id)
                NotificationManagerCompat.from(context).cancel(Alarms.notificationId(id))
                PatientsApp.syncInBackground(context)
            }
            else -> {
                if (parts.size < 2) return
                if (parts[0] == "test") {
                    Alarms.showTest(context)
                    return
                }
                val kind = runCatching { Schedule.Kind.valueOf(parts[1]) }.getOrNull() ?: return
                Alarms.show(context, parts[0], kind)
                Alarms.reschedule(context, repo.state.value)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // после перезагрузки все будильники сброшены системой
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) PatientsApp.settings(context).scheduledKeys = emptySet()
        Alarms.reschedule(context, PatientsApp.repo(context).state.value)
    }
}
