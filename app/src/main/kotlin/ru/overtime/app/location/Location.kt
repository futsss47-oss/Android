package ru.overtime.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.overtime.app.MainActivity
import ru.overtime.app.OvertimeApp
import ru.overtime.app.R
import ru.overtime.app.reminder.MonthReminder
import ru.overtime.app.data.SettingsRepository
import ru.overtime.domain.Settings
import ru.overtime.domain.TrackerState
import java.time.format.DateTimeFormatter

object Perms {
    fun hasFine(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasNotifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

object GeofenceHelper {
    private const val GEOFENCE_ID = "work"

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, GeofenceReceiver::class.java)
        // Для геозон PendingIntent должен быть изменяемым: система дописывает в него данные события.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0, intent, flags)
    }

    /** Регистрирует (или перерегистрирует) геозону работы. Без прав или без места работы ничего не делает. */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context, s: Settings) {
        val lat = s.workLat
        val lon = s.workLon
        if (lat == null || lon == null || !Perms.hasFine(ctx) || !Perms.hasBackground(ctx)) return
        val client = LocationServices.getGeofencingClient(ctx)
        val pi = pendingIntent(ctx)
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(lat, lon, s.enterRadiusM.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        // Если вы уже на работе в момент регистрации, система сразу пришлёт событие входа.
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        client.removeGeofences(pi).addOnCompleteListener {
            try {
                client.addGeofences(request, pi)
            } catch (_: SecurityException) {
                // права отозвали между проверкой и вызовом
            }
        }
    }
}

/** Принимает события входа и выхода из геозоны и запускает фоновый опрос. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val entered = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val exited = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        if (entered || exited) TrackingService.start(context, biasInside = entered)
    }
}

/** После перезагрузки телефона или обновления приложения геозоны надо зарегистрировать заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsRepository(context).current()
        GeofenceHelper.register(context, settings)
        MonthReminder.schedule(context, settings)
    }
}

/**
 * Фоновый сервис опроса. Работает, пока человек на работе: раз в N минут (по умолчанию 10)
 * берёт геопозицию и передаёт движку. Когда подтверждено, что человека на работе нет, останавливается.
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var updatesStarted = false
    private var biasInsideForNextFix = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handle(it, bias = false) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as OvertimeApp
        val s = app.settingsRepo.current()
        showForeground(app.trackerState())
        if (!s.hasWorkPlace || !Perms.hasFine(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        biasInsideForNextFix = intent?.getBooleanExtra(EXTRA_BIAS_INSIDE, false) ?: false
        startUpdates(s)
        requestNow()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(s: Settings) {
        if (updatesStarted) return
        val interval = s.pollIntervalMinutes.coerceAtLeast(1) * 60_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            updatesStarted = true
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNow() {
        try {
            val token = CancellationTokenSource().token
            val bias = biasInsideForNextFix
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token)
                .addOnSuccessListener { location -> if (location != null) handle(location, bias) }
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun handle(location: android.location.Location, bias: Boolean) {
        scope.launch {
            val app = application as OvertimeApp
            // Слишком старые замеры не используем.
            if (System.currentTimeMillis() - location.time > STALE_MS) return@launch
            val state = app.processLocation(
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = location.accuracy.toDouble(),
                biasInside = bias,
            )
            if (!state.atWork) {
                stopSelf()
            } else {
                showForeground(state)
            }
        }
    }

    private fun showForeground(state: TrackerState) {
        val text = when {
            state.openStart != null -> "Сверхурочная идёт с ${state.openStart!!.format(TIME)}"
            state.atWork -> "Вы на работе, слежу за геопозицией"
            else -> "Проверяю геопозицию"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, OvertimeApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle("Учёт сверхурочных")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_BIAS_INSIDE = "bias_inside"
        private const val STALE_MS = 10 * 60_000L
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun start(context: Context, biasInside: Boolean = false) {
            val intent = Intent(context, TrackingService::class.java)
                .putExtra(EXTRA_BIAS_INSIDE, biasInside)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
