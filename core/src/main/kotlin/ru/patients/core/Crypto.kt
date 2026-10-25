package ru.patients.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : Exception("Неверный пароль")
class BadFileException(msg: String) : Exception(msg)

/**
 * Шифрование паролем: резервные копии и файл синхронизации на Яндекс Диске.
 * Формат: "PTS1" | соль 16 байт | IV 12 байт | AES-256-GCM(данные).
 * Ключ из пароля получается через PBKDF2-HMAC-SHA256.
 */
object PasswordCrypto {
    private val MAGIC = "PTS1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 120_000
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(MAGIC.size + salt.size + iv.size + body.size)
            .put(MAGIC).put(salt).put(iv).put(body).array()
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        if (data.size < 4 + 16 + 12 + 16 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw BadFileException("Файл не похож на данные приложения")
        }
        val salt = data.copyOfRange(4, 20)
        val iv = data.copyOfRange(20, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(data, 32, data.size - 32)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}

/** Хранение PIN-кода: только соль и хэш, сам PIN нигде не сохраняется. */
object PinHash {
    private const val ITERATIONS = 60_000
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also(random::nextBytes)

    fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun verify(pin: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt), expected)
}
