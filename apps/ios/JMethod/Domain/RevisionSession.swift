import Foundation

/// Réviser sans quitter Aujourd'hui (`native/shared/notebook-to-course.md` §8) : fiche de séance, enchaînement des
/// séances du soir, accès au cours depuis les créneaux de demain, documents proches de la même matière.
enum RevisionSession {
    /// Description repliée au-delà de 6 lignes (« Afficher plus »).
    static let descriptionLines = 6
    /// Documents proches : 10 jours au plus avant ou après la date du cours, 5 affichés avant « Afficher tout ».
    static let nearbyWindowDays = 10
    static let nearbyVisible = 5

    static func hasDocuments(_ course: Course?) -> Bool { !(course?.documents ?? []).isEmpty }

    /// Document principal : le premier de la liste (action « Revoir le cours »).
    static func principalDocument(_ course: Course?) -> CourseDocument? { course?.documents?.first }

    // MARK: Enchaîner (§8.2)

    /// Séance suivante après une validation : la première séance encore à faire du programme du soir, dans l'ordre
    /// affiché par Aujourd'hui (matières puis séances), hors `excluding`.
    static func nextDue(revisions: [Revision], courses: [Course], subjects: [Subject], today: String, excluding: String?) -> Revision? {
        let screen = TodayScreen(today: today, courses: courses, revisions: revisions, subjects: subjects, pronoteEvents: [])
        return screen.groups.lazy.flatMap(\.rows).first { $0.canValidate && $0.revision.id != excluding }?.revision
    }

    /// « Séance suivante · <matière> — <titre> ».
    static func nextLabel(_ revision: Revision, courses: [Course], subjects: [Subject]) -> String? {
        guard let course = courses.first(where: { $0.id == revision.courseId }) else { return nil }
        let subject = subjects.first { $0.id == course.subjectId }?.name ?? "Matière"
        return "Séance suivante · \(subject) — \(course.title)"
    }

    /// Prochaine séance à faire d'un cours (§8.4) : la première non faite dans l'ordre des J.
    static func nextOfCourse(_ courseId: String, revisions: [Revision]) -> Revision? {
        let pending = revisions.enumerated().filter { $0.element.applies(to: courseId) && $0.element.status != .completed }
        let programme = pending.contains { $0.element.programmeId != nil }
        let knownDays = pending.allSatisfy { $0.element.nominalOffsetDays != nil }
        return pending.min { lhs, rhs in
            if programme {
                if knownDays, let a = lhs.element.nominalOffsetDays, let b = rhs.element.nominalOffsetDays, a != b { return a < b }
                if lhs.element.scheduledDate != rhs.element.scheduledDate { return lhs.element.scheduledDate < rhs.element.scheduledDate }
            }
            if lhs.element.stepIndex != rhs.element.stepIndex { return lhs.element.stepIndex < rhs.element.stepIndex }
            return lhs.offset < rhs.offset
        }?.element
    }

    /// Retard d'une séance à faire par rapport au jour préparé ce soir (lendemain de `today`).
    static func lateLabel(_ revision: Revision, today: String) -> String? {
        guard revision.status != .completed, let preparation = SchoolDay.adding(1, to: today),
              let days = SchoolDay.daysBetween(revision.scheduledDate, preparation), days > 0 else { return nil }
        return "En retard de \(days) jour\(days > 1 ? "s" : "")"
    }

    // MARK: Aujourd'hui (§8.3)

    /// Créneau de demain déjà importé : cours de la même matière J-Method et du même jour, de préférence issu du cahier.
    static func courseForSlot(_ event: PronoteEvent, courses: [Course], subjects: [Subject]) -> Course? {
        guard !event.isCancelled, let subject = SubjectMatcher.match(event.subjectName, subjects: subjects) else { return nil }
        let candidates = courses.filter { $0.subjectId == subject.id && $0.date == event.date }
        return candidates.first { $0.source != nil } ?? candidates.first
    }

