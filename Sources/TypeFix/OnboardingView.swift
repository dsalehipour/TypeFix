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
    @State private var backendReady = false

    private let refreshTimer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    beforeAfter
                    howItWorks
                    setup
                }
                .padding(22)
                .frame(maxWidth: .infinity)
            }
            .background(Color(nsColor: .windowBackgroundColor))
            footer
        }
        .frame(width: 560, height: 720)
        .onAppear(perform: refreshStatus)
        .onReceive(refreshTimer) { _ in refreshStatus() }
    }

    private func refreshStatus() {
        accessibilityGranted = AXIsProcessTrusted()
        backendReady = settings.backendReadiness.isReady
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(.white.opacity(0.18))
                .frame(width: 64, height: 64)
                .overlay(
                    Image(systemName: "keyboard.badge.ellipsis")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(.white)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(.white.opacity(0.25), lineWidth: 1)
                )
            Text("Welcome to TypeFix")
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(.white)
            Text("Type fast and sloppy, and let AI fix it right where you're typing.")
                .font(.callout)
                .foregroundStyle(.white.opacity(0.92))
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 30)
        .padding(.horizontal, 24)
        .background(
            LinearGradient(
                colors: [Color.accentColor, Color.accentColor.opacity(0.68)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
    }

    // MARK: - Before / after

    private var beforeAfter: some View {
        AppCard {
            SectionLabel("See it in action")
            VStack(alignment: .leading, spacing: 8) {
                example(
                    tag: "YOU TYPE",
                    icon: "keyboard",
                    text: "whjkat m,ios th best thign swe dcan do to incmprve our converospn rates.",
                    prominent: false
                )
                Image(systemName: "arrow.down")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Color.accentColor)
                    .frame(maxWidth: .infinity)
                example(
                    tag: "TYPEFIX WRITES",
                    icon: "sparkles",
                    text: "What is the best thing we can do to improve our conversion rates?",
                    prominent: true
                )
            }
        }
    }

    private func example(tag: String, icon: String, text: String, prominent: Bool) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Label(tag, systemImage: icon)
                .font(.caption2.weight(.bold))
                .foregroundStyle(prominent ? Color.accentColor : .secondary)
            Text(text)
                .font(.system(size: 14, weight: prominent ? .semibold : .regular))
                .foregroundStyle(prominent ? .primary : .secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(prominent ? Color.accentColor.opacity(0.08) : Color.primary.opacity(0.04))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(prominent ? Color.accentColor.opacity(0.22) : Color.primary.opacity(0.06), lineWidth: 1)
        )
    }

    // MARK: - How it works

    private var howItWorks: some View {
        AppCard {
            SectionLabel("How to use it")
            VStack(alignment: .leading, spacing: 14) {
                step(
                    icon: "hand.tap.fill",
                    tint: Color(red: 0.30, green: 0.47, blue: 0.96),
                    title: "Manual: tap \(hotkeySymbol)",
                    detail: "Tap your shortcut, type, then tap it again. TypeFix rewrites what you typed."
                )
                step(
                    icon: "wand.and.stars",
                    tint: Color(red: 0.55, green: 0.38, blue: 0.92),
                    title: "Autofix: fix on a pause",
                    detail: "Turn on Autofix from the menu bar and it fixes automatically a moment after you stop typing."
                )
                step(
                    icon: "text.cursor",
                    tint: Color(red: 0.95, green: 0.55, blue: 0.20),
                    title: "Fix existing text: highlight, then tap \(hotkeySymbol)",
                    detail: "Already typed something? Select any text and tap your shortcut, and TypeFix rewrites the highlighted text in place."
                )
                step(
                    icon: "doc.on.doc.fill",
                    tint: Color(red: 0.09, green: 0.61, blue: 0.51),
                    title: "Recover anything: ⌥⇧⌘C",
                    detail: "Press ⌥⇧⌘C (or use the menu) to copy the original text of the last fix, in case a correction wasn't what you wanted."
                )
            }
        }
    }

    private func step(icon: String, tint: Color, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            IconChip(systemName: icon, tint: tint, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - Setup

    private var setup: some View {
        AppCard {
            SectionLabel("Two quick things to set up")
            setupRow(
                number: 1,
                done: accessibilityGranted,
                text: "Grant **Accessibility** so TypeFix can read and rewrite text.",
                buttonTitle: "Open",
                action: onOpenAccessibility
            )
            Divider()
            setupRow(
                number: 2,
                done: backendReady,
                text: "Choose your AI backend: a **cloud key** (Anthropic / OpenAI) or a **private, on-device** model.",
                buttonTitle: "Settings",
                action: onOpenSettings
            )
        }
    }

    private func setupRow(
        number: Int,
        done: Bool,
        text: LocalizedStringKey,
        buttonTitle: String,
        action: @escaping () -> Void
    ) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(done ? Color.green.opacity(0.16) : Color.accentColor.opacity(0.14))
                    .frame(width: 28, height: 28)
                if done {
                    Image(systemName: "checkmark")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.green)
                } else {
                    Text("\(number)")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Color.accentColor)
                }
            }
            Text(text)
                .font(.callout)
                .foregroundStyle(done ? .secondary : .primary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            if done {
                Label("Done", systemImage: "checkmark")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.green)
                    .labelStyle(.titleOnly)
            } else {
                Button(buttonTitle, action: action)
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
        .animation(.default, value: done)
    }

    // MARK: - Footer

    private var footer: some View {
        HStack {
            Text("Reopen this anytime from the menu: How TypeFix works…")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Button("Get Started", action: onDone)
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
        }
        .padding(16)
        .background(.bar)
    }
}
