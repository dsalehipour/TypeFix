package com.typefix.keyboard.inference

import android.content.Context
import com.typefix.keyboard.correction.CorrectionText
import com.typefix.keyboard.correction.SpellCheckGuard
import com.typefix.keyboard.ime.PhraseMemory
import com.typefix.keyboard.model.Provider
import com.typefix.keyboard.settings.SettingsSnapshot

/**
 * High-level orchestrator: picks a backend from settings, runs the model,
 * cleans the output, and applies the spell-check guardrail. This is the Android
 * equivalent of macOS `CorrectionEngine.runCorrection` (minus the UI/state
 * machine, which lives in the IME).
 */
object Corrector {

    sealed interface Result {
        /** [changed] is false when the model returned text identical to the input. */
        data class Fixed(
            val text: String,
            val changed: Boolean,
            val possibleTypo: Boolean,
        ) : Result

        data class Failed(val message: String) : Result
    }

    suspend fun correct(context: Context, original: String, s: SettingsSnapshot): Result {
        if (original.isBlank()) return Result.Fixed(original, changed = false, possibleTypo = false)

        // Protect the user's own learned vocabulary so it's never "corrected".
        val protectedAll = if (s.phraseMemoryEnabled) {
            s.protectedWords + PhraseMemory.learned(context)
        } else {
            s.protectedWords
        }
        val systemPrompt = CorrectionText.composedPrompt(protectedAll)

        val raw = try {
            engineFor(context, s)?.generate(systemPrompt, original)
                ?: return Result.Failed(setupHint(context, s))
        } catch (t: Throwable) {
            return Result.Failed(t.message ?: "Correction failed")
        }

        var cleaned = CorrectionText.clean(raw, original)
        // A model that returns nothing usable must never wipe the user's draft —
        // tiny/over-quantized models sometimes emit empty output. Treat as a no-op.
        if (cleaned.isBlank()) return Result.Failed("No correction returned")
        if (s.autoFixResidualTypos) {
            cleaned = SpellCheckGuard.autoFixed(context, cleaned, protectedAll)
        }
        val possibleTypo = s.spellCheckAfterCorrection &&
            SpellCheckGuard.hasLikelyTypo(context, cleaned, protectedAll)

        return Result.Fixed(cleaned, changed = cleaned != original, possibleTypo = possibleTypo)
    }

    /** Tone flags the detector can return, mapped to a friendly heads-up. */
    val toneLabels: Map<String, String> = linkedMapOf(
        "defensive" to "This may sound defensive",
        "cold" to "This sounds colder than you may intend",
        "too-many-questions" to "This asks a lot at once",
        "too-long" to "This is long for a message",
        "over-apologizing" to "You're apologizing a lot",
        "passive-aggressive" to "This may read passive-aggressive",
    )

    /** Returns a tone flag (a key of [toneLabels]) for [text], or null if it's fine. */
    suspend fun checkTone(context: Context, text: String, s: SettingsSnapshot): String? {
        if (text.trim().length < 12) return null
        val engine = try {
            engineFor(context, s) ?: return null
        } catch (t: Throwable) {
            return null
        }
        val prompt = "You check the tone of a short draft message. Reply with ONE label that " +
            "best applies, or 'none' if the tone is fine. Labels: defensive, cold, " +
            "too-many-questions, too-long, over-apologizing, passive-aggressive, none. " +
            "Output ONLY the label."
        val raw = try {
            engine.generate(prompt, text)
        } catch (t: Throwable) {
            return null
        }
        val norm = raw.lowercase().replace(' ', '-')
        return toneLabels.keys.firstOrNull { norm.contains(it) }
    }

    /** Rewrites [text] to fix the given tone [flag]. */
    suspend fun rewriteForTone(context: Context, text: String, flag: String, s: SettingsSnapshot): String? {
        val engine = try {
            engineFor(context, s) ?: return null
        } catch (t: Throwable) {
            return null
        }
        val guidance = when (flag) {
            "defensive" -> "Make it sound open and non-defensive."
            "cold" -> "Make it warmer and friendlier."
            "too-many-questions" -> "Keep only the single most important question."
            "too-long" -> "Make it much shorter and to the point."
            "over-apologizing" -> "Remove excessive apologies; keep it kind but confident."
            "passive-aggressive" -> "Make it direct and neutral, not passive-aggressive."
            else -> "Improve the tone."
        }
        val prompt = "Rewrite the message to fix its tone. $guidance Keep the meaning and key " +
            "details. Output ONLY the rewritten message, nothing else."
        val raw = try {
            engine.generate(prompt, text)
        } catch (t: Throwable) {
            return null
        }
        return CorrectionText.clean(raw, text).ifBlank { null }
    }

    /**
     * Asks the model for a short GIF search phrase that captures the message's
     * mood/reaction (so the GIF panel can suggest contextually, like emoji).
     * Returns null if no backend is ready or nothing usable comes back.
     */
    suspend fun suggestGifQuery(context: Context, text: String, s: SettingsSnapshot): String? {
        if (text.isBlank()) return null
        val engine = try {
            engineFor(context, s) ?: return null
        } catch (t: Throwable) {
            return null
        }
        val prompt = "From the message, give a short GIF search phrase (2 to 4 words) that captures " +
            "its mood or reaction, for finding a reaction GIF. Output ONLY the phrase — no quotes, " +
            "no punctuation, no explanation."
        val raw = try {
            engine.generate(prompt, text)
        } catch (t: Throwable) {
            return null
        }
        val q = CorrectionText.clean(raw, "").lineSequence().firstOrNull()?.trim().orEmpty()
        return q.takeIf { it.isNotBlank() && it.length <= 40 }
    }

