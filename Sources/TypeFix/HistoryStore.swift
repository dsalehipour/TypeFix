import Foundation

/// One applied correction: what TypeFix saw and what it became.
struct CorrectionRecord: Identifiable, Codable, Hashable {
    let id: UUID
    let date: Date
    let original: String
    let corrected: String
    let appName: String?
    /// Set when the post-correction spell check found a likely remaining typo.
    var flaggedResidualTypo: Bool

    init(original: String, corrected: String, appName: String?, flaggedResidualTypo: Bool = false) {
        self.id = UUID()
        self.date = Date()
        self.original = original
        self.corrected = corrected
        self.appName = appName
        self.flaggedResidualTypo = flaggedResidualTypo
    }

    // Decode older history (saved before this field existed) gracefully.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        date = try container.decode(Date.self, forKey: .date)
        original = try container.decode(String.self, forKey: .original)
        corrected = try container.decode(String.self, forKey: .corrected)
        appName = try container.decodeIfPresent(String.self, forKey: .appName)
        flaggedResidualTypo = try container.decodeIfPresent(Bool.self, forKey: .flaggedResidualTypo) ?? false
    }

    var isUnchanged: Bool { original == corrected }
}

/// Persisted, capped log of corrections so the user can recover what they typed.
final class HistoryStore: ObservableObject {
    @Published private(set) var records: [CorrectionRecord] = []

    private let defaults = UserDefaults.standard
    private let storageKey = "history"
    private let maxRecords = 300

    init() {
        load()
    }

    func add(_ record: CorrectionRecord) {
        records.insert(record, at: 0)
        if records.count > maxRecords {
            records.removeLast(records.count - maxRecords)
        }
        save()
    }

    func clear() {
        records.removeAll()
        save()
    }

    private func load() {
        guard let data = defaults.data(forKey: storageKey) else { return }
        if let decoded = try? JSONDecoder().decode([CorrectionRecord].self, from: data) {
            records = decoded
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(records) {
            defaults.set(data, forKey: storageKey)
        }
    }
}
