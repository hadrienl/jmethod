import Foundation

/// Portage de `matchSubject` (`src/lib/icalParser.ts`). Les comparaisons se font en unités UTF-16,
/// comme `String.prototype.includes`, après minuscules, NFD et suppression des diacritiques U+0300–U+036F.
enum SubjectMatcher {
    static func normalize(_ value: String) -> String {
        let decomposed = value.lowercased().decomposedStringWithCanonicalMapping
        var scalars = String.UnicodeScalarView()
        scalars.append(contentsOf: decomposed.unicodeScalars.filter { !(0x300...0x36F).contains($0.value) })
        return String(scalars)
    }

    static func match(_ rawSummary: String, subjects: [Subject]) -> Subject? {
        let summary = Array(normalize(rawSummary).utf16)
        let has: (String) -> Bool = { contains(summary, Array($0.utf16)) }

        // L'EPS est une matière distincte de la physique-chimie.
        if isPhysicalEducation(summary) {
            return subjects.first { $0.id == "eps" }
        }

        return subjects.first { subject in
            if contains(summary, Array(normalize(subject.name).utf16)) { return true }
            switch subject.id {
            case "maths": return has("math") || has("mathematiques")
            case "svt": return has("s.v.t") || has("svt") || has("sciences de la vie") || has("sciences vie")
            case "physique-chimie": return has("physique") || has("chimie") || has("spc")
            case "histoire-geo": return has("histoire") || has("geo") || has("emc") || has("h-g")
            case "francais": return has("francais")
            case "anglais": return has("anglais")
            case "espagnol": return has("espagnol")
            case "techno": return has("techno") || has("technologie")
            case "arts-plastiques": return has("arts") || has("plastique")
            case "musique": return has("musique") || has("chant")
            default: return false
            }
        }
    }

    /// `/\beps\b|sport|ed\.?\s*physique|education physique/` avec la frontière de mot ASCII de JavaScript.
    private static func isPhysicalEducation(_ text: [UInt16]) -> Bool {
        if contains(text, Array("sport".utf16)) || contains(text, Array("education physique".utf16)) { return true }
        let eps = Array("eps".utf16)
        if eps.count <= text.count {
            for start in 0...(text.count - eps.count) where Array(text[start..<start + eps.count]) == eps {
                let before = start == 0 || !isWordUnit(text[start - 1])
                let afterIndex = start + eps.count
                let after = afterIndex == text.count || !isWordUnit(text[afterIndex])
                if before && after { return true }
            }
        }
        // ed, point facultatif, espaces (\s JavaScript), physique
        let ed = Array("ed".utf16), physique = Array("physique".utf16)
        guard text.count >= ed.count + physique.count else { return false }
        for start in 0...(text.count - ed.count) where Array(text[start..<start + ed.count]) == ed {
            var index = start + ed.count
            if index < text.count, text[index] == 0x2E { index += 1 }
            while index < text.count, isJSWhitespace(text[index]) { index += 1 }
            if index + physique.count <= text.count, Array(text[index..<index + physique.count]) == physique { return true }
        }
        return false
    }

    private static func isWordUnit(_ unit: UInt16) -> Bool {
        (0x30...0x39).contains(unit) || (0x41...0x5A).contains(unit) || (0x61...0x7A).contains(unit) || unit == 0x5F
    }

    private static func isJSWhitespace(_ unit: UInt16) -> Bool {
        switch unit {
        case 0x09...0x0D, 0x20, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF: true
        default: false
        }
    }

    private static func contains(_ haystack: [UInt16], _ needle: [UInt16]) -> Bool {
        if needle.isEmpty { return true }
        guard needle.count <= haystack.count else { return false }
        for start in 0...(haystack.count - needle.count) where haystack[start] == needle[0] {
            if Array(haystack[start..<start + needle.count]) == needle { return true }
        }
        return false
    }
}
