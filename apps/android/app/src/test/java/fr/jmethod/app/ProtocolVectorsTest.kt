package fr.jmethod.app

import fr.jmethod.app.pronote.AccountType
import fr.jmethod.app.pronote.PronoteCrypto
import fr.jmethod.app.pronote.PronoteException
import fr.jmethod.app.pronote.PronotePolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8

/** Interopérabilité octet à octet avec les vecteurs synthétiques partagés (`native/shared/protocol-vectors.json`). */
class ProtocolVectorsTest {
    private val vectors by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("protocol-vectors.json")!!.bufferedReader().use { it.readText() })
    }
    private fun bytes(text: String) = text.toByteArray(UTF_8)

    @Test
    fun hashes() {
        val hashes = vectors.getJSONObject("hashes")
        assertEquals(hashes.getString("md5EmptyHex"), PronoteCrypto.hex(PronoteCrypto.md5(byteArrayOf())))
        assertEquals(hashes.getString("md5Pin1234Hex"), PronoteCrypto.hex(PronoteCrypto.md5(bytes("1234"))))
        val temporary = PronoteCrypto.unhex(vectors.getJSONObject("initialization").getString("temporaryIVHex"))
        assertEquals(hashes.getString("md5TemporaryIVHex"), PronoteCrypto.hex(PronoteCrypto.md5(temporary)))
    }

    @Test
    fun aesCbcPkcs7() {
        val aes = vectors.getJSONObject("aesCbcPkcs7")
        val key = PronoteCrypto.unhex(aes.getString("keyHex"))
        val iv = PronoteCrypto.unhex(aes.getString("ivHex"))
        val cases = aes.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val clear = bytes(row.getString("plaintextUtf8"))
            val encrypted = PronoteCrypto.encrypt(clear, key, iv)
            assertEquals(row.getString("ciphertextHex"), PronoteCrypto.hex(encrypted))
            assertArrayEquals(clear, PronoteCrypto.decrypt(encrypted, key, iv))
        }
    }

    @Test
    fun qrDecryptionAndMobileUrl() {
        val row = vectors.getJSONObject("qr")
        val credentials = PronotePolicy.credentialsFromQr(row.getJSONObject("qrData"), row.getString("pin"))
        assertEquals(row.getString("decodedUsernameUtf8"), credentials.username)
        assertEquals(row.getString("decodedPasswordUtf8"), credentials.password)
        assertEquals(row.getString("expectedMobileURL"), credentials.url)
        assertFalse("Les identifiants ne doivent pas apparaître dans toString", credentials.toString().contains(credentials.password))
    }

    @Test
    fun initializationRequest() {
        val init = vectors.getJSONObject("initialization")
        val key = PronoteCrypto.unhex(init.getString("initialKeyHex"))
        val iv = PronoteCrypto.unhex(init.getString("requestIVHex"))
        var data = bytes(init.getString("serializedDataUtf8"))
        if (init.getBoolean("compress")) data = PronoteCrypto.deflate(bytes(PronoteCrypto.hex(data)))
        if (init.getBoolean("encrypt")) data = PronoteCrypto.encrypt(data, key, iv)
        val envelope = init.getJSONObject("expectedRequestEnvelope")
        assertEquals(envelope.getString("dataSec"), PronoteCrypto.hex(data).uppercase())
        assertEquals(envelope.getString("no"), PronoteCrypto.hex(PronoteCrypto.encrypt(bytes("1"), key, iv)))
    }

    @Test
    fun authenticationChallenge() {
        val auth = vectors.getJSONObject("authentication")
        val username = if (auth.getBoolean("modeCompLog")) auth.getString("usernameUtf8").lowercase() else auth.getString("usernameUtf8")
        val password = if (auth.getBoolean("modeCompMdp")) auth.getString("passwordUtf8").lowercase() else auth.getString("passwordUtf8")
        assertEquals(auth.getString("normalizedUsernameUtf8"), username)
        val hash = PronoteCrypto.sha256Upper(auth.getString("aleaUtf8") + password)
        assertEquals(auth.getString("passwordSHA256UpperHex"), hash)
        val authKey = PronoteCrypto.md5(bytes(username + hash))
        assertEquals(auth.getString("authKeyHex"), PronoteCrypto.hex(authKey))
        val iv = PronoteCrypto.unhex(auth.getString("ivHex"))
        assertEquals(auth.getString("challengeCipherHex"), PronoteCrypto.hex(PronoteCrypto.encrypt(bytes(auth.getString("challengeUtf8")), authKey, iv)))
    }

    @Test
    fun sessionKeyFromCommaSeparatedBytes() {
        val after = vectors.getJSONObject("afterAuthentication")
        val clear = String(
            PronoteCrypto.decrypt(PronoteCrypto.unhex(after.getString("cleCipherHex")), PronoteCrypto.unhex(after.getString("authKeyHex")), PronoteCrypto.unhex(after.getString("ivHex"))),
            UTF_8,
        )
        assertEquals(after.getString("clePlainUtf8"), clear)
        val secret = clear.split(',').map { it.trim().toInt().toByte() }.toByteArray()
        assertEquals(after.getString("sessionSecretBytesHex"), PronoteCrypto.hex(secret))
        assertEquals(after.getString("sessionKeyHex"), PronoteCrypto.hex(PronoteCrypto.md5(secret)))
    }

    @Test
    fun requestWireAllModes() {
        val rows = vectors.getJSONArray("requests")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val key = PronoteCrypto.unhex(row.getString("keyHex"))
            val iv = PronoteCrypto.unhex(row.getString("ivHex"))
            var data = bytes(row.getString("serializedDataUtf8"))
            if (row.getBoolean("compress")) {
                data = PronoteCrypto.deflate(bytes(PronoteCrypto.hex(data)))
                assertEquals(row.getString("rawDeflateOfHexJsonHex"), PronoteCrypto.hex(data))
            }
            if (row.getBoolean("encrypt")) data = PronoteCrypto.encrypt(data, key, iv)
            if (row.getBoolean("encrypt") || row.getBoolean("compress")) {
                assertEquals(row.getString("expectedDataSec"), PronoteCrypto.hex(data).uppercase())
            }
            assertEquals(row.getString("expectedOrderHex"), PronoteCrypto.hex(PronoteCrypto.encrypt(bytes(row.getInt("requestNumber").toString()), key, iv)))
        }
    }

    @Test
    fun responseWireAllModes() {
        val rows = vectors.getJSONArray("responses")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (!row.getBoolean("encrypt") && !row.getBoolean("compress")) continue
            var data = PronoteCrypto.unhex(row.getString("wireDataSec"))
            if (row.getBoolean("encrypt")) data = PronoteCrypto.decrypt(data, PronoteCrypto.unhex(row.getString("keyHex")), PronoteCrypto.unhex(row.getString("ivHex")))
            if (row.getBoolean("compress")) data = PronoteCrypto.inflate(data)
            assertEquals(row.getString("serializedDataUtf8"), String(data, UTF_8))
        }
    }

    @Test
    fun startAttributesAreTypedWithoutEvaluation() {
        val html = vectors.getJSONObject("htmlParsing")
        val parsed = PronotePolicy.parseStart(html.getString("sample"))
        val expected = html.getJSONObject("expected")
        for (key in expected.keys()) assertEquals(key, expected.get(key).toString(), parsed.get(key).toString())
        assertTrue(parsed.get("CrA") is Boolean)
        assertEquals(false, PronotePolicy.parseStart("Start({h:1,a:3,CrA:\"false\"})").get("CrA"))
    }

    @Test
    fun localDatesWithoutUtcShift() {
        val dates = vectors.getJSONArray("dates")
        for (i in 0 until dates.length()) {
            val row = dates.getJSONObject(i)
            val expected = row.getString("expectedLocalISO")
            val parsed = PronotePolicy.date(row.getJSONObject("raw"))
            assertEquals(expected, java.time.LocalDateTime.parse(parsed).toString().let { if (it.length == 16) "$it:00" else it })
        }
    }

    @Test
    fun readOnlyAllowlistMatchesSharedSafety() {
        val safety = vectors.getJSONObject("safety")
        val allowed = safety.getJSONArray("readOnlyFunctionIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        // Jalon 5 : `PageCahierDeTexte` ajouté à la liste blanche (notebook-reference.md, section 1).
        assertEquals(allowed + "PageCahierDeTexte", PronotePolicy.allowedFunctions)
        val rejected = safety.getJSONArray("rejectedFunctionExamples")
        for (i in 0 until rejected.length()) assertFalse(rejected.getString(i) in PronotePolicy.allowedFunctions)
    }

    @Test
    fun compressedUtf8RoundTrip() {
        val data = bytes("Géométrie été €")
        assertArrayEquals(data, PronoteCrypto.inflate(PronoteCrypto.deflate(data)))
    }

    @Test
    fun bridgeTrustIsExact() {
        val origin = "https://school.index-education.net"
        assertTrue(PronotePolicy.isTrustedPage("https://school.index-education.net/pronote/eleve.html", origin))
        assertTrue(PronotePolicy.isTrustedPage("https://school.index-education.net/pronote/parent.html", origin))
        assertFalse(PronotePolicy.isTrustedPage("https://other.index-education.net/pronote/eleve.html", origin))
        assertFalse(PronotePolicy.isTrustedPage("https://school.index-education.net/pronote/mobile.eleve.html", origin))
        assertFalse(PronotePolicy.isTrustedPage("https://school.index-education.net.evil.test/pronote/eleve.html", origin))
        assertFalse(PronotePolicy.isTrustedPage("http://school.index-education.net/pronote/eleve.html", origin))
        assertFalse(PronotePolicy.isTrustedPage("https://school.index-education.net/pronote/professeur.html", origin))
        assertFalse(PronotePolicy.isTrustedPage(null, origin))
    }

    @Test
    fun addressIsNormalized() {
        assertEquals("https://school.index-education.net/pronote/eleve.html", PronotePolicy.normalizedAddress("  school.index-education.net "))
        assertEquals("https://school.index-education.net/pronote/eleve.html", PronotePolicy.normalizedAddress("http://school.index-education.net/pronote/"))
        assertEquals("https://school.index-education.net/pronote/eleve.html", PronotePolicy.normalizedAddress("school.index-education.net/pronote"))
        assertEquals("https://school.index-education.net/pronote/parent.html", PronotePolicy.normalizedAddress("https://school.index-education.net/pronote/mobile.parent.html?fd=1#x"))
        // L’ENT redirige l’adresse nue vers son CAS : le formulaire natif charge la connexion directe Pronote.
        assertEquals("https://school.index-education.net/pronote/parent.html?login=true", PronotePolicy.directLoginPage("https://school.index-education.net/pronote/parent.html"))
        assertEquals("https://school.index-education.net/pronote/eleve.html?login=true", PronotePolicy.directLoginPage("https://school.index-education.net/pronote/eleve.html?p=daCas#x"))
        assertEquals("https://school.index-education.net/pronote/parent.html", PronotePolicy.normalizedAddress("HTTPS://School.Index-Education.net/pronote/parent.html?login=true"))
        assertEquals("", PronotePolicy.normalizedAddress("   "))
        PronotePolicy.validatedUrl(PronotePolicy.normalizedAddress("school.index-education.net"))
    }

    @Test
    fun schoolCodeHostOrFullAddress() {
        val student = "https://abcd1234.index-education.net/pronote/eleve.html"
        val parent = "https://abcd1234.index-education.net/pronote/parent.html"
        assertEquals(student, PronotePolicy.schoolAddress("abcd1234", AccountType.STUDENT))
        assertEquals(parent, PronotePolicy.schoolAddress("abcd1234", AccountType.PARENT))
        assertEquals(student, PronotePolicy.schoolAddress("  ABCD 1234 ", AccountType.STUDENT))
        assertEquals(parent, PronotePolicy.schoolAddress("abcd1234.index-education.net", AccountType.PARENT))
        assertEquals(student, PronotePolicy.schoolAddress("abcd1234.index-education.net/pronote/", AccountType.STUDENT))
        // La page d’une adresse complète prime sur le choix Élève/Parent.
        assertEquals(parent, PronotePolicy.schoolAddress("https://abcd1234.index-education.net/pronote/parent.html", AccountType.STUDENT))
        assertEquals(student, PronotePolicy.schoolAddress("https://abcd1234.index-education.net/pronote/mobile.eleve.html?fd=1", AccountType.PARENT))
        assertEquals("", PronotePolicy.schoolAddress("   ", AccountType.STUDENT))
        PronotePolicy.validatedUrl(PronotePolicy.schoolAddress("abcd1234", AccountType.STUDENT))
        for (invalid in listOf("abcd_1234", "abcd1234.example.test", "http://abcd1234.index-education.net:8080/pronote/eleve.html")) {
            val rejected = runCatching { PronotePolicy.validatedUrl(PronotePolicy.schoolAddress(invalid, AccountType.STUDENT)) }.isFailure
            assertTrue(invalid, rejected)
        }
    }

    @Test
    fun malformedCiphertextIsRejectedBeforeAes() {
        val key = ByteArray(16)
        for (size in listOf(0, 1, 15, 17, 31)) {
            try {
                PronoteCrypto.decrypt(ByteArray(size), key, PronoteCrypto.zeroIv)
                fail("taille $size acceptée")
            } catch (error: PronoteException) {
                assertEquals("crypto", error.code)
                assertEquals("Données de connexion invalides.", error.message)
            }
        }
        val valid = PronoteCrypto.encrypt(bytes("bloc"), key, PronoteCrypto.zeroIv)
        assertEquals(16, valid.size)
        assertArrayEquals(bytes("bloc"), PronoteCrypto.decrypt(valid, key, PronoteCrypto.zeroIv))
    }

    @Test
    fun endTimeFallsBackToGrid() {
        val grid = JSONArray()
        listOf("8h25", "9h20", "10h15", "11h25", "12h20").forEachIndexed { i, label -> grid.put(JSONObject().put("G", i).put("L", label)) }
        val general = JSONObject().put("ListeHeuresFin", JSONObject().put("_T", 24).put("V", grid))
        assertEquals("2026-11-18T09:20", PronotePolicy.endFromGrid("2026-11-18T08:30", 0, 2, general))
        assertEquals("2026-11-18T08:25", PronotePolicy.endFromGrid("2026-11-18T07:30", 0, 1, general))
        assertEquals("", PronotePolicy.endFromGrid("2026-11-18T08:30", 0, 2, JSONObject()))
        assertEquals("", PronotePolicy.endFromGrid("2026-11-18T08:30", 0, 0, general))
    }
}
