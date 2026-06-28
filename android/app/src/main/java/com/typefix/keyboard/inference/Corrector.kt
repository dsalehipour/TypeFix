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
        if (s.autoFixResidualTypos) {
            cleaned = SpellCheckGuard.autoFixed(context, cleaned, protectedAll)
        }
        val possibleTypo = s.spellCheckAfterCorrection &&
            SpellCheckGuard.hasLikelyTypo(context, cleaned, protectedAll)

        return Result.Fixed(cleaned, changed = cleaned != original, possibleTypo = possibleTypo)
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
        return raw
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.code > 0x2000 } }
            .distinct()
            .take(8)
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
        return raw
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.code > 0x2000 } }
            .distinct()
            .take(12)
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
