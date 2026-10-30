package com.tjg.twidget.widget.coloros

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only diagnostic: asks OCS only about our own package, never grants capability. */
@RunWith(AndroidJUnit4::class)
class ColorOsAdmissionInstrumentedTest {
    private fun report(message: String) {
        Log.i("TwidgetCardDiagnostic", message)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("native_card_diagnostic", message)
        })
    }

    @Test fun reportOwnCardClientAuthorizationAndProducerResponse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val own = context.contentResolver.call(
            Uri.parse("content://${context.packageName}.coloros.lockcards"), "onCardUpdate", null,
            Bundle().apply {
                putString("package", "com.tjg.twidget.coloroscards")
                putString("path", "/cards/followers")
                putString("instanceId", "diagnostic")
            },
        )
        assertEquals(0, own?.getInt("code"))
        val result = runCatching {
            context.contentResolver.call(
                Uri.parse("content://com.oplus.ocs.out.OpenCapabilityProvider/authenticate"),
                "capability_permission_method", null,
                Bundle().apply {
                    putStringArray("capability_package_names", arrayOf(context.packageName))
                    putString("capability_client", "CARD_CLIENT")
                    putInt("capability_single", 1)
                },
            )
        }
        result.fold(
            onSuccess = { response ->
                report("CARD_CLIENT codes=${response?.getIntArray("capability_error_codes")?.contentToString()} permissions=${response?.getIntArray("capability_permissions")?.contentToString()} responseKeys=${response?.keySet()}")
            },
            onFailure = { report("CARD_CLIENT query unavailable: ${it.javaClass.simpleName}") },
        )
    }
}
