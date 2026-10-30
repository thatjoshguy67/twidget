package com.tjg.twidget.widget.coloros

/** Keep protocol validation independent of Android so malformed host requests are testable. */
internal object ColorOsCardProtocol {
    val paths = setOf("/cards/compact", "/cards/wide")
    private val methods = setOf("onCardCreate", "onCardUpdate", "onCardSubscribe", "onCardUnsubscribe", "onCardDestroy", "onCardEvent")

    fun error(method: String, rpk: String?, path: String?, instance: String?): String? = when {
        method !in methods -> "Unsupported card method"
        rpk != ColorOsLockCardProvider.RPK_PACKAGE || path !in paths -> "Unknown card"
        instance.isNullOrBlank() || instance.length > 512 -> "Invalid instance"
        else -> null
    }
}
