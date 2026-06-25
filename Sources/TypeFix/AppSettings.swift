import Foundation

enum CorrectionMode: String, CaseIterable, Identifiable {
    case auto
    case manual

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .auto: return "Auto (fix when I pause typing)"
        case .manual: return "Manual (double-Shift to start & stop)"
        }
    }

    var shortName: String {
        switch self {
        case .auto: return "Auto"
        case .manual: return "Manual"
        }
    }
}

/// How a provider runs, which drives gating and the Settings UI.
enum ProviderKind {
    case cloud        // remote API, needs a key
    case localServer  // OpenAI-compatible server on this machine/network
    case embedded     // model runs in-process (MLX)
    case system       // Apple's built-in on-device model
}

enum Provider: String, CaseIterable, Identifiable {
    case anthropic
    case openai
    case ollama
    case customEndpoint
    case mlx
    case appleFoundation

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .anthropic: return "Anthropic"
        case .openai: return "OpenAI"
        case .ollama: return "Ollama (local server)"
        case .customEndpoint: return "Custom endpoint"
        case .mlx: return "Embedded model (MLX)"
        case .appleFoundation: return "Apple on-device"
        }
    }

    var kind: ProviderKind {
        switch self {
        case .anthropic, .openai: return .cloud
        case .ollama, .customEndpoint: return .localServer
        case .mlx: return .embedded
        case .appleFoundation: return .system
        }
    }

    /// True when corrections happen on the user's own machine (nothing sent online).
    var isLocal: Bool { kind != .cloud }

    /// Cloud providers require a key; the custom endpoint may take an optional one.
    var requiresAPIKey: Bool { kind == .cloud }

    /// OpenAI-compatible servers need a base URL.
    var usesBaseURL: Bool { kind == .localServer }

    var defaultBaseURL: String? {
        switch self {
        case .ollama: return "http://localhost:11434/v1"
        case .customEndpoint: return "http://localhost:1234/v1"
        case .anthropic, .openai, .mlx, .appleFoundation: return nil
        }
    }

    var defaultModel: String {
        switch self {
        case .anthropic: return "claude-sonnet-4-6"
        case .openai: return "gpt-5.4-mini"
        case .ollama: return "qwen2.5:3b"
        case .customEndpoint: return ""
        case .mlx: return "mlx-community/Qwen3-4B-Instruct-2507-4bit"
        case .appleFoundation: return ""
        }
    }

    /// Curated model ids offered in the Settings dropdown. Empty means the UI
    /// shows a free-text field (custom endpoint) or no model picker at all
    /// (Apple's on-device model has a single built-in model).
    var suggestedModels: [ModelOption] {
        switch self {
        case .anthropic:
            return [
                ModelOption(id: "claude-sonnet-4-6", label: "Sonnet 4.6 (balanced, recommended)"),
                ModelOption(id: "claude-haiku-4-5", label: "Haiku 4.5 (fastest & cheapest)"),
                ModelOption(id: "claude-opus-4-8", label: "Opus 4.8 (most capable)"),
            ]
        case .openai:
            return [
                ModelOption(id: "gpt-5.4-mini", label: "GPT-5.4 mini (fast & cheap, recommended)"),
                ModelOption(id: "gpt-5.4", label: "GPT-5.4 (capable & affordable)"),
                ModelOption(id: "gpt-5.5", label: "GPT-5.5 (most capable)"),
                ModelOption(id: "gpt-5.4-nano", label: "GPT-5.4 nano (cheapest)"),
            ]
        case .ollama:
            return [
                ModelOption(id: "qwen2.5:3b", label: "Qwen2.5 3B (balanced, recommended)"),
                ModelOption(id: "llama3.2:3b", label: "Llama 3.2 3B"),
                ModelOption(id: "qwen2.5:1.5b", label: "Qwen2.5 1.5B (fastest)"),
                ModelOption(id: "gemma2:2b", label: "Gemma 2 2B"),
            ]
        case .mlx:
            // Non-thinking instruct models whose architectures mlx-swift-examples
            // 2.29.1 implements (qwen3 / qwen2 / llama / phi3). Reasoning models that
            // emit <think> blocks make poor autocorrectors, and newer architectures
            // like qwen3_5 download but fail to load ("Unsupported model type").
            return [
                ModelOption(id: "mlx-community/Qwen3-4B-Instruct-2507-4bit", label: "Qwen3 4B Instruct (recommended, ~2.3 GB)"),
                ModelOption(id: "mlx-community/Qwen2.5-3B-Instruct-4bit", label: "Qwen2.5 3B Instruct (~1.7 GB)"),
                ModelOption(id: "mlx-community/Qwen2.5-1.5B-Instruct-4bit", label: "Qwen2.5 1.5B Instruct (faster, ~0.9 GB)"),
                ModelOption(id: "mlx-community/Llama-3.2-3B-Instruct-4bit", label: "Llama 3.2 3B Instruct (stable fallback)"),
                ModelOption(id: "mlx-community/Phi-4-mini-instruct-4bit", label: "Phi-4 mini Instruct (~2.3 GB)"),
            ]
        case .customEndpoint, .appleFoundation:
            return []
        }
    }

    /// SF Symbol used for the provider's row in Settings.
    var symbolName: String {
        switch self {
        case .anthropic: return "a.circle.fill"
        case .openai: return "o.circle.fill"
        case .ollama: return "server.rack"
        case .customEndpoint: return "network"
        case .mlx: return "memorychip.fill"
        case .appleFoundation: return "apple.logo"
        }
    }

    /// One-line description shown under the provider name in Settings.
    var shortDescription: String {
        switch self {
        case .anthropic: return "Claude models · cloud"
        case .openai: return "GPT models · cloud"
        case .ollama: return "Local server on your Mac"
        case .customEndpoint: return "Your own OpenAI-compatible server"
        case .mlx: return "Runs in-app, fully offline"
        case .appleFoundation: return "Apple Intelligence · on-device"
        }
    }

    /// Whether this provider can be used on the current Mac (independent of
    /// per-provider setup like keys or downloads).
    var isAvailableOnThisMac: Bool {
        switch self {
        case .anthropic, .openai, .ollama, .customEndpoint:
            return true
        case .mlx:
            #if canImport(MLXLLM)
            return PlatformInfo.isAppleSilicon
            #else
            return false
            #endif
        case .appleFoundation:
            #if canImport(FoundationModels)
            if #available(macOS 26, *) { return true }
            return false
            #else
            return false
            #endif
        }
    }

    /// Whether the provider is set up enough to attempt a correction.
    func readiness(apiKey: String, baseURL: String, model: String) -> BackendReadiness {
        switch self {
        case .anthropic, .openai:
            return apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? .needsSetup("Add your \(displayName) API key in Settings.")
                : .ready
        case .ollama:
            // Reachability is verified when a correction is attempted.
            return model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? .needsSetup("Choose an Ollama model in Settings.")
                : .ready
        case .customEndpoint:
            if baseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return .needsSetup("Enter your server's base URL in Settings.")
            }
            return model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? .needsSetup("Enter the model name in Settings.")
                : .ready
        case .mlx:
            guard isAvailableOnThisMac else {
                return .needsSetup("The embedded model needs an Apple Silicon Mac.")
            }
            return MLXModelManager.shared.isModelDownloaded(model)
                ? .ready
                : .needsSetup("Download the embedded model in Settings.")
        case .appleFoundation:
            return FoundationModelsBackend.readiness()
        }
    }
}

