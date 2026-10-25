package ru.patients.app.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.patients.app.PatientsApp
import ru.patients.core.BadFileException
import ru.patients.core.PasswordCrypto
import ru.patients.core.StoreCodec
import ru.patients.core.WrongPasswordException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SyncException(msg: String) : Exception(msg)

/** Яндекс Диск через REST API. Файл лежит в папке приложения: «Приложения/<имя приложения>». */
class YandexDisk(private val token: String) {
    private val base = "https://cloud-api.yandex.net/v1/disk/resources"
    private val path = URLEncoder.encode("app:/patients.sync", "UTF-8")

    private fun open(url: String, method: String = "GET", auth: Boolean = true): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (auth) setRequestProperty("Authorization", "OAuth $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun fail(c: HttpURLConnection): Nothing {
        val code = c.responseCode
        val msg = runCatching {
            JSONObject(c.errorStream.bufferedReader().readText()).optString("message")
        }.getOrNull().orEmpty()
        throw SyncException(
            when (code) {
                401 -> "Яндекс не принял токен. Войдите в Яндекс заново в настройках."
                403 -> "Нет доступа к Диску. Проверьте, что приложению на oauth.yandex.ru выдан доступ к папке приложения."
                507 -> "На Яндекс Диске закончилось место."
                else -> "Ошибка Яндекс Диска ($code) $msg".trim()
            },
        )
    }

    private fun href(c: HttpURLConnection): String =
        JSONObject(c.inputStream.bufferedReader().readText()).getString("href")

    /** null — файла на Диске ещё нет. */
    fun download(): ByteArray? {
        val c = open("$base/download?path=$path")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) fail(c)
            val link = href(c)
            val d = open(link, auth = false)
            try {
                if (d.responseCode != 200) fail(d)
                return d.inputStream.readBytes()
            } finally {
                d.disconnect()
            }
        } finally {
            c.disconnect()
        }
    }

    fun upload(bytes: ByteArray) {
        val c = open("$base/upload?path=$path&overwrite=true")
        try {
            if (c.responseCode != 200) fail(c)
            val link = href(c)
            val u = open(link, method = "PUT", auth = false).apply {
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            try {
                u.outputStream.use { it.write(bytes) }
                if (u.responseCode !in 200..202) fail(u)
            } finally {
                u.disconnect()
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun authUrl(clientId: String) =
            "https://oauth.yandex.ru/authorize?response_type=token&client_id=${URLEncoder.encode(clientId, "UTF-8")}"
    }
}

data class SyncStatus(val running: Boolean = false, val lastAt: Long = 0, val error: String? = null)

object SyncManager {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    fun init(ctx: Context) {
        val s = PatientsApp.settings(ctx)
        _status.value = SyncStatus(lastAt = s.lastSyncAt, error = s.lastSyncError)
    }

    /**
     * Скачать файл с Диска, слить с базой на телефоне, выгрузить результат.
     * Возвращает null при успехе или текст ошибки.
     */
    suspend fun sync(ctx: Context): String? = withContext(Dispatchers.IO) {
        val settings = PatientsApp.settings(ctx)
        val token = settings.yandexToken
        val password = settings.syncPassword
        if (token.isNullOrEmpty() || password.isNullOrEmpty()) return@withContext "Синхронизация не настроена"
        if (!mutex.tryLock()) return@withContext null // уже идёт
        try {
            _status.value = _status.value.copy(running = true)
            val repo = PatientsApp.repo(ctx)
            val disk = YandexDisk(token)
            val error = try {
                val remoteBytes = disk.download()
                if (remoteBytes != null) {
                    val remote = StoreCodec.decode(PasswordCrypto.decrypt(remoteBytes, password))
                    repo.mergeFrom(remote, markDirty = false)
                    val merged = repo.state.value
                    if (merged != remote) disk.upload(PasswordCrypto.encrypt(StoreCodec.encode(merged), password))
                } else {
                    disk.upload(PasswordCrypto.encrypt(StoreCodec.encode(repo.state.value), password))
                }
                repo.dirty = false
                null
            } catch (e: WrongPasswordException) {
                "Пароль синхронизации не подходит к файлу на Диске. Введите тот же пароль, что на другом телефоне."
            } catch (e: BadFileException) {
                "Файл на Диске повреждён: ${e.message}"
            } catch (e: SyncException) {
                e.message
            } catch (e: IOException) {
                "Нет связи с Яндекс Диском. Синхронизация повторится позже."
            } catch (e: Exception) {
                "Ошибка синхронизации: ${e.message ?: e.javaClass.simpleName}"
            }
            val now = System.currentTimeMillis()
            if (error == null) settings.lastSyncAt = now
            settings.lastSyncError = error
            _status.value = SyncStatus(running = false, lastAt = settings.lastSyncAt, error = error)
            error
        } finally {
            mutex.unlock()
        }
    }
}
