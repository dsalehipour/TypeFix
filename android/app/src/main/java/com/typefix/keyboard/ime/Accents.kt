package com.typefix.keyboard.ime

/**
 * Long-press alternates for keys, à la Samsung/Gboard. Letters expose accented
 * variants; the number row and a few punctuation keys expose related symbols.
 */
object Accents {
    private val map: Map<String, List<String>> = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"),
        "i" to listOf("î", "ï", "í", "ī", "į", "ì"),
        "l" to listOf("ł"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        "s" to listOf("ß", "ś", "š"),
        "u" to listOf("û", "ü", "ù", "ú", "ū"),
        "y" to listOf("ÿ"),
        "z" to listOf("ž", "ź", "ż"),
        // number row → symbols
        "1" to listOf("¡", "1"),
        "2" to listOf("²"),
        "3" to listOf("³"),
        "4" to listOf("¼", "£"),
        "5" to listOf("½", "%"),
        "6" to listOf("¾"),
        "7" to listOf("¥"),
        "8" to listOf("•", "°"),
        "9" to listOf("(", "<"),
        "0" to listOf(")", ">", "ø"),
        // punctuation
        "." to listOf(",", "?", "!", ";", ":", "…"),
        "," to listOf(";", "'"),
        "-" to listOf("—", "–", "_", "·"),
        "?" to listOf("¿"),
        "\"" to listOf("“", "”", "«", "»"),
        "'" to listOf("‘", "’"),
    )

    fun alternatesFor(key: String): List<String> = map[key].orEmpty()
}
