import XCTest
@testable import JMethod

/// Rejoue `native/shared/domain/domain-vectors.json`, généré depuis le TypeScript du web.
/// Les suites du jalon 1 sont rejouées intégralement ; `parseICalFeed` n'est pas porté dans ce jalon.
final class DomainVectorTests: XCTestCase {
    private var vectors: [String: Any] = [:]
    private var suites: [String: Any] = [:]
    private var subjectSets: [String: [Subject]] = [:]
    /// Écarts regroupés : un seul échec court par test, pour des rapports lisibles.
    private var problems: [String] = []

    override func tearDown() {
        if !problems.isEmpty {
            XCTFail("\(problems.count) écart(s) :\n" + problems.prefix(25).joined(separator: "\n"))
        }
        problems = []
        super.tearDown()
    }

    private func check<T: Equatable>(_ actual: T, _ expected: T, _ label: String = #function) {
        guard actual != expected else { return }
        problems.append("\(label) : \(String(describing: actual).prefix(300)) ≠ \(String(describing: expected).prefix(300))")
    }

    private func check<T: Equatable>(_ actual: [T], _ expected: [T], _ label: String = #function) {
        guard actual != expected else { return }
        if actual.count != expected.count {
            problems.append("\(label) : \(actual.count) éléments au lieu de \(expected.count)")
        }
        if let index = zip(actual, expected).enumerated().first(where: { $0.element.0 != $0.element.1 })?.offset {
            problems.append("\(label) [\(index)] : \(String(describing: actual[index]).prefix(400)) ≠ \(String(describing: expected[index]).prefix(400))")
        }
    }

    private func checkJSON(_ actual: Any, _ expected: Any, _ label: String) {
        if let mismatch = jsonMismatch(actual, expected) { problems.append("\(label) : \(mismatch)") }
    }

    override func setUpWithError() throws {
        vectors = try SharedVectors.object("domain-vectors.json", subdirectory: "domain")
        check(vectors["schemaVersion"] as? Int, 1)
        check(vectors["timeZone"] as? String, "Europe/Paris")
        check(vectors["containsPersonalData"] as? Bool, false)
        suites = try XCTUnwrap(vectors["suites"] as? [String: Any])
        let sets = try XCTUnwrap(vectors["subjectSets"] as? [String: Any])
        for (name, value) in sets { subjectSets[name] = try decodeJSON([Subject].self, value) }
    }

    private func cases(_ suite: String) throws -> [[String: Any]] {
        let list = try XCTUnwrap(suites[suite] as? [[String: Any]], "suite \(suite) absente")
        XCTAssertFalse(list.isEmpty, "suite \(suite) vide")
        return list
    }

    private func subjects(_ value: Any?) throws -> [Subject] {
        try XCTUnwrap(subjectSets[try XCTUnwrap(value as? String)])
    }

    // MARK: Algorithme adaptatif

    func testGetPhaseForStep() throws {
        for item in try cases("getPhaseForStep") {
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let step = try XCTUnwrap(input["stepIndex"] as? Int)
            check(AdaptiveAlgorithm.phase(forStep: step).rawValue, item["expected"] as? String, item["name"] as? String ?? "")
        }
    }

    func testGenerateInitialRevisions() throws {
        for item in try cases("generateInitialRevisions") {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let course = try decodeJSON(Course.self, input["course"])
            let intervals = try decodeJSON([Int].self, input["intervals"])
            let expected = try decodeJSON([Revision].self, item["expected"])
            check(try AdaptiveAlgorithm.initialRevisions(for: course, intervals: intervals), expected, name)
        }
    }

