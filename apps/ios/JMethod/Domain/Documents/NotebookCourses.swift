import Foundation

/// Une séance du cahier de textes = un cours (`notebook-to-course.md` §1–§5, `native/shared/documents/reference.py`).
enum NotebookCourses {
    static let backfillDays = 14
    static let maximumFiles = 10
    /// Au plus une lecture du cahier toutes les 2 h pour la seule création automatique.
    static let autoReadInterval: TimeInterval = 2 * 60 * 60

    // MARK: Clé stable (§1)

    static let keyPrefix = "pn1:"

    /// Clé de dédoublonnage stable entre sessions Pronote. Les `N` du cahier (et `lessonId`) changent à chaque session :
    /// ils ne servent jamais de clé persistante.
    static func sessionKey(_ session: NotebookSession) -> String {
        sessionKey(date: session.date, subject: session.subject, ordinal: session.ordinal)
    }

    static func sessionKey(date: String, subject: String, ordinal: Int) -> String {
        "\(keyPrefix)\(date)|\(subjectKey(subject))|\(ordinal)"
    }

    /// Matière normalisée (règle 33) : forme repliée de `matchSubject` (minuscules, NFD, retrait de U+0300–U+036F),
    /// toute suite de caractères ni lettre ni chiffre → une espace, rognée. Pas de recomposition NFC.
    static func subjectKey(_ subject: String) -> String {
        var output = ""
        var pendingSpace = false
        for character in SubjectMatcher.normalize(subject) {
            if character.isLetter || character.isNumber {
                if pendingSpace && !output.isEmpty { output.append(" ") }
                pendingSpace = false
                output.append(character)
            } else {
                pendingSpace = true
            }
        }
        return output
    }

    /// Rang de chaque séance parmi celles de même date et même matière normalisée, dans l'ordre donné.
    static func assigningOrdinals(_ sessions: [NotebookSession]) -> [NotebookSession] {
        var counts: [String: Int] = [:]
        return sessions.map { session in
            var ranked = session
            let group = "\(session.date)|\(subjectKey(session.subject))"
            let rank = (counts[group] ?? 0) + 1
            counts[group] = rank
            ranked.ordinal = rank
            return ranked
        }
    }

    /// Ceinture et bretelles : cours issu du cahier de même date, même matière normalisée et même rang.
    static func matchingCourse(_ session: NotebookSession, courses: [Course]) -> Course? {
        let subject = subjectKey(session.subject)
        return courses.first { course in
            (course.sources ?? course.source.map { [$0] } ?? []).contains { source in
                source.kind == CourseSource.notebookKind && source.date == session.date && source.ordinal == session.ordinal && subjectKey(source.subjectLabel) == subject
            }
        }
    }

    // MARK: État d'une séance

    enum ImportState: Equatable {
        /// Jamais importée, ou cours supprimé (`dismissed`) : « Créer un cours ».
        case none
        /// Cours créé et toujours présent : « Voir le cours ».
        case created(courseId: String)
        case dismissed
    }

    static func state(of session: NotebookSession, imports: [String: NotebookImport], courses: [Course]) -> ImportState {
        let record = imports[sessionKey(session)]
        if record?.status == .created, let courseId = record?.courseId, courses.contains(where: { $0.id == courseId }) {
            return .created(courseId: courseId)
        }
        if let course = matchingCourse(session, courses: courses) { return .created(courseId: course.id) }
        return record == nil ? .none : .dismissed
    }

    // MARK: Création automatique

    enum SkipReason: String, Equatable, Sendable {
        case autoCreateDisabled, created, dismissed, courseExists, future, beforeSince, unknownSubject
    }

    static func skipReason(_ session: NotebookSession, settings: AppSettings, imports: [String: NotebookImport],
                           courses: [Course], subjects: [Subject], today: String) -> SkipReason? {
        guard settings.autoCreatesCourses else { return .autoCreateDisabled }
        if let status = imports[sessionKey(session)]?.status { return status == .created ? .created : .dismissed }
        if matchingCourse(session, courses: courses) != nil { return .courseExists }
        if session.date > today { return .future }
        if let since = settings.notebookAutoCreateSince, session.date < since { return .beforeSince }
        if SubjectMatcher.match(session.subject, subjects: subjects) == nil { return .unknownSubject }
        return nil
    }

    /// Séances éligibles, par date croissante (ordre de la réponse à date égale).
    static func eligible(_ sessions: [NotebookSession], settings: AppSettings, imports: [String: NotebookImport],
                         courses: [Course], subjects: [Subject], today: String) -> [NotebookSession] {
        sessions.enumerated()
            .filter { skipReason($0.element, settings: settings, imports: imports, courses: courses, subjects: subjects, today: today) == nil }
            .sorted { $0.element.date != $1.element.date ? $0.element.date < $1.element.date : $0.offset < $1.offset }
            .map(\.element)
    }

