package ru.patients.app.data

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.patients.app.reminders.Alarms
import ru.patients.core.Merge
import ru.patients.core.Patient
import ru.patients.core.PatientType
import ru.patients.core.Reminder
import ru.patients.core.Schedule
import ru.patients.core.Store
import ru.patients.core.StoreCodec
import java.io.File
import java.util.UUID

/**
 * Вся база в памяти и в зашифрованном файле store.bin.
 * Любое изменение сразу сохраняется и пересчитывает будильники.
 */
class Repository(private val ctx: Context) {
    private val file = AtomicFile(File(ctx.filesDir, "store.bin"))
    private val lock = Any()

    var loadError: String? = null
        private set

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Store> = _state

    /** Есть ли правки, которые ещё не ушли на Яндекс Диск. */
    @Volatile
    var dirty: Boolean = false

    private fun load(): Store {
        val f = file.baseFile
        if (!f.exists()) return Store()
        return try {
            StoreCodec.decode(KeystoreCipher.decrypt(file.readFully()))
        } catch (e: Exception) {
            Log.e("Repository", "Не удалось прочитать базу", e)
            loadError = "Не удалось прочитать базу на телефоне. Файл сохранён как store.bin.broken. " +
                "Если включена синхронизация, данные вернутся с Яндекс Диска."
            runCatching { f.copyTo(File(ctx.filesDir, "store.bin.broken"), overwrite = true) }
            Store()
        }
    }

    private fun save(s: Store) {
        val out = file.startWrite()
        try {
            out.write(KeystoreCipher.encrypt(StoreCodec.encode(s)))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /** markDirty = false для слияния с Диском: такие изменения не требуют новой выгрузки. */
    fun update(markDirty: Boolean = true, fn: (Store) -> Store) {
        val new: Store
        synchronized(lock) {
            val old = _state.value
            new = fn(old)
            if (new == old) return
            save(new)
            _state.value = new
            if (markDirty) dirty = true
        }
        Alarms.reschedule(ctx, new)
    }

    private fun now() = System.currentTimeMillis()

    // ---- Пациенты ----

    fun addPatient(type: PatientType, fio: String): Patient {
        val t = now()
        val p = Patient(id = UUID.randomUUID().toString(), type = type, fio = fio.trim(), createdAt = t, updatedAt = t)
        update { it.copy(patients = it.patients + p) }
        return p
    }

    /** Сохраняет правки карточки. Если ничего не поменялось, время правки не трогаем. */
    fun savePatient(edited: Patient) = update { s ->
        val old = s.patients.firstOrNull { it.id == edited.id } ?: return@update s
        if (old.deleted) return@update s // удалённого не воскрешаем
        // тип и служебные поля меняются только своими командами, черновик экрана их не трогает
        val p = edited.copy(type = old.type, deleted = false, createdAt = old.createdAt, updatedAt = old.updatedAt)
        if (p == old) return@update s
        s.copy(patients = s.patients.map { if (it.id == p.id) p.copy(updatedAt = now()) else it })
    }

    fun deletePatient(id: String) = update { s ->
        val t = now()
        s.copy(
            patients = s.patients.map { if (it.id == id) it.copy(deleted = true, updatedAt = t) else it },
            reminders = s.reminders.map { if (it.patientId == id && !it.deleted) it.copy(deleted = true, updatedAt = t) else it },
        )
    }

    fun transferToMain(id: String) = update { s ->
        s.copy(patients = s.patients.map { if (it.id == id) it.copy(type = PatientType.MAIN, updatedAt = now()) else it })
    }

    // ---- Напоминания ----

    fun saveReminder(r: Reminder) = update { s ->
        val stamped = r.copy(updatedAt = now())
        val exists = s.reminders.any { it.id == r.id }
        s.copy(reminders = if (exists) s.reminders.map { if (it.id == r.id) stamped else it } else s.reminders + stamped)
    }

    fun deleteReminder(id: String) = update { s ->
        s.copy(reminders = s.reminders.map { if (it.id == id) it.copy(deleted = true, updatedAt = now()) else it })
    }

    fun markDone(id: String) = update { s ->
        s.copy(reminders = s.reminders.map { if (it.id == id && !it.done) Schedule.markDone(it, now()) else it })
    }

    fun markNotDone(id: String) = update { s ->
        s.copy(reminders = s.reminders.map { if (it.id == id) it.copy(done = false, updatedAt = now()) else it })
    }

    // ---- Синхронизация и копии ----

    fun mergeFrom(other: Store, markDirty: Boolean) = update(markDirty) { Merge.merge(it, other) }

    fun wipe() {
        synchronized(lock) {
            file.delete()
            _state.value = Store()
            dirty = false
        }
        Alarms.reschedule(ctx, Store())
    }
}
