import Foundation

/// Écran principal selon le chargement des données et l'accès Pronote.
enum AppRoute: Equatable {
    case loading
    /// Reprise de la connexion enregistrée : écran sobre, sans passage par l'écran de connexion.
    case resuming
    case loadFailure(String)
    case signIn
    case app(offline: Bool)

    static func resolve(load: AppModel.LoadState, access: PronoteSession.Access) -> AppRoute {
        switch load {
        case .loading: return access == .resuming ? .resuming : .loading
        case .failed(let message): return .loadFailure(message)
        case .ready:
            switch access {
            case .resuming: return .resuming
            case .signedOut: return .signIn
            case .offline: return .app(offline: true)
            case .connected: return .app(offline: false)
            }
        }
    }
}
