import SwiftUI
import ServiceManagement

enum LoginItem {
    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    static func set(_ enabled: Bool) {
        do {
            if enabled {
                if SMAppService.mainApp.status != .enabled {
                    try SMAppService.mainApp.register()
                }
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            NSLog("TypeFix: failed to update login item: \(error)")
        }
    }
}

struct SettingsView: View {
    @ObservedObject var settings: AppSettings
    @ObservedObject private var mlx = MLXModelManager.shared

    @State private var apiKey = ""
    @State private var isCustomModel = false
    @State private var launchAtLogin = LoginItem.isEnabled
    @State private var accessibilityGranted = AXIsProcessTrusted()

    private static let customModelTag = "__custom__"

    @State private var testInput = "whjkat m,ios th best thign swe dcan do to incmprve our converospn rates."
    @State private var testOutput = ""
    @State private var isTesting = false

    private let corrector = TextCorrector()
    private let permissionTimer = Timer.publish(every: 1.5, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                behaviorSection
                providerSection
                permissionSection
                testSection
                generalSection
                helpSection
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(width: 460, height: 640)
        .onAppear {
            apiKey = settings.apiKey ?? ""
            syncCustomModel()
        }
        .onChange(of: settings.provider) { _, _ in
            apiKey = settings.apiKey ?? ""
            syncCustomModel()
        }
        .onChange(of: apiKey) { _, newValue in settings.setAPIKey(newValue) }
        .onReceive(permissionTimer) { _ in accessibilityGranted = AXIsProcessTrusted() }
    }

