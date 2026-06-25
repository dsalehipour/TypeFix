import Foundation
import Combine
#if canImport(MLXLLM)
import MLXLLM
import MLXLMCommon
#endif

/// Observable state for the embedded (MLX) model, used by Settings to show
/// download progress and by gating to know whether a model is ready.
///
/// This type is always compiled; the actual MLX work is guarded by
/// `#if canImport(MLXLLM)` so the app still builds (with MLX disabled) when the
/// dependency or Apple Silicon isn't available.
final class MLXModelManager: ObservableObject, @unchecked Sendable {
    static let shared = MLXModelManager()

    enum Status: Equatable {
        case idle
        /// Bytes written to the model's cache directory so far, and the total
        /// expected size when known. Reported in bytes (not Hugging Face's
        /// file-count-weighted fraction, which jumps misleadingly).
        case downloading(receivedBytes: Int64, totalBytes: Int64?)
        case ready(String)
        case failed(String)
        case unsupported
    }

    @Published private(set) var status: Status

    /// Total expected download size, fetched from the Hub once per download.
    private var totalBytes: Int64?
    /// Polls the cache directory while a download is in flight.
    private var pollTimer: Timer?

    private init() {
        #if canImport(MLXLLM)
        status = PlatformInfo.isAppleSilicon ? .idle : .unsupported
        #else
        status = .unsupported
        #endif
    }

    var isSupported: Bool {
        #if canImport(MLXLLM)
        return PlatformInfo.isAppleSilicon
        #else
        return false
        #endif
    }

    /// Whether the given model's weights have been downloaded at least once.
    /// Reads a persisted flag so gating stays synchronous.
    func isModelDownloaded(_ modelID: String) -> Bool {
        let id = modelID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return false }
        return UserDefaults.standard.bool(forKey: Self.downloadedKey(id))
    }

    static func downloadedKey(_ id: String) -> String { "mlxDownloaded:\(id)" }

    /// Kick off a download + load for the given model, publishing byte progress.
    func prepare(modelID: String) {
        let id = modelID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        #if canImport(MLXLLM)
        guard isSupported else {
            setStatus(.unsupported)
            return
        }
        if case .downloading = status { return }
        totalBytes = nil
        setStatus(.downloading(receivedBytes: Self.cacheBytes(for: id), totalBytes: nil))
        startPolling(id: id)
        Task { [weak self] in
            guard let self else { return }
            if let total = await Self.fetchTotalBytes(modelID: id) {
                DispatchQueue.main.async { self.totalBytes = total }
            }
            do {
                _ = try await MLXContainerCache.shared.container(modelID: id) { _ in }
                UserDefaults.standard.set(true, forKey: Self.downloadedKey(id))
                self.stopPolling()
                self.setStatus(.ready(id))
            } catch {
                self.stopPolling()
                self.setStatus(.failed(error.localizedDescription))
            }
        }
        #else
        setStatus(.unsupported)
        #endif
    }

    // MARK: - Byte-based progress

    /// Directory where the Hub stores this model's files (caches/models/<id>).
    static func cacheDirectory(for modelID: String) -> URL? {
        guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else {
            return nil
        }
        return caches.appendingPathComponent("models/\(modelID)", isDirectory: true)
    }

    /// Total size on disk of the model's downloaded files so far.
    static func cacheBytes(for modelID: String) -> Int64 {
        guard let dir = cacheDirectory(for: modelID),
              let enumerator = FileManager.default.enumerator(
                at: dir, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey]
              )
        else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }

    /// Sums the sizes of the files the loader downloads (`*.safetensors`, `*.json`)
    /// from the Hub's tree API so we can show a real percentage.
    private static func fetchTotalBytes(modelID: String) async -> Int64? {
        guard let url = URL(string: "https://huggingface.co/api/models/\(modelID)/tree/main?recursive=true") else {
            return nil
        }
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let entries = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return nil }
        var total: Int64 = 0
        for entry in entries {
            guard (entry["type"] as? String) == "file",
                  let path = entry["path"] as? String,
                  path.hasSuffix(".safetensors") || path.hasSuffix(".json")
            else { continue }
            if let size = entry["size"] as? NSNumber { total += size.int64Value }
        }
        return total > 0 ? total : nil
    }

    private func startPolling(id: String) {
        DispatchQueue.main.async { [weak self] in
            self?.pollTimer?.invalidate()
            self?.pollTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                guard let self else { return }
                guard case .downloading = self.status else { return }
                let received = Self.cacheBytes(for: id)
                self.status = .downloading(receivedBytes: received, totalBytes: self.totalBytes)
            }
        }
    }

    private func stopPolling() {
        DispatchQueue.main.async { [weak self] in
            self?.pollTimer?.invalidate()
            self?.pollTimer = nil
        }
    }

    private func setStatus(_ newStatus: Status) {
        if Thread.isMainThread {
            status = newStatus
        } else {
            DispatchQueue.main.async { self.status = newStatus }
        }
    }
}

#if canImport(MLXLLM)

/// Loads MLX model containers once and keeps the most recent one warm so
/// repeated corrections don't re-load weights.
actor MLXContainerCache {
    static let shared = MLXContainerCache()

    private var cached: (id: String, container: ModelContainer)?
    private var inFlight: [String: Task<ModelContainer, Error>] = [:]

    func container(
        modelID: String,
        progress: @escaping @Sendable (Double) -> Void
    ) async throws -> ModelContainer {
        if let cached, cached.id == modelID {
            return cached.container
        }
        if let existing = inFlight[modelID] {
            return try await existing.value
        }

        let task = Task { () throws -> ModelContainer in
            try await loadModelContainer(id: modelID) { progressInfo in
                progress(progressInfo.fractionCompleted)
            }
        }
        inFlight[modelID] = task
        do {
            let container = try await task.value
            cached = (modelID, container)
            inFlight[modelID] = nil
            return container
        } catch {
            inFlight[modelID] = nil
            throw error
        }
    }
}

/// Correction running entirely on-device via Apple's MLX framework.
struct MLXBackend: CorrectionBackend {
    let modelID: String

    func correct(_ text: String, systemPrompt: String) async throws -> String {
        let id = modelID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            throw CorrectorError(message: "No on-device model selected.")
        }

        let container = try await MLXContainerCache.shared.container(modelID: id) { _ in }

        let parameters = GenerateParameters(maxTokens: 2048, temperature: 0)
        let prompt = systemPrompt
        let userText = text

        let output = try await container.perform { (context: ModelContext) async throws -> String in
            let messages: [Chat.Message] = [.system(prompt), .user(userText)]
            let lmInput = try await context.processor.prepare(input: UserInput(chat: messages))
            let result = try MLXLMCommon.generate(
                input: lmInput, parameters: parameters, context: context
            ) { (_: [Int]) in .more }
            return result.output
        }

        UserDefaults.standard.set(true, forKey: MLXModelManager.downloadedKey(id))
        return CorrectionText.clean(output, original: text)
    }
}

#endif
