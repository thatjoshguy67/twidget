package com.tjg.twidget.main

import java.util.Locale

/** X handles are case-insensitive identities; misspellings must not resolve to a different account. */
internal object OnboardingHandleInput {
    const val MAX_INPUT_LENGTH = 64
    private val handle = Regex("[a-z0-9_]{1,15}")

    fun normalize(input: String): String = input.trim().trimStart('@').trim().lowercase(Locale.ROOT)

    fun isValid(input: String): Boolean = handle.matches(normalize(input))

    fun sameAccount(first: String, second: String): Boolean {
        val normalized = normalize(first)
        return handle.matches(normalized) && normalized == normalize(second)
    }
}
