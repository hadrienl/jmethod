import Foundation

/// Limites des essais automatiques, communes au premier plan et à l'arrière-plan : erreur Pronote 25
/// (trop de tentatives) et reconnexions silencieuses. Horodatages non secrets, conservés entre deux lancements.
struct PronoteThrottle: Sendable {
    /// Pronote bloque les connexions après trop d'essais : aucune connexion automatique pendant au moins une heure.
    static let blockDuration: TimeInterval = 60 * 60
    /// Au plus une reconnexion silencieuse automatique par heure.
    static let silentInterval: TimeInterval = 60 * 60
    static let blockedCode = "pronote-25"
    static let blockedMessage = "Pronote a temporairement bloqué les connexions. Réessaie dans une heure."

    private enum Key {
        static let blockedUntil = "fr.jmethod.pronote.blockedUntil"
        static let lastSilent = "fr.jmethod.pronote.lastSilentAttempt"
    }

    /// Domaine des préférences (nil : préférences standard de l'app). Seul le nom est conservé, pour rester `Sendable`.
    private let suiteName: String?

    init(suiteName: String? = nil) { self.suiteName = suiteName }

    static let live = PronoteThrottle()

    private var defaults: UserDefaults { suiteName.flatMap { UserDefaults(suiteName: $0) } ?? .standard }

    private func date(_ key: String) -> Date? {
        let value = defaults.double(forKey: key)
        return value > 0 ? Date(timeIntervalSince1970: value) : nil
    }

    var blockedUntil: Date? { date(Key.blockedUntil) }

    func isBlocked(now: Date) -> Bool { (blockedUntil ?? .distantPast) > now }

    func block(now: Date) { defaults.set(now.addingTimeInterval(Self.blockDuration).timeIntervalSince1970, forKey: Key.blockedUntil) }

    /// Enregistre le blocage si l'erreur est l'erreur 25 ; renvoie vrai dans ce cas.
    @discardableResult
    func note(_ error: Error, now: Date) -> Bool {
        guard Self.isBlockedError(error) else { return false }
        block(now: now)
        return true
    }

    static func isBlockedError(_ error: Error) -> Bool { (error as? PronoteFailure)?.code == blockedCode }

    var lastSilentAttempt: Date? { date(Key.lastSilent) }

    func silentAllowed(now: Date) -> Bool {
        guard let last = lastSilentAttempt else { return true }
        return now.timeIntervalSince(last) >= Self.silentInterval || now < last
    }

    func recordSilentAttempt(now: Date) { defaults.set(now.timeIntervalSince1970, forKey: Key.lastSilent) }
}
