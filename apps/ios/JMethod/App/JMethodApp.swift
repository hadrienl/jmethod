import SwiftUI

@main
struct JMethodApp: App {
    @State private var app: AppModel
    @State private var pronote: PronoteSession
    @State private var notebook: NotebookModel

    init() {
        let model = AppModel.live()
        let session = PronoteSession(app: model)
        _app = State(initialValue: model)
        _pronote = State(initialValue: session)
        _notebook = State(initialValue: NotebookModel(pronote: session))
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
                RootView(app: app, pronote: pronote, notebook: notebook)
                    .preferredColorScheme(.light)
            }
        }
    }
}
