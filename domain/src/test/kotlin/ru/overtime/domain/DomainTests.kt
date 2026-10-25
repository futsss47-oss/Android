package ru.overtime.domain

import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 2026-10-26 понедельник
private val MON = LocalDate.of(2026, 10, 26)
private val TUE = MON.plusDays(1)
private val SAT = LocalDate.of(2026, 10, 31)

private fun at(d: LocalDate, h: Int, m: Int = 0): LocalDateTime = d.atTime(h, m)

/** Настройки с часовой ставкой ровно [rate] в октябре 2026: оклад rate*100 при норме 100 ч, без районного коэффициента. */
private fun pay(rate: Double) = Settings(
    salary = rate * 100,
    districtPercent = 0.0,
    monthOverrides = mapOf("2026-10" to MonthOverride(norm = 100.0)),
)

private class FakeGateway : SessionGateway {
    data class Row(val id: Long, var start: LocalDateTime, var end: LocalDateTime?)

    val rows = mutableListOf<Row>()
    override fun open(start: LocalDateTime): Long {
        val id = rows.size + 1L
        rows += Row(id, start, null)
        return id
    }

    override fun close(id: Long, end: LocalDateTime) {
        rows.first { it.id == id }.end = end
    }

    override fun reopen(id: Long) {
        rows.first { it.id == id }.end = null
    }
}

private class MemStore : StateStore {
    var st = TrackerState()
    override fun load() = st
    override fun save(state: TrackerState) {
        st = state
    }
}

class WindowsTest {
    @Test
    fun weekdayAfterShift() {
        val s = Settings()
        val w = Windows.forDate(MON, s).last()
        assertEquals(at(MON, 17), w.start)
        assertEquals(TUE.atStartOfDay(), w.end)
    }

    @Test
    fun weekdayBeforeAndAfter() {
        val s = Settings(mode = OvertimeMode.BEFORE_AND_AFTER)
        val w = Windows.forDate(MON, s)
        assertEquals(2, w.size)
        assertEquals(MON.atStartOfDay(), w[0].start)
        assertEquals(at(MON, 8), w[0].end)
    }

    @Test
    fun weekendWholeDayOrNothing() {
        assertEquals(1, Windows.forDate(SAT, Settings()).size)
        assertTrue(Windows.forDate(SAT, Settings(weekendAllOvertime = false)).isEmpty())
    }

    @Test
    fun graceShiftsWindowStart() {
        val s = Settings(graceAfterShiftMinutes = 15)
        assertEquals(at(MON, 17, 15), Windows.forDate(MON, s).last().start)
        assertNull(Windows.containing(at(MON, 17, 10), s))
        assertNotNull(Windows.containing(at(MON, 17, 15), s))
    }

    @Test
    fun overnightTailOnlyInAfterShiftMode() {
        val tail = Windows.forDate(TUE, Settings())
        assertEquals(2, tail.size)
        assertNotNull(tail[0].needsPresenceBefore)
        // в понедельник после выходных (воскресенье весь день сверхурочный) хвост тоже есть
        assertEquals(2, Windows.forDate(MON, Settings()).size)
        // если выходные не считаются, хвоста в понедельник нет
        assertEquals(1, Windows.forDate(MON, Settings(weekendAllOvertime = false)).size)
    }
}

class CalculatorTest {
    private fun auto(a: LocalDateTime, b: LocalDateTime) = Span(a, b, manual = false)

    @Test
    fun clipsAutoSessionToWindow() {
        val s = Settings(mode = OvertimeMode.BEFORE_AND_AFTER)
        val raw = Calculator.rawMinutes(MON, listOf(auto(at(MON, 6), at(MON, 9))), s)
        assertEquals(120, raw) // 6:00-8:00, а 8:00-9:00 это обычная смена
    }

    @Test
    fun afterShiftModeIgnoresMorning() {
        val s = Settings(weekendAllOvertime = false)
        val raw = Calculator.rawMinutes(MON, listOf(auto(at(MON, 6), at(MON, 9))), s)
        assertEquals(0, raw)
    }