    struct Backfill: Equatable, Sendable {
        var from: String
        var to: String
        var sessionKeys: [String]
        var count: Int { sessionKeys.count }
        /// `notebookAutoCreateSince` si la proposition est acceptée.
        var acceptSetsSince: String { from }
        var message: String? { count == 0 ? nil : "Importer aussi les \(count) séances des 14 derniers jours ?" }
    }

    /// Proposition faite à l'activation : séances des 14 jours précédents, jamais importées, matière reconnue.
    static func backfill(_ sessions: [NotebookSession], imports: [String: NotebookImport], courses: [Course],
                         subjects: [Subject], today: String) -> Backfill {
        let from = SchoolDay.adding(-backfillDays, to: today) ?? today
        let to = SchoolDay.adding(-1, to: today) ?? today
        let keys = sessions.filter { session in
            session.date >= from && session.date <= to && imports[sessionKey(session)] == nil
                && matchingCourse(session, courses: courses) == nil
                && SubjectMatcher.match(session.subject, subjects: subjects) != nil
        }.map(sessionKey)
        return Backfill(from: from, to: to, sessionKeys: keys)
    }

    static func toast(_ count: Int) -> String? {
        count == 0 ? nil : "\(count) cours \(count == 1 ? "créé" : "créés") depuis le cahier de textes"
    }

    // MARK: Préremplissage

    /// Résultat du téléchargement d'une pièce jointe (raison d'échec : `tooLarge`, `unavailable`…).
    enum Download {
        case data(Data, url: URL?)
        case failed(String)
    }

    struct PreparedDocument: Equatable, Sendable {
        var attachment: NotebookAttachment
        var kind: DocumentExtraction.Kind?
        var mime: String
        var size: Int
        var extractedTitle: String?
        var url: URL?
    }

    struct Skipped: Equatable, Sendable {
        var id: String
        var name: String
        /// `limit` (au-delà de 10 fichiers) ou raison de l'échec du téléchargement.
        var reason: String
    }

    struct Prefill: Equatable, Sendable {
        var sessionKey: String
        var subjectId: String?
        var date: String
        var chapter: String
        var title: String
        var titleSource: CourseTitleRules.Source?
        var candidates: [CourseTitleRules.Candidate]
        var description: String
        var principalDocument: String?
        var documents: [PreparedDocument]
        var skipped: [Skipped]
        var courseNumber: Int?
    }

    /// Fichiers de la séance dans l'ordre (contenus puis pièces jointes) ; 10 au plus enregistrés, les suivants ignorés.
    static func fileAttachments(of session: NotebookSession) -> [NotebookAttachment] {
        session.contents.flatMap(\.attachments).filter { $0.kind == .file }
    }

    /// Formulaire prérempli : matière, J0, titre et chapitre (document principal, titre Pronote, nom du fichier, date),
    /// description, documents. `download` n'est appelé que pour les pièces jointes retenues, dans l'ordre.
    static func prefill(session: NotebookSession, subjects: [Subject], courses: [Course],
                        download: (NotebookAttachment) -> Download) -> Prefill {
        let subject = SubjectMatcher.match(session.subject, subjects: subjects)
        let names = [session.subject] + (subject.map { [$0.name] } ?? [])
        var documents: [PreparedDocument] = []
        var skipped: [Skipped] = []
        var principal: (name: String, extraction: DocumentExtraction)?
        for attachment in fileAttachments(of: session) {
            if documents.count >= maximumFiles {
                skipped.append(Skipped(id: attachment.id, name: attachment.name, reason: "limit"))
                continue
            }
            switch download(attachment) {
            case .failed(let reason):
                skipped.append(Skipped(id: attachment.id, name: attachment.name, reason: reason))
            case .data(let data, let url):
                let extraction = DocumentText.extract(name: attachment.name, data: data)
                if principal == nil, extraction.kind != nil { principal = (attachment.name, extraction) }
                documents.append(PreparedDocument(
                    attachment: attachment, kind: extraction.kind, mime: DocumentText.mime(name: attachment.name, kind: extraction.kind),
                    size: data.count,
                    extractedTitle: extraction.kind == nil ? nil : CourseTitleRules.extractedTitle(extraction, name: attachment.name, subjectNames: names),
                    url: url
                ))
            }
        }
        let contentTitle = session.contents.map(\.title).first { !$0.isEmpty }
        let pipeline = CourseTitleRules.pipeline(principal: principal, contentTitle: contentTitle, subjectNames: names, sessionDate: session.date)
        let chapter = pipeline.choice.chapter
        return Prefill(
            sessionKey: sessionKey(session),
            subjectId: subject?.id,
            date: session.date,
            chapter: chapter,
            title: pipeline.choice.title ?? CourseTitleRules.dateFallback(session.date),
            titleSource: pipeline.choice.titleFrom.map { pipeline.candidates[$0].source },
            candidates: pipeline.candidates,
            description: CourseTitleRules.description(session.contents),
            principalDocument: principal?.name,
            documents: documents,
            skipped: skipped,
            courseNumber: subject.map { CourseCreation.courseNumber(subjectId: $0.id, chapter: chapter, courses: courses) }
        )
    }

