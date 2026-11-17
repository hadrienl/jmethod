import XCTest
@testable import JMethod

/// Rejoue `native/shared/protocol-vectors.json` (synthétiques) octet par octet.
final class ProtocolVectorTests: XCTestCase {
    private var vectors: [String: Any] = [:]

    override func setUpWithError() throws {
        vectors = try SharedVectors.object("protocol-vectors.json")
        XCTAssertEqual(vectors["schemaVersion"] as? Int, 1)
    }

    private func section(_ name: String) throws -> [String: Any] {
        try XCTUnwrap(vectors[name] as? [String: Any], name)
    }

    private func hex(_ value: Any?) throws -> Data { try ProtocolCrypto.unhex(try XCTUnwrap(value as? String)) }

    func testHashes() throws {
        let hashes = try section("hashes")
        XCTAssertEqual(ProtocolCrypto.hex(ProtocolCrypto.md5(Data())), hashes["md5EmptyHex"] as? String)
        XCTAssertEqual(ProtocolCrypto.hex(ProtocolCrypto.md5(Data("1234".utf8))), hashes["md5Pin1234Hex"] as? String)
        let initialization = try section("initialization")
        XCTAssertEqual(ProtocolCrypto.hex(ProtocolCrypto.md5(try hex(initialization["temporaryIVHex"]))), hashes["md5TemporaryIVHex"] as? String)
    }

    func testAESCBCPKCS7ByteExact() throws {
        let aes = try section("aesCbcPkcs7")
        let key = try hex(aes["keyHex"]), iv = try hex(aes["ivHex"])
        let cases = try XCTUnwrap(aes["cases"] as? [[String: String]])
        XCTAssertFalse(cases.isEmpty)
        for item in cases {
            let plaintext = Data(try XCTUnwrap(item["plaintextUtf8"]).utf8)
            let encrypted = try ProtocolCrypto.encrypt(plaintext, key: key, iv: iv)
            XCTAssertEqual(ProtocolCrypto.hex(encrypted), item["ciphertextHex"])
            XCTAssertEqual(try ProtocolCrypto.decrypt(encrypted, key: key, iv: iv), plaintext)
        }
        XCTAssertThrowsError(try ProtocolCrypto.decrypt(Data([0]), key: key, iv: iv))
    }

    func testQRDecryptionAndMobileURL() throws {
        let qr = try section("qr")
        let data = try decodeJSON(PronoteQRData.self, qr["qrData"])
        let credentials = try PronoteClient.credentials(qr: data, pin: try XCTUnwrap(qr["pin"] as? String), uuid: "fixture-uuid")
        XCTAssertEqual(credentials.username, qr["decodedUsernameUtf8"] as? String)
        XCTAssertEqual(credentials.password, qr["decodedPasswordUtf8"] as? String)
        XCTAssertEqual(credentials.url, qr["expectedMobileURL"] as? String)
        XCTAssertEqual(credentials.uuid, "fixture-uuid")
        XCTAssertThrowsError(try PronoteClient.credentials(qr: data, pin: "12a4"))
        XCTAssertThrowsError(try PronoteClient.credentials(qr: data, pin: "123"))
    }

    func testInitializationEnvelopeAndResponse() throws {
        let initialization = try section("initialization")
        let key = try hex(initialization["initialKeyHex"])
        let requestIV = try hex(initialization["requestIVHex"])
        XCTAssertEqual(key, ProtocolCrypto.md5(Data()))
        XCTAssertEqual(requestIV, ProtocolCrypto.zeroIV)
        XCTAssertEqual(try hex(initialization["temporaryIVHex"]).base64EncodedString(), initialization["uuidBase64WithoutRSA"] as? String)

        let compress = try XCTUnwrap(initialization["compress"] as? Bool), encrypt = try XCTUnwrap(initialization["encrypt"] as? Bool)
        let serialized = Data(try XCTUnwrap(initialization["serializedDataUtf8"] as? String).utf8)
        let envelope = try XCTUnwrap(initialization["expectedRequestEnvelope"] as? [String: Any])
        XCTAssertEqual(try ProtocolCodec.orderHex(1, key: key, iv: requestIV), envelope["no"] as? String)
        XCTAssertEqual(try ProtocolCodec.requestDataSec(serialized, compress: compress, encrypt: encrypt, key: key, iv: requestIV) as? String, envelope["dataSec"] as? String)

        // La réponse est déchiffrée avec l'IV changé (MD5 de l'IV temporaire) et la clé initiale.
        let responseIV = try hex(initialization["responseIVHex"])
        XCTAssertEqual(responseIV, ProtocolCrypto.md5(try hex(initialization["temporaryIVHex"])))
        let decoded = try ProtocolCodec.responseDataSec(initialization["wireResponseDataSec"], compress: compress, encrypt: encrypt, key: key, iv: responseIV)
        assertJSONEqual(try XCTUnwrap(decoded["data"]), try XCTUnwrap(initialization["expectedResponseData"]), "réponse FonctionParametres")
        let data = try XCTUnwrap(decoded["data"] as? [String: Any])
        XCTAssertEqual(ProtocolJSON.dateString(ProtocolJSON.object(data["General"])["PremierLundi"]), "2026-08-31T00:00:00")
    }

