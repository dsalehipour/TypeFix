package com.typefix.keyboard.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Tenor (v2) GIF client — the same provider gifboard-style keyboards
 * use. Needs a free Tenor API key (set in Settings). Returns a small preview
 * URL for the grid and a full GIF URL for insertion.
 */
object GifClient {

    data class Gif(val previewUrl: String, val gifUrl: String)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun search(query: String, apiKey: String, limit: Int = 24): List<Gif> =
        fetch("https://tenor.googleapis.com/v2/search?q=${enc(query)}&key=$apiKey" +
            "&client_key=typefix&limit=$limit&media_filter=tinygif,gif&contentfilter=high")

    suspend fun featured(apiKey: String, limit: Int = 24): List<Gif> =
        fetch("https://tenor.googleapis.com/v2/featured?key=$apiKey" +
            "&client_key=typefix&limit=$limit&media_filter=tinygif,gif&contentfilter=high")

    private suspend fun fetch(url: String): List<Gif> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<Gif>()
                val json = JSONObject(resp.body?.string().orEmpty())
                val results = json.optJSONArray("results") ?: return@use emptyList<Gif>()
                buildList {
                    for (i in 0 until results.length()) {
                        val formats = results.getJSONObject(i).optJSONObject("media_formats") ?: continue
                        val preview = formats.optJSONObject("tinygif")?.optString("url")
                        val full = formats.optJSONObject("gif")?.optString("url") ?: preview
                        if (!preview.isNullOrEmpty() && !full.isNullOrEmpty()) add(Gif(preview, full))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

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
