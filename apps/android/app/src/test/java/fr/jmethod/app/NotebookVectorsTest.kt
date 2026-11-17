package fr.jmethod.app

import fr.jmethod.app.pronote.Credentials
import fr.jmethod.app.pronote.FileTransport
import fr.jmethod.app.pronote.HomeworkItem
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookContent
import fr.jmethod.app.pronote.NotebookRules
import fr.jmethod.app.pronote.PronoteClient
import fr.jmethod.app.pronote.PronoteCrypto
import fr.jmethod.app.pronote.PronoteException
import fr.jmethod.app.pronote.PronotePolicy
import fr.jmethod.app.pronote.SessionEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/** Rejoue `native/shared/notebook-vectors.json` (jalon 5, cahier de textes en lecture seule). */
class NotebookVectorsTest {
    private val vectors: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("notebook-vectors.json") ?: error("Vecteurs absents")
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
    private fun date(value: String) = LocalDate.parse(value)
    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    /** Comparaison structurelle de deux valeurs JSON (l’ordre des clés ne compte pas). */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keyNames() == b.keyNames() && a.keyNames().all { same(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toLong() == b.toLong()
        else -> a == b
    }

    @Test
    fun windowAndWeeks() {
        val window = vectors.getJSONObject("window")
        val computed = NotebookRules.window(date(window.getString("today")), LocalDate.of(2026, 8, 31))
        assertEquals(window.getString("from"), computed.from.toString())
        assertEquals(window.getString("to"), computed.to.toString())
        assertEquals(window.getJSONObject("domaine").getString("V"), computed.domaine)
        for (case in vectors.getJSONArray("weeks").objects()) {
            val expected = case.getJSONObject("expected")
            val w = NotebookRules.window(date(case.getString("today")), date(case.getString("premierLundi")))
            assertEquals(expected.getString("from"), w.from.toString())
            assertEquals(expected.getString("to"), w.to.toString())
            assertEquals(expected.getInt("firstWeek"), w.firstWeek)
            assertEquals(expected.getInt("lastWeek"), w.lastWeek)
            assertEquals(expected.getJSONObject("domaine").getString("V"), w.domaine)
            assertEquals(28, java.time.temporal.ChronoUnit.DAYS.between(w.from, w.to).toInt() + 1)
        }
    }

    @Test
    fun dates() {
        for (case in vectors.getJSONArray("dates").objects()) {
            val raw = case.str("raw")
            assertEquals("date $raw", case.str("expectedDate"), NotebookRules.parseDate(raw)?.toString())
        }
    }

    @Test
    fun htmlToSafeText() {
        val cases = vectors.getJSONObject("html").getJSONArray("cases").objects()
        for (case in cases) {
            val expected = case.getJSONObject("expected")
            val result = NotebookRules.safeText(case.getString("html"))
            assertEquals(case.getString("name"), expected.getString("text"), result.text)
            val links = expected.getJSONArray("links").objects().map { it.getString("label") to it.getString("url") }
            assertEquals(case.getString("name"), links, result.links.map { it.label to it.url })
        }
        assertEquals(21, cases.size)
        assertEquals(253, fr.jmethod.app.pronote.HtmlEntities.table.size)
        val table = vectors.getJSONObject("html").getJSONObject("entityTable")
        table.keys().forEach { assertEquals(it, table.getInt(it), fr.jmethod.app.pronote.HtmlEntities.table[it]) }
    }

    @Test
    fun linkUrls() {
        for (case in vectors.getJSONArray("linkUrls").objects()) {
            assertEquals(case.getString("url"), case.str("accepted"), NotebookRules.validLinkUrl(case.getString("url")))
        }
    }

    private fun attachmentJson(a: NotebookAttachment) = JSONObject().put("kind", a.kind.wire).put("id", a.id).put("name", a.name)
        .also { if (a.url != null) it.put("url", a.url) }
    private fun linksJson(links: List<fr.jmethod.app.pronote.NotebookLink>) = JSONArray(links.map { JSONObject().put("label", it.label).put("url", it.url) })
    private fun contentJson(c: NotebookContent) = JSONObject().put("title", c.title).put("description", c.description)
        .put("links", linksJson(c.links)).put("category", c.category).put("attachments", JSONArray(c.attachments.map(::attachmentJson)))
    private fun sessionJson(s: SessionEntry) = JSONObject().put("id", s.id).put("lessonId", s.lessonId).put("date", s.date.toString())
        .put("subject", s.subject).put("contents", JSONArray(s.contents.map(::contentJson)))
    private fun homeworkJson(h: HomeworkItem) = JSONObject().put("id", h.id).put("dueDate", h.dueDate.toString()).put("subject", h.subject)
        .put("description", h.description).put("links", linksJson(h.links)).put("done", h.done)
        .put("attachments", JSONArray(h.attachments.map(::attachmentJson)))

    @Test
    fun notebookModel() {
        val notebook = vectors.getJSONObject("notebook")
        val responses = notebook.getJSONObject("responses")
        val subjects = notebook.getJSONObject("timetableSubjects").let { o -> o.keyNames().associateWith { o.getString(it) } }
        val snapshot = NotebookRules.parse(
            NotebookRules.window(date(notebook.getString("today")), LocalDate.of(2026, 8, 31)),
            responses.getJSONObject("PageCahierDeTexte89").getJSONObject("dataSec").getJSONObject("data"),
            responses.getJSONObject("PageCahierDeTexte88").getJSONObject("dataSec").getJSONObject("data"),
            subjects,
        )
        val expected = notebook.getJSONObject("expected")
        assertEquals(expected.getString("from"), snapshot.from.toString())
        assertEquals(expected.getString("to"), snapshot.to.toString())
        val sessions = expected.getJSONArray("sessions").objects()
        assertEquals(sessions.map { it.getString("id") }, snapshot.sessions.map { it.id })
        sessions.zip(snapshot.sessions).forEach { (e, s) -> assertTrue("séance ${s.id} : ${sessionJson(s)}", same(e, sessionJson(s))) }
        val homework = expected.getJSONArray("homework").objects()
        assertEquals(homework.map { it.getString("id") }, snapshot.homework.map { it.id })
        homework.zip(snapshot.homework).forEach { (e, h) -> assertTrue("devoir ${h.id} : ${homeworkJson(h)}", same(e, homeworkJson(h))) }
        assertEquals(expected.getInt("skippedEntries"), snapshot.skippedEntries)
        assertEquals(expected.getInt("attachmentCount"), snapshot.attachmentCount)
    }

    @Test
    fun downloadIdentifiersAndUrls() {
        val download = vectors.getJSONObject("download")
        val key = PronoteCrypto.unhex(download.getString("sessionKeyHex"))
        val iv = PronoteCrypto.unhex(download.getString("ivHex"))
        for (case in download.getJSONArray("cases").objects()) {
            val attachment = case.getJSONObject("attachment")
            val id = attachment.getString("N")
            val name = attachment.optString("L", "")
            assertEquals(case.getString("plaintextUtf8"), String(NotebookRules.plaintext(id), Charsets.UTF_8))
            assertEquals(case.getString("padded1Hex"), PronoteCrypto.hex(NotebookRules.pkcs7(NotebookRules.plaintext(id))).lowercase())
            val encrypted = NotebookRules.encryptedId(id, key, iv)
            assertEquals(case.getString("ciphertextHex"), encrypted)
            assertEquals(case.getString("singlePaddingVariantCiphertextHex"), NotebookRules.encryptedId(id, key, iv, doublePadding = false))
            assertEquals(case.getString("encodedName"), NotebookRules.encodeURIComponent(name))
            assertEquals(case.getString("expectedURL"), NotebookRules.downloadUrl(download.getString("pronoteRoot"), encrypted, name, download.getLong("sessionH")))
            assertEquals(case.getString("saveAsName"), NotebookRules.saveAsName(name))
            assertTrue(NotebookRules.isDownloadableId(id))
        }
        for (i in 0 until download.getJSONArray("rejectedIds").length()) {
            assertFalse(NotebookRules.isDownloadableId(download.getJSONArray("rejectedIds").getString(i)))
        }
        for (case in download.getJSONArray("saveAsNames").objects()) {
            assertEquals(case.getString("expected"), NotebookRules.saveAsName(case.getString("name")))
        }
        assertEquals(download.getLong("maxBytes"), NotebookRules.MAX_DOWNLOAD_BYTES)
    }

    @Test
    fun safetyRefusesWritesAndOutOfBoundsReads() {
        val safety = vectors.getJSONObject("safety")
        val allowed = safety.getJSONArray("readOnlyFunctionIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        assertEquals(allowed, PronotePolicy.allowedFunctions)
        val rejected = safety.getJSONArray("rejectedFunctionExamples")
        for (i in 0 until rejected.length()) {
            try {
                PronotePolicy.checkCall(rejected.getString(i), null, null)
                fail(rejected.getString(i))
            } catch (error: PronoteException) {
                assertEquals("readonly", error.code)
            }
        }
        fun domaine(v: String) = JSONObject().put("domaine", JSONObject().put("_T", 8).put("V", v))
        PronotePolicy.checkCall("PageCahierDeTexte", 89, domaine("[10..14]"))
        PronotePolicy.checkCall("PageCahierDeTexte", 88, domaine("[1..6]"))
        val refused = listOf(
            Triple(16, domaine("[10..14]"), "autre onglet"),
            Triple(89, domaine("[10..16]"), "plus de six semaines"),
            Triple(89, domaine("[0..3]"), "semaine 0"),
            Triple(89, domaine("[5..4]"), "bornes inversées"),
            Triple(89, domaine("[10..14]").put("ressource", JSONObject()), "clé en trop"),
            Triple(89, JSONObject().put("domaine", JSONObject().put("_T", 7).put("V", "[10..14]")), "type"),
            Triple(89, JSONObject(), "sans domaine"),
        )
        for ((tab, data, label) in refused) {
            try {
                PronotePolicy.checkCall("PageCahierDeTexte", tab, data)
                fail(label)
            } catch (error: PronoteException) {
                assertEquals(label, "readonly", error.code)
            }
        }
    }

    private fun clientFor(server: FakePronoteServer): PronoteClient {
        val notebook = vectors.getJSONObject("notebook").getJSONObject("responses")
        server.notebookResponses[89] = notebook.getJSONObject("PageCahierDeTexte89").getJSONObject("dataSec").getJSONObject("data")
        server.notebookResponses[88] = notebook.getJSONObject("PageCahierDeTexte88").getJSONObject("dataSec").getJSONObject("data")
        val credentials = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, server.token, "uuid-test")
        return PronoteClient(server.transport, credentials).also { it.login(PronoteClient.Mode.TOKEN) }
    }

    @Test
    fun studentRequestsMatchVectors() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val snapshot = clientFor(server).notebook(LocalDate.of(2026, 11, 18))
        val expected = vectors.getJSONObject("requests").getJSONArray("student").objects()
        assertEquals(expected.size, server.notebookPayloads.size)
        expected.zip(server.notebookPayloads).forEach { (e, actual) -> assertTrue(actual.toString(), same(e.getJSONObject("inner"), actual)) }
        assertEquals(5, snapshot.sessions.size)
        assertEquals(5, snapshot.homework.size)
        // Aucune écriture : seules des fonctions de lecture ont été appelées.
        assertTrue(server.calls.all { it in PronotePolicy.allowedFunctions && !it.startsWith("Saisie") })
    }

