package com.typefix.keyboard.correction

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * A fast, deterministic, on-device spell check used as a post-correction
 * guardrail — the Android counterpart of macOS `TypoChecker`. After the model
 * rewrites text, we check whether it left (or introduced) a likely-misspelled
 * word so the user can be nudged, or auto-fix it with the top suggestion.
 *
 * Uses Android's built-in [SpellCheckerSession]. Proper nouns, all-caps
 * abbreviations and common chat shorthand are deliberately ignored so domain
 * terms and slang don't trigger false alarms.
 */
object SpellCheckGuard {

    private val allowedShorthand = setOf(
        "idk", "imo", "ngl", "tbh", "btw", "lol", "pls", "plz", "u", "ur", "r",
        "ooo", "eod", "mvp", "fyi", "afaik", "iirc", "rn", "asap", "ymmv", "tl", "dr",
    )

    private data class Finding(val start: Int, val length: Int, val word: String, val top: String?)

    suspend fun hasLikelyTypo(context: Context, text: String, allowing: List<String>): Boolean {
        val allowed = allowedSet(allowing)
        return analyze(context, text).any { looksLikeTypo(it.word, allowed) }
    }

    /** Returns [text] with each likely-misspelled word replaced by the top guess. */
    suspend fun autoFixed(context: Context, text: String, allowing: List<String>): String {
        val allowed = allowedSet(allowing)
        val replacements = analyze(context, text)
            .filter { looksLikeTypo(it.word, allowed) && !it.top.isNullOrEmpty() }
        if (replacements.isEmpty()) return text
        val sb = StringBuilder(text)
        // Apply back-to-front so earlier offsets stay valid.
        for (r in replacements.sortedByDescending { it.start }) {
            sb.replace(r.start, r.start + r.length, r.top!!)
        }
        return sb.toString()
    }

    private fun allowedSet(extra: List<String>): Set<String> =
        allowedShorthand + extra.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun looksLikeTypo(word: String, allowed: Set<String>): Boolean {
        val first = word.firstOrNull() ?: return false
        if (word.length < 3) return false
        if (first.isUpperCase()) return false
        if (word == word.uppercase()) return false
        if (word.any { it.isDigit() }) return false
        if (word.lowercase() in allowed) return false
        return true
    }

    private suspend fun analyze(context: Context, text: String): List<Finding> {
        if (text.isBlank()) return emptyList()
        return withTimeoutOrNull(1500L) { requestSuggestions(context, text) } ?: emptyList()
    }

    private suspend fun requestSuggestions(context: Context, text: String): List<Finding> =
        suspendCancellableCoroutine { cont ->
            val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE)
                as? TextServicesManager
            if (tsm == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            val main = Handler(Looper.getMainLooper())
            var session: SpellCheckerSession? = null

            val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
                override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
                    // We use sentence-level suggestions; nothing to do here.
                }

                override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                    val findings = mutableListOf<Finding>()
                    results?.forEach { sentence ->
                        for (i in 0 until sentence.suggestionsCount) {
                            val info = sentence.getSuggestionsInfoAt(i) ?: continue
                            val start = sentence.getOffsetAt(i)
                            val len = sentence.getLengthAt(i)
                            if (start < 0 || len <= 0 || start + len > text.length) continue
                            val word = text.substring(start, start + len)
                            val isTypo = (info.suggestionsAttributes and
                                SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
                            if (!isTypo) continue
                            val top = if (info.suggestionsCount > 0) info.getSuggestionAt(0) else null
                            findings.add(Finding(start, len, word, top))
                        }
                    }
                    if (cont.isActive) cont.resume(findings)
                    session?.close()
                }
            }

            main.post {
                try {
                    session = tsm.newSpellCheckerSession(null, Locale.ENGLISH, listener, true)
                    if (session == null) {
                        if (cont.isActive) cont.resume(emptyList())
                    } else {
                        session!!.getSentenceSuggestions(arrayOf(TextInfo(text)), 3)
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }

            cont.invokeOnCancellation { main.post { session?.close() } }
        }
}