    // MARK: Dans le cahier de textes (§8.1, point 3)

    /// « Séance du 18 novembre · Pronote » (« 1er » le premier du mois).
    static func sessionLine(_ date: String) -> String {
        "\(sessionDate(date)) · Pronote"
    }

    /// « Séance du 5 novembre ».
    static func sessionDate(_ date: String) -> String {
        guard let parts = SchoolDay.components(date), SchoolDay.isValid(date) else { return "Séance" }
        let day = parts.day == 1 ? "1er" : String(parts.day)
        return "Séance du \(day) \(FrenchDate.monthsLong[parts.month - 1])"
    }

    /// Séance d'origine d'un cours dans l'instantané lu : clé stable `pn1:`, sinon correspondance par la `source`.
    static func notebookSession(for course: Course, in snapshot: NotebookSnapshot?) -> NotebookSession? {
        guard let source = course.source, source.kind == CourseSource.notebookKind, let snapshot else { return nil }
        return snapshot.sessions.first { NotebookCourses.sessionKey($0) == source.sessionKey }
            ?? snapshot.sessions.first { NotebookCourses.matchingCourse($0, courses: [course]) != nil }
    }

    struct NotebookPart: Equatable {
        var sourceLine: String
        /// Contenus de la séance d'origine, pièces jointes déjà importées retirées ; nil hors de la fenêtre lue.
        var contents: [NotebookContent]?
    }

    static func documentSourceKey(_ document: CourseDocument, course: Course) -> String? {
        if let key = document.sourceSessionKey { return key }
        let sources = course.sources ?? course.source.map { [$0] } ?? []
        let keys = Set(sources.map(\.sessionKey))
        return keys.count == 1 ? keys.first : nil
    }
    static func documentSourceLine(_ document: CourseDocument) -> String? {
        guard let key = document.sourceSessionKey, key.hasPrefix("pn1:"), let day = key.dropFirst(4).split(separator: "|").first,
              SchoolDay.isValid(String(day)) else { return nil }
        return sessionLine(String(day))
    }
    static func hasDocument(from sessionKey: String, name: String, course: Course) -> Bool {
        (course.documents ?? []).contains { $0.name == name && documentSourceKey($0, course: course) == sessionKey }
    }
    static func notebookDocumentCandidates(_ document: CourseDocument, course: Course, snapshot: NotebookSnapshot) -> [(NotebookSession, NotebookAttachment)] {
        guard let key = documentSourceKey(document, course: course) else { return [] }
        let keys = Set([key])
        return snapshot.sessions.filter { keys.contains(NotebookCourses.sessionKey($0)) }.flatMap { session in
            NotebookCourses.fileAttachments(of: session).filter { $0.name == document.name }.map { (session, $0) }
        }
    }

    /// nil pour un cours saisi à la main.
    static func notebookPart(for course: Course, snapshot: NotebookSnapshot?) -> NotebookPart? {
        let sources = course.sources ?? course.source.map { [$0] } ?? []
        guard !sources.isEmpty else { return nil }
        let sessions = sources.compactMap { source -> NotebookSession? in
            var leaf = course; leaf.source = source
            return notebookSession(for: leaf, in: snapshot)
        }
        let contents: [NotebookContent]? = sessions.isEmpty ? nil : sessions.flatMap { session in session.contents.map { content in
            var copy = content
            copy.attachments = content.attachments.filter { $0.kind != .file || !hasDocument(from: NotebookCourses.sessionKey(session), name: $0.name, course: course) }
            return copy
        } }
        return NotebookPart(sourceLine: sources.map { sessionLine($0.date) }.joined(separator: " · "), contents: contents)
    }

    // MARK: Documents proches (§8.5)

    struct NearbyDocument: Equatable, Identifiable {
        enum Origin: Equatable {
            /// Document enregistré dans un autre cours de la même matière.
            case course(courseId: String, document: CourseDocument)
            /// Pièce jointe du cahier lu, téléchargée à l'ouverture.
            case notebook(NotebookAttachment)
        }

