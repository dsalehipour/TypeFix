package com.typefix.keyboard.ime

import android.content.Context

/**
 * A deliberately simple swipe/gesture decoder (FlorisBoard/Gboard do this with a
 * trained spatial model; this is a lightweight approximation):
 *
 *  - The finger path is reduced to the sequence of letter keys it crossed.
 *  - A candidate word must start with the first crossed key, end with the last,
 *    and have all its letters appear in order within the crossed sequence
 *    (subsequence match).
 *  - Among candidates we pick the most frequent (the wordlist is frequency
 *    ordered), which resolves most everyday words well.
 *
 * It won't be as accurate as a real gesture model, but it's a solid, offline,
 * dependency-free baseline.
 */
object GestureDecoder {

    @Volatile
    private var words: List<String> = emptyList()

    fun ensureLoaded(context: Context) {
        if (words.isNotEmpty()) return
        synchronized(this) {
            if (words.isNotEmpty()) return
            words = runCatching {
                context.applicationContext.assets.open("wordlist.txt").bufferedReader().useLines { seq ->
                    seq.map { it.trim().lowercase() }.filter { it.length >= 2 && it.all { c -> c in 'a'..'z' } }.toList()
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Returns the best-guess word for the crossed-key sequence, or null. */
    fun decode(crossed: String): String? {
        val path = crossed.lowercase().filter { it in 'a'..'z' }
        if (path.length < 2) return null
        val first = path.first()
        val last = path.last()

        var best: String? = null
        for (word in words) {
            if (word.first() != first || word.last() != last) continue
            if (word.length > path.length) continue
            if (isSubsequence(word, path)) {
                best = word
                break // words are frequency-ordered, so first match is most common
            }
        }
        return best
    }

    private fun isSubsequence(word: String, path: String): Boolean {
        var w = 0
        var p = 0
        while (w < word.length && p < path.length) {
            if (word[w] == path[p]) w++
            p++
        }
        return w == word.length
    }
}
