package com.crossclip.sync

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PlaintextClip(
    val text: String,
    val sender: String,
    val timestamp: Long,
    val hash: String
)

object CryptoUtil {
    private val SALT = "cross-clip-v1".toByteArray(Charsets.UTF_8)
    private val INFO_ROOM_ID = "cross-clip-room-id".toByteArray(Charsets.UTF_8)
    private val INFO_AES_KEY = "cross-clip-aes-key".toByteArray(Charsets.UTF_8)
    private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun deriveKeys(masterSecret: ByteArray): Pair<String, ByteArray> {
        val prk = hmacSha256(SALT, masterSecret)
        val roomBytes = hkdfExpand(prk, INFO_ROOM_ID, 16)
        val aesKey = hkdfExpand(prk, INFO_AES_KEY, 32)
        val roomId = bytesToHex(roomBytes)
        return Pair(roomId, aesKey)
    }

    fun encrypt(text: String, sender: String, aesKey: ByteArray): String {
        val timestamp = System.currentTimeMillis()
        val hash = sha256(text)

        val innerJson = JSONObject().apply {
            put("text", text)
            put("sender", sender)
            put("timestamp", timestamp)
            put("hash", hash)
        }.toString()

        val plaintext = innerJson.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)

        val aad = ByteBuffer.allocate(8).putLong(timestamp).array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(aesKey, "AES")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        cipher.updateAAD(aad)
        val ciphertextAndTag = cipher.doFinal(plaintext)

        val envelope = JSONObject().apply {
            put("type", "clip")
            put("nonce", Base64.getEncoder().encodeToString(nonce))
            put("ciphertext", Base64.getEncoder().encodeToString(ciphertextAndTag))
            put("timestamp", timestamp)
        }

        return envelope.toString()
    }

    fun decrypt(nonceB64: String, ciphertextB64: String, timestamp: Long, aesKey: ByteArray): PlaintextClip? {
        return try {
            val nonce = Base64.getDecoder().decode(nonceB64)
            val combined = Base64.getDecoder().decode(ciphertextB64)
            val aad = ByteBuffer.allocate(8).putLong(timestamp).array()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(aesKey, "AES")
            val spec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(combined)

            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            PlaintextClip(
                text = json.optString("text"),
                sender = json.optString("sender"),
                timestamp = json.optLong("timestamp"),
                hash = json.optString("hash")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(hash)
    }

    fun decodeBase32(input: String): ByteArray {
        val clean = input.replace("-", "").replace(" ", "").trim().uppercase()
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0

        for (c in clean) {
            val index = BASE32_CHARS.indexOf(c)
            if (index < 0) continue
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1

        while (result.size() < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val toWrite = minOf(t.size, length - result.size())
            result.write(t, 0, toWrite)
            i++
        }

        return result.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }
}
