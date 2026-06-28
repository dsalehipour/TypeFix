import AppKit

/// A fast, deterministic, on-device spell check used as a post-correction
/// guardrail: after the LLM rewrites text, we check whether it left (or
/// introduced) a likely-misspelled word so the user can be nudged to look.
///
/// Uses Apple's built-in `NSSpellChecker` (instant, local, no dependency). We
/// deliberately ignore proper nouns, all-caps abbreviations, and common chat
/// shorthand so domain terms and slang don't trigger false alarms.
enum TypoChecker {
    /// Chat shorthand / abbreviations that are correct as-is even though the
    /// dictionary doesn't know them.
    private static let allowedShorthand: Set<String> = [
        "idk", "imo", "ngl", "tbh", "btw", "lol", "pls", "plz", "u", "ur", "r",
        "ooo", "eod", "mvp", "fyi", "afaik", "iirc", "rn", "asap", "ymmv", "tl", "dr",
    ]

    /// An installed English dictionary id (e.g. "en", "en_US"). Picking an
    /// explicit language avoids the automatic language-identification path, which
    /// can block when invoked from a background/agent context.
    private static let englishLanguage: String = {
        let languages = NSSpellChecker.shared.availableLanguages
        return languages.first(where: { $0.hasPrefix("en") }) ?? "en"
    }()

    /// True if `text` contains a word that looks like an unintended misspelling.
    /// `allowing` is the user's personal dictionary (never treated as typos).
    ///
    /// Call this OFF the main thread. NSSpellChecker can block on its XPC service,
    /// and the main thread also services the keystroke event tap, so blocking it
    /// would freeze the user's typing.
    static func hasLikelyTypo(in text: String, allowing: [String] = []) -> Bool {
        let checker = NSSpellChecker.shared
        let allowed = allowedSet(allowing)
        let nsText = text as NSString
        var searchStart = 0

        while searchStart < nsText.length {
            let range = checker.checkSpelling(
                of: text,
                startingAt: searchStart,
                language: englishLanguage,
                wrap: false,
                inSpellDocumentWithTag: 0,
                wordCount: nil
            )
            guard range.location != NSNotFound, range.length > 0 else { break }
            let word = nsText.substring(with: range)
            if looksLikeTypo(word, allowed: allowed) { return true }
            searchStart = range.location + range.length
        }
        return false
    }

    private static func allowedSet(_ extra: [String]) -> Set<String> {
        var set = allowedShorthand
        for word in extra {
            let trimmed = word.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if !trimmed.isEmpty { set.insert(trimmed) }
        }
        return set
    }

    /// Returns `text` with each likely-misspelled word replaced by the system's
    /// top spelling suggestion. Returns the original if nothing was changed.
    /// Call OFF the main thread (see `hasLikelyTypo`).
    static func autoFixed(_ text: String, allowing: [String] = []) -> String {
        let checker = NSSpellChecker.shared
        let allowed = allowedSet(allowing)
        let nsText = text as NSString
        var replacements: [(NSRange, String)] = []
        var searchStart = 0

        while searchStart < nsText.length {
            let range = checker.checkSpelling(
                of: text,
                startingAt: searchStart,
                language: englishLanguage,
                wrap: false,
                inSpellDocumentWithTag: 0,
                wordCount: nil
            )
            guard range.location != NSNotFound, range.length > 0 else { break }
            let word = nsText.substring(with: range)
            if looksLikeTypo(word, allowed: allowed),
               let best = checker.guesses(
                   forWordRange: range, in: text, language: englishLanguage, inSpellDocumentWithTag: 0
               )?.first,
               !best.isEmpty {
                replacements.append((range, best))
            }
            searchStart = range.location + range.length
        }

        guard !replacements.isEmpty else { return text }
        let mutable = NSMutableString(string: text)
        // Apply back-to-front so earlier ranges stay valid.
        for (range, replacement) in replacements.reversed() {
            mutable.replaceCharacters(in: range, with: replacement)
        }
        return mutable as String
    }

    private static func looksLikeTypo(_ word: String, allowed: Set<String>) -> Bool {
        guard let first = word.first else { return false }
        if word.count < 3 { return false }            // too short to judge
        if first.isUppercase { return false }          // likely a proper noun
        if word == word.uppercased() { return false }  // all-caps abbreviation
        if word.contains(where: { $0.isNumber }) { return false } // e.g. "v2", "9am"
        if allowed.contains(word.lowercased()) { return false }
        return true
    }
}
