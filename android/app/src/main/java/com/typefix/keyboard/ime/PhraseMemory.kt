package com.typefix.keyboard.ime

import android.content.Context
import org.json.JSONObject

/**
 * Learns the user's niche vocabulary from an explicit, unambiguous signal: when
 * autocorrect-on-space changes a word, the user backspaces to revert it, and then
 * KEEPS that original word (moves on past it). Each kept revert is counted; once a
 * word reaches [THRESHOLD] kept reverts it's "learned" and treated like the
 * personal dictionary — never auto-corrected and never changed by the LLM.
 *
 * Counts persist across sessions (a word is usually reverted in separate typing
 * sessions), and the learned set is persisted too. Only active when the user
 * enables phrase memory.
 */
object PhraseMemory {

    private const val PREFS = "phrase_memory"
    private const val KEY_LEARNED = "learned"
    private const val KEY_COUNTS = "revert_counts"
    private const val THRESHOLD = 3

    @Volatile
    private var learned: MutableSet<String>? = null
    @Volatile
    private var counts: MutableMap<String, Int>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun learned(context: Context): Set<String> = ensureLearned(context)

    fun isLearned(context: Context, word: String): Boolean =
        ensureLearned(context).any { it.equals(word, ignoreCase = true) }

    private fun ensureLearned(context: Context): MutableSet<String> {
        learned?.let { return it }
        synchronized(this) {
            learned?.let { return it }
            val set = prefs(context).getStringSet(KEY_LEARNED, emptySet())!!.toMutableSet()
            learned = set
            return set
        }
    }

    private fun ensureCounts(context: Context): MutableMap<String, Int> {
        counts?.let { return it }
        synchronized(this) {
            counts?.let { return it }
            val map = HashMap<String, Int>()
            runCatching {
                val raw = prefs(context).getString(KEY_COUNTS, null) ?: "{}"
                val obj = JSONObject(raw)
                obj.keys().forEach { k -> map[k] = obj.optInt(k) }
            }
            counts = map
            return map
        }
    }

    private fun saveCounts(context: Context, map: Map<String, Int>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit().putString(KEY_COUNTS, obj.toString()).apply()
    }

    /**
     * Records one "the user reverted autocorrect for this word and kept it" event.
     * Returns true if this push crossed the threshold and the word was just learned.
     */
    fun recordKeptRevert(context: Context, rawWord: String): Boolean {
        val word = clean(rawWord) ?: return false
        val learnedSet = ensureLearned(context)
        if (learnedSet.any { it.equals(word, ignoreCase = true) }) return false
        val map = ensureCounts(context)
        val key = word.lowercase()
        val n = (map[key] ?: 0) + 1
        map[key] = n
        if (n >= THRESHOLD) {
            map.remove(key)
            saveCounts(context, map)
            learnedSet.add(word)
            prefs(context).edit().putStringSet(KEY_LEARNED, learnedSet).apply()
            return true
        }
        saveCounts(context, map)
        return false
    }

    fun forget(context: Context, word: String) {
        val set = ensureLearned(context)
        set.removeAll { it.equals(word, ignoreCase = true) }
        prefs(context).edit().putStringSet(KEY_LEARNED, set).apply()
    }

    fun clear(context: Context) {
        ensureCounts(context).clear()
        ensureLearned(context).clear()
        prefs(context).edit().remove(KEY_LEARNED).remove(KEY_COUNTS).apply()
    }

    private fun clean(rawWord: String): String? {
        val word = rawWord.trim().trim('.', ',', '!', '?', ':', ';', '"', '\'', '(', ')')
        if (word.length < 2 || word.length > 40) return null
        if (!word.any { it.isLetter() }) return null
        if (word.all { it.isDigit() || it == '.' }) return null // pure numbers
        return word
    }
}
