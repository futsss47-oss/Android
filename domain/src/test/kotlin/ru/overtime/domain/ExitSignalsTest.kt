package ru.overtime.domain

import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExitSignalsTest {
    // 2026-10-26 понедельник, смена 8:00–17:00
    private val mon = LocalDate.of(2026, 10, 26)
    private fun at(h: Int, m: Int = 0): LocalDateTime = mon.atTime(h, m)

    private class Gateway : SessionGateway {
        val starts = mutableListOf<LocalDateTime>()
        val ends = mutableMapOf<Long, LocalDateTime?>()
        override fun open(start: LocalDateTime): Long {
            starts += start
            ends[starts.size.toLong()] = null
            return starts.size.toLong()
        }
        override fun close(id: Long, end: LocalDateTime) { ends[id] = end }
        override fun reopen(id: Long) { ends[id] = null }
    }

    private class Store : StateStore {
        var st = TrackerState()
        override fun load() = st
        override fun save(state: TrackerState) { st = state }
    }

    private fun engine(): Pair<OvertimeEngine, Gateway> {
        val g = Gateway()
        return OvertimeEngine({ Settings() }, g, Store()) to g
    }

    @Test
    fun endIsFirstExitSignalNotLastInsideReading() {
        val (e, g) = engine()
        e.onReading(at(8), Reading.INSIDE)
        e.onReading(at(17, 5), Reading.INSIDE)
        e.onReading(at(18, 0), Reading.INSIDE) // последний редкий замер
        e.onExitHint(at(18, 12)) // пропал Wi‑Fi / пошёл к проходной
        e.onReading(at(18, 13), Reading.OUTSIDE, confirmNow = true) // точный GPS: уже за зоной
        assertEquals(at(18, 12), g.ends[1L])
        assertNull(e.state().exitHint)
    }

    @Test
    fun insideReadingCancelsHint() {
        val (e, g) = engine()
        e.onReading(at(8), Reading.INSIDE)
        e.onReading(at(17, 5), Reading.INSIDE)
        e.onExitHint(at(17, 30)) // ходил по цеху
        e.onReading(at(17, 31), Reading.INSIDE) // всё ещё на работе
        assertNull(e.state().exitHint)
        e.onReading(at(19, 0), Reading.OUTSIDE)
        e.onReading(at(19, 15), Reading.OUTSIDE) // подтверждено двумя замерами, сигнала не было
        assertEquals(at(17, 31), g.ends[1L])
    }

    @Test
    fun earliestHintWinsAndConfirmNeedsOneReliableReading() {
        val (e, g) = engine()
        e.onReading(at(8), Reading.INSIDE)
        e.onReading(at(17, 5), Reading.INSIDE)
        e.onExitHint(at(18, 20))
        e.onExitHint(at(18, 10)) // более ранний сигнал
        e.onExitHint(at(18, 30)) // поздний не затирает
        e.onReading(at(18, 32), Reading.OUTSIDE) // обычный замер: ждём второй
        assertNull(g.ends[1L])
        e.onReading(at(18, 33), Reading.OUTSIDE, confirmNow = true)
        assertEquals(at(18, 10), g.ends[1L])
    }

    @Test
    fun hintIgnoredWhenNotAtWork() {
        val (e, _) = engine()
        e.onExitHint(at(18))
        assertNull(e.state().exitHint)
    }

    @Test
    fun nextWindowStartIsShiftEndOrWeekend() {
        val s = Settings()
        assertEquals(at(17), Windows.nextStart(at(9), s))
        // после 17:00 в понедельник следующее окно — вторник 17:00
        assertEquals(mon.plusDays(1).atTime(17, 0), Windows.nextStart(at(17, 30), s))
        // в пятницу вечером следующее — суббота 0:00 (весь выходной сверхурочный)
        val fri = LocalDate.of(2026, 10, 30)
        assertEquals(fri.plusDays(1).atStartOfDay(), Windows.nextStart(fri.atTime(18, 0), s))
        // 3 ноября 2026 сокращённый день: окно с 16:00
        assertEquals(LocalDate.of(2026, 11, 3).atTime(16, 0), Windows.nextStart(LocalDate.of(2026, 11, 3).atTime(9, 0), s))
    }
}