    /** Rewrites a rambling voice transcript into a concise written message. */
    suspend fun cleanupVoice(context: Context, transcript: String, s: SettingsSnapshot): String? {
        if (transcript.isBlank()) return null
        val engine = try {
            engineFor(context, s) ?: return null
        } catch (t: Throwable) {
            return null
        }
        val prompt = "Rewrite this spoken, rambling transcript into a clear, concise written " +
            "message. Keep the meaning and every key detail; remove filler and false starts; " +
            "fix grammar and punctuation. Output ONLY the rewritten message, nothing else."
        val raw = try {
            engine.generate(prompt, transcript)
        } catch (t: Throwable) {
            return null
        }
        return CorrectionText.clean(raw, transcript).ifBlank { null }
    }

    /**
     * Asks the configured model for emojis that fit [text]. Returns an empty
     * list if no backend is ready or parsing fails (callers fall back to the
     * local [com.typefix.keyboard.ime.EmojiSuggester]).
     */
    suspend fun suggestEmojis(context: Context, text: String, s: SettingsSnapshot): List<String> {
        if (text.isBlank()) return emptyList()
        val engine = try {
            engineFor(context, s) ?: return emptyList()
        } catch (t: Throwable) {
            return emptyList()
        }
        val prompt = "Suggest 8 emojis that fit the tone and content of the message. " +
            "Output ONLY the emojis separated by spaces, no words, no explanations."
        val raw = try {
            engine.generate(prompt, text)
        } catch (t: Throwable) {
            return emptyList()
        }
        // Split into individual emojis by grapheme cluster (not by spaces): small
        // models often run the emojis together, which would otherwise become one
        // chip that pastes 8 emojis at once.
        return toSingleEmojis(raw).take(8)
    }

    /**
     * Semantic emoji search: asks the model for emojis matching a free-text
     * query (so "celebrate", "feeling down", etc. work without exact keywords).
     * Returns empty if no backend is ready (callers fall back to the local
     * [com.typefix.keyboard.ime.EmojiSearchIndex]).
     */
    suspend fun searchEmojis(context: Context, query: String, s: SettingsSnapshot): List<String> {
        if (query.isBlank()) return emptyList()
        val engine = try {
            engineFor(context, s) ?: return emptyList()
        } catch (t: Throwable) {
            return emptyList()
        }
        val prompt = "You are an emoji search engine. For the user's search query, return up to " +
            "12 relevant emojis ordered best-first. Output ONLY the emojis separated by spaces, " +
            "no words, no punctuation, no explanations."
        val raw = try {
            engine.generate(prompt, query)
        } catch (t: Throwable) {
            return emptyList()
        }
        return toSingleEmojis(raw).take(12)
    }

    /** Breaks a model's emoji output into individual emojis by grapheme cluster,
     *  so combined/space-less output ("😂😭🔥") yields one entry per emoji while
     *  multi-codepoint emojis (skin tones, ZWJ families, flags) stay intact. */
    private fun toSingleEmojis(raw: String): List<String> {
        val out = ArrayList<String>()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(raw)
        var start = it.first()
        var end = it.next()
        while (end != java.text.BreakIterator.DONE) {
            val cluster = raw.substring(start, end).trim()
            if (cluster.isNotEmpty() && cluster.any { ch -> ch.code > 0x2000 }) out.add(cluster)
            start = end
            end = it.next()
        }
        return out.distinct()
    }

    /** True if a backend is configured & ready — callers use this to decide
     *  whether to show an LLM "thinking" UI before actually calling. */
    fun isBackendReady(context: Context, s: SettingsSnapshot): Boolean = when (s.provider) {
        Provider.LOCAL -> ModelManager.isInstalled(context, s.localModelId.ifBlank { s.model })
        Provider.OPENAI, Provider.ANTHROPIC -> s.apiKey.isNotBlank()
        Provider.CUSTOM -> s.baseUrl.isNotBlank() && s.model.isNotBlank()
    }

    /** Resolves the backend, or returns null when the provider isn't set up. */
    private suspend fun engineFor(context: Context, s: SettingsSnapshot): InferenceEngine? {
        return when (s.provider) {
            Provider.LOCAL -> {
                val id = s.localModelId.ifBlank { s.model }
                if (!ModelManager.isInstalled(context, id)) return null
                // The local engine isn't a throwaway object — it's the shared,
                // already-loaded model. Wrap it so generate() goes through it.
                object : InferenceEngine {
                    override suspend fun generate(systemPrompt: String, text: String) =
                        InferenceController.generate(context, id, systemPrompt, text)
                }
            }

            Provider.OPENAI -> {
                if (s.apiKey.isBlank()) return null
                OpenAiCompatibleEngine(
                    baseUrl = "https://api.openai.com/v1",
                    apiKey = s.apiKey,
                    model = s.model,
                    providerLabel = "OpenAI",
                )
            }

            Provider.ANTHROPIC -> {
                if (s.apiKey.isBlank()) return null
                AnthropicEngine(apiKey = s.apiKey, model = s.model)
            }

            Provider.CUSTOM -> {
                if (s.baseUrl.isBlank() || s.model.isBlank()) return null
                OpenAiCompatibleEngine(
                    baseUrl = s.baseUrl,
                    apiKey = s.apiKey, // optional for local servers
                    model = s.model,
                    providerLabel = "Custom endpoint",
                )
            }
        }
    }

    private fun setupHint(context: Context, s: SettingsSnapshot): String = when (s.provider) {
        Provider.LOCAL -> "Download an on-device model in Settings."
        Provider.OPENAI -> "Add your OpenAI API key in Settings."
        Provider.ANTHROPIC -> "Add your Anthropic API key in Settings."
        Provider.CUSTOM -> "Enter your server URL and model in Settings."
    }
}
