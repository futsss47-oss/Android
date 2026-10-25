package ru.patients.core

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object StoreCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(store: Store): ByteArray = json.encodeToString(Store.serializer(), store).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Store = json.decodeFromString(Store.serializer(), bytes.toString(Charsets.UTF_8))
}

/** Слияние двух копий базы (телефон и Яндекс Диск): по каждой записи побеждает более свежая правка. */
object Merge {
    fun merge(local: Store, remote: Store): Store = Store(
        version = maxOf(local.version, remote.version),
        patients = mergeList(local.patients, remote.patients, { it.id }, { it.updatedAt }, { it.deleted }),
        reminders = mergeList(local.reminders, remote.reminders, { it.id }, { it.updatedAt }, { it.deleted }),
    )

    private fun <T> mergeList(
        a: List<T>,
        b: List<T>,
        id: (T) -> String,
        updated: (T) -> Long,
        deleted: (T) -> Boolean,
    ): List<T> {
        val result = LinkedHashMap<String, T>()
        for (x in a) result[id(x)] = x
        for (y in b) {
            val x = result[id(y)]
            result[id(y)] = when {
                x == null -> y
                updated(y) > updated(x) -> y
                updated(y) < updated(x) -> x
                deleted(y) && !deleted(x) -> y // при равном времени удаление важнее
                else -> x
            }
        }
        return result.values.toList()
    }
}

object Schedule {
    /** Следующий срок повторяющегося напоминания: шагаем от прошлого срока, пока не окажемся в будущем. */
    fun nextOccurrence(
        atMillis: Long,
        unit: RepeatUnit,
        every: Int,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (unit == RepeatUnit.NONE) return null
        val n = every.coerceAtLeast(1).toLong()
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        var k = 1L
        while (true) {
            val next = when (unit) {
                RepeatUnit.DAYS -> start.plusDays(n * k)
                RepeatUnit.WEEKS -> start.plusWeeks(n * k)
                RepeatUnit.MONTHS -> start.plusMonths(n * k) // от исходной даты, чтобы 31-е не «съезжало»
                RepeatUnit.NONE -> return null
            }
            val ms = next.atZone(zone).toInstant().toEpochMilli()
            if (ms > nowMillis) return ms
            k++
            if (k > 100_000) return null
        }
    }

    /**
     * Отметка «выполнено». Разовое напоминание закрывается,
     * повторяющееся переносится на следующий срок и остаётся активным.
     */
    fun markDone(r: Reminder, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Reminder {
        val next = nextOccurrence(r.atMillis, r.repeatUnit, r.repeatEvery, nowMillis, zone)
        return if (next != null) {
            r.copy(atMillis = next, done = false, doneAt = nowMillis, updatedAt = nowMillis)
        } else {
            r.copy(done = true, doneAt = nowMillis, updatedAt = nowMillis)
        }
    }

    enum class Kind { DUE, ADVANCE }

    data class Event(val reminderId: String, val kind: Kind, val atMillis: Long)

    /** Ближайшие будущие уведомления по всей базе, по времени. */
    fun upcomingEvents(store: Store, nowMillis: Long, limit: Int): List<Event> {
        val events = ArrayList<Event>()
        for (r in store.visibleReminders()) {
            if (r.done) continue
            if (r.atMillis > nowMillis) events += Event(r.id, Kind.DUE, r.atMillis)
            if (r.advanceMinutes > 0) {
                val adv = r.atMillis - r.advanceMinutes * 60_000L
                if (adv > nowMillis) events += Event(r.id, Kind.ADVANCE, adv)
            }
        }
        return events.sortedBy { it.atMillis }.take(limit)
    }
}

object Texts {
    fun repeat(unit: RepeatUnit, every: Int): String {
        if (unit == RepeatUnit.NONE) return "Без повтора"
        val n = every.coerceAtLeast(1)
        if (n == 1) {
            return when (unit) {
                RepeatUnit.DAYS -> "Каждый день"
                RepeatUnit.WEEKS -> "Каждую неделю"
                else -> "Каждый месяц"
            }
        }
        val word = when (unit) {
            RepeatUnit.DAYS -> plural(n, "день", "дня", "дней")
            RepeatUnit.WEEKS -> plural(n, "неделю", "недели", "недель")
            else -> plural(n, "месяц", "месяца", "месяцев")
        }
        // «Каждые 21 день», но «Каждые 2 месяца»: для 21, 31… форма «каждый»
        if (n % 10 == 1 && n % 100 != 11) {
            return if (unit == RepeatUnit.WEEKS) "Каждую $n $word" else "Каждый $n $word"
        }
        return "Каждые $n $word"
    }

    fun advance(minutes: Int): String = when {
        minutes <= 0 -> "Без предупреждения"
        minutes % 1440 == 0 -> "За ${minutes / 1440} ${plural(minutes / 1440, "день", "дня", "дней")}"
        minutes % 60 == 0 -> "За ${minutes / 60} ${plural(minutes / 60, "час", "часа", "часов")}"
        else -> "За $minutes мин"
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val a = n % 100
        val b = n % 10
        return when {
            a in 11..14 -> many
            b == 1 -> one
            b in 2..4 -> few
            else -> many
        }
    }

    /** Поиск без учёта регистра и буквы «ё». */
    fun matches(fio: String, query: String): Boolean {
        val q = norm(query.trim())
        if (q.isEmpty()) return true
        val f = norm(fio)
        return q.split(' ').filter { it.isNotBlank() }.all { f.contains(it) }
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')
}
