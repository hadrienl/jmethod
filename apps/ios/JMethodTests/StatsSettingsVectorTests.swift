import XCTest
@testable import JMethod

/// Jalon 4 : suites `stats`, `settingsUpdate` et `importExport`, avec les écarts D3 à D7 et D13 (`DEVIATIONS.md`)
/// appliqués nommément, comme sur Android (`StatsSettingsVectorsTest`).
final class StatsSettingsVectorTests: XCTestCase {
    private var suites: [String: Any] = [:]
    private var fixtures: [String: Any] = [:]
    private var subjectSets: [String: [Subject]] = [:]
    private var problems: [String] = []
    private let today = "2026-11-17"

    override func setUpWithError() throws {
        let vectors = try SharedVectors.object("domain-vectors.json", subdirectory: "domain")
        suites = try XCTUnwrap(vectors["suites"] as? [String: Any])
        fixtures = try XCTUnwrap(vectors["fixtures"] as? [String: Any])
        for (name, value) in try XCTUnwrap(vectors["subjectSets"] as? [String: Any]) { subjectSets[name] = try decodeJSON([Subject].self, value) }
    }

    override func tearDown() {
        if !problems.isEmpty {
            XCTFail("\(problems.count) écart(s) :\n" + problems.prefix(25).joined(separator: "\n"))
        }
        problems = []
        super.tearDown()
    }

    private func check<T: Equatable>(_ actual: T, _ expected: T, _ label: String) {
        guard actual != expected else { return }
        problems.append("\(label) : \(String(describing: actual).prefix(300)) ≠ \(String(describing: expected).prefix(300))")
    }

    private func cases(_ suite: String) throws -> [[String: Any]] {
        try XCTUnwrap(suites[suite] as? [[String: Any]], "suite \(suite) absente")
    }

    private func subjects(_ value: Any?) throws -> [Subject] {
        if let name = value as? String { return try XCTUnwrap(subjectSets[name]) }
        return try decodeJSON([Subject].self, value)
    }

    // MARK: Mes progrès

    /// Valeurs corrigées par cas (le reste est comparé au web) : D4 sans aucune note « — » au lieu de 50 ;
    /// D5 seules les séances terminées notées comptent ; D6 `score: null` = pas de note.
    private struct Corrected {
        var overall: Int?
        var subjectAverages: [String: Int?] = [:]
        var weak: [String]?
    }

    private let statsCorrections: [String: Corrected] = [
        "no_data": .init(overall: nil), // D4
        "only_overdue_pending": .init(overall: nil), // D4
        // D5 : le 100 posé sur une séance anglaise non terminée ne compte plus.
        // Global (49+50+45+54+0)/5 = 39,6 → 40 ; maths 49,5 → 50 ; anglais (45+54+0)/3 = 33.
        "rounding_and_weak_subjects": .init(overall: 40, subjectAverages: ["maths": 50, "anglais": 33], weak: ["anglais"]),
        "subject_without_revisions_listed_with_null_average": .init(overall: 40, subjectAverages: ["maths": 50, "anglais": 33, "techno": nil], weak: ["anglais"]),
        // D6 : `score: null` n'est pas une note de 0.
        "score_null_from_import_counts_as_zero": .init(overall: nil, subjectAverages: ["maths": nil], weak: []),
    ]

