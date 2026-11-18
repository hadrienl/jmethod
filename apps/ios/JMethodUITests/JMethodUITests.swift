import XCTest

/// Tests de fumée et captures d'écran : l'app est lancée en aperçu Debug hors ligne sur un document fictif
/// (`-JMethodPreviewFixture`), sans Pronote ni donnée de l'utilisateur.
///
/// Variables d'environnement facultatives du lanceur de tests (préfixe `TEST_RUNNER_` côté `xcodebuild`) :
/// - `JMETHOD_SCREENSHOT_DIR` : dossier où écrire aussi les captures en PNG ;
/// - `JMETHOD_SCREENSHOT_PREFIX` : préfixe des fichiers (par exemple `after`, `dark`) ;
/// - `JMETHOD_UI_TEXT_SIZE=xl` : toute la série en Dynamic Type « Accessibilité XL ».
/// Les captures sont toujours jointes au résultat (`XCTAttachment`, conservées).
@MainActor
final class JMethodUITests: XCTestCase {
    private var environment: [String: String] { ProcessInfo.processInfo.environment }
    private var prefix: String { environment["JMETHOD_SCREENSHOT_PREFIX"] ?? "shot" }
    private var forceXL: Bool { environment["JMETHOD_UI_TEXT_SIZE"] == "xl" }

    override func setUp() async throws {
        continueAfterFailure = true
    }

    // MARK: Parcours

    func testTabsScrollAndPlanningModes() {
        let app = launch()
        assertTabsExist(app)
        captureScrolling(app, "01-today", maxPages: 10)

        tapTab(app, "Planning")
        captureScrolling(app, "02-planning-week")
        app.buttons["Jour"].firstMatch.tap()
        captureScrolling(app, "03-planning-day")
        app.buttons["Mois"].firstMatch.tap()
        captureScrolling(app, "04-planning-month")

        tapTab(app, "Mes cours")
        captureScrolling(app, "05-courses")

        tapTab(app, "Mes progrès")
        captureScrolling(app, "06-stats")

        tapTab(app, "Réglages")
        captureScrolling(app, "07-settings", maxPages: 10)
    }

