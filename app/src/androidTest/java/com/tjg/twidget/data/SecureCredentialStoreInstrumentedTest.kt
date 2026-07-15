package com.tjg.twidget.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureCredentialStoreInstrumentedTest {
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        SecureCredentialStore.write(
            context,
            mapOf(
                SecureCredentialStore.BRIDGE_API_TOKEN to "",
                SecureCredentialStore.X_API_TOKEN to "",
            ),
        )
    }

    @Test
    fun writeReadRoundTrip_persistsEncryptedCredential() {
        SecureCredentialStore.write(context, mapOf(SecureCredentialStore.BRIDGE_API_TOKEN to "bridge-secret"))

        assertEquals("bridge-secret", SecureCredentialStore.read(context, SecureCredentialStore.BRIDGE_API_TOKEN))
    }

    @Test
    fun clearCredential_removesStoredValue() {
        SecureCredentialStore.write(context, mapOf(SecureCredentialStore.X_API_TOKEN to "token-value"))
        SecureCredentialStore.clear(context, SecureCredentialStore.X_API_TOKEN)

        assertEquals("", SecureCredentialStore.read(context, SecureCredentialStore.X_API_TOKEN))
    }

    @Test
    fun migrateFrom_movesLegacyPlaintextIntoSecureStore() {
        val legacy = context.getSharedPreferences(TwidgetStore.PREFS, android.content.Context.MODE_PRIVATE)
        legacy.edit().putString("api_key", "legacy-bridge-token").commit()
        SecureCredentialStore.migrateFrom(context, legacy)

        assertEquals("legacy-bridge-token", SecureCredentialStore.read(context, SecureCredentialStore.BRIDGE_API_TOKEN))
        assertTrue(legacy.getString("api_key", null).isNullOrEmpty())
    }
}
