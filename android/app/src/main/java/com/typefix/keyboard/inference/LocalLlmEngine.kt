package com.typefix.keyboard.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.typefix.keyboard.correction.CorrectionText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device LLM via Google's MediaPipe LLM Inference API. Loads a `.task` model
 * (Gemma / Qwen / Phi exported for MediaPipe) from local storage and runs fully
 * offline. Temperature is pinned to 0 to match TypeFix's deterministic fixes.
 *
 * GPU is preferred but we fall back to CPU on failure — the same defensive
 * pattern PrivateLM uses for flaky mobile GPU drivers.
 */
class LocalLlmEngine private constructor(
    private val llm: LlmInference,
    val backend: String,
) : InferenceEngine {

    override suspend fun generate(systemPrompt: String, text: String): String =
        withContext(Dispatchers.Default) {
            val session = LlmInferenceSession.createFromOptions(
                llm,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTopP(0.9f)
                    .setTemperature(0f)
                    .build()
            )
            try {
                session.addQueryChunk(CorrectionText.singlePrompt(systemPrompt, text))
                session.generateResponse()
            } finally {
                session.close()
            }
        }

    override fun close() {
        runCatching { llm.close() }
    }

    companion object {
        private const val TAG = "LocalLlmEngine"

        /** Loads [modelPath]. Heavy (seconds + lots of RAM) — call off the UI thread. */
        suspend fun load(
            context: Context,
            modelPath: String,
            maxTokens: Int = 512,
            preferGpu: Boolean = true,
        ): LocalLlmEngine = withContext(Dispatchers.Default) {
            fun build(backend: LlmInference.Backend): LlmInference {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(maxTokens)
                    .setPreferredBackend(backend)
                    .build()
                return LlmInference.createFromOptions(context, options)
            }

            if (preferGpu) {
                try {
                    return@withContext LocalLlmEngine(build(LlmInference.Backend.GPU), "gpu")
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU backend failed, falling back to CPU", t)
                }
            }
            LocalLlmEngine(build(LlmInference.Backend.CPU), "cpu")
        }
    }
}
