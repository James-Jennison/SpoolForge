package net.jamesjennison.filamajignfc.core

/** Canonical validation for numeric GTIN text. Format-specific decoding happens at the camera boundary. */
object Gtin {
    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.length !in setOf(8, 12, 13, 14) || value.any { it !in '0'..'9' } || value.all { it == '0' }) return null
        val sum = value.dropLast(1).reversed().mapIndexed { index, c -> (c - '0') * if (index % 2 == 0) 3 else 1 }.sum()
        return if ((sum + (value.last() - '0')) % 10 == 0) value.padStart(14, '0') else null
    }
}
