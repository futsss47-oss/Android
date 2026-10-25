package ru.patients.core

import kotlinx.serialization.Serializable

/** Тип списка: основные или диагностические пациенты. */
@Serializable
enum class PatientType { MAIN, DIAG }

/**
 * Карточка пациента. Даты хранятся как номер дня (LocalDate.toEpochDay), null значит «не указано».
 * updatedAt нужен для слияния при синхронизации, deleted помечает удалённую запись,
 * чтобы удаление тоже доехало до второго телефона.
 */
@Serializable
data class Patient(
    val id: String,
    val type: PatientType,
    val fio: String = "",
    val birthDate: Long? = null,
    val phone: String = "",
    val address: String = "",
    val cardNumber: String = "",
    // Основные пациенты
    val registeredDate: Long? = null,
    val mainDiagnosis: String = "",
    val comorbid: List<String> = emptyList(),
    val intensiveStart: Long? = null,
    val continuationStart: Long? = null,
    // Диагностические пациенты
    val firstVisit: Long? = null,
    val complaints: String = "",
    val todo: String = "",
    val result: String = "",
    // Служебное
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Serializable
enum class RepeatUnit { NONE, DAYS, WEEKS, MONTHS }

/**
 * Напоминание. atMillis — время срока (локальное время телефона в миллисекундах).
 * advanceMinutes — за сколько минут до срока прислать предупреждение (0 — не присылать).
 */
@Serializable
data class Reminder(
    val id: String,
    val patientId: String,
    val title: String = "",
    val atMillis: Long,
    val repeatUnit: RepeatUnit = RepeatUnit.NONE,
    val repeatEvery: Int = 1,
    val advanceMinutes: Int = 0,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Serializable
data class Store(
    val version: Int = 1,
    val patients: List<Patient> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
) {
    fun patient(id: String): Patient? = patients.firstOrNull { it.id == id && !it.deleted }

    fun activePatients(type: PatientType): List<Patient> =
        patients.filter { it.type == type && !it.deleted }

    /** Напоминания, которые видны пользователю: не удалены и пациент существует. */
    fun visibleReminders(): List<Reminder> {
        val alive = patients.filter { !it.deleted }.mapTo(HashSet()) { it.id }
        return reminders.filter { !it.deleted && it.patientId in alive }
    }

    fun remindersOf(patientId: String): List<Reminder> =
        reminders.filter { it.patientId == patientId && !it.deleted }
}
