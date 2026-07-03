package com.typefix.keyboard.update

import android.content.Context
import com.typefix.keyboard.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update for the sideloaded (non-Play-Store) build.
 *
 * Since Android forbids silent installs for ordinary apps, the flow is:
 * check GitHub Releases → if a newer version exists, download its APK → hand it
 * to the system installer (the user taps "Update" once). The new APK must be
 * signed with the same key as the installed one (see `keystore.properties`),
 * otherwise Android refuses to install it over the top.
 */
object UpdateChecker {

    data class UpdateInfo(
        val versionName: String,
        val notes: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val htmlUrl: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val current: String) : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val info: UpdateInfo, val progress: Float) : State
        data class ReadyToInstall(val info: UpdateInfo, val apk: File) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Only auto-check this often, so a launch doesn't hammer the GitHub API. */
    private val AUTO_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)

    /**
     * Fires a check at most once per [AUTO_CHECK_INTERVAL_MS], and never while a
     * result/download is already showing. Safe to call on every app launch.
     */
    fun autoCheck(context: Context) {
        val current = _state.value
        if (current != State.Idle && current !is State.UpToDate && current !is State.Error) return
        val prefs = context.applicationContext
            .getSharedPreferences("typefix_update", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        check(context)
    }

    /** Explicit "Check for updates" tap: always runs (subject to not being mid-flight). */
    fun check(context: Context) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        val app = context.applicationContext
        _state.value = State.Checking
        job = scope.launch {
            _state.value = try {
                val info = fetchLatest()
                app.getSharedPreferences("typefix_update", Context.MODE_PRIVATE)
                    .edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                if (info != null && isNewer(info.versionName, BuildConfig.VERSION_NAME)) {
                    State.Available(info)
                } else {
                    State.UpToDate(BuildConfig.VERSION_NAME)
                }
            } catch (e: Exception) {
                State.Error(e.message ?: "Couldn't check for updates")
            }
        }
    }

    /** Downloads the [Available] update's APK, then launches the installer. */
    fun download(context: Context) {
        val available = _state.value as? State.Available ?: return
        val app = context.applicationContext
        val info = available.info
        _state.value = State.Downloading(info, 0f)
        job = scope.launch {
            _state.value = try {
                val apk = downloadApk(app, info) { p ->
                    val s = _state.value
                    if (s is State.Downloading) _state.value = s.copy(progress = p)
                }
                State.ReadyToInstall(info, apk).also {
                    // Kick the system installer immediately; the ReadyToInstall
                    // state keeps an "Install" button around for retries.
                    ApkInstaller.install(app, apk)
                }
            } catch (e: Exception) {
                State.Error(e.message ?: "Download failed")
            }
        }
    }

    /** Re-launches the system installer for an already-downloaded APK. */
    fun install(context: Context) {
        val ready = _state.value as? State.ReadyToInstall ?: return
        ApkInstaller.install(context.applicationContext, ready.apk)
    }

    /** Clears a result/error back to idle (e.g. user dismisses the banner). */
    fun dismiss() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    private fun fetchLatest(): UpdateInfo? {
        val url = "https://api.github.com/repos/" +
            "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases?per_page=30"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TypeFix-Updater")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub API ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val releases = JSONArray(body)
            // Releases come back newest-first; take the newest one that ships an APK.
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.optString("name")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val version = parseVersion(release.optString("tag_name"))
                        ?: parseVersion(name)
                        ?: continue
                    return UpdateInfo(
                        versionName = version,
                        notes = release.optString("body").trim(),
                        downloadUrl = asset.optString("browser_download_url"),
                        sizeBytes = asset.optLong("size", 0L),
                        htmlUrl = release.optString("html_url"),
                    )
                }
            }
        }
        return null
    }

    private fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Fresh file each time so a partial/old download can't be installed.
        val out = File(dir, "typefix-update.apk").apply { if (exists()) delete() }
        val request = Request.Builder()
            .url(info.downloadUrl)
            .header("User-Agent", "TypeFix-Updater")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Download HTTP ${resp.code}")
            val bodyStream = resp.body?.byteStream() ?: error("Empty download")
            val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: info.sizeBytes
            bodyStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        if (!scope.isActive) throw InterruptedException("cancelled")
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        return out
    }

    /** Pulls the first `major.minor.patch`-style number out of a tag/filename. */
    private fun parseVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("""\d+(?:\.\d+)*""").find(raw)?.value
    }

    /** True when [remote] is a strictly higher version than [current]. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
