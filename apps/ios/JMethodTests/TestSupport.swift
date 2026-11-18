import XCTest
@testable import JMethod

/// Accès aux vecteurs partagés de `native/shared/`, référencés comme ressources par project.yml (jamais copiés dans le dépôt).
/// À défaut de ressource (fichier ajouté après la génération du projet), lecture directe depuis le dépôt.
enum SharedVectors {
    static func url(_ name: String, subdirectory: String?) throws -> URL {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        // Fichiers isolés copiés à la racine du bundle ; dossiers (`documents/`) copiés tels quels.
        let bundle = Bundle(for: BundleToken.self)
        if let bundled = bundle.url(forResource: base, withExtension: ext, subdirectory: subdirectory)
            ?? bundle.url(forResource: base, withExtension: ext) {
            return bundled
        }
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

    /// Dossier des documents synthétiques (`native/shared/documents/files`), copié dans le bundle de test :
    /// le simulateur ne lit pas les fichiers du dépôt.
    static func documentsDirectory() throws -> URL {
        var candidates: [URL] = []
        if let resources = Bundle(for: BundleToken.self).resourceURL {
            candidates.append(resources.appendingPathComponent("documents/files"))
        }
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { root.deleteLastPathComponent() }
        candidates.append(root.appendingPathComponent("native/shared/documents/files"))
        guard let found = candidates.first(where: { FileManager.default.isReadableFile(atPath: $0.appendingPathComponent("fiche.md").path) }) else {
            throw XCTSkip("Documents synthétiques introuvables : native/shared/documents/files")
        }
        return found
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

/// Document de test (aucun lien avec l'ancien jeu de démonstration) : deux cours avec leurs révisions,
/// les séances antérieures à `today` étant terminées, et un créneau Pronote synchronisé pour le lendemain.
enum Fixture {
    static func document(today: String) throws -> AppDocument {
        let first = try XCTUnwrap(SchoolDay.adding(-7, to: today))
        let second = today
        let courses = [
            Course(id: "course-test-1", subjectId: "maths", chapter: "Chapitre test", courseNumber: 1, title: "Cours test 1", date: first, description: "Résumé test", createdAt: first),
            Course(id: "course-test-2", subjectId: "francais", chapter: "Chapitre test", courseNumber: 2, title: "Cours test 2", date: second, description: nil, createdAt: second),
        ]
        var revisions: [Revision] = []
        for course in courses { revisions += try AdaptiveAlgorithm.initialRevisions(for: course, intervals: Constants.defaultIntervals) }
        revisions = revisions.map { revision in
            guard revision.scheduledDate < today else { return revision }
            var done = revision
            done.status = .completed
            done.completedDate = revision.scheduledDate
            done.score = 70
            return done
        }
        let event = PronoteEvent(id: "pronote-x-\(try XCTUnwrap(SchoolDay.adding(1, to: today)))T08:00:00-0", subjectName: "MATHEMATIQUES", matchedSubjectId: "maths",
                                 date: try XCTUnwrap(SchoolDay.adding(1, to: today)), startTime: "08:00", endTime: "09:00", room: "Salle 1", teacher: "Professeur A", isCancelled: false)
        return AppDocument(settings: Constants.defaultSettings, subjects: Constants.defaultSubjects, courses: courses, revisions: revisions, pronoteEvents: [event], initialized: true)
    }
}
