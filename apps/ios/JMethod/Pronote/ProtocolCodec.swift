import Foundation

/// Transformations pures des enveloppes Pronote, testées octet par octet sur `protocol-vectors.json`.
enum ProtocolCodec {
    /// Numéro d'ordre chiffré (hexadécimal minuscule), y compris quand la charge utile n'est pas chiffrée.
    static func orderHex(_ order: Int, key: Data, iv: Data) throws -> String {
        ProtocolCrypto.hex(try ProtocolCrypto.encrypt(Data(String(order).utf8), key: key, iv: iv))
    }

    /// Requête : JSON UTF-8 → (si compression) hex minuscule ASCII → DEFLATE brut → (si chiffrement) AES-CBC → hex majuscule.
    /// Sans compression ni chiffrement, `dataSec` reste un objet JSON.
    static func requestDataSec(_ serialized: Data, compress: Bool, encrypt: Bool, key: Data, iv: Data) throws -> Any {
        guard compress || encrypt else {
            return try JSONSerialization.jsonObject(with: serialized)
        }
        var bytes = serialized
        if compress { bytes = try ProtocolCrypto.deflateRaw(Data(ProtocolCrypto.hex(bytes).utf8)) }
        if encrypt { bytes = try ProtocolCrypto.encrypt(bytes, key: key, iv: iv) }
        return ProtocolCrypto.hex(bytes).uppercased()
    }

    /// Réponse (asymétrique) : hex → (si chiffrement) déchiffrement → (si compression) inflation → JSON, sans couche hexadécimale après inflation.
    static func responseDataSec(_ value: Any?, compress: Bool, encrypt: Bool, key: Data, iv: Data) throws -> [String: Any] {
        guard compress || encrypt else {
            guard let object = value as? [String: Any] else { throw PronoteFailure.invalid("json", "Réponse Pronote inattendue.") }
            return object
        }
        guard let text = value as? String else { throw PronoteFailure.invalid("json", "Réponse chiffrée inattendue.") }
        var bytes = try ProtocolCrypto.unhex(text)
        if encrypt { bytes = try ProtocolCrypto.decrypt(bytes, key: key, iv: iv) }
        if compress { bytes = try ProtocolCrypto.inflateRaw(bytes) }
        guard let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
            throw PronoteFailure.invalid("json", "Réponse Pronote inattendue.")
        }
        return object
    }

    /// Clé d'authentification et réponse au défi (flux 2026 : le défi est chiffré).
    static func authenticationKey(username: String, password: String, alea: String, lowercaseUsername: Bool, lowercasePassword: Bool) -> Data {
        let user = lowercaseUsername ? username.lowercased() : username
        let secret = lowercasePassword ? password.lowercased() : password
        let hashed = ProtocolCrypto.sha256Hex(alea + secret)
        return ProtocolCrypto.md5(Data((user + hashed).utf8))
    }

    static func challengeResponse(_ challenge: String, key: Data, iv: Data) throws -> String {
        ProtocolCrypto.hex(try ProtocolCrypto.encrypt(Data(challenge.utf8), key: key, iv: iv))
    }

    /// `cle` déchiffrée = octets décimaux séparés par des virgules ; la clé de session est le MD5 de ces octets.
    static func sessionKey(encryptedCle: String, authenticationKey: Data, iv: Data) throws -> Data {
        let plain = try ProtocolCrypto.decrypt(ProtocolCrypto.unhex(encryptedCle), key: authenticationKey, iv: iv)
        guard let text = String(data: plain, encoding: .utf8) else { throw PronoteFailure.invalid("crypto", "Clé de session incompatible.") }
        let parts = text.split(separator: ",")
        let numbers = parts.compactMap { UInt8($0.trimmingCharacters(in: .whitespaces)) }
        guard !numbers.isEmpty, numbers.count == parts.count else { throw PronoteFailure.invalid("crypto", "Clé de session invalide.") }
        return ProtocolCrypto.md5(Data(numbers))
    }
}

/// Calculs d'emploi du temps sans réseau : numéro de semaine et fin de cours par la grille.
enum TimetableMath {
    /// Semaine 1 = semaine de `PremierLundi`. Différence en jours calendaires de l'établissement, divisée par sept.
    static func weekNumber(day: String, firstMonday: String) -> Int? {
        guard let days = SchoolDay.daysBetween(firstMonday, day) else { return nil }
        // Division entière tronquée, comme `1 + int(days / 7)` dans pronotepy.
        return 1 + days / 7
    }

    /// Fin de cours tirée de `General.ListeHeuresFin` quand `DateDuCoursFin` manque (`Util.place2time`).
    /// - Returns: `HH:mm:ss`, ou nil si la grille ne permet pas de conclure (aucune durée inventée).
    static func endTime(place: Int, duration: Int, grid: [[String: Any]]) -> String? {
        let count = grid.count
        guard count > 1, duration > 0 else { return nil }
        var endPlace = place % (count - 1) + duration - 1
        if endPlace > count { endPlace %= (count - 1) }
        guard let entry = grid.first(where: { ProtocolJSON.integer($0["G"]) == endPlace }) else { return nil }
        let parts = ProtocolJSON.string(entry["L"]).lowercased().split(separator: "h", omittingEmptySubsequences: false)
        guard parts.count == 2, let hour = Int(parts[0]), (0...23).contains(hour), parts[1].count == 2, let minute = Int(parts[1]), (0...59).contains(minute) else { return nil }
        return String(format: "%02d:%02d:00", hour, minute)
    }
}
