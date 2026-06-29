package com.typefix.keyboard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typefix.keyboard.inference.InferenceController
import com.typefix.keyboard.inference.ModelDownloads
import com.typefix.keyboard.inference.ModelManager
import com.typefix.keyboard.model.CorrectionMode
import com.typefix.keyboard.model.Provider
import com.typefix.keyboard.settings.AppSettings
import com.typefix.keyboard.settings.SettingsSnapshot
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val snapshot by settings.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("TypeFix Keyboard") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SetupCard(context)
            ProviderCard(settings, snapshot.provider)
            if (snapshot.provider.isLocal) {
                LocalModelCard(context, settings, snapshot.localModelId)
            } else {
                CloudCard(settings, snapshot.provider, snapshot.model, snapshot.baseUrl, snapshot.apiKey)
            }
            ModeCard(
                settings,
                snapshot.correctionMode,
                snapshot.autoDelayMs,
                snapshot.autoMinChars,
                snapshot.autocorrectOnSpace,
            )
            FeedbackCard(settings, snapshot.vibrationEnabled)
            SmartFeaturesCard(settings, snapshot)
            GifCard(settings, snapshot.klipyApiKey)
            GuardrailCard(settings, snapshot.spellCheckAfterCorrection, snapshot.autoFixResidualTypos)
            ProtectedWordsCard(settings, snapshot.protectedWords)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SetupCard(context: Context) {
    SectionCard("Setup") {
        Text(
            "1. Enable the TypeFix keyboard in system settings.\n" +
                "2. Switch your active keyboard to TypeFix.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enable TypeFix keyboard") }
        OutlinedButton(
            onClick = {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Switch to TypeFix") }
    }
}

@Composable
private fun ProviderCard(settings: AppSettings, current: Provider) {
    SectionCard("Where corrections run") {
        Provider.entries.forEach { provider ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(selected = provider == current, onClick = { settings.provider = provider })
                Column(Modifier.padding(start = 4.dp)) {
                    Text(provider.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        provider.shortDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalModelCard(context: Context, settings: AppSettings, selectedId: String) {
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val download by ModelDownloads.state.collectAsState()
    val installed = remember(refresh, download.id) { ModelManager.installed(context) }
    val inferenceState by InferenceController.state.collectAsState()
    val scope = rememberCoroutineScopeCompat()

    // Auto-select a model once its download finishes (even if it finished while
    // the user was on another screen).
    var lastDownloading by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(download.id) {
        val finished = lastDownloading
        lastDownloading = download.id
        if (download.id == null && finished != null && ModelManager.isInstalled(context, finished)) {
            if (settings.localModelId.isBlank()) settings.localModelId = finished
            refresh++
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                ModelManager.importModel(context, uri, "imported").onSuccess {
                    settings.localModelId = "imported"
                    refresh++
                }
            }
        }
    }

    SectionCard("On-device model") {
        Text(
            "Runs fully offline. Models are large and download into the app " +
                "(not bundled). Status: $inferenceState",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (installed.isNotEmpty()) {
            Text("Installed", style = MaterialTheme.typography.labelLarge)
            installed.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = id == selectedId, onClick = { settings.localModelId = id })
                    Column(Modifier.padding(start = 4.dp).weight(1f)) {
                        Text(ModelManager.labelFor(id), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${ModelManager.fileSizeMb(context, id)} MB on device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { pendingDelete = id }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete model",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            HorizontalDivider()
        }

        pendingDelete?.let { id ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete this model?") },
                text = {
                    Text("${ModelManager.labelFor(id)} (${ModelManager.fileSizeMb(context, id)} MB) will be removed. You can download it again anytime.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        ModelManager.delete(context, id)
                        if (settings.localModelId == id) settings.localModelId = ""
                        InferenceController.unload()
                        pendingDelete = null
                        refresh++
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                },
            )
        }

        Text("Download", style = MaterialTheme.typography.labelLarge)
        Text(
            "Big downloads keep going with the screen off (a notification shows " +
                "progress) and resume on their own if the connection drops.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelManager.catalog.forEach { entry ->
            Column(Modifier.fillMaxWidth()) {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                val partialMb = (ModelManager.partialBytes(context, entry) / 1_000_000L).toInt()
                if (download.id == entry.id) {
                    LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Downloading… ${(download.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { ModelDownloads.cancel(context) }) { Text("Pause") }
                    }
                } else if (ModelManager.isInstalled(context, entry.id)) {
                    Text("Installed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                } else {
                    FilledTonalButton(
                        enabled = !download.active,
                        onClick = { ModelDownloads.start(context, entry) },
                    ) {
                        Text(
                            if (partialMb > 0) "Resume (~$partialMb of ${entry.approxSizeMb} MB)"
                            else "Download (~${entry.approxSizeMb} MB)"
                        )
                    }
                }
            }
        }
        download.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import a .litertlm model file") }
    }
}

@Composable
private fun CloudCard(
    settings: AppSettings,
    provider: Provider,
    model: String,
    baseUrl: String,
    apiKey: String,
) {
    SectionCard("Model & connection") {
        OutlinedTextField(
            value = model,
            onValueChange = { settings.model = it },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (provider.suggestedModels.isNotEmpty()) {
            provider.suggestedModels.forEach { option ->
                OutlinedButton(onClick = { settings.model = option.id }, modifier = Modifier.fillMaxWidth()) {
                    Text(option.label)
                }
            }
        }
        if (provider.usesBaseUrl) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { settings.baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { settings.setApiKey(provider, it) },
            label = { Text(if (provider.requiresApiKey) "API key" else "API key (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModeCard(
    settings: AppSettings,
    mode: CorrectionMode,
    autoDelayMs: Long,
    autoMinChars: Int,
    autocorrectOnSpace: Boolean,
) {
    SectionCard("When to fix") {
        CorrectionMode.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(selected = option == mode, onClick = { settings.correctionMode = option })
                Text(option.displayName, Modifier.padding(start = 4.dp))
            }
        }
        HorizontalDivider()
        SmartToggle(
            title = "Autocorrect on space",
            caption = "Works offline, no AI. Instantly fixes an obvious typo when you hit space. " +
                "Backspace right after undoes it, and that word won't be fixed again.",
            checked = autocorrectOnSpace,
        ) { settings.autocorrectOnSpace = it }
        if (mode == CorrectionMode.AUTO) {
            HorizontalDivider()
            Text("Fix after pausing: $autoDelayMs ms", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Slider(
                value = autoDelayMs.toFloat(),
                onValueChange = { settings.autoDelayMs = it.toLong() },
                valueRange = AppSettings.AUTO_DELAY_RANGE.first.toFloat()..AppSettings.AUTO_DELAY_RANGE.last.toFloat(),
            )
            Text("Minimum length to fix: $autoMinChars chars", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Slider(
                value = autoMinChars.toFloat(),
                onValueChange = { settings.autoMinChars = it.toInt() },
                valueRange = AppSettings.AUTO_MIN_CHARS_RANGE.first.toFloat()..AppSettings.AUTO_MIN_CHARS_RANGE.last.toFloat(),
            )
        }
    }
}

@Composable
private fun SmartFeaturesCard(settings: AppSettings, snapshot: SettingsSnapshot) {
    SectionCard("Smart features (beta)") {
        Text(
            "All off by default. The ones marked \"Requires an AI model\" need an " +
                "on-device model or a cloud key (set above); the rest work offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SmartToggle(
            title = "Phrase memory",
            caption = "Works offline. Learns your niche words (company names, acronyms, products) and stops \"fixing\" them.",
            checked = snapshot.phraseMemoryEnabled,
        ) { settings.phraseMemoryEnabled = it }
        SmartToggle(
            title = "Voice note cleanup",
            caption = "Requires an AI model. Turns rambling dictation into a tight, written message.",
            checked = snapshot.voiceCleanupEnabled,
        ) { settings.voiceCleanupEnabled = it }
        SmartToggle(
            title = "GIF reactions",
            caption = "Works offline. Suggests GIFs that match your message's vibe.",
            checked = snapshot.gifIntentEnabled,
        ) { settings.gifIntentEnabled = it }
        SmartToggle(
            title = "Tone check",
            caption = "Requires an AI model. Flags a defensive, cold, or too-long draft, with a one-tap fix.",
            checked = snapshot.toneCheckEnabled,
        ) { settings.toneCheckEnabled = it }
    }
}

@Composable
private fun SmartToggle(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GifCard(settings: AppSettings, klipyApiKey: String) {
    SectionCard("GIFs") {
        Text(
            "GIF search (the GIF button in the keyboard toolbar) works out of the " +
                "box with a built-in key. Optionally paste your own KLIPY key " +
                "(partner.klipy.com) to use instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = klipyApiKey,
            onValueChange = { settings.klipyApiKey = it },
            label = { Text("KLIPY API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FeedbackCard(settings: AppSettings, vibrationEnabled: Boolean) {
    SectionCard("Feedback") {
        ToggleRow("Vibration (haptics while fixing & holding ✨)", vibrationEnabled) {
            settings.vibrationEnabled = it
        }
    }
}

@Composable
private fun GuardrailCard(
    settings: AppSettings,
    spellCheck: Boolean,
    autoFixResidual: Boolean,
) {
    SectionCard("Spell-check guardrail") {
        ToggleRow("Flag residual typos after a fix", spellCheck) {
            settings.spellCheckAfterCorrection = it
        }
        ToggleRow("Auto-fix residual typos with top suggestion", autoFixResidual) {
            settings.autoFixResidualTypos = it
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProtectedWordsCard(settings: AppSettings, words: List<String>) {
    var newWord by remember { mutableStateOf("") }
    SectionCard("Protected words") {
        Text(
            "Words and names that should never be changed or flagged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newWord,
                onValueChange = { newWord = it },
                label = { Text("Add a word") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                settings.addProtectedWord(newWord)
                newWord = ""
            }) { Icon(Icons.Default.Add, contentDescription = "Add") }
        }
        words.forEach { word ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(word, Modifier.weight(1f))
                IconButton(onClick = { settings.removeProtectedWord(word) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove")
                }
            }
        }
    }
}

/** Tiny helper so we don't import the coroutine-scope composable at every call site. */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