    @Test
    fun parentRequestsCarryTheSelectedChild() {
        val server = FakePronoteServer(token = "jeton-enregistre", parent = true)
        val client = clientFor(server)
        client.selectChild("enfant-b")
        client.notebook(LocalDate.of(2026, 11, 18))
        val expected = vectors.getJSONObject("requests").getJSONObject("parent")
        val child = expected.getString("selectedChildId")
        expected.getJSONArray("calls").objects().zip(server.notebookPayloads).forEach { (e, actual) ->
            val inner = JSONObject(e.getJSONObject("inner").toString().replace(child, "enfant-b"))
            assertTrue(actual.toString(), same(inner, actual))
        }
    }

    @Test
    fun unauthorizedTabsAreRefusedLocally() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        server.tabs = JSONArray().put(JSONObject().put("G", 16)).put(JSONObject().put("G", 89))
        val snapshot = clientFor(server).notebook(LocalDate.of(2026, 11, 18))
        // 88 absent : contenus seuls, aucune requête envoyée pour le travail à faire.
        assertEquals(listOf(89), server.notebookPayloads.map { it.getJSONObject("Signature").getInt("onglet") })
        assertTrue(snapshot.homework.isEmpty())
        assertFalse(snapshot.homeworkAvailable)

        val without = FakePronoteServer(token = "jeton-enregistre").apply { tabs = JSONArray().put(JSONObject().put("G", 16)) }
        try {
            clientFor(without).notebook(LocalDate.of(2026, 11, 18))
            fail("onglet 89 absent")
        } catch (error: PronoteException) {
            assertEquals("notebook-unavailable", error.code)
        }
        assertTrue(without.notebookPayloads.isEmpty())
    }

    @Test
    fun downloadUsesSessionCookiesAndLimits() {
        val server = FakePronoteServer(token = "jeton-enregistre")
        val client = clientFor(server)
        val target = Files.createTempFile("pj", ".pdf").toFile()
        var seenUrl = ""
        var seenHeaders = emptyMap<String, String>()
        var seenMax = 0L
        val ok = FileTransport { url, headers, max, file -> seenUrl = url; seenHeaders = headers; seenMax = max; file.writeText("%PDF"); 200 }
        client.download("pj-file-01", "Fiche d’exercices (1) – été.pdf", ok, target)
        assertTrue(seenUrl.startsWith("${server.origin}/pronote/FichiersExternes/"))
        assertTrue(seenUrl.endsWith("/Fiche%20d%E2%80%99exercices%20(1)%20%E2%80%93%20%C3%A9t%C3%A9.pdf?Session=424242"))
        val expectedId = NotebookRules.encryptedId("pj-file-01", server.sessionKey, server.sessionIv)
        assertTrue(seenUrl.contains("/$expectedId/"))
        assertEquals("sid=abc", seenHeaders["Cookie"])
        assertEquals(NotebookRules.MAX_DOWNLOAD_BYTES, seenMax)
        assertEquals("%PDF", target.readText())

        fun failing(status: Int): String = try {
            client.download("pj-file-01", "a.pdf", FileTransport { _, _, _, _ -> status }, target)
            "ok"
        } catch (error: PronoteException) {
            error.message
        }
        assertEquals("Ce document dépasse la limite de 20 Mo.", failing(413))
        assertEquals("Document indisponible. Actualise le cahier de textes puis réessaie.", failing(404))
        assertEquals("Document indisponible. Actualise le cahier de textes puis réessaie.", failing(302))
        // Identifiant refusé : aucune requête.
        var called = false
        try {
            client.download("a b", "x.pdf", FileTransport { _, _, _, _ -> called = true; 200 }, target)
            fail()
        } catch (_: PronoteException) {
        }
        assertFalse(called)
        target.delete()
    }

    @Test
    fun nativeFileTransportStopsAboveTheLimit() {
        val sizes = vectors.getJSONObject("download").getJSONArray("sizeCases").objects()
        for (case in sizes) {
            val bytes = case.getLong("bytes")
            assertEquals(case.getBoolean("accepted"), bytes <= NotebookRules.MAX_DOWNLOAD_BYTES)
        }
        assertNull(NotebookRules.parseDate("18/11"))
    }
}
