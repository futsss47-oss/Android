package ru.overtime.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import ru.overtime.app.data.AppDatabase
import ru.overtime.app.data.PrefsStateStore
import ru.overtime.app.data.RoomSessionGateway
import ru.overtime.app.data.SettingsRepository
import ru.overtime.domain.Geo
import ru.overtime.domain.OvertimeEngine
import ru.overtime.domain.Reading
import ru.overtime.domain.TrackerState
import java.time.LocalDateTime

class OvertimeApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var settingsRepo: SettingsRepository
        private set
    private lateinit var engine: OvertimeEngine

    /** Движок вызывается из сервиса, ресивера и интерфейса, поэтому все обращения идут под одним замком. */
    private val lock = Any()

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.build(this)
        settingsRepo = SettingsRepository(this)
        engine = OvertimeEngine(
            settings = { settingsRepo.current() },
            gateway = RoomSessionGateway(db.sessionDao()),
            store = PrefsStateStore(this),
        )
        createChannel()
    }

    fun trackerState(): TrackerState = synchronized(lock) { engine.state() }

    /** Только не из главного потока: пишет в базу. */
    fun tick() {
        synchronized(lock) { engine.onTick(LocalDateTime.now()) }
    }

    /**
     * Обрабатывает замер геопозиции. [biasInside] = true сразу после события входа в геозону:
     * в «серой зоне» между радиусами считаем, что человек на работе.
     */
    fun processLocation(lat: Double, lon: Double, accuracyM: Double, biasInside: Boolean): TrackerState {
        synchronized(lock) {
            val s = settingsRepo.current()
            val workLat = s.workLat
            val workLon = s.workLon
            if (workLat == null || workLon == null) return engine.state()
            val distance = Geo.distanceMeters(lat, lon, workLat, workLon)
            val wasAtWork = biasInside || engine.state().atWork
            val reading: Reading = Geo.classify(distance, accuracyM, wasAtWork, s)
            return engine.onReading(LocalDateTime.now(), reading)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Учёт сверхурочных",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "tracking"
    }
}
