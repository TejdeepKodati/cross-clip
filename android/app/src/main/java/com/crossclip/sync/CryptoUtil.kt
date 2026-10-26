package com.crossclip.sync

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val NONCE_LENGTH = 12

    fun deriveKey(secret: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    fun encrypt(plainText: String, secretKey: SecretKeySpec): String {
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(nonce.size + cipherText.size)
        System.arraycopy(nonce, 0, combined, 0, nonce.size)
        System.arraycopy(cipherText, 0, combined, nonce.size, cipherText.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(base64Payload: String, secretKey: SecretKeySpec): String {
        val raw = Base64.decode(base64Payload, Base64.NO_WRAP)
        if (raw.size < NONCE_LENGTH) throw IllegalArgumentException("Invalid payload size")

        val nonce = ByteArray(NONCE_LENGTH)
        System.arraycopy(raw, 0, nonce, 0, NONCE_LENGTH)

        val cipherText = ByteArray(raw.size - NONCE_LENGTH)
        System.arraycopy(raw, NONCE_LENGTH, cipherText, 0, cipherText.size)

        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val plain = cipher.doFinal(cipherText)
        return String(plain, Charsets.UTF_8)
    }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
