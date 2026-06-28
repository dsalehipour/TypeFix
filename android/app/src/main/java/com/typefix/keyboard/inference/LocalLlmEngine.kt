package com.typefix.keyboard.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device LLM via Google's **LiteRT-LM**. This runs the official `.litertlm`
 * builds (the same Qwen3 family the macOS app uses) — LiteRT-LM ships the model's
 * own HuggingFace tokenizer and chat template, so we just pass a system prompt +
 * the raw user text and get back clean answer text (no manual ChatML, no special
 * tokens leaking, no runaway generation).
 *
 * GPU is preferred (a 4B model is painfully slow on CPU) with a CPU fallback for
 * devices/emulators without a usable OpenCL driver.
 */
class LocalLlmEngine private constructor(
    private val engine: Engine,
    val backend: String,
) : InferenceEngine {

    override suspend fun generate(systemPrompt: String, text: String): String =
        withContext(Dispatchers.Default) {
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                // Greedy decoding → deterministic corrections (topK = 1). The
                // recommended model (Qwen3-4B-Instruct-2507) is non-reasoning; for
                // hybrid Qwen3 builds any <think> block is stripped by clean().
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
            )
            engine.createConversation(config).use { conversation ->
                val response = conversation.sendMessage(text)
                response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            }
        }

    override fun close() {
        runCatching { engine.close() }
    }

    companion object {
        private const val TAG = "LocalLlmEngine"

        /** Loads [modelPath]. Heavy (seconds + lots of RAM) — call off the UI thread. */
        suspend fun load(
            context: Context,
            modelPath: String,
            preferGpu: Boolean = true,
        ): LocalLlmEngine = withContext(Dispatchers.Default) {
            val cacheDir = context.cacheDir.absolutePath
            fun build(backend: Backend, name: String): LocalLlmEngine {
                val engine = Engine(
                    EngineConfig(modelPath = modelPath, backend = backend, cacheDir = cacheDir)
                )
                engine.initialize()
                return LocalLlmEngine(engine, name)
            }

            if (preferGpu) {
                try {
                    return@withContext build(Backend.GPU(), "gpu")
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU backend failed, falling back to CPU", t)
                }
            }
            build(Backend.CPU(), "cpu")
        }
    }
}
