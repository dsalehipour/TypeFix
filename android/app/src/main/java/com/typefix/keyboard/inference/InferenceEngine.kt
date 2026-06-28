package com.typefix.keyboard.inference

/**
 * One way to turn a prompt + text into corrected text. Implementations may be
 * on-device ([LocalLlmEngine]) or a network call (see the cloud clients). This
 * is the seam that lets you swap MediaPipe for llama.cpp / LiteRT-LM later,
 * exactly like PrivateLM keeps multiple runtimes behind one interface.
 */
interface InferenceEngine {
    /** Returns the model's raw (uncleaned) output for [text]. */
    suspend fun generate(systemPrompt: String, text: String): String

    fun close() {}
}
