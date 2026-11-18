import Foundation
import Security

enum SecureStore {
    private static let service = "fr.jmethod.connectionpoc"
    private static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "mobile-token", kSecAttrSynchronizable as String: false] }
    static func load() throws -> PronoteCredentials? {
        var search = query
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(search as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw failure }
        return try JSONDecoder().decode(PronoteCredentials.self, from: data)
    }
    static func save(_ credentials: PronoteCredentials) throws {
        let data = try JSONEncoder().encode(credentials)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw failure }
        } else if status != errSecSuccess { throw failure }
    }
    static func remove() { SecItemDelete(query as CFDictionary) }
    private static var failure: PronoteFailure { .invalid("keychain", "Le trousseau sécurisé est indisponible. Déverrouille l’iPhone puis réessaie.") }
}
