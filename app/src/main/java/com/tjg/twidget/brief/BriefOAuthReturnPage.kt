package com.tjg.twidget.brief

/** Static HTML only: never reflect OAuth parameters, account details or credentials. */
internal object BriefOAuthReturnPage {
    fun html(valid: Boolean, appPackage: String, pending: Boolean = false): String {
        val title = if (pending) "Continue in Twidget" else if (valid) "You’re connected" else "Sign-in didn’t complete"
        val message = if (pending) "Returning to Your Brief to finish connecting your account." else if (valid) "Returning to Your Brief. You can close this tab." else "Return to Twidget to see the connection error and try again."
        require(appPackage.matches(Regex("[a-zA-Z0-9_.]+")))
        val returnUrl = "$appPackage.oauth://return"
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Twidget</title><style>
            :root{color-scheme:light dark}*{box-sizing:border-box}body{margin:0;min-height:100svh;display:grid;place-items:center;padding:24px;background:#f1f1f3;color:#17171a;font-family:system-ui,sans-serif}main{width:min(100%,380px);background:#fcfcff;border-radius:28px;padding:32px}small{color:#387aff;font-weight:600}h1{font-size:24px;line-height:1.2;margin:20px 0 12px}p{font-size:16px;line-height:1.6;margin:0;color:#68686e}@media(prefers-color-scheme:dark){body{background:#010102;color:#fff}main{background:#17171a}p{color:#b6b6bf}}
            a{display:block;margin-top:24px;padding:14px 20px;border-radius:24px;background:#387aff;color:white;text-decoration:none;text-align:center;font-weight:600}</style></head><body><main><small>Twidget · Your Brief</small><h1>$title</h1><p>$message</p><a href="$returnUrl">Return to Twidget</a></main>${if (valid || pending) "<script>setTimeout(function(){location.href=\"$returnUrl\"},400)</script>" else ""}</body></html>"""
    }
}
