import XCTest
import CoreImage
import UIKit
@testable import JMethodConnectionPOC

final class ProtocolTests: XCTestCase {
    func testUTF8CompressionRoundTrip() throws {
        let text = Data("Mathématiques – élève 漢字".utf8)
        XCTAssertEqual(try ProtocolCrypto.inflateRaw(ProtocolCrypto.deflateRaw(text)), text)
    }
    func testPKCS7AndInvalidCiphertext() throws {
        let key = ProtocolCrypto.md5(Data("1234".utf8))
        let text = Data("élève".utf8)
        XCTAssertEqual(try ProtocolCrypto.decrypt(ProtocolCrypto.encrypt(text, key: key, iv: ProtocolCrypto.zeroIV), key: key, iv: ProtocolCrypto.zeroIV), text)
        XCTAssertThrowsError(try ProtocolCrypto.decrypt(Data([0]), key: key, iv: ProtocolCrypto.zeroIV))
    }
    func testHostValidationAndReadOnlyBoundary() throws {
        XCTAssertThrowsError(try PronoteURL.validated("https://index-education.net.evil.test/pronote/eleve.html"))
        XCTAssertThrowsError(try PronoteURL.validated("http://demo.index-education.net/pronote/eleve.html"))
        XCTAssertFalse(PronoteClient.readFunctions.contains("SaisieInformationsPersonnelles"))
        XCTAssertTrue(PronoteClient.readFunctions.contains("PageEmploiDuTemps"))
    }
    func testSyntheticReferenceVectors() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "protocol-vectors", withExtension: "json"))
        let vectors = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertEqual(vectors["schemaVersion"] as? Int, 1)
        let aes = try XCTUnwrap(vectors["aesCbcPkcs7"] as? [String: Any])
        let key = try ProtocolCrypto.unhex(try XCTUnwrap(aes["keyHex"] as? String))
        let iv = try ProtocolCrypto.unhex(try XCTUnwrap(aes["ivHex"] as? String))
        for item in try XCTUnwrap(aes["cases"] as? [[String: String]]) {
            let plaintext = Data(try XCTUnwrap(item["plaintextUtf8"]).utf8)
            let encrypted = try ProtocolCrypto.encrypt(plaintext, key: key, iv: iv)
            XCTAssertEqual(ProtocolCrypto.hex(encrypted), item["ciphertextHex"])
        }
        let qr = try XCTUnwrap(vectors["qr"] as? [String: Any])
        let raw = try JSONSerialization.data(withJSONObject: try XCTUnwrap(qr["qrData"]))
        let credentials = try PronoteClient.credentials(qr: JSONDecoder().decode(PronoteQRData.self, from: raw), pin: "1234")
        XCTAssertEqual(credentials.username, qr["decodedUsernameUtf8"] as? String)
        XCTAssertEqual(credentials.password, qr["decodedPasswordUtf8"] as? String)
    }
    @MainActor
    func testNativeQRCodeDecode() throws {
        let message = Data(#"{"url":"https://demo.index-education.net/pronote/eleve.html","login":"abcd","jeton":"0123"}"#.utf8)
        let generator = try XCTUnwrap(CIFilter(name: "CIQRCodeGenerator"))
        generator.setValue(message, forKey: "inputMessage")
        let output = try XCTUnwrap(generator.outputImage).transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let cg = try XCTUnwrap(CIContext().createCGImage(output, from: output.extent))
        let png = try XCTUnwrap(UIImage(cgImage: cg).pngData())
        XCTAssertEqual(try PronoteWebFlow.decode(image: png).login, "abcd")
        XCTAssertThrowsError(try PronoteWebFlow.decode(image: Data([0, 1, 2])))
    }
    func testDateAndTypedBoolean() {
        XCTAssertEqual(ProtocolJSON.dateString(["V": "31/12/2026 08:30:00"]), "2026-12-31T08:30:00")
        XCTAssertTrue(ProtocolJSON.bool("True"))
        XCTAssertFalse(ProtocolJSON.bool("False"))
    }
}
