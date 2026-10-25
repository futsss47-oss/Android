package ru.overtime.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/**
 * Производственный календарь Республики Башкортостан (пятидневка, 40-часовая неделя).
 *
 * Кроме федеральных праздников и переносов здесь учтены региональные нерабочие дни:
 * Ураза-байрам и Курбан-байрам (даты каждый год устанавливает Правительство РБ),
 * День Республики 11 октября (если выпадает на выходной, отдых переносится на понедельник).
 *
 * Источники: федеральные календари КонсультантПлюс, постановление Правительства РБ от 15.07.2025 N 322
 * (20 марта и 27 мая 2026 года), календарные данные по Башкирии за май и октябрь.
 * Данные 2025 года сверены с расчётными листками: март 159 ч, июнь 142 ч, июль 184 ч, сентябрь 176 ч.
 *
 * Для лет без данных норма оценивается по общим правилам и помечается как оценка.
 */
object ProductionCalendar {

    private class YearData(
        /** Нерабочие дни, выпавшие на будни (праздники и перенесённые выходные). */
        val off: Set<LocalDate>,
        /** Рабочие суббота и воскресенье (переносы). */
        val workWeekends: Set<LocalDate>,
        /** Дни, у которых рабочее время на 1 час короче. */
        val short: Set<LocalDate>,
        val estimated: Boolean,
    )

    private fun d(year: Int, month: Int, vararg days: Int): List<LocalDate> =
        days.map { LocalDate.of(year, month, it) }

    private val Y2025 = YearData(
        off = (
            d(2025, 1, 1, 2, 3, 6, 7, 8) +
                d(2025, 3, 31) +                 // Ураза-байрам (РБ)
                d(2025, 5, 1, 2, 8, 9) +
                d(2025, 6, 6, 12, 13) +          // 6 июня Курбан-байрам (РБ)
                d(2025, 10, 13) +                // День Республики 11.10 (сб), отдых перенесён на понедельник
                d(2025, 11, 3, 4) +
                d(2025, 12, 31)
            ).toSet(),
        workWeekends = d(2025, 11, 1).toSet(),
        short = (
            d(2025, 3, 7) + d(2025, 4, 30) + d(2025, 6, 5) + d(2025, 6, 11) +
                d(2025, 10, 10) + d(2025, 11, 1)
            ).toSet(),
        estimated = false,
    )

    private val Y2026 = YearData(
        off = (
            d(2026, 1, 1, 2, 5, 6, 7, 8, 9) +
                d(2026, 2, 23) +
                d(2026, 3, 9, 20) +              // 20 марта Ураза-байрам (РБ)
                d(2026, 5, 1, 11, 27) +          // 27 мая Курбан-байрам (РБ)
                d(2026, 6, 12) +
                d(2026, 10, 12) +                // День Республики 11.10 (вс), отдых перенесён на понедельник
                d(2026, 11, 4) +
                d(2026, 12, 31)
            ).toSet(),
        workWeekends = emptySet(),
        short = (
            d(2026, 3, 19) +                     // накануне Ураза-байрама (по общему правилу)
                d(2026, 4, 30) + d(2026, 5, 8) + d(2026, 5, 26) + d(2026, 6, 11) + d(2026, 11, 3)
            ).toSet(),
        estimated = false,
    )

    /** 2027: федеральный календарь и День Республики. Байрамы РБ на 2027 год пока не внесены. */
    private val Y2027 = YearData(
        off = (
            d(2027, 1, 1, 4, 5, 6, 7, 8) +
                d(2027, 2, 22, 23) +
                d(2027, 3, 8) +
                d(2027, 5, 3, 10) +
                d(2027, 6, 14) +
                d(2027, 10, 11) +
                d(2027, 11, 4, 5) +
                d(2027, 12, 31)
            ).toSet(),
        workWeekends = d(2027, 2, 20).toSet(),
        short = (d(2027, 2, 20) + d(2027, 4, 30) + d(2027, 6, 11) + d(2027, 11, 3)).toSet(),
        estimated = false,
    )

    private val known: Map<Int, YearData> = mapOf(2025 to Y2025, 2026 to Y2026, 2027 to Y2027)
    private val estimates = ConcurrentHashMap<Int, YearData>()

    private fun data(year: Int): YearData = known[year] ?: estimates.computeIfAbsent(year) { estimate(it) }

    private fun isWeekend(date: LocalDate) =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    /** Грубая оценка для лет без данных: постоянные праздники РФ и День Республики, без байрамов. */
    private fun estimate(year: Int): YearData {
        val fixed = (1..8).map { LocalDate.of(year, 1, it) } + listOf(
            LocalDate.of(year, 2, 23), LocalDate.of(year, 3, 8), LocalDate.of(year, 5, 1),
            LocalDate.of(year, 5, 9), LocalDate.of(year, 6, 12), LocalDate.of(year, 10, 11),
            LocalDate.of(year, 11, 4),
        )
        val off = mutableSetOf<LocalDate>()
        for (holiday in fixed) {
            var day = holiday
            // праздник совпал с выходным: выходной переносится на следующий рабочий день
            while (isWeekend(day) || day in off) day = day.plusDays(1)
            off += day
        }
        val short = mutableSetOf<LocalDate>()
        for (holiday in fixed) {
            if (holiday.monthValue == 1) continue
            val eve = holiday.minusDays(1)
            if (!isWeekend(eve) && eve !in off) short += eve
        }
        return YearData(off, emptySet(), short, estimated = true)
    }

    /** Длительность рабочего дня по календарю: 0 (нерабочий), 7 (сокращённый) или 8 часов. */
    fun hours(date: LocalDate): Int {
        val y = data(date.year)
        return when {
            date in y.off -> 0
            isWeekend(date) && date !in y.workWeekends -> 0
            date in y.short -> 7
            else -> 8
        }
    }

    fun isShortDay(date: LocalDate): Boolean = hours(date) == 7

    fun normHours(month: YearMonth): Int =
        (1..month.lengthOfMonth()).sumOf { hours(month.atDay(it)) }

    fun workDays(month: YearMonth): Int =
        (1..month.lengthOfMonth()).count { hours(month.atDay(it)) > 0 }

    fun isEstimated(year: Int): Boolean = data(year).estimated

    /** Предупреждение для пользователя, если данные за год неполные. */
    fun note(year: Int): String? = when (year) {
        2025, 2026 -> null
        2027 -> "На 2027 год в приложении нет дат Ураза-байрама и Курбан-байрама в Башкортостане " +
            "(их устанавливает постановление Правительства РБ). Проверьте норму часов в марте и мае."
        else -> "Календарь на $year год не загружен, норма часов оценена по общим правилам. Проверьте её."
    }
}
