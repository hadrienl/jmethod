import Foundation

struct PronoteCredentials: Codable, Sendable {
    var url: String
    var username: String
    var password: String
    var uuid: String
    var clientIdentifier: String?
}

struct PronoteQRData: Codable, Sendable {
    var url: String
    var login: String
    var jeton: String
}

struct PronoteChild: Identifiable, Sendable, Equatable {
    var id: String
    var name: String
}

struct PronoteAccount: Sendable {
    var name: String
    var children: [PronoteChild]
    var selectedChildId: String?
}

struct PronoteLesson: Identifiable, Sendable, Equatable {
    var id: String
    var subject: String
    var start: String
    var end: String
    var teacher: String
    var room: String
    var cancelled: Bool
}

enum ConnectionPhase: String, Sendable {
    case idle, opening, login, signingIn, authenticated, qr, nativeAuthentication, synchronizing, ready, failed

    var label: String {
        switch self {
        case .idle: "Prêt à connecter Pronote"
        case .opening: "Ouverture de Pronote"
        case .login: "Connecte-toi dans la page officielle"
        case .signingIn: "Connexion à Pronote en cours"
        case .authenticated: "Session reconnue"
        case .qr: "Préparation automatique du QR code"
        case .nativeAuthentication: "Connexion directe depuis l’iPhone"
        case .synchronizing: "Lecture de l’emploi du temps"
        case .ready: "Connexion native validée"
        case .failed: "Une étape demande ton intervention"
        }
    }
}

struct PronoteFailure: Error, LocalizedError, Sendable {
    let code: String
    let message: String
    var errorDescription: String? { message }
    static func invalid(_ code: String, _ message: String) -> Self { .init(code: code, message: message) }
}

struct ConnectionLogEntry: Identifiable, Sendable {
    let id = UUID()
    let time = Date()
    let phase: ConnectionPhase
}
