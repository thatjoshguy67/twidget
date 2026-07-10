package com.tjg.twidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.KeyGenerator

class EncryptedCredentialCodecTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTrip() {
        val encrypted = EncryptedCredentialCodec.encrypt("a very secret token", "x_api_token", key)
        assertEquals(
            "a very secret token",
            EncryptedCredentialCodec.decrypt(encrypted, "x_api_token", key),
        )
    }

    @Test
    fun freshIvProducesDifferentCiphertext() {
        val first = EncryptedCredentialCodec.encrypt("same", "x_api_token", key)
        val second = EncryptedCredentialCodec.encrypt("same", "x_api_token", key)
        assertNotEquals(first, second)
    }

    @Test
    fun credentialNameIsAuthenticated() {
        val encrypted = EncryptedCredentialCodec.encrypt("secret", "x_api_token", key)
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedCredentialCodec.decrypt(encrypted, "x_api_secret", key)
        }
    }

    @Test
    fun tamperingIsRejected() {
        val encrypted = EncryptedCredentialCodec.encrypt("secret", "x_api_token", key)
        val parts = encrypted.split(':').toMutableList()
        val ciphertext = Base64.getUrlDecoder().decode(parts[2])
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)
        val tampered = parts.joinToString(":")
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedCredentialCodec.decrypt(tampered, "x_api_token", key)
        }
    }

    @Test
    fun restoredOrMalformedEnvelopeIsRejected() {
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedCredentialCodec.decrypt("v1:not-an-iv:not-ciphertext", "x_api_token", key)
        }
    }
}