    @Test
    fun manualSessionIsNotClipped() {
        val raw = Calculator.rawMinutes(MON, listOf(Span(at(MON, 10), at(MON, 11), true)), Settings())
        assertEquals(60, raw)
    }

    @Test
    fun overlapDoesNotDoubleCount() {
        val spans = listOf(auto(at(MON, 17), at(MON, 19)), Span(at(MON, 18), at(MON, 20), true))
        assertEquals(180, Calculator.rawMinutes(MON, spans, Settings()))
    }

    @Test
    fun sessionAcrossMidnightIsSplitByDate() {
        val spans = listOf(Span(at(MON, 23), at(TUE, 1), true))
        assertEquals(60, Calculator.rawMinutes(MON, spans, Settings()))
        assertEquals(60, Calculator.rawMinutes(TUE, spans, Settings()))
    }

    @Test
    fun roundingAndThreshold() {
        val s = Settings(roundStepMinutes = 10, minOvertimeMinutes = 10)
        assertEquals(0, Calculator.counted(9, s))
        assertEquals(10, Calculator.counted(10, s))
        assertEquals(160, Calculator.counted(155, s)) // ровно середина округляется вверх
        assertEquals(150, Calculator.counted(154, s))
        assertEquals(150, Calculator.counted(155, s.copy(roundMode = RoundMode.DOWN)))
        assertEquals(160, Calculator.counted(151, s.copy(roundMode = RoundMode.UP)))
        assertEquals(0, Calculator.counted(25, s.copy(minOvertimeMinutes = 30)))
    }

    @Test
    fun moneyDefaultFormula() {
        val s = pay(100.0)
        // будни, 3 часа: 100 * (2*1.5 + 1*2) = 500
        assertEquals(500.0, Calculator.money(MON, 180, s))
        // выходной, 3 часа: 100 * 3 * 2 = 600
        assertEquals(600.0, Calculator.money(SAT, 180, s))
    }

    @Test
    fun reportTotalsAndFormulaError() {
        val spans = listOf(auto(at(MON, 17), at(MON, 20)), auto(at(SAT, 10), at(SAT, 12)))
        val r = Calculator.report(MON, SAT, spans, pay(100.0))
        assertEquals(2, r.days.size)
        assertEquals(300, r.totalMinutes)
        assertEquals(500.0 + 400.0, r.totalMoney)
        assertNull(r.formulaError)

        // сломана только формула рабочих дней: будни дают ошибку, выходной считается по своей формуле
        val bad = Calculator.report(MON, SAT, spans, pay(100.0).copy(formula = "rate * ("))
        assertNotNull(bad.formulaError)
        assertEquals(400.0, bad.totalMoney)
    }

    @Test
    fun weekendUsesOwnFormula() {
        val s = pay(100.0).copy(
            formula = "rate * h * 1.5",
            weekendFormula = "rate * h * 3 + 50",
        )
        assertEquals(450.0, Calculator.money(MON, 180, s))  // будни: 100 * 3 * 1.5
        assertEquals(950.0, Calculator.money(SAT, 180, s))  // выходной: 100 * 3 * 3 + 50
        // выходной по графику, даже если это будний день недели
        val monOff = s.copy(days = s.days + (DayOfWeek.MONDAY to DayRule(false)))
        assertEquals(950.0, Calculator.money(MON, 180, monOff))
    }

    @Test
    fun defaultFormulasAreDoublePayOnWeekends() {
        val s = pay(200.0)
        // выходной: час в двойном размере
        assertEquals(400.0, Calculator.money(SAT, 60, s))
        // будни: первые два часа ×1.5, третий ×2
        assertEquals(200.0 * (2 * 1.5 + 1 * 2), Calculator.money(MON, 180, s))
    }
}

class FormulaTest {
    private val vars = mapOf("h" to 3.0, "rate" to 100.0, "weekend" to 0.0)

    private fun ev(src: String) = Formula.evaluate(src, vars)

