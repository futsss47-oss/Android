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
    /** Оплата за день по формуле, до районного коэффициента. */
    val money: Double,
    /** Оплата за день с районным коэффициентом (как «начислено»). */
    val gross: Double,
)

data class Report(
    val days: List<DayResult>,
    val totalMinutes: Int,
    /** Сумма по формулам за период, до районного коэффициента. */
    val totalMoney: Double,
    /** Сумма за период с районным коэффициентом. */
    val totalGross: Double,
    /** Если формула некорректна, здесь текст ошибки, а деньги по этим дням равны нулю. */
    val formulaError: String?,
)

/** Расчёт месяца в виде расчётного листка. Оклад и премия плановые, остальное по записям. */
data class MonthSummary(
    val month: YearMonth,
    val norm: Double,
    val normIsAuto: Boolean,
    val rate: Double,
    val salary: Double,
    val bonus: Double,
    val bonusIsAuto: Boolean,
    /** Выходные и сверхурочные до районного коэффициента. */
    val extra: Double,
    val base: Double,
    val district: Double,
    val gross: Double,
    val tax: Double,
    val net: Double,
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
        return (union(parts).sumOf { it.seconds } / 60).toInt()
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
                days += DayResult(date, raw, counted, !s.ruleFor(date).workday, money, round2(money * k))
            }
            date = date.plusDays(1)
        }
        return Report(
            days = days,
            totalMinutes = days.sumOf { it.countedMinutes },
            totalMoney = round2(days.sumOf { it.money }),
            totalGross = round2(days.sumOf { it.gross }),
            formulaError = formulaError,
        )
    }

    /**
     * Расчёт месяца как в расчётном листке: оклад + премия + выходные и сверхурочные,
     * на сумму районный коэффициент, из начисленного НДФЛ (округляется до рубля).
     * [report] должен быть отчётом за этот же месяц.
     */
    fun monthSummary(month: YearMonth, report: Report, s: Settings): MonthSummary {
        val bonus = s.bonusFor(month)
        val base = round2(s.salary + bonus + report.totalMoney)
        val district = round2(base * s.districtPercent / 100.0)
        val gross = round2(base + district)
        val tax = Math.round(gross * s.taxPercent / 100.0).toDouble()
        return MonthSummary(
            month = month,
            norm = s.normFor(month),
            normIsAuto = s.normIsAuto(month),
            rate = round2(s.hourlyRate(month)),
            salary = s.salary,
            bonus = bonus,
            bonusIsAuto = s.bonusIsAuto(month),
            extra = report.totalMoney,
            base = base,
            district = district,
            gross = gross,
            tax = tax,
            net = round2(gross - tax),
        )
    }
}
