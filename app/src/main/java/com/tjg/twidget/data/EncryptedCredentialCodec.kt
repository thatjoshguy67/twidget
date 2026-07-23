package com.tjg.twidget.data

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned AES-GCM envelope used by [SecureCredentialStore]. */
internal object EncryptedCredentialCodec {
    private const val VERSION = "v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(value: String, credentialName: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(credentialName.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            VERSION,
            encoder.encodeToString(cipher.iv),
            encoder.encodeToString(ciphertext),
        ).joinToString(":")
    }

    fun decrypt(envelope: String, credentialName: String, key: SecretKey): String {
        val parts = envelope.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != VERSION) {
            throw GeneralSecurityException("Unsupported encrypted credential format")
        }
        return try {
            val decoder = Base64.getUrlDecoder()
            val iv = decoder.decode(parts[1])
            val ciphertext = decoder.decode(parts[2])
            if (iv.size != 12) throw GeneralSecurityException("Invalid encrypted credential IV")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(credentialName.toByteArray(StandardCharsets.UTF_8))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid encrypted credential encoding", error)
        }
    }
}