    @Test
    fun precedenceAndPower() {
        assertEquals(14.0, ev("2 + 3 * 4"))
        assertEquals(20.0, ev("(2 + 3) * 4"))
        assertEquals(512.0, ev("2 ^ 3 ^ 2")) // правоассоциативно
        assertEquals(-4.0, ev("-2 ^ 2")) // как в математике: минус применяется после степени
        assertEquals(0.5, ev("2 ^ -1"))
        assertEquals(1.0, ev("7 % 3"))
    }

    @Test
    fun variablesAndFunctions() {
        assertEquals(300.0, ev("h * rate"))
        assertEquals(2.0, ev("min(h, 2)"))
        assertEquals(5.0, ev("max(1; 5; 3)"))
        assertEquals(3.14, ev("round(3.14159, 2)"))
        assertEquals(4.0, ev("ceil(3.2)"))
        assertEquals(3.0, ev("sqrt(9)"))
    }

    @Test
    fun conditionals() {
        assertEquals(10.0, ev("if(h > 2, 10, 20)"))
        assertEquals(20.0, ev("if(h > 5 || weekend, 10, 20)"))
        assertEquals(1.0, ev("h >= 3 && rate == 100"))
        assertEquals(1.0, ev("!weekend"))
        // ленивая ветка: деления на ноль нет, потому что условие её не выбирает
        assertEquals(1.0, ev("if(1, 1, 1 / 0)"))
    }

    @Test
    fun errors() {
        assertFailsWith<FormulaException> { ev("") }
        assertFailsWith<FormulaException> { ev("2 +") }
        assertFailsWith<FormulaException> { ev("(2 + 3") }
        assertFailsWith<FormulaException> { ev("foo * 2") }
        assertFailsWith<FormulaException> { ev("bar(2)") }
        assertFailsWith<FormulaException> { ev("1 / 0") }
        assertFailsWith<FormulaException> { ev("min(1)") }
        assertFailsWith<FormulaException> { ev("2 = 2") }
        assertFailsWith<FormulaException> { ev("2 3") }
    }

    @Test
    fun validate() {
        assertNull(Formula.validate(Settings.DEFAULT_FORMULA))
        assertNull(Formula.validate(Settings.DEFAULT_WEEKEND_FORMULA))
        assertNull(Formula.validate("salary / norm * 1.5 * h"))
        assertNotNull(Formula.validate("rate * (h"))
        assertNotNull(Formula.validate("hours * 2"))
    }
}

class GeoTest {
    @Test
    fun distanceOneDegreeLatitude() {
        val d = Geo.distanceMeters(56.0, 60.0, 57.0, 60.0)
        assertTrue(d in 111_000.0..111_400.0, "d=$d")
    }

    @Test
    fun classifyWithHysteresis() {
        val s = Settings() // 100 / 200 м, точность до 100 м
        assertEquals(Reading.INSIDE, Geo.classify(50.0, 20.0, false, s))
        assertEquals(Reading.OUTSIDE, Geo.classify(250.0, 20.0, true, s))
        assertEquals(Reading.INSIDE, Geo.classify(150.0, 20.0, true, s))   // серая зона: как было
        assertEquals(Reading.OUTSIDE, Geo.classify(150.0, 20.0, false, s))
        assertEquals(Reading.UNRELIABLE, Geo.classify(50.0, 500.0, false, s))
    }
}

class EngineTest {
    private fun newEngine(s: Settings = Settings()): Triple<OvertimeEngine, FakeGateway, MemStore> {
        val g = FakeGateway()
        val st = MemStore()
        return Triple(OvertimeEngine({ s }, g, st), g, st)
    }

