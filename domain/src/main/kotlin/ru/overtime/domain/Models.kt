package ru.overtime.domain

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** Как считать сверхурочную: только после конца смены или ещё и до начала. */
enum class OvertimeMode { AFTER_SHIFT, BEFORE_AND_AFTER }

enum class RoundMode { DOWN, UP, NEAREST }

/** Правило одного дня недели. Если [workday] = false, день выходной. */
data class DayRule(
    val workday: Boolean,
    val start: LocalTime = LocalTime.of(8, 0),
    val end: LocalTime = LocalTime.of(17, 0),
)

/** Все настройки приложения. Деньги везде в рублях. */
data class Settings(
    val days: Map<DayOfWeek, DayRule> = defaultDays(),
    val mode: OvertimeMode = OvertimeMode.AFTER_SHIFT,
    /** В выходные весь день считается сверхурочным. */
    val weekendAllOvertime: Boolean = true,
    /** Первые N минут после конца смены не считаются. */
    val graceAfterShiftMinutes: Int = 0,
    val roundStepMinutes: Int = 10,
    val roundMode: RoundMode = RoundMode.NEAREST,
    /** Если за день набралось меньше, сверхурочная не засчитывается. */
    val minOvertimeMinutes: Int = 10,
    /** Если вернулись на работу не позже чем через N минут, сессия продолжается. */
    val mergeGapMinutes: Int = 60,
    val pollIntervalMinutes: Int = 10,
    val enterRadiusM: Int = 100,
    val exitRadiusM: Int = 200,
    /** Замеры с худшей точностью отбрасываются. */
    val maxAccuracyM: Int = 100,
    val workLat: Double? = null,
    val workLon: Double? = null,
    val formula: String = DEFAULT_FORMULA,
    /** Ставка, руб/час. */
    val rate: Double = 0.0,
    /** Оклад, руб/мес (переменная salary в формуле). */
    val salary: Double = 0.0,
    /** Норма часов в месяце (переменная norm в формуле). */
    val normHours: Double = 0.0,
) {
    fun rule(day: DayOfWeek): DayRule = days[day] ?: DayRule(false)

    val hasWorkPlace: Boolean get() = workLat != null && workLon != null

    /** Список проблем с настройками (пусто, если всё в порядке). */
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        for (d in DayOfWeek.values()) {
            val r = rule(d)
            if (r.workday && !r.end.isAfter(r.start)) errors += "Конец смены должен быть позже начала (${d.name})"
        }
        if (roundStepMinutes < 1) errors += "Шаг округления должен быть не меньше 1 минуты"
        if (exitRadiusM < enterRadiusM) errors += "Радиус выхода не может быть меньше радиуса входа"
        if (enterRadiusM < 30) errors += "Радиус входа меньше 30 м ненадёжен"
        if (pollIntervalMinutes < 1) errors += "Интервал опроса должен быть не меньше 1 минуты"
        if (graceAfterShiftMinutes < 0 || minOvertimeMinutes < 0 || mergeGapMinutes < 0) {
            errors += "Значения в минутах не могут быть отрицательными"
        }
        return errors
    }

    companion object {
        const val DEFAULT_FORMULA =
            "if(weekend, rate * h * 2, rate * (min(h, 2) * 1.5 + max(h - 2, 0) * 2))"

        fun defaultDays(): Map<DayOfWeek, DayRule> = DayOfWeek.values().associateWith {
            DayRule(workday = it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY)
        }
    }
}

/** Интервал времени [start, end). */
data class Interval(val start: LocalDateTime, val end: LocalDateTime) {
    init {
        require(!end.isBefore(start)) { "end < start" }
    }

    fun intersect(o: Interval): Interval? {
        val s = laterOf(start, o.start)
        val e = earlierOf(end, o.end)
        return if (e.isAfter(s)) Interval(s, e) else null
    }

    val seconds: Long get() = java.time.Duration.between(start, end).seconds
}

/** Объединяет пересекающиеся и соприкасающиеся интервалы, чтобы ничего не считалось дважды. */
fun union(list: List<Interval>): List<Interval> {
    if (list.isEmpty()) return emptyList()
    val sorted = list.sortedBy { it.start }
    val result = mutableListOf<Interval>()
    var cur = sorted[0]
    for (i in 1 until sorted.size) {
        val n = sorted[i]
        cur = if (!n.start.isAfter(cur.end)) {
            Interval(cur.start, laterOf(cur.end, n.end))
        } else {
            result += cur
            n
        }
    }
    result += cur
    return result
}

fun laterOf(a: LocalDateTime, b: LocalDateTime): LocalDateTime = if (a.isAfter(b)) a else b
fun earlierOf(a: LocalDateTime, b: LocalDateTime): LocalDateTime = if (a.isBefore(b)) a else b
