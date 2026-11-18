package fr.jmethod.app

import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentStore
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.documents.CourseCreator
import fr.jmethod.app.documents.CourseFiles
import fr.jmethod.app.documents.DocType
import fr.jmethod.app.domain.RevisionSession
import fr.jmethod.app.domain.RevisionSession.Nearby
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.model.CourseSource
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookContent
import fr.jmethod.app.pronote.NotebookRules
import fr.jmethod.app.pronote.NotebookSnapshot
import fr.jmethod.app.pronote.SessionEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

/** Documents proches (`notebook-to-course.md`, section 8.5). Données synthétiques. */
class NearbyDocumentsTest {
    private val subjects = listOf(Subject("anglais", "Anglais", "#db2777"), Subject("maths", "Mathématiques", "#2563eb"))

    private fun source(date: String, label: String = "ANGLAIS LV1") = CourseSource("pronote-notebook", NotebookRules.sessionKey(date, label, 1), label, date, 1)

    private fun doc(name: String, title: String? = null) = CourseDocument("d-$name", name, "application/pdf", 10, "documents/c/$name", title)

    private fun course(id: String, date: String, subjectId: String = "anglais", source: CourseSource? = source(date), docs: List<CourseDocument>? = null) =
        Course(id, subjectId, "Sans chapitre", 1, "Cours $id", date, null, "2026-11-18", source, docs)

    private fun file(id: String, name: String) = NotebookAttachment(NotebookAttachment.Kind.FILE, id, name)

    private fun session(date: String, subject: String, vararg attachments: NotebookAttachment) =
        SessionEntry("n-$date-$subject", "l", LocalDate.parse(date), subject, listOf(NotebookContent("T", "", emptyList(), "", attachments.toList())))

    private fun snapshot(vararg sessions: SessionEntry) =
        NotebookSnapshot(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-11-25"), NotebookRules.withOrdinals(sessions.toList()), emptyList(), 0, 0)

    @Test
    fun sortedByDistanceThenLaterSessionThenOrigin() {
        val target = course("c04", "2026-11-04")
        val courses = listOf(
            target,
            course("c06", "2026-11-06", docs = listOf(doc("magazine.pdf", "Black Friday"), doc("mall.pdf"))),
            course("c03", "2026-11-03", docs = listOf(doc("avant.odt"))),
            course("c05", "2026-11-05", docs = listOf(doc("cours 3° 4 et 5 nov.odt"))),
            course("c20", "2026-11-20", docs = listOf(doc("trop loin.pdf"))),
            course("m05", "2026-11-05", subjectId = "maths", source = source("2026-11-05", "MATHEMATIQUES"), docs = listOf(doc("maths.pdf"))),
        )
        val nearby = RevisionSession.nearbyDocuments(target, courses, null, subjects)
        assertEquals(listOf("cours 3° 4 et 5 nov.odt", "avant.odt", "magazine.pdf", "mall.pdf"), nearby.map { it.name })
        assertEquals("Séance du 5 novembre", nearby.first().dayLine)
        assertEquals("Black Friday", nearby[2].title)
        assertEquals("c05", (nearby.first() as Nearby.FromCourse).courseId)
    }

    @Test
    fun excludesJoinedMissingLinksAndDuplicatesFromTheNotebook() {
        val target = course("c04", "2026-11-04", docs = listOf(doc("deja.pdf")))
        val c05 = course("c05", "2026-11-05", docs = listOf(doc("cours 3° 4 et 5 nov.odt"), doc("deja.pdf"), doc("absent.pdf")))
        val notebook = snapshot(
            session("2026-11-04", "ANGLAIS LV1", file("own", "propre.pdf")),
            session("2026-11-05", "ANGLAIS LV1", file("dup", "cours 3° 4 et 5 nov.odt"), file("new", "fiche.pdf"),
                NotebookAttachment(NotebookAttachment.Kind.LINK, "lien", "Vidéo", url = "https://example.org")),
            session("2026-11-05", "MATHEMATIQUES", file("m", "maths.pdf")),
            session("2026-11-02", "Anglais (LV1)", file("old", "avant.pdf")),
        )
        val nearby = RevisionSession.nearbyDocuments(target, listOf(target, c05), notebook, subjects, available = { it.name != "absent.pdf" })
        // Le document du cours d’abord, puis la pièce jointe du cahier de la même séance ; la séance du cours,
        // les liens, l’autre matière, le document déjà joint et le fichier absent sont exclus.
        assertEquals(listOf("cours 3° 4 et 5 nov.odt", "fiche.pdf", "avant.pdf"), nearby.map { it.name })
        assertTrue(nearby[0] is Nearby.FromCourse)
        assertEquals(NotebookRules.sessionKey("2026-11-05", "ANGLAIS LV1", 1), (nearby[1] as Nearby.FromNotebook).sessionKey)
        assertEquals("Séance du 2 novembre", nearby[2].dayLine)
    }

    @Test
    fun courseTypedByHandMatchesBySubject() {
        val typed = course("t", "2026-11-04", source = null)
        val nearby = RevisionSession.nearbyDocuments(
            typed, listOf(typed, course("c05", "2026-11-05", docs = listOf(doc("a.pdf")))),
            snapshot(session("2026-11-06", "ANGLAIS LV1", file("b", "b.pdf")), session("2026-11-06", "MATHEMATIQUES", file("m", "m.pdf"))),
            subjects,
        )
        assertEquals(listOf("a.pdf", "b.pdf"), nearby.map { it.name })
    }

    @Test
    fun attachCopiesTheFileWithoutTouchingTheOtherCourse(): Unit = runBlocking {
        val dir = Files.createTempDirectory("jm-attach").toFile()
        val repository = AppRepository(AppDocumentStore(File(dir, "data.json")), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined)
        val files = CourseFiles(dir)
        val creator = CourseCreator(repository, AppClock { Instant.parse("2026-11-18T19:00:00Z") }, files)
        val source = File(dir, "tmp.odt").apply { writeText("contenu") }
        val original = files.store("c05", source, "cours 3° 4 et 5 nov.odt", DocType.ODT, "Project 2")
        repository.update { it.copy(courses = listOf(course("c04", "2026-11-04"), course("c05", "2026-11-05", docs = listOf(original)))) }

        val copy = creator.attach("c04", files.fileOf(original)!!, original.name, DocType.ODT, original.extractedTitle, original.mime)
        assertNotNull(copy)
        assertNotEquals(original.id, copy!!.id)
        assertEquals(original.name, copy.name)
        assertEquals("Project 2", copy.extractedTitle)
        assertEquals("contenu", files.fileOf(copy)!!.readText())
        val after = repository.current().courses.associateBy { it.id }
        assertEquals(listOf(copy), after.getValue("c04").documents)
        assertEquals("Cours c04", after.getValue("c04").title)
        assertEquals(listOf(original), after.getValue("c05").documents)
        // Même nom déjà joint : refusé, aucun fichier en plus.
        assertNull(creator.attach("c04", files.fileOf(original)!!, original.name, DocType.ODT, null))
        assertEquals(1, files.courseDir("c04").listFiles()!!.size)
        dir.deleteRecursively()
    }
}