    @Test
    fun opensAtShiftEndAndIgnoresSingleGlitch() {
        val (e, g, _) = newEngine()
        e.onReading(at(MON, 8, 0), Reading.INSIDE)
        e.onReading(at(MON, 16, 50), Reading.INSIDE)
        assertTrue(g.rows.isEmpty())
        e.onReading(at(MON, 17, 5), Reading.INSIDE)
        assertEquals(1, g.rows.size)
        assertEquals(at(MON, 17), g.rows[0].start) // начало окна, а не момент замера

        e.onReading(at(MON, 17, 15), Reading.INSIDE)
        e.onReading(at(MON, 17, 25), Reading.OUTSIDE) // один замер вне зоны
        assertNull(g.rows[0].end)
        e.onReading(at(MON, 17, 35), Reading.INSIDE) // следующий снова на работе: глюк
        assertNull(g.rows[0].end)
        assertEquals(0, e.state().outsideStreak)
    }

    @Test
    fun closesAfterTwoOutsideAtLastInsideTime() {
        val (e, g, _) = newEngine()
        e.onReading(at(MON, 8), Reading.INSIDE)
        e.onReading(at(MON, 17, 5), Reading.INSIDE)
        e.onReading(at(MON, 18, 5), Reading.INSIDE)
        e.onReading(at(MON, 18, 15), Reading.OUTSIDE)
        e.onReading(at(MON, 18, 25), Reading.OUTSIDE)
        assertEquals(at(MON, 18, 5), g.rows[0].end)
        assertFalse(e.state().atWork)
    }

    @Test
    fun unreliableReadingsAreIgnored() {
        val (e, g, _) = newEngine()
        e.onReading(at(MON, 8), Reading.INSIDE)
        e.onReading(at(MON, 17, 5), Reading.INSIDE)
        e.onReading(at(MON, 17, 15), Reading.OUTSIDE)
        e.onReading(at(MON, 17, 25), Reading.UNRELIABLE)
        e.onReading(at(MON, 17, 35), Reading.UNRELIABLE)
        assertNull(g.rows[0].end)
        assertEquals(1, e.state().outsideStreak)
    }

    @Test
    fun shortAbsenceIsMergedAndCounted() {
        val (e, g, _) = newEngine()
        e.onReading(at(MON, 8), Reading.INSIDE)
        e.onReading(at(MON, 17, 5), Reading.INSIDE)
        e.onReading(at(MON, 17, 45), Reading.INSIDE)
        e.onReading(at(MON, 18, 5), Reading.OUTSIDE)
        e.onReading(at(MON, 18, 15), Reading.OUTSIDE) // закрыто на 17:45
        assertEquals(at(MON, 17, 45), g.rows[0].end)
        e.onReading(at(MON, 18, 40), Reading.INSIDE) // 55 минут спустя: склейка
        assertEquals(1, g.rows.size)
        assertNull(g.rows[0].end)
        assertEquals(at(MON, 17), g.rows[0].start)
        e.onReading(at(MON, 19, 0), Reading.OUTSIDE)
        e.onReading(at(MON, 19, 10), Reading.OUTSIDE)
        assertEquals(at(MON, 18, 40), g.rows[0].end)
        val minutes = Calculator.rawMinutes(
            MON, listOf(Span(g.rows[0].start, g.rows[0].end!!, false)), Settings(),
        )
        assertEquals(100, minutes) // 17:00-18:40, время магазина внутри засчитано
    }

    @Test
    fun longAbsenceStartsNewSession() {
        val (e, g, _) = newEngine()
        e.onReading(at(MON, 8), Reading.INSIDE)
        e.onReading(at(MON, 17, 5), Reading.INSIDE)
        e.onReading(at(MON, 17, 15), Reading.OUTSIDE)
        e.onReading(at(MON, 17, 25), Reading.OUTSIDE)
        e.onReading(at(MON, 20, 0), Reading.INSIDE) // через 2+ часа
        assertEquals(2, g.rows.size)
        assertEquals(at(MON, 20), g.rows[1].start) // пришёл в 20:00, раньше не считаем
    }

