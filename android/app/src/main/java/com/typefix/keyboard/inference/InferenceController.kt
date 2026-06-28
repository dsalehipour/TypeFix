package com.typefix.keyboard.inference

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the single loaded on-device model for the whole app process so the
 * keyboard doesn't reload a multi-hundred-MB model every time it opens. The
 * [InferenceService] foreground service keeps this process alive while a model
 * is loaded.
 */
object InferenceController {

    enum class State { IDLE, LOADING, READY, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var loadedBackend: String? = null
        private set

    private var engine: LocalLlmEngine? = null
    private var loadedModelId: String? = null
    private val mutex = Mutex()

    suspend fun ensureLoaded(context: Context, modelId: String): LocalLlmEngine =
        mutex.withLock { ensureLoadedLocked(context, modelId) }

    private suspend fun ensureLoadedLocked(context: Context, modelId: String): LocalLlmEngine {
        engine?.let { if (loadedModelId == modelId) return it }

        engine?.close()
        engine = null
        loadedModelId = null
        loadedBackend = null

        val file = ModelManager.fileFor(context, modelId)
        if (!file.exists() || file.length() == 0L) {
            _state.value = State.ERROR
            lastError = "Model \"$modelId\" is not downloaded."
            error(lastError!!)
        }

        _state.value = State.LOADING
        try {
            val loaded = LocalLlmEngine.load(context.applicationContext, file.absolutePath)
            engine = loaded
            loadedModelId = modelId
            loadedBackend = loaded.backend
            lastError = null
            _state.value = State.READY
            return loaded
        } catch (t: Throwable) {
            lastError = t.message ?: "Failed to load model"
            _state.value = State.ERROR
            throw t
        }
    }

    /**
     * Loads (if needed) and runs one generation. The whole call holds [mutex] so
     * inferences are serialized — MediaPipe sessions are NOT safe to run
     * concurrently, and overlapping calls (e.g. a correction plus emoji
     * suggestions) crash the native engine and take the process down.
     */
    suspend fun generate(
        context: Context,
        modelId: String,
        systemPrompt: String,
        text: String,
    ): String = mutex.withLock {
        ensureLoadedLocked(context, modelId).generate(systemPrompt, text)
    }

    fun unload() {
        engine?.close()
        engine = null
        loadedModelId = null
        loadedBackend = null
        _state.value = State.IDLE
    }
}