        var origin: Origin
        var name: String
        var extractedTitle: String?
        var sourceSessionKey: String? = nil
        /// Date de la séance d'origine (`yyyy-MM-dd`).
        var date: String
        var dateLine: String { RevisionSession.sessionDate(date) }
        var id: String {
            switch origin {
            case .course(let courseId, let document): "course:\(courseId):\(document.id)"
            case .notebook(let attachment): "notebook:\(attachment.id)"
            }
        }
    }

    /// Fichiers joints à d'autres séances de la même matière normalisée, à 10 jours au plus de la date du cours,
    /// hors documents déjà joints à ce cours. Documents des autres cours puis pièces jointes du cahier ; tri par écart
    /// de date croissant, séance postérieure avant antérieure, puis ordre d'origine. Liste complète (5 affichés).
    /// `fileExists` écarte les documents d'autres cours dont le fichier manque (restauration sans fichier).
    static func nearbyDocuments(for course: Course, courses: [Course], snapshot: NotebookSnapshot?, subjects: [Subject],
                                fileExists: (CourseDocument) -> Bool = { _ in true }) -> [NearbyDocument] {
        let reference = course.source?.date ?? course.date
        let label = course.source.map { NotebookCourses.subjectKey($0.subjectLabel) }
        func alreadyAttached(_ name: String, key: String?) -> Bool {
            (course.documents ?? []).contains { document in
                guard document.name == name else { return false }
                guard let key, let origin = document.sourceSessionKey else { return true }
                return origin == key
            }
        }
        func sameSubject(label other: String?, subjectId: String?) -> Bool {
            if let label, let other { return NotebookCourses.subjectKey(other) == label }
            return subjectId == course.subjectId
        }
        func inWindow(_ date: String) -> Int? {
            guard let difference = SchoolDay.daysBetween(reference, date), abs(difference) <= nearbyWindowDays else { return nil }
            return difference
        }

        var found: [(item: NearbyDocument, difference: Int)] = []
        var seen = Set<String>()
        for other in courses where other.id != course.id {
            let date = other.source?.date ?? other.date
            guard sameSubject(label: other.source?.subjectLabel, subjectId: other.subjectId), let difference = inWindow(date) else { continue }
            for document in other.documents ?? [] where !alreadyAttached(document.name, key: documentSourceKey(document, course: other)) && fileExists(document) {
                guard seen.insert("\(documentSourceKey(document, course: other) ?? date)|\(document.name)").inserted else { continue }
                found.append((NearbyDocument(origin: .course(courseId: other.id, document: document), name: document.name,
                                             extractedTitle: document.extractedTitle, date: date), difference))
            }
        }
        let ownSession = notebookSession(for: course, in: snapshot)
        for session in snapshot?.sessions ?? [] where session != ownSession {
            let subjectId = SubjectMatcher.match(session.subject, subjects: subjects)?.id
            guard sameSubject(label: session.subject, subjectId: subjectId), let difference = inWindow(session.date) else { continue }
            for attachment in NotebookCourses.fileAttachments(of: session) where !alreadyAttached(attachment.name, key: NotebookCourses.sessionKey(session)) {
                guard seen.insert("\(NotebookCourses.sessionKey(session))|\(attachment.name)").inserted else { continue }
                found.append((NearbyDocument(origin: .notebook(attachment), name: attachment.name, extractedTitle: nil, sourceSessionKey: NotebookCourses.sessionKey(session), date: session.date),
                              difference))
            }
        }
        return found.enumerated().sorted { lhs, rhs in
            let a = lhs.element.difference, b = rhs.element.difference
            if abs(a) != abs(b) { return abs(a) < abs(b) }
            if (a > 0) != (b > 0) { return a > 0 }
            return lhs.offset < rhs.offset
        }.map(\.element.item)
    }
}
