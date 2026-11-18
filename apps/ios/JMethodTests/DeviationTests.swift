import XCTest
@testable import JMethod

/// Comportement corrigé des écarts D1, D2, D7 à D12 et D14 (`native/shared/domain/DEVIATIONS.md`) dans le périmètre des jalons 1 à 3.
final class DeviationTests: XCTestCase {
    private let subjects = Constants.defaultSubjects

    private func course(_ id: String, subject: String = "maths", chapter: String = "Chapitre", number: Int = 1, title: String = "Cours test",
                        date: String = "2026-11-18", createdAt: String = "2026-11-17") -> Course {
        Course(id: id, subjectId: subject, chapter: chapter, courseNumber: number, title: title, date: date, createdAt: createdAt)
    }

    func testD1CourseWithoutRevisionShowsZeroPercent() {
        let detail = CourseDetail(course: course("c"), subjects: subjects, revisions: [])
        XCTAssertEqual(detail.progressLine, "0 / 0 effectuées (0%)")
        XCTAssertEqual(detail.progressPercent, 0)
        XCTAssertNil(detail.averageLine)
    }

    func testD2CreatedLineReadsCreatedAtAndCourseDateIsSeparate() {
        let detail = CourseDetail(course: course("c", date: "2026-11-05", createdAt: "2026-11-17"), subjects: subjects, revisions: [])
        XCTAssertEqual(detail.createdLine, "Créé le 17 novembre 2026")
        XCTAssertEqual(detail.courseDateLine, "Cours du 05/11/2026")
    }

    func testD7SingularDayInAlgorithmMessages() throws {
        let base = course("c", date: "2026-11-17")
        let revisions = try AdaptiveAlgorithm.initialRevisions(for: base, intervals: Constants.defaultIntervals)
        let now = Date(timeIntervalSince1970: 1_794_942_000)
        let exact = try AdaptiveAlgorithm.applyReview(courseRevisions: revisions, revisionId: "c-rev-0", score: 50, settings: Constants.defaultSettings,
                                                      actualCompletionDate: "2026-11-17", schedulingReferenceDate: "2026-11-18", now: now)
        XCTAssertTrue(exact.explanation.message.contains("conservé (1 jour)"), exact.explanation.message)
        XCTAssertFalse(exact.explanation.message.contains("1 jours"))
        let expanded = try AdaptiveAlgorithm.applyReview(courseRevisions: revisions, revisionId: "c-rev-0", score: 70, settings: Constants.defaultSettings,
                                                         actualCompletionDate: "2026-11-17", schedulingReferenceDate: "2026-11-18", now: now)
        XCTAssertTrue(expanded.explanation.message.contains("(1 jour au lieu de 1j)"), expanded.explanation.message)
        XCTAssertEqual(AdaptiveAlgorithm.dayCount(2), "2 jours")
        XCTAssertEqual(CourseCreation.milestonesPhrase(1), "son échéance de révision")
        XCTAssertEqual(CourseCreation.milestonesPhrase(10), "ses 10 échéances de révision")
    }

    /// La liste D7 nomme exactement les cas des vecteurs qui contiennent « (1 jours ».
    func testD7CaseListIsClosed() throws {
        let vectors = try SharedVectors.object("domain-vectors.json", subdirectory: "domain")
        let suites = try XCTUnwrap(vectors["suites"] as? [String: [[String: Any]]])
        var touched = Set<String>()
        for (_, cases) in suites {
            for item in cases {
                let data = try JSONSerialization.data(withJSONObject: item)
                if String(decoding: data, as: UTF8.self).contains("(1 jours"), let name = item["name"] as? String { touched.insert(name) }
            }
        }
        XCTAssertEqual(touched, Deviation.d7Cases)
    }

