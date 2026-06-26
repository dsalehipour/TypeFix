import Foundation

/// Correction via any OpenAI-compatible server running on the user's machine or
/// network, e.g. Ollama (`http://localhost:11434/v1`), `llama.cpp`'s server, or
/// LM Studio. The text never leaves the configured endpoint.
struct OpenAICompatibleBackend: CorrectionBackend {
    let baseURL: String
    /// Usually empty for local servers; some setups put an auth token here.
    let apiKey: String
    let model: String
    let providerLabel: String
    let session: URLSession

    func correct(_ text: String, systemPrompt: String) async throws -> String {
        let trimmedModel = model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedModel.isEmpty else {
            throw CorrectorError(message: "No model name set for \(providerLabel).")
        }
        let content = try await CorrectionHTTP.openAIChat(
            baseURL: baseURL,
            apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
            model: trimmedModel,
            systemPrompt: systemPrompt,
            text: text,
            session: session,
            providerLabel: providerLabel
        )
        return CorrectionText.clean(content, original: text)
    }
}
