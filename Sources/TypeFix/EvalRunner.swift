import Foundation

/// Headless evaluation mode (`TypeFix --eval <cases.json>`).
///
/// Runs each garbled case through the *real* correction path (the same
/// `TextCorrector` + system prompt the app ships), against whichever provider/
/// model is currently selected in settings - including the embedded MLX model -
/// and grades the output word-for-word (ignoring case/punctuation).
enum Eval {
    struct Case: Decodable {
        let input: String
        let expected: String
        /// Other answers that are also acceptable (e.g. "what is" for "what's").
        let alts: [String]?
        enum CodingKeys: String, CodingKey {
            case input = "in"
            case expected = "out"
            case alts
        }
    }

    static func run(casesPath: String) async {
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: casesPath)),
              let cases = try? JSONDecoder().decode([Case].self, from: data)
        else {
            stderr("Could not read cases at \(casesPath)\n")
            return
        }

        // A bare CLI binary has no bundle id, so `UserDefaults.standard` is NOT the
        // app's domain. Read the app's real selection from its suite directly so we
        // evaluate the provider/model the user actually has loaded (e.g. MLX).
        let env = ProcessInfo.processInfo.environment
        let defaults = UserDefaults(suiteName: "com.typefix.app") ?? .standard
        let providerRaw = env["EVAL_PROVIDER"] ?? defaults.string(forKey: "provider") ?? "mlx"
        let provider = Provider(rawValue: providerRaw) ?? .mlx
        let model = (env["EVAL_MODEL"] ?? defaults.string(forKey: "model") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let apiKey = Keychain.read(account: provider.rawValue) ?? ""
        let storedBase = (defaults.string(forKey: "baseURL") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let baseURL = storedBase.isEmpty ? (provider.defaultBaseURL ?? "") : storedBase
        let config = CorrectionConfig(provider: provider, model: model, apiKey: apiKey, baseURL: baseURL)
        let corrector = TextCorrector()

        // For hybrid Qwen3 (1.7B/0.6B), thinking is ON by default - append the
        // /no_think soft switch so we measure non-thinking speed, not reasoning.
        let noThink = env["EVAL_NOTHINK"] == "1"
        // Optionally A/B test an alternate prompt from a file (for speed/quality tuning).
        let basePrompt: String
        if let promptFile = env["EVAL_PROMPT_FILE"],
           let custom = try? String(contentsOfFile: promptFile, encoding: .utf8) {
            basePrompt = custom.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            basePrompt = CorrectionText.systemPrompt
        }
        let systemPrompt = noThink ? basePrompt + "\n\n/no_think" : basePrompt

        print("PROVIDER: \(config.provider.displayName)")
        print("MODEL: \(config.model)")
        print("THINKING: \(noThink ? "off (/no_think)" : "default")")
        stderr("Loading model and running \(cases.count) cases…\n")

        var passed = 0
        var mismatches: [(String, String, String)] = []
        var times: [Double] = [] // ms per correction
        let start = Date()

        for (index, testCase) in cases.enumerated() {
            let caseStart = Date()
            let got: String
            do {
                got = try await corrector.correct(testCase.input, config: config, systemPrompt: systemPrompt)
            } catch {
                got = "ERROR: \(error.localizedDescription)"
            }
            times.append(Date().timeIntervalSince(caseStart) * 1000)
            let candidates = [testCase.expected] + (testCase.alts ?? [])
            let normalizedGot = normalize(got)
            let ok = candidates.contains { normalize($0) == normalizedGot }
            if ok {
                passed += 1
            } else {
                mismatches.append((testCase.input, testCase.expected, got))
            }
            stderr("[\(index + 1)/\(cases.count)] \(ok ? "ok" : "XX") \(Int(times[index]))ms\n")
        }

        let seconds = Int(Date().timeIntervalSince(start))
        // First call includes the cold model load; report it separately so the
        // per-correction figure reflects steady-state latency.
        let loadFirst = times.first ?? 0
        let steady = times.dropFirst()
        let avg = steady.isEmpty ? 0 : steady.reduce(0, +) / Double(steady.count)
        let fastest = steady.min() ?? 0
        let slowest = steady.max() ?? 0
        print("\nPASS: \(passed)/\(cases.count)  (\(seconds)s total)")
        print(String(format: "SPEED: load+first %.0f ms | per-correction avg %.0f ms (min %.0f, max %.0f)\n",
                     loadFirst, avg, fastest, slowest))
        print("=== MISMATCHES (review for acceptability) ===\n")
        for (input, expected, got) in mismatches {
            print("IN : \(input)")
            print("EXP: \(expected)")
            print("GOT: \(got)\n")
        }
        fflush(stdout)
    }

    /// Lowercase, drop everything but letters/digits, collapse whitespace - so we
    /// judge whether the words are right, not exact casing/punctuation.
    static func normalize(_ string: String) -> String {
        let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789")
        var output = ""
        var pendingSpace = false
        for character in string.lowercased() {
            if character == "'" || character == "\u{2019}" {
                continue // ignore apostrophes so "let's" == "lets"
            }
            if allowed.contains(character) {
                if pendingSpace, !output.isEmpty { output.append(" ") }
                output.append(character)
                pendingSpace = false
            } else {
                pendingSpace = true
            }
        }
        return output
    }

    private static func stderr(_ text: String) {
        FileHandle.standardError.write(Data(text.utf8))
    }
}