    func testStats() throws {
        let list = try cases("stats")
        for item in list {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let model = StatsScreen(
                courses: try decodeJSON([Course].self, input["courses"]),
                revisions: try decodeJSON([Revision].self, input["revisions"]),
                subjects: try subjects(input["subjects"]),
                today: today
            )
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            check(model.completionRate, expected["completionRate"] as? Int, "\(name) taux")
            check(model.completionLine, expected["completionLine"] as? String, "\(name) ligne")
            // D3 : la régularité est calculée (testD3Streak) ; le web affiche toujours « 5 j ».
            check(expected["streakLabel"] as? String, "5 j", "\(name) vecteur D3")
            check(model.coursesCount, expected["coursesCount"] as? Int, "\(name) cours")
            // D7 : « Sur 1 matière » (0 et 1 au singulier).
            let subjectsLine = (expected["subjectsLine"] as? String ?? "").replacingOccurrences(of: #"^Sur ([01]) matières$"#, with: "Sur $1 matière", options: .regularExpression)
            check(model.subjectsLine, subjectsLine, "\(name) matières")
            let rows = try XCTUnwrap(expected["subjects"] as? [[String: Any]])
            check(model.subjects.map(\.subject.id), rows.compactMap { $0["subjectId"] as? String }, "\(name) lignes")
            let fix = statsCorrections[name]
            for (row, actual) in zip(rows, model.subjects) {
                let id = actual.subject.id
                check(actual.coursesCount, row["coursesCount"] as? Int, "\(name) \(id) cours")
                check(actual.totalRevs, row["totalRevs"] as? Int, "\(name) \(id) total")
                check(actual.completedRevs, row["completedRevs"] as? Int, "\(name) \(id) faites")
                check(actual.rate, row["rate"] as? Int, "\(name) \(id) taux")
                check(actual.line, row["line"] as? String, "\(name) \(id) ligne")
                if let fix, let corrected = fix.subjectAverages[id] {
                    check(actual.avgScore, corrected, "\(name) \(id) moyenne corrigée")
                } else {
                    check(actual.avgScore, row["avgScore"] as? Int, "\(name) \(id) moyenne")
                    check(actual.averageTone?.rawValue, row["averageTone"] as? String, "\(name) \(id) ton")
                }
            }
            if let fix {
                check(model.overallAvgScore, fix.overall, "\(name) moyenne corrigée")
                if fix.overall == nil {
                    check(model.overallAvgLabel, "—", "\(name) D4")
                    check(model.balanceLabel, "Pas encore de note", "\(name) D4")
                }
                if let weak = fix.weak { check(model.weakSubjects.map(\.subject.id), weak, "\(name) à retravailler") }
            } else {
                check(model.overallAvgScore, expected["overallAvgScore"] as? Int, "\(name) moyenne")
                check(model.balanceLabel, expected["balanceLabel"] as? String, "\(name) équilibre")
                check(model.weakSubjects.map(\.subject.id), expected["weakSubjects"] as? [String] ?? [], "\(name) à retravailler")
            }
        }
        check(list.count, 6, "stats")
    }

    private func revision(_ id: String, completed: String?, score: Int? = 70) -> Revision {
        Revision(id: id, courseId: "c", stepIndex: 0, stepLabel: "J0", scheduledDate: "2026-11-01", completedDate: completed, score: score,
                 status: completed == nil ? .pending : .completed, phase: .encodage, adjustedIntervalDays: 0)
    }

    /// D3 : jours consécutifs avec une séance validée, depuis aujourd'hui ou depuis hier.
    func testD3Streak() {
        let days = ["2026-11-17", "2026-11-16", "2026-11-16", "2026-11-15", "2026-11-13"]
        let revisions = days.enumerated().map { revision("r\($0.offset)", completed: $0.element) } + [revision("p", completed: nil)]
        XCTAssertEqual(StatsScreen.streak(revisions: revisions, today: today), 3)
        XCTAssertEqual(StatsScreen.streak(revisions: revisions.filter { $0.completedDate != "2026-11-17" }, today: today), 2, "rien aujourd'hui : depuis hier")
        XCTAssertEqual(StatsScreen.streak(revisions: revisions.filter { $0.completedDate == "2026-11-13" }, today: today), 0)
        XCTAssertEqual(StatsScreen.streak(revisions: [], today: today), 0)
        // Passage de mois et changement d'heure : jours civils.
        let autumn = ["2026-10-24", "2026-10-25", "2026-10-26"].enumerated().map { revision("a\($0.offset)", completed: $0.element) }
        XCTAssertEqual(StatsScreen.streak(revisions: autumn, today: "2026-10-26"), 3)
        let course = Course(id: "c", subjectId: "maths", chapter: "C", courseNumber: 1, title: "T", date: "2026-11-01", createdAt: "2026-11-01")
        let empty = StatsScreen(courses: [course], revisions: [], subjects: Constants.defaultSubjects, today: today)
        XCTAssertEqual(empty.streakLabel, "0 j")
        XCTAssertEqual(empty.streakLine, "Valide une séance pour commencer")
        let streak = StatsScreen(courses: [course], revisions: revisions, subjects: Constants.defaultSubjects, today: today)
        XCTAssertEqual(streak.streakLabel, "3 j")
        XCTAssertEqual(streak.streakLine, "Jours consécutifs avec une séance")
    }

    /// D4 à D7 : « — » sans note, séance non terminée ignorée, `null` sans note, accords.
    func testD4ToD7() throws {
        let course = Course(id: "c", subjectId: "maths", chapter: "C", courseNumber: 1, title: "T", date: "2026-11-01", createdAt: "2026-11-01")
        let pendingScored = revision("p", completed: nil, score: 10)
        let nullScore = try decodeJSON(Revision.self, ["id": "n", "courseId": "c", "stepIndex": 0, "stepLabel": "J0", "scheduledDate": "2026-11-01",
                                                       "completedDate": "2026-11-10", "status": "completed", "phase": "encodage", "score": NSNull()])
        XCTAssertNil(nullScore.score, "D6")
        let empty = StatsScreen(courses: [course], revisions: [pendingScored, nullScore], subjects: Constants.defaultSubjects, today: today)
        XCTAssertNil(empty.overallAvgScore)
        XCTAssertEqual(empty.overallAvgLabel, "—")
        XCTAssertEqual(empty.balanceLabel, "Pas encore de note")
        XCTAssertNil(empty.subjects.first?.avgScore)
        let scored = StatsScreen(courses: [course], revisions: [pendingScored, nullScore, revision("d", completed: "2026-11-11", score: 40)],
                                 subjects: Constants.defaultSubjects, today: today)
        XCTAssertEqual(scored.overallAvgScore, 40)
        XCTAssertEqual(scored.balanceLabel, "Sous l’équilibre")
        XCTAssertEqual(scored.weakSubjects.map(\.subject.id), ["maths"])
        XCTAssertEqual(scored.subjectsLine, "Sur 1 matière")
        XCTAssertEqual(scored.subjects.first?.accessibilityLabel, "Mathématiques, 1 cours, 2 séances faites sur 3, 67 %, moyenne 40 sur 100")
    }

    // MARK: Réglages

    func testSettingsUpdate() throws {
        let list = try cases("settingsUpdate")
        for item in list {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            switch input["action"] as? String {
            case "addInterval":
                check(SettingsLogic.addInterval(try decodeJSON([Int].self, input["intervals"]), value: try XCTUnwrap(input["value"] as? String)),
                      try decodeJSON([Int].self, expected["intervals"]), name)
            case "removeInterval":
                let value = try XCTUnwrap(input["value"] as? Int)
                check(SettingsLogic.removeInterval(try decodeJSON([Int].self, input["intervals"]), value: value), try decodeJSON([Int].self, expected["intervals"]), name)
                check(SettingsLogic.removeButtonShown(value), expected["removeButtonShown"] as? Bool, name)
            case "resetIntervals":
                check(SettingsLogic.resetIntervals(), try decodeJSON([Int].self, expected["intervals"]), name)
            case "save":
                let edit = try XCTUnwrap(input["edit"] as? [String: Any])
                let saved = SettingsLogic.save(
                    try decodeJSON(AppSettings.self, input["settings"]), icalUrl: try XCTUnwrap(edit["icalUrl"] as? String),
                    sensitivity: try XCTUnwrap((edit["sensitivity"] as? NSNumber)?.doubleValue),
                    emergencyThreshold: try XCTUnwrap((edit["emergencyThreshold"] as? NSNumber)?.doubleValue),
                    intervals: try decodeJSON([Int].self, edit["intervals"])
                )
                check(saved, try decodeJSON(AppSettings.self, expected["settings"]), name)
            case "sensitivityLabel":
                let value = try XCTUnwrap((input["sensitivity"] as? NSNumber)?.doubleValue)
                check(SettingsLogic.sensitivityLabel(value), expected["label"] as? String, name)
                check(SettingsLogic.jsNumber(SettingsLogic.snapSensitivity(value)), expected["valueText"] as? String, name)
            case "thresholdLabel":
                check(SettingsLogic.thresholdLabel(try XCTUnwrap((input["emergencyThreshold"] as? NSNumber)?.doubleValue)), expected["label"] as? String, name)
            case "subjectColor":
                check(SettingsLogic.subjectColor(try subjects(input["subjects"]), subjectId: try XCTUnwrap(input["subjectId"] as? String),
                                                 color: try XCTUnwrap(input["color"] as? String)),
                      try subjects(expected["subjects"]), name)
            default:
                problems.append("\(name) : action inconnue")
            }
        }
        check(list.count, 30, "settingsUpdate")
        let constants = try SharedVectors.object("constants.json", subdirectory: "domain")
        let sliders = try XCTUnwrap(constants["SETTINGS_SLIDERS"] as? [String: [String: Double]])
        check(sliders["sensitivity"], ["min": SettingsLogic.sensitivityRange.lowerBound, "max": SettingsLogic.sensitivityRange.upperBound, "step": SettingsLogic.sensitivityStep], "curseur sensibilité")
        check(sliders["emergencyThreshold"], ["min": SettingsLogic.thresholdRange.lowerBound, "max": SettingsLogic.thresholdRange.upperBound, "step": SettingsLogic.thresholdStep], "curseur seuil")
        check(constants["INTERVALS_REMOVE_REQUIRES_MORE_THAN"] as? Int, SettingsLogic.removeRequiresMoreThan, "retrait")
    }

    // MARK: Sauvegarde

    /// D13 : formes invalides refusées (le web les accepte et les stocke).
    private let importRejectedByD13: Set<String> = ["courses_object_is_accepted", "wrong_shape_string_is_accepted"]

    func testImportExport() throws {
        let list = try cases("importExport")
        let before = try XCTUnwrap(fixtures["importBefore"] as? [String: Any])
        let beforeDocument = AppDocument(
            settings: try decodeJSON(AppSettings.self, before["settings"]), subjects: try decodeJSON([Subject].self, before["subjects"]),
            courses: try decodeJSON([Course].self, before["courses"]), revisions: try decodeJSON([Revision].self, before["revisions"]),
            pronoteEvents: try decodeJSON([PronoteEvent].self, before["pronoteEvents"]), initialized: true
        )
        var replayed = 0, d13 = 0
        for item in list {
            let name = item["name"] as? String ?? ""
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            if name == "export_format" {
                let state = try XCTUnwrap(input["state"] as? [String: Any])
                let document = AppDocument(
                    settings: try decodeJSON(AppSettings.self, state["settings"]), subjects: try decodeJSON([Subject].self, state["subjects"]),
                    courses: try decodeJSON([Course].self, state["courses"]), revisions: try decodeJSON([Revision].self, state["revisions"]),
                    pronoteEvents: [], initialized: true
                )
                check(Backup.export(document, now: try vectorDate(item["now"])), expected["json"] as? String, name)
                replayed += 1
                continue
            }
            let result = Backup.parse(Data(try XCTUnwrap(input["json"] as? String).utf8))
            if importRejectedByD13.contains(name) {
                check(expected["accepted"] as? Bool, true, "\(name) (vecteur D13)")
                guard case .rejected(let title, let message) = result else {
                    problems.append("\(name) : accepté malgré D13")
                    continue
                }
                check(title, Backup.errorTitle, name)
                check(message.hasPrefix("Ce fichier n’est pas une sauvegarde J-Method"), true, name)
                d13 += 1
                continue
            }
            let after: AppDocument
            switch result {
            case .accepted(let accepted):
                check(expected["accepted"] as? Bool, true, name)
                after = accepted.apply(to: beforeDocument)
            case .rejected(let title, let message):
                check(expected["accepted"] as? Bool, false, name)
                let toast = try XCTUnwrap(expected["toast"] as? [String: Any])
                check(title, toast["title"] as? String, name)
                check(message, toast["message"] as? String, name)
                after = beforeDocument
            }
            let state = try XCTUnwrap(expected["stateAfterReload"] as? [String: Any])
            check(after.courses, try decodeJSON([Course].self, state["courses"]), "\(name) cours")
            check(after.revisions, try decodeJSON([Revision].self, state["revisions"]), "\(name) séances")
            check(after.subjects, try decodeJSON([Subject].self, state["subjects"]), "\(name) matières")
            check(after.pronoteEvents, try decodeJSON([PronoteEvent].self, state["pronoteEvents"]), "\(name) créneaux")
            check(after.settings, try decodeJSON(AppSettings.self, state["settings"]), "\(name) réglages")
            replayed += 1
        }
        check(list.count, 13, "importExport")
        check(replayed, 11, "importExport rejoués")
        check(d13, 2, "importExport D13")
    }

    /// D13 : validation de forme ; D6 : `score: null` importé lu comme absent.
    func testD13ShapeValidation() throws {
        func rejected(_ text: String) -> Bool {
            if case .rejected = Backup.parse(Data(text.utf8)) { return true }
            return false
        }
        XCTAssertTrue(rejected(#"{"courses":{},"revisions":[]}"#))
        XCTAssertTrue(rejected(#"{"courses":[],"revisions":[1]}"#))
        XCTAssertTrue(rejected(#"{"courses":[{"id":"c"}],"revisions":[]}"#))
        XCTAssertTrue(rejected(#"{"courses":[],"revisions":[],"subjects":"x"}"#))
        XCTAssertTrue(rejected(#"{"courses":[],"revisions":[],"settings":[]}"#))
        XCTAssertTrue(rejected(#"{"courses":[],"revisions":[{"id":"r","courseId":"c","stepIndex":0,"stepLabel":"J0","scheduledDate":"2026-11-01","status":"inconnu","phase":"encodage"}]}"#))
        guard case .accepted(let accepted) = Backup.parse(Data(#"{"courses":[],"revisions":[{"id":"r","courseId":"c","stepIndex":0,"stepLabel":"J0","scheduledDate":"2026-11-01","status":"completed","phase":"encodage","score":null}]}"#.utf8)) else {
            return XCTFail("sauvegarde valide refusée")
        }
        XCTAssertNil(accepted.revisions.first?.score)
        // Un export natif se relit.
        let document = try Fixture.document(today: today)
        guard case .accepted(let roundTrip) = Backup.parse(Data(Backup.export(document, now: Date()).utf8)) else { return XCTFail("export illisible") }
        XCTAssertEqual(roundTrip.courses, document.courses)
        XCTAssertEqual(roundTrip.revisions, document.revisions)
        XCTAssertEqual(roundTrip.subjects, document.subjects)
        XCTAssertEqual(roundTrip.settings, document.settings)
        XCTAssertEqual(Backup.fileName(today: today), "jmethod-sauvegarde-2026-11-17.json")
    }

    func testJSParseInt() {
        XCTAssertEqual(SettingsLogic.jsParseInt("12abc"), 12)
        XCTAssertEqual(SettingsLogic.jsParseInt(" 30"), 30)
        XCTAssertEqual(SettingsLogic.jsParseInt("+5"), 5)
        XCTAssertEqual(SettingsLogic.jsParseInt("1e3"), 1)
        XCTAssertEqual(SettingsLogic.jsParseInt("-0"), 0)
        XCTAssertNil(SettingsLogic.jsParseInt("abc"))
        XCTAssertNil(SettingsLogic.jsParseInt(""))
        XCTAssertEqual(SettingsLogic.addInterval([0, 2], value: "99999999999"), [0, 2], "valeur hors des entiers ignorée")
    }
}
