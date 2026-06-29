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

    /**
     * High-confidence corrections applied on space even though some appear in the
     * broad dictionary: contraction stubs (which also need an apostrophe the fuzzy
     * matcher can't add) and very common misspellings. Deliberately excludes
     * ambiguous homographs (its, well, ill, id, were, wed, shell, hell).
     */
    private val COMMON_FIXES: Map<String, String> = mapOf(
        // contraction stubs
        "im" to "I'm", "ive" to "I've", "youre" to "you're", "youve" to "you've",
        "youll" to "you'll", "youd" to "you'd", "hes" to "he's", "shes" to "she's",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll",
        "theyd" to "they'd", "weve" to "we've",
        "thats" to "that's", "whats" to "what's", "wheres" to "where's",
        "hows" to "how's", "whos" to "who's", "theres" to "there's",
        "heres" to "here's", "dont" to "don't", "doesnt" to "doesn't",
        "didnt" to "didn't", "isnt" to "isn't", "arent" to "aren't",
        "wasnt" to "wasn't", "werent" to "weren't", "hasnt" to "hasn't",
        "havent" to "haven't", "hadnt" to "hadn't", "wont" to "won't",
        "cant" to "can't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't", "mustnt" to "mustn't", "neednt" to "needn't",
        "wouldve" to "would've", "couldve" to "could've", "shouldve" to "should've",
        "mightve" to "might've", "aint" to "ain't",
        // frequent misspellings
        "hav" to "have", "teh" to "the", "alot" to "a lot", "recieve" to "receive",
        "definately" to "definitely", "seperate" to "separate", "untill" to "until",
        "occured" to "occurred", "becuase" to "because", "becasue" to "because",
        "wich" to "which", "thier" to "their", "freind" to "friend",
        "beleive" to "believe", "wierd" to "weird", "accross" to "across",
        "agian" to "again", "tommorow" to "tomorrow", "tommorrow" to "tomorrow",
        "goverment" to "government", "enviroment" to "environment",
        "wanna" to "wanna", "gonna" to "gonna",
    )

    @Volatile
    private var words: List<String> = emptyList()
    @Volatile
    private var commonSet: Set<String> = emptySet()
    // A large curated dictionary used ONLY to decide "is this typed word a real
    // word (so autocorrect should leave it alone)?". Loaded lazily the first time
    // autocorrect runs, because it's ~370k words (autocorrect is off by default).
    @Volatile
    private var validSet: Set<String> = emptySet()

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

    fun ensureValidLoaded(context: Context) {
        if (validSet.isNotEmpty()) return
        synchronized(this) {
            if (validSet.isNotEmpty()) return
            validSet = runCatching {
                context.applicationContext.assets.open("words_valid.txt").bufferedReader().useLines { seq ->
                    seq.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
                }
            }.getOrDefault(emptySet())
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
    fun autoFix(context: Context, word: String): String? {
        ensureLoaded(context)
        ensureValidLoaded(context)
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.length < 2 || words.isEmpty()) return null
        // Forced fixes (contraction stubs + frequent typos) win over the dictionary,
        // since the broad dictionary lists informal stubs like "hav"/"dont"/"wont"
        // as words and can't insert the apostrophe these need.
        COMMON_FIXES[w]?.let { return it }
        if (w.length < 3) return null
        // A stray digit/symbol inside a word (e.g. "hav3", "hello2") is a typo too.
        val hadStrayChars = w.length != word.length
        // A genuine word (per the big dictionary or the common list) is never a
        // typo — this is what stops "heck" being "corrected" to "check".
        if (validSet.contains(w) || commonSet.contains(w)) {
            return if (hadStrayChars) w else null
        }
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
