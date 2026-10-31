package ru.overtime.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * Отпускные и больничные месяца по среднему заработку.
 *
 * Отпускные (ст. 139 ТК РФ, Положение № 922): средний дневной = начисления за 12 месяцев до месяца отпуска /
 * (29,3 × полностью отработанные месяцы + 29,3 / дней месяца × отработанные календарные дни неполных месяцев).
 * Оплачиваются календарные дни отпуска, кроме праздников.
 *
 * Больничный (255-ФЗ): средний дневной = начисления за 2 предыдущих календарных года / 730, но не больше
 * предельной базы взносов и не меньше МРОТ × районный × 24 / 730; затем процент по стажу.
 * Оплачиваются все календарные дни (первые 3 дня — работодатель, остальные — Соцфонд; здесь одной суммой).
 */
data class LeavePay(
    val vacationDays: Int = 0,
    /** Средний дневной заработок для отпуска. */
    val vacationAvgDaily: Double = 0.0,
    val vacationPay: Double = 0.0,
    /** Начисления за расчётный период, по которым считался средний. */
    val vacationEarnings: Double = 0.0,
    /** Дней в расчётном периоде (29,3 за полный месяц). */
    val vacationPeriodDays: Double = 0.0,
    /** true, если начисления оценены приложением, а не вписаны из бухгалтерии. */
    val vacationEstimated: Boolean = true,
    val sickDays: Int = 0,
    /** Пособие в день с учётом процента по стажу. */
    val sickDaily: Double = 0.0,
    val sickPay: Double = 0.0,
    val sickEarnings: Double = 0.0,
    val sickEstimated: Boolean = true,
    /** Сработал минимум по МРОТ или максимум по предельной базе. */
    val sickLimit: SickLimit = SickLimit.NONE,
) {
    val isEmpty: Boolean get() = vacationDays == 0 && sickDays == 0
}

enum class SickLimit { NONE, MIN_MROT, MAX_BASE }

object Leave {
    /** Среднее число календарных дней в месяце для отпускных. */
    const val AVG_MONTH_DAYS = 29.3

    /** Предельная база для взносов в Соцфонд по годам, руб. */
    private val SICK_BASE_LIMIT = mapOf(
        2022 to 1_565_000.0,
        2023 to 1_917_000.0,
        2024 to 2_225_000.0,
        2025 to 2_759_000.0,
        2026 to 2_979_000.0,
    )

    /** МРОТ по годам, руб. */
    private val MROT = mapOf(
        2024 to 19_242.0,
        2025 to 22_440.0,
        2026 to 27_093.0,
    )

    private fun limit(year: Int): Double =
        SICK_BASE_LIMIT[year] ?: SICK_BASE_LIMIT.getValue(SICK_BASE_LIMIT.keys.max())

    private fun mrot(year: Int): Double = MROT[year] ?: MROT.getValue(MROT.keys.max())

    /**
     * Входит ли дата в отпуск или больничный ([type] — VACATION или SICK).
     * Отмеченный день входит всегда (отпуск — кроме праздника). Неотмеченный нерабочий день входит,
     * если с обеих сторон к нему примыкают дни того же типа: выходные внутри отпуска отмечать не нужно.
     */
    fun isLeaveDay(date: LocalDate, s: Settings, type: Absence): Boolean {
        val vacation = type == Absence.VACATION
        val marked = s.absences[date]
        if (marked == type) return !(vacation && s.isHoliday(date))
        if (marked != null || s.dayHours(date) > 0) return false
        if (vacation && s.isHoliday(date)) return false
        return neighbour(date, s, -1) == type && neighbour(date, s, 1) == type
    }

    /** Ближайшая отметка через нерабочие неотмеченные дни; null, если раньше встретился рабочий день. */
    private fun neighbour(date: LocalDate, s: Settings, step: Long): Absence? {
        var d = date
        repeat(14) {
            d = d.plusDays(step)
            s.absences[d]?.let { return it }
            if (s.dayHours(d) > 0) return null
        }
        return null
    }

    fun leaveDates(month: YearMonth, s: Settings, type: Absence): List<LocalDate> =
        (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { isLeaveDay(it, s, type) }

    /** Календарные дни месяца, которые исключаются из расчётного периода отпускных. */
    private fun excludedDays(month: YearMonth, s: Settings): Int =
        (1..month.lengthOfMonth()).map { month.atDay(it) }.count {
            isLeaveDay(it, s, Absence.VACATION) || isLeaveDay(it, s, Absence.SICK) ||
                (s.absences[it] == Absence.VACATION)
        }

    /**
     * [earnings] — начисления месяца без отпускных и больничных (вызывается только для нужных месяцев).
     */
    fun compute(month: YearMonth, s: Settings, earnings: (YearMonth) -> Double): LeavePay {
        val vacationDays = leaveDates(month, s, Absence.VACATION).size
        val sickDays = leaveDates(month, s, Absence.SICK).size
        var result = LeavePay(vacationDays = vacationDays, sickDays = sickDays)

        if (vacationDays > 0) {
            val period = (1L..12L).map { month.minusMonths(it) }
            val periodDays = period.sumOf { m ->
                val len = m.lengthOfMonth()
                AVG_MONTH_DAYS * (len - excludedDays(m, s)).coerceAtLeast(0) / len
            }
            val manual = s.vacationEarnings12m
            val sum = manual ?: period.sumOf { earnings(it) }
            val avg = if (periodDays > 0) sum / periodDays else 0.0
            result = result.copy(
                vacationAvgDaily = round2(avg),
                vacationPay = round2(round2(avg) * vacationDays),
                vacationEarnings = round2(sum),
                vacationPeriodDays = Math.round(periodDays * 100) / 100.0,
                vacationEstimated = manual == null,
            )
        }

        if (sickDays > 0) {
            val y1 = month.year - 2
            val y2 = month.year - 1
            val maxTotal = limit(y1) + limit(y2)
            val manual = s.sickEarnings2y
            val sum = manual?.coerceAtMost(maxTotal) ?: run {
                fun year(y: Int) = (1..12).sumOf { earnings(YearMonth.of(y, it)) }.coerceAtMost(limit(y))
                year(y1) + year(y2)
            }
            val raw = sum / 730.0
            val min = mrot(month.year) * (1 + s.districtPercent / 100.0) * 24 / 730.0
            val max = maxTotal / 730.0
            val (avg, lim) = when {
                raw < min -> min to SickLimit.MIN_MROT
                raw > max -> max to SickLimit.MAX_BASE
                else -> raw to SickLimit.NONE
            }
            val daily = round2(avg * s.sickPercent / 100.0)
            result = result.copy(
                sickDaily = daily,
                sickPay = round2(daily * sickDays),
                sickEarnings = round2(sum),
                sickEstimated = manual == null,
                sickLimit = lim,
            )
        }
        return result
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
