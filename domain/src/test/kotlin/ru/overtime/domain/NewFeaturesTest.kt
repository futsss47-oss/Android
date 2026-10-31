package ru.overtime.domain

import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightHoursTest {
    private val mon = LocalDate.of(2026, 10, 26)

    private fun settings(on: Boolean) = Settings(
        salary = 16800.0, // ставка 100 ₽/ч при норме 168 ч (октябрь 2026)
        districtPercent = 0.0,
        nightEnabled = on,
        roundStepMinutes = 1,
        minOvertimeMinutes = 0,
    )

    @Test
    fun nightMinutesAfter22() {
        val spans = listOf(Span(mon.atTime(20, 0), mon.plusDays(1).atTime(1, 0), true))
        val s = settings(true)
        assertEquals(120, Calculator.nightRawMinutes(mon, spans, s))
        assertEquals(60, Calculator.nightRawMinutes(mon.plusDays(1), spans, s))
        val r = Calculator.report(mon, mon, spans, s)
        assertEquals(120, r.days.single().nightMinutes)
        assertEquals(100.0 * 2 * 0.2, r.days.single().nightPay, 0.001)
        assertEquals(40.0, r.totalNightPay, 0.001)
    }

    @Test
    fun nightOffByDefault() {
        val spans = listOf(Span(mon.atTime(20, 0), mon.atTime(23, 0), true))
        val r = Calculator.report(mon, mon, spans, settings(false))
        assertEquals(0, r.totalNightMinutes)
        assertEquals(0.0, r.totalNightPay)
    }

    @Test
    fun nightPayGoesToSummaryButNotBonusBase() {
        val month = YearMonth.of(2026, 10)
        val spans = listOf(Span(mon.atTime(20, 0), mon.atTime(23, 0), true))
        val s = settings(true).copy(bonusPercent = 100.0)
        val r = Calculator.report(month.atDay(1), month.atEndOfMonth(), spans, s)
        val sum = Calculator.monthSummary(month, r, s)
        assertEquals(20.0, sum.night, 0.001)
        assertEquals(sum.oklad + r.totalBonusBase, sum.bonusBase, 0.01)
        assertEquals(sum.oklad + sum.extra + sum.night + sum.bonus, sum.base, 0.01)
    }
}

class CalendarOverrideTest {
    @Test
    fun userHolidayReducesNormAndMakesDayOff() {
        val day = LocalDate.of(2027, 3, 10) // среда
        val month = YearMonth.from(day)
        val plain = Settings()
        val s = Settings(calendarOverrides = mapOf(day to DayKind.HOLIDAY))
        assertEquals(plain.normFor(month) - 8, s.normFor(month))
        assertEquals(plain.normDays(month) - 1, s.normDays(month))
        assertFalse(s.ruleFor(day).workday)
        assertTrue(s.isHoliday(day))
    }

    @Test
    fun userWorkdayAndShortDay() {
        val sat = LocalDate.of(2026, 10, 31)
        val s = Settings(calendarOverrides = mapOf(sat to DayKind.SHORT))
        assertEquals(7, s.dayHours(sat))
        assertTrue(s.ruleFor(sat).workday)
        assertEquals(LocalTime.of(16, 0), s.ruleFor(sat).end)
    }
}

class LeaveTest {
    private val month = YearMonth.of(2026, 10)

    @Test
    fun weekendsInsideVacationCountHolidaysDoNot() {
        // отмечены пн–пт 12–16 и пн–пт 19–23 октября: выходные 17–18 внутри отпуска
        val days = (12..16).map { month.atDay(it) } + (19..23).map { month.atDay(it) }
        val s = Settings(absences = days.associateWith { Absence.VACATION })
        assertEquals(12, Leave.leaveDates(month, s, Absence.VACATION).size)
        // 4 ноября — праздник, в отпуск не входит
        val nov = (2..6).map { LocalDate.of(2026, 11, it) }
        val s2 = Settings(absences = nov.associateWith { Absence.VACATION })
        assertEquals(4, Leave.leaveDates(YearMonth.of(2026, 11), s2, Absence.VACATION).size)
    }

    @Test
    fun vacationPayFromAverage() {
        val days = (12..16).map { month.atDay(it) }
        val s = Settings(absences = days.associateWith { Absence.VACATION })
        val pay = Leave.compute(month, s) { 58_600.0 }
        // 12 полных месяцев по 58 600: средний = 58 600 / 29,3 = 2000 ₽
        assertEquals(2000.0, pay.vacationAvgDaily, 0.01)
        assertEquals(5, pay.vacationDays)
        assertEquals(10_000.0, pay.vacationPay, 0.01)
        assertTrue(pay.vacationEstimated)
        val manual = Leave.compute(month, s.copy(vacationEarnings12m = 351_600.0)) { error("не нужно") }
        assertEquals(1000.0, manual.vacationAvgDaily, 0.01)
        assertFalse(manual.vacationEstimated)
    }

    @Test
    fun sickPayWithStazhAndLimits() {
        val days = (12..14).map { month.atDay(it) }
        val s = Settings(absences = days.associateWith { Absence.SICK }, sickPercent = 80.0)
        // 2 года по 73 000 в месяц: 1 752 000 / 730 = 2400 ₽, 80% = 1920 ₽
        val pay = Leave.compute(month, s) { 73_000.0 }
        assertEquals(3, pay.sickDays)
        assertEquals(1920.0, pay.sickDaily, 0.01)
        assertEquals(5760.0, pay.sickPay, 0.01)
        assertEquals(SickLimit.NONE, pay.sickLimit)
        // маленький заработок: минимум по МРОТ × районный
        val low = Leave.compute(month, s.copy(sickPercent = 100.0)) { 1000.0 }
        assertEquals(SickLimit.MIN_MROT, low.sickLimit)
        assertEquals(27_093.0 * 1.15 * 24 / 730, low.sickDaily, 0.01)
    }

    @Test
    fun monthCalcAddsLeaveToGross() {
        val days = (12..16).map { month.atDay(it) }
        val s = Settings(salary = 30_000.0, absences = days.associateWith { Absence.VACATION })
        val c = MonthCalculator.calc(month, emptyList(), s)
        assertTrue(c.summary.vacationPay > 0)
        assertEquals(c.summary.earningsForAverage + c.summary.vacationPay, c.summary.gross, 0.01)
        val none = MonthCalculator.calc(month, emptyList(), Settings(salary = 30_000.0))
        assertTrue(none.leave.isEmpty)
        assertEquals(none.summary.earningsForAverage, none.summary.gross, 0.01)
    }
}
