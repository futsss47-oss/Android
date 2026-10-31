package ru.overtime.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Отрезок присутствия. Автоматические отрезки ([manual] = false) обрезаются по окнам сверхурочной,
 * ручные и исправленные вручную считаются как есть.
 */
data class Span(val start: LocalDateTime, val end: LocalDateTime, val manual: Boolean)

data class DayResult(
    val date: LocalDate,
    val rawMinutes: Int,
    val countedMinutes: Int,
    val dayOff: Boolean,
    /** Оплата за день по формуле, до премии и районного коэффициента. */
    val money: Double,
    /** Оплата с районным коэффициентом, без премии. */
    val gross: Double,
    /** Часть оплаты, на которую начисляется премия (выходные — половина, сверхурочные — целиком). */
    val bonusBase: Double,
    /** Премия, которую добавил этот день. */
    val bonus: Double,
    /** Начислено за день: оплата + премия, с районным коэффициентом. */
    val withBonus: Double,
    /** На руки за день: начислено с премией минус НДФЛ. */
    val net: Double,
)

data class Report(
    val days: List<DayResult>,
    val totalMinutes: Int,
    /** Сумма по формулам за период, до премии и районного коэффициента. */
    val totalMoney: Double,
    /** Сумма за период с районным коэффициентом, без премии. */
    val totalGross: Double,
    /** Если формула некорректна, здесь текст ошибки, а деньги по этим дням равны нулю. */
    val formulaError: String?,
    /** База премии от переработок (выходные одинарно + сверхурочные). */
    val totalBonusBase: Double = 0.0,
    /** Начислено за переработки с премией и районным коэффициентом. */
    val totalWithBonus: Double = 0.0,
    /** На руки за переработки с премией. */
    val totalNet: Double = 0.0,
)

/** Расчёт месяца в виде расчётного листка. */
data class MonthSummary(
    val month: YearMonth,
    val norm: Double,
    val normIsAuto: Boolean,
    val rate: Double,
    val salary: Double,
    val normDays: Int,
    val workedDays: Int,
    val workedDaysIsAuto: Boolean,
    /** Оклад за отработанные дни. */
    val oklad: Double,
    val bonusPercent: Double,
    val bonusPercentIsAuto: Boolean,
    /** База премии: оклад за отработанное + выходные одинарно + сверхурочные. */
    val bonusBase: Double,
    val bonus: Double,
    /** Премия вписана вручную точной суммой из листка. */
    val bonusIsManual: Boolean,
    /** Выходные и сверхурочные до премии и районного коэффициента. */
    val extra: Double,
    val base: Double,
    val district: Double,
    val gross: Double,
    val tax: Double,
    val net: Double,
)

/**
 * Аванс за первую половину месяца, как в расчётном листке:
 * (оклад за отработанные 1–15 числа + выходные и сверхурочные за 1–15 число) × районный − НДФЛ.
 * Премия в аванс не входит. Остаток = вся зарплата на руки − аванс.
 */
data class AdvanceSummary(
    val firstHalfDays: Int,
    val workedFirstHalf: Int,
    val oklad: Double,
    val extra: Double,
    val district: Double,
    val gross: Double,
    val tax: Double,
    val net: Double,
    /** Остаток зарплаты, который придёт в следующем месяце. */
    val rest: Double,
)

object Calculator {

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    /** Сколько минут сверхурочной набралось за дату до округления и порогов. */
    fun rawMinutes(date: LocalDate, spans: List<Span>, s: Settings): Int {
        val d0 = date.atStartOfDay()
        val dayInterval = Interval(d0, date.plusDays(1).atStartOfDay())
        val windows = Windows.forDate(date, s).map { Interval(it.start, it.end) }
        val parts = mutableListOf<Interval>()
        for (sp in spans) {
            if (!sp.end.isAfter(sp.start)) continue
            val iv = Interval(sp.start, sp.end)
            if (sp.manual) {
                iv.intersect(dayInterval)?.let { parts += it }
            } else {
                for (w in windows) iv.intersect(w)?.let { parts += it }
            }
        }
        val merged = union(parts)
        var seconds = merged.sumOf { it.seconds }
        // Обед не оплачивается: вычитаем ту часть обеденного перерыва, когда человек был на работе.
        if (s.lunchEnabled && s.lunchEnd.isAfter(s.lunchStart)) {
            val lunch = Interval(date.atTime(s.lunchStart), date.atTime(s.lunchEnd))
            seconds -= merged.sumOf { it.intersect(lunch)?.seconds ?: 0L }
        }
        return (seconds / 60).toInt()
    }

    /** Применяет минимальный порог и округление. */
    fun counted(raw: Int, s: Settings): Int {
        if (raw <= 0 || raw < s.minOvertimeMinutes) return 0
        val step = maxOf(1, s.roundStepMinutes)
        return when (s.roundMode) {
            RoundMode.DOWN -> raw / step * step
            RoundMode.UP -> (raw + step - 1) / step * step
            RoundMode.NEAREST -> (raw + step / 2) / step * step
        }
    }