    func testApplyAdaptiveReview() throws {
        var count = 0
        for item in try cases("applyAdaptiveReview") {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let run = {
                try AdaptiveAlgorithm.applyReview(
                    courseRevisions: try decodeJSON([Revision].self, input["revisions"]),
                    revisionId: try XCTUnwrap(input["revisionId"] as? String),
                    score: try XCTUnwrap(input["score"] as? Int),
                    settings: try decodeJSON(AppSettings.self, input["settings"]),
                    actualCompletionDate: input["actualCompletionDate"] as? String,
                    schedulingReferenceDate: input["schedulingReferenceDate"] as? String,
                    now: try vectorDate(item["now"])
                )
            }
            if let errorInfo = item["expectedError"] as? [String: Any] {
                do {
                    _ = try run()
                    problems.append("\(name) : exception attendue")
                } catch {
                    check((error as? LocalizedError)?.errorDescription, errorInfo["message"] as? String, name)
                }
            } else {
                try assertResult(try run(), item["expected"], name)
            }
            count += 1
        }
        check(count, 65)
    }

    func testApplyAdaptiveReviewChains() throws {
        var steps = 0
        for chain in try cases("applyAdaptiveReviewChains") {
            let name = chain["name"] as? String ?? ""
            let settings = try decodeJSON(AppSettings.self, chain["settings"])
            var revisions = try decodeJSON([Revision].self, chain["initialRevisions"])
            for (index, step) in try XCTUnwrap(chain["steps"] as? [[String: Any]]).enumerated() {
                let result = try AdaptiveAlgorithm.applyReview(
                    courseRevisions: revisions,
                    revisionId: try XCTUnwrap(step["revisionId"] as? String),
                    score: try XCTUnwrap(step["score"] as? Int),
                    settings: settings,
                    actualCompletionDate: step["actualCompletionDate"] as? String,
                    schedulingReferenceDate: step["schedulingReferenceDate"] as? String,
                    now: try vectorDate(step["now"])
                )
                try assertResult(result, step["expected"], "\(name) étape \(index)")
                // L'étape suivante part du résultat attendu, comme le générateur.
                revisions = try decodeJSON([Revision].self, (step["expected"] as? [String: Any])?["updatedRevisions"])
                steps += 1
            }
        }
        check(steps, 44)
    }

    private func assertResult(_ result: AdaptiveAlgorithm.Result, _ expectedValue: Any?, _ name: String) throws {
        let expected = try XCTUnwrap(expectedValue as? [String: Any])
        check(result.updatedRevisions, try decodeJSON([Revision].self, expected["updatedRevisions"]), name)
        let explanation = try XCTUnwrap(expected["explanation"] as? [String: Any])
        check(result.explanation.score, explanation["score"] as? Int, name)
        let factor = try XCTUnwrap((explanation["factor"] as? NSNumber)?.doubleValue)
        if abs(result.explanation.factor - factor) > 1e-12 { problems.append("\(name) factor : \(result.explanation.factor) ≠ \(factor)") }
        check(result.explanation.message, explanation["message"] as? String, name)
        check(result.explanation.actionType.rawValue, explanation["actionType"] as? String, name)
        check(result.explanation.nextDate, explanation["nextDate"] as? String, name)
    }

    // MARK: Programme du soir et matières

    func testGetEveningProgramme() throws {
        for item in try cases("getEveningProgramme") {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let programme = EveningProgramme(
                revisions: try decodeJSON([Revision].self, input["revisions"]),
                courses: try decodeJSON([Course].self, input["courses"]),
                today: try XCTUnwrap(input["today"] as? String)
            )
            let actual: [String: Any] = [
                "preparationDate": programme.preparationDate,
                "due": programme.due.map(\.id), "completed": programme.completed.map(\.id),
                "overdue": programme.overdue.map(\.id), "visible": programme.visible.map(\.id),
            ]
            checkJSON(actual, try XCTUnwrap(item["expected"]), name)
        }
    }

    func testMatchSubject() throws {
        for item in try cases("matchSubject") {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let match = SubjectMatcher.match(try XCTUnwrap(input["summary"] as? String), subjects: try subjects(input["subjects"]))
            check(match?.id, item["expectedSubjectId"] as? String, name)
        }
    }

    // MARK: Démonstration

    func testGenerateSamplePronoteFeedEvents() throws {
        for item in try cases("generateSamplePronoteFeed") {
            let name = item["name"] as? String ?? ""
            let today = SchoolDay.today(now: try vectorDate(item["now"]))
            let events = try DemoData.sampleEvents(today: today, subjects: Constants.defaultSubjects)
            check(events, try decodeJSON([PronoteEvent].self, item["expectedEvents"]), name)
        }
    }

