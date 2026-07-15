package com.tjg.twidget.schedule

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@file:Suppress("UseKtx")



enum class XHandoffResult {
    OPENED,
    COPIED,
    SHARED,
    NO_HANDLER,
    INVALID_MEDIA,
    FAILED,
}

data class XHandoffOutcome(
    val result: XHandoffResult,
    val limitation: String? = null,
)

object XComposeIntents {
    const val WEB_COMPOSE_URL = "https://x.com/intent/post"
    const val X_APP_PACKAGE = "com.twitter.android"
    const val APP_MEDIA_LIMITATION =
        "X opens with your draft text. Attach media manually if needed."
    const val WEB_MEDIA_LIMITATION =
        "X's web compose URL accepts text only; media must be attached manually."
    const val SHARE_LIMITATION =
        "The receiving X app controls whether shared text and media are accepted and cannot create a thread automatically."

    fun buildWebComposeUrl(text: String): String =
        "$WEB_COMPOSE_URL?text=${encodeQueryValue(text)}"

    fun buildAppComposeUri(text: String): String =
        "twitter://post?message=${encodeQueryValue(text)}"

    internal fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun openWebCompose(context: Context, text: String): XHandoffOutcome = openCompose(context, text)

    fun openCompose(context: Context, text: String): XHandoffOutcome {
        val appDeepLink = Intent(Intent.ACTION_VIEW, Uri.parse(buildAppComposeUri(text)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (safeStart(context, appDeepLink)) {
            return XHandoffOutcome(XHandoffResult.OPENED, APP_MEDIA_LIMITATION)
        }

        val packagedWeb = Intent(Intent.ACTION_VIEW, Uri.parse(buildWebComposeUrl(text)))
            .setPackage(X_APP_PACKAGE)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (safeStart(context, packagedWeb)) {
            return XHandoffOutcome(XHandoffResult.OPENED, APP_MEDIA_LIMITATION)
        }

        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(buildWebComposeUrl(text)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (safeStart(context, webIntent)) {
            XHandoffOutcome(XHandoffResult.OPENED, WEB_MEDIA_LIMITATION)
        } else {
            XHandoffOutcome(XHandoffResult.NO_HANDLER, WEB_MEDIA_LIMITATION)
        }
    }

    fun copyText(context: Context, text: String): XHandoffOutcome = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return XHandoffOutcome(XHandoffResult.FAILED)
        clipboard.setPrimaryClip(ClipData.newPlainText("X post", text))
        XHandoffOutcome(XHandoffResult.COPIED)
    } catch (_: RuntimeException) {
        XHandoffOutcome(XHandoffResult.FAILED)
    }

    fun shareItem(context: Context, item: ScheduleThreadItem): XHandoffOutcome {
        val localUris = item.media.filterIsInstance<LocalUriMedia>().mapNotNull {
            runCatching { Uri.parse(it.uri) }.getOrNull()?.takeIf { uri ->
                uri.scheme == "content"
            }
        }
        if (localUris.size != item.media.count { it is LocalUriMedia }) {
            return XHandoffOutcome(
                XHandoffResult.INVALID_MEDIA,
                "Only persisted content:// URIs can be handed to another app.",
            )
        }

        val remoteLinks = item.media.mapNotNull {
            when (it) {
                is PublicUrlMedia -> it.url
                is PostponeLibraryMedia -> it.url
                is LocalUriMedia -> null
            }
        }
        val sharedText = (listOf(item.text) + remoteLinks)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val intent = when (localUris.size) {
            0 -> Intent(Intent.ACTION_SEND).setType("text/plain")
            1 -> Intent(Intent.ACTION_SEND)
                .setType(item.media.filterIsInstance<LocalUriMedia>().first().mimeType ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, localUris.first())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            else -> Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(commonMimeType(item.media.filterIsInstance<LocalUriMedia>()))
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(localUris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (sharedText.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, sharedText)
        localUris.forEach { uri ->
            intent.clipData = (intent.clipData ?: ClipData.newRawUri("X media", uri)).also { clip ->
                if (clip.itemCount == 1 && clip.getItemAt(0).uri == uri) return@also
                clip.addItem(ClipData.Item(uri))
            }
        }
        val chooser = Intent.createChooser(intent, "Share to X").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (safeStart(context, chooser)) {
            XHandoffOutcome(XHandoffResult.SHARED, SHARE_LIMITATION)
        } else {
            XHandoffOutcome(XHandoffResult.NO_HANDLER, SHARE_LIMITATION)
        }
    }

    private fun commonMimeType(media: List<LocalUriMedia>): String {
        val types = media.mapNotNull { it.mimeType }.distinct()
        return if (types.size == 1) types.first() else "*/*"
    }

    private fun safeStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
