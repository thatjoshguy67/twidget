package com.tjg.twidget.main

/** Full counts, including accounts above a billion; never abbreviate the editorial card's value. */
internal fun followerNumberWords(value: Long): String {
    require(value >= 0)
    val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    fun group(number: Int): String = buildList {
        if (number >= 100) {
            add(small[number / 100]); add("hundred")
            if (number % 100 != 0) add("and")
        }
        val rest = number % 100
        if (rest >= 20) {
            add(tens[rest / 10])
            if (rest % 10 != 0) add(small[rest % 10])
        } else if (rest > 0) add(small[rest])
    }.joinToString(" ")
    if (value == 0L) return small[0]
    val scales = listOf("", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion")
    var remainder = value
    val groups = mutableListOf<String>()
    var scale = 0
    while (remainder > 0) {
        val number = (remainder % 1000).toInt()
        if (number > 0) groups.add(group(number) + if (scale > 0) " ${scales[scale]}" else "")
        remainder /= 1000
        scale++
    }
    return groups.reversed().joinToString(", ")
}