    func testInitializeDefaultData() throws {
        for item in try cases("initializeDefaultData") {
            let name = item["name"] as? String ?? ""
            let random = try XCTUnwrap(item["mathRandom"] as? Double)
            let document = try DemoData.document(today: SchoolDay.today(now: try vectorDate(item["now"])), random: { random })
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            check(document.courses, try decodeJSON([Course].self, expected["courses"]), name)
            check(document.revisions, try decodeJSON([Revision].self, expected["revisions"]), name)
            check(document.subjects, try decodeJSON([Subject].self, expected["subjects"]), name)
            check(document.pronoteEvents, try decodeJSON([PronoteEvent].self, expected["pronoteEvents"]), name)
            check(document.settings, try decodeJSON(AppSettings.self, expected["settings"]), name)

            // L'export JSON du web se relit comme document de l'app (sans créneaux Pronote).
            let exported = try decodeJSON(AppDocument.self, item["expectedExport"])
            check(exported.courses, document.courses, name)
            check(exported.revisions, document.revisions, name)
            check(exported.subjects, document.subjects, name)
            check(exported.settings, document.settings, name)
            check(exported.pronoteEvents, [], name)
            XCTAssertTrue(exported.initialized, name)
        }
    }

    // MARK: Écran Aujourd'hui, validation, aperçu

    func testTodayScreen() throws {
        for item in try cases("todayScreen") {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let subjectList: [Subject] = try (input["subjects"] as? String).map { try subjects($0) } ?? decodeJSON([Subject].self, input["subjects"])
            let screen = TodayScreen(
                today: try XCTUnwrap(input["today"] as? String),
                courses: try decodeJSON([Course].self, input["courses"]),
                revisions: try decodeJSON([Revision].self, input["revisions"]),
                subjects: subjectList,
                pronoteEvents: try decodeJSON([PronoteEvent].self, input["pronoteEvents"])
            )
            checkJSON(Self.json(screen), try XCTUnwrap(item["expected"]), name)
        }
    }

    private static func json(_ screen: TodayScreen) -> [String: Any] {
        var hero: [String: Any] = ["title": screen.hero.title, "revisionId": NSNull()]
        if let revision = screen.hero.revision, let course = screen.hero.course {
            hero["revisionId"] = revision.id
            hero["courseId"] = course.id
            hero["subjectLabel"] = screen.hero.subjectLabel.json
            hero["courseTitle"] = course.title
        }
        return [
            "preparationDate": screen.programme.preparationDate,
            "headerDate": screen.headerDate,
            "tomorrowDate": screen.tomorrowDate,
            "navigation": ["pendingCountToday": screen.pendingCountToday, "overdueCount": screen.overdueCount, "badgeCount": screen.badgeCount],
            "due": screen.programme.due.map(\.id),
            "completed": screen.programme.completed.map(\.id),
            "overdue": screen.programme.overdue.map(\.id),
            "total": screen.total,
            "progress": screen.progress,
            "progressSuffix": screen.progressSuffix,
            "progressMessage": screen.progressMessage,
            "allDoneBanner": screen.allDoneBanner,
            "hero": hero,
            "dueLabel": screen.dueLabel,
            "overdueBanner": screen.overdueBanner.json,
            "emptyProgramme": screen.emptyProgramme,
            "groups": screen.groups.map { group in
                [
                    "subjectId": group.subject.id, "subjectName": group.subject.name, "countLabel": group.countLabel,
                    "rows": group.rows.map { row in
                        [
                            "revisionId": row.revision.id, "courseId": row.course.id, "phase": row.revision.phase.rawValue,
                            "badge": row.badge, "courseLine": row.courseLine, "title": row.title, "done": row.done,
                            "doneLabel": row.doneLabel.json, "canValidate": row.canValidate,
                        ] as [String: Any]
                    },
                ] as [String: Any]
            },
            "tomorrowEvents": screen.tomorrowEvents.map { event in
                [
                    "id": event.id, "timeRange": event.timeRange, "subjectName": event.subjectName, "cancelled": event.cancelled,
                    "trailing": event.trailing.json, "teacher": event.teacher.json, "canAddCourse": event.canAddCourse,
                ] as [String: Any]
            },
        ]
    }