    func testAuthenticationChallenge() throws {
        let auth = try section("authentication")
        let username = try XCTUnwrap(auth["usernameUtf8"] as? String)
        let password = try XCTUnwrap(auth["passwordUtf8"] as? String)
        let alea = try XCTUnwrap(auth["aleaUtf8"] as? String)
        let lowerUser = try XCTUnwrap(auth["modeCompLog"] as? Bool), lowerPassword = try XCTUnwrap(auth["modeCompMdp"] as? Bool)
        XCTAssertEqual(lowerUser ? username.lowercased() : username, auth["normalizedUsernameUtf8"] as? String)
        XCTAssertEqual(ProtocolCrypto.sha256Hex(try XCTUnwrap(auth["sha256InputUtf8"] as? String)), auth["passwordSHA256UpperHex"] as? String)
        let key = ProtocolCodec.authenticationKey(username: username, password: password, alea: alea, lowercaseUsername: lowerUser, lowercasePassword: lowerPassword)
        XCTAssertEqual(ProtocolCrypto.hex(key), auth["authKeyHex"] as? String)
        let challenge = try ProtocolCodec.challengeResponse(try XCTUnwrap(auth["challengeUtf8"] as? String), key: key, iv: try hex(auth["ivHex"]))
        XCTAssertEqual(challenge, auth["challengeCipherHex"] as? String)
    }

    func testSessionKeyFromCle() throws {
        let after = try section("afterAuthentication")
        let key = try ProtocolCodec.sessionKey(encryptedCle: try XCTUnwrap(after["cleCipherHex"] as? String),
                                               authenticationKey: try hex(after["authKeyHex"]), iv: try hex(after["ivHex"]))
        XCTAssertEqual(ProtocolCrypto.hex(key), after["sessionKeyHex"] as? String)
        XCTAssertEqual(ProtocolCrypto.md5(try hex(after["sessionSecretBytesHex"])), key)
    }

    func testRequestsAllCompressionAndEncryptionModes() throws {
        let requests = try XCTUnwrap(vectors["requests"] as? [[String: Any]])
        XCTAssertEqual(requests.count, 4)
        for item in requests {
            let name = item["name"] as? String ?? ""
            let key = try hex(item["keyHex"]), iv = try hex(item["ivHex"])
            let compress = try XCTUnwrap(item["compress"] as? Bool), encrypt = try XCTUnwrap(item["encrypt"] as? Bool)
            let serialized = Data(try XCTUnwrap(item["serializedDataUtf8"] as? String).utf8)
            XCTAssertEqual(ProtocolCrypto.hex(serialized), item["serializedDataHex"] as? String, name)
            // Le flux DEFLATE de référence (zlib niveau 6) doit être accepté et redonner l'hex ASCII.
            let reference = try hex(item["rawDeflateOfHexJsonHex"])
            XCTAssertEqual(String(decoding: try ProtocolCrypto.inflateRaw(reference), as: UTF8.self), item["hexJsonAsciiUtf8"] as? String, name)

            XCTAssertEqual(try ProtocolCodec.orderHex(try XCTUnwrap(item["requestNumber"] as? Int), key: key, iv: iv), item["expectedOrderHex"] as? String, name)
            let dataSec = try ProtocolCodec.requestDataSec(serialized, compress: compress, encrypt: encrypt, key: key, iv: iv)
            if let text = item["expectedDataSec"] as? String {
                if compress {
                    // DEFLATE natif : flux valide qui se relit en clair ; le chiffrement, lui, est vérifié sur le flux de référence.
                    var bytes = try ProtocolCrypto.unhex(try XCTUnwrap(dataSec as? String))
                    if encrypt { bytes = try ProtocolCrypto.decrypt(bytes, key: key, iv: iv) }
                    XCTAssertEqual(String(decoding: try ProtocolCrypto.inflateRaw(bytes), as: UTF8.self), item["hexJsonAsciiUtf8"] as? String, name)
                    let expected = encrypt ? ProtocolCrypto.hex(try ProtocolCrypto.encrypt(reference, key: key, iv: iv)).uppercased() : ProtocolCrypto.hex(reference).uppercased()
                    XCTAssertEqual(expected, text, name)
                } else {
                    XCTAssertEqual(dataSec as? String, text, name)
                }
            } else {
                assertJSONEqual(dataSec, try XCTUnwrap(item["expectedDataSec"]), name)
            }
            XCTAssertEqual(item["nextRequestNumber"] as? Int, (item["requestNumber"] as? Int).map { $0 + 2 }, name)
        }
    }

