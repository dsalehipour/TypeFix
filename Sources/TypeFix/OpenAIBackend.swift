import Foundation

/// Correction via OpenAI's Chat Completions API (cloud).
struct OpenAIBackend: CorrectionBackend {
    let apiKey: String
    let model: String
    let session: URLSession

    func correct(_ text: String, systemPrompt: String) async throws -> String {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else {
            throw CorrectorError(message: "No OpenAI API key configured.")
        }
        let content = try await CorrectionHTTP.openAIChat(
            baseURL: "https://api.openai.com/v1",
            apiKey: key,
            model: model,
            systemPrompt: systemPrompt,
            text: text,
            session: session,
            providerLabel: "OpenAI"
        )
        return CorrectionText.clean(content, original: text)
    }
}