    func testScoreSubmit() throws {
        for item in try cases("scoreSubmit") {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let outcome = try ScoreSubmission.submit(
                revisions: try decodeJSON([Revision].self, input["revisions"]),
                courses: try decodeJSON([Course].self, input["courses"]),
                settings: try decodeJSON(AppSettings.self, input["settings"]),
                revisionId: try XCTUnwrap(input["revisionId"] as? String),
                score: try XCTUnwrap(input["score"] as? Int),
                now: try vectorDate(item["now"])
            )
            let expected = try XCTUnwrap(item["expected"] as? [String: Any])
            if let ids = expected["revisions"] as? [String] {
                // Validation ignorée : le vecteur liste les identifiants, inchangés.
                check(outcome.revisions.map(\.id), ids, name)
            } else {
                check(outcome.revisions, try decodeJSON([Revision].self, expected["revisions"]), name)
            }
            var actual: [String: Any] = [
                "today": outcome.today, "ignored": outcome.ignored, "celebrate": outcome.celebrate,
                "toast": outcome.toast.map { ["title": $0.title, "message": $0.message, "type": $0.kind.rawValue] as [String: Any] }.json,
            ]
            if let preparation = outcome.preparationDate { actual["preparationDate"] = preparation }
            var comparable = expected
            comparable.removeValue(forKey: "revisions")
            checkJSON(actual, comparable, name)
        }
    }

    func testScorePreview() throws {
        for item in try cases("scorePreview") {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let preview = ScorePreview(
                score: try XCTUnwrap(input["score"] as? Int),
                sensitivity: try XCTUnwrap((input["sensitivity"] as? NSNumber)?.doubleValue),
                emergencyThreshold: try XCTUnwrap((input["emergencyThreshold"] as? NSNumber)?.doubleValue)
            )
            let actual: [String: Any] = [
                "tone": preview.tone.rawValue, "title": preview.title, "description": preview.description,
                "scoreBand": preview.band.rawValue, "confettiOnSubmit": preview.confettiOnSubmit,
            ]
            checkJSON(actual, try XCTUnwrap(item["expected"]), name)
        }
    }

    // MARK: Constantes

    func testConstantsMatchWeb() throws {
        let constants = try SharedVectors.object("constants.json", subdirectory: "domain")
        check(Constants.defaultSubjects, try decodeJSON([Subject].self, constants["DEFAULT_SUBJECTS"]))
        check(Constants.defaultIntervals, try decodeJSON([Int].self, constants["DEFAULT_INTERVALS"]))
        check(Constants.defaultSettings, try decodeJSON(AppSettings.self, constants["DEFAULT_SETTINGS"]))
        let phases = try XCTUnwrap(constants["PHASE_CONFIG"] as? [String: [String: String]])
        let today = try XCTUnwrap(constants["TODAY_PHASES"] as? [String: [String: String]])
        for phase in Phase.allCases {
            let config = Constants.phaseConfig(phase)
            check(config.label, phases[phase.rawValue]?["label"])
            check(config.color, phases[phase.rawValue]?["color"])
            check(config.description, phases[phase.rawValue]?["description"])
            let todayPhase = Constants.todayPhase(phase)
            check(todayPhase.label, today[phase.rawValue]?["label"])
            check(todayPhase.hint, today[phase.rawValue]?["hint"])
            check(todayPhase.symbol, today[phase.rawValue]?["symbol"])
        }
        let presets = try XCTUnwrap(constants["SCORE_PRESETS"] as? [[String: Any]])
        check(Constants.scorePresets.map(\.value), presets.compactMap { $0["val"] as? Int })
        check(Constants.scorePresets.map(\.sub), presets.compactMap { $0["sub"] as? String })
        let slider = try XCTUnwrap(constants["SCORE_SLIDER"] as? [String: Int])
        check(slider["step"], Constants.scoreStep)
        check(slider["initial"], Constants.initialScore)
    }
}
