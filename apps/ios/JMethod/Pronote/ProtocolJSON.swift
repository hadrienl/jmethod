import Foundation

/// Lecture tolérante des structures JSON Pronote (`{ _T, V }`, nombres en texte, booléens typés).
enum ProtocolJSON {
    static func object(_ value: Any?) -> [String: Any] { value as? [String: Any] ?? [:] }
    static func unwrap(_ value: Any?) -> Any? { (value as? [String: Any])?["V"] ?? value }
    static func list(_ value: Any?) -> [[String: Any]] { unwrap(value) as? [[String: Any]] ?? [] }

    static func string(_ value: Any?) -> String {
        if let text = value as? String { return text }
        if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() { return number.stringValue }
        return ""
    }

    static func integer(_ value: Any?) -> Int {
        if let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() { return number.intValue }
        return Int(string(value)) ?? 0
    }

    /// Un vrai booléen JSON, ou les textes "true"/"1" ; la chaîne "false" reste fausse.
    static func bool(_ value: Any?) -> Bool {
        if let number = value as? NSNumber {
            return CFGetTypeID(number) == CFBooleanGetTypeID() ? number.boolValue : number.intValue != 0
        }
        return ["true", "1"].contains(string(value).lowercased())
    }

    static func parseStart(_ html: String) throws -> [String: Any] {
        let expression = try NSRegularExpression(pattern: #"\bStart\s*\(\s*(\{[^}]*\})\s*\)"#)
        let range = NSRange(html.startIndex..., in: html)
        guard let match = expression.firstMatch(in: html, range: range), let bodyRange = Range(match.range(at: 1), in: html) else {
            throw PronoteFailure.invalid("html", "Cette page demande une connexion officielle ou le format Pronote a changé.")
        }
        let body = String(html[bodyRange])
        let attribute = try NSRegularExpression(pattern: #"(?:\{|,)\s*['"]?([\w]+)['"]?\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([^,}]+))"#)
        var result: [String: Any] = [:]
        for match in attribute.matches(in: body, range: NSRange(body.startIndex..., in: body)) {
            guard let keyRange = Range(match.range(at: 1), in: body) else { continue }
            var raw = ""
            var quoted = false
            for index in 2...4 {
                if let valueRange = Range(match.range(at: index), in: body) {
                    raw = String(body[valueRange]).trimmingCharacters(in: .whitespacesAndNewlines)
                    quoted = index < 4
                    break
                }
            }
            let key = String(body[keyRange])
            if !quoted && raw == "true" { result[key] = true }
            else if !quoted && raw == "false" { result[key] = false }
            else if !quoted, let number = Int(raw) { result[key] = number }
            else { result[key] = raw.replacingOccurrences(of: #"\'"#, with: "'").replacingOccurrences(of: #"\""#, with: #"""#) }
        }
        guard result["h"] != nil, result["a"] != nil else { throw PronoteFailure.invalid("html", "Identifiant de session Pronote introuvable.") }
        return result
    }

    /// `18/11/2026 08:30:00` devient `2026-11-18T08:30:00`, sans fuseau ajouté : c'est l'heure locale de l'établissement.
    static func dateString(_ raw: Any?) -> String {
        let value = string(unwrap(raw)).trimmingCharacters(in: .whitespaces)
        let parts = value.split(separator: " ", maxSplits: 1).map(String.init)
        let date = (parts.first ?? "").split(separator: "/").map(String.init)
        guard date.count == 3, let day = Int(date[0]), let month = Int(date[1]), let year = Int(date[2]) else { return "" }
        var time = "00:00:00"
        if parts.count > 1 {
            let pieces = parts[1].split(separator: ":").compactMap { Int($0) }
            guard pieces.count >= 2 else { return "" }
            time = String(format: "%02d:%02d:%02d", pieces[0], pieces[1], pieces.count > 2 ? pieces[2] : 0)
        }
        return SchoolDay.format(year: year, month: month, day: day) + "T" + time
    }
}
