package ru.overtime.domain

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Результат одного замера геопозиции. */
enum class Reading { INSIDE, OUTSIDE, UNRELIABLE }

object Geo {
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * Превращает расстояние до работы в [Reading].
     * Внутри входного радиуса: INSIDE. За выходным радиусом: OUTSIDE. Между ними (гистерезис)
     * остаётся прежнее состояние, чтобы на границе зоны не было дрожания.
     */
    fun classify(distanceM: Double, accuracyM: Double, wasAtWork: Boolean, s: Settings): Reading = when {
        accuracyM > s.maxAccuracyM -> Reading.UNRELIABLE
        distanceM <= s.enterRadiusM -> Reading.INSIDE
        distanceM > s.exitRadiusM -> Reading.OUTSIDE
        else -> if (wasAtWork) Reading.INSIDE else Reading.OUTSIDE
    }
}

/** Состояние движка. Сохраняется между запусками, потому что система может убить сервис. */
data class TrackerState(
    val openId: Long? = null,
    val openStart: LocalDateTime? = null,
    val lastInside: LocalDateTime? = null,
    val outsideStreak: Int = 0,
    /** Когда человек пришёл на работу; null, если его там нет. */
    val presenceSince: LocalDateTime? = null,
    /** Последняя автоматически закрытая сессия, нужна для склейки после короткого отсутствия. */
    val lastClosedId: Long? = null,
    val lastClosedStart: LocalDateTime? = null,
    val lastClosedEnd: LocalDateTime? = null,
) {
    val atWork: Boolean get() = presenceSince != null
}

interface SessionGateway {
    fun open(start: LocalDateTime): Long
    fun close(id: Long, end: LocalDateTime)
    fun reopen(id: Long)
}

interface StateStore {
    fun load(): TrackerState
    fun save(state: TrackerState)
}

/**
 * Логика автоматического учёта.
 *
 * - Сессия открывается, когда человек на работе и наступило окно сверхурочной.
 * - Выход засчитывается только после [CONFIRM_READINGS] замеров подряд вне зоны.
 *   Один «выброс» считается глюком геолокации и игнорируется.
 * - Время окончания ставится по последнему подтверждённому присутствию.
 * - Если человек вернулся не позже чем через mergeGapMinutes, сессия продолжается,
 *   а время отсутствия засчитывается.
 * - Сессия закрывается по концу окна (например, по началу смены в режиме «до + после»).
 */
class OvertimeEngine(
    private val settings: () -> Settings,
    private val gateway: SessionGateway,
    private val store: StateStore,
) {
    fun state(): TrackerState = store.load()

    fun onReading(now: LocalDateTime, reading: Reading): TrackerState {
        val s = settings()
        var st = closeIfWindowOver(store.load(), now, s)

        when (reading) {
            Reading.UNRELIABLE -> Unit

            Reading.INSIDE -> {
                st = st.copy(
                    outsideStreak = 0,
                    lastInside = now,
                    presenceSince = st.presenceSince ?: now,
                )
                if (st.openId == null) st = tryOpen(st, now, s)
            }

            Reading.OUTSIDE -> {
                val streak = st.outsideStreak + 1
                val confirmed = streak >= CONFIRM_READINGS
                val openId = st.openId
                val openStart = st.openStart
                st = if (openId != null && openStart != null) {
                    if (confirmed) {
                        val end = laterOf(st.lastInside ?: openStart, openStart)
                        gateway.close(openId, end)
                        st.copy(
                            openId = null,
                            openStart = null,
                            outsideStreak = 0,
                            presenceSince = null,
                            lastClosedId = openId,
                            lastClosedStart = openStart,
                            lastClosedEnd = end,
                        )
                    } else {
                        st.copy(outsideStreak = streak)
                    }
                } else if (confirmed) {
                    st.copy(outsideStreak = 0, presenceSince = null)
                } else {
                    st.copy(outsideStreak = streak)
                }
            }
        }
        store.save(st)
        return st
    }

    /** Вызывать по таймеру, чтобы сессия закрылась по концу окна, даже если замеров нет. */
    fun onTick(now: LocalDateTime): TrackerState {
        val st = closeIfWindowOver(store.load(), now, settings())
        store.save(st)
        return st
    }

    private fun tryOpen(st: TrackerState, now: LocalDateTime, s: Settings): TrackerState {
        val window = Windows.containing(now, s) ?: return st
        val since = st.presenceSince ?: now

        val needs = window.needsPresenceBefore
        if (needs != null && !since.isBefore(needs)) return st

        val closedId = st.lastClosedId
        val closedStart = st.lastClosedStart
        val closedEnd = st.lastClosedEnd
        if (closedId != null && closedStart != null && closedEnd != null &&
            !closedEnd.isBefore(window.start) &&
            Duration.between(closedEnd, now).toMinutes() <= s.mergeGapMinutes
        ) {
            gateway.reopen(closedId)
            return st.copy(
                openId = closedId,
                openStart = closedStart,
                lastClosedId = null,
                lastClosedStart = null,
                lastClosedEnd = null,
            )
        }

        val start = laterOf(window.start, since)
        val id = gateway.open(start)
        return st.copy(openId = id, openStart = start)
    }

    private fun closeIfWindowOver(st: TrackerState, now: LocalDateTime, s: Settings): TrackerState {
        val id = st.openId ?: return st
        val start = st.openStart ?: return st
        val window = Windows.containing(start, s)
        val end: LocalDateTime = when {
            window == null -> laterOf(st.lastInside ?: start, start) // настройки поменяли, окна больше нет
            !now.isBefore(window.end) -> window.end
            else -> return st
        }
        gateway.close(id, end)
        return st.copy(openId = null, openStart = null, outsideStreak = 0)
    }

    companion object {
        const val CONFIRM_READINGS = 2
    }
}
