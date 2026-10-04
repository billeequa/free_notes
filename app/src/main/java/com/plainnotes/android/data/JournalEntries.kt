package com.plainnotes.android.data

import java.time.LocalDate

object JournalEntries {
    private val titlePattern = Regex("^(\\d{4}-\\d{2}-\\d{2})(?: ([IVXLCDM]+))?(?: Double X Day)?$")
    private val romanParts = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
        100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")

    fun nextTitle(date: LocalDate, existingTitles: List<String>): String {
        val highest = existingTitles.mapNotNull { title ->
            val match = titlePattern.matchEntire(title) ?: return@mapNotNull null
            if (match.groupValues[1] != date.toString()) return@mapNotNull null
            ordinal(match.groupValues[2])
        }.maxOrNull() ?: 0
        return if (highest == 0) date.toString() else "$date ${roman(highest + 1)}"
    }

    fun doubleXTitle(date: LocalDate, title: String): String {
        val match = titlePattern.matchEntire(title)
        val suffix = match?.groupValues?.get(2)?.takeIf { ordinal(it) != null && it.isNotEmpty() }
        return "$date${if (suffix == null) "" else " $suffix"} Double X Day"
    }

    fun dateFromTitle(title: String): LocalDate? =
        titlePattern.matchEntire(title)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun sequenceNumber(title: String): Int =
        titlePattern.matchEntire(title)?.groupValues?.get(2)?.let { ordinal(it) } ?: Int.MAX_VALUE

    private fun ordinal(value: String): Int? {
        if (value.isEmpty()) return 1
        var rest = value
        var number = 0
        for ((amount, token) in romanParts) {
            while (rest.startsWith(token)) { number += amount; rest = rest.removePrefix(token) }
        }
        return number.takeIf { it > 1 && rest.isEmpty() && roman(it) == value }
    }

    private fun roman(value: Int): String = buildString {
        var rest = value
        for ((amount, token) in romanParts) while (rest >= amount) { append(token); rest -= amount }
    }
}
