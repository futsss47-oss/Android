package ru.overtime.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import ru.overtime.app.data.AppDatabase
import ru.overtime.app.data.SessionEntity
import ru.overtime.app.tile.ManualTileService
import ru.overtime.app.data.PrefsStateStore
import ru.overtime.app.data.RoomSessionGateway
import ru.overtime.app.data.SettingsRepository
import ru.overtime.app.reminder.MonthReminder
import ru.overtime.app.widget.WidgetUpdater
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

    /** Фоновые задачи приложения: обновление виджета, напоминания. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Движок вызывается из сервиса, ресивера и интерфейса, поэтому все обращения идут под одним замком. */
    private val lock = Any()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.build(this)
        settingsRepo = SettingsRepository(this)
        engine = OvertimeEngine(
            settings = { settingsRepo.current() },
            gateway = RoomSessionGateway(db.sessionDao()),
            store = PrefsStateStore(this),
        )
        createChannels()
        MonthReminder.schedule(this, settingsRepo.current())

        // Виджет обновляется при любом изменении записей или настроек.
        appScope.launch {
            combine(db.sessionDao().observeAll(), settingsRepo.flow) { sessions, settings -> sessions to settings }
                .debounce(500)
                .collect { (sessions, settings) -> WidgetUpdater.render(this@OvertimeApp, sessions, settings) }
        }
    }

    fun trackerState(): TrackerState = synchronized(lock) { engine.state() }

    // ---- ручной режим: общий для приложения, виджетов и плитки в шторке ----

    /** Только не из главного потока. */
    fun isManualRunning(): Boolean = db.sessionDao().openManual() != null

    /** Только не из главного потока. Запускает ручную сверхурочную, если она ещё не идёт. */
    fun manualStart() {
        val dao = db.sessionDao()
        if (dao.openManual() == null) {
            dao.insert(SessionEntity(startMillis = System.currentTimeMillis(), endMillis = null, manual = true))
        }
        onManualChanged()
    }

    /** Только не из главного потока. */
    fun manualStop() {
        val dao = db.sessionDao()
        dao.openManual()?.let { dao.setEnd(it.id, System.currentTimeMillis()) }
        onManualChanged()
    }

    /** Только не из главного потока. Возвращает true, если после нажатия сверхурочная идёт. */
    fun manualToggle(): Boolean {
        if (isManualRunning()) manualStop() else manualStart()
        return isManualRunning()
    }

    private fun onManualChanged() {
        WidgetUpdater.request(this)
        ManualTileService.requestUpdate(this)
    }

    /** Только не из главного потока: пишет в базу. */
    fun tick() {
        synchronized(lock) { engine.onTick(LocalDateTime.now()) }
    }

    /**
     * Обрабатывает замер геопозиции. [biasInside] = true сразу после события входа в геозону:
     * в «серой зоне» между радиусами считаем, что человек на работе.
     */
    fun processLocation(lat: Double, lon: Double, accuracyM: Double, biasInside: Boolean): TrackerState {
        val state = synchronized(lock) {
            val s = settingsRepo.current()
            val workLat = s.workLat
            val workLon = s.workLon
            if (workLat == null || workLon == null) return engine.state()
            val distance = Geo.distanceMeters(lat, lon, workLat, workLon)
            val wasAtWork = biasInside || engine.state().atWork
            val reading: Reading = Geo.classify(distance, accuracyM, wasAtWork, s)
            engine.onReading(LocalDateTime.now(), reading)
        }
        // Таймер идущей сверхурочной на виджете обновляется с каждым замером.
        WidgetUpdater.request(this)
        return state
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Учёт сверхурочных", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL_ID = "tracking"
        const val REMINDER_CHANNEL_ID = "reminders"
    }
}
