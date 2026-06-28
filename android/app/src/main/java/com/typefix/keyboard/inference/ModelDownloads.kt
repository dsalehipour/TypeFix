package com.typefix.keyboard.inference

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the current model download. The download runs in an
 * app-scoped coroutine (NOT the Settings screen's scope) and is kept alive by
 * [ModelDownloadService] (a foreground service + wake lock), so it keeps going
 * when the screen turns off or the user leaves the app — the previous behavior
 * tore the socket down on screen-off ("Software caused connection abort").
 */
object ModelDownloads {

    data class State(
        val id: String? = null,
        val label: String = "",
        val progress: Float = 0f,
        val error: String? = null,
    ) {
        val active: Boolean get() = id != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Starts (or resumes) the download for [entry]. No-op if one is running. */
    fun start(context: Context, entry: ModelManager.CatalogEntry) {
        if (_state.value.active) return
        val app = context.applicationContext
        _state.value = State(id = entry.id, label = entry.label, progress = 0f, error = null)
        ModelDownloadService.start(app)
        job = scope.launch {
            val result = ModelManager.download(app, entry) { p ->
                _state.value = _state.value.copy(progress = p)
            }
            result
                .onSuccess { _state.value = State() }
                .onFailure { e -> _state.value = State(error = "${entry.label}: ${e.message ?: "download failed"}") }
            ModelDownloadService.stop(app)
        }
    }

    /** Pauses the current download (the partial file is kept so it can resume). */
    fun cancel(context: Context) {
        job?.cancel()
        job = null
        _state.value = State()
        ModelDownloadService.stop(context.applicationContext)
    }

    fun clearError() {
        if (!_state.value.active) _state.value = State()
    }
}
