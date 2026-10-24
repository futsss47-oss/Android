package ru.overtime.domain

import java.time.LocalDate
import java.time.LocalDateTime

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
    val money: Double,
)

data class Report(
    val days: List<DayResult>,
    val totalMinutes: Int,
    val totalMoney: Double,
    /** Если формула некорректна, здесь текст ошибки, а деньги равны нулю. */
    val formulaError: String?,
)

object Calculator {

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

    fun money(date: LocalDate, countedMinutes: Int, s: Settings): Double {
        if (countedMinutes <= 0) return 0.0
        val vars = mapOf(
            "h" to countedMinutes / 60.0,
            "m" to countedMinutes.toDouble(),
            "rate" to s.rate,
            "salary" to s.salary,
            "norm" to s.normHours,
            "weekend" to if (s.rule(date.dayOfWeek).workday) 0.0 else 1.0,
            "dow" to date.dayOfWeek.value.toDouble(),
        )
        val raw = Formula.evaluate(s.formula, vars)
        return Math.round(raw * 100.0) / 100.0
    }

    /** Отчёт за период [from]..[to] включительно. Дни без сверхурочной в отчёт не попадают. */
    fun report(from: LocalDate, to: LocalDate, spans: List<Span>, s: Settings): Report {
        var formulaError: String? = null
        val days = mutableListOf<DayResult>()
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
                days += DayResult(date, raw, counted, !s.rule(date.dayOfWeek).workday, money)
            }
            date = date.plusDays(1)
        }
        return Report(days, days.sumOf { it.countedMinutes }, days.sumOf { it.money }, formulaError)
    }
}