/// A model choice shown in the Settings dropdown.
struct ModelOption: Identifiable, Hashable {
    let id: String      // the model id sent to the API
    let label: String   // friendly description
}

/// Model ids that providers have since retired. If a user has one of these
/// stored, we silently migrate them to the current default.
private let retiredModels: Set<String> = [
    "claude-3-5-haiku-latest",
    "claude-3-5-haiku-20241022",
    "claude-3-haiku-20240307",
    "claude-3-5-sonnet-latest",
    "claude-3-7-sonnet-latest",
    "gpt-4o-mini",
    "gpt-4o",
]

/// User-facing settings. Non-secret values live in `UserDefaults`;
/// API keys live in the Keychain, keyed per provider.
final class AppSettings: ObservableObject {
    private let defaults = UserDefaults.standard

    private enum Keys {
        static let provider = "provider"
        static let model = "model"
        static let baseURL = "baseURL"
        static let correctionMode = "correctionMode"
        static let autoDelay = "autoDelay"
        static let autoMinChars = "autoMinChars"
        static let hotkey = "hotkey"
        static let didUpgradeToSonnetDefault = "didUpgradeToSonnetDefault"
    }

    static let autoDelayRange: ClosedRange<Double> = 0.6...4.0
    static let autoMinCharsRange: ClosedRange<Int> = 1...100

    @Published var provider: Provider {
        didSet {
            defaults.set(provider.rawValue, forKey: Keys.provider)
            // When switching providers, jump to the new provider's default model
            // unless the user has typed a genuinely custom model name.
            let isOtherProvidersDefault = Provider.allCases.contains {
                $0 != provider && !$0.defaultModel.isEmpty && $0.defaultModel == model
            }
            if model.isEmpty || isOtherProvidersDefault {
                model = provider.defaultModel
            }
            // Likewise, reset the base URL to the new provider's default unless the
            // user has entered a genuinely custom one.
            if provider.usesBaseURL {
                let trimmedBase = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
                let isOtherProvidersDefaultURL = Provider.allCases.contains {
                    $0 != provider && $0.usesBaseURL && $0.defaultBaseURL == trimmedBase
                }
                if trimmedBase.isEmpty || isOtherProvidersDefaultURL {
                    baseURL = provider.defaultBaseURL ?? ""
                }
            }
        }
    }

