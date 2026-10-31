package ru.overtime.app.ui

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ru.overtime.app.OvertimeApp
import ru.overtime.app.data.SessionEntity
import ru.overtime.app.data.TimeConv
import ru.overtime.app.location.GeofenceHelper
import ru.overtime.app.location.Perms
import ru.overtime.app.reminder.MonthReminder
import ru.overtime.domain.DayKind
import ru.overtime.domain.MonthCalc
import ru.overtime.domain.MonthCalculator
import ru.overtime.domain.MonthOverride
import ru.overtime.domain.Report
import ru.overtime.domain.Settings
import ru.overtime.domain.TrackerState
import java.time.LocalDateTime
import java.time.YearMonth

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OvertimeApp
    private val dao = app.db.sessionDao()

    val settings: StateFlow<Settings> = app.settingsRepo.flow
    val month = MutableStateFlow(YearMonth.now())

    /** Часы приложения: обновляются раз в 30 секунд, чтобы идущая сессия росла на экране. */
    val clock: StateFlow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalDateTime.now())

    val sessions: StateFlow<List<SessionEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracker: StateFlow<TrackerState> = flow {
        while (true) {
            emit(app.trackerState())
            delay(10_000)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackerState())

    /** Выбранный на вкладке «Месяц» месяц: отчёт, листок, аванс, отпускные и больничные. */
    val monthCalc: StateFlow<MonthCalc> = calcFor(month)

    /** Текущий месяц для главного экрана. */
    val homeCalc: StateFlow<MonthCalc> = calcFor(clock.map { YearMonth.from(it) }.distinctUntilChanged())

    val monthReport: StateFlow<Report> = monthCalc.map { it.report }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_REPORT)
    val homeReport: StateFlow<Report> = homeCalc.map { it.report }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_REPORT)

    private fun calcFor(months: Flow<YearMonth>): StateFlow<MonthCalc> =
        combine(sessions, settings, months, clock) { list, s, ym, now ->
            MonthCalculator.calc(ym, list.map { it.toSpan(now) }, s)
        }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                MonthCalculator.calc(YearMonth.now(), emptyList(), app.settingsRepo.current()),
            )

    init {
        // Закрыть сессию, у которой окно сверхурочной уже закончилось, пока приложение не работало.
        viewModelScope.launch(Dispatchers.IO) { app.tick() }
        refreshGeofence()
    }

    fun refreshGeofence() {
        GeofenceHelper.register(app, settings.value)
    }

    fun saveSettings(draft: Settings) {
        // Отметки дней и правки месяцев меняются на вкладке «Месяц», черновик настроек их не трогает.
        val current = app.settingsRepo.current()
        val s = draft.copy(
            monthOverrides = current.monthOverrides,
            absences = current.absences,
            calendarOverrides = current.calendarOverrides,
        )
        app.settingsRepo.save(s)
        GeofenceHelper.register(app, s)
        MonthReminder.schedule(app, s)
    }

    /** Отметить день отсутствия (null — работал). */
    fun setAbsence(date: java.time.LocalDate, absence: ru.overtime.domain.Absence?) {
        val s = app.settingsRepo.current()
        val map = s.absences.toMutableMap()
        if (absence == null) map.remove(date) else map[date] = absence
        app.settingsRepo.save(s.copy(absences = map))
    }

    /** Своя правка типа дня в календаре (null — как в производственном календаре). */
    fun setDayKind(date: java.time.LocalDate, kind: DayKind?) {
        val s = app.settingsRepo.current()
        val map = s.calendarOverrides.toMutableMap()
        if (kind == null) map.remove(date) else map[date] = kind
        app.settingsRepo.save(s.copy(calendarOverrides = map))
    }

    /** Оформление меняется сразу, без кнопки «Сохранить». */
    fun setWallpaperColors(on: Boolean) {
        val s = app.settingsRepo.current()
        app.settingsRepo.save(s.copy(wallpaperColors = on))
    }

    /** Правка дней, нормы и премии одного месяца. Пустые поля = автоматически / из общих настроек. */
    fun saveMonthOverride(month: YearMonth, o: MonthOverride) {
        val s = app.settingsRepo.current()
        val map = s.monthOverrides.toMutableMap()
        if (o.isEmpty) map.remove(month.toString()) else map[month.toString()] = o
        app.settingsRepo.save(s.copy(monthOverrides = map))
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Location? {
        if (!Perms.hasFine(app)) return null
        return try {
            LocationServices.getFusedLocationProviderClient(app)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .await()
        } catch (_: Exception) {
            null
        }
    }

    // ---- ручной режим и правка записей ----

    fun manualStart() = io { app.manualStart() }

    fun manualStop() = io { app.manualStop() }

    fun addSession(start: LocalDateTime, end: LocalDateTime, note: String? = null) = io {
        dao.insert(
            SessionEntity(
                startMillis = TimeConv.toMillis(start),
                endMillis = TimeConv.toMillis(end),
                manual = true,
                note = note?.trim()?.ifEmpty { null },
            ),
        )
    }

    fun updateSession(old: SessionEntity, start: LocalDateTime, end: LocalDateTime, note: String? = old.note) = io {
        val timeChanged = old.startMillis != TimeConv.toMillis(start) || old.endMillis != TimeConv.toMillis(end)
        dao.update(
            old.copy(
                startMillis = TimeConv.toMillis(start),
                endMillis = TimeConv.toMillis(end),
                // правка только заметки не делает автоматическую запись «исправленной»
                edited = old.edited || (!old.manual && timeChanged),
                note = note?.trim()?.ifEmpty { null },
            ),
        )
    }

    /** Заметка к записи, в том числе к идущей. */
    fun setNote(id: Long, note: String?) = io { dao.setNote(id, note?.trim()?.ifEmpty { null }) }

    fun deleteSession(id: Long) = io { dao.delete(id) }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }


    private companion object {
        val EMPTY_REPORT = Report(emptyList(), 0, 0.0, 0.0, null)
    }
}
