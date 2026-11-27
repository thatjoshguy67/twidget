package com.tjg.twidget.widget

/** Dutch cardinal spellings for the follower artwork. */
internal object DutchNumberWords {
    private val small = listOf(
        "nul", "een", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht", "negen",
        "tien", "elf", "twaalf", "dertien", "veertien", "vijftien", "zestien", "zeventien",
        "achttien", "negentien",
    )
    private val tens = listOf(
        "", "", "twintig", "dertig", "veertig", "vijftig", "zestig", "zeventig", "tachtig",
        "negentig",
    )
    private val scales = listOf(
        1_000_000_000_000_000_000L to "triljoen",
        1_000_000_000_000_000L to "biljard",
        1_000_000_000_000L to "biljoen",
        1_000_000_000L to "miljard",
        1_000_000L to "miljoen",
    )

    fun spell(value: Long): String {
        require(value >= 0)
        return cardinal(value).replaceFirstChar { it.uppercase() }
    }

    private fun cardinal(value: Long): String = when {
        value < 20 -> small[value.toInt()]
        value < 100 -> {
            val units = (value % 10).toInt()
            val prefix = when (units) {
                0 -> ""
                2, 3 -> small[units] + "ën"
                else -> small[units] + "en"
            }
            prefix + tens[(value / 10).toInt()]
        }
        value < 1_000 -> {
            val prefix = if (value < 200) "honderd" else small[(value / 100).toInt()] + "honderd"
            withRemainder(prefix, value % 100, separator = "")
        }
        value < 1_000_000 -> {
            val prefix = if (value < 2_000) "duizend" else cardinal(value / 1_000) + "duizend"
            withRemainder(prefix, value % 1_000, separator = " ")
        }
        else -> {
            val (scale, name) = scales.first { value >= it.first }
            withRemainder(cardinal(value / scale) + " " + name, value % scale, separator = " ")
        }
    }

    private fun withRemainder(prefix: String, remainder: Long, separator: String): String =
        if (remainder == 0L) prefix else prefix + separator + cardinal(remainder)
}
