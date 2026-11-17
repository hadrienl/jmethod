import Foundation
import Security

/// Stockage d'éléments secrets, abstrait pour les tests (Keychain en production).
protocol SecureItemStore: Sendable {
    func read(_ account: String) throws -> Data?
    func write(_ data: Data, account: String) throws
    func delete(_ account: String)
}

/// Keychain iOS : accessible après le premier déverrouillage (nécessaire à l'actualisation en arrière-plan),
/// limité à cet appareil, sans synchronisation iCloud.
struct KeychainItemStore: SecureItemStore {
    let service: String

    private func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
    }

    func read(_ account: String) throws -> Data? {
        var search = query(account)
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(search as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw PronoteVault.unavailable }
        return data
    }

    func write(_ data: Data, account: String) throws {
        // La mise à jour applique aussi la nouvelle accessibilité aux éléments créés par une version précédente.
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(query(account) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query(account)
            item.merge(attributes) { _, new in new }
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw PronoteVault.unavailable }
        } else if status != errSecSuccess {
            throw PronoteVault.unavailable
        }
    }

    func delete(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }
}

/// Données de connexion Pronote, toutes chiffrées dans le trousseau de cet iPhone :
/// jeton mobile rotatif (avec URL, identifiant, UUID d'application), enfant suivi, établissement,
/// identifiant et mot de passe si « Rester connecté » est activé, et reconnexion à faire au prochain premier plan.
struct PronoteVault: Sendable {
    struct School: Codable, Equatable, Sendable {
        /// Saisie de l'utilisateur telle quelle (code, hôte ou adresse).
        var input: String
        var account: PronoteAccountType
    }

    /// Identifiant et mot de passe enregistrés avec « Rester connecté ». Le mot de passe disparaît
    /// si Pronote le refuse ; l'identifiant reste pour préremplir le formulaire.
    struct SavedLogin: Codable, Equatable, Sendable {
        var username: String
        var password: String?
    }

    static let unavailable = PronoteFailure.invalid("keychain", "Ta connexion enregistrée n’est pas accessible. Déverrouille l’iPhone puis réessaie.")
    static let live = PronoteVault(store: KeychainItemStore(service: "fr.jmethod.app.pronote"))

    private enum Key {
        static let credentials = "mobile-token"
        static let child = "selected-child"
        static let school = "school"
        static let login = "login"
        static let relogin = "needs-relogin"
        static let autoReloginOff = "auto-relogin-off"
    }

    let store: any SecureItemStore

    // MARK: Jeton

    func loadCredentials() throws -> PronoteCredentials? {
        guard let data = try store.read(Key.credentials) else { return nil }
        do { return try JSONDecoder().decode(PronoteCredentials.self, from: data) } catch { throw Self.unavailable }
    }

    func saveCredentials(_ credentials: PronoteCredentials) throws {
        try store.write(JSONEncoder().encode(credentials), account: Key.credentials)
    }

    func removeCredentials() { store.delete(Key.credentials) }

    // MARK: Enfant suivi

    func loadSelectedChild() -> String? {
        (try? store.read(Key.child)).flatMap { String(data: $0, encoding: .utf8) }
    }

    func saveSelectedChild(_ id: String?) {
        if let id, !id.isEmpty { try? store.write(Data(id.utf8), account: Key.child) } else { store.delete(Key.child) }
    }

    // MARK: Établissement et identifiants

    func loadSchool() -> School? { decode(School.self, Key.school) }
    func saveSchool(_ school: School) { encode(school, Key.school) }

    func loadLogin() -> SavedLogin? { decode(SavedLogin.self, Key.login) }
    func saveLogin(_ login: SavedLogin) { encode(login, Key.login) }
    func removeLogin() { store.delete(Key.login) }

    /// Mot de passe refusé par Pronote : il est effacé, l'identifiant et l'établissement restent.
    func removePassword() {
        guard var login = loadLogin() else { return }
        login.password = nil
        saveLogin(login)
    }

    // MARK: Reconnexion différée

    /// Jeton refusé en arrière-plan : une reconnexion silencieuse est à faire au prochain premier plan.
    var needsSilentRelogin: Bool { (try? store.read(Key.relogin)) != nil }

    func setNeedsSilentRelogin(_ value: Bool) {
        if value { try? store.write(Data([1]), account: Key.relogin) } else { store.delete(Key.relogin) }
    }

    /// Reconnexion automatique avec les identifiants enregistrés : désactivée par une déconnexion volontaire
    /// (le lancement suivant affiche le formulaire prérempli), réactivée par la connexion réussie suivante.
    var autoReloginEnabled: Bool { (try? store.read(Key.autoReloginOff)) == nil }

    func setAutoReloginEnabled(_ enabled: Bool) {
        if enabled { store.delete(Key.autoReloginOff) } else { try? store.write(Data([1]), account: Key.autoReloginOff) }
    }

    /// Déconnexion : jeton, enfant suivi et reconnexion différée effacés, reconnexion automatique désactivée.
    /// Établissement, type de compte, identifiant et mot de passe restent pour préremplir le formulaire ;
    /// `forgetLogin` efface aussi identifiant et mot de passe (l'établissement et le type de compte restent).
    func removeConnection(forgetLogin: Bool) {
        store.delete(Key.credentials)
        store.delete(Key.child)
        store.delete(Key.relogin)
        setAutoReloginEnabled(false)
        if forgetLogin { store.delete(Key.login) }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let data = try? store.read(key) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func encode<T: Encodable>(_ value: T, _ key: String) {
        if let data = try? JSONEncoder().encode(value) { try? store.write(data, account: key) }
    }
}
