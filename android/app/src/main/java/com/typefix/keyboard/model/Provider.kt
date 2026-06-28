package com.typefix.keyboard.model

/**
 * Where a correction is computed. Ported from the macOS TypeFix `Provider`
 * enum, trimmed to what makes sense on Android:
 *
 *  - [LOCAL]  runs an on-device model in-process (privacy-first default).
 *  - [OPENAI] / [ANTHROPIC] are cloud APIs (need a key).
 *  - [CUSTOM] is any OpenAI-compatible server (Ollama, LM Studio, your own).
 */
enum class Provider(
    val id: String,
    val displayName: String,
    val shortDescription: String,
) {
    LOCAL("local", "On-device model", "Runs in-app, fully offline"),
    OPENAI("openai", "OpenAI", "GPT models · cloud"),
    ANTHROPIC("anthropic", "Anthropic", "Claude models · cloud"),
    CUSTOM("custom", "Custom endpoint", "Any OpenAI-compatible server");

    val isLocal: Boolean get() = this == LOCAL

    val requiresApiKey: Boolean get() = this == OPENAI || this == ANTHROPIC

    val usesBaseUrl: Boolean get() = this == CUSTOM

    val defaultBaseUrl: String?
        get() = when (this) {
            CUSTOM -> "http://10.0.2.2:11434/v1" // host machine's Ollama from the emulator
            LOCAL, OPENAI, ANTHROPIC -> null
        }

    /** Default model id sent to the backend when the user hasn't picked one. */
    val defaultModel: String
        get() = when (this) {
            // An on-device model id; the actual file is resolved by ModelManager.
            LOCAL -> "qwen3-4b-instruct"
            OPENAI -> "gpt-5.4-mini"
            ANTHROPIC -> "claude-sonnet-4-6"
            CUSTOM -> "qwen2.5:1.5b"
        }

    /** Curated picks shown in Settings. Empty means free-text only. */
    val suggestedModels: List<ModelOption>
        get() = when (this) {
            LOCAL -> emptyList() // handled by the on-device model catalog
            OPENAI -> listOf(
                ModelOption("gpt-5.4-mini", "GPT-5.4 mini (fast & cheap)"),
                ModelOption("gpt-5.4", "GPT-5.4 (capable)"),
                ModelOption("gpt-5.4-nano", "GPT-5.4 nano (cheapest)"),
            )
            ANTHROPIC -> listOf(
                ModelOption("claude-sonnet-4-6", "Sonnet 4.6 (balanced)"),
                ModelOption("claude-haiku-4-5", "Haiku 4.5 (fastest)"),
            )
            CUSTOM -> listOf(
                ModelOption("qwen2.5:1.5b", "Qwen2.5 1.5B"),
                ModelOption("qwen2.5:3b", "Qwen2.5 3B"),
                ModelOption("llama3.2:3b", "Llama 3.2 3B"),
            )
        }

    companion object {
        fun fromId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: LOCAL
    }
}

data class ModelOption(val id: String, val label: String)
