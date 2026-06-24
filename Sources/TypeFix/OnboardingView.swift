import SwiftUI
import AppKit

/// A one-time welcome screen that shows what TypeFix does and how to use it.
struct OnboardingView: View {
    @ObservedObject var settings: AppSettings
    var hotkeySymbol: String
    var onOpenSettings: () -> Void
    var onOpenAccessibility: () -> Void
    var onDone: () -> Void

    @State private var accessibilityGranted = AXIsProcessTrusted()
    @State private var hasAPIKey = false

    private let refreshTimer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    beforeAfter
                    howItWorks
                    setup
                }
                .padding(24)
            }
            footer
        }
        .frame(width: 540, height: 680)
        .onAppear(perform: refreshStatus)
        .onReceive(refreshTimer) { _ in refreshStatus() }
    }

    private func refreshStatus() {
        accessibilityGranted = AXIsProcessTrusted()
        hasAPIKey = !(settings.apiKey ?? "").isEmpty
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 8) {
            Image(systemName: "keyboard.badge.ellipsis")
                .font(.system(size: 42, weight: .semibold))
                .foregroundStyle(.white)
            Text("Welcome to TypeFix")
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(.white)
            Text("Type fast and sloppy, and let AI fix it right where you're typing.")
                .font(.callout)
                .foregroundStyle(.white.opacity(0.9))
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 24)
        .background(
            LinearGradient(
                colors: [Color.accentColor, Color.accentColor.opacity(0.7)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
    }

    // MARK: - Before / after

    private var beforeAfter: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("See it in action")
            VStack(alignment: .leading, spacing: 10) {
                labeledExample(
                    tag: "YOU TYPE",
                    text: "whjkat m,ios th best thign swe dcan do to incmprve our converospn rates.",
                    color: .secondary,
                    icon: "keyboard"
                )
                Image(systemName: "arrow.down")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Color.accentColor)
                    .frame(maxWidth: .infinity)
                labeledExample(
                    tag: "TYPEFIX WRITES",
                    text: "What is the best thing we can do to improve our conversion rates?",
                    color: .primary,
                    icon: "sparkles"
                )
            }
            .padding(14)
            .background(Color(nsColor: .controlBackgroundColor))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }

    private func labeledExample(tag: String, text: String, color: Color, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(tag, systemImage: icon)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(text)
                .font(.system(size: 14, weight: color == .primary ? .semibold : .regular))
                .foregroundStyle(color)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - How it works

    private var howItWorks: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("How to use it")
            step(
                icon: "hand.tap",
                title: "Manual: tap \(hotkeySymbol)",
                detail: "Tap your shortcut, type, then tap it again. TypeFix rewrites what you typed."
            )
            step(
                icon: "wand.and.stars",
                title: "Autofix: fix on a pause",
                detail: "Turn on Autofix from the menu bar and it fixes automatically a moment after you stop typing."
            )
            step(
                icon: "doc.on.doc",
                title: "Recover anything: ⌘⇧C",
                detail: "Press ⌘⇧C (or use the menu) to copy the original text of the last fix, in case a correction wasn't what you wanted."
            )
        }
    }

    private func step(icon: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.callout).foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Setup

    private var setup: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("Two quick things to set up")
            setupRow(
                number: 1,
                done: accessibilityGranted,
                text: "Grant **Accessibility** so TypeFix can read and rewrite text.",
                buttonTitle: "Open",
                action: onOpenAccessibility
            )
            setupRow(
                number: 2,
                done: hasAPIKey,
                text: "Add your **Anthropic or OpenAI** API key in Settings.",
                buttonTitle: "Settings",
                action: onOpenSettings
            )
        }
        .padding(14)
        .background(Color(nsColor: .controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func setupRow(
        number: Int,
        done: Bool,
        text: LocalizedStringKey,
        buttonTitle: String,
        action: @escaping () -> Void
    ) -> some View {
        HStack(spacing: 10) {
            Image(systemName: done ? "checkmark.circle.fill" : "\(number).circle.fill")
                .font(.system(size: 17))
                .foregroundStyle(done ? Color.green : Color.accentColor)
            Text(text)
                .font(.callout)
                .foregroundStyle(done ? .secondary : .primary)
            Spacer()
            if done {
                Label("Done", systemImage: "checkmark")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.green)
            } else {
                Button(buttonTitle, action: action)
            }
        }
        .animation(.default, value: done)
    }

    // MARK: - Footer

    private var footer: some View {
        HStack {
            Text("You can reopen this from the menu: How TypeFix works…")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Button("Get Started", action: onDone)
                .keyboardShortcut(.defaultAction)
                .controlSize(.large)
        }
        .padding(16)
        .background(.bar)
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.headline)
    }
}
