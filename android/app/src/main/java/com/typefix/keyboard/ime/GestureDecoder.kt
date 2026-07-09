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
        // the standalone pronoun
        "i" to "I",
        // contraction stubs
        "im" to "I'm", "ive" to "I've", "youre" to "you're", "youve" to "you've",
        "youll" to "you'll", "youd" to "you'd", "hes" to "he's", "shes" to "she's",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll",
        "theyd" to "they'd", "weve" to "we've",
        "thats" to "that's", "whats" to "what's", "wheres" to "where's",
        "hows" to "how's", "whos" to "who's", "theres" to "there's",
        "heres" to "here's", "lets" to "let's", "thatll" to "that'll",
        "itll" to "it'll", "dont" to "don't", "doesnt" to "doesn't",
        "didnt" to "didn't", "isnt" to "isn't", "arent" to "aren't",
        "wasnt" to "wasn't", "werent" to "weren't", "hasnt" to "hasn't",
        "havent" to "haven't", "hadnt" to "hadn't", "wont" to "won't",
        "cant" to "can't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't", "mustnt" to "mustn't", "neednt" to "needn't",
        "wouldve" to "would've", "couldve" to "could've", "shouldve" to "should've",
        "mightve" to "might've", "mustve" to "must've", "aint" to "ain't",
        "yall" to "y'all", "cmon" to "c'mon", "maam" to "ma'am", "oclock" to "o'clock",
        // frequent misspellings
        "hav" to "have", "teh" to "the", "alot" to "a lot", "recieve" to "receive",
        "recieved" to "received", "definately" to "definitely",
        "definatly" to "definitely", "definetly" to "definitely",
        "seperate" to "separate", "seperately" to "separately", "untill" to "until",
        "occured" to "occurred", "occurance" to "occurrence", "becuase" to "because",
        "becasue" to "because", "wich" to "which", "thier" to "their",
        "freind" to "friend", "beleive" to "believe", "beleived" to "believed",
        "wierd" to "weird", "accross" to "across", "agian" to "again",
        "tomorow" to "tomorrow", "tommorow" to "tomorrow", "tommorrow" to "tomorrow",
        "goverment" to "government", "enviroment" to "environment",
        "acheive" to "achieve", "adress" to "address", "arguement" to "argument",
        "embarass" to "embarrass", "occassion" to "occasion", "publically" to "publicly",
        "wanna" to "wanna", "gonna" to "gonna",
        // adjacent-key slips too short for the fuzzy matcher (min length 3)
        "uo" to "up",
    )

    /** Words that exist in the big dictionary but are near-always typos when typed
     *  in lowercase (e.g. "od" is a real-but-obscure noun; typed it's almost always
     *  "of"). Skipped for ALL-CAPS input so abbreviations like "OD" survive. */
    private val RARE_WORD_FIXES: Map<String, String> = mapOf(
        "od" to "of",
    )

    /** Fixes that depend on capitalization: only applied when the word is written
     *  as a sentence-start capital, to disambiguate from a real lowercase word
     *  (e.g. "Ill" -> "I'll", but leave "ill" = sick alone). */
    private val CASED_FIXES: Map<String, String> = mapOf(
        "Ill" to "I'll",
        "Id" to "I'd",
    )

    /** For a digit typed inside a word, the letters it plausibly stands for: the
     *  QWERTY key directly below it (a number-row slip: intending "o" but hitting
     *  "9" gives "h9w"), plus common look-alikes (0→o, 1→l, 5→s). */
    private val DIGIT_SLIPS: Map<Char, String> = mapOf(
        '1' to "ql", '2' to "w", '3' to "e", '4' to "r", '5' to "ts",
        '6' to "y", '7' to "u", '8' to "i", '9' to "o", '0' to "po",
    )

    /** QWERTY neighbors (same row + adjacent rows) of each letter key, used to
     *  tell an accidental extra keypress from a misplaced space. */
    private val KEY_NEIGHBORS: Map<Char, String> = mapOf(
        'a' to "qswz", 'b' to "ghnv", 'c' to "dfvx", 'd' to "cefrsx",
        'e' to "drsw", 'f' to "cdgrtv", 'g' to "bfhtvy", 'h' to "bgjnuy",
        'i' to "jkou", 'j' to "hikmnu", 'k' to "ijlmo", 'l' to "kop",
        'm' to "jkn", 'n' to "bhjm", 'o' to "iklp", 'p' to "lo",
        'q' to "aw", 'r' to "deft", 's' to "adewxz", 't' to "fgry",
        'u' to "hijy", 'v' to "bcfg", 'w' to "aeqs", 'x' to "cdsz",
        'y' to "ghtu", 'z' to "asx",
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
        if (words.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val w: String
        if (word.any { it in '0'..'9' }) {
            // An interior digit is a number-row slip ("h9w"): suggest for the
            // repaired reading instead of showing nothing.
            val repairs = digitSlipRepairs(word)
            if (repairs.isEmpty()) return emptyList()
            w = repairs.firstOrNull { commonSet.contains(it) } ?: repairs.first()
            if (commonSet.contains(w)) out.add(w) // the repaired word itself
        } else {
            w = word.lowercase().filter { it in 'a'..'z' }
        }
        if (w.length < 2) return out.toList()
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
        // Case-sensitive fixes for homographs that are only typos when capitalized
        // like a sentence start (e.g. "Ill" -> "I'll", but leave lowercase "ill" = sick).
        CASED_FIXES[word]?.let { return it }
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return null
        // Forced fixes (contraction stubs + frequent typos, plus the standalone "i")
        // win over the dictionary AND the wordlist-load state, since the broad
        // dictionary lists informal stubs like "hav"/"dont"/"wont" as words and
        // can't insert the apostrophe these need.
        COMMON_FIXES[w]?.let { return it }
        // Real-but-obscure dictionary words that are near-always typos ("od" → "of"),
        // unless typed ALL-CAPS (an abbreviation like "OD").
        val letters = word.filter { it.isLetter() }
        val allCaps = letters.length > 1 && letters.all { it.isUpperCase() }
        if (!allCaps) RARE_WORD_FIXES[w]?.let { return it }
        if (words.isEmpty()) return null
        // A digit sandwiched between letters is a number-row slip ("h9w" → "how",
        // "ah9uld" → "should"): substitute the letter under that digit key and see
        // if a real (or near-real) word comes out. Digits at a word's edge are left
        // alone here — those are usually intentional ("9am", "mp3") and the plain
        // stripped path below already covers trailing slips like "hav3".
        val repairs = digitSlipRepairs(word)
        for (repair in repairs) {
            COMMON_FIXES[repair]?.let { return it }
            if (validSet.contains(repair) || commonSet.contains(repair)) return repair
        }
        for (repair in repairs) {
            fuzzyFix(repair)?.let { return it }
        }
        if (w.length < 3) return null
        // A stray digit/symbol inside a word (e.g. "hav3", "hello2") is a typo too.
        val hadStrayChars = w.length != word.length
        // A genuine word (per the big dictionary or the common list) is never a
        // typo — this is what stops "heck" being "corrected" to "check".
        if (validSet.contains(w) || commonSet.contains(w)) {
            return if (hadStrayChars) w else null
        }
        fuzzyFix(w)?.let { return it }
        // Last resort for longer words: two adjacent-key substitutions against a
        // very common same-length word ("whouls" → "should", w/s and s/d are
        // neighboring keys). Requiring both slips to be keyboard neighbors keeps
        // names and slang ("spotify", "yeeted") from being mangled.
        if (w.length >= 6) {
            var rank = 0
            for (cand in words) {
                if (rank++ > 4000) break
                if (cand.length != w.length) continue
                if (within2NeighborSubs(cand, w)) return cand
            }
        }
        return null
    }

    /**
     * Fixes a space typed one character too early, splitting a contraction into
     * the next word: "that scool" → "that's cool", "i mcool" → "I'm cool". Fires
     * only when the typed [word] is NOT a real word, the previous word plus the
     * stray first letter is a known contraction stub, and what's left is a real
     * word. Returns the fixed (prev, word) pair, lowercase.
     */
    fun autoFixSpaceSlip(context: Context, prev: String, word: String): Pair<String, String>? {
        ensureLoaded(context)
        ensureValidLoaded(context)
        if (words.isEmpty()) return null
        val p = prev.lowercase().filter { it in 'a'..'z' }
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (p.isEmpty() || w.length < 3 || w.length != word.length || p.length != prev.length) return null
        if (validSet.contains(w) || commonSet.contains(w)) return null
        val fixedPrev = COMMON_FIXES[p + w.first()] ?: return null
        val rest = w.drop(1)
        if (!validSet.contains(rest) && !commonSet.contains(rest)) return null
        // When the stray letter sits right next to the following key it's more
        // likely an accidental double press than a misplaced space — leave that
        // to the single-word fuzzy fix ("sdid" → "did", not "'s did").
        if (KEY_NEIGHBORS[w[0]]?.contains(w[1]) == true) return null
        return fixedPrev to rest
    }

    /** First (most frequent) wordlist entry within Damerau-Levenshtein distance 1. */
    private fun fuzzyFix(w: String): String? {
        if (w.length < 3) return null
        var scanned = 0
        for (cand in words) { // frequency-ordered → first within-1 hit is the most common
            if (scanned++ > 20000) break
            if (cand.length < 2 || kotlin.math.abs(cand.length - w.length) > 1) continue
            if (within1(cand, w)) return cand
        }
        return null
    }

    /**
     * Candidate letter-only readings of a word whose interior digits were
     * number-row slips ("h9w" → ["how"], "ah9uld" → ["ahould"]). Empty when the
     * word has no digits, too many, or a digit at either edge (usually real:
     * "9am", "v2", "win10").
     */
    private fun digitSlipRepairs(word: String): List<String> {
        val w = word.lowercase()
        val digits = w.count { it in '0'..'9' }
        if (digits == 0 || digits > 2) return emptyList()
        for (i in w.indices) {
            if (w[i] !in '0'..'9') continue
            if (i == 0 || i == w.length - 1) return emptyList()
            if (w[i - 1] !in 'a'..'z' || w[i + 1] !in 'a'..'z') return emptyList()
        }
        var variants = listOf("")
        for (ch in w) {
            variants = when (ch) {
                in 'a'..'z' -> variants.map { it + ch }
                in '0'..'9' -> {
                    val options = DIGIT_SLIPS[ch] ?: return emptyList()
                    variants.flatMap { v -> options.map { v + it } }
                }
                else -> variants
            }
        }
        return variants
    }

    /** At-most-two-substitution match for same-length strings where every
     *  substituted pair sits on neighboring keys (a fat-finger slip, not a
     *  different word). 0/1 diffs were already handled by the within-1 pass. */
    private fun within2NeighborSubs(a: String, b: String): Boolean {
        var diff = 0
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            if (++diff > 2) return false
            if (KEY_NEIGHBORS[a[i]]?.contains(b[i]) != true) return false
        }
        return true
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

    /**
     * Shape-based swipe decoder (SHARK²-style): instead of the crude "is the word a
     * subsequence of the crossed keys" heuristic, this scores each candidate by how
     * closely the actual finger path matches the ideal path through that word's keys.
     *
     * @param points  finger samples in screen coordinates (start..end).
     * @param centers map of letter -> on-screen key center [x,y].
     * @param keyW    average key width, used for tolerances/thresholds.
     */
    fun decodeGesture(points: List<FloatArray>, centers: Map<Char, FloatArray>, keyW: Float): String? {
        if (words.isEmpty() || points.size < 2 || centers.isEmpty() || keyW <= 0f) return null
        val n = 24
        val g = resample(points, n)
        val start = points.first()
        val end = points.last()
        val anchorSq = (keyW * 1.6f) * (keyW * 1.6f)
        val total = words.size.toFloat()
        var best: String? = null
        var bestScore = Float.MAX_VALUE
        var rank = -1
        for (word in words) {
            rank++
            if (word.length < 2) continue
            // The first/last letters are anchored near where the swipe began/ended.
            val firstC = centers[word[0]] ?: continue
            val lastC = centers[word[word.length - 1]] ?: continue
            if (distSq(firstC, start) > anchorSq || distSq(lastC, end) > anchorSq) continue
            val poly = ArrayList<FloatArray>(word.length)
            var ok = true
            for (c in word) { val ce = centers[c]; if (ce == null) { ok = false; break }; poly.add(ce) }
            if (!ok) continue
            val rw = resample(poly, n)
            var sum = 0f
            for (i in 0 until n) sum += dist(g[i], rw[i])
            // Mild bias toward more frequent words for near-ties.
            val score = sum / n + (rank / total) * (keyW * 0.5f)
            if (score < bestScore) { bestScore = score; best = word }
        }
        // Reject obvious non-matches so we can fall back to the starting letter.
        return if (bestScore <= keyW * 1.9f) best else null
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun distSq(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]
        return dx * dx + dy * dy
    }

    /** Resample a path to exactly [n] points spaced evenly along its length ($1-style). */
    private fun resample(pts: List<FloatArray>, n: Int): List<FloatArray> {
        if (pts.isEmpty()) return List(n) { floatArrayOf(0f, 0f) }
        if (pts.size == 1) return List(n) { pts[0].copyOf() }
        var pathLen = 0f
        for (i in 1 until pts.size) pathLen += dist(pts[i - 1], pts[i])
        if (pathLen <= 0f) return List(n) { pts[0].copyOf() }
        val interval = pathLen / (n - 1)
        val out = ArrayList<FloatArray>(n)
        out.add(pts[0].copyOf())
        var prev = pts[0]
        var acc = 0f
        var i = 1
        val work = ArrayList(pts)
        while (i < work.size) {
            val cur = work[i]
            val d = dist(prev, cur)
            if (d > 0f && acc + d >= interval) {
                val t = (interval - acc) / d
                val np = floatArrayOf(prev[0] + t * (cur[0] - prev[0]), prev[1] + t * (cur[1] - prev[1]))
                out.add(np)
                work.add(i, np) // continue measuring from the inserted point
                prev = np
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < n) out.add(pts[pts.size - 1].copyOf())
        return if (out.size > n) out.subList(0, n) else out
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
