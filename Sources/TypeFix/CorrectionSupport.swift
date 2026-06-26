import Foundation

/// An error surfaced to the user when a correction can't be produced.
struct CorrectorError: Error, LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// A snapshot of everything a backend needs to perform one correction.
///
/// Built from `AppSettings` (see `AppSettings.makeCorrectionConfig()`) so the
/// async correction work never touches the live, main-thread settings object.
struct CorrectionConfig {
    let provider: Provider
    let model: String
    /// Empty for local providers that don't authenticate.
    let apiKey: String
    /// Used by OpenAI-compatible local servers (Ollama / custom endpoints).
    let baseURL: String
}

/// Whether a provider is set up enough to attempt a correction, with a
/// human-readable next step when it isn't.
enum BackendReadiness: Equatable {
    case ready
    case needsSetup(String)

    var isReady: Bool {
        if case .ready = self { return true }
        return false
    }

    var message: String? {
        if case .needsSetup(let message) = self { return message }
        return nil
    }
}

/// A single way of turning garbled text into clean text. Implementations may be
/// remote (HTTP) or fully on-device.
protocol CorrectionBackend {
    func correct(_ text: String, systemPrompt: String) async throws -> String
}

enum PlatformInfo {
    /// True on Apple Silicon (required for the embedded MLX backend). Evaluated
    /// once; safe to read from any thread.
    static let isAppleSilicon: Bool = {
        var value: Int32 = 0
        var size = MemoryLayout<Int32>.size
        let result = sysctlbyname("hw.optional.arm64", &value, &size, nil, 0)
        return result == 0 && value == 1
    }()
}

/// Shared prompt and output-normalization used by every backend so cloud and
/// local providers produce consistent results.
enum CorrectionText {
    static let systemPrompt = """
    You fix typing mistakes. Output the same text with every typo fixed — same words, same meaning, same order. Output ONLY the corrected text, nothing else.

    - Fix typos, transpositions, dropped/doubled letters, run-together words, and wrong words from finger slips (use sentence context). Fix homophones: to/too, your/you're, its/it's, there/their/they're, then/than. Expand lazy single letters to words: u→you, ur→your, r→are.
    - Keep abbreviations and shorthand EXACTLY (OOO, EOD, MVP, PDF, API, URL, CI, lol, ngl, pls, btw, idk, imo, tbh) and keep contractions (write isn't, can't, don't — never "is not", "can not", "do not"). Never expand, drop, or add words, and never change the meaning.
    - Decode a garbled word to the nearest real word that fits the sentence — never an unrelated word, and never capitalize an unknown word as if it were a name.
    - Capitalize sentence starts and the word "I". Don't insert punctuation in the middle of a sentence.

    Examples:
    Input: whjkat m,ios th best thign swe dcan do to incmprve our converospn rates.
    Output: What is the best thing we can do to improve our conversion rates?

    Input: the tets are all gren now but the sidebra wont collapse
    Output: the tests are all green now but the sidebar won't collapse

    Input: ill be ooo next weke, pls dont merge the brnach
    Output: I'll be OOO next week, pls don't merge the branch
    """

    private static let quoteCharacters: Set<Character> = [
        "\"", "'", "`", "\u{201C}", "\u{201D}", "\u{2018}", "\u{2019}",
    ]

    /// Reasoning models (e.g. some Qwen3 variants) emit a `<think>…</think>`
    /// block before their answer. Keep only the text after the final closing tag.
    private static func stripThinking(_ text: String) -> String {
        let lower = text.lowercased()
        guard lower.contains("<think>"), let closeRange = lower.range(of: "</think>", options: .backwards) else {
            return text
        }
        let afterIndex = text.index(text.startIndex, offsetBy: text.distance(from: lower.startIndex, to: closeRange.upperBound))
        return String(text[afterIndex...])
    }

    /// Trims whitespace and removes only the wrapping quotes the model ADDED
    /// beyond what the user actually typed (so quotes you meant to type survive),
    /// then restores the exact leading/trailing whitespace from the original so an
    /// in-place replacement doesn't run into adjacent text.
    static func clean(_ text: String, original: String) -> String {
        var result = stripThinking(text).trimmingCharacters(in: .whitespacesAndNewlines)
        let originalTrimmed = original.trimmingCharacters(in: .whitespacesAndNewlines)

        func leadingQuotes(_ string: String) -> Int {
            string.prefix(while: { quoteCharacters.contains($0) }).count
        }
        func trailingQuotes(_ string: String) -> Int {
            string.reversed().prefix(while: { quoteCharacters.contains($0) }).count
        }

        var extraLeading = leadingQuotes(result) - leadingQuotes(originalTrimmed)
        while extraLeading > 0, let first = result.first, quoteCharacters.contains(first) {
            result.removeFirst()
            extraLeading -= 1
        }
        var extraTrailing = trailingQuotes(result) - trailingQuotes(originalTrimmed)
        while extraTrailing > 0, let last = result.last, quoteCharacters.contains(last) {
            result.removeLast()
            extraTrailing -= 1
        }
        result = result.trimmingCharacters(in: .whitespacesAndNewlines)

        let leadingWhitespace = String(original.prefix(while: { $0.isWhitespace }))
        var trailingWhitespace = ""
        for character in original.reversed() {
            if character.isWhitespace {
                trailingWhitespace.insert(character, at: trailingWhitespace.startIndex)
            } else {
                break
            }
        }
        return leadingWhitespace + result + trailingWhitespace
    }
}

/// Shared HTTP helpers for the network-based backends.
enum CorrectionHTTP {
    static func validate(_ response: URLResponse, data: Data, provider: String) throws {
        guard let http = response as? HTTPURLResponse else { return }
        guard (200...299).contains(http.statusCode) else {
            let detail = parseErrorMessage(from: data) ?? "HTTP \(http.statusCode)"
            throw CorrectorError(message: "\(provider) request failed: \(detail)")
        }
    }

    static func parseErrorMessage(from data: Data) -> String? {
        guard
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let error = json["error"] as? [String: Any],
            let message = error["message"] as? String
        else {
            return nil
        }
        return message
    }

    /// Performs a chat-completions request against any OpenAI-compatible endpoint
    /// and returns the raw assistant message content. When `apiKey` is empty the
    /// `Authorization` header is omitted (local servers like Ollama don't need one).
    static func openAIChat(
        baseURL: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        text: String,
        session: URLSession,
        providerLabel: String
    ) async throws -> String {
        var normalizedBase = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while normalizedBase.hasSuffix("/") {
            normalizedBase.removeLast()
        }
        guard !normalizedBase.isEmpty, let url = URL(string: normalizedBase + "/chat/completions") else {
            throw CorrectorError(message: "Invalid \(providerLabel) base URL: \(baseURL)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: Any] = [
            "model": model,
            "temperature": 0,
            "messages": [
                ["role": "system", "content": systemPrompt],
                ["role": "user", "content": text],
            ],
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw CorrectorError(
                message: "Couldn't reach \(providerLabel) at \(normalizedBase): \(error.localizedDescription)"
            )
        }
        try validate(response, data: data, provider: providerLabel)

        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let choices = json["choices"] as? [[String: Any]],
            let message = choices.first?["message"] as? [String: Any],
            let content = message["content"] as? String
        else {
            throw CorrectorError(message: "Unexpected response from \(providerLabel).")
        }
        return content
    }
}
