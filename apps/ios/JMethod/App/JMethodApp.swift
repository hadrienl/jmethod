import SwiftUI

@main
struct JMethodApp: App {
    @State private var app: AppModel
    @State private var pronote: PronoteSession

    init() {
        let model = AppModel.live()
        _app = State(initialValue: model)
        _pronote = State(initialValue: PronoteSession(app: model))
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
                RootView(app: app, pronote: pronote)
                    .preferredColorScheme(.light)
            }
        }
    }
}
