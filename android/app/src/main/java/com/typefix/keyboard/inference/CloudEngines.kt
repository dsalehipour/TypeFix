package com.typefix.keyboard.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val JSON = "application/json; charset=utf-8".toMediaType()

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

/**
 * Any OpenAI-compatible chat-completions endpoint (OpenAI, Ollama, LM Studio,
 * your own server). Ported from macOS `CorrectionHTTP.openAIChat`. When
 * [apiKey] is blank the Authorization header is omitted (local servers).
 */
class OpenAiCompatibleEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val providerLabel: String,
) : InferenceEngine {

    override suspend fun generate(systemPrompt: String, text: String): String =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/')
            require(base.isNotEmpty()) { "Invalid $providerLabel base URL" }

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", text))
                })
            }

            val builder = Request.Builder()
                .url("$base/chat/completions")
                .post(body.toString().toRequestBody(JSON))
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

            httpClient.newCall(builder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("$providerLabel request failed: ${parseError(raw) ?: "HTTP ${resp.code}"}")
                }
                JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        }
}

/** Anthropic Messages API. Ported from macOS `AnthropicBackend`. */
class AnthropicEngine(
    private val apiKey: String,
    private val model: String,
) : InferenceEngine {

    override suspend fun generate(systemPrompt: String, text: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 2048)
                put("system", systemPrompt)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", text))
                })
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody(JSON))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Anthropic request failed: ${parseError(raw) ?: "HTTP ${resp.code}"}")
                }
                val content = JSONObject(raw).getJSONArray("content")
                buildString {
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "text") append(block.optString("text"))
                    }
                }
            }
        }
}

private fun parseError(raw: String): String? = runCatching {
    JSONObject(raw).getJSONObject("error").getString("message")
}.getOrNull()
