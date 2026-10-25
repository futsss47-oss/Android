package ru.patients.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование ключом из Android Keystore. Ключ хранится в защищённом хранилище телефона
 * и не извлекается: файл базы, токен Яндекса и пароль синхронизации без него не прочитать.
 */
object KeystoreCipher {
    private const val ALIAS = "patients_master_key"

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        val n = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + n)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(data, 1 + n, data.size - 1 - n)
    }

    fun deleteKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/** Настройки приложения. Секреты (токен, пароль синхронизации) лежат зашифрованными. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun putSecret(key: String, value: String?) {
        if (value.isNullOrEmpty()) p.edit().remove(key).apply()
        else p.edit().putString(key, b64(KeystoreCipher.encrypt(value.toByteArray()))).apply()
    }

    private fun getSecret(key: String): String? =
        p.getString(key, null)?.let { runCatching { String(KeystoreCipher.decrypt(unb64(it))) }.getOrNull() }

    // PIN
    val hasPin: Boolean get() = p.contains("pin_hash")
    fun setPin(salt: ByteArray, hash: ByteArray) =
        p.edit().putString("pin_salt", b64(salt)).putString("pin_hash", b64(hash)).apply()
    val pinSalt: ByteArray? get() = p.getString("pin_salt", null)?.let(::unb64)
    val pinHash: ByteArray? get() = p.getString("pin_hash", null)?.let(::unb64)

    var biometric: Boolean
        get() = p.getBoolean("biometric", false)
        set(v) = p.edit().putBoolean("biometric", v).apply()

    var failedAttempts: Int
        get() = p.getInt("failed", 0)
        set(v) = p.edit().putInt("failed", v).apply()

    var lockedUntil: Long
        get() = p.getLong("locked_until", 0)
        set(v) = p.edit().putLong("locked_until", v).apply()

    // Яндекс Диск
    var yandexClientId: String
        get() = p.getString("ya_client", "") ?: ""
        set(v) = p.edit().putString("ya_client", v.trim()).apply()

    var yandexToken: String?
        get() = getSecret("ya_token")
        set(v) = putSecret("ya_token", v?.trim())

    var syncPassword: String?
        get() = getSecret("sync_pass")
        set(v) = putSecret("sync_pass", v)

    var lastSyncAt: Long
        get() = p.getLong("last_sync", 0)
        set(v) = p.edit().putLong("last_sync", v).apply()

    var lastSyncError: String?
        get() = p.getString("last_sync_err", null)
        set(v) = p.edit().putString("last_sync_err", v).apply()

    val syncConfigured: Boolean get() = !yandexToken.isNullOrEmpty() && !syncPassword.isNullOrEmpty()

    // Какие будильники сейчас поставлены (чтобы снять лишние)
    var scheduledKeys: Set<String>
        get() = p.getStringSet("alarms", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("alarms", v).apply()

    fun clearAll() = p.edit().clear().apply()
}