    @Test
    fun beforeAndAfterClosesAtShiftStart() {
        val (e, g, _) = newEngine(Settings(mode = OvertimeMode.BEFORE_AND_AFTER))
        e.onReading(at(MON, 6), Reading.INSIDE)
        assertEquals(1, g.rows.size)
        assertEquals(at(MON, 6), g.rows[0].start)
        e.onReading(at(MON, 7, 50), Reading.INSIDE)
        e.onReading(at(MON, 8, 5), Reading.INSIDE)
        assertEquals(at(MON, 8), g.rows[0].end)
        assertEquals(1, g.rows.size) // в рабочее время новая сессия не открывается
    }

    @Test
    fun overnightContinuesOnlyWhenPresentSinceEvening() {
        val (e, g, _) = newEngine()
        e.onReading(at(MON, 8), Reading.INSIDE)
        e.onReading(at(MON, 17, 5), Reading.INSIDE)
        e.onReading(at(MON, 23, 50), Reading.INSIDE)
        e.onReading(at(TUE, 0, 10), Reading.INSIDE) // сессию закрыло по концу окна в полночь
        assertEquals(TUE.atStartOfDay(), g.rows[0].end)
        assertEquals(2, g.rows.size)
        assertEquals(TUE.atStartOfDay(), g.rows[1].start)

        // а пришедший в 6 утра в этом режиме ничего не получает
        val (e2, g2, _) = newEngine()
        e2.onReading(at(TUE, 6), Reading.INSIDE)
        assertTrue(g2.rows.isEmpty())
    }

    @Test
    fun weekendSessionStartsOnArrival() {
        val (e, g, _) = newEngine()
        e.onReading(at(SAT, 10), Reading.INSIDE)
        assertEquals(at(SAT, 10), g.rows[0].start)
    }

    @Test
    fun singleOutsideWithoutSessionDoesNotLosePresence() {
        val (e, _, _) = newEngine()
        e.onReading(at(MON, 9), Reading.INSIDE)
        e.onReading(at(MON, 9, 10), Reading.OUTSIDE)
        assertTrue(e.state().atWork) // один выброс не должен «выключать» учёт
        e.onReading(at(MON, 9, 20), Reading.OUTSIDE)
        assertFalse(e.state().atWork)
    }

    @Test
    fun settingsValidation() {
        assertTrue(Settings().validationErrors().isEmpty())
        val bad = Settings(days = Settings.defaultDays() + (DayOfWeek.MONDAY to DayRule(true, LocalTime.of(18, 0), LocalTime.of(9, 0))))
        assertTrue(bad.validationErrors().isNotEmpty())
        assertTrue(Settings(enterRadiusM = 300, exitRadiusM = 200).validationErrors().isNotEmpty())
    }
}

class CalendarTest {
    private fun norms(year: Int) = (1..12).map { ProductionCalendar.normHours(YearMonth.of(year, it)) }

    @Test
    fun norms2025MatchPayslips() {
        val n = norms(2025)
        assertEquals(159, n[2], "март 2025, листок 159 ч")
        assertEquals(142, n[5], "июнь 2025, листок 142 ч")
        assertEquals(184, n[6], "июль 2025, листок 184 ч")
        assertEquals(176, n[8], "сентябрь 2025, листок 176 ч")
        assertEquals(listOf(136, 160, 159, 175, 144, 142, 184, 168, 176, 175, 151, 176), n)
        assertEquals(1946, n.sum()) // федеральные 1972 минус байрамы и День Республики РБ
    }

    @Test
    fun workDaysMatchPayslips() {
        assertEquals(20, ProductionCalendar.workDays(YearMonth.of(2025, 3)))
        assertEquals(18, ProductionCalendar.workDays(YearMonth.of(2025, 6)))
        assertEquals(23, ProductionCalendar.workDays(YearMonth.of(2025, 7)))
        assertEquals(22, ProductionCalendar.workDays(YearMonth.of(2025, 9)))
    }

    @Test
    fun norms2026() {
        val n = norms(2026)
        assertEquals(listOf(120, 152, 159, 175, 142, 167, 184, 168, 176, 168, 159, 176), n)
        assertEquals(1946, n.sum())
        assertEquals(18, ProductionCalendar.workDays(YearMonth.of(2026, 5))) // как в календаре Башкирии
    }

