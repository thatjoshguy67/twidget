@file:Suppress("UseKtx") // This project intentionally excludes AndroidX core-ktx.

package com.tjg.twidget

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Stores connector credentials as authenticated ciphertext whose key remains in
 * Android Keystore. Call [migrateFrom] before reading settings from legacy prefs.
 */
object SecureCredentialStore {
    const val BRIDGE_API_TOKEN = "api_key"
    const val X_API_TOKEN = "x_api_token"
    const val X_API_KEY = "x_api_key"
    const val X_API_SECRET = "x_api_secret"
    const val X_API_BEARER = "x_api_bearer"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "com.tjg.twidget.credentials.v1"
    private const val PREFS = "twidget_secure_credentials"
    private const val MIGRATION_COMPLETE = "migration_complete_v1"
    private val credentialNames = setOf(
        BRIDGE_API_TOKEN,
        X_API_TOKEN,
        X_API_KEY,
        X_API_SECRET,
        X_API_BEARER,
    )
    private val lock = Any()

    /** Returns an empty string when a credential has not been configured. */
    fun read(context: Context, name: String): String = synchronized(lock) {
        requireCredentialName(name)
        val prefs = securePrefs(context)
        val envelope = prefs.getString(name, null) ?: return@synchronized ""
        try {
            EncryptedCredentialCodec.decrypt(envelope, name, secretKey())
        } catch (_: GeneralSecurityException) {
            // A restored preference file has no matching Keystore key. Treat
            // only this credential as absent and never expose cipher details.
            prefs.edit().remove(name).apply()
            ""
        } catch (_: ProviderException) {
            prefs.edit().remove(name).apply()
            ""
        }
    }

    /** Atomically updates a set of credentials; blank values remove them. */
    fun write(context: Context, values: Map<String, String>) = synchronized(lock) {
        values.keys.forEach(::requireCredentialName)
        val key = secretKey()
        val encrypted = values.mapValues { (name, value) ->
            value.trim().takeIf { it.isNotEmpty() }
                ?.let { EncryptedCredentialCodec.encrypt(it, name, key) }
        }
        val editor = securePrefs(context).edit()
        encrypted.forEach { (name, value) ->
            if (value == null) editor.remove(name) else editor.putString(name, value)
        }
        check(editor.commit()) { "Unable to persist encrypted credentials" }
    }

    fun clear(context: Context, name: String) = write(context, mapOf(name to ""))

    /**
     * Copies legacy plaintext credentials into secure storage. Plaintext is
     * removed only after every encrypted value has been durably committed.
     */
    fun migrateFrom(context: Context, legacyPrefs: SharedPreferences) = synchronized(lock) {
        val secure = securePrefs(context)
        if (secure.getBoolean(MIGRATION_COMPLETE, false)) {
            removeLegacyValues(legacyPrefs)
            return@synchronized
        }

        val key = try {
            secretKey()
        } catch (_: GeneralSecurityException) {
            // Keep legacy values untouched so a transient Keystore failure can
            // be retried, while callers still fail closed through read().
            return@synchronized
        } catch (_: ProviderException) {
            return@synchronized
        }
        val secureEditor = secure.edit()
        credentialNames.forEach { name ->
            val legacy = legacyPrefs.getString(name, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val existing = secure.getString(name, null)
            val existingIsReadable = existing != null && try {
                EncryptedCredentialCodec.decrypt(existing, name, key)
                true
            } catch (_: GeneralSecurityException) {
                false
            } catch (_: ProviderException) {
                false
            }
            if (!existingIsReadable) {
                if (legacy == null) {
                    secureEditor.remove(name)
                } else {
                    try {
                        secureEditor.putString(name, EncryptedCredentialCodec.encrypt(legacy, name, key))
                    } catch (_: GeneralSecurityException) {
                        secureEditor.remove(name)
                    } catch (_: ProviderException) {
                        secureEditor.remove(name)
                    }
                }
            }
        }
        secureEditor.putBoolean(MIGRATION_COMPLETE, true)
        if (!secureEditor.commit()) return@synchronized
        removeLegacyValues(legacyPrefs)
    }

    private fun removeLegacyValues(legacyPrefs: SharedPreferences) {
        if (credentialNames.none(legacyPrefs::contains)) return
        val editor = legacyPrefs.edit()
        credentialNames.forEach(editor::remove)
        editor.apply()
    }

    private fun securePrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun requireCredentialName(name: String) {
        require(name in credentialNames) { "Unknown credential name" }
    }
}