    /// D14 : date de la prochaine révision en français, relative au jour réel de la validation.
    func testD14NextReviewDateInFrench() throws {
        XCTAssertEqual(AdaptiveAlgorithm.nextReviewPhrase("2026-11-19", today: "2026-11-18"), "demain")
        XCTAssertEqual(AdaptiveAlgorithm.nextReviewPhrase("2026-11-20", today: "2026-11-18"), "après-demain")
        XCTAssertEqual(AdaptiveAlgorithm.nextReviewPhrase("2026-11-27", today: "2026-11-18"), "le vendredi 27 novembre")
        XCTAssertEqual(AdaptiveAlgorithm.nextReviewPhrase("2027-01-08", today: "2026-12-30"), "le vendredi 8 janvier 2027")
        XCTAssertEqual(AdaptiveAlgorithm.nextReviewPhrase("2027-01-01", today: "2026-12-31"), "demain")

        // Validation le soir du mercredi 18 novembre (prépare le 19) : J0 à 50 → 20 novembre ; J4 à 100 → 19 + 4 = lundi 23.
        let base = course("c", date: "2026-11-18")
        let revisions = try AdaptiveAlgorithm.initialRevisions(for: base, intervals: Constants.defaultIntervals)
        let now = Date(timeIntervalSince1970: 1_795_028_400)
        let evening = try AdaptiveAlgorithm.applyReview(courseRevisions: revisions, revisionId: "c-rev-0", score: 50, settings: Constants.defaultSettings,
                                                        actualCompletionDate: "2026-11-18", schedulingReferenceDate: "2026-11-19", now: now)
        XCTAssertEqual(evening.explanation.nextDate, "2026-11-20")
        XCTAssertTrue(evening.explanation.message.hasSuffix("Prochaine révision après-demain."), evening.explanation.message)
        let later = try AdaptiveAlgorithm.applyReview(courseRevisions: evening.updatedRevisions, revisionId: "c-rev-3", score: 100,
                                                      settings: Constants.defaultSettings, actualCompletionDate: "2026-11-18",
                                                      schedulingReferenceDate: "2026-11-19", now: now)
        XCTAssertTrue(later.explanation.message.hasSuffix("Prochaine révision le lundi 23 novembre."), later.explanation.message)
        XCTAssertNil(later.explanation.message.range(of: #"\d{4}-\d{2}-\d{2}"#, options: .regularExpression))
    }

    /// La liste D14 nomme exactement les cas des vecteurs dont un message contient une date ISO de prochaine révision.
    func testD14CaseListIsClosed() throws {
        let vectors = try SharedVectors.object("domain-vectors.json", subdirectory: "domain")
        let suites = try XCTUnwrap(vectors["suites"] as? [String: [[String: Any]]])
        var touched = Set<String>()
        for (_, cases) in suites {
            for item in cases {
                let text = String(decoding: try JSONSerialization.data(withJSONObject: item), as: UTF8.self)
                if text.contains(Deviation.isoNextReview), let name = item["name"] as? String { touched.insert(name) }
            }
        }
        XCTAssertEqual(touched, Deviation.d14Cases)
    }

    func testD8ToastCountsGeneratedMilestones() throws {
        var settings = Constants.defaultSettings
        settings.intervals = [0, 2, 9]
        let draft = CourseDraft(subjectId: "anglais", chapter: "Vocabulary", title: "Cours test", date: "2026-12-30")
        let outcome = try XCTUnwrap(try CourseCreation.create(draft, courses: [], revisions: [], settings: settings, now: Date(timeIntervalSince1970: 1_794_936_600)))
        XCTAssertEqual(outcome.newRevisions.count, 3)
        XCTAssertEqual(outcome.toast.message, "C1 « Cours test » ajouté avec ses 3 échéances de révision.")
    }

    func testD9SearchIsTrimmedAndAccentInsensitive() {
        let courses = [
            course("thales", chapter: "Géométrie", number: 1, title: "Cours de test Thalès"),
            course("energy", subject: "physique-chimie", chapter: "Énergie", number: 1, title: "Cinétique"),
            course("c10", chapter: "Arithmétique", number: 10, title: "PGCD"),
            course("c2", chapter: "Arithmétique", number: 2, title: "Nombres premiers"),
        ]
        func ids(_ search: String) -> [String] {
            CoursesList(courses: courses, revisions: [], subjects: subjects, search: search).groups.flatMap { $0.chapters.flatMap { $0.cards.map(\.course.id) } }
        }
        XCTAssertEqual(ids("thalès "), ["thales"], "requête nettoyée")
        XCTAssertEqual(ids("thales"), ["thales"], "sans accent")
        XCTAssertEqual(ids("energie"), ["energy"])
        XCTAssertEqual(ids("ÉNERGIE"), ["energy"])
        XCTAssertEqual(ids("  GRAM"), [])
        XCTAssertEqual(ids("c1"), ["thales", "c10", "energy"], "« c1 » reste une sous-chaîne : C1 et C10")
        XCTAssertEqual(ids("   ").count, 4, "une requête vide après nettoyage ne filtre pas")
    }

    func testD10NativePhaseLabelsInPlanningAndDetail() throws {
        let base = course("c", date: "2026-03-23")
        var revisions = try AdaptiveAlgorithm.initialRevisions(for: base, intervals: Constants.defaultIntervals)
        revisions[0].status = .completed
        revisions[0].completedDate = "2026-03-23"
        revisions[0].score = 80
        let screen = PlanningScreen(mode: .week, date: "2026-03-23", today: "2026-03-23", courses: [base], revisions: revisions, subjects: subjects, pronoteEvents: [])
        let tiles = screen.days.flatMap(\.tiles)
        XCTAssertEqual(tiles.map(\.phaseLine), ["Découvrir · C1", "S’entraîner · C1", "S’entraîner · C1", "S’entraîner · C1"])
        XCTAssertEqual(tiles.first?.accessibilityLabel, "Mathématiques, J0, Découvrir, C1 Cours test, terminée")
        let detail = try XCTUnwrap(RevisionDetail(revisionId: "c-rev-4", revisions: revisions, courses: [base], subjects: subjects))
        XCTAssertEqual(detail.phaseLabel, "Se tester")
        XCTAssertEqual(detail.history.map(\.label), ["J0 · Découvrir"])
        for label in ["Encodage", "Consolidation", "Récupération"] {
            XCTAssertFalse(tiles.contains { $0.phaseLine.contains(label) || $0.accessibilityLabel.contains(label) })
        }
    }

    func testD11OnlyFirstLetterIsCapitalized() {
        let day = PlanningScreen(mode: .day, date: "2026-03-30", today: "2026-03-30", courses: [], revisions: [], subjects: subjects, pronoteEvents: [])
        XCTAssertEqual(day.period, "lundi 30 mars 2026")
        XCTAssertEqual(day.period.capitalizedFirst, "Lundi 30 mars 2026")
        XCTAssertEqual(day.days[0].heading.capitalizedFirst, "Lundi 30 mars")
        let week = PlanningScreen(mode: .week, date: "2026-12-31", today: "2026-03-30", courses: [], revisions: [], subjects: subjects, pronoteEvents: [])
        XCTAssertEqual(week.period.capitalizedFirst, "28 déc. – 3 janv. 2027")
        XCTAssertEqual("".capitalizedFirst, "")
    }

    func testD12CompletedWithoutScoreShowsDash() {
        let base = course("c")
        let revision = Revision(id: "c-rev-0", courseId: "c", stepIndex: 0, stepLabel: "J0", scheduledDate: "2026-11-18", completedDate: "2026-11-18",
                                score: nil, status: .completed, phase: .encodage, adjustedIntervalDays: 0)
        let detail = CourseDetail(course: base, subjects: subjects, revisions: [revision])
        XCTAssertEqual(detail.timeline.first?.right, "—/100")
        XCTAssertNil(detail.averageLine)
    }
}
