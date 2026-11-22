import XCTest
@testable import JMethod

/// Du cahier de textes aux cours (`native/shared/notebook-to-course.md`) : règles, création, suppression, sauvegarde.
@MainActor
final class NotebookCourseTests: XCTestCase {
    private func session(_ id: String, date: String, subject: String = "MATHEMATIQUES", title: String = "Fonctions affines",
                         files: [String] = []) -> NotebookSession {
        NotebookSession(id: id, lessonId: "l-\(id)", date: date, subject: subject, contents: [
            NotebookContent(title: title, description: "Définition et exemples.", links: [], category: "Cours",
                            attachments: files.enumerated().map { NotebookAttachment(kind: .file, id: "pj-\(id)-\($0.offset)", name: $0.element, fileName: $0.element) }),
            NotebookContent(title: "Exercices", description: "Ex. 12 p. 45", links: [], category: "", attachments: []),
        ])
    }

    private func makeModel() async throws -> (AppModel, URL) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-docs-\(UUID().uuidString)", isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        let suite = "jmethod-docs-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set(true, forKey: AppModel.demoMigrationKey)
        let now = ISO8601DateFormatter().date(from: "2026-11-18T17:00:00Z")!
        let app = AppModel(store: DocumentStore(url: root.appendingPathComponent("data.json")), now: { now }, preferences: defaults,
                           documentStore: CourseDocumentStore(root: root))
        await app.load()
        return (app, root)
    }

    private func stagedFile(_ name: String, contents: String, in directory: URL) throws -> StagedDocument {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(name)
        try Data(contents.utf8).write(to: url)
        return StagedDocument(id: "staged-\(name)", name: name, url: url, size: contents.utf8.count,
                              kind: DocumentKind.detect(name: name, data: nil), mime: DocumentText.mime(name: name, kind: nil), extractedTitle: "Titre extrait")
    }

    // MARK: Règles

    func testImportStateAndEligibility() {
        let subjects = Constants.defaultSubjects
        // Rangs attribués comme dans un instantané : « created » et « dismissed » sont les séances 1 et 2 du 17.
        let all = NotebookCourses.assigningOrdinals([
            session("cdt-1", date: "2026-11-16"),
            session("cdt-2", date: "2026-11-18"),
            session("cdt-3", date: "2026-11-20"),
            session("cdt-4", date: "2026-11-18", subject: "LATIN"),
            session("cdt-5", date: "2026-11-10"),
            session("cdt-6", date: "2026-11-17"),
            session("cdt-7", date: "2026-11-17"),
        ])
        let (past, today, future, unknown, old, created, dismissed) = (all[0], all[1], all[2], all[3], all[4], all[5], all[6])
        let key = NotebookCourses.sessionKey as (NotebookSession) -> String
        let course = Course(id: "course-1", subjectId: "maths", chapter: "C", courseNumber: 1, title: "T", date: "2026-11-17", createdAt: "2026-11-17")
        let imports: [String: NotebookImport] = [
            key(created): .init(status: .created, courseId: "course-1", at: "2026-11-17T18:00:00Z"),
            key(dismissed): .init(status: .dismissed, courseId: "course-gone", at: "2026-11-17T18:00:00Z"),
        ]
        XCTAssertEqual(NotebookCourses.state(of: created, imports: imports, courses: [course]), .created(courseId: "course-1"))
        XCTAssertEqual(NotebookCourses.state(of: dismissed, imports: imports, courses: [course]), .dismissed)
        XCTAssertEqual(NotebookCourses.state(of: past, imports: imports, courses: [course]), .none)
        XCTAssertEqual(NotebookCourses.state(of: created, imports: imports, courses: []), .dismissed, "cours disparu : jamais recréé")

        var settings = Constants.defaultSettings
        XCTAssertEqual(NotebookCourses.skipReason(past, settings: settings, imports: imports, courses: [course], subjects: subjects, today: "2026-11-18"), .autoCreateDisabled)
        settings.notebookAutoCreate = true
        settings.notebookAutoCreateSince = "2026-11-16"
        let eligible = NotebookCourses.eligible(all, settings: settings, imports: imports, courses: [course], subjects: subjects, today: "2026-11-18")
        XCTAssertEqual(eligible.map(\.id), ["cdt-1", "cdt-2"], "futures, inconnues, anciennes, déjà importées ou refusées exclues")
        XCTAssertEqual(NotebookCourses.skipReason(future, settings: settings, imports: imports, courses: [course], subjects: subjects, today: "2026-11-18"), .future)
        XCTAssertEqual(NotebookCourses.skipReason(old, settings: settings, imports: imports, courses: [course], subjects: subjects, today: "2026-11-18"), .beforeSince)
        XCTAssertEqual(NotebookCourses.skipReason(unknown, settings: settings, imports: imports, courses: [course], subjects: subjects, today: "2026-11-18"), .unknownSubject)
        XCTAssertEqual(NotebookCourses.skipReason(dismissed, settings: settings, imports: imports, courses: [course], subjects: subjects, today: "2026-11-18"), .dismissed)
        XCTAssertEqual(today.ordinal, 1)
        let backfill = NotebookCourses.backfill(all, imports: imports, courses: [course], subjects: subjects, today: "2026-11-18")
        XCTAssertEqual(backfill.sessionKeys, [key(past), key(old)], "14 jours avant aujourd'hui, hors séances déjà importées")
        XCTAssertEqual(backfill.acceptSetsSince, "2026-11-04")
        XCTAssertEqual(NotebookCourses.toast(1), "1 cours créé depuis le cahier de textes")
    }

    func testPrefillFromSession() {
        let subjects = Constants.defaultSubjects
        let item = session("cdt-1", date: "2026-11-16", title: "Chapitre 3 : Les fonctions affines")
        let result = NotebookCourses.prefill(session: item, subjects: subjects, courses: []) { _ in .failed("unavailable") }
        XCTAssertEqual(result.title, "Les fonctions affines")
        XCTAssertEqual(result.chapter, "Chapitre 3")
        let draft = NotebookCourses.form(result)
        XCTAssertEqual(draft.subjectId, "maths")
        XCTAssertEqual(draft.date, "2026-11-16")
        XCTAssertEqual(draft.description, "Chapitre 3 : Les fonctions affines\n\nDéfinition et exemples.\n\nExercices\n\nEx. 12 p. 45")
        let untitled = NotebookSession(id: "x", lessonId: "", date: "2026-11-18", subject: "MATHEMATIQUES",
                                       contents: [NotebookContent(title: "", description: "", links: [], category: "", attachments: [])])
        let fallback = NotebookCourses.prefill(session: untitled, subjects: subjects, courses: []) { _ in .failed("unavailable") }
        XCTAssertEqual(fallback.title, "Cours du 18 novembre")
        XCTAssertEqual(fallback.chapter, "Sans chapitre")
        let many = session("y", date: "2026-11-18", files: (1...12).map { "f\($0).txt" })
        let limited = NotebookCourses.prefill(session: many, subjects: subjects, courses: []) { _ in .data(Data("Texte".utf8), url: nil) }
        XCTAssertEqual(limited.documents.count, 10, "10 fichiers au plus par cours")
        XCTAssertEqual(limited.skipped.map(\.reason), ["limit", "limit"])
    }

    func testTitleRulesRejectionAndNormalization() {
        let context = CourseTitleRules.Context(subjectNames: ["MATHEMATIQUES", "Mathématiques"], fileName: "fiche_exo_3.pdf")
        func reason(_ raw: String) -> CourseTitleRules.Rejection? { CourseTitleRules.candidate(.metadata, raw, context: context).rejection }
        XCTAssertEqual(reason("Document 1"), .generic)
        XCTAssertEqual(reason("Présentation2"), .generic)
        XCTAssertEqual(reason("Exercice"), .generic)
        XCTAssertEqual(reason("Nom : Martin"), .adminHeader)
        XCTAssertEqual(reason("12/11/2026"), .numberOrDate)
        XCTAssertEqual(reason("Mercredi 18 novembre"), .numberOrDate)
        XCTAssertEqual(reason("Mathematiques"), .subjectName)
        XCTAssertEqual(reason("fiche_exo_3.pdf"), .fileName)
        XCTAssertNil(reason("Les fonctions affines"))
        XCTAssertEqual(CourseTitleRules.normalize("Microsoft Word - « Le cycle de l’eau ».docx"), "Le cycle de l’eau")
        XCTAssertEqual(CourseTitleRules.chapterOf("Séq. 4 - Le récit")?.chapter, "Séquence 4")
        XCTAssertNil(CourseTitleRules.chapterOf("CHIMIE organique"), "chiffres romains : « ch » + « I » n'est pas un chapitre")
        XCTAssertNil(CourseTitleRules.chapterOf("Partie civile"))
        XCTAssertEqual(CourseTitleRules.dateFallback("2026-11-01"), "Cours du 1er novembre")
        XCTAssertEqual(CourseTitleRules.fileStem("cours__chimie.pdf"), "cours__chimie")
    }

    // MARK: Création, suppression, sauvegarde

    func testManualCreationStoresSourceDocumentsAndImport() async throws {
        let (app, root) = try await makeModel()
        let staging = root.appendingPathComponent("staging", isDirectory: true)
        let first = try stagedFile("Fiche.pdf", contents: "%PDF-1.4", in: staging)
        var second = try stagedFile("Annexe.txt", contents: "Annexe", in: staging)
        second.selected = false
        let item = session("cdt-1", date: "2026-11-16")
        let result = NotebookCourses.prefill(session: item, subjects: app.document.subjects, courses: []) { _ in .failed("unavailable") }
        let draft = NotebookCourseDraft(form: NotebookCourses.form(result),
                                        source: NotebookCourses.source(for: item), documents: [first, second], stagingDirectory: staging)
        app.openAddCourse(fromNotebook: draft)
        XCTAssertEqual(app.presented, .addCourse)
        XCTAssertEqual(app.courseDraft.title, "Fonctions affines")
        XCTAssertTrue(app.createCourse())
        let course = try XCTUnwrap(app.document.courses.first)
        let key = "pn1:2026-11-16|mathematiques|1"
        XCTAssertEqual(course.source, CourseSource(kind: "pronote-notebook", sessionKey: key, subjectLabel: "MATHEMATIQUES", date: "2026-11-16", ordinal: 1))
        XCTAssertEqual(course.documents?.map(\.name), ["Fiche.pdf"], "seuls les documents cochés")
        let document = try XCTUnwrap(course.documents?.first)
        XCTAssertTrue(document.file.hasPrefix("documents/\(course.id)/doc-"))
        XCTAssertTrue(document.file.hasSuffix(".pdf"))
        XCTAssertEqual(document.mime, "application/pdf")
        XCTAssertEqual(document.extractedTitle, "Titre extrait")
        let url = try XCTUnwrap(app.documentURL(document))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        let excluded = try root.appendingPathComponent("documents").resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        XCTAssertEqual(excluded, true, "documents exclus des sauvegardes")
        XCTAssertEqual(app.document.notebookImports[key]?.status, .created)
        XCTAssertEqual(app.document.notebookImports[key]?.courseId, course.id)
        XCTAssertNil(app.notebookDraft)
        XCTAssertFalse(FileManager.default.fileExists(atPath: staging.path), "fichiers préparés effacés")
        XCTAssertEqual(NotebookCourses.state(of: item, imports: app.document.notebookImports, courses: app.document.courses), .created(courseId: course.id))

        // Suppression : fichiers supprimés, séance « dismissed », jamais recréée automatiquement.
        app.deleteCourse(id: course.id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertEqual(app.document.notebookImports[key]?.status, .dismissed)
        var settings = app.document.settings
        settings.notebookAutoCreate = true
        settings.notebookAutoCreateSince = "2026-11-01"
        XCTAssertTrue(NotebookCourses.eligible([item], settings: settings, imports: app.document.notebookImports,
                                               courses: app.document.courses, subjects: app.document.subjects, today: "2026-11-18").isEmpty)
        // Nouvelle session Pronote : autres `N`, même clé, toujours refusée.
        var renumbered = item
        renumbered.id = "cdt-99"
        XCTAssertEqual(NotebookCourses.state(of: renumbered, imports: app.document.notebookImports, courses: app.document.courses), .dismissed)
        XCTAssertEqual(NotebookCourses.state(of: item, imports: app.document.notebookImports, courses: app.document.courses), .dismissed)
    }

    // MARK: Clé stable entre sessions Pronote

    func testStableSessionKeyAndOrdinals() {
        XCTAssertEqual(NotebookCourses.subjectKey("  HISTOIRE-GÉOGRAPHIE "), "histoire geographie")
        XCTAssertEqual(NotebookCourses.subjectKey("ED.PHYSIQUE & SPORT."), "ed physique sport")
        XCTAssertEqual(NotebookCourses.subjectKey("Sciences économiques et sociales"), "sciences economiques et sociales")
        XCTAssertEqual(NotebookCourses.subjectKey("ANGLAIS LV1"), "anglais lv1")
        let ranked = NotebookCourses.assigningOrdinals([
            session("a", date: "2026-11-17"), session("b", date: "2026-11-17", subject: "Mathématiques"),
            session("c", date: "2026-11-17", subject: "FRANCAIS"), session("d", date: "2026-11-16"),
        ])
        XCTAssertEqual(ranked.map(\.ordinal), [1, 2, 1, 1], "rang par date et matière normalisée, dans l'ordre de la réponse")
        XCTAssertEqual(ranked.map { NotebookCourses.sessionKey($0) }, [
            "pn1:2026-11-17|mathematiques|1", "pn1:2026-11-17|mathematiques|2",
            "pn1:2026-11-17|francais|1", "pn1:2026-11-16|mathematiques|1",
        ])
        // Les `N` et `lessonId` changent à chaque session Pronote, le contenu peut être modifié : la clé reste la même.
        var renumbered = ranked[0]
        renumbered.id = "N-autre-session"
        renumbered.lessonId = "cours-autre-session"
        renumbered.contents = []
        XCTAssertEqual(NotebookCourses.sessionKey(renumbered), NotebookCourses.sessionKey(ranked[0]))
    }

    func testExistingCourseMeansAlreadyImported() {
        // Ceinture et bretelles : un cours de même date, matière normalisée et rang suffit, même sans `notebookImports`.
        let item = NotebookCourses.assigningOrdinals([session("cdt-1", date: "2026-11-17")])[0]
        var course = Course(id: "course-1", subjectId: "maths", chapter: "C", courseNumber: 1, title: "T", date: "2026-11-17", createdAt: "2026-11-17")
        course.source = CourseSource(kind: "pronote-notebook", sessionKey: "pn1:2026-11-17|mathematiques|1", subjectLabel: "Mathématiques",
                                     date: "2026-11-17", ordinal: 1)
        var settings = Constants.defaultSettings
        settings.notebookAutoCreate = true
        XCTAssertEqual(NotebookCourses.state(of: item, imports: [:], courses: [course]), .created(courseId: "course-1"))
        XCTAssertEqual(NotebookCourses.skipReason(item, settings: settings, imports: [:], courses: [course], subjects: Constants.defaultSubjects,
                                                  today: "2026-11-18"), .courseExists)
        XCTAssertTrue(NotebookCourses.backfill([item], imports: [:], courses: [course], subjects: Constants.defaultSubjects, today: "2026-11-18").sessionKeys.isEmpty)
        var second = item
        second.ordinal = 2
        XCTAssertEqual(NotebookCourses.state(of: second, imports: [:], courses: [course]), .none, "autre rang : autre séance")
    }

    func testLegacyKeyMigration() {
        func notebookCourse(_ id: String, date: String, subject: String, key: String, createdAt: String) -> Course {
            var course = Course(id: id, subjectId: "maths", chapter: "C", courseNumber: 1, title: id, date: date, createdAt: createdAt)
            course.source = CourseSource(kind: "pronote-notebook", sessionKey: key, subjectLabel: subject, date: date)
            return course
        }
        var document = AppModel.emptyDocument
        document.courses = [
            // Doublon créé après un redémarrage (autre `N`) : rang 2, rien n'est supprimé.
            notebookCourse("dup", date: "2026-11-16", subject: "MATHEMATIQUES", key: "N-new", createdAt: "2026-11-17T08:00:00.000Z"),
            notebookCourse("orig", date: "2026-11-16", subject: "MATHEMATIQUES", key: "N-old", createdAt: "2026-11-16T18:00:00.000Z"),
            notebookCourse("fr", date: "2026-11-16", subject: "FRANCAIS", key: "N-fr", createdAt: "2026-11-16T18:01:00.000Z"),
            Course(id: "manual", subjectId: "maths", chapter: "C", courseNumber: 2, title: "Manuel", date: "2026-11-16", createdAt: "2026-11-16"),
        ]
        document.notebookImports = [
            "N-old": .init(status: .created, courseId: "orig", at: "2026-11-16T18:00:00Z"),
            "N-new": .init(status: .created, courseId: "dup", at: "2026-11-17T08:00:00Z"),
            "N-fr": .init(status: .created, courseId: "fr", at: "2026-11-16T18:01:00Z"),
            "N-gone": .init(status: .dismissed, courseId: "deleted", at: "2026-11-15T18:00:00Z"),
            "pn1:2026-11-10|svt|1": .init(status: .dismissed, courseId: "deleted-later", at: "2026-11-18T10:00:00Z"),
        ]
        let migrated = NotebookCourses.migratingKeys(document)
        let sources = Dictionary(uniqueKeysWithValues: migrated.courses.map { ($0.id, $0.source) })
        XCTAssertEqual(sources["orig"]??.sessionKey, "pn1:2026-11-16|mathematiques|1", "rang = ordre de création")
        XCTAssertEqual(sources["orig"]??.ordinal, 1)
        XCTAssertEqual(sources["dup"]??.sessionKey, "pn1:2026-11-16|mathematiques|2")
        XCTAssertEqual(sources["fr"]??.sessionKey, "pn1:2026-11-16|francais|1")
        XCTAssertNil(sources["manual"] ?? nil)
        XCTAssertEqual(migrated.courses.count, 4, "aucun cours supprimé")
        XCTAssertEqual(Set(migrated.notebookImports.keys), [
            "pn1:2026-11-16|mathematiques|1", "pn1:2026-11-16|mathematiques|2", "pn1:2026-11-16|francais|1", "pn1:2026-11-10|svt|1",
        ], "anciens « dismissed » sans cours retirés, clés `pn1:` conservées")
        XCTAssertEqual(migrated.notebookImports["pn1:2026-11-16|mathematiques|1"]?.courseId, "orig")
        XCTAssertEqual(migrated.notebookImports["pn1:2026-11-16|mathematiques|1"]?.status, .created)
        XCTAssertEqual(NotebookCourses.migratingKeys(migrated), migrated, "idempotente")
        XCTAssertEqual(NotebookCourses.migratingKeys(AppModel.emptyDocument), AppModel.emptyDocument)
    }

    func testAutomaticCreationAndUniqueIdentifiers() async throws {
        let (app, _) = try await makeModel()
        for item in NotebookCourses.assigningOrdinals([session("cdt-1", date: "2026-11-17"), session("cdt-2", date: "2026-11-17")]) {
            let result = NotebookCourses.prefill(session: item, subjects: app.document.subjects, courses: app.document.courses) { _ in .failed("unavailable") }
            let draft = NotebookCourseDraft(form: NotebookCourses.form(result),
                                            source: NotebookCourses.source(for: item), documents: [])
            XCTAssertNotNil(app.createCourseFromNotebook(draft))
        }
        XCTAssertEqual(Set(app.document.courses.map(\.id)).count, 2, "identifiants distincts dans la même milliseconde")
        XCTAssertEqual(app.document.courses.map(\.courseNumber).sorted(), [1, 2], "numérotation comme une création manuelle")
        XCTAssertTrue(app.document.revisions.isEmpty, "importer des ressources ne démarre aucun programme")
    }

    func testAssociateResourcePreservesPersonalSheetAndProgramme() async throws {
        let (app, root) = try await makeModel()
        app.openAddCourse(); app.courseDraft.reference = "C42"; app.courseDraft.description = "Mes notes"
        XCTAssertTrue(app.createCourse())
        let course = try XCTUnwrap(app.document.courses.first)
        XCTAssertTrue(app.startProgramme(courseIds: [course.id], startDate: "2026-11-19"))
        let before = app.document.revisions
        let resource = NotebookCourses.source(for: session("extra", date: "2026-11-18"))
        let staged = try stagedFile("Fiche.txt", contents: "x", in: root.appendingPathComponent("staging-associate"))
        app.notebookDraft = NotebookCourseDraft(form: CourseDraft(subjectId: "maths", date: "2026-11-18"), source: resource, documents: [staged])
        XCTAssertTrue(app.associateNotebook(to: course.id))
        let enriched = try XCTUnwrap(app.course(id: course.id))
        XCTAssertEqual(enriched.displayReference, "C42")
        XCTAssertEqual(enriched.description, "Mes notes")
        XCTAssertEqual(enriched.sources, [resource])
        XCTAssertEqual(enriched.documents?.map(\.name), ["Fiche.txt"])
        XCTAssertTrue(app.documentExists(try XCTUnwrap(enriched.documents?.first)))
        XCTAssertEqual(app.document.revisions, before)
        XCTAssertEqual(app.document.courses.count, 1)
        XCTAssertEqual(NotebookCourses.migratingKeys(app.document), app.document)
    }

    func testCancelledFormRemovesStagedFiles() async throws {
        let (app, root) = try await makeModel()
        let staging = root.appendingPathComponent("staging-cancel", isDirectory: true)
        let staged = try stagedFile("Fiche.txt", contents: "x", in: staging)
        let item = session("cdt-1", date: "2026-11-16")
        app.openAddCourse(fromNotebook: NotebookCourseDraft(form: CourseDraft(subjectId: "maths", chapter: "C", title: "T", date: "2026-11-16"),
                                                            source: NotebookCourses.source(for: item), documents: [staged], stagingDirectory: staging))
        app.discardNotebookDraft()
        XCTAssertFalse(FileManager.default.fileExists(atPath: staging.path))
        XCTAssertNil(app.notebookDraft)
        XCTAssertTrue(app.document.notebookImports.isEmpty)
    }

    func testExportAndRestoreKeepNotebookFields() throws {
        var document = AppModel.emptyDocument
        var course = Course(id: "course-1", subjectId: "maths", chapter: "Chapitre 3", courseNumber: 1, title: "Fonctions affines",
                            date: "2026-11-16", createdAt: "2026-11-18")
        course.source = CourseSource(kind: "pronote-notebook", sessionKey: "pn1:2026-11-16|mathematiques|1", subjectLabel: "MATHEMATIQUES",
                                     date: "2026-11-16", ordinal: 1)
        course.documents = [CourseDocument(id: "doc-1", name: "Fiche.pdf", mime: "application/pdf", size: 42,
                                           file: "documents/course-1/doc-1.pdf", extractedTitle: "Les fonctions affines")]
        document.courses = [course]
        document.notebookImports = ["pn1:2026-11-16|mathematiques|1": .init(status: .created, courseId: "course-1", at: "2026-11-18T19:02:11Z"),
                                    "pn1:2026-11-17|francais|1": .init(status: .dismissed, courseId: nil, at: "2026-11-18T19:03:00Z")]
        document.settings.notebookAutoCreate = true
        document.settings.notebookAutoCreateSince = "2026-11-18"
        let text = Backup.export(document, now: Date(timeIntervalSince1970: 1_795_000_000))
        XCTAssertTrue(text.contains("\"notebookImports\""))
        XCTAssertTrue(text.contains("\"sessionKey\": \"pn1:2026-11-16|mathematiques|1\""))
        XCTAssertTrue(text.contains("\"ordinal\": 1"))
        XCTAssertFalse(text.contains("FichiersExternes") || text.contains("Session="), "aucune URL Pronote")
        guard case .accepted(let accepted) = Backup.parse(Data(text.utf8)) else { return XCTFail("export illisible") }
        let restored = accepted.apply(to: AppModel.emptyDocument)
        XCTAssertEqual(restored.courses, [course])
        XCTAssertEqual(restored.notebookImports, document.notebookImports)
        XCTAssertEqual(restored.settings.notebookAutoCreate, true)
        XCTAssertEqual(restored.settings.notebookAutoCreateSince, "2026-11-18")

        // Une sauvegarde web (sans ces champs) garde le suivi existant et le réglage désactivé par défaut.
        guard case .accepted(let web) = Backup.parse(Data(#"{"courses":[],"revisions":[]}"#.utf8)) else { return XCTFail("sauvegarde web refusée") }
        XCTAssertEqual(web.apply(to: document).notebookImports, document.notebookImports)
        XCTAssertFalse(Constants.defaultSettings.autoCreatesCourses)

        // Fichier du document local : relu à l'identique ; un document vide n'écrit pas `notebookImports`.
        XCTAssertEqual(try DocumentStore.decode(try DocumentStore.encode(document)), document)
        let raw = try XCTUnwrap(JSONSerialization.jsonObject(with: try DocumentStore.encode(AppModel.emptyDocument)) as? [String: Any])
        XCTAssertNil(raw["notebookImports"])
    }

    func testDocumentStorePathsAreConfined() {
        let store = CourseDocumentStore(root: URL(fileURLWithPath: "/tmp/jmethod-root"))
        let bad = CourseDocument(id: "d", name: "x", mime: "", size: 0, file: "documents/../../secret", extractedTitle: nil)
        XCTAssertNil(store.url(for: bad))
        let good = CourseDocument(id: "d", name: "x", mime: "", size: 0, file: "documents/course-1/d.pdf", extractedTitle: nil)
        XCTAssertEqual(store.url(for: good)?.path, "/tmp/jmethod-root/documents/course-1/d.pdf")
        XCTAssertEqual(CourseDocumentStore.safeComponent("course/../1"), "course____1")
    }

    func testZipReaderAndDocumentKinds() throws {
        let directory = try SharedVectors.documentsDirectory()
        let odt = try Data(contentsOf: directory.appendingPathComponent("chapitre-trompeur.bin"))
        XCTAssertEqual(DocumentKind.detect(name: "chapitre-trompeur.bin", data: odt), .odt, "type détecté par la signature")
        let archive = try ZipArchive(odt)
        XCTAssertNotNil(try archive.read("content.xml"))
        XCTAssertNil(try archive.read("absent.xml"))
        XCTAssertThrowsError(try ZipArchive(Data("pas un zip".utf8)))
        XCTAssertEqual(DocumentKind.detect(name: "x", data: Data("%PDF-1.7".utf8)), .pdf)
        XCTAssertEqual(DocumentKind.detect(name: "Cours.PPTX", data: nil), .pptx)
    }
}
