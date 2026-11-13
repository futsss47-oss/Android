package ru.overtime.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import ru.overtime.app.MainActivity
import ru.overtime.app.OvertimeApp
import ru.overtime.app.R
import ru.overtime.app.data.SettingsRepository
import ru.overtime.app.data.TimeConv
import ru.overtime.app.reminder.MonthReminder
import ru.overtime.domain.Settings
import ru.overtime.domain.TrackerState
import ru.overtime.domain.Windows
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Как приложение следит за уходом с работы, экономя батарею.
 *
 * Днём в смену геопозиция не опрашивается: приход и уход ловит система (геозона), приложение спит.
 * Регулярный опрос идёт только в окне сверхурочной (после смены, в выходной, в режиме «до и после» — утром),
 * пока человек на работе. В это время работает служба с уведомлением, и она слушает три дешёвых сигнала:
 *  - пропал рабочий Wi‑Fi;
 *  - человек пошёл или поехал (распознавание движения);
 *  - система сообщила о выходе из геозоны.
 * Сигнал не закрывает сверхурочную сам: он запоминается как возможное время ухода и запускает
 * точные замеры раз в 1–1,5 минуты, но не дольше 10 минут. Решают координаты и радиус зоны:
 * если точный замер показывает «вне зоны», концом становится момент первого сигнала.
 * Без сигналов — редкий контрольный замер раз в pollIntervalMinutes (по умолчанию 15 минут).
 */

