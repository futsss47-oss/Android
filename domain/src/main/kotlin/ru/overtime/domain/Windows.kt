package ru.overtime.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Окно, в котором присутствие на работе считается сверхурочной: [start, end).
 *
 * [needsPresenceBefore] задан для «хвоста» после полуночи в режиме «только после смены»:
 * ночные часы до начала смены считаются, только если человек остался на работе с вечера
 * (то есть пришёл раньше этого момента).
 */
data class TimeWindow(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val needsPresenceBefore: LocalDateTime? = null,
)

object Windows {

    fun forDate(date: LocalDate, s: Settings): List<TimeWindow> {
        val d0 = date.atStartOfDay()
        val d1 = date.plusDays(1).atStartOfDay()
        val rule = s.ruleFor(date)
        val result = mutableListOf<TimeWindow>()

        if (!rule.workday) {
            if (s.weekendAllOvertime) result += TimeWindow(d0, d1)
            return result
        }

        if (rule.start != LocalTime.MIDNIGHT) {
            when (s.mode) {
                OvertimeMode.BEFORE_AND_AFTER ->
                    result += TimeWindow(d0, date.atTime(rule.start))
                OvertimeMode.AFTER_SHIFT ->
                    if (windowReachesMidnight(date.minusDays(1), s)) {
                        result += TimeWindow(d0, date.atTime(rule.start), needsPresenceBefore = d0)
                    }
            }
        }

        val afterStart = date.atTime(rule.end).plusMinutes(s.graceAfterShiftMinutes.toLong())
        if (afterStart.isBefore(d1)) result += TimeWindow(afterStart, d1)
        return result
    }

    /** Окно, в которое попадает момент [t], либо null. */
    fun containing(t: LocalDateTime, s: Settings): TimeWindow? =
        forDate(t.toLocalDate(), s).firstOrNull { !t.isBefore(it.start) && t.isBefore(it.end) }

    private fun windowReachesMidnight(date: LocalDate, s: Settings): Boolean {
        val rule = s.ruleFor(date)
        if (!rule.workday) return s.weekendAllOvertime
        val afterStart = date.atTime(rule.end).plusMinutes(s.graceAfterShiftMinutes.toLong())
        return afterStart.isBefore(date.plusDays(1).atStartOfDay())
    }
}
