import Foundation

/// Correction via Anthropic's Messages API.
struct AnthropicBackend: CorrectionBackend {
    let apiKey: String
    let model: String
    let session: URLSession

    func correct(_ text: String, systemPrompt: String) async throws -> String {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else {
            throw CorrectorError(message: "No Anthropic API key configured.")
        }

        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        request.httpMethod = "POST"
        request.setValue(key, forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        request.setValue("application/json", forHTTPHeaderField: "content-type")

        let body: [String: Any] = [
            "model": model,
            "max_tokens": 2048,
            "system": systemPrompt,
            "messages": [["role": "user", "content": text]],
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await session.data(for: request)
        try CorrectionHTTP.validate(response, data: data, provider: "Anthropic")

        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let content = json["content"] as? [[String: Any]]
        else {
            throw CorrectorError(message: "Unexpected response from Anthropic.")
        }
        let output = content.compactMap { $0["text"] as? String }.joined()
        return CorrectionText.clean(output, original: text)
    }
}
