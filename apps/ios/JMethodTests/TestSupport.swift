import XCTest
@testable import JMethod

/// Accès aux vecteurs partagés de `native/shared/`, référencés comme ressources par project.yml (jamais copiés dans le dépôt).
/// À défaut de ressource (fichier ajouté après la génération du projet), lecture directe depuis le dépôt.
enum SharedVectors {
    static func url(_ name: String, subdirectory: String?) throws -> URL {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        if let bundled = Bundle(for: BundleToken.self).url(forResource: base, withExtension: ext) { return bundled }
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { root.deleteLastPathComponent() }
        var url = root.appendingPathComponent("native/shared")
        if let subdirectory { url.appendPathComponent(subdirectory) }
        url.appendPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("Vecteurs partagés introuvables : native/shared/\(subdirectory.map { $0 + "/" } ?? "")\(name)")
        }
        return url
    }

    static func object(_ name: String, subdirectory: String? = nil) throws -> [String: Any] {
        let data = try Data(contentsOf: url(name, subdirectory: subdirectory))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private final class BundleToken {}
}

/// Décodage d'un fragment JSON (déjà analysé) vers un type Codable.
func decodeJSON<T: Decodable>(_ type: T.Type, _ value: Any?, file: StaticString = #filePath, line: UInt = #line) throws -> T {
    let object = try XCTUnwrap(value, "valeur absente", file: file, line: line)
    let data = try JSONSerialization.data(withJSONObject: object, options: [.fragmentsAllowed])
    return try JSONDecoder().decode(T.self, from: data)
}

/// Instant figé `{ epochMs, utc }` des vecteurs.
func vectorDate(_ value: Any?) throws -> Date {
    let now = try XCTUnwrap(value as? [String: Any])
    let milliseconds = try XCTUnwrap((now["epochMs"] as? NSNumber)?.int64Value)
    return Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
}

/// Écart entre deux valeurs JSON (clés triées), ou nil si elles sont identiques.
func jsonMismatch(_ actual: Any, _ expected: Any) -> String? {
    let options: JSONSerialization.WritingOptions = [.sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes]
    guard let lhs = try? JSONSerialization.data(withJSONObject: actual, options: options),
          let rhs = try? JSONSerialization.data(withJSONObject: expected, options: options) else { return "sérialisation impossible" }
    guard lhs != rhs else { return nil }
    let a = Array(lhs), b = Array(rhs)
    let index = a.indices.first { $0 >= b.count || a[$0] != b[$0] } ?? min(a.count, b.count)
    let start = max(0, index - 80)
    return "obtenu …\(String(decoding: a[start..<min(a.count, index + 160)], as: UTF8.self)) / attendu …\(String(decoding: b[start..<min(b.count, index + 160)], as: UTF8.self))"
}

/// Comparaison structurelle de deux valeurs JSON (clés triées) avec un message lisible.
func assertJSONEqual(_ actual: Any, _ expected: Any, _ label: String, file: StaticString = #filePath, line: UInt = #line) {
    let options: JSONSerialization.WritingOptions = [.sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes]
    guard let lhs = try? JSONSerialization.data(withJSONObject: actual, options: options),
          let rhs = try? JSONSerialization.data(withJSONObject: expected, options: options) else {
        XCTFail("\(label) : sérialisation impossible", file: file, line: line)
        return
    }
    if lhs != rhs {
        XCTFail("\(label)\n  obtenu  : \(String(decoding: lhs, as: UTF8.self))\n  attendu : \(String(decoding: rhs, as: UTF8.self))", file: file, line: line)
    }
}

extension Optional {
    /// `nil` devient `NSNull` pour une comparaison JSON.
    var json: Any { self.map { $0 as Any } ?? NSNull() }
}
