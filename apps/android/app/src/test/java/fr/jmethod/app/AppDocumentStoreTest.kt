package fr.jmethod.app

import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.data.AppDocumentStore
import fr.jmethod.app.data.DataMigrations
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.pronote.BridgeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppDocumentStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun atomicWriteRoundTripsWithoutTemporaryFile() {
        val file = File(folder.root, "jmethod-data.json")
        val store = AppDocumentStore(file)
        assertTrue(store.read() is AppDocumentStore.ReadResult.Missing)
        val course = fr.jmethod.app.model.Course("course-1", "maths", "Chapitre", 1, "Titre", "2026-11-17", null, "2026-11-17")
        val document = DataMigrations.emptyDocument().copy(
            courses = listOf(course),
            revisions = fr.jmethod.app.domain.AdaptiveAlgorithm.generateInitialRevisions(course, fr.jmethod.app.model.DEFAULT_INTERVALS),
        )
        store.write(document)
        store.write(document.copy(settings = document.settings.copy(lastPronoteSync = "2026-11-17T19:00:00.000Z")))
        val loaded = (store.read() as AppDocumentStore.ReadResult.Loaded).document
        assertEquals("2026-11-17T19:00:00.000Z", loaded.settings.lastPronoteSync)
        assertEquals(document.revisions, loaded.revisions)
        assertFalse(File(folder.root, "jmethod-data.json.tmp").exists())
        // Champs optionnels absents (comme `undefined` côté web), pas de jeton dans le document.
        val text = file.readText()
        assertFalse(text.contains("null"))
        assertFalse(text.contains("jeton"))
    }

    @Test
    fun firstLaunchDocumentHasNoFictionalData() {
        val document = DataMigrations.emptyDocument()
        assertTrue(document.initialized)
        assertTrue(document.courses.isEmpty())
        assertTrue(document.revisions.isEmpty())
        assertTrue(document.pronoteEvents.isEmpty())
        assertEquals(fr.jmethod.app.model.DEFAULT_SUBJECTS, document.subjects)
        assertEquals(AppSettings(), document.settings)
        assertEquals(document, DataMigrations.migrate(document))
    }

    @Test
    fun corruptedFileIsKeptAside() {
        val file = File(folder.root, "jmethod-data.json")
        file.writeText("{tronqué")
        val result = AppDocumentStore(file).read()
        assertTrue(result is AppDocumentStore.ReadResult.Corrupted)
        assertTrue((result as AppDocumentStore.ReadResult.Corrupted).keptAs.exists())
        assertFalse(file.exists())
    }

    @Test
    fun webStorageAndExportAreReadable() {
        val web = """
            {"version":"1.0","exportDate":"2026-11-17T19:00:00.000Z",
             "courses":[{"id":"c","subjectId":"maths","chapter":"A","courseNumber":1,"title":"T","date":"2026-11-17","createdAt":"2026-11-17"}],
             "revisions":[{"id":"c-rev-0","courseId":"c","stepIndex":0,"stepLabel":"J0","scheduledDate":"2026-11-17","status":"overdue","phase":"encodage","adjustedIntervalDays":0,"futur":true}],
             "settings":{"sensitivity":0.5}}
        """.trimIndent()
        val document = AppDocumentCodec.decodeExport(web)
        assertNotNull(document)
        document!!
        assertTrue(document.initialized)
        assertEquals(RevisionStatus.OVERDUE, document.revisions.single().status)
        assertEquals(AppSettings().copy(sensitivity = 0.5), document.settings)
        assertEquals(10, document.subjects.size)
        assertNull(AppDocumentCodec.decodeExport("""{"courses":[]}"""))
        assertNull(AppDocumentCodec.decodeExport("pas du json"))
    }

    @Test
    fun bridgeRejectsMalformedOrOversizedMessages() {
        val attempt = BridgeGate.Attempt("n".repeat(36), "1234", "https://school.index-education.net")
        val url = "https://school.index-education.net/pronote/eleve.html"
        val accept = { raw: String? -> BridgeGate.accept(raw, attempt, attempt.origin, true, url) }
        assertNull(accept(null))
        assertNull(accept("pas du json"))
        assertNull(accept("x".repeat(BridgeGate.MAX_MESSAGE + 1)))
        val base = """{"version":1,"nonce":"${attempt.nonce}","origin":"${attempt.origin}","url":"$url""""
        assertNull(accept("$base,\"type\":\"inconnu\"}"))
        assertNull(accept("""{"version":2,"nonce":"${attempt.nonce}","origin":"${attempt.origin}","url":"$url","type":"authenticated"}"""))
        assertNull(accept("$base,\"type\":\"qr\",\"pin\":\"1234\",\"image\":\"data:image/jpeg;base64,AA\"}"))
        assertEquals(BridgeGate.Event.Authenticated, accept("$base,\"type\":\"authenticated\"}"))
        assertEquals(BridgeGate.Event.Failure("AUTOMATION_FAILED"), accept("$base,\"type\":\"error\",\"code\":\"<script>\"}"))
        assertEquals(BridgeGate.Event.Progress(""), accept("$base,\"type\":\"progress\",\"stage\":\"autre\"}"))
    }
}
