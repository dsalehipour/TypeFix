import SwiftUI
import AppKit

struct HistoryView: View {
    @ObservedObject var store: HistoryStore

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("History").font(.headline)
                    Text("What you typed and what it became. Nothing is lost — copy the original back any time.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button(role: .destructive) {
                    store.clear()
                } label: {
                    Label("Clear", systemImage: "trash")
                }
                .disabled(store.records.isEmpty)
            }
            .padding()

            Divider()

            if store.records.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(store.records) { record in
                            HistoryRow(record: record)
                            Divider()
                        }
                    }
                }
            }
        }
        .frame(width: 540, height: 580)
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 36))
                .foregroundStyle(.secondary)
            Text("No corrections yet")
                .font(.headline)
            Text("Your corrected text will show up here.")
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct HistoryRow: View {
    let record: CorrectionRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(record.date, format: .dateTime.month().day().hour().minute())
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let app = record.appName, !app.isEmpty {
                    Text("· \(app)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if record.isUnchanged {
                    Text("no change")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            textBlock(title: "Original", text: record.original, prominent: false)
            textBlock(title: "Corrected", text: record.corrected, prominent: true)
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 16)
    }

    private func textBlock(title: String, text: String, prominent: Bool) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(title.uppercased())
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Button {
                    copy(text)
                } label: {
                    Image(systemName: "doc.on.doc")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
                .help("Copy \(title.lowercased())")
            }
            Text(text)
                .font(.callout)
                .foregroundStyle(prominent ? .primary : .secondary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(Color(nsColor: .textBackgroundColor).opacity(prominent ? 1 : 0.5))
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
    }

    private func copy(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }
}