    static func source(for session: NotebookSession) -> CourseSource {
        CourseSource(kind: CourseSource.notebookKind, sessionKey: sessionKey(session), subjectLabel: session.subject, date: session.date,
                     ordinal: session.ordinal)
    }

    // MARK: Migration des anciennes clés (§1, règles 37 à 39)

    /// Migration idempotente des données créées avec l'ancienne clé (`N` Pronote, `cours:<lessonId>@<date>`).
    /// Aucun cours n'est supprimé ; un document déjà migré ressort inchangé.
    static func migratingKeys(_ document: AppDocument) -> AppDocument {
        guard document.schemaVersion < 2 else { return document }
        var result = document
        let ids = Set(document.courses.map(\.id))
        let position = Dictionary(document.courses.enumerated().map { ($0.element.id, $0.offset) }, uniquingKeysWith: { first, _ in first })
        // Entrées qui désignent un cours existant, par cours (ordre stable : `at` puis clé).
        let sortedImports = document.notebookImports.sorted { $0.value.at != $1.value.at ? $0.value.at < $1.value.at : $0.key < $1.key }
        var pointing: [String: [(key: String, record: NotebookImport)]] = [:]
        for (key, record) in sortedImports {
            if let courseId = record.courseId, ids.contains(courseId) { pointing[courseId, default: []].append((key, record)) }
        }
        // Ordre de création (règle 37) : `createdAt`, puis le plus petit `at` (absent = en dernier), puis position décroissante.
        func older(_ a: Course, _ b: Course) -> Bool {
            if a.createdAt != b.createdAt { return a.createdAt < b.createdAt }
            let atA = pointing[a.id]?.first?.record.at, atB = pointing[b.id]?.first?.record.at
            if atA != atB {
                guard let atA else { return false }
                guard let atB else { return true }
                return atA < atB
            }
            return (position[a.id] ?? 0) > (position[b.id] ?? 0)
        }

        let notebookIndices = document.courses.indices.filter { document.courses[$0].source?.kind == CourseSource.notebookKind }
        var groups: [String: [Int]] = [:]
        var groupOrder: [String] = []
        for index in notebookIndices {
            guard let source = document.courses[index].source else { continue }
            let group = "\(source.date)|\(subjectKey(source.subjectLabel))"
            if groups[group] == nil { groupOrder.append(group) }
            groups[group, default: []].append(index)
        }
        for group in groupOrder {
            let members = groups[group] ?? []
            let legacy = members.filter { document.courses[$0].source?.ordinal == nil }
                .sorted { older(document.courses[$0], document.courses[$1]) }
            // Doublons du bug (même titre et même chapitre repliés) : même rang, donc même clé.
            var signatures: [(signature: String, ordinal: Int)] = []
            for index in legacy {
                let course = document.courses[index]
                let signature = CourseTitleRules.fold(CourseTitleRules.cleanWhitespace(course.title)) + "\u{0}"
                    + CourseTitleRules.fold(CourseTitleRules.cleanWhitespace(course.chapter))
                let ordinal = signatures.first { $0.signature == signature }?.ordinal ?? {
                    signatures.append((signature, signatures.count + 1))
                    return signatures.count
                }()
                result.courses[index].source?.ordinal = ordinal
            }
            for index in members {
                guard let source = result.courses[index].source, let ordinal = source.ordinal else { continue }
                result.courses[index].source?.sessionKey = sessionKey(date: source.date, subject: source.subjectLabel, ordinal: ordinal)
            }
        }

        // Règle 38 : une entrée par clé désignée, rattachée au cours le plus ancien de la clé.
        var imports: [String: NotebookImport] = [:]
        var consumed = Set<String>()
        var byKey: [String: [Course]] = [:]
        for index in notebookIndices {
            let course = result.courses[index]
            if let key = course.source?.sessionKey { byKey[key, default: []].append(course) }
        }
        for (key, unsorted) in byKey {
            let members = unsorted.sorted(by: older)
            let entries = members.flatMap { pointing[$0.id] ?? [] }
            guard let oldest = members.first, !entries.isEmpty else { continue }
            consumed.formUnion(entries.map(\.key))
            let own = pointing[oldest.id] ?? []
            let source = own.isEmpty ? entries : own
            imports[key] = NotebookImport(status: own.first?.record.status ?? .created, courseId: oldest.id,
                                          at: source.map(\.record.at).min() ?? "")
        }
        // Règle 39 : entrées restantes.
        for (key, record) in document.notebookImports where !consumed.contains(key) && imports[key] == nil {
            if key.hasPrefix(keyPrefix) || record.courseId.map(ids.contains) == true { imports[key] = record }
        }
        result.notebookImports = imports
        return result
    }

    static func form(_ prefill: Prefill) -> CourseDraft {
        CourseDraft(subjectId: prefill.subjectId, chapter: prefill.chapter, title: prefill.title, date: prefill.date, description: prefill.description)
    }
}
