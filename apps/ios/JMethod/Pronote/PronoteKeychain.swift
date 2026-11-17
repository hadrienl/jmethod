import Foundation
import Security

/// Trousseau iOS, accessibilité `WhenUnlockedThisDeviceOnly`, sans synchronisation iCloud.
/// Contient le jeton mobile rotatif (avec URL, identifiant, UUID d'application) et le choix d'enfant d'un compte parent.
enum PronoteKeychain {
    private static let service = "fr.jmethod.app.pronote"
    private static let credentialsAccount = "mobile-token"
    private static let childAccount = "selected-child"

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

    static func removeAll() {
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
        .invalid("keychain", "Le trousseau sécurisé est indisponible. Déverrouille l’iPhone puis réessaie.")
    }
}
