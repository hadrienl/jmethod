import Foundation

/// Modèles du contrat partagé (`native/shared/CONTRACT.md`).
struct PronoteCredentials: Codable, Sendable, Equatable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    var url: String
    var username: String
    /// Jeton mobile rotatif, jamais le mot de passe de l'utilisateur.
    var password: String
    var uuid: String
    var clientIdentifier: String?

    // Aucune description (journal, débogueur, `dump`) n'expose le jeton ni l'identifiant.
    var description: String { "PronoteCredentials(<masqué>)" }
    var debugDescription: String { description }
    var customMirror: Mirror { Mirror(self, children: [], displayStyle: .struct) }
}

struct PronoteQRData: Codable, Sendable, Equatable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    var url: String
    var login: String
    var jeton: String

    var description: String { "PronoteQRData(<masqué>)" }
    var debugDescription: String { description }
    var customMirror: Mirror { Mirror(self, children: [], displayStyle: .struct) }
}

struct PronoteChild: Identifiable, Sendable, Equatable, Hashable {
    var id: String
    var name: String
}

struct PronoteAccount: Sendable, Equatable {
    var name: String
    var children: [PronoteChild]
}

struct PronoteLesson: Identifiable, Sendable, Equatable {
    var id: String
    var subject: String
    /// Heure locale de l'établissement, `YYYY-MM-DDTHH:mm:ss`.
    var start: String
    /// Vide si Pronote ne donne ni fin ni grille horaire exploitable.
    var end: String
    var teacher: String
    var room: String
    var cancelled: Bool
}

struct PronoteFailure: Error, LocalizedError, Sendable, Equatable {
    let code: String
    let message: String
    var errorDescription: String? { message }

    static func invalid(_ code: String, _ message: String) -> Self { .init(code: code, message: message) }

    /// Session perdue (toute erreur Pronote sauf 25, HTTP non 2xx, réponse illisible) : une reconnexion par jeton suffit.
    var isExpiredSession: Bool { PronoteSession.isSessionLost(self) }
    /// Erreur 25 : trop de tentatives, aucune nouvelle tentative automatique.
    var isTooManyAttempts: Bool { code == "pronote-25" }
}
