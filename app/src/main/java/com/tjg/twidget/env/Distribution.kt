package com.tjg.twidget.env

enum class Distribution {
    GITHUB,
    PLAY,
    ;

    fun isGithub(): Boolean = this == GITHUB
}