    @Test
    fun norms2027FederalPlusRepublicDay() {
        val n = norms(2027)
        assertEquals(1964, n.sum()) // 1972 минус 8 за 11 октября
        val q = { from: Int -> (from..from + 2).sumOf { ProductionCalendar.workDays(YearMonth.of(2027, it)) } }
        assertEquals(56, q(1))
        assertEquals(62, q(4))
        assertEquals(66, q(7))
        assertEquals(62, q(10)) // федеральные 63 минус День Республики
        assertNotNull(ProductionCalendar.note(2027))
    }

    @Test
    fun republicDayRule() {
        assertEquals(0, ProductionCalendar.hours(LocalDate.of(2025, 10, 13))) // 11.10 суббота -> понедельник
        assertEquals(7, ProductionCalendar.hours(LocalDate.of(2025, 10, 10)))
        assertEquals(0, ProductionCalendar.hours(LocalDate.of(2026, 10, 12))) // 11.10 воскресенье -> понедельник
        assertEquals(8, ProductionCalendar.hours(LocalDate.of(2026, 10, 9)))
    }

    @Test
    fun holidaysAreWeekendForPay() {
        val s = Settings()
        assertFalse(s.ruleFor(LocalDate.of(2026, 11, 4)).workday)   // День народного единства, среда
        assertFalse(s.ruleFor(LocalDate.of(2026, 5, 27)).workday)   // Курбан-байрам, среда
        assertTrue(s.ruleFor(LocalDate.of(2026, 11, 5)).workday)
        // перенесённая рабочая суббота 1 ноября 2025: рабочий день, сокращённый на час
        val sat = s.ruleFor(LocalDate.of(2025, 11, 1))
        assertTrue(sat.workday)
        assertEquals(LocalTime.of(16, 0), sat.end)
        // предпраздничный день заканчивается на час раньше
        assertEquals(LocalTime.of(16, 0), s.ruleFor(LocalDate.of(2026, 5, 26)).end)
        // обычный день без изменений
        assertEquals(LocalTime.of(17, 0), s.ruleFor(LocalDate.of(2026, 10, 14)).end)
    }

    @Test
    fun overtimeAfterShortDayStartsEarlier() {
        val s = Settings()
        val day = LocalDate.of(2026, 5, 26) // предпраздничный: смена до 16:00
        val raw = Calculator.rawMinutes(day, listOf(Span(day.atTime(8, 0), day.atTime(17, 0), false)), s)
        assertEquals(60, raw)
    }

    @Test
    fun holidayWorkIsPaidAsWeekend() {
        val s = pay(100.0).copy(monthOverrides = mapOf("2026-11" to MonthOverride(norm = 100.0)))
        val holiday = LocalDate.of(2026, 11, 4)
        assertEquals(100.0 * 8 * 2, Calculator.money(holiday, 480, s)) // выходная формула x2
    }

    @Test
    fun estimatedYearsAreFlagged() {
        assertTrue(ProductionCalendar.isEstimated(2030))
        assertFalse(ProductionCalendar.isEstimated(2026))
        val norm = ProductionCalendar.normHours(YearMonth.of(2030, 3))
        assertTrue(norm in 150..180, "норма $norm")
        assertNotNull(ProductionCalendar.note(2030))
    }
}

class MonthSummaryTest {
    @Test
    fun september2025LikePayslip() {
        // ставка 181.82 (176 ч), 21 ч сверхурочных (14 ч x1.5 + 7 ч x2) и 22 ч выходных, как в листке за сентябрь 2025
        val s = Settings(salary = 32000.0, bonus = 67266.0)
        val ym = YearMonth.of(2025, 9)
        val rate = s.hourlyRate(ym)
        assertEquals(176.0, s.normFor(ym))
        assertEquals(6363.64, Calculator.money(LocalDate.of(2025, 9, 1), 0, s) + Math.round(rate * 35 * 100) / 100.0, 0.001)
        assertEquals(8000.0, Math.round(rate * 22 * 2 * 100) / 100.0)
        // в листке сверхурочные отрезаны до копейки (6363.63), берём суммы ровно как в листке
        val report = Report(emptyList(), 0, 6363.63 + 8000.0, 0.0, null)
        val sum = Calculator.monthSummary(ym, report, s)
        assertEquals(113629.63, sum.base, 0.011)
        assertEquals(17044.44, sum.district, 0.011)
        assertEquals(130674.07, sum.gross, 0.011)
        assertEquals(16988.0, sum.tax)
        assertEquals(113686.07, sum.net, 0.011)
    }