    @Published var model: String {
        didSet { defaults.set(model, forKey: Keys.model) }
    }

    /// Base URL for OpenAI-compatible local servers (Ollama / custom endpoints).
    @Published var baseURL: String {
        didSet { defaults.set(baseURL, forKey: Keys.baseURL) }
    }

    @Published var correctionMode: CorrectionMode {
        didSet { defaults.set(correctionMode.rawValue, forKey: Keys.correctionMode) }
    }

    /// Seconds of typing inactivity before an auto-correction fires.
    @Published var autoDelay: Double {
        didSet { defaults.set(autoDelay, forKey: Keys.autoDelay) }
    }

    /// In Auto mode, the minimum number of characters before a fix is attempted.
    @Published var autoMinChars: Int {
        didSet { defaults.set(autoMinChars, forKey: Keys.autoMinChars) }
    }

    /// The trigger that starts / submits a correction.
    @Published var hotkey: Hotkey {
        didSet {
            if let data = try? JSONEncoder().encode(hotkey) {
                defaults.set(data, forKey: Keys.hotkey)
            }
        }
    }

    init() {
        let storedProvider = defaults.string(forKey: Keys.provider).flatMap(Provider.init(rawValue:)) ?? .anthropic
        self.provider = storedProvider
        self.model = defaults.string(forKey: Keys.model) ?? storedProvider.defaultModel
        self.baseURL = defaults.string(forKey: Keys.baseURL) ?? (storedProvider.defaultBaseURL ?? "")
        // Default to Manual (explicit double-Shift). Auto is opt-in.
        self.correctionMode = defaults.string(forKey: Keys.correctionMode)
            .flatMap(CorrectionMode.init(rawValue:)) ?? .manual
        let storedDelay = defaults.object(forKey: Keys.autoDelay) as? Double ?? 2.0
        self.autoDelay = min(max(storedDelay, AppSettings.autoDelayRange.lowerBound), AppSettings.autoDelayRange.upperBound)
        let storedMinChars = defaults.object(forKey: Keys.autoMinChars) as? Int ?? 10
        self.autoMinChars = min(max(storedMinChars, AppSettings.autoMinCharsRange.lowerBound), AppSettings.autoMinCharsRange.upperBound)
        if let data = defaults.data(forKey: Keys.hotkey),
           let storedHotkey = try? JSONDecoder().decode(Hotkey.self, from: data) {
            self.hotkey = storedHotkey
        } else {
            self.hotkey = .bothShifts
        }

        // Property observers don't fire during init, so persist migrations
        // explicitly.
        if retiredModels.contains(model) {
            model = storedProvider.defaultModel
            defaults.set(model, forKey: Keys.model)
        }

        // One-time: users on the previous default (Haiku) move to the new
        // recommended default (Sonnet). Deliberate Haiku choices made afterward
        // are preserved, since this only runs once.
        if !defaults.bool(forKey: Keys.didUpgradeToSonnetDefault) {
            if model == "claude-haiku-4-5" {
                model = Provider.anthropic.defaultModel
                defaults.set(model, forKey: Keys.model)
            }
            defaults.set(true, forKey: Keys.didUpgradeToSonnetDefault)
        }
    }

    // Cache keys in memory so we only hit the Keychain once per provider per
    // launch — otherwise every correction triggers a Keychain access (and
    // potentially its permission prompt).
    private var keyCache: [String: String] = [:]
    private var keyCacheLoaded: Set<String> = []

    var apiKey: String? {
        let account = provider.rawValue
        if keyCacheLoaded.contains(account) {
            return keyCache[account]
        }
        let value = Keychain.read(account: account)
        keyCacheLoaded.insert(account)
        keyCache[account] = value
        return value
    }

    func setAPIKey(_ key: String) {
        let account = provider.rawValue
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            Keychain.delete(account: account)
            keyCache[account] = nil
        } else {
            Keychain.save(trimmed, account: account)
            keyCache[account] = trimmed
        }
        keyCacheLoaded.insert(account)
    }

    /// The base URL to use for the current provider, falling back to its default.
    var effectiveBaseURL: String {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        return provider.defaultBaseURL ?? ""
    }

    /// Whether the selected provider is configured enough to attempt a correction.
    var backendReadiness: BackendReadiness {
        provider.readiness(apiKey: apiKey ?? "", baseURL: effectiveBaseURL, model: model)
    }

    /// A thread-safe snapshot of the settings needed to perform one correction.
    func makeCorrectionConfig() -> CorrectionConfig {
        CorrectionConfig(
            provider: provider,
            model: model.trimmingCharacters(in: .whitespacesAndNewlines),
            apiKey: apiKey ?? "",
            baseURL: effectiveBaseURL
        )
    }
}
