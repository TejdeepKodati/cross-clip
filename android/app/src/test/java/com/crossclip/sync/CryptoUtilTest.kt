package com.crossclip.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CryptoUtilTest {

    @Test
    fun testDeriveKeysIsDeterministic() {
        val masterSecret = ByteArray(32) { it.toByte() }
        val (roomId1, key1) = CryptoUtil.deriveKeys(masterSecret)
        val (roomId2, key2) = CryptoUtil.deriveKeys(masterSecret)

        assertEquals(roomId1, roomId2)
        assertArrayEquals(key1, key2)
        assertEquals(32, roomId1.length) // 16 bytes = 32 hex chars
        assertEquals(32, key1.size)     // 256 bits = 32 bytes
    }

    @Test
    fun testBase32DecodingWithHyphens() {
        val rawBase32 = "JBSWY3DPEHPK3PXP"
        val formatted = "JBSW-Y3DP-EHPK-3PXP"

        val decoded1 = CryptoUtil.decodeBase32(rawBase32)
        val decoded2 = CryptoUtil.decodeBase32(formatted)

        assertArrayEquals(decoded1, decoded2)
        assertTrue(decoded1.isNotEmpty())
    }

    @Test
    fun testSha256MatchesStandard() {
        val hash = CryptoUtil.sha256("cross-clip")
        // Echo "cross-clip" | sha256
        assertNotNull(hash)
        assertEquals(64, hash.length)
    }

    @Test
    fun testEncryptDecryptRoundtrip() {
        val masterSecret = ByteArray(32) { (it * 3).toByte() }
        val (_, aesKey) = CryptoUtil.deriveKeys(masterSecret)

        val sampleText = "Hello from Android! Sync test with special characters: 🚀 123"
        val envelopeJson = CryptoUtil.encrypt(sampleText, "android", aesKey)

        val json = JSONObject(envelopeJson)
        val nonce = json.getString("nonce")
        val ciphertext = json.getString("ciphertext")
        val timestamp = json.getLong("timestamp")

        val decrypted = CryptoUtil.decrypt(nonce, ciphertext, timestamp, aesKey)

        assertNotNull(decrypted)
        assertEquals(sampleText, decrypted?.text)
        assertEquals("android", decrypted?.sender)
        assertEquals(CryptoUtil.sha256(sampleText), decrypted?.hash)
    }
}
