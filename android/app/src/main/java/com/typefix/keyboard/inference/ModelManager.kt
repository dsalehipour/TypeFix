package com.typefix.keyboard.inference

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolves and manages on-device model files (MediaPipe `.task`). Models are
 * NOT bundled in the APK (they're hundreds of MB to a few GB) — they're
 * downloaded or imported into private app storage, the same approach PrivateLM
 * takes. The filesystem is the source of truth.
 */
object ModelManager {

    /** A downloadable on-device model. [ext] is the on-disk file extension
     *  (`litertlm` for the LiteRT-LM runtime). */
    data class CatalogEntry(
        val id: String,
        val label: String,
        val approxSizeMb: Int,
        val url: String,
        val ext: String = "litertlm",
    )

    /**
     * Recognized on-device model file extension. We run LiteRT-LM `.litertlm`
     * builds (which carry the model's own HuggingFace tokenizer + chat template).
     */
    private val MODEL_EXTS = listOf("litertlm")

    /**
     * On-device instruct models, all LiteRT-LM `.litertlm`. Qwen3 matches the
     * macOS app's family; 4B-Instruct-2507 is the same build the Mac runs.
     * Larger = better but slower (and GPU-dependent on phones). URLs point at the
     * ungated Hugging Face LiteRT Community repos.
     */
    val catalog: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "qwen3-4b-instruct",
            label = "Qwen3 4B Instruct 2507 (best · matches Mac · ~2.7 GB)",
            approxSizeMb = 2659,
            url = "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/main/" +
                "qwen3_4b_instruct_2507_mixed_int4.litertlm",
            ext = "litertlm",
        ),
        CatalogEntry(
            id = "qwen3-1.7b",
            label = "Qwen3 1.7B (great balance · ~2.1 GB)",
            approxSizeMb = 2100,
            url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/" +
                "Qwen3_1.7B.litertlm",
            ext = "litertlm",
        ),
        CatalogEntry(
            id = "qwen3-0.6b",
            label = "Qwen3 0.6B (fastest Qwen3 · ~0.5 GB)",
            approxSizeMb = 498,
            url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/" +
                "qwen3_0_6b_mixed_int4.litertlm",
            ext = "litertlm",
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

    /** The on-disk file for [id]: an existing `.task`/`.litertlm` if present,
     *  otherwise the path implied by the catalog (download target). */
    fun fileFor(context: Context, id: String): File {
        val dir = modelsDir(context)
        MODEL_EXTS.forEach { ext -> File(dir, "$id.$ext").let { if (it.exists()) return it } }
        val ext = catalog.firstOrNull { it.id == id }?.ext ?: "litertlm"
        return File(dir, "$id.$ext")
    }

    fun isInstalled(context: Context, id: String): Boolean =
        id.isNotBlank() && fileFor(context, id).let { it.exists() && it.length() > 0 }

    fun fileSizeMb(context: Context, id: String): Int =
        (fileFor(context, id).length() / 1_000_000L).toInt()

    fun labelFor(id: String): String =
        catalog.firstOrNull { it.id == id }?.label ?: id

    fun installed(context: Context): List<String> =
        modelsDir(context).listFiles()
            ?.filter { it.isFile && it.extension in MODEL_EXTS }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()

    /** Bytes already fetched into the partial file for [entry] (for resume UI). */
    fun partialBytes(context: Context, entry: CatalogEntry): Long {
        val part = File(modelsDir(context), "${entry.id}.${entry.ext}.part")
        return if (part.exists()) part.length() else 0L
    }

    /**
     * Streams [entry] to its model file, reporting progress 0f..1f. The download
     * is RESUMABLE: it keeps a `.part` file and uses an HTTP Range request to
     * continue where it left off, so a dropped connection (screen off, app
     * backgrounded, flaky network) doesn't throw the whole multi-GB download
     * away. Cancellation is cooperative and leaves the `.part` in place to resume.
     */
    suspend fun download(
        context: Context,
        entry: CatalogEntry,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = File(modelsDir(context), "${entry.id}.${entry.ext}")
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            var existing = if (tmp.exists()) tmp.length() else 0L
            val builder = Request.Builder().url(entry.url)
            if (existing > 0) builder.header("Range", "bytes=$existing-")

            http.newCall(builder.build()).execute().use { response ->
                val resuming = response.code == 206
                if (!response.isSuccessful) error("HTTP ${response.code}")
                // Server ignored our Range — start the file over cleanly.
                if (!resuming && existing > 0) {
                    tmp.delete()
                    existing = 0
                }
                val body = response.body ?: error("Empty response body")
                val reported = body.contentLength()
                val total = if (reported > 0) existing + reported
                else entry.approxSizeMb.toLong() * 1_000_000
                body.byteStream().use { input ->
                    java.io.FileOutputStream(tmp, /* append = */ existing > 0).use { output ->
                        val buf = ByteArray(1 shl 16)
                        var read: Int
                        var done = existing
                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        while (input.read(buf).also { read = it } >= 0) {
                            ensureActive() // cooperative cancel; .part is kept for resume
                            output.write(buf, 0, read)
                            done += read
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                        output.flush()
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true); tmp.delete()
            }
            Result.success(target)
        } catch (c: CancellationException) {
            throw c // keep .part, let the caller treat it as paused
        } catch (t: Throwable) {
            Result.failure(t)
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
