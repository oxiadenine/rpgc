package io.github.oxiadenine.rpgc.tools.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.toCharArray

class PasswordEncoder {
    fun generateBytes(size: Int) = ByteArray(size).let { bytes ->
        SecureRandom().nextBytes(bytes)

        bytes
    }

    fun hash(password: String, salt: ByteArray, iterationCount: Int = 256000, length: Int = 256): ByteArray {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, iterationCount, length)

        val secretKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec)

        return secretKey.encoded
    }

    private fun cipher(password: ByteArray, key: ByteArray, iv: ByteArray, mode: Int): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")

        val ivParamSpec = GCMParameterSpec(96, iv)

        return Cipher.getInstance("AES/GCM/NoPadding").let { cipher ->
            cipher.init(mode, secretKey, ivParamSpec)
            cipher.doFinal(password)
        }
    }

    fun encrypt(password: ByteArray, key: ByteArray, iv: ByteArray) = cipher(password, key, iv, Cipher.ENCRYPT_MODE)
    fun decrypt(password: ByteArray, key: ByteArray, iv: ByteArray) = cipher(password, key, iv, Cipher.DECRYPT_MODE)
}