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
    @Volatile
    private var commonSet: Set<String> = emptySet()

    fun ensureLoaded(context: Context) {
        if (words.isNotEmpty()) return
        synchronized(this) {
            if (words.isNotEmpty()) return
            words = runCatching {
                context.applicationContext.assets.open("wordlist.txt").bufferedReader().useLines { seq ->
                    seq.map { it.trim().lowercase() }.filter { it.length >= 2 && it.all { c -> c in 'a'..'z' } }.toList()
                }
            }.getOrDefault(emptyList())
            commonSet = words.toHashSet()
        }
    }

    /** True if [word] is an ordinary English word (so phrase memory can skip it). */
    fun isCommon(context: Context, word: String): Boolean {
        ensureLoaded(context)
        return commonSet.contains(word.lowercase())
    }

    /**
     * Live suggestions for a partially typed [word]: frequency-ordered prefix
     * completions first (autocomplete), then near-misses within edit distance 1
     * (typo fixes, incl. transpositions like "teh" → "the").
     */
    fun suggest(word: String, limit: Int = 3): List<String> {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.length < 2 || words.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (cand in words) {
            if (cand.length > w.length && cand.startsWith(w)) {
                out.add(cand)
                if (out.size >= limit) return out.toList()
            }
        }
        var scanned = 0
        for (cand in words) {
            if (scanned++ > 12000) break
            if (cand == w || cand in out || kotlin.math.abs(cand.length - w.length) > 1) continue
            if (within1(cand, w)) {
                out.add(cand)
                if (out.size >= limit) break
            }
        }
        return out.toList()
    }

    /**
     * Confident offline autocorrect used the moment the user hits space. Returns a
     * lowercase replacement only when [word] is NOT already a real word and there's
     * a close (edit distance 1) common word; otherwise null. Conservative on
     * purpose (min length 3, must be a non-word with a near match) so it never
     * "fixes" intentional input that has no obvious correction.
     */
    fun autoFix(word: String): String? {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.length < 3 || words.isEmpty()) return null
        if (commonSet.contains(w)) return null
        var scanned = 0
        for (cand in words) { // frequency-ordered → first within-1 hit is the most common
            if (scanned++ > 20000) break
            if (cand.length < 2 || kotlin.math.abs(cand.length - w.length) > 1) continue
            if (within1(cand, w)) return cand
        }
        return null
    }

    /** Damerau-Levenshtein distance <= 1 (substitution / insert / delete / swap). */
    private fun within1(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (la == lb) {
            var diff = 0
            var firstDiff = -1
            for (i in 0 until la) if (a[i] != b[i]) {
                diff++
                if (firstDiff < 0) firstDiff = i
                if (diff > 2) return false
            }
            if (diff <= 1) return true
            // diff == 2: allow a single adjacent transposition
            val i = firstDiff
            return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] &&
                a.substring(i + 2) == b.substring(i + 2)
        }
        val longer = if (la > lb) a else b
        val shorter = if (la > lb) b else a
        var i = 0
        var j = 0
        var skipped = false
        while (i < longer.length && j < shorter.length) {
            if (longer[i] == shorter[j]) { i++; j++ } else {
                if (skipped) return false
                skipped = true
                i++
            }
        }
        return true
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