    /** Оплата за день по формуле (до районного коэффициента). */
    fun money(date: LocalDate, countedMinutes: Int, s: Settings): Double {
        if (countedMinutes <= 0) return 0.0
        val month = YearMonth.from(date)
        val dayOff = !s.ruleFor(date).workday
        val vars = mapOf(
            "h" to countedMinutes / 60.0,
            "m" to countedMinutes.toDouble(),
            "rate" to s.hourlyRate(month),
            "salary" to s.salary,
            "norm" to s.normFor(month),
            "weekend" to if (dayOff) 1.0 else 0.0,
            "dow" to date.dayOfWeek.value.toDouble(),
        )
        // В нерабочий день считаем по формуле для выходных, в рабочий по обычной.
        val raw = Formula.evaluate(if (dayOff) s.weekendFormula else s.formula, vars)
        return round2(raw)
    }

    /** Отчёт за период [from]..[to] включительно. Дни без сверхурочной в отчёт не попадают. */
    fun report(from: LocalDate, to: LocalDate, spans: List<Span>, s: Settings): Report {
        var formulaError: String? = null
        val days = mutableListOf<DayResult>()
        val k = 1.0 + s.districtPercent / 100.0
        var date = from
        while (!date.isAfter(to)) {
            val raw = rawMinutes(date, spans, s)
            if (raw > 0) {
                val counted = counted(raw, s)
                val money = try {
                    money(date, counted, s)
                } catch (e: FormulaException) {
                    formulaError = e.message
                    0.0
                }
                val dayOff = !s.ruleFor(date).workday
                val bonusBase = if (dayOff) money * Settings.WEEKEND_BONUS_SHARE else money
                val bonus = bonusBase * s.bonusPercentFor(YearMonth.from(date)) / 100.0
                val withBonus = (money + bonus) * k
                days += DayResult(
                    date = date,
                    rawMinutes = raw,
                    countedMinutes = counted,
                    dayOff = dayOff,
                    money = money,
                    gross = round2(money * k),
                    bonusBase = round2(bonusBase),
                    bonus = round2(bonus),
                    withBonus = round2(withBonus),
                    net = round2(withBonus * (1.0 - s.taxPercent / 100.0)),
                )
            }
            date = date.plusDays(1)
        }
        return Report(
            days = days,
            totalMinutes = days.sumOf { it.countedMinutes },
            totalMoney = round2(days.sumOf { it.money }),
            totalGross = round2(days.sumOf { it.gross }),
            formulaError = formulaError,
            totalBonusBase = round2(days.sumOf { it.bonusBase }),
            totalWithBonus = round2(days.sumOf { it.withBonus }),
            totalNet = round2(days.sumOf { it.net }),
        )
    }

    /**
     * Расчёт месяца как в расчётном листке:
     * оклад за отработанные дни + выходные и сверхурочные + премия
     * (коэффициент × (оклад за отработанное + выходные одинарно + сверхурочные)),
     * на сумму районный коэффициент, из начисленного НДФЛ (округляется до рубля).
     * [report] должен быть отчётом за этот же месяц.
     */
    fun monthSummary(month: YearMonth, report: Report, s: Settings): MonthSummary {
        val oklad = round2(s.okladFor(month))
        val percent = s.bonusPercentFor(month)
        val bonusBase = round2(oklad + report.totalBonusBase)
        val manual = s.bonusSumOverride(month)
        val bonus = manual ?: round2(bonusBase * percent / 100.0)
        val base = round2(oklad + report.totalMoney + bonus)
        val district = round2(base * s.districtPercent / 100.0)
        val gross = round2(base + district)
        val tax = Math.round(gross * s.taxPercent / 100.0).toDouble()
        return MonthSummary(
            month = month,
            norm = s.normFor(month),
            normIsAuto = s.normIsAuto(month),
            rate = round2(s.hourlyRate(month)),
            salary = s.salary,
            normDays = s.normDays(month),
            workedDays = s.workedDaysFor(month),
            workedDaysIsAuto = s.workedDaysIsAuto(month),
            oklad = oklad,
            bonusPercent = percent,
            bonusPercentIsAuto = s.bonusPercentIsAuto(month),
            bonusBase = bonusBase,
            bonus = bonus,
            bonusIsManual = manual != null,
            extra = report.totalMoney,
            base = base,
            district = district,
            gross = gross,
            tax = tax,
            net = round2(gross - tax),
        )
    }

    /** Последнее число первой половины месяца. */
    const val ADVANCE_LAST_DAY = 15

    fun advance(month: YearMonth, report: Report, summary: MonthSummary, s: Settings): AdvanceSummary {
        val normDays = s.normDays(month)
        val firstHalf = s.workdaysIn(month, 1, ADVANCE_LAST_DAY).size
        val worked = (firstHalf - s.absentWorkdaysIn(month, 1, ADVANCE_LAST_DAY)).coerceAtLeast(0)
        val oklad = if (normDays > 0) round2(s.salary * worked / normDays) else 0.0
        val extra = round2(report.days.filter { it.date.dayOfMonth <= ADVANCE_LAST_DAY }.sumOf { it.money })
        val base = oklad + extra
        val district = round2(base * s.districtPercent / 100.0)
        val gross = round2(base + district)
        val tax = Math.round(gross * s.taxPercent / 100.0).toDouble()
        val net = round2(gross - tax)
        return AdvanceSummary(
            firstHalfDays = firstHalf,
            workedFirstHalf = worked,
            oklad = oklad,
            extra = extra,
            district = district,
            gross = gross,
            tax = tax,
            net = net,
            rest = round2(summary.net - net),
        )
    }
}
