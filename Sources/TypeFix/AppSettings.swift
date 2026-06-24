import Foundation

enum CorrectionMode: String, CaseIterable, Identifiable {
    case auto
    case manual

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .auto: return "Auto — fix when I pause typing"
        case .manual: return "Manual — double-Shift to start & stop"
        }
    }
}

enum Provider: String, CaseIterable, Identifiable {
    case anthropic
    case openai

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .anthropic: return "Anthropic"
        case .openai: return "OpenAI"
        }
    }

    var defaultModel: String {
        switch self {
        case .anthropic: return "claude-haiku-4-5"
        case .openai: return "gpt-4o-mini"
        }
    }
}

/// Model ids that providers have since retired. If a user has one of these
/// stored, we silently migrate them to the current default.
private let retiredModels: Set<String> = [
    "claude-3-5-haiku-latest",
    "claude-3-5-haiku-20241022",
    "claude-3-haiku-20240307",
    "claude-3-5-sonnet-latest",
    "claude-3-7-sonnet-latest",
]

/// User-facing settings. Non-secret values live in `UserDefaults`;
/// API keys live in the Keychain, keyed per provider.
final class AppSettings: ObservableObject {
    private let defaults = UserDefaults.standard

    private enum Keys {
        static let provider = "provider"
        static let model = "model"
        static let armed = "armed"
        static let correctionMode = "correctionMode"
        static let autoDelay = "autoDelay"
        static let autoMinChars = "autoMinChars"
        static let hotkey = "hotkey"
    }

    static let autoDelayRange: ClosedRange<Double> = 0.6...4.0
    static let autoMinCharsRange: ClosedRange<Int> = 1...100

    @Published var provider: Provider {
        didSet {
            defaults.set(provider.rawValue, forKey: Keys.provider)
            // When switching providers, jump to the new provider's default model
            // unless the user has typed a genuinely custom model name.
            let isOtherProvidersDefault = Provider.allCases.contains {
                $0 != provider && $0.defaultModel == model
            }
            if model.isEmpty || isOtherProvidersDefault {
                model = provider.defaultModel
            }
        }
    }

    @Published var model: String {
        didSet { defaults.set(model, forKey: Keys.model) }
    }

    /// Whether the hotkey / auto-correction is active. Persisted across launches.
    @Published var armed: Bool {
        didSet { defaults.set(armed, forKey: Keys.armed) }
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
        self.armed = defaults.object(forKey: Keys.armed) as? Bool ?? true
        // Default to Manual (explicit double-Shift). Auto is opt-in.
        self.correctionMode = defaults.string(forKey: Keys.correctionMode)
            .flatMap(CorrectionMode.init(rawValue:)) ?? .manual
        let storedDelay = defaults.object(forKey: Keys.autoDelay) as? Double ?? 1.5
        self.autoDelay = min(max(storedDelay, AppSettings.autoDelayRange.lowerBound), AppSettings.autoDelayRange.upperBound)
        let storedMinChars = defaults.object(forKey: Keys.autoMinChars) as? Int ?? 10
        self.autoMinChars = min(max(storedMinChars, AppSettings.autoMinCharsRange.lowerBound), AppSettings.autoMinCharsRange.upperBound)
        if let data = defaults.data(forKey: Keys.hotkey),
           let storedHotkey = try? JSONDecoder().decode(Hotkey.self, from: data) {
            self.hotkey = storedHotkey
        } else {
            self.hotkey = .bothShifts
        }

        // Property observers don't fire during init, so persist the migration
        // explicitly.
        if retiredModels.contains(model) {
            model = storedProvider.defaultModel
            defaults.set(model, forKey: Keys.model)
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
}