    func testResponsesAllCompressionAndEncryptionModes() throws {
        let responses = try XCTUnwrap(vectors["responses"] as? [[String: Any]])
        XCTAssertEqual(responses.count, 4)
        for item in responses {
            let name = item["name"] as? String ?? ""
            let decoded = try ProtocolCodec.responseDataSec(
                item["wireDataSec"], compress: try XCTUnwrap(item["compress"] as? Bool), encrypt: try XCTUnwrap(item["encrypt"] as? Bool),
                key: try hex(item["keyHex"]), iv: try hex(item["ivHex"])
            )
            assertJSONEqual(decoded, try XCTUnwrap(item["expectedDecodedDataSec"]), name)
            assertJSONEqual(try XCTUnwrap(decoded["data"]), try XCTUnwrap(item["expectedData"]), name)
            // Le texte UTF-8 accentué ressort intact.
            XCTAssertEqual(ProtocolJSON.object(decoded["data"])["libelle"] as? String, "Mathématiques – chapitre 2", name)
        }
    }

    func testParentSignatureUsesSelectedChild() throws {
        let parent = try section("parent")
        let parameters = try XCTUnwrap(parent["parametersData"] as? [String: Any])
        let children = ProtocolJSON.list(ProtocolJSON.object(parameters["ressource"])["listeRessources"])
        XCTAssertEqual(children.count, 2)
        let selected = try XCTUnwrap(children.first { ProtocolJSON.string($0["N"]) == parent["selectedChildId"] as? String })
        assertJSONEqual(selected, try XCTUnwrap(parent["expectedSelectedResource"]), "ressource enfant")
        assertJSONEqual(PronoteClient.signature(tab: 16, selectedResource: selected, hasChildren: true), try XCTUnwrap(parent["expectedSignature"]), "signature")
        assertJSONEqual(PronoteClient.signature(tab: 16, selectedResource: selected, hasChildren: false), ["onglet": 16], "signature élève")
    }

    func testStartAttributesAreTyped() throws {
        let html = try section("htmlParsing")
        let parsed = try ProtocolJSON.parseStart("<script>" + (try XCTUnwrap(html["sample"] as? String)) + "</script>")
        assertJSONEqual(parsed, try XCTUnwrap(html["expected"]), "Start")
        XCTAssertFalse(ProtocolJSON.bool(parsed["CoA"]))
        XCTAssertFalse(ProtocolJSON.bool("false"))
        XCTAssertTrue(ProtocolJSON.bool("True"))
    }

    func testLocalDates() throws {
        let dates = try XCTUnwrap(vectors["dates"] as? [[String: Any]])
        for item in dates {
            XCTAssertEqual(ProtocolJSON.dateString(item["raw"]), item["expectedLocalISO"] as? String)
        }
    }

    func testReadOnlyAllowList() throws {
        let safety = try section("safety")
        XCTAssertEqual(PronoteClient.readFunctions, Set(try XCTUnwrap(safety["readOnlyFunctionIds"] as? [String])))
        for rejected in try XCTUnwrap(safety["rejectedFunctionExamples"] as? [String]) {
            XCTAssertFalse(PronoteClient.readFunctions.contains(rejected), rejected)
        }
    }
}
