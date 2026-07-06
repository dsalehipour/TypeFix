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
 * sessions), and the learned set is persisted too. The user can also approve an
 * in-progress word (learn it now) or reject it (block it from being learned).
 * Only active when the user enables phrase memory.
 */
object PhraseMemory {

    const val THRESHOLD = 3

    private const val PREFS = "phrase_memory"
    private const val KEY_LEARNED = "learned"
    private const val KEY_COUNTS = "revert_counts"
    private const val KEY_BLOCKED = "blocked"

    @Volatile
    private var learned: MutableSet<String>? = null
    @Volatile
    private var counts: MutableMap<String, Int>? = null
    @Volatile
    private var blocked: MutableSet<String>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun learned(context: Context): Set<String> = ensureLearned(context)

    fun isLearned(context: Context, word: String): Boolean =
        ensureLearned(context).any { it.equals(word, ignoreCase = true) }

    /** Words currently being learned, most-progress first, as (word, count). */
    fun inProgress(context: Context): List<Pair<String, Int>> =
        ensureCounts(context).entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    private fun ensureLearned(context: Context): MutableSet<String> {
        learned?.let { return it }
        synchronized(this) {
            learned?.let { return it }
            val set = prefs(context).getStringSet(KEY_LEARNED, emptySet())!!.toMutableSet()
            learned = set
            return set
        }
    }

    private fun ensureBlocked(context: Context): MutableSet<String> {
        blocked?.let { return it }
        synchronized(this) {
            blocked?.let { return it }
            val set = prefs(context).getStringSet(KEY_BLOCKED, emptySet())!!.toMutableSet()
            blocked = set
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
        val key = word.lowercase()
        if (ensureBlocked(context).contains(key)) return false // user rejected it
        val learnedSet = ensureLearned(context)
        if (learnedSet.any { it.equals(word, ignoreCase = true) }) return false
        val map = ensureCounts(context)
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

    /** Approve an in-progress word: learn it now (skips the remaining reverts). */
    fun learnNow(context: Context, word: String) {
        val clean = clean(word) ?: return
        val map = ensureCounts(context)
        map.remove(clean.lowercase())
        saveCounts(context, map)
        val set = ensureLearned(context)
        if (set.none { it.equals(clean, ignoreCase = true) }) {
            set.add(clean)
            prefs(context).edit().putStringSet(KEY_LEARNED, set).apply()
        }
    }

    /** Reject an in-progress word: drop its progress and never learn it again. */
    fun reject(context: Context, word: String) {
        val key = (clean(word) ?: word).lowercase()
        val map = ensureCounts(context)
        map.remove(key)
        saveCounts(context, map)
        val blockedSet = ensureBlocked(context)
        blockedSet.add(key)
        prefs(context).edit().putStringSet(KEY_BLOCKED, blockedSet).apply()
    }

    fun forget(context: Context, word: String) {
        val set = ensureLearned(context)
        set.removeAll { it.equals(word, ignoreCase = true) }
        prefs(context).edit().putStringSet(KEY_LEARNED, set).apply()
    }

    fun clear(context: Context) {
        ensureCounts(context).clear()
        ensureLearned(context).clear()
        ensureBlocked(context).clear()
        prefs(context).edit().remove(KEY_LEARNED).remove(KEY_COUNTS).remove(KEY_BLOCKED).apply()
    }

    private fun clean(rawWord: String): String? {
        val word = rawWord.trim().trim('.', ',', '!', '?', ':', ';', '"', '\'', '(', ')')
        if (word.length < 2 || word.length > 40) return null
        if (!word.any { it.isLetter() }) return null
        if (word.all { it.isDigit() || it == '.' }) return null // pure numbers
        return word
    }
}
