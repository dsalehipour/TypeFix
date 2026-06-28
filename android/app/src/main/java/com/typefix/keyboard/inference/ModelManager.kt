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

    /** A downloadable on-device model. [ext] is the on-disk file extension —
     *  `task` (MediaPipe bundle) or `litertlm` (LiteRT-LM, used by Qwen3). */
    data class CatalogEntry(
        val id: String,
        val label: String,
        val approxSizeMb: Int,
        val url: String,
        val ext: String = "task",
    )

    /** Recognized on-device model file extensions, in lookup order. */
    private val MODEL_EXTS = listOf("litertlm", "task")

    /**
     * Suggested instruct models for on-device use. Qwen3 (LiteRT-LM `.litertlm`)
     * matches the macOS app's Qwen3 family and is the better choice; Qwen2.5
     * (`.task`) stays as a lighter, older option. URLs point at Hugging Face;
     * importing a file you already have is also supported (see [importModel]).
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
        CatalogEntry(
            id = "qwen2.5-1.5b",
            label = "Qwen2.5 1.5B Instruct (older · ~1.6 GB)",
            approxSizeMb = 1600,
            url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        ),
        CatalogEntry(
            id = "qwen2.5-0.5b",
            label = "Qwen2.5 0.5B Instruct (older · smallest · ~0.5 GB)",
            approxSizeMb = 560,
            url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
                "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
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
        val ext = catalog.firstOrNull { it.id == id }?.ext ?: "task"
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

    /** Streams [url] to the model file, reporting progress 0f..1f. */
    suspend fun download(
        context: Context,
        entry: CatalogEntry,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(modelsDir(context), "${entry.id}.${entry.ext}")
            val tmp = File(target.parentFile, "${target.name}.part")
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
