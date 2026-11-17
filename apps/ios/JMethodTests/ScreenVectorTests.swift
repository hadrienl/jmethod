import XCTest
@testable import JMethod

/// Écarts volontaires de `native/shared/domain/DEVIATIONS.md` (liste fermée, appliquée aussi par Android).
/// Un cas de vecteur touché est nommé ici avec sa référence ; le comportement corrigé a son test natif (`DeviationTests`).
enum Deviation {
    /// D7 : accord du pluriel, « (1 jour » au lieu de « (1 jours » dans les messages de l'algorithme.
    /// Les autres champs de ces cas restent comparés à l'identique.
    static let d7Cases: Set<String> = [
        "completed_later_step_is_overwritten", "default_dates_from_clock_after_midnight", "j0_default_score_100", "j0_default_score_30",
        "j0_default_score_50", "j0_default_score_70",
        "only_actual_date_given_reference_defaults_to_it", "reference_without_actual_date", "settings_intervals_shorter_than_schedule",
        "threshold_0_score_0_is_not_emergency", "unsorted_input_is_sorted_by_step_index", "validate_emergency_session",
        // Enchaînements (toutes leurs étapes).
        "all_steps_at_50", "february_threshold_50_sensitivity_0.1", "mixed_scores_emergencies_dst_autumn", "year_end_to_spring_sensitivity_1",
        // Validation (toast).
        "last_due_triggers_celebration", "validate_after_midnight_uses_new_day", "validate_j0_at_50_keeps_others_pending",
    ]

    static func d7(_ message: String) -> String { message.replacingOccurrences(of: "(1 jours", with: "(1 jour") }

    /// Texte attendu après D7, ou nil si le cas contient « 1 jours » sans figurer dans la liste (liste à mettre à jour).
    static func expectedMessage(_ message: String, caseName: String) -> String? {
        guard message.contains("(1 jours") else { return message }
        let base = caseName.components(separatedBy: " étape ").first ?? caseName
        return d7Cases.contains(base) ? d7(message) : nil
    }

    /// D10 : Découvrir / S'entraîner / Se tester au lieu de Encodage / Consolidation / Récupération (Planning et détail de séance).
    /// Appliqué champ par champ (`phaseLine`, `accessibilityLabel`, `phaseLabel`, libellés de l'historique) à tous les cas des suites
    /// `planning` et `revisionDetail` : le reste de chaque cas est comparé à l'identique.
    static let d10Labels: [(web: String, native: String)] = [("Encodage", "Découvrir"), ("Consolidation", "S’entraîner"), ("Récupération", "Se tester")]

    static func d10PhaseLine(_ value: String) -> String {
        for label in d10Labels where value.hasPrefix("\(label.web) · ") { return label.native + value.dropFirst(label.web.count) }
        return value
    }

    static func d10Accessibility(_ value: String) -> String {
        var result = value
        for label in d10Labels { result = result.replacingOccurrences(of: ", \(label.web), C", with: ", \(label.native), C") }
        return result
    }

    static func d10Label(_ value: String) -> String { d10Labels.first { $0.web == value }?.native ?? value }

    static func d10HistoryLabel(_ value: String) -> String {
        for label in d10Labels where value.hasSuffix(" · \(label.web)") { return value.dropLast(label.web.count) + label.native }
        return value
    }

    /// D9 : recherche nettoyée et insensible aux accents. Ces cas figent le défaut du web ; résultat corrigé testé nativement.
    static let d9SkippedCoursesList: Set<String> = ["search_trailing_space_no_match", "search_without_accent_does_not_match"]

    /// D1 : 0 % au lieu de « NaN% » ; seuls `progressLine` et `progressPercent` de ce cas sont remplacés par le comportement corrigé.
    static let d1CourseDetail = "no_revisions_gives_nan_percent"

    /// D8 (et D7) : nombre réel d'échéances dans le toast de création ; seul le message de ce cas diffère.
    static let d8CourseCreate = "custom_intervals_and_empty_description"
}

/// Rejoue les suites des jalons 2 et 3 : `planning`, `planningNavigate`, `revisionDetail`, `coursesList`, `courseDetail`,
/// `courseCreate`, `courseCreatePrefill`, `courseDelete`.
final class ScreenVectorTests: XCTestCase {
    private var suites: [String: Any] = [:]
    private var fixtures: [String: Any] = [:]
    private var subjectSets: [String: [Subject]] = [:]
    private var problems: [String] = []

