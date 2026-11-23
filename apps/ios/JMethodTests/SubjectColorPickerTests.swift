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
        let saved = await editor.save()
        XCTAssertTrue(saved)
        XCTAssertEqual(model.document, before)
        XCTAssertEqual(try savedBytes(), bytes)
    }

    func testCancelPersistsNothing() async throws {
        let model = try await loadedModel()
        let before = model.document
        let bytes = try savedBytes()
        var editor: SubjectColorEditor? = SubjectColorEditor(app: model, subject: try maths(model))
        editor?.draft.setHue(120)
        editor?.draft.setSaturation(0.9, value: 0.4)
        editor?.draft.editText("#123456")
        XCTAssertEqual(editor?.draft.hex, "#123456")
        editor = nil // Annuler, croix, Échap ou glissement : le brouillon est abandonné.
        XCTAssertEqual(model.document, before)
        XCTAssertEqual(try savedBytes(), bytes)
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
        let saved = await editor.save()
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
        let saved = await editor.save()
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
        let saved = await editor.save()
        XCTAssertFalse(saved)
        XCTAssertEqual(editor.saveError, AppModel.SubjectColorFailure.subjectMissing.errorDescription)
        XCTAssertNil(model.subject(id: "maths"))
        XCTAssertEqual(model.document.subjects, remaining)
    }

    func testWriteFailureKeepsDraftAndAllowsRetry() async throws {
        let model = try await loadedModel()
        let before = model.document.subjects
        try breakStorage()
        let editor = SubjectColorEditor(app: model, subject: try maths(model))
        editor.draft.editText("#10B981")
        let failed = await editor.save()
        XCTAssertFalse(failed)
        XCTAssertEqual(editor.saveError, AppModel.SubjectColorFailure.unwritable.errorDescription)
        XCTAssertEqual(editor.draft.candidate, "#10B981", "brouillon conservé")
        XCTAssertFalse(editor.saving)
        XCTAssertEqual(model.document.subjects, before, "la couleur non enregistrée n'est pas affichée")

        repairStorage()
        let retried = await editor.save()
        XCTAssertTrue(retried)
        XCTAssertNil(editor.saveError)
        XCTAssertEqual(model.subject(id: "maths")?.color, "#10B981")
        let disk = try await savedDocument()
        XCTAssertEqual(disk?.subjects.first { $0.id == "maths" }?.color, "#10B981")
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
        draft.adjustHue(steps: -1)
        XCTAssertEqual(draft.hueDegrees, 355)
        XCTAssertEqual(draft.text, draft.hex)
        draft.adjustValue(steps: 3)
        XCTAssertEqual(draft.valuePercent, 100)
        draft.adjustSaturation(steps: -4)
        XCTAssertEqual(draft.saturationPercent, 80)
    }
}
