package com.typefix.keyboard.ime

import android.content.Context

/**
 * Learns the user's repeat niche vocabulary — company names, acronyms, products,
 * people (e.g. "AppFolio", "Rentvine", "TTM", "T3M") — by counting words that
 * AREN'T ordinary English. Once a word is seen enough times it's "learned" and
 * treated like the personal dictionary: never corrected and never flagged.
 *
 * Counts live in memory (cheap, no per-keystroke disk writes); the learned set
 * is persisted. Only active when the user enables phrase memory.
 */
object PhraseMemory {

    private const val PREFS = "phrase_memory"
    private const val KEY_LEARNED = "learned"
    private const val THRESHOLD = 3

    private val counts = HashMap<String, Int>()
    @Volatile
    private var learned: MutableSet<String>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun learned(context: Context): Set<String> = ensure(context)

    private fun ensure(context: Context): MutableSet<String> {
        learned?.let { return it }
        synchronized(this) {
            learned?.let { return it }
            val set = prefs(context).getStringSet(KEY_LEARNED, emptySet())!!.toMutableSet()
            learned = set
            return set
        }
    }

    /** Records one occurrence of [rawWord]; promotes it to "learned" past the threshold. */
    fun record(context: Context, rawWord: String) {
        val word = rawWord.trim().trim('.', ',', '!', '?', ':', ';', '"', '\'', '(', ')')
        if (word.length < 2 || word.length > 40) return
        if (word.any { it.isDigit() } && word.all { it.isDigit() || it == '.' }) return // pure numbers
        if (!word.any { it.isLetter() }) return
        val set = ensure(context)
        if (set.any { it.equals(word, ignoreCase = true) }) return
        if (GestureDecoder.isCommon(context, word)) return // ordinary English — no need to learn
        val n = (counts[word] ?: 0) + 1
        counts[word] = n
        if (n >= THRESHOLD) {
            set.add(word)
            prefs(context).edit().putStringSet(KEY_LEARNED, set).apply()
        }
    }

    fun forget(context: Context, word: String) {
        val set = ensure(context)
        set.removeAll { it.equals(word, ignoreCase = true) }
        prefs(context).edit().putStringSet(KEY_LEARNED, set).apply()
    }

    fun clear(context: Context) {
        counts.clear()
        ensure(context).clear()
        prefs(context).edit().remove(KEY_LEARNED).apply()
    }
}
