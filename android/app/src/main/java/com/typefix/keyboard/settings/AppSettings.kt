package com.typefix.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import com.typefix.keyboard.model.CorrectionMode
import com.typefix.keyboard.model.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All user-facing settings, ported from the macOS `AppSettings`.
 *
 * Backed by [SharedPreferences] so the keyboard process can read values
 * synchronously while typing, and exposed as a [StateFlow] so the Compose
 * settings screen recomposes on change.
 */
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("typefix", Context.MODE_PRIVATE)

    private object Keys {
        const val PROVIDER = "provider"
        const val MODEL = "model"
        const val BASE_URL = "baseURL"
        const val CORRECTION_MODE = "correctionMode"
        const val AUTO_DELAY_MS = "autoDelayMs"
        const val AUTO_MIN_CHARS = "autoMinChars"
        const val SPELL_CHECK = "spellCheckAfterCorrection"
        const val AUTO_FIX_RESIDUAL = "autoFixResidualTypos"
        const val PROTECTED_WORDS = "protectedWords"
        const val LOCAL_MODEL_ID = "localModelId"
        const val VIBRATION = "vibrationEnabled"
        const val KLIPY_KEY = "klipyApiKey"
        const val PHRASE_MEMORY = "phraseMemory"
        const val VOICE_CLEANUP = "voiceCleanup"
        const val GIF_INTENT = "gifIntent"
        fun apiKey(provider: Provider) = "apiKey_${provider.id}"
    }

    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    /** Always-current snapshot, safe to read from the IME thread. */
    fun snapshot(): SettingsSnapshot = _state.value

    var provider: Provider
        get() = Provider.fromId(prefs.getString(Keys.PROVIDER, null))
        set(value) {
            prefs.edit().putString(Keys.PROVIDER, value.id).apply()
            // Mirror macOS: when switching, adopt the new provider's default model
            // unless the user typed a genuinely custom one.
            val current = model
            val isAnotherDefault = Provider.entries.any {
                it != value && it.defaultModel.isNotEmpty() && it.defaultModel == current
            }
            if (current.isEmpty() || isAnotherDefault) model = value.defaultModel
            if (value.usesBaseUrl && baseUrl.isBlank()) baseUrl = value.defaultBaseUrl.orEmpty()
            publish()
        }

    var model: String
        get() = prefs.getString(Keys.MODEL, provider.defaultModel).orEmpty()
        set(value) { prefs.edit().putString(Keys.MODEL, value).apply(); publish() }

    var baseUrl: String
        get() = prefs.getString(Keys.BASE_URL, provider.defaultBaseUrl ?: "").orEmpty()
        set(value) { prefs.edit().putString(Keys.BASE_URL, value).apply(); publish() }

    var correctionMode: CorrectionMode
        get() = CorrectionMode.fromId(prefs.getString(Keys.CORRECTION_MODE, null))
        set(value) { prefs.edit().putString(Keys.CORRECTION_MODE, value.id).apply(); publish() }

    /** Typing-idle delay before an auto fix fires (milliseconds). */
    var autoDelayMs: Long
        get() = prefs.getLong(Keys.AUTO_DELAY_MS, 600L).coerceIn(AUTO_DELAY_RANGE)
        set(value) {
            prefs.edit().putLong(Keys.AUTO_DELAY_MS, value.coerceIn(AUTO_DELAY_RANGE)).apply(); publish()
        }

    var autoMinChars: Int
        get() = prefs.getInt(Keys.AUTO_MIN_CHARS, 10).coerceIn(AUTO_MIN_CHARS_RANGE)
        set(value) {
            prefs.edit().putInt(Keys.AUTO_MIN_CHARS, value.coerceIn(AUTO_MIN_CHARS_RANGE)).apply(); publish()
        }

    var spellCheckAfterCorrection: Boolean
        get() = prefs.getBoolean(Keys.SPELL_CHECK, true)
        set(value) { prefs.edit().putBoolean(Keys.SPELL_CHECK, value).apply(); publish() }

    var autoFixResidualTypos: Boolean
        get() = prefs.getBoolean(Keys.AUTO_FIX_RESIDUAL, false)
        set(value) { prefs.edit().putBoolean(Keys.AUTO_FIX_RESIDUAL, value).apply(); publish() }

    /** Haptic feedback while fixing / holding the sparkle. On by default. */
    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(Keys.VIBRATION, true)
        set(value) { prefs.edit().putBoolean(Keys.VIBRATION, value).apply(); publish() }

    /** Free KLIPY API key, enables GIF search. */
    var klipyApiKey: String
        get() = prefs.getString(Keys.KLIPY_KEY, "").orEmpty()
        set(value) { prefs.edit().putString(Keys.KLIPY_KEY, value.trim()).apply(); publish() }

    /** Learn frequently-typed niche words and stop "correcting" them. Off by default. */
    var phraseMemoryEnabled: Boolean
        get() = prefs.getBoolean(Keys.PHRASE_MEMORY, false)
        set(value) { prefs.edit().putBoolean(Keys.PHRASE_MEMORY, value).apply(); publish() }

    /** Rewrite rambling voice transcripts into a concise message. Off by default. */
    var voiceCleanupEnabled: Boolean
        get() = prefs.getBoolean(Keys.VOICE_CLEANUP, false)
        set(value) { prefs.edit().putBoolean(Keys.VOICE_CLEANUP, value).apply(); publish() }

    /** Seed GIF results from the message's emotional intent. Off by default. */
    var gifIntentEnabled: Boolean
        get() = prefs.getBoolean(Keys.GIF_INTENT, false)
        set(value) { prefs.edit().putBoolean(Keys.GIF_INTENT, value).apply(); publish() }

    var protectedWords: List<String>
        get() = prefs.getString(Keys.PROTECTED_WORDS, "")
            .orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(Keys.PROTECTED_WORDS, value.joinToString("\n")).apply(); publish()
        }

    /** Filename of the on-device model the user downloaded/selected. */
    var localModelId: String
        get() = prefs.getString(Keys.LOCAL_MODEL_ID, "").orEmpty()
        set(value) { prefs.edit().putString(Keys.LOCAL_MODEL_ID, value).apply(); publish() }

    fun apiKey(provider: Provider): String =
        prefs.getString(Keys.apiKey(provider), "").orEmpty()

    fun setApiKey(provider: Provider, key: String) {
        prefs.edit().putString(Keys.apiKey(provider), key.trim()).apply()
        publish()
    }

    fun addProtectedWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        if (protectedWords.any { it.equals(trimmed, ignoreCase = true) }) return
        protectedWords = protectedWords + trimmed
    }

    fun removeProtectedWord(word: String) {
        protectedWords = protectedWords.filterNot { it.equals(word, ignoreCase = true) }
    }

    private fun publish() {
        _state.value = readSnapshot()
    }

    private fun readSnapshot(): SettingsSnapshot {
        val p = provider
        return SettingsSnapshot(
            provider = p,
            model = model,
            baseUrl = baseUrl.ifBlank { p.defaultBaseUrl.orEmpty() },
            apiKey = apiKey(p),
            correctionMode = correctionMode,
            autoDelayMs = autoDelayMs,
            autoMinChars = autoMinChars,
            spellCheckAfterCorrection = spellCheckAfterCorrection,
            autoFixResidualTypos = autoFixResidualTypos,
            protectedWords = protectedWords,
            localModelId = localModelId,
            vibrationEnabled = vibrationEnabled,
            klipyApiKey = klipyApiKey,
            phraseMemoryEnabled = phraseMemoryEnabled,
            voiceCleanupEnabled = voiceCleanupEnabled,
            gifIntentEnabled = gifIntentEnabled,
        )
    }

    companion object {
        val AUTO_DELAY_RANGE = 600L..4000L
        val AUTO_MIN_CHARS_RANGE = 1..100

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}

/** An immutable copy of the settings needed to perform one correction. */
data class SettingsSnapshot(
    val provider: Provider,
    val model: String,
    val baseUrl: String,
    val apiKey: String,
    val correctionMode: CorrectionMode,
    val autoDelayMs: Long,
    val autoMinChars: Int,
    val spellCheckAfterCorrection: Boolean,
    val autoFixResidualTypos: Boolean,
    val protectedWords: List<String>,
    val localModelId: String,
    val vibrationEnabled: Boolean,
    val klipyApiKey: String,
    val phraseMemoryEnabled: Boolean,
    val voiceCleanupEnabled: Boolean,
    val gifIntentEnabled: Boolean,
)
