import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Correction via Apple's built-in on-device model (Apple Intelligence /
/// Foundation Models). Nothing is downloaded and nothing leaves the device.
///
/// Requires building against the macOS 26 SDK and running on a Mac with Apple
/// Intelligence available. On older SDKs the framework can't be imported, so the
/// real implementation compiles out and the provider reports that it needs setup.
struct FoundationModelsBackend: CorrectionBackend {
    func correct(_ text: String, systemPrompt: String) async throws -> String {
        #if canImport(FoundationModels)
        if #available(macOS 26, *) {
            guard case .available = SystemLanguageModel.default.availability else {
                throw CorrectorError(message: Self.readiness().message ?? "Apple's on-device model is unavailable.")
            }
            let session = LanguageModelSession(instructions: systemPrompt)
            let response = try await session.respond(to: text)
            return CorrectionText.clean(response.content, original: text)
        } else {
            throw CorrectorError(message: "Apple's on-device model requires macOS 26 or later.")
        }
        #else
        throw CorrectorError(
            message: "Apple's on-device model isn't available in this build (requires the macOS 26 SDK)."
        )
        #endif
    }

    /// Synchronous availability check used for gating in Settings, the menu, and
    /// onboarding.
    static func readiness() -> BackendReadiness {
        #if canImport(FoundationModels)
        if #available(macOS 26, *) {
            switch SystemLanguageModel.default.availability {
            case .available:
                return .ready
            case .unavailable(let reason):
                return .needsSetup(message(for: reason))
            @unknown default:
                return .needsSetup("Apple's on-device model is unavailable.")
            }
        } else {
            return .needsSetup("Requires macOS 26 or later with Apple Intelligence.")
        }
        #else
        return .needsSetup("Requires building with the macOS 26 SDK and a Mac with Apple Intelligence.")
        #endif
    }

    #if canImport(FoundationModels)
    @available(macOS 26, *)
    private static func message(for reason: SystemLanguageModel.Availability.UnavailableReason) -> String {
        switch reason {
        case .deviceNotEligible:
            return "This Mac doesn't support Apple's on-device model."
        case .appleIntelligenceNotEnabled:
            return "Turn on Apple Intelligence in System Settings to use the on-device model."
        case .modelNotReady:
            return "Apple's on-device model is still downloading. Try again shortly."
        @unknown default:
            return "Apple's on-device model is unavailable."
        }
    }
    #endif
}