    override func setUpWithError() throws {
        let vectors = try SharedVectors.object("domain-vectors.json", subdirectory: "domain")
        suites = try XCTUnwrap(vectors["suites"] as? [String: Any])
        fixtures = try XCTUnwrap(vectors["fixtures"] as? [String: Any])
        let sets = try XCTUnwrap(vectors["subjectSets"] as? [String: Any])
        for (name, value) in sets { subjectSets[name] = try decodeJSON([Subject].self, value) }
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

    private func checkJSON(_ actual: Any, _ expected: Any, _ label: String) {
        if let mismatch = jsonMismatch(actual, expected) { problems.append("\(label) : \(mismatch)") }
    }

    private func cases(_ suite: String) throws -> [[String: Any]] {
        let list = try XCTUnwrap(suites[suite] as? [[String: Any]], "suite \(suite) absente")
        XCTAssertFalse(list.isEmpty, "suite \(suite) vide")
        return list
    }

    private struct Fixture {
        var courses: [Course]
        var revisions: [Revision]
        var subjects: [Subject]
        var pronoteEvents: [PronoteEvent]
    }

    private func fixture(_ name: Any?) throws -> Fixture {
        let data = try XCTUnwrap(fixtures[try XCTUnwrap(name as? String)] as? [String: Any])
        return Fixture(
            courses: try decodeJSON([Course].self, data["courses"]),
            revisions: try decodeJSON([Revision].self, data["revisions"]),
            subjects: try decodeJSON([Subject].self, data["subjects"]),
            pronoteEvents: try data["pronoteEvents"].map { try decodeJSON([PronoteEvent].self, $0) } ?? []
        )
    }

    // MARK: Planning

    func testPlanning() throws {
        var count = 0
        for item in try cases("planning") {
            let name = item["name"] as? String ?? ""
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let data = try fixture(item["fixture"])
            let mode = try XCTUnwrap(PlanningScreen.Mode(rawValue: try XCTUnwrap(input["mode"] as? String)))
            let screen = PlanningScreen(
                mode: mode, date: try XCTUnwrap(input["date"] as? String),
                subjectId: input["subjectId"] as? String,
                phase: (input["phase"] as? String).flatMap(Phase.init(rawValue:)),
                today: SchoolDay.today(now: try vectorDate(item["now"])),
                courses: data.courses, revisions: data.revisions, subjects: data.subjects, pronoteEvents: data.pronoteEvents
            )
            checkJSON(Self.json(screen), Self.applyingD10(planning: try XCTUnwrap(item["expected"] as? [String: Any])), name)
            count += 1
        }
        check(count, 14, "planning")
    }

    private static func json(_ screen: PlanningScreen) -> [String: Any] {
        [
            "period": screen.period,
            "days": screen.days.map { day in
                var out: [String: Any] = ["date": day.date, "heading": day.heading, "countLabel": day.countLabel, "isToday": day.isToday]
                if let outside = day.outsideMonth { out["outsideMonth"] = outside }
                if let summary = day.timetableSummary {
                    out["timetableSummary"] = summary
                    out["events"] = day.events.map { ["id": $0.id, "label": $0.label, "subjectId": $0.subjectId.json] as [String: Any] }
                }
                if let empty = day.empty {
                    out["empty"] = empty
                } else {
                    out["tiles"] = day.tiles.map { tile in
                        [
                            "revisionId": tile.revision.id, "subjectName": tile.subject.name, "stepLabel": tile.revision.stepLabel,
                            "phaseLine": tile.phaseLine, "title": tile.course.title, "chapter": tile.course.chapter,
                            "done": tile.done, "footer": tile.footer, "accessibilityLabel": tile.accessibilityLabel,
                        ] as [String: Any]
                    }
                }
                return out
            },
        ]
    }

    private static func applyingD10(planning expected: [String: Any]) -> [String: Any] {
        var result = expected
        result["days"] = (expected["days"] as? [[String: Any]])?.map { day in
            var day = day
            day["tiles"] = (day["tiles"] as? [[String: Any]])?.map { tile in
                var tile = tile
                tile["phaseLine"] = Deviation.d10PhaseLine(tile["phaseLine"] as? String ?? "")
                tile["accessibilityLabel"] = Deviation.d10Accessibility(tile["accessibilityLabel"] as? String ?? "")
                return tile
            }
            return day
        }
        return result
    }

    func testPlanningNavigate() throws {
        var count = 0
        for item in try cases("planningNavigate") {
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let result = PlanningScreen.navigate(
                mode: try XCTUnwrap(PlanningScreen.Mode(rawValue: try XCTUnwrap(input["mode"] as? String))),
                date: try XCTUnwrap(input["date"] as? String),
                direction: try XCTUnwrap(input["direction"] as? Int)
            )
            check(result, item["expected"] as? String, item["name"] as? String ?? "")
            count += 1
        }
        check(count, 10, "planningNavigate")
    }

    func testRevisionDetail() throws {
        var count = 0
        for item in try cases("revisionDetail") {
            let name = item["name"] as? String ?? ""
            let data = try fixture(item["fixture"])
            let id = try XCTUnwrap((item["input"] as? [String: Any])?["revisionId"] as? String)
            var expected = try XCTUnwrap(item["expected"] as? [String: Any])
            let detail = RevisionDetail(revisionId: id, revisions: data.revisions, courses: data.courses, subjects: data.subjects)
            count += 1
            guard let detail else {
                checkJSON(["opens": false], expected, name)
                continue
            }
            expected["phaseLabel"] = Deviation.d10Label(expected["phaseLabel"] as? String ?? "")
            expected["history"] = (expected["history"] as? [[String: Any]])?.map { row in
                var row = row
                row["label"] = Deviation.d10HistoryLabel(row["label"] as? String ?? "")
                return row
            }
            let actual: [String: Any] = [
                "opens": true, "subjectName": detail.subject.name, "title": detail.title, "chapterLine": detail.chapterLine,
                "stepLabel": detail.revision.stepLabel, "phaseLabel": detail.phaseLabel, "plannedLine": detail.plannedLine,
                "description": detail.description.json, "historyTitle": detail.historyTitle,
                "history": detail.history.map { ["revisionId": $0.id, "label": $0.label, "detail": $0.detail] },
                "historyEmpty": detail.historyEmpty.json, "completedLine": detail.completedLine.json, "canValidate": detail.canValidate,
            ]
            checkJSON(actual, expected, name)
        }
        check(count, 7, "revisionDetail")
    }

    // MARK: Mes cours

    func testCoursesList() throws {
        var count = 0, skipped: [String] = []
        for item in try cases("coursesList") {
            let name = item["name"] as? String ?? ""
            if Deviation.d9SkippedCoursesList.contains(name) {
                skipped.append(name) // D9, voir DeviationTests.testD9SearchIsTrimmedAndAccentInsensitive
                continue
            }
            let data = try fixture(item["fixture"])
            let input = item["input"] as? [String: Any] ?? [:]
            let list = CoursesList(courses: data.courses, revisions: data.revisions, subjects: data.subjects,
                                   search: input["search"] as? String ?? "", subjectId: input["subjectId"] as? String)
            let actual: [String: Any] = [
                "header": list.header, "empty": list.empty,
                "groups": list.groups.map { group in
                    [
                        "subjectId": group.subject.id, "countLabel": group.countLabel,
                        "chapters": group.chapters.map { chapter in
                            [
                                "chapter": chapter.chapter,
                                "courses": chapter.cards.map { card in
                                    [
                                        "courseId": card.course.id, "badge": card.badge, "dateLine": card.dateLine,
                                        "lastScoreLine": card.lastScoreLine.json, "counter": card.counter, "progress": card.progress,
                                        "next": card.next.map { ["label": $0.label, "date": $0.date] as [String: Any] }.json,
                                    ] as [String: Any]
                                },
                            ] as [String: Any]
                        },
                    ] as [String: Any]
                },
            ]
            checkJSON(actual, try XCTUnwrap(item["expected"]), name)
            count += 1
        }
        check(skipped.sorted(), Deviation.d9SkippedCoursesList.sorted(), "coursesList cas D9")
        check(count, 9, "coursesList")
    }

    func testCourseDetail() throws {
        var count = 0
        for item in try cases("courseDetail") {
            let name = item["name"] as? String ?? ""
            let data = try fixture(item["fixture"])
            let id = try XCTUnwrap((item["input"] as? [String: Any])?["courseId"] as? String)
            let course = try XCTUnwrap(data.courses.first { $0.id == id })
            let detail = CourseDetail(course: course, subjects: data.subjects, revisions: data.revisions.filter { $0.courseId == id })
            var expected = try XCTUnwrap(item["expected"] as? [String: Any])
            if name == Deviation.d1CourseDetail {
                // D1 : le web affiche « NaN% » ; natif : 0 %.
                check(expected["progressPercent"] as? String, "NaN", "\(name) (vecteur D1)")
                expected["progressLine"] = "0 / 0 effectuées (0%)"
                expected["progressPercent"] = 0
            }
            let actual: [String: Any] = [
                "subjectLabel": detail.subjectLabel, "createdLine": detail.createdLine, "averageLine": detail.averageLine.json,
                "notes": detail.notes.json, "progressLine": detail.progressLine, "progressPercent": detail.progressPercent,
                "timeline": detail.timeline.map { row in
                    [
                        "revisionId": row.id, "stepLabel": row.revision.stepLabel, "phaseLabel": row.phaseLabel,
                        "emergencyBadge": row.emergencyBadge, "state": row.state.rawValue, "plannedLine": row.plannedLine,
                        "doneLine": row.doneLine.json, "right": row.right,
                    ] as [String: Any]
                },
                "deleteConfirm": detail.deleteConfirm,
            ]
            checkJSON(actual, expected, name)
            count += 1
        }
        check(count, 5, "courseDetail")
    }

    func testCourseCreate() throws {
        var count = 0
        for item in try cases("courseCreate") {
            let name = item["name"] as? String ?? ""
            let data = try fixture(item["fixture"])
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let settings = try decodeJSON(AppSettings.self, input["settings"])
            let form = try XCTUnwrap(input["form"] as? [String: String])
            let draft = CourseDraft(subjectId: form["subjectId"], chapter: form["chapter"] ?? "", title: form["title"] ?? "",
                                    date: try XCTUnwrap(form["date"]), description: form["description"] ?? "")
            let number = CourseCreation.courseNumber(subjectId: draft.subjectId, chapter: draft.chapter, courses: data.courses)
            var actual: [String: Any] = [
                "courseNumber": number,
                "submitLabel": "Créer le cours C\(number)",
                "chapterSuggestions": CourseCreation.chapterSuggestions(subjectId: draft.subjectId, chapter: draft.chapter, courses: data.courses),
            ]
            let outcome = try CourseCreation.create(draft, courses: data.courses, revisions: data.revisions, settings: settings, now: try vectorDate(item["now"]))
            var expected = try XCTUnwrap(item["expected"] as? [String: Any])
            actual["created"] = outcome != nil
            if let outcome {
                actual["course"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(outcome.course))
                actual["newRevisions"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(outcome.newRevisions))
                actual["courseOrder"] = outcome.courses.map(\.id)
                actual["revisionOrder"] = outcome.revisions.map(\.id)
                actual["toast"] = ["title": outcome.toast.title, "message": outcome.toast.message, "type": "success"]
                if name == Deviation.d8CourseCreate, var toast = expected["toast"] as? [String: Any] {
                    // D8 : le web annonce toujours « 10 échéances » ; ici trois intervalles donnent trois échéances.
                    check(toast["message"] as? String, "C1 « Cours de test » ajouté avec ses 10 échéances de révision.", "\(name) (vecteur D8)")
                    toast["message"] = "C1 « Cours de test » ajouté avec ses 3 échéances de révision."
                    expected["toast"] = toast
                }
            }
            checkJSON(actual, expected, name)
            count += 1
        }
        check(count, 9, "courseCreate")
    }

    func testCourseCreatePrefill() throws {
        var count = 0
        for item in try cases("courseCreatePrefill") {
            let input = try XCTUnwrap(item["input"] as? [String: Any])
            let event = try decodeJSON(PronoteEvent.self, input["event"])
            let subjects = try XCTUnwrap(subjectSets[try XCTUnwrap(input["subjects"] as? String)])
            let prefill = CourseCreation.prefill(event: event, subjects: subjects)
            checkJSON(["subjectId": prefill.subjectId.json, "date": prefill.date, "title": prefill.title],
                      try XCTUnwrap(item["expected"]), item["name"] as? String ?? "")
            count += 1
        }
        check(count, 4, "courseCreatePrefill")
    }

    func testCourseDelete() throws {
        var count = 0
        for item in try cases("courseDelete") {
            let data = try fixture(item["fixture"])
            let id = try XCTUnwrap((item["input"] as? [String: Any])?["courseId"] as? String)
            let result = CourseDeletion.apply(courseId: id, courses: data.courses, revisions: data.revisions)
            let actual: [String: Any] = [
                "courses": result.courses.map(\.id), "revisions": result.revisions.map(\.id),
                "toast": ["title": CourseDeletion.toastTitle, "message": CourseDeletion.toastMessage, "type": "info"],
            ]
            checkJSON(actual, try XCTUnwrap(item["expected"]), item["name"] as? String ?? "")
            count += 1
        }
        check(count, 3, "courseDelete")
    }
}
