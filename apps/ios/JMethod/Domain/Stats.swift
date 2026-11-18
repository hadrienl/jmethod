import Foundation

/// Mes progrès (`StatsView.tsx`, suite `stats`), avec les écarts de `DEVIATIONS.md` :
/// D3 série de régularité réelle, D4 « — » sans aucune note, D5 seules les séances terminées notées comptent,
/// D6 `score: null` = pas de note (déjà vrai au décodage), D7 accords.
struct StatsScreen: Equatable {
    enum Tone: String { case good, weak }

    struct SubjectRow: Equatable, Identifiable {
        var subject: Subject
        var coursesCount: Int
        var totalRevs: Int
        var completedRevs: Int
        var rate: Int
        var avgScore: Int?
        /// `(c cours • t/n J)`
        var line: String
        var averageTone: Tone?
        var id: String { subject.id }

        /// Libellé VoiceOver de la barre de la matière.
        var accessibilityLabel: String {
            var text = "\(subject.name), \(coursesCount) cours, \(completedRevs) séance\(completedRevs > 1 ? "s" : "") faite\(completedRevs > 1 ? "s" : "") sur \(totalRevs), \(rate) %"
            if let avgScore { text += ", moyenne \(avgScore) sur 100" }
            return text
        }
    }

    var completionRate: Int
    var completionLine: String
    /// nil sans aucune séance terminée notée (D4).
    var overallAvgScore: Int?
    var overallAvgLabel: String
    var balanceLabel: String
    var streakDays: Int
    var streakLabel: String
    var streakLine: String
    var coursesCount: Int
    var subjectsLine: String
    var weakSubjects: [SubjectRow]
    var subjects: [SubjectRow]

    init(courses: [Course], revisions: [Revision], subjects: [Subject], today: String) {
        let courseMap = Dictionary(courses.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let completed = revisions.filter { $0.status == .completed }.count
        let overall = Self.average(revisions)
        let rows: [SubjectRow] = subjects.compactMap { subject in
            let subjectCourses = courses.filter { $0.subjectId == subject.id }.count
            guard subjectCourses > 0 else { return nil }
            let subjectRevisions = revisions.filter { courseMap[$0.courseId]?.subjectId == subject.id }
            let done = subjectRevisions.filter { $0.status == .completed }.count
            let average = Self.average(subjectRevisions)
            return SubjectRow(
                subject: subject,
                coursesCount: subjectCourses,
                totalRevs: subjectRevisions.count,
                completedRevs: done,
                rate: subjectRevisions.isEmpty ? 0 : jsRound(Double(done) / Double(subjectRevisions.count) * 100),
                avgScore: average,
                line: "(\(subjectCourses) cours • \(done)/\(subjectRevisions.count) J)",
                averageTone: average.map { $0 >= 50 ? .good : .weak }
            )
        }
        let streak = Self.streak(revisions: revisions, today: today)
        completionRate = revisions.isEmpty ? 0 : jsRound(Double(completed) / Double(revisions.count) * 100)
        completionLine = "\(completed) sur \(revisions.count) faites"
        overallAvgScore = overall
        overallAvgLabel = overall.map(String.init) ?? "—"
        balanceLabel = switch overall {
        case nil: "Pas encore de note"
        case let value? where value >= 50: "Au-dessus de l’équilibre"
        default: "Sous l’équilibre"
        }
        streakDays = streak
        streakLabel = "\(streak) j"
        streakLine = streak == 0 ? "Valide une séance pour commencer"
            : "\(streak > 1 ? "Jours consécutifs" : "Jour consécutif") avec une séance"
        coursesCount = courses.count
        subjectsLine = "Sur \(rows.count) matière\(rows.count > 1 ? "s" : "")"
        weakSubjects = rows.filter { ($0.avgScore ?? 100) < 50 }
        self.subjects = rows
    }

    /// D5 : moyenne arrondie des séances terminées notées, nil s'il n'y en a aucune.
    static func average(_ revisions: [Revision]) -> Int? {
        let scores = revisions.filter { $0.status == .completed }.compactMap(\.score)
        guard !scores.isEmpty else { return nil }
        return jsRound(Double(scores.reduce(0, +)) / Double(scores.count))
    }

    /// D3 : jours consécutifs ayant au moins une séance validée (`completedDate`), en remontant depuis aujourd'hui,
    /// ou depuis hier si rien n'est encore validé aujourd'hui.
    static func streak(revisions: [Revision], today: String) -> Int {
        let days = Set(revisions.filter { $0.status == .completed }.compactMap(\.completedDate))
        guard var day = days.contains(today) ? today : SchoolDay.adding(-1, to: today) else { return 0 }
        var count = 0
        while days.contains(day), let previous = SchoolDay.adding(-1, to: day) {
            count += 1
            day = previous
        }
        return count
    }
}
