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

    @State private var pane: Pane? = .behavior
    @State private var apiKey = ""
    @State private var isCustomModel = false
    @State private var launchAtLogin = LoginItem.isEnabled
    @State private var accessibilityGranted = AXIsProcessTrusted()

    private static let customModelTag = "__custom__"

    @State private var testInput = "whjkat m,ios th best thign swe dcan do to incmprve our converospn rates."
    @State private var testOutput = ""
    @State private var isTesting = false
    @State private var newProtectedWord = ""

    private let corrector = TextCorrector()
    private let permissionTimer = Timer.publish(every: 1.5, on: .main, in: .common).autoconnect()

    enum Pane: String, CaseIterable, Identifiable, Hashable {
        case behavior, provider, permissions, general

        var id: String { rawValue }

        var title: String {
            switch self {
            case .behavior: return "Behavior"
            case .provider: return "AI Provider"
            case .permissions: return "Permissions"
            case .general: return "General"
            }
        }

        var subtitle: String {
            switch self {
            case .behavior: return "How and when TypeFix fixes your text."
            case .provider: return "Choose where corrections run: a cloud key or a private, on-device model."
            case .permissions: return "What TypeFix needs to work, and what it keeps private."
            case .general: return "App preferences and a quick refresher."
            }
        }

        var icon: String {
            switch self {
            case .behavior: return "slider.horizontal.3"
            case .provider: return "cpu"
            case .permissions: return "lock.shield"
            case .general: return "gearshape"
            }
        }
    }

    var body: some View {
        NavigationSplitView {
            List {
                ForEach(Pane.allCases) { item in
                    NavRow(pane: item, selected: pane == item) { pane = item }
                        .listRowInsets(EdgeInsets(top: 2, leading: 8, bottom: 2, trailing: 8))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.sidebar)
            .safeAreaInset(edge: .top, spacing: 0) { sidebarBrand }
            .navigationSplitViewColumnWidth(min: 212, ideal: 222, max: 252)
        } detail: {
            detail
        }
        .frame(minWidth: 760, idealWidth: 780, minHeight: 560, idealHeight: 600)
        .onAppear {
            apiKey = settings.apiKey ?? ""
            syncCustomModel()
            mlx.refreshDownloadedModels()
            warmSelectedModelIfNeeded()
        }
        .onChange(of: settings.provider) { _, _ in
            apiKey = settings.apiKey ?? ""
            syncCustomModel()
            warmSelectedModelIfNeeded()
        }
        .onChange(of: settings.model) { _, _ in warmSelectedModelIfNeeded() }
        .onChange(of: apiKey) { _, newValue in settings.setAPIKey(newValue) }
        .onReceive(permissionTimer) { _ in accessibilityGranted = AXIsProcessTrusted() }
    }

    private var sidebarBrand: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(LinearGradient(colors: [Color(red: 0.30, green: 0.47, blue: 0.96), Color(red: 0.20, green: 0.36, blue: 0.86)], startPoint: .top, endPoint: .bottom))
                .frame(width: 30, height: 30)
                .overlay(
                    Image(systemName: "keyboard.badge.ellipsis")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                )
                .shadow(color: .black.opacity(0.18), radius: 2, y: 1)
            Text("TypeFix")
                .font(.title3.bold())
            Spacer()
        }
        .padding(.horizontal, 12)
        .padding(.top, 14)
        .padding(.bottom, 10)
    }

    @ViewBuilder
    private var detail: some View {
        let current = pane ?? .behavior
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                paneHeader(current)
                switch current {
                case .behavior: behaviorContent
                case .provider: providerContent
                case .permissions: permissionsContent
                case .general: generalContent
                }
            }
            .padding(.horizontal, 30)
            .padding(.vertical, 26)
            .frame(maxWidth: 660, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }

    private func paneHeader(_ pane: Pane) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(pane.title)
                .font(.largeTitle.bold())
            Text(pane.subtitle)
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .padding(.bottom, 2)
    }

    // MARK: - Reusable surfaces

    @ViewBuilder
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            content()
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color(nsColor: .controlBackgroundColor))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(.primary.opacity(0.07), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.05), radius: 14, y: 4)
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.caption2.weight(.bold))
            .tracking(0.8)
            .foregroundStyle(.secondary)
    }

    private func caption(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private func field<Content: View>(_ title: String, caption captionText: String? = nil, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.semibold))
            content()
            if let captionText {
                caption(captionText)
            }
        }
    }

    // MARK: - Behavior

    private var behaviorContent: some View {
        VStack(alignment: .leading, spacing: 22) {
            card {
                sectionLabel("Correction Mode")
                modeSelector
                caption(settings.correctionMode == .manual
                    ? "Use the trigger below to start capturing, type, then trigger again to fix."
                    : "TypeFix fixes automatically a moment after you stop typing.")
            }

            card {
                sectionLabel("Auto Mode")
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("Pause before fixing")
                        Spacer()
                        Text(String(format: "%.1fs", settings.autoDelay))
                            .font(.body.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Slider(value: $settings.autoDelay, in: AppSettings.autoDelayRange, step: 0.1)
                }
                Stepper(
                    "Don't auto-fix until at least \(settings.autoMinChars) characters",
                    value: $settings.autoMinChars,
                    in: AppSettings.autoMinCharsRange
                )
                caption("Applied when Autofix is on: TypeFix waits for a pause and leaves shorter fragments alone. The hotkey still fixes any length instantly.")
            }

            card {
                sectionLabel("Trigger Shortcut")
                HStack(spacing: 10) {
                    ShortcutRecorder(hotkey: $settings.hotkey)
                        .frame(height: 30)
                        .frame(maxWidth: 220)
                    Button("Use ⇧⇧") { settings.hotkey = .bothShifts }
                        .buttonStyle(SecondaryButtonStyle())
                        .disabled(settings.hotkey.useBothShifts)
                }
                caption("Click the box and press a shortcut (must include ⌘, ⌥, or ⌃). The default is both Shift keys tapped together.")
            }
        }
    }

    // MARK: - AI Provider

    private var providerContent: some View {
        VStack(alignment: .leading, spacing: 22) {
            card {
                sectionLabel("Cloud")
                VStack(spacing: 4) {
                    ForEach(Provider.allCases.filter { $0.kind == .cloud }) { provider in
                        providerTile(provider)
                    }
                }
                sectionLabel("On this Mac")
                    .padding(.top, 4)
                VStack(spacing: 4) {
                    ForEach(Provider.allCases.filter { $0.kind != .cloud }) { provider in
                        providerTile(provider)
                    }
                }
                HStack(spacing: 6) {
                    Image(systemName: settings.provider.isLocal ? "lock.fill" : "cloud.fill")
                        .font(.caption)
                        .foregroundStyle(settings.provider.isLocal ? .green : .secondary)
                    caption(settings.provider.isLocal
                        ? "Runs on your machine. Your text never leaves this device."
                        : "Your text is sent to \(settings.provider.displayName) over HTTPS.")
                }
                .padding(.top, 2)
            }

            card {
                sectionLabel("Configuration")
                if settings.provider.requiresAPIKey || settings.provider == .customEndpoint {
                    apiKeyField
                }
                if settings.provider.usesBaseURL {
                    baseURLField
                }
                if settings.provider != .appleFoundation {
                    modelField
                }
                if settings.provider == .mlx {
                    mlxBlock
                }
                if settings.provider == .appleFoundation {
                    foundationBlock
                }
            }

            card {
                sectionLabel("Test Correction")
                TextField("Type some gibberish to test", text: $testInput, axis: .vertical)
                    .lineLimit(2...4)
                    .fieldChrome()
                HStack {
                    Button(isTesting ? "Correcting…" : "Run Test") { runTest() }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(isTesting || !settings.backendReadiness.isReady)
                    if isTesting { ProgressView().controlSize(.small) }
                }
                if !testOutput.isEmpty {
                    Text(testOutput)
                        .font(.callout)
                        .textSelection(.enabled)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(Color(nsColor: .textBackgroundColor))
                        )
                }
            }
        }
    }

    private func providerTile(_ provider: Provider) -> some View {
        ProviderTile(
            provider: provider,
            selected: settings.provider == provider,
            available: provider.isAvailableOnThisMac,
            tint: Self.tint(for: provider),
            action: { settings.provider = provider }
        )
    }

    static func tint(for provider: Provider) -> Color {
        switch provider {
        case .anthropic: return Color(red: 0.83, green: 0.42, blue: 0.30)
        case .openai: return Color(red: 0.09, green: 0.61, blue: 0.51)
        case .ollama: return Color(red: 0.40, green: 0.43, blue: 0.49)
        case .customEndpoint: return Color(red: 0.36, green: 0.34, blue: 0.84)
        case .mlx: return Color(red: 0.30, green: 0.47, blue: 0.96)
        case .appleFoundation: return Color(red: 0.46, green: 0.48, blue: 0.53)
        }
    }

    private var apiKeyField: some View {
        let isCustom = settings.provider == .customEndpoint
        return field(
            isCustom ? "API Key (optional)" : "API Key",
            caption: "Stored securely in your macOS Keychain."
        ) {
            SecureField(
                isCustom ? "Token, if your server requires one" : "Paste your \(settings.provider.displayName) API key",
                text: $apiKey
            )
            .fieldChrome()
        }
    }

    private var baseURLField: some View {
        field(
            "Server URL",
            caption: settings.provider == .ollama
                ? "Install Ollama, run `ollama serve`, and pull a model (e.g. `ollama pull qwen2.5:3b`)."
                : "Any OpenAI-compatible server (llama.cpp, LM Studio, etc.)."
        ) {
            TextField(settings.provider.defaultBaseURL ?? "http://localhost:1234/v1", text: $settings.baseURL)
                .fieldChrome()
        }
    }

    @ViewBuilder
    private var modelField: some View {
        if settings.provider == .customEndpoint {
            field("Model") {
                TextField("Model name your server exposes", text: $settings.model)
                    .fieldChrome()
            }
        } else {
            field("Model", caption: isCustomModel ? modelHelpText : nil) {
                modelDropdown
                if isCustomModel {
                    TextField("Model id", text: $settings.model)
                        .fieldChrome()
                        .padding(.top, 4)
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

    @ViewBuilder
    private var mlxBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch mlx.status {
            case .unsupported:
                statusLabel("The embedded model needs an Apple Silicon Mac.", icon: "exclamationmark.triangle.fill", color: .orange)
            case .idle:
                if mlx.isModelDownloaded(settings.model) {
                    statusLabel("Model downloaded. Runs fully offline.", icon: "checkmark.circle.fill", color: .green)
                    Button("Re-download model") { mlx.prepare(modelID: settings.model) }
                        .buttonStyle(SecondaryButtonStyle())
                } else {
                    caption("Downloads once (about 1–3 GB depending on the model), then runs entirely on this Mac with nothing sent online.")
                    Button("Download model") { mlx.prepare(modelID: settings.model) }
                        .buttonStyle(PrimaryButtonStyle())
                }
            case .downloading(let received, let total):
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
                HStack {
                    Button("Cancel", role: .cancel) { mlx.cancelDownload() }
                        .buttonStyle(SecondaryButtonStyle())
                    caption("Resumes later; partial files are kept.")
                }
            case .ready:
                statusLabel("Model downloaded. Runs fully offline.", icon: "checkmark.circle.fill", color: .green)
            case .failed(let message):
                statusLabel("Download failed: \(message)", icon: "xmark.circle.fill", color: .red)
                Button("Retry") { mlx.prepare(modelID: settings.model) }
                    .buttonStyle(SecondaryButtonStyle())
            }

            if mlx.isSupported, mlx.isModelDownloaded(settings.model) {
                modelLoadStateRow
            }

            if !mlx.downloadedModels.isEmpty {
                Divider()
                sectionLabel("Downloaded models")
                caption("Click a model to use it.")
                ForEach(mlx.downloadedModels) { model in
                    downloadedModelRow(model)
                }
            }
        }
        .padding(.top, 2)
    }

    private var foundationBlock: some View {
        let readiness = FoundationModelsBackend.readiness()
        return statusLabel(
            readiness.isReady
                ? "Apple's built-in on-device model is available. Nothing is downloaded or sent online."
                : (readiness.message ?? "Unavailable."),
            icon: readiness.isReady ? "checkmark.circle.fill" : "exclamationmark.triangle.fill",
            color: readiness.isReady ? .green : .orange
        )
    }

    @ViewBuilder
    private var modelLoadStateRow: some View {
        switch mlx.loadState {
        case .loading(let id) where id == settings.model:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Loading model into memory…")
                    .font(.callout)
            }
        case .loaded(let id) where id == settings.model:
            statusLabel("Loaded in memory. Corrections are instant.", icon: "bolt.fill", color: .green)
        case .failed(let message):
            statusLabel("Couldn't load model: \(message)", icon: "xmark.circle.fill", color: .red)
        default:
            HStack(spacing: 10) {
                caption("Loads into memory automatically (a few seconds) so your first correction is instant.")
                Spacer(minLength: 8)
                Button("Load now") { mlx.warm(modelID: settings.model) }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
    }

    private func downloadedModelRow(_ model: MLXModelManager.DownloadedModel) -> some View {
        let isSelected = settings.model == model.id
        return HStack(spacing: 8) {
            Button {
                settings.model = model.id
                syncCustomModel()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "shippingbox")
                        .foregroundStyle(isSelected ? Color.accentColor : .secondary)
                    Text(model.id)
                        .font(.callout)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: 8)
                    Text(Self.formatBytes(model.bytes))
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(isSelected ? Color.accentColor.opacity(0.12) : Color.clear)
                )
                .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .buttonStyle(.plain)
            .help(isSelected ? "Currently selected" : "Use this model")

            Button(role: .destructive) { mlx.deleteModel(model.id) } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .help("Delete this model to free up disk space")
        }
    }

    private func statusLabel(_ text: String, icon: String, color: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 7) {
            Image(systemName: icon).foregroundStyle(color)
            Text(text).font(.callout)
            Spacer(minLength: 0)
        }
    }

    // MARK: - Permissions

    private var permissionsContent: some View {
        card {
            HStack(spacing: 13) {
                ZStack {
                    Circle()
                        .fill((accessibilityGranted ? Color.green : Color.orange).opacity(0.15))
                        .frame(width: 42, height: 42)
                    Image(systemName: accessibilityGranted ? "checkmark.shield.fill" : "exclamationmark.shield.fill")
                        .font(.title2)
                        .foregroundStyle(accessibilityGranted ? .green : .orange)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(accessibilityGranted ? "Accessibility granted" : "Accessibility not granted")
                        .font(.headline)
                    Text("Required to read keystrokes and replace text.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if !accessibilityGranted {
                    Button("Open Settings") { openAccessibilitySettings() }
                        .buttonStyle(PrimaryButtonStyle())
                }
            }
            Divider()
            caption("TypeFix only reads text during an active correction, stores nothing beyond your local history, and (with a local provider) sends nothing online.")
        }
    }

    // MARK: - General

    private var generalContent: some View {
        VStack(alignment: .leading, spacing: 22) {
            card {
                sectionLabel("Startup")
                Toggle("Launch at login", isOn: Binding(
                    get: { launchAtLogin },
                    set: { newValue in
                        launchAtLogin = newValue
                        LoginItem.set(newValue)
                    }
                ))
                .toggleStyle(.switch)
            }

            card {
                sectionLabel("Safety Net")
                Toggle("Spell-check after correcting", isOn: Binding(
                    get: { settings.spellCheckAfterCorrection },
                    set: { settings.spellCheckAfterCorrection = $0 }
                ))
                .toggleStyle(.switch)
                caption("After each fix, an instant on-device check flags the result (in the HUD and History) if a likely typo remains. It never changes your text, just a heads-up.")

                Toggle("Auto-fix leftover typos", isOn: Binding(
                    get: { settings.autoFixResidualTypos },
                    set: { settings.autoFixResidualTypos = $0 }
                ))
                .toggleStyle(.switch)
                .padding(.top, 4)
                caption("Instead of just flagging, replace any leftover misspelling with the system's top suggestion automatically. Fast and on-device, but it can occasionally pick the wrong word.")
            }

            card {
                sectionLabel("Personal Dictionary")
                caption("Words and names TypeFix should never change, split, or flag, like your product names, jargon, or handles.")
                HStack(spacing: 8) {
                    TextField("Add a word or name", text: $newProtectedWord)
                        .fieldChrome()
                        .onSubmit { addProtectedWord() }
                    Button("Add") { addProtectedWord() }
                        .buttonStyle(SecondaryButtonStyle())
                        .disabled(newProtectedWord.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                if settings.protectedWords.isEmpty {
                    caption("No protected words yet.")
                } else {
                    ProtectedWordsFlow(words: settings.protectedWords) { word in
                        if let index = settings.protectedWords.firstIndex(of: word) {
                            settings.removeProtectedWords(IndexSet(integer: index))
                        }
                    }
                }
            }

            card {
                sectionLabel("How It Works")
                VStack(alignment: .leading, spacing: 10) {
                    if settings.correctionMode == .auto {
                        helpRow("1.circle.fill", "Just type normally in any app.")
                        helpRow("2.circle.fill", "Pause briefly, and your text is auto-corrected in place.")
                        helpRow("3.circle.fill", "Or tap both Shift keys to fix immediately.")
                        helpRow("exclamationmark.circle.fill", "Clicking, arrow keys, Enter, or Tab cancel a pending fix.")
                    } else {
                        helpRow("1.circle.fill", "Tap Left Shift + Right Shift together to begin capturing.")
                        helpRow("2.circle.fill", "Type normally; your text appears as usual.")
                        helpRow("3.circle.fill", "Tap both Shift keys again to replace it with the fix.")
                        helpRow("escape", "Press Esc while capturing to cancel.")
                    }
                }
            }
        }
    }

    private func helpRow(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 9) {
            Image(systemName: icon)
                .foregroundStyle(Color.accentColor)
                .frame(width: 18)
            Text(text)
            Spacer(minLength: 0)
        }
        .font(.callout)
    }

    // MARK: - Custom controls

    private var modeSelector: some View {
        HStack(spacing: 4) {
            ForEach(CorrectionMode.allCases) { mode in
                let selected = settings.correctionMode == mode
                Button { settings.correctionMode = mode } label: {
                    Text(mode.shortName)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(selected ? .primary : .secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .background(
                            RoundedRectangle(cornerRadius: 7, style: .continuous)
                                .fill(selected ? Color(nsColor: .controlBackgroundColor) : Color.clear)
                                .shadow(color: selected ? .black.opacity(0.12) : .clear, radius: 2, y: 1)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(Color.primary.opacity(0.06))
        )
    }

    private var currentModelLabel: String {
        if isCustomModel {
            return settings.model.isEmpty ? "Other…" : settings.model
        }
        if let match = settings.provider.suggestedModels.first(where: { $0.id == settings.model }) {
            return match.label
        }
        return settings.model.isEmpty ? "Select a model" : settings.model
    }

    private var modelDropdown: some View {
        Menu {
            ForEach(settings.provider.suggestedModels) { option in
                let downloaded = settings.provider == .mlx && mlx.isModelDownloaded(option.id)
                Button {
                    isCustomModel = false
                    settings.model = option.id
                } label: {
                    let title = option.label + (downloaded ? "  ·  Downloaded ✓" : "")
                    if !isCustomModel && settings.model == option.id {
                        Label(title, systemImage: "checkmark")
                    } else {
                        Text(title)
                    }
                }
            }
            Divider()
            Button("Other (enter a model id)…") { isCustomModel = true }
        } label: {
            HStack(spacing: 8) {
                Text(currentModelLabel)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 8)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(DropdownButtonStyle())
        .menuIndicator(.hidden)
    }

    private func syncCustomModel() {
        let ids = settings.provider.suggestedModels.map(\.id)
        isCustomModel = !ids.contains(settings.model)
    }

    /// Pre-load the selected embedded model into memory (debounced) so the first
    /// correction is instant.
    private func warmSelectedModelIfNeeded() {
        guard settings.provider == .mlx else { return }
        mlx.scheduleWarm(modelID: settings.model)
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

    private func addProtectedWord() {
        settings.addProtectedWord(newProtectedWord)
        newProtectedWord = ""
    }

    private func openAccessibilitySettings() {
        let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!
        NSWorkspace.shared.open(url)
    }
}

/// Removable chips for the personal dictionary, wrapped across lines.
private struct ProtectedWordsFlow: View {
    let words: [String]
    let onRemove: (String) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(words, id: \.self) { word in
                HStack(spacing: 5) {
                    Text(word)
                        .font(.callout)
                        .lineLimit(1)
                    Button { onRemove(word) } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.caption)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .help("Remove")
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
                .background(Capsule().fill(Color.primary.opacity(0.08)))
            }
        }
    }
}

/// A minimal wrapping layout that flows subviews left-to-right, onto new lines.
private struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        let width = maxWidth == .infinity ? widest : maxWidth
        return CGSize(width: width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// A modern sidebar nav item with a rounded accent-tinted selection and hover.
private struct NavRow: View {
    let pane: SettingsView.Pane
    let selected: Bool
    let action: () -> Void

    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 11) {
                Image(systemName: pane.icon)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(selected ? Color.accentColor : .secondary)
                    .frame(width: 22)
                Text(pane.title)
                    .font(.body.weight(selected ? .semibold : .regular))
                    .foregroundStyle(.primary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(selected
                        ? Color.accentColor.opacity(0.16)
                        : (hovering ? Color.primary.opacity(0.06) : Color.clear))
            )
            .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .animation(.easeOut(duration: 0.12), value: hovering)
    }
}

/// A dropdown that reads as a clear, tappable field (used with `.menuStyle(.button)`).
private struct DropdownButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        StyleBody(configuration: configuration)
    }

    private struct StyleBody: View {
        let configuration: ButtonStyle.Configuration
        @State private var hovering = false

        var body: some View {
            configuration.label
                .font(.body)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Color(nsColor: .textBackgroundColor))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(
                            hovering ? Color.accentColor.opacity(0.55) : Color.primary.opacity(0.14),
                            lineWidth: 1
                        )
                )
                .shadow(color: .black.opacity(0.04), radius: 1, y: 1)
                .opacity(configuration.isPressed ? 0.85 : 1)
                .onHover { hovering = $0 }
                .animation(.easeOut(duration: 0.1), value: hovering)
        }
    }
}

/// A premium, selectable provider row with a gradient icon chip and hover state.
private struct ProviderTile: View {
    let provider: Provider
    let selected: Bool
    let available: Bool
    let tint: Color
    let action: () -> Void

    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 13) {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(LinearGradient(colors: [tint, tint.opacity(0.72)], startPoint: .top, endPoint: .bottom))
                    .frame(width: 30, height: 30)
                    .overlay(
                        Image(systemName: provider.symbolName)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white)
                    )
                    .shadow(color: tint.opacity(0.35), radius: 3, y: 1)

                VStack(alignment: .leading, spacing: 1) {
                    Text(provider.displayName)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                    Text(provider.shortDescription)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                if !available {
                    Text("Unavailable")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                } else if selected {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.bold))
                        .foregroundStyle(tint)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(selected ? tint.opacity(0.14) : (hovering ? Color.primary.opacity(0.05) : Color.clear))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .strokeBorder(selected ? tint.opacity(0.45) : Color.clear, lineWidth: 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(.plain)
        .opacity(available ? 1 : 0.55)
        .onHover { hovering = $0 }
    }
}
