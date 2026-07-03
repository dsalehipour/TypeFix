import Foundation

/// Routes a correction request to the backend selected in settings. Cloud
/// providers go over HTTPS; local providers run on the user's machine so the
/// captured text never leaves the device.
final class TextCorrector {
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        // Bound how long a correction can hang. Without this, a stuck backend
        // leaves the engine in `.processing` (UI frozen on that fix) until the
        // system default (~60s+). These are generous enough for slow local
        // servers but surface a clear error instead of an indefinite hang.
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }()

    func correct(
        _ text: String,
        config: CorrectionConfig,
        systemPrompt: String = CorrectionText.systemPrompt
    ) async throws -> String {
        let backend = try makeBackend(for: config)
        return try await backend.correct(text, systemPrompt: systemPrompt)
    }

    private func makeBackend(for config: CorrectionConfig) throws -> CorrectionBackend {
        switch config.provider {
        case .anthropic:
            return AnthropicBackend(apiKey: config.apiKey, model: config.model, session: session)
        case .openai:
            return OpenAIBackend(apiKey: config.apiKey, model: config.model, session: session)
        case .ollama, .customEndpoint:
            return OpenAICompatibleBackend(
                baseURL: config.baseURL,
                apiKey: config.apiKey,
                model: config.model,
                providerLabel: config.provider.displayName,
                session: session
            )
        case .mlx:
            #if canImport(MLXLLM)
            return MLXBackend(modelID: config.model)
            #else
            throw CorrectorError(
                message: "The embedded (MLX) model isn't available in this build."
            )
            #endif
        case .appleFoundation:
            return FoundationModelsBackend()
        }
    }
}
