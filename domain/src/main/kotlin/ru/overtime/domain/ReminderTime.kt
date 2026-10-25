package ru.overtime.domain

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/** Когда показывать напоминание «конец месяца»: последний день месяца в заданное время. */
object ReminderTime {
    val DEFAULT_TIME: LocalTime = LocalTime.of(18, 0)

    fun next(now: LocalDateTime, time: LocalTime = DEFAULT_TIME): LocalDateTime {
        val month = YearMonth.from(now)
        val thisMonth = month.atEndOfMonth().atTime(time)
        return if (thisMonth.isAfter(now)) thisMonth else month.plusMonths(1).atEndOfMonth().atTime(time)
    }
}
