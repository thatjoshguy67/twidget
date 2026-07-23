package com.tjg.twidget.schedule

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.tjg.twidget.core.AppExecutors

class BufferOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent?.data
        if (callback == null) {
            finish()
            return
        }
        AppExecutors.execute(onRejected = { finish() }) {
            val result = BufferOAuth.exchangeCallback(this, callback)
            runOnUiThread {
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { "Buffer connected" },
                        onFailure = { it.message ?: "Buffer sign-in failed" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }
}