object Perms {
    fun hasFine(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasNotifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Распознавание движения (Android 10+ спрашивает отдельно). Без него просто нет этого сигнала. */
    fun hasActivity(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
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
            // Система сообщит о выходе не позже чем через 2 минуты: точнее время ухода, батарея почти не страдает.
            .setNotificationResponsiveness(RESPONSIVENESS_MS)
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

    private const val RESPONSIVENESS_MS = 2 * 60_000
}

/**
 * Решает, нужна ли сейчас слежка: только если человек на работе и идёт окно сверхурочной.
 * Вызывается после любого события: геозона, будильник, замер, загрузка телефона, открытие приложения.
 */
object Tracker {
    private const val ALARM_REQUEST = 43
    private const val ACTIVITY_REQUEST = 44
    private const val UPDATES_REQUEST = 45
    const val FALLBACK_NOTIFICATION_ID = 3

    /** Только не из главного потока. */
    fun reconcile(ctx: Context) {
        val app = ctx.applicationContext as OvertimeApp
        val s = app.settingsRepo.current()
        val state = app.trackerState()
        val now = LocalDateTime.now()
        val inWindow = Windows.containing(now, s) != null
        if (state.atWork && inWindow && s.hasWorkPlace) {
            arm(app)
        } else {
            disarm(app)
        }
        if (state.atWork && !inWindow) scheduleWindowAlarm(app, s, now) else cancelWindowAlarm(app)
    }

    private fun arm(app: OvertimeApp) {
        registerActivity(app)
        val started = try {
            ContextCompat.startForegroundService(app, Intent(app, TrackingService::class.java))
            true
        } catch (_: Exception) {
            // Android 12+ может запретить запуск службы из фона (например, по неточному будильнику).
            // Тогда работаем без службы: редкие замеры приходят в ресивер, движение поднимет службу само.
            false
        }
        if (started) {
            removeFallbackUpdates(app)
        } else {
            requestFallbackUpdates(app)
            showFallbackNotification(app)
        }
    }

    private fun disarm(app: OvertimeApp) {
        app.stopService(Intent(app, TrackingService::class.java))
        unregisterActivity(app)
        removeFallbackUpdates(app)
        app.getSystemService(NotificationManager::class.java)?.cancel(FALLBACK_NOTIFICATION_ID)
    }

    // ---- будильник к началу окна (конец смены) ----

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        ALARM_REQUEST,
        Intent(ctx, WindowAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleWindowAlarm(ctx: Context, s: Settings, now: LocalDateTime) {
        val at = Windows.nextStart(now, s) ?: return
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        // Неточный будильник: может опоздать на несколько минут, но начало сверхурочной всё равно
        // ставится по концу смены, потому что приход на работу уже известен.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, TimeConv.toMillis(at), alarmIntent(ctx))
    }

    private fun cancelWindowAlarm(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx))
    }

    // ---- распознавание движения ----

    private fun activityIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, ACTIVITY_REQUEST, Intent(ctx, ActivityReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    private fun registerActivity(ctx: Context) {
        if (!Perms.hasActivity(ctx)) return
        val moving = listOf(
            DetectedActivity.WALKING,
            DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        )
        val transitions = moving.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        } + ActivityTransition.Builder()
            .setActivityType(DetectedActivity.STILL)
            .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
            .build()
        try {
            ActivityRecognition.getClient(ctx)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), activityIntent(ctx))
        } catch (_: SecurityException) {
            // разрешение отозвали
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterActivity(ctx: Context) {
        if (!Perms.hasActivity(ctx)) return
        try {
            ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(activityIntent(ctx))
        } catch (_: SecurityException) {
            // нечего снимать
        }
    }

    // ---- запасной режим без службы ----

    private fun updatesIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, UPDATES_REQUEST, Intent(ctx, LocationUpdatesReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    private fun requestFallbackUpdates(app: OvertimeApp) {
        if (!Perms.hasFine(app)) return
        val interval = app.settingsRepo.current().pollIntervalMinutes.coerceAtLeast(1) * 60_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(app).requestLocationUpdates(request, updatesIntent(app))
        } catch (_: SecurityException) {
            // разрешение отозвали
        }
    }

    private fun removeFallbackUpdates(app: OvertimeApp) {
        LocationServices.getFusedLocationProviderClient(app).removeLocationUpdates(updatesIntent(app))
    }

    private fun showFallbackNotification(app: OvertimeApp) {
        if (!Perms.hasNotifications(app)) return
        val state = app.trackerState()
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(FALLBACK_NOTIFICATION_ID, TrackingService.buildNotification(app, state))
    }

    /** Один замер для событий вне службы (будильник, геозона без координат). Только не из главного потока. */
    @SuppressLint("MissingPermission")
    suspend fun oneFix(ctx: Context, priority: Int): android.location.Location? {
        if (!Perms.hasFine(ctx)) return null
        return try {
            withTimeoutOrNull(20_000) {
                LocationServices.getFusedLocationProviderClient(ctx)
                    .getCurrentLocation(priority, CancellationTokenSource().token)
                    .await()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Передаёт замер движку. Свежесть проверяется здесь. */
    fun handleFix(ctx: Context, location: android.location.Location, bias: Boolean, confirmNow: Boolean): TrackerState? {
        if (System.currentTimeMillis() - location.time > STALE_MS) return null
        val app = ctx.applicationContext as OvertimeApp
        return app.processLocation(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = location.accuracy.toDouble(),
            biasInside = bias,
            confirmNow = confirmNow,
        )
    }

    /** Точный замер (≤ 50 м) после сигнала об уходе можно засчитывать сразу, без второго подтверждения. */
    fun isPrecise(location: android.location.Location): Boolean = location.hasAccuracy() && location.accuracy <= 50f

    private const val STALE_MS = 10 * 60_000L
}

/** Принимает события входа и выхода из геозоны. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val entered = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val exited = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        if (!entered && !exited) return
        val pending = goAsync()
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            try {
                if (exited) app.exitHint(LocalDateTime.now())
                val location = event.triggeringLocation
                    ?: Tracker.oneFix(app, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                if (location != null) {
                    // Событие выхода система уже проверила сама: второго замера не ждём.
                    Tracker.handleFix(app, location, bias = entered, confirmNow = exited)
                }
                Tracker.reconcile(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Будильник к началу окна сверхурочной: один замер, и если человек на работе — включается слежка. */
class WindowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            try {
                app.tick()
                Tracker.oneFix(app, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    ?.let { Tracker.handleFix(app, it, bias = false, confirmNow = false) }
                Tracker.reconcile(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Человек пошёл, поехал или остановился. Поднимает службу для серии точных замеров. */
class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val app = context.applicationContext as OvertimeApp
        var moveAtMillis: Long? = null
        var vehicle = false
        var still = false
        for (e in result.transitionEvents) {
            if (e.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue
            // Время события — по часам с момента загрузки; переводим в обычное время.
            val at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.elapsedRealTimeNanos) / 1_000_000
            if (e.activityType == DetectedActivity.STILL) {
                still = true
            } else {
                moveAtMillis = minOf(moveAtMillis ?: at, at)
                if (e.activityType == DetectedActivity.IN_VEHICLE) vehicle = true
            }
        }
        val pending = goAsync()
        app.appScope.launch {
            try {
                val move = moveAtMillis
                if (move != null) {
                    app.exitHint(TimeConv.toLocal(move))
                    // Событие распознавания движения позволяет запустить службу даже из фона.
                    TrackingService.startBurst(app, vehicle)
                } else if (still) {
                    TrackingService.stopBurst(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Редкие замеры в запасном режиме (служба не запустилась). */
class LocationUpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = LocationResult.extractResult(intent) ?: return
        val pending = goAsync()
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            try {
                result.lastLocation?.let { Tracker.handleFix(app, it, bias = false, confirmNow = false) }
                Tracker.reconcile(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** После перезагрузки телефона или обновления приложения геозоны надо зарегистрировать заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsRepository(context).current()
        GeofenceHelper.register(context, settings)
        MonthReminder.schedule(context, settings)
        val pending = goAsync()
        val app = context.applicationContext as OvertimeApp
        app.appScope.launch {
            try {
                Tracker.reconcile(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Служба на время сверхурочной. Редкий контрольный замер, бесплатные «пассивные» координаты от других
 * приложений, слежение за рабочим Wi‑Fi и серии точных замеров после сигнала об уходе.
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var updatesStarted = false
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /** Какие сети сейчас подключены и рабочие ли они (по BSSID). */
    private val workNetworks = mutableSetOf<Network>()

    private var burstUntil = 0L
    private var burstVehicle = false
    private var cooldownUntil = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handle(it, confirmNow = false) }
        }
    }

    private val passiveCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handle(it, confirmNow = false) }
        }
    }

    private val burstStep = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now >= burstUntil) {
                endBurst(stillInside = true)
                return
            }
            preciseFix()
            handler.postDelayed(this, if (burstVehicle) BURST_VEHICLE_MS else BURST_WALK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as OvertimeApp
        val s = app.settingsRepo.current()
        try {
            startForegroundCompat(buildNotification(this, app.trackerState()))
        } catch (_: Exception) {
            // система не дала стать службой переднего плана (запуск из фона) — остаются редкие замеры
            stopSelf()
            return START_NOT_STICKY
        }
        if (!s.hasWorkPlace || !Perms.hasFine(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startUpdates(s)
        startWifiWatch(s)
        when (intent?.action) {
            ACTION_BURST -> startBurstInternal(intent.getBooleanExtra(EXTRA_VEHICLE, false))
            ACTION_STILL -> if (burstUntil > 0) endBurst(stillInside = true)
            else -> Unit
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(s: Settings) {
        if (updatesStarted) return
        val interval = s.pollIntervalMinutes.coerceAtLeast(1) * 60_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .build()
        // Пассивные координаты: то, что и так получили навигатор или карты, приходит бесплатно.
        val passive = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 60_000L).build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            fused.requestLocationUpdates(passive, passiveCallback, Looper.getMainLooper())
            updatesStarted = true
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    // ---- рабочий Wi‑Fi ----

    private fun startWifiWatch(s: Settings) {
        if (wifiCallback != null || s.workWifiBssids.isEmpty()) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val work = s.workWifiBssids.map { it.lowercase() }.toSet()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val bssid = (caps.transportInfo as? WifiInfo)?.bssid?.lowercase()
                    onWifiSeen(network, bssid != null && bssid in work)
                }

                override fun onLost(network: Network) = onWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onWifiSeen(network, legacyBssid()?.lowercase()?.let { it in work } == true)
                }

                override fun onLost(network: Network) = onWifiLost(network)
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            wifiCallback = cb
        } catch (_: Exception) {
            // нет разрешения на состояние сети
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyBssid(): String? =
        applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.bssid

    private fun onWifiSeen(network: Network, isWork: Boolean) {
        synchronized(workNetworks) { if (isWork) workNetworks += network else workNetworks -= network }
    }

    private fun onWifiLost(network: Network) {
        val wasWork = synchronized(workNetworks) { workNetworks.remove(network) }
        if (!wasWork) return
        // Пропал рабочий Wi‑Fi: запоминаем время и проверяем координаты. Сеть сама ничего не решает.
        val app = application as OvertimeApp
        scope.launch { app.exitHint(LocalDateTime.now()) }
        handler.post { startBurstInternal(vehicle = false) }
    }

    // ---- серия точных замеров после сигнала ----

    private fun startBurstInternal(vehicle: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (burstUntil > now) {
            burstVehicle = burstVehicle || vehicle
            return
        }
        // Если недавняя серия показала «вы на работе», не тратим батарею повторно (ходьба по цеху).
        if (now < cooldownUntil) {
            preciseFix()
            return
        }
        burstVehicle = vehicle
        burstUntil = now + BURST_MAX_MS
        handler.removeCallbacks(burstStep)
        handler.post(burstStep)
    }

    private fun endBurst(stillInside: Boolean) {
        handler.removeCallbacks(burstStep)
        if (stillInside && burstUntil > 0) cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
        burstUntil = 0
    }

    @SuppressLint("MissingPermission")
    private fun preciseFix() {
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location ->
                    if (location != null) handle(location, confirmNow = Tracker.isPrecise(location))
                }
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun handle(location: android.location.Location, confirmNow: Boolean) {
        scope.launch {
            val app = application as OvertimeApp
            val state = Tracker.handleFix(app, location, bias = false, confirmNow = confirmNow) ?: return@launch
            val inWindow = Windows.containing(LocalDateTime.now(), app.settingsRepo.current()) != null
            if (!state.atWork || !inWindow) {
                // Ушёл с работы или окно сверхурочной закончилось: слежка больше не нужна.
                handler.post { endBurst(stillInside = false) }
                Tracker.reconcile(app)
            } else {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(this@TrackingService, state))
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(burstStep)
        fused.removeLocationUpdates(callback)
        fused.removeLocationUpdates(passiveCallback)
        wifiCallback?.let { cb ->
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
                // уже снят
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_BURST = "ru.overtime.app.location.BURST"
        private const val ACTION_STILL = "ru.overtime.app.location.STILL"
        private const val EXTRA_VEHICLE = "vehicle"

        /** Серия точных замеров: не дольше 10 минут, пешком раз в 90 с, на машине раз в 60 с. */
        private const val BURST_MAX_MS = 10 * 60_000L
        private const val BURST_WALK_MS = 90_000L
        private const val BURST_VEHICLE_MS = 60_000L

        /** После серии, показавшей «на работе», новая серия не раньше чем через 15 минут. */
        private const val COOLDOWN_MS = 15 * 60_000L

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun startBurst(context: Context, vehicle: Boolean) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_BURST)
                .putExtra(EXTRA_VEHICLE, vehicle)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // запуск из фона запрещён — остаются редкие замеры
            }
        }

        fun stopBurst(context: Context) {
            try {
                context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STILL))
            } catch (_: Exception) {
                // служба не запущена — останавливать нечего
            }
        }

        fun buildNotification(context: Context, state: TrackerState): Notification {
            val text = when {
                state.openStart != null -> "Сверхурочная идёт с ${state.openStart!!.format(TIME)}"
                state.atWork -> "Вы на работе, сверхурочная начнётся сама"
                else -> "Проверяю геопозицию"
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, OvertimeApp.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_clock)
                .setContentTitle("Учёт сверхурочных")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