    @Test
    fun monthOverridesWin() {
        val s = Settings(salary = 32000.0, bonus = 50000.0).copy(
            monthOverrides = mapOf("2026-10" to MonthOverride(norm = 160.0, bonus = 60000.0)),
        )
        val oct = YearMonth.of(2026, 10)
        assertEquals(160.0, s.normFor(oct))
        assertFalse(s.normIsAuto(oct))
        assertEquals(60000.0, s.bonusFor(oct))
        assertEquals(200.0, s.hourlyRate(oct))
        val nov = YearMonth.of(2026, 11)
        assertEquals(159.0, s.normFor(nov))
        assertTrue(s.normIsAuto(nov))
        assertEquals(50000.0, s.bonusFor(nov))
    }

    @Test
    fun reportAppliesDistrictCoefficient() {
        val s = pay(100.0).copy(districtPercent = 15.0)
        val spans = listOf(Span(LocalDateTime.of(2026, 10, 26, 17, 0), LocalDateTime.of(2026, 10, 26, 20, 0), false))
        val r = Calculator.report(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26), spans, s)
        assertEquals(500.0, r.totalMoney)
        assertEquals(575.0, r.totalGross)
    }
}

class LunchTest {
    private fun auto(a: LocalDateTime, b: LocalDateTime) = Span(a, b, manual = false)

    @Test
    fun weekendDayMinusLunch() {
        val sat = SAT
        val spans = listOf(auto(sat.atTime(8, 0), sat.atTime(17, 0)))
        assertEquals(480, Calculator.rawMinutes(sat, spans, Settings()))                       // 9 ч - 1 ч обеда
        assertEquals(540, Calculator.rawMinutes(sat, spans, Settings(lunchEnabled = false)))
    }

    @Test
    fun partialLunchOverlap() {
        val sat = SAT
        assertEquals(240, Calculator.rawMinutes(sat, listOf(auto(sat.atTime(8, 0), sat.atTime(12, 30))), Settings()))
        assertEquals(180, Calculator.rawMinutes(sat, listOf(auto(sat.atTime(14, 0), sat.atTime(17, 0))), Settings()))
    }

    @Test
    fun customLunchTime() {
        val sat = SAT
        val s = Settings(lunchStart = LocalTime.of(11, 30), lunchEnd = LocalTime.of(12, 0))
        assertEquals(510, Calculator.rawMinutes(sat, listOf(auto(sat.atTime(8, 0), sat.atTime(17, 0))), s))
    }

    @Test
    fun weekdayAfterShiftUnaffected() {
        val spans = listOf(auto(at(MON, 8), at(MON, 19)))
        assertEquals(120, Calculator.rawMinutes(MON, spans, Settings())) // 17:00-19:00, обед внутри смены
    }

    @Test
    fun manualWeekendAlsoMinusLunch() {
        val sat = SAT
        assertEquals(480, Calculator.rawMinutes(sat, listOf(Span(sat.atTime(8, 0), sat.atTime(17, 0), true)), Settings()))
    }

    @Test
    fun invalidLunchIsReported() {
        val bad = Settings(lunchStart = LocalTime.of(13, 0), lunchEnd = LocalTime.of(12, 0))
        assertTrue(bad.validationErrors().isNotEmpty())
        assertTrue(bad.copy(lunchEnabled = false).validationErrors().isEmpty())
    }
}
