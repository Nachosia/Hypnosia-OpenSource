package dev.hypnosia.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object FileEncryption {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128

    /** Derive a 32-byte AES key from an arbitrary string (e.g. hardware fingerprint). */
    fun deriveKey(input: String): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    }

    fun encrypt(plaintext: String, keyBytes: ByteArray): String {
        val iv = ByteArray(IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), GCMParameterSpec(TAG_LENGTH, iv))
        }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(ciphertext: String, keyBytes: ByteArray): String? {
        return runCatching {
            val decoded = Base64.getDecoder().decode(ciphertext)
            val iv = decoded.copyOfRange(0, IV_LENGTH)
            val encrypted = decoded.copyOfRange(IV_LENGTH, decoded.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), GCMParameterSpec(TAG_LENGTH, iv))
            }
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }
}
