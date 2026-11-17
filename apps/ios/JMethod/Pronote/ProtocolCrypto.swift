import Foundation
import CommonCrypto
import CryptoKit
import Security
import zlib

// Algorithmes imposés par le protocole PRONOTE, pas par un nouveau format de stockage.
enum ProtocolCrypto {
    static let zeroIV = Data(repeating: 0, count: 16)

    /// MD5 imposé par Pronote pour dériver clés et IV ; jamais utilisé pour protéger des données de l'app.
    static func md5(_ value: Data) -> Data {
        Data(Insecure.MD5.hash(data: value))
    }

    static func sha256Hex(_ value: String) -> String {
        hex(Data(SHA256.hash(data: Data(value.utf8)))).uppercased()
    }

    static func randomBytes(_ count: Int) throws -> Data {
        var result = Data(count: count)
        let status = result.withUnsafeMutableBytes { pointer in
            SecRandomCopyBytes(kSecRandomDefault, count, pointer.baseAddress!)
        }
        guard status == errSecSuccess else { throw PronoteFailure.invalid("random", "Impossible de préparer une connexion sécurisée.") }
        return result
    }

    static func hex(_ value: Data) -> String { value.map { String(format: "%02x", $0) }.joined() }

    static func unhex(_ value: String) throws -> Data {
        guard value.count.isMultiple(of: 2), value.allSatisfy({ $0.isHexDigit }) else {
            throw PronoteFailure.invalid("hex", "La réponse de Pronote est illisible. Réessaie.")
        }
        var result = Data()
        var index = value.startIndex
        while index < value.endIndex {
            let end = value.index(index, offsetBy: 2)
            guard let byte = UInt8(value[index..<end], radix: 16) else { throw PronoteFailure.invalid("hex", "Données chiffrées invalides.") }
            result.append(byte)
            index = end
        }
        return result
    }

    static func encrypt(_ value: Data, key: Data, iv: Data) throws -> Data { try aes(value, key: key, iv: iv, operation: CCOperation(kCCEncrypt)) }
    /// Un chiffré AES-CBC/PKCS7 fait toujours un multiple non nul de 16 octets ; CommonCrypto ne le vérifie pas.
    static func decrypt(_ value: Data, key: Data, iv: Data) throws -> Data {
        guard !value.isEmpty, value.count.isMultiple(of: kCCBlockSizeAES128) else {
            throw PronoteFailure.invalid("crypto", "La réponse de Pronote est illisible. Réessaie.")
        }
        return try aes(value, key: key, iv: iv, operation: CCOperation(kCCDecrypt))
    }

    private static func aes(_ value: Data, key: Data, iv: Data, operation: CCOperation) throws -> Data {
        guard [16, 24, 32].contains(key.count), iv.count == 16 else { throw PronoteFailure.invalid("crypto", "Paramètres de chiffrement invalides.") }
        var output = [UInt8](repeating: 0, count: value.count + kCCBlockSizeAES128)
        var length = 0
        let capacity = output.count
        let status = key.withUnsafeBytes { keyBytes in
            iv.withUnsafeBytes { ivBytes in
                value.withUnsafeBytes { input in
                    CCCrypt(operation, CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding), keyBytes.baseAddress, key.count, ivBytes.baseAddress, input.baseAddress, value.count, &output, capacity, &length)
                }
            }
        }
        guard status == kCCSuccess else { throw PronoteFailure.invalid("crypto", "La réponse de Pronote est illisible. Réessaie.") }
        return Data(output.prefix(length))
    }

    /// Taille maximale d'une réponse décompressée (bombe de décompression).
    static let maximumInflatedBytes = 16 * 1024 * 1024

    static func deflateRaw(_ value: Data) throws -> Data { try zlibTransform(value, compress: true) }
    static func inflateRaw(_ value: Data) throws -> Data { try zlibTransform(value, compress: false) }

    private static func zlibTransform(_ value: Data, compress: Bool) throws -> Data {
        var stream = z_stream()
        let initialized = compress
            ? deflateInit2_(&stream, 6, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
            : inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard initialized == Z_OK else { throw PronoteFailure.invalid("compression", "Initialisation de la compression impossible.") }
        defer { if compress { deflateEnd(&stream) } else { inflateEnd(&stream) } }
        var input = [UInt8](value)
        var output = Data()
        return try input.withUnsafeMutableBytes { bytes in
            stream.next_in = bytes.bindMemory(to: Bytef.self).baseAddress
            stream.avail_in = uInt(value.count)
            var status: Int32 = Z_OK
            repeat {
                var chunk = [UInt8](repeating: 0, count: 16_384)
                let produced = chunk.withUnsafeMutableBytes { bytes -> Int in
                    stream.next_out = bytes.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(bytes.count)
                    status = compress ? deflate(&stream, Z_FINISH) : inflate(&stream, Z_NO_FLUSH)
                    return bytes.count - Int(stream.avail_out)
                }
                guard status == Z_OK || status == Z_STREAM_END else { throw PronoteFailure.invalid("compression", "Réponse Pronote compressée invalide.") }
                output.append(contentsOf: chunk.prefix(produced))
                guard output.count <= maximumInflatedBytes else { throw PronoteFailure.invalid("size", "Réponse Pronote trop volumineuse.") }
                if produced == 0 && status != Z_STREAM_END { throw PronoteFailure.invalid("compression", "Réponse Pronote tronquée.") }
            } while status != Z_STREAM_END
            return output
        }
    }

    static func encryptInitializationIV(_ value: Data) throws -> Data {
        let decimal = "130337874517286041778445012253514395801341480334668979416920989365464528904618150245388048105865059387076357492684573172203245221386376405947824377827224846860699130638566643129067735803555082190977267155957271492183684665050351182476506458843580431717209261903043895605014125081521285387341454154194253026277"
        var modulus = decimalBytes(decimal)
        if modulus.first.map({ $0 & 0x80 != 0 }) == true { modulus.insert(0, at: 0) }
        let body = der(0x02, modulus) + der(0x02, Data([1, 0, 1]))
        let encoded = der(0x30, body)
        let attributes: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPublic, kSecAttrKeySizeInBits: 1024]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(encoded as CFData, attributes as CFDictionary, &error),
              let encrypted = SecKeyCreateEncryptedData(key, .rsaEncryptionPKCS1, value as CFData, &error) else {
            throw PronoteFailure.invalid("rsa", "Préparation de la session Pronote impossible.")
        }
        return encrypted as Data
    }

    private static func decimalBytes(_ value: String) -> Data {
        var digits = value.compactMap(\.wholeNumberValue)
        var bytes: [UInt8] = []
        while !digits.isEmpty {
            var quotient: [Int] = [], remainder = 0
            for digit in digits {
                let number = remainder * 10 + digit
                if !quotient.isEmpty || number / 256 != 0 { quotient.append(number / 256) }
                remainder = number % 256
            }
            bytes.append(UInt8(remainder)); digits = quotient
        }
        return Data(bytes.reversed())
    }

    private static func der(_ tag: UInt8, _ data: Data) -> Data {
        let count = data.count
        let length: [UInt8] = count < 128 ? [UInt8(count)] : count < 256 ? [0x81, UInt8(count)] : [0x82, UInt8(count >> 8), UInt8(count & 255)]
        return Data([tag] + length) + data
    }
}
