package ru.overtime.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/** Как считать сверхурочную: только после конца смены или ещё и до начала. */
enum class OvertimeMode { AFTER_SHIFT, BEFORE_AND_AFTER }

enum class RoundMode { DOWN, UP, NEAREST }

/** Правило одного дня недели. Если [workday] = false, день выходной. */
data class DayRule(
    val workday: Boolean,
    val start: LocalTime = LocalTime.of(8, 0),
    val end: LocalTime = LocalTime.of(17, 0),
)

/** Правка параметров одного месяца. null означает «взять автоматически / из общих настроек». */
data class MonthOverride(val norm: Double? = null, val bonus: Double? = null) {
    val isEmpty: Boolean get() = norm == null && bonus == null
}

/** Все настройки приложения. Деньги везде в рублях. */
data class Settings(
    val days: Map<DayOfWeek, DayRule> = defaultDays(),
    val mode: OvertimeMode = OvertimeMode.AFTER_SHIFT,
    /** В выходные весь день считается сверхурочным. */
    val weekendAllOvertime: Boolean = true,
    /** Вычитать обед из выходных и из сверхурочной вне смены. */
    val lunchEnabled: Boolean = true,
    val lunchStart: LocalTime = LocalTime.of(12, 0),
    val lunchEnd: LocalTime = LocalTime.of(13, 0),
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
    /** Формула оплаты за рабочий день. */
    val formula: String = DEFAULT_FORMULA,
    /** Отдельная формула для выходных дней (по вашему графику). */
    val weekendFormula: String = DEFAULT_WEEKEND_FORMULA,
    /** Оклад, руб/мес. Часовая ставка = оклад / норма часов месяца, считается сама. */
    val salary: Double = 0.0,
    /** Месячная премия без сверхурочных, руб. Для конкретного месяца её можно поправить. */
    val bonus: Double = 0.0,
    /** Районный коэффициент, %. Начисляется на оклад, премию, выходные и сверхурочные. */
    val districtPercent: Double = 15.0,
    /** НДФЛ, %. */
    val taxPercent: Double = 13.0,
    /** Напоминание в последний день месяца сверить расчёт с листком. */
    val monthReminder: Boolean = true,
    /** Правки нормы часов и премии по месяцам. Ключ вида «2026-10». */
    val monthOverrides: Map<String, MonthOverride> = emptyMap(),
) {
    fun rule(day: DayOfWeek): DayRule = days[day] ?: DayRule(false)

    /**
     * Правило конкретной даты с учётом производственного календаря РБ:
     * праздники и перенесённые выходные нерабочие, перенесённые рабочие субботы рабочие,
     * в сокращённый день смена на час короче.
     */
    fun ruleFor(date: LocalDate): DayRule {
        val hours = ProductionCalendar.hours(date)
        if (hours == 0) return DayRule(false)
        val base = rule(date.dayOfWeek)
        val weekendDay = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val template = when {
            base.workday -> base
            weekendDay -> workdayTemplate() ?: return DayRule(false)
            else -> return DayRule(false)
        }
        if (hours >= 8) return template
        val shortEnd = template.end.minusHours(1)
        return if (shortEnd.isAfter(template.start)) template.copy(end = shortEnd) else template
    }

    private fun workdayTemplate(): DayRule? =
        listOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
            .map { rule(it) }
            .firstOrNull { it.workday }

    /** Норма часов месяца: правка пользователя или расчёт по календарю. */
    fun normFor(month: YearMonth): Double =
        monthOverrides[month.toString()]?.norm ?: ProductionCalendar.normHours(month).toDouble()

    fun normIsAuto(month: YearMonth): Boolean = monthOverrides[month.toString()]?.norm == null

    fun bonusFor(month: YearMonth): Double = monthOverrides[month.toString()]?.bonus ?: bonus

    fun bonusIsAuto(month: YearMonth): Boolean = monthOverrides[month.toString()]?.bonus == null

    /** Часовая ставка месяца = оклад / норма часов. */
    fun hourlyRate(month: YearMonth): Double {
        val norm = normFor(month)
        return if (norm > 0) salary / norm else 0.0
    }

    val hasWorkPlace: Boolean get() = workLat != null && workLon != null

    /** Список проблем с настройками (пусто, если всё в порядке). */
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        for (d in DayOfWeek.values()) {
            val r = rule(d)
            if (r.workday && !r.end.isAfter(r.start)) errors += "Конец смены должен быть позже начала (${d.name})"
        }
        if (lunchEnabled && !lunchEnd.isAfter(lunchStart)) errors += "Конец обеда должен быть позже начала"
        if (roundStepMinutes < 1) errors += "Шаг округления должен быть не меньше 1 минуты"
        if (exitRadiusM < enterRadiusM) errors += "Радиус выхода не может быть меньше радиуса входа"
        if (enterRadiusM < 30) errors += "Радиус входа меньше 30 м ненадёжен"
        if (pollIntervalMinutes < 1) errors += "Интервал опроса должен быть не меньше 1 минуты"
        if (graceAfterShiftMinutes < 0 || minOvertimeMinutes < 0 || mergeGapMinutes < 0) {
            errors += "Значения в минутах не могут быть отрицательными"
        }
        if (salary < 0 || bonus < 0) errors += "Оклад и премия не могут быть отрицательными"
        if (districtPercent < 0 || districtPercent > 100) errors += "Районный коэффициент должен быть от 0 до 100%"
        if (taxPercent < 0 || taxPercent > 100) errors += "НДФЛ должен быть от 0 до 100%"
        for ((key, o) in monthOverrides) {
            if ((o.norm ?: 0.0) < 0 || (o.bonus ?: 0.0) < 0) errors += "Отрицательное значение в правке месяца $key"
        }
        return errors
    }

    companion object {
        /** Будни: первые 2 часа в полуторном размере, дальше в двойном. */
        const val DEFAULT_FORMULA = "rate * (min(h, 2) * 1.5 + max(h - 2, 0) * 2)"

        /** Выходные: всё время в двойном размере. */
        const val DEFAULT_WEEKEND_FORMULA = "rate * h * 2"

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
