package com.typefix.keyboard.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GIF client backed by KLIPY (https://klipy.com), using KLIPY's native GIF API:
 *
 *   GET /api/v1/{apiKey}/gifs/search?q=…&per_page=…&customer_id=…
 *   GET /api/v1/{apiKey}/gifs/trending?per_page=…&customer_id=…
 *
 * Response shape:
 *   { "data": { "data": [ { "file": { "xs|sm|md|hd": { "gif": { "url" } } } } ] } }
 *
 * (An earlier Tenor-compatible guess at the endpoint returned HTTP 200 but no
 * parseable results, so searches were counted yet nothing rendered.)
 */
object GifClient {

    private const val BASE = "https://api.klipy.com/api/v1"
    // KLIPY personalizes per customer; a stable app-level id is fine (rate limits
    // are tied to the API key, not this).
    private const val CUSTOMER_ID = "typefix-android"

    data class Gif(val previewUrl: String, val gifUrl: String)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun search(query: String, apiKey: String, limit: Int = 24): List<Gif> =
        fetch("$BASE/$apiKey/gifs/search?q=${enc(query)}&per_page=$limit&customer_id=$CUSTOMER_ID")

    suspend fun featured(apiKey: String, limit: Int = 24): List<Gif> =
        fetch("$BASE/$apiKey/gifs/trending?per_page=$limit&customer_id=$CUSTOMER_ID")

    private suspend fun fetch(url: String): List<Gif> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<Gif>()
                val items = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("data")?.optJSONArray("data") ?: return@use emptyList<Gif>()
                buildList {
                    for (i in 0 until items.length()) {
                        val file = items.getJSONObject(i).optJSONObject("file") ?: continue
                        // Small render for the thumbnail; HD for the inserted GIF.
                        val preview = gifUrl(file, "sm") ?: gifUrl(file, "md")
                            ?: gifUrl(file, "xs") ?: gifUrl(file, "hd")
                        val full = gifUrl(file, "hd") ?: gifUrl(file, "md") ?: preview
                        if (!preview.isNullOrEmpty() && !full.isNullOrEmpty()) add(Gif(preview, full))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun gifUrl(file: JSONObject, size: String): String? =
        file.optJSONObject(size)?.optJSONObject("gif")?.optString("url")?.takeIf { it.isNotEmpty() }

    /** Downloads [url] into [target]. Call off the main thread. */
    suspend fun download(url: String, target: java.io.File): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Empty body")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
