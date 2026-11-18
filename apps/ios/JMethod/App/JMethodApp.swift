import SwiftUI

@main
struct JMethodApp: App {
    @State private var app: AppModel
    @State private var pronote: PronoteSession
    @State private var notebook: NotebookModel
    @State private var importer: NotebookCourseImporter

    init() {
        #if DEBUG
        let model = PreviewFixture.isEnabled ? PreviewFixture.makeModel() : AppModel.live()
        #else
        let model = AppModel.live()
        #endif
        let session = PronoteSession(app: model)
        _app = State(initialValue: model)
        _pronote = State(initialValue: session)
        let notebook = NotebookModel(pronote: session)
        let importer = NotebookCourseImporter(app: model, pronote: session)
        notebook.onRead = { [weak importer] read in importer?.afterNotebookRead(read) }
        _notebook = State(initialValue: notebook)
        _importer = State(initialValue: importer)
        if !Self.isRunningUnitTests {
            BackgroundRefresh.register(session: session)
        }
    }

    private static var isRunningUnitTests: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    var body: some Scene {
        WindowGroup {
            if Self.isRunningUnitTests {
                // Les tests unitaires n'ouvrent ni données ni connexion Pronote.
                Color.clear
            } else {
                RootView(app: app, pronote: pronote, notebook: notebook, importer: importer)
                    .preferredColorScheme(.light)
            }
        }
    }
}
