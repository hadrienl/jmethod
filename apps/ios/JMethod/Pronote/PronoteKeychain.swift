import Foundation
import Security

/// Trousseau iOS, accessibilité `WhenUnlockedThisDeviceOnly`, sans synchronisation iCloud.
/// Contient le jeton mobile rotatif (avec URL, identifiant, UUID d'application), le choix d'enfant d'un compte parent
/// et l'établissement saisi (pour préremplir le formulaire ; jamais le mot de passe).
enum PronoteKeychain {
    private static let service = "fr.jmethod.app.pronote"
    private static let credentialsAccount = "mobile-token"
    private static let childAccount = "selected-child"
    private static let schoolAccount = "school"

    struct School: Codable, Equatable, Sendable {
        /// Saisie de l'utilisateur telle quelle (code, hôte ou adresse).
        var input: String
        var account: PronoteAccountType
    }

    static func loadSchool() -> School? {
        guard let data = try? read(schoolAccount) else { return nil }
        return try? JSONDecoder().decode(School.self, from: data)
    }

    static func saveSchool(_ school: School) {
        if let data = try? JSONEncoder().encode(school) { try? write(data, account: schoolAccount) }
    }

    /// Retire le jeton seul (refusé par Pronote) ; l'établissement mémorisé reste pour préremplir.
    static func removeCredentials() {
        delete(credentialsAccount)
    }

    static func loadCredentials() throws -> PronoteCredentials? {
        guard let data = try read(credentialsAccount) else { return nil }
        do { return try JSONDecoder().decode(PronoteCredentials.self, from: data) }
        catch { throw failure }
    }

    static func saveCredentials(_ credentials: PronoteCredentials) throws {
        try write(JSONEncoder().encode(credentials), account: credentialsAccount)
    }

    static func loadSelectedChild() -> String? {
        guard let data = try? read(childAccount) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func saveSelectedChild(_ id: String?) {
        if let id, !id.isEmpty { try? write(Data(id.utf8), account: childAccount) }
        else { delete(childAccount) }
    }

    /// Déconnexion : jeton et choix d'enfant effacés. L'établissement mémorisé (non secret) est conservé pour préremplir.
    static func removeConnection() {
        delete(credentialsAccount)
        delete(childAccount)
    }

    private static func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
    }

    private static func read(_ account: String) throws -> Data? {
        var search = query(account)
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(search as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw failure }
        return data
    }

    private static func write(_ data: Data, account: String) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let status = SecItemUpdate(query(account) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query(account)
            item.merge(attributes) { _, new in new }
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw failure }
        } else if status != errSecSuccess {
            throw failure
        }
    }

    private static func delete(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }

    private static var failure: PronoteFailure {
        .invalid("keychain", "Ta connexion enregistrée n’est pas accessible. Déverrouille l’iPhone puis réessaie.")
    }
}
