import XCTest
@testable import JMethod

/// Sélecteur de couleur d'une matière (#8) : brouillon, confirmation et annulation au niveau d'`AppModel`.
/// Le dessin de la roue n'est pas testé ici ; les conversions le sont par `ColorWheelTests`.
@MainActor
final class SubjectColorPickerTests: XCTestCase {
    private var file: URL!

    /// Modèle chargé sur un document de test enregistré dans un fichier temporaire.
    private func loadedModel() async throws -> AppModel {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("jmethod-color-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        file = directory.appendingPathComponent("data.json")
        try await DocumentStore(url: file).save(try Fixture.document(today: "2026-11-17"), version: 1)
        let suite = "jmethod-color-tests-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set(true, forKey: AppModel.demoMigrationKey)
        let now = ISO8601DateFormatter().date(from: "2026-11-17T17:00:00Z")!
        let model = AppModel(store: DocumentStore(url: file), now: { now }, preferences: defaults)
        await model.load()
        XCTAssertEqual(model.loadState, .ready)
        return model
    }

    /// Fichiers que `DocumentStore` peut lire ou écrire (schéma 1, 2 ou 3).
    private var storeFiles: [URL] {
        let base = file.deletingPathExtension().lastPathComponent
        let folder = file.deletingLastPathComponent()
        return [file, folder.appendingPathComponent(base + "-v2.json"), folder.appendingPathComponent(base + "-v3.json")]
    }

    private func savedDocument() async throws -> AppDocument? { try await DocumentStore(url: file).load() }

    private func savedBytes() throws -> [Data] {
        storeFiles.compactMap { try? Data(contentsOf: $0) }
    }

    /// Remplace chaque fichier du document par un dossier non vide : toute écriture atomique échoue.
    private func breakStorage() throws {
        for url in storeFiles {
            try? FileManager.default.removeItem(at: url)
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            try Data("x".utf8).write(to: url.appendingPathComponent("occupé"))
        }
    }

    private func repairStorage() {
        storeFiles.forEach { try? FileManager.default.removeItem(at: $0) }
    }

    private func maths(_ model: AppModel) throws -> Subject { try XCTUnwrap(model.subject(id: "maths")) }

    // MARK: Ouverture et annulation

    func testOpeningPresentsCurrentColourWithoutChangingAnything() async throws {
        let model = try await loadedModel()
        let before = model.document
        let bytes = try savedBytes()
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        XCTAssertEqual(editor.draft.text, "#38BDF8")
        XCTAssertEqual(editor.draft.hex, "#38BDF8")
        XCTAssertEqual(ColorWheel.hex(editor.draft.color), "#38BDF8")
        XCTAssertFalse(editor.draft.isModified)
        XCTAssertNil(editor.draft.textError)
        XCTAssertEqual(model.document, before)
        XCTAssertEqual(try savedBytes(), bytes)
        // Enregistrer sans rien changer n'écrit rien.
        let saved = await editor.confirm()
        XCTAssertTrue(saved)
        XCTAssertEqual(model.document, before)
        XCTAssertEqual(try savedBytes(), bytes)
    }

    func testCancelPersistsNothing() async throws {
        let model = try await loadedModel()
        let before = model.document
        let bytes = try savedBytes()
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        editor.draft.setHue(120)
        editor.draft.setSaturation(0.9, value: 0.4)
        editor.draft.editText("#123456")
        XCTAssertEqual(editor.draft.hex, "#123456")
        XCTAssertTrue(editor.canConfirm)
        // Action d'Annuler, de la croix, d'Échap et de la fermeture de la feuille.
        editor.cancel()
        XCTAssertFalse(editor.canConfirm)
        let confirmed = await editor.confirm() // Entrée ou Enregistrer arrivés après coup
        XCTAssertFalse(confirmed)
        XCTAssertEqual(model.document, before)
        XCTAssertEqual(try savedBytes(), bytes)
        let disk = try await savedDocument()
        XCTAssertEqual(disk?.subjects, before.subjects)
    }

    // MARK: Enregistrement

    func testSaveChangesOnlyThisSubjectAndKeepsSettings() async throws {
        let model = try await loadedModel()
        let before = model.document
        let settingsDraft = SettingsView.Draft(model.document.settings)
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        editor.draft.editText("2563eb")
        XCTAssertEqual(editor.draft.text, "2563eb", "la saisie n'est pas réécrite")
        XCTAssertEqual(editor.draft.hex, "#2563EB")
        let saved = await editor.confirm()
        XCTAssertTrue(saved)
        XCTAssertNil(editor.saveError)

        var expected = before.subjects
        expected[expected.firstIndex { $0.id == "maths" }!].color = "#2563EB"
        XCTAssertEqual(model.document.subjects, expected)
        XCTAssertEqual(model.document.settings, before.settings)
        XCTAssertEqual(settingsDraft.base, model.document.settings, "le brouillon des réglages reste valable")
        XCTAssertEqual(model.document.courses, before.courses)
        XCTAssertEqual(model.document.revisions, before.revisions)
        // Écriture attendue : le fichier contient déjà la couleur normalisée.
        let disk = try await savedDocument()
        XCTAssertEqual(disk?.subjects, expected)
        XCTAssertEqual(disk?.settings, before.settings)
    }

    func testInvalidCodeIsRefused() async throws {
        let model = try await loadedModel()
        let before = model.document
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        editor.draft.editText("#12A")
        XCTAssertFalse(editor.draft.canSave)
        XCTAssertEqual(editor.draft.textError, SubjectColorDraft.invalidMessage)
        XCTAssertEqual(editor.draft.text, "#12A", "saisie partielle conservée")
        XCTAssertEqual(editor.draft.hex, "#38BDF8", "dernier aperçu valide conservé")
        let saved = await editor.confirm()
        XCTAssertFalse(saved)
        XCTAssertEqual(model.document, before)
        for invalid in ["#12345G", "#1234567", "", "bleu"] {
            do {
                try await model.setSubjectColor(subjectId: "maths", color: invalid)
                XCTFail("\(invalid) accepté")
            } catch {
                XCTAssertEqual(error as? AppModel.SubjectColorFailure, .invalidColor)
            }
        }
        XCTAssertEqual(model.document, before)
    }

    func testDeletedSubjectIsNotRecreated() async throws {
        let model = try await loadedModel()
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        editor.draft.setHue(300)
        // Restauration pendant l'édition : la matière disparaît.
        let remaining = model.document.subjects.filter { $0.id != "maths" }
        model.restore(Backup.Accepted(courses: [], revisions: [], subjects: remaining, settings: nil))
        XCTAssertFalse(editor.subjectExists)
        XCTAssertFalse(editor.canConfirm, "Enregistrer désactivé")
        XCTAssertEqual(editor.message, AppModel.SubjectColorFailure.subjectMissing.errorDescription)
        let saved = await editor.confirm()
        XCTAssertFalse(saved)
        XCTAssertEqual(editor.message, AppModel.SubjectColorFailure.subjectMissing.errorDescription, "le message reste affiché")
        XCTAssertNil(model.subject(id: "maths"))
        do {
            try await model.setSubjectColor(subjectId: "maths", color: "#10B981")
            XCTFail("matière recréée")
        } catch {
            XCTAssertEqual(error as? AppModel.SubjectColorFailure, .subjectMissing)
        }
        XCTAssertEqual(model.document.subjects, remaining)
    }

    func testWriteFailureKeepsDraftAndAllowsRetry() async throws {
        let model = try await loadedModel()
        let before = model.document.subjects
        try breakStorage()
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        editor.draft.editText("#10B981")
        let failed = await editor.confirm()
        XCTAssertFalse(failed)
        XCTAssertEqual(editor.saveError, AppModel.SubjectColorFailure.unwritable.errorDescription)
        XCTAssertEqual(editor.draft.candidate, "#10B981", "brouillon conservé")
        XCTAssertFalse(editor.saving)
        XCTAssertEqual(model.document.subjects, before, "la couleur non enregistrée n'est pas affichée")
        // Le message s'efface dès que la couleur change ; il revient si l'écriture échoue encore.
        editor.draft.setHue(200)
        XCTAssertNil(editor.saveError)
        editor.draft.editText("#10B981")
        let again = await editor.confirm()
        XCTAssertFalse(again)
        XCTAssertNotNil(editor.saveError)
        editor.draft.editText("#10B98")
        XCTAssertNil(editor.saveError, "saisie modifiée : message effacé")
        editor.draft.editText("#10B981")

        repairStorage()
        let retried = await editor.confirm()
        XCTAssertTrue(retried)
        XCTAssertNil(editor.saveError)
        XCTAssertEqual(model.subject(id: "maths")?.color, "#10B981")
        let disk = try await savedDocument()
        XCTAssertEqual(disk?.subjects.first { $0.id == "maths" }?.color, "#10B981")
    }

    // MARK: Entrée dans le champ

    /// Entrée valide : même chemin que la vue (normalisation puis `confirm()`), la feuille peut se fermer.
    func testEnterWithValidCodeSavesAndCloses() async throws {
        let model = try await loadedModel()
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        editor.draft.editText(" 10b981 ")
        editor.draft.commitText()
        let closes = await editor.confirm()
        XCTAssertTrue(closes)
        XCTAssertEqual(editor.draft.text, "#10B981")
        XCTAssertEqual(model.subject(id: "maths")?.color, "#10B981")
        let disk = try await savedDocument()
        XCTAssertEqual(disk?.subjects.first { $0.id == "maths" }?.color, "#10B981")
    }

    /// Entrée invalide : rien ne se passe, la saisie et son erreur restent.
    func testEnterWithInvalidCodeDoesNothing() async throws {
        let model = try await loadedModel()
        let before = model.document
        let bytes = try savedBytes()
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        editor.draft.editText("#10B98")
        editor.draft.commitText()
        let closes = await editor.confirm()
        XCTAssertFalse(closes)
        XCTAssertEqual(editor.draft.text, "#10B98")
        XCTAssertEqual(editor.draft.textError, SubjectColorDraft.invalidMessage)
        XCTAssertNil(editor.saveError)
        XCTAssertEqual(model.document, before)
        XCTAssertEqual(try savedBytes(), bytes)
    }

    // MARK: Écritures concurrentes

    /// L'écriture de la couleur (v) échoue alors qu'une écriture lancée pendant l'attente (v+1, avec la nouvelle
    /// couleur) réussit : l'ancienne couleur est rétablie en mémoire puis réécrite, sans perdre l'autre modification.
    func testFailedColourWriteRealignsDiskAfterNewerWriteSucceeded() async throws {
        let store = ScriptedStore(document: try Fixture.document(today: "2026-11-17"))
        let suite = "jmethod-color-tests-\(UUID().uuidString)"
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set(true, forKey: AppModel.demoMigrationKey)
        let now = ISO8601DateFormatter().date(from: "2026-11-17T17:00:00Z")!
        let model = AppModel(store: store, now: { now }, preferences: defaults)
        await model.load()
        XCTAssertEqual(model.loadState, .ready)

        await store.failNextSave()
        let colour = Task { try await model.setSubjectColor(subjectId: "maths", color: "#10B981") }
        try await eventually { await store.isHoldingFailure }
        model.setNotebookAutoCreateSince("2026-11-01")
        try await eventually { await store.last?.settings.notebookAutoCreateSince == "2026-11-01" }
        let newer = await store.last
        XCTAssertEqual(newer?.subjects.first { $0.id == "maths" }?.color, "#10B981", "v+1 contient la nouvelle couleur")

        await store.releaseFailure()
        do {
            try await colour.value
            XCTFail("échec attendu")
        } catch {
            XCTAssertEqual(error as? AppModel.SubjectColorFailure, .unwritable)
        }
        XCTAssertEqual(model.subject(id: "maths")?.color, "#38BDF8")
        try await eventually { await store.last?.subjects.first { $0.id == "maths" }?.color == "#38BDF8" }
        let realigned = await store.last
        XCTAssertEqual(realigned?.settings.notebookAutoCreateSince, "2026-11-01", "l'autre modification est conservée")
    }

    private func eventually(_ condition: () async -> Bool, file: StaticString = #filePath, line: UInt = #line) async throws {
        for _ in 0..<200 {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("condition jamais remplie", file: file, line: line)
    }

    // MARK: Brouillon

    func testDraftKeepsHueThroughGreyAndSyncsFieldOnGestures() {
        var draft = SubjectColorDraft(subject: Subject(id: "s", name: "Matière", color: "#2563eb"))
        XCTAssertEqual(draft.original, "#2563EB")
        let hue = draft.color.hue
        draft.setSaturation(0, value: 0.5)
        XCTAssertEqual(draft.color.hue, hue, accuracy: 1e-9)
        XCTAssertEqual(draft.text, draft.hex)
        draft.editText("#000000")
        XCTAssertEqual(draft.color.hue, hue, accuracy: 1e-9, "noir : teinte conservée")
        draft.editText("#ff00")
        XCTAssertEqual(draft.text, "#ff00")
        XCTAssertEqual(draft.hex, "#000000")
        draft.editText("ff0000")
        draft.commitText()
        XCTAssertEqual(draft.text, "#FF0000")
        // VoiceOver : 5°, bouclage 0 ↔ 355 ; 5 % bornés.
        draft.adjustHue(by: -SubjectColorDraft.voiceOverHueStep)
        XCTAssertEqual(draft.hueDegrees, 355)
        XCTAssertEqual(draft.text, draft.hex)
        draft.adjustHue(by: SubjectColorDraft.voiceOverHueStep)
        XCTAssertEqual(draft.hueDegrees, 0)
        draft.adjustValue(by: 3 * SubjectColorDraft.voiceOverLevelStep)
        XCTAssertEqual(draft.valuePercent, 100)
        draft.adjustSaturation(by: -4 * SubjectColorDraft.voiceOverLevelStep)
        XCTAssertEqual(draft.saturationPercent, 80)
        XCTAssertEqual(draft.colorName, ColorWheel.name(draft.color))
        // Clavier : 1° ou 1 %, 10 avec Maj.
        draft.adjustHue(by: -SubjectColorDraft.keyStep(SubjectColorDraft.keyHueStep, shift: false))
        XCTAssertEqual(draft.hueDegrees, 359)
        draft.adjustHue(by: SubjectColorDraft.keyStep(SubjectColorDraft.keyHueStep, shift: true))
        XCTAssertEqual(draft.hueDegrees, 9)
        draft.adjustSaturation(by: SubjectColorDraft.keyStep(SubjectColorDraft.keyLevelStep, shift: false))
        XCTAssertEqual(draft.saturationPercent, 81)
        draft.adjustValue(by: -SubjectColorDraft.keyStep(SubjectColorDraft.keyLevelStep, shift: true))
        XCTAssertEqual(draft.valuePercent, 90)
    }
}

/// Double de `DocumentStore` : la prochaine écriture armée reste en attente puis échoue, les autres réussissent.
private actor ScriptedStore: DocumentPersistence {
    private var document: AppDocument?
    private(set) var last: AppDocument?
    private var armed = false
    private var held: CheckedContinuation<Void, Never>?

    init(document: AppDocument) { self.document = document }

    func load() -> AppDocument? { document }
    func archiveUnreadable() {}

    func save(_ document: AppDocument, version: Int) async throws {
        if armed {
            armed = false
            await withCheckedContinuation { held = $0 }
            throw DocumentStore.Failure.unwritable
        }
        self.document = document
        last = document
    }

    func failNextSave() { armed = true }
    var isHoldingFailure: Bool { held != nil }
    func releaseFailure() {
        held?.resume()
        held = nil
    }
}
