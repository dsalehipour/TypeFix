package com.typefix.keyboard.inference

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolves and manages on-device model files (MediaPipe `.task`). Models are
 * NOT bundled in the APK (they're hundreds of MB to a few GB) — they're
 * downloaded or imported into private app storage, the same approach PrivateLM
 * takes. The filesystem is the source of truth.
 */
object ModelManager {

    /** A downloadable on-device model. */
    data class CatalogEntry(
        val id: String,
        val label: String,
        val approxSizeMb: Int,
        val url: String,
    )

    /**
     * Suggested small instruct models exported for MediaPipe. URLs typically
     * point at Hugging Face / Kaggle and may require you to accept a license or
     * supply a token — so importing a `.task` file you already have is also
     * supported (see [importModel]).
     */
    val catalog: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "gemma2-2b-it",
            label = "Gemma 2 2B Instruct (int8, ~1.3 GB)",
            approxSizeMb = 1300,
            url = "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-cpu-int8.task",
        ),
        CatalogEntry(
            id = "qwen2.5-1.5b",
            label = "Qwen2.5 1.5B Instruct (~1.6 GB)",
            approxSizeMb = 1600,
            url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/qwen2.5-1.5b-instruct.task",
        ),
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(context: Context, id: String): File =
        File(modelsDir(context), "$id.task")

    fun isInstalled(context: Context, id: String): Boolean =
        id.isNotBlank() && fileFor(context, id).let { it.exists() && it.length() > 0 }

    fun installed(context: Context): List<String> =
        modelsDir(context).listFiles()
            ?.filter { it.isFile && it.extension == "task" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()

    /** Streams [url] to the model file, reporting progress 0f..1f. */
    suspend fun download(
        context: Context,
        entry: CatalogEntry,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = fileFor(context, entry.id)
            val tmp = File(target.parentFile, "${entry.id}.task.part")
            val request = Request.Builder().url(entry.url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response body")
                val total = body.contentLength().takeIf { it > 0 }
                    ?: (entry.approxSizeMb.toLong() * 1_000_000)
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } >= 0) {
                            output.write(buf, 0, read)
                            done += read
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true); tmp.delete()
            }
            target
        }
    }

    /** Copies a user-picked `.task` file into app storage under [id]. */
    suspend fun importModel(context: Context, uri: Uri, id: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = fileFor(context, id)
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open file" }
                    target.outputStream().use { input.copyTo(it) }
                }
                target
            }
        }

    fun delete(context: Context, id: String): Boolean = fileFor(context, id).delete()
}
