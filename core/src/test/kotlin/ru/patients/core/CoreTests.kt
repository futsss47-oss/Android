package ru.patients.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTests {
    private val zone = ZoneId.of("Asia/Yekaterinburg")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 9, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun patient(id: String, upd: Long, fio: String = "Иванов", deleted: Boolean = false) =
        Patient(id = id, type = PatientType.MAIN, fio = fio, updatedAt = upd, deleted = deleted)

    @Test
    fun mergeKeepsNewestPerRecord() {
        val local = Store(patients = listOf(patient("a", 10, "А-новый"), patient("b", 1, "Б-старый")))
        val remote = Store(patients = listOf(patient("a", 5, "А-старый"), patient("b", 20, "Б-новый"), patient("c", 3)))
        val m = Merge.merge(local, remote)
        assertEquals("А-новый", m.patients.first { it.id == "a" }.fio)
        assertEquals("Б-новый", m.patients.first { it.id == "b" }.fio)
        assertEquals(3, m.patients.size)
    }

    @Test
    fun mergeDeletionWinsWhenNewer() {
        val local = Store(patients = listOf(patient("a", 10)))
        val remote = Store(patients = listOf(patient("a", 11, deleted = true)))
        assertTrue(Merge.merge(local, remote).patients.single().deleted)
        assertTrue(Merge.merge(remote, local).patients.single().deleted)
    }

    @Test
    fun mergeIsSymmetric() {
        val a = Store(patients = listOf(patient("a", 10, "x"), patient("b", 3)))
        val b = Store(patients = listOf(patient("a", 12, "y"), patient("c", 1)))
        assertEquals(
            Merge.merge(a, b).patients.sortedBy { it.id },
            Merge.merge(b, a).patients.sortedBy { it.id },
        )
    }

    @Test
    fun monthlyRepeatFromOriginalDate() {
        val at = ms(2026, 1, 31)
        val next = Schedule.nextOccurrence(at, RepeatUnit.MONTHS, 1, at, zone)
        assertEquals(ms(2026, 2, 28), next)
    }

    @Test
    fun repeatSkipsPastOccurrences() {
        val at = ms(2026, 1, 10)
        val now = ms(2026, 3, 15)
        assertEquals(ms(2026, 4, 10), Schedule.nextOccurrence(at, RepeatUnit.MONTHS, 1, now, zone))
        assertEquals(ms(2026, 5, 10), Schedule.nextOccurrence(at, RepeatUnit.MONTHS, 2, now, zone))
    }

    @Test
    fun doneOneShotCloses() {
        val r = Reminder(id = "r", patientId = "a", atMillis = ms(2026, 1, 10))
        val d = Schedule.markDone(r, ms(2026, 1, 11), zone)
        assertTrue(d.done)
    }

    @Test
    fun doneRepeatingMovesForward() {
        val r = Reminder(id = "r", patientId = "a", atMillis = ms(2026, 1, 10), repeatUnit = RepeatUnit.WEEKS, repeatEvery = 2)
        val d = Schedule.markDone(r, ms(2026, 1, 11), zone)
        assertFalse(d.done)
        assertEquals(ms(2026, 1, 24), d.atMillis)
    }

    @Test
    fun upcomingEventsIncludeAdvanceAndSkipDeletedPatient() {
        val store = Store(
            patients = listOf(patient("a", 1), patient("b", 1, deleted = true)),
            reminders = listOf(
                Reminder(id = "1", patientId = "a", atMillis = ms(2026, 2, 10), advanceMinutes = 1440),
                Reminder(id = "2", patientId = "b", atMillis = ms(2026, 2, 10)),
                Reminder(id = "3", patientId = "a", atMillis = ms(2026, 2, 1), done = true),
            ),
        )
        val ev = Schedule.upcomingEvents(store, ms(2026, 1, 1), 50)
        assertEquals(listOf(Schedule.Kind.ADVANCE, Schedule.Kind.DUE), ev.map { it.kind })
        assertTrue(ev.all { it.reminderId == "1" })
    }

    @Test
    fun passwordCryptoRoundTrip() {
        val store = Store(patients = listOf(patient("a", 1, "Петров Пётр")))
        val enc = PasswordCrypto.encrypt(StoreCodec.encode(store), "пароль123")
        assertEquals(store, StoreCodec.decode(PasswordCrypto.decrypt(enc, "пароль123")))
        assertFailsWith<WrongPasswordException> { PasswordCrypto.decrypt(enc, "другой") }
        assertFailsWith<BadFileException> { PasswordCrypto.decrypt("мусор".toByteArray(), "x") }
    }

    @Test
    fun pinHash() {
        val salt = PinHash.newSalt()
        val h = PinHash.hash("1234", salt)
        assertTrue(PinHash.verify("1234", salt, h))
        assertFalse(PinHash.verify("4321", salt, h))
    }

    @Test
    fun texts() {
        assertEquals("Каждые 2 месяца", Texts.repeat(RepeatUnit.MONTHS, 2))
        assertEquals("Каждую неделю", Texts.repeat(RepeatUnit.WEEKS, 1))
        assertEquals("Каждые 5 дней", Texts.repeat(RepeatUnit.DAYS, 5))
        assertEquals("За 1 день", Texts.advance(1440))
        assertEquals("За 2 часа", Texts.advance(120))
        assertTrue(Texts.matches("Сёмин Пётр Ильич", "семин петр"))
        assertFalse(Texts.matches("Иванов", "петров"))
    }

    @Test
    fun oldJsonWithoutNewFieldsStillLoads() {
        val json = """{"patients":[{"id":"a","type":"DIAG","fio":"Тест"}]}"""
        val s = StoreCodec.decode(json.toByteArray())
        assertEquals("Тест", s.patients.single().fio)
        assertNull(s.patients.single().firstVisit)
    }
}
