import SwiftUI
import AppKit

struct HistoryView: View {
    @ObservedObject var store: HistoryStore

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("History")
                        .font(.largeTitle.bold())
                    Text("What you typed and what it became. Nothing is lost — copy the original back any time.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 16)
                Button(role: .destructive) {
                    store.clear()
                } label: {
                    Label("Clear", systemImage: "trash")
                }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(store.records.isEmpty)
            }
            .padding(.horizontal, 26)
            .padding(.top, 24)
            .padding(.bottom, 14)

            if store.records.isEmpty {
                emptyState
            } else {
                ScrollView {
                    LazyVStack(spacing: 14) {
                        ForEach(store.records) { record in
                            HistoryCard(record: record)
                        }
                    }
                    .padding(.horizontal, 26)
                    .padding(.top, 4)
                    .padding(.bottom, 26)
                }
            }
        }
        .frame(width: 580, height: 620)
        .background(Color(nsColor: .windowBackgroundColor))
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            IconChip(systemName: "clock.arrow.circlepath", tint: Color(red: 0.30, green: 0.47, blue: 0.96), size: 56)
            VStack(spacing: 4) {
                Text("No corrections yet")
                    .font(.title3.bold())
                Text("Your corrected text will show up here.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct HistoryCard: View {
    let record: CorrectionRecord

    var body: some View {
        AppCard {
            HStack(spacing: 6) {
                Image(systemName: "clock")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(record.date, format: .dateTime.month().day().hour().minute())
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let app = record.appName, !app.isEmpty {
                    Text("· \(app)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if record.flaggedResidualTypo {
                    Label("Check spelling", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.orange)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(Color.orange.opacity(0.12)))
                } else if record.isUnchanged {
                    Text("No change")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(Color.primary.opacity(0.07)))
                }
            }

            textBlock(title: "Original", text: record.original, prominent: false)
            textBlock(title: "Corrected", text: record.corrected, prominent: true)
        }
    }

    private func textBlock(title: String, text: String, prominent: Bool) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                SectionLabel(title)
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
                .padding(10)
                .background(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(Color(nsColor: .textBackgroundColor).opacity(prominent ? 1 : 0.55))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .strokeBorder(prominent ? Color.accentColor.opacity(0.25) : Color.primary.opacity(0.06), lineWidth: 1)
                )
        }
    }

    private func copy(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }
}