    // MARK: - Sections

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("TypeFix")
                .font(.largeTitle.bold())
            Text("Fix sloppy typing with AI, automatically when you pause or on a hotkey.")
                .font(.callout)
                .foregroundStyle(.secondary)
        }
    }

    private var behaviorSection: some View {
        GroupBox("Correction Behavior") {
            VStack(alignment: .leading, spacing: 12) {
                Picker("Mode", selection: $settings.correctionMode) {
                    ForEach(CorrectionMode.allCases) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }
                .pickerStyle(.radioGroup)
                .labelsHidden()

                if settings.correctionMode == .manual {
                    Text("Use the trigger below to start capturing, type, then trigger again to fix.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Divider()

                // Auto-mode options stay visible so they can be set up in advance.
                VStack(alignment: .leading, spacing: 6) {
                    Text("Auto mode options")
                        .font(.subheadline.weight(.medium))
                    HStack {
                        Text("Pause before fixing")
                        Spacer()
                        Text(String(format: "%.1fs", settings.autoDelay))
                            .font(.subheadline.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Slider(value: $settings.autoDelay, in: AppSettings.autoDelayRange, step: 0.1)

                    Stepper(
                        "Don't auto-fix until at least \(settings.autoMinChars) characters",
                        value: $settings.autoMinChars,
                        in: AppSettings.autoMinCharsRange
                    )
                    .padding(.top, 4)
                    Text("These apply when Autofix is on: TypeFix waits for a pause and leaves fragments shorter than this alone. The hotkey still fixes any length instantly.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Divider()

                VStack(alignment: .leading, spacing: 6) {
                    Text("Trigger shortcut")
                        .font(.subheadline.weight(.medium))
                    HStack(spacing: 10) {
                        ShortcutRecorder(hotkey: $settings.hotkey)
                            .frame(height: 30)
                            .frame(maxWidth: 220)
                        Button("Use ⇧⇧ (default)") {
                            settings.hotkey = .bothShifts
                        }
                        .disabled(settings.hotkey.useBothShifts)
                    }
                    Text("Click the box and press a shortcut (must include ⌘, ⌥, or ⌃). The default is both Shift keys tapped together.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(8)
        }
    }

    private var providerSection: some View {
        GroupBox("AI Provider") {
            VStack(alignment: .leading, spacing: 12) {
                Picker("Provider", selection: $settings.provider) {
                    Section("Cloud") {
                        ForEach(Provider.allCases.filter { $0.kind == .cloud }) { provider in
                            Text(provider.displayName).tag(provider)
                        }
                    }
                    Section("On this Mac (private)") {
                        ForEach(Provider.allCases.filter { $0.kind != .cloud }) { provider in
                            Text(providerMenuLabel(provider)).tag(provider)
                        }
                    }
                }
                .pickerStyle(.menu)

                providerHint

                if settings.provider.requiresAPIKey || settings.provider == .customEndpoint {
                    apiKeyField
                }
                if settings.provider.usesBaseURL {
                    baseURLField
                }
                modelField
                if settings.provider == .mlx {
                    mlxSection
                }
                if settings.provider == .appleFoundation {
                    foundationSection
                }
            }
            .padding(8)
        }
    }

    private func providerMenuLabel(_ provider: Provider) -> String {
        provider.isAvailableOnThisMac ? provider.displayName : "\(provider.displayName) (unavailable)"
    }

    private var providerHint: some View {
        Text(settings.provider.isLocal
            ? "Runs on your machine — your text never leaves this device."
            : "Your text is sent to \(settings.provider.displayName) over HTTPS.")
            .font(.caption)
            .foregroundStyle(.secondary)
    }

    private var apiKeyField: some View {
        let isCustom = settings.provider == .customEndpoint
        return VStack(alignment: .leading, spacing: 4) {
            Text(isCustom ? "API Key (optional)" : "API Key")
                .font(.subheadline.weight(.medium))
            SecureField(
                isCustom ? "Token, if your server requires one" : "Paste your \(settings.provider.displayName) API key",
                text: $apiKey
            )
            .textFieldStyle(.roundedBorder)
            Text("Stored securely in your macOS Keychain.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var baseURLField: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Server URL")
                .font(.subheadline.weight(.medium))
            TextField(settings.provider.defaultBaseURL ?? "http://localhost:1234/v1", text: $settings.baseURL)
                .textFieldStyle(.roundedBorder)
            Text(settings.provider == .ollama
                ? "Install Ollama, run `ollama serve`, and pull a model (e.g. `ollama pull qwen2.5:3b`)."
                : "Any OpenAI-compatible server (llama.cpp, LM Studio, etc.).")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var modelField: some View {
        if settings.provider == .appleFoundation {
            EmptyView()
        } else if settings.provider == .customEndpoint {
            VStack(alignment: .leading, spacing: 4) {
                Text("Model")
                    .font(.subheadline.weight(.medium))
                TextField("Model name your server exposes", text: $settings.model)
                    .textFieldStyle(.roundedBorder)
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text("Model")
                    .font(.subheadline.weight(.medium))
                Picker("Model", selection: modelSelection) {
                    ForEach(settings.provider.suggestedModels) { option in
                        Text(option.label).tag(option.id)
                    }
                    Divider()
                    Text("Other (enter a model id)…").tag(Self.customModelTag)
                }
                .labelsHidden()
                if isCustomModel {
                    TextField("Model id", text: $settings.model)
                        .textFieldStyle(.roundedBorder)
                    Text(modelHelpText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var modelHelpText: String {
        switch settings.provider {
        case .ollama: return "Any model you've pulled with `ollama pull`."
        case .mlx: return "Any 4-bit MLX-community model id from Hugging Face."
        default: return "Enter any model id your provider supports."
        }
    }

    private static func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private var mlxSection: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 8) {
                switch mlx.status {
                case .unsupported:
                    Label("The embedded model needs an Apple Silicon Mac.", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                case .idle:
                    if mlx.isModelDownloaded(settings.model) {
                        Label("Model downloaded — runs fully offline.", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Button("Re-download model") { mlx.prepare(modelID: settings.model) }
                    } else {
                        Text("Downloads once (about 1–3 GB depending on the model), then runs entirely on this Mac with nothing sent online.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Button("Download model") { mlx.prepare(modelID: settings.model) }
                    }
                case .downloading(let received, let total):
                    VStack(alignment: .leading, spacing: 6) {
                        if let total, total > 0 {
                            let fraction = min(Double(received) / Double(total), 1.0)
                            Text("Downloading… \(Self.formatBytes(received)) / \(Self.formatBytes(total)) (\(Int(fraction * 100))%)")
                                .font(.subheadline.monospacedDigit())
                            ProgressView(value: fraction)
                        } else {
                            Text("Downloading… \(Self.formatBytes(received))")
                                .font(.subheadline.monospacedDigit())
                            ProgressView()
                        }
                    }
                case .ready:
                    Label("Model downloaded — runs fully offline.", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                case .failed(let message):
                    VStack(alignment: .leading, spacing: 6) {
                        Label("Download failed: \(message)", systemImage: "xmark.circle.fill")
                            .foregroundStyle(.red)
                        Button("Retry") { mlx.prepare(modelID: settings.model) }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(6)
        }
    }

    private var foundationSection: some View {
        let readiness = FoundationModelsBackend.readiness()
        return GroupBox {
            HStack(spacing: 10) {
                Image(systemName: readiness.isReady ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(readiness.isReady ? .green : .orange)
                    .font(.title3)
                Text(readiness.isReady
                    ? "Apple's built-in on-device model is available — nothing is downloaded or sent online."
                    : (readiness.message ?? "Unavailable."))
                    .font(.callout)
                Spacer()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(6)
        }
    }

    private var permissionSection: some View {
        GroupBox("Accessibility Permission") {
            HStack(spacing: 12) {
                Image(systemName: accessibilityGranted ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(accessibilityGranted ? .green : .orange)
                    .font(.title2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(accessibilityGranted ? "Granted" : "Not granted")
                        .font(.subheadline.weight(.medium))
                    Text("Required to read keystrokes and replace text.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if !accessibilityGranted {
                    Button("Open Settings") { openAccessibilitySettings() }
                }
            }
            .padding(8)
        }
    }

    private var testSection: some View {
        GroupBox("Test Correction") {
            VStack(alignment: .leading, spacing: 8) {
                TextField("Type some gibberish to test", text: $testInput, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(2...4)
                HStack {
                    Button(isTesting ? "Correcting…" : "Run Test") { runTest() }
                        .disabled(isTesting || !settings.backendReadiness.isReady)
                    if isTesting { ProgressView().controlSize(.small) }
                }
                if !testOutput.isEmpty {
                    Text(testOutput)
                        .font(.callout)
                        .textSelection(.enabled)
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(nsColor: .textBackgroundColor))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
            }
            .padding(8)
        }
    }

    private var generalSection: some View {
        GroupBox("General") {
            VStack(alignment: .leading, spacing: 10) {
                Toggle("Launch at login", isOn: Binding(
                    get: { launchAtLogin },
                    set: { newValue in
                        launchAtLogin = newValue
                        LoginItem.set(newValue)
                    }
                ))
            }
            .padding(8)
        }
    }

    private var helpSection: some View {
        GroupBox("How it works") {
            VStack(alignment: .leading, spacing: 6) {
                if settings.correctionMode == .auto {
                    Label("Just type normally in any app.", systemImage: "1.circle")
                    Label("Pause briefly, and your text is auto-corrected in place.", systemImage: "2.circle")
                    Label("Or tap both Shift keys to fix immediately.", systemImage: "3.circle")
                    Label("Clicking, arrow keys, Enter, or Tab cancel a pending fix.", systemImage: "exclamationmark.circle")
                } else {
                    Label("Tap Left Shift + Right Shift together to begin capturing.", systemImage: "1.circle")
                    Label("Type normally; your text appears as usual.", systemImage: "2.circle")
                    Label("Tap both Shift keys again to replace it with the fix.", systemImage: "3.circle")
                    Label("Press Esc while capturing to cancel.", systemImage: "escape")
                }
            }
            .font(.callout)
            .padding(8)
        }
    }

    // MARK: - Model selection

    private var modelSelection: Binding<String> {
        Binding(
            get: {
                if isCustomModel { return Self.customModelTag }
                let ids = settings.provider.suggestedModels.map(\.id)
                return ids.contains(settings.model) ? settings.model : Self.customModelTag
            },
            set: { newValue in
                if newValue == Self.customModelTag {
                    isCustomModel = true
                } else {
                    isCustomModel = false
                    settings.model = newValue
                }
            }
        )
    }

    private func syncCustomModel() {
        let ids = settings.provider.suggestedModels.map(\.id)
        isCustomModel = !ids.contains(settings.model)
    }

    // MARK: - Actions

    private func runTest() {
        isTesting = true
        testOutput = ""
        let config = settings.makeCorrectionConfig()
        let input = testInput
        Task {
            do {
                let result = try await corrector.correct(input, config: config)
                await MainActor.run {
                    testOutput = result
                    isTesting = false
                }
            } catch {
                await MainActor.run {
                    testOutput = "Error: \((error as? CorrectorError)?.message ?? error.localizedDescription)"
                    isTesting = false
                }
            }
        }
    }

    private func openAccessibilitySettings() {
        let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!
        NSWorkspace.shared.open(url)
    }
}
