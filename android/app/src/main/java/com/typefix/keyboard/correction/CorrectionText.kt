package com.typefix.keyboard.correction

/**
 * Shared prompt and output-normalization used by every backend so cloud and
 * on-device providers behave the same. Ported directly from the macOS
 * `CorrectionText` (CorrectionSupport.swift) — keep the two in sync.
 */
object CorrectionText {

    val systemPrompt: String = """
        You fix typing mistakes. Output the same text with every typo fixed: same words, same meaning, same order. Output ONLY the corrected text, nothing else.

        - Fix typos, transpositions, dropped/doubled letters, run-together words, and wrong words from finger slips (use sentence context). Fix homophones: to/too, your/you're, its/it's, there/their/they're, then/than. Expand lazy single letters to words: u→you, ur→your, r→are.
        - Keep abbreviations and shorthand EXACTLY (OOO, EOD, MVP, PDF, API, URL, CI, lol, ngl, pls, btw, idk, imo, tbh) and keep contractions (write isn't, can't, don't, never "is not", "can not", "do not"). Never expand, drop, or add words, and never change the meaning.
        - Decode a garbled word to the nearest real word that fits the sentence, never an unrelated word, and never capitalize an unknown word as if it were a name.
        - Capitalize sentence starts and the word "I". Don't insert punctuation in the middle of a sentence.
        - Never add em dashes. If a break is needed, use a comma, colon, or parentheses instead.

        Examples:
        Input: whjkat m,ios th best thign swe dcan do to incmprve our converospn rates.
        Output: What is the best thing we can do to improve our conversion rates?

        Input: the tets are all gren now but the sidebra wont collapse
        Output: the tests are all green now but the sidebar won't collapse

        Input: ill be ooo next weke, pls dont merge the brnach
        Output: I'll be OOO next week, pls don't merge the branch
    """.trimIndent()

    /** System prompt plus the user's protected-words instruction. */
    fun composedPrompt(protectedWords: List<String>): String {
        val cleaned = protectedWords.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return systemPrompt
        return systemPrompt +
            "\n\nAlways keep these words and names EXACTLY as written, never changing, " +
            "splitting, or \"fixing\" them: " + cleaned.joinToString(", ") + "."
    }

    private val quoteChars = setOf('"', '\'', '`', '\u201C', '\u201D', '\u2018', '\u2019')

    /**
     * Reasoning models emit a `<think>…</think>` block before the answer; keep
     * only what follows the final closing tag.
     */
    private fun stripThinking(text: String): String {
        val lower = text.lowercase()
        if (!lower.contains("<think>")) return text
        val close = lower.lastIndexOf("</think>")
        if (close < 0) return text
        return text.substring(close + "</think>".length)
    }

    /** Turn/sequence terminators that on-device chat models emit after the answer. */
    private val endMarkers = listOf(
        "<|im_end|>", "<|endoftext|>", "<|eot_id|>", "<|end|>", "<end_of_turn>", "<eos>",
    )

    /** Role/control tokens (ChatML, Gemma, Llama, …) that must never reach the field. */
    private val specialTokenRegex =
        Regex("<\\|[^|>]*\\|>|</?(?:start_of_turn|end_of_turn|bos|eos|s)>")

    /**
     * On-device models often echo chat-template scaffolding — a `<|im_start|>assistant`
     * prefix, then the answer, then `<|im_end|>` repeated until the token budget runs
     * out. MediaPipe doesn't stop on those tokens, so we strip them ourselves: keep only
     * up to the first turn terminator, drop any leftover role tokens and a bare
     * "assistant" prefix. Without this the markers get committed straight into the text.
     */
    private fun stripSpecialTokens(text: String): String {
        var cut = text.length
        for (marker in endMarkers) {
            val i = text.indexOf(marker)
            if (i in 0 until cut) cut = i
        }
        var t = specialTokenRegex.replace(text.substring(0, cut), "").trimStart()
        // Weak/quantized models sometimes emit the two literal characters "\n"
        // instead of an actual newline; strip those when they lead the answer.
        while (t.startsWith("\\n") || t.startsWith("\\t")) t = t.substring(2)
        t = t.trimStart()
        when {
            t.startsWith("assistant\n") -> t = t.removePrefix("assistant\n")
            t == "assistant" -> t = ""
        }
        // Few-shot models occasionally echo the label before the answer.
        for (label in listOf("Output:", "output:")) {
            if (t.startsWith(label)) {
                t = t.removePrefix(label).trimStart()
                break
            }
        }
        return t
    }

    /**
     * Trims whitespace, removes only the wrapping quotes the model ADDED beyond
     * what the user actually typed, then restores the exact leading/trailing
     * whitespace of [original] so in-place replacement doesn't eat adjacent text.
     */
    fun clean(text: String, original: String): String {
        var result = stripSpecialTokens(stripThinking(text)).trim()
        val originalTrimmed = original.trim()

        fun leadingQuotes(s: String) = s.takeWhile { it in quoteChars }.length
        fun trailingQuotes(s: String) = s.reversed().takeWhile { it in quoteChars }.length

        var extraLeading = leadingQuotes(result) - leadingQuotes(originalTrimmed)
        while (extraLeading > 0 && result.firstOrNull() in quoteChars) {
            result = result.substring(1)
            extraLeading--
        }
        var extraTrailing = trailingQuotes(result) - trailingQuotes(originalTrimmed)
        while (extraTrailing > 0 && result.lastOrNull() in quoteChars) {
            result = result.dropLast(1)
            extraTrailing--
        }
        result = result.trim()

        val leadingWhitespace = original.takeWhile { it.isWhitespace() }
        val trailingWhitespace = original.takeLastWhile { it.isWhitespace() }
        return leadingWhitespace + result + trailingWhitespace
    }

    /**
     * Builds the prompt for on-device chat models using the ChatML template that
     * Qwen2.5 and SmolLM expect. MediaPipe does NOT apply a chat template for us,
     * so a bare "Input:/Output:" completion prompt makes the model answer and then
     * keep inventing more fake Input/Output pairs until it hits the token budget.
     * Wrapping system+user in ChatML and opening the assistant turn keeps it to a
     * single answer terminated by `<|im_end|>`, which [stripSpecialTokens] trims.
     */
    fun singlePrompt(systemPrompt: String, text: String, noThink: Boolean = false): String {
        // Qwen3 is a hybrid reasoning model; "/no_think" keeps it from emitting a
        // slow <think> block we'd only strip anyway (and which can blow the token
        // budget on the small variants).
        val user = if (noThink) "$text /no_think" else text
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n" +
            "<|im_start|>assistant\n"
    }
}