    func testSheets() {
        let sheets: [(String, String)] = [
            ("score:fixture-maths-1-rev-4", "10-sheet-score"),
            ("revision:fixture-pc-1-rev-3", "11-sheet-revision"),
            ("revision:fixture-maths-1-rev-4", "11b-sheet-revision-documents"),
            ("course:fixture-maths-1", "12-sheet-course"),
            ("addCourse", "13-sheet-add-course"),
            ("notebook", "14-sheet-notebook"),
        ]
        for (argument, name) in sheets {
            let app = launch(["-JMethodPreviewSheet", argument])
            XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 10), "feuille \(name) absente")
            captureScrolling(app, name)
            app.terminate()
        }
    }

    func testAddCourseKeyboardAndValidation() {
        let app = launch(["-JMethodPreviewSheet", "addCourse"])
        let field = app.textFields["course-title"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("Théorème de Pythagore")
        capture(app, "15-add-course-keyboard")
    }

    func testScoreValidationFlow() {
        let app = launch(["-JMethodPreviewSheet", "score:fixture-maths-1-rev-4"])
        let validate = app.buttons["Valider la révision"].firstMatch
        XCTAssertTrue(validate.waitForExistence(timeout: 10))
        app.buttons["75 sur 100, Maîtrisé"].firstMatch.tapIfExists()
        capture(app, "16-score-selected")
        scrollTo(validate, in: app)
        validate.tap()
        // §8.2 : le résultat reste dans la feuille, avec la séance suivante du programme du soir.
        let next = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Séance suivante")).firstMatch
        XCTAssertTrue(next.waitForExistence(timeout: 10), "« Séance suivante » absent après la validation")
        XCTAssertTrue(app.buttons["Revenir à Aujourd’hui"].firstMatch.exists)
        capture(app, "17-after-validation")
        next.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Faire le point")).firstMatch.waitForExistence(timeout: 10),
                      "fiche de la séance suivante absente")
        capture(app, "18-next-session")
    }

    /// Fiche de séance (§8.1, §8.5) : cours sans document, documents proches de la même matière, « Joindre à ce cours ».
    func testRevisionSessionSheet() {
        let app = launch(["-JMethodPreviewSheet", "revision:fixture-maths-2-rev-1"])
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Faire le point")).firstMatch.waitForExistence(timeout: 10),
                      "« Faire le point » toujours visible")
        XCTAssertTrue(app.staticTexts["Aucun document pour ce cours."].firstMatch.exists)
        let attach = app.buttons["Joindre Fiche - Fonctions affines.md à ce cours"].firstMatch
        XCTAssertTrue(attach.waitForExistence(timeout: 5), "documents proches absents")
        scrollTo(attach, in: app)
        captureScrolling(app, "19-session-sheet")
        attach.tap()
        XCTAssertTrue(app.buttons["Revoir le cours"].firstMatch.waitForExistence(timeout: 5), "document joint absent du cours")
        app.buttons["Revoir le cours"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Ouvrir avec une autre app ou partager"].firstMatch.waitForExistence(timeout: 10), "visionneuse absente")
        capture(app, "20-session-document")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["Réviser"].waitForExistence(timeout: 5), "retour à la fiche")
    }

    /// « Revoir le cours » : la fiche s'ouvre directement sur le document principal.
    func testReviewOpensPrincipalDocument() {
        let app = launch(["-JMethodPreviewSheet", "review:fixture-maths-1-rev-4"])
        XCTAssertTrue(app.buttons["Ouvrir avec une autre app ou partager"].firstMatch.waitForExistence(timeout: 10), "document principal absent")
        XCTAssertTrue(app.staticTexts["Lecture graphique"].firstMatch.waitForExistence(timeout: 5))
        capture(app, "21-review-document")
    }

    /// Cahier de textes : « Créer un cours » sur une séance ouvre le formulaire prérempli (documents non téléchargeables
    /// dans l'aperçu : message affiché).
    func testNotebookCreateCourse() {
        let app = launch(["-JMethodPreviewSheet", "notebook"])
        let create = app.buttons["Créer un cours à partir de cette séance"].firstMatch
        XCTAssertTrue(create.waitForExistence(timeout: 10), "bouton « Créer un cours » absent")
        capture(app, "40-notebook-create-button")
        scrollTo(create, in: app)
        create.tap()
        XCTAssertTrue(app.navigationBars["Nouveau cours"].waitForExistence(timeout: 15), "formulaire prérempli absent")
        let title = app.textFields["course-title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertEqual(title.value as? String, "Fonctions affines")
        captureScrolling(app, "41-notebook-prefilled-form")
    }

    /// Fiche d'un cours issu du cahier de textes : section « Documents » (fichier présent, fichier à retélécharger),
    /// puis visionneuse intégrée (vue « Lecture » d'une fiche Markdown fictive).
    func testCourseDocuments() {
        let app = launch(["-JMethodPreviewSheet", "course:fixture-maths-1"])
        let fiche = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Fiche - Fonctions affines")).firstMatch
        XCTAssertTrue(fiche.waitForExistence(timeout: 10), "document de la fiche absent")
        scrollTo(fiche, in: app)
        XCTAssertTrue(app.staticTexts["À retélécharger"].firstMatch.exists, "document manquant non signalé")
        capture(app, "42-course-documents")
        fiche.tap()
        XCTAssertTrue(app.buttons["Ouvrir avec une autre app ou partager"].firstMatch.waitForExistence(timeout: 10), "visionneuse absente")
        XCTAssertTrue(app.staticTexts["Lecture graphique"].firstMatch.waitForExistence(timeout: 5), "texte de la fiche absent")
        capture(app, "43-document-viewer")
    }

    func testSignIn() {
        let app = launch(["-JMethodPreviewSignIn"])
        XCTAssertTrue(app.buttons["Se connecter"].firstMatch.waitForExistence(timeout: 10))
        captureScrolling(app, "20-sign-in")
    }

    /// Fumée en très grande taille de texte, quel que soit le réglage de la série.
    func testAccessibilityXLSmoke() {
        let app = launch(textSizeXL: true)
        assertTabsExist(app)
        captureScrolling(app, "30-xl-today", maxPages: 4)
        tapTab(app, "Planning")
        captureScrolling(app, "31-xl-planning", maxPages: 3)
        tapTab(app, "Mes cours")
        captureScrolling(app, "32-xl-courses", maxPages: 3)
        tapTab(app, "Mes progrès")
        captureScrolling(app, "33-xl-stats", maxPages: 3)
        tapTab(app, "Réglages")
        captureScrolling(app, "34-xl-settings", maxPages: 3)
        app.terminate()
        let sheet = launch(["-JMethodPreviewSheet", "score:fixture-maths-1-rev-4"], textSizeXL: true)
        captureScrolling(sheet, "35-xl-score", maxPages: 3)
    }

    // MARK: Outils

    private func launch(_ arguments: [String] = [], textSizeXL: Bool? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-JMethodPreviewFixture", "-AppleLanguages", "(fr)", "-AppleLocale", "fr_FR"] + arguments
        if textSizeXL ?? forceXL {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXL"]
        }
        app.launch()
        return app
    }

    private func assertTabsExist(_ app: XCUIApplication) {
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 15), "barre d'onglets absente")
        for label in ["Aujourd’hui", "Planning", "Mes cours", "Mes progrès", "Réglages"] {
            XCTAssertTrue(app.tabBars.buttons[label].exists, "onglet \(label) absent")
        }
    }

    private func tapTab(_ app: XCUIApplication, _ label: String) {
        let button = app.tabBars.buttons[label]
        XCTAssertTrue(button.waitForExistence(timeout: 5), "onglet \(label) absent")
        button.tap()
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication) {
        var attempts = 0
        while !element.isHittable && attempts < 8 {
            app.swipeUp()
            attempts += 1
        }
    }

    /// Capture l'écran puis fait défiler jusqu'en bas (arrêt quand l'image ne change plus).
    private func captureScrolling(_ app: XCUIApplication, _ name: String, maxPages: Int = 6) {
        // Laisse finir les animations d'apparition.
        _ = app.wait(for: .runningForeground, timeout: 5)
        Thread.sleep(forTimeInterval: 0.8)
        var previous = capture(app, "\(name)-0")
        guard app.scrollViews.firstMatch.exists else { return }
        for page in 1..<maxPages {
            app.scrollViews.firstMatch.swipeUp(velocity: .slow)
            Thread.sleep(forTimeInterval: 0.6)
            let shot = XCUIScreen.main.screenshot()
            if shot.pngRepresentation == previous { break }
            previous = store(shot, "\(name)-\(page)")
        }
    }

    @discardableResult
    private func capture(_ app: XCUIApplication, _ name: String) -> Data {
        store(XCUIScreen.main.screenshot(), name)
    }

    @discardableResult
    private func store(_ shot: XCUIScreenshot, _ name: String) -> Data {
        let file = "\(prefix)-\(name)"
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = file
        attachment.lifetime = .keepAlways
        add(attachment)
        let data = shot.pngRepresentation
        if let directory = environment["JMETHOD_SCREENSHOT_DIR"], !directory.isEmpty {
            let url = URL(fileURLWithPath: directory, isDirectory: true)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            try? data.write(to: url.appendingPathComponent("\(file).png"))
        }
        return data
    }
}

private extension XCUIElement {
    func tapIfExists() {
        if waitForExistence(timeout: 2) { tap() }
    }
}
