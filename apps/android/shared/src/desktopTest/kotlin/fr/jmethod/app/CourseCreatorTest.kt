package fr.jmethod.app

import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentStore
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.documents.CourseCreator
import fr.jmethod.app.documents.CourseFiles
import fr.jmethod.app.documents.DocType
import fr.jmethod.app.documents.PendingDocument
import fr.jmethod.app.domain.CourseEditing
import fr.jmethod.app.domain.NotebookCourses
import fr.jmethod.app.model.CourseSource
import fr.jmethod.app.model.NotebookImport
import fr.jmethod.app.model.PronoteEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Recréation d’un cours supprimé : toute création depuis le cahier de textes est vérifiée sur l’état
 * courant au moment d’écrire (suppression pendant une création, double soumission, création automatique
 * concurrente, synchronisation concurrente).
 */
class CourseCreatorTest {
    private val ticks = AtomicLong(Instant.parse("2026-11-18T19:00:00Z").toEpochMilli())
    private val clock = AppClock { Instant.ofEpochMilli(ticks.incrementAndGet()) }

    private class Fixture(val repository: AppRepository, val creator: CourseCreator, val files: CourseFiles)

    private fun fixture(): Fixture {
        val dir = Files.createTempDirectory("jm-creator").toFile()
        val repository = AppRepository(AppDocumentStore(java.io.File(dir, "data.json")), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined)
        val files = CourseFiles(dir)
        return Fixture(repository, CourseCreator(repository, clock, files), files)
    }

    private val source = CourseSource(NotebookCourses.SOURCE_KIND, "cdt-18", "ANGLAIS LV1", "2026-11-18")
    private val form = CourseEditing.Form("anglais", "Sans chapitre", "Cours de test", "2026-11-18", "")

    private fun document(): PendingDocument {
        val file = Files.createTempFile("pj", ".odt").toFile().apply { writeText("x") }
        return PendingDocument("fiche.odt", file, DocType.ODT, null, extractable = true)
    }

    @Test
    fun personalSheetsHaveNoImplicitProgrammeAndReferencesAreUniquePerSubject() = runBlocking {
        val f = fixture()
        val created = f.creator.create(CourseEditing.Form("maths", "", "", "2026-11-18", "", "C42"), null, emptyList())!!
        assertEquals("C42", created.course.displayReference)
        assertEquals("C42", created.course.title)
        assertTrue(f.repository.current().revisions.isEmpty())
        assertTrue(f.repository.current().programmes.isEmpty())
        assertNull(f.creator.create(CourseEditing.Form("maths", "", "", "2026-11-18", "", "c42"), null, emptyList()))
        assertNotNull(f.creator.create(CourseEditing.Form("francais", "", "", "2026-11-18", "", "C42"), null, emptyList()))
    }

    @Test
    fun samePronoteSessionCanExplicitlySupplySeveralSheetsWithoutCreatingJs() = runBlocking {
        val f = fixture()
        assertNotNull(f.creator.create(form, source, emptyList()))
        val seen = f.repository.current().notebookImports[source.sessionKey]
        assertNotNull(f.creator.create(form, source, emptyList(), CourseCreator.Expectation.Manual(seen)))
        assertEquals(2, f.repository.current().courses.size)
        assertTrue(f.repository.current().revisions.isEmpty())
        assertTrue(f.repository.current().courses.all { it.sources == listOf(source) })
    }

    @Test
    fun associatedResourcePreservesReferenceAndProgramme() = runBlocking {
        val f = fixture()
        val created = f.creator.create(CourseEditing.Form("anglais", "", "", "2026-11-18", "Mes notes", "C42"), null, emptyList())!!
        f.repository.update { fr.jmethod.app.domain.RevisionProgrammes.start(it, listOf(created.course.id), "2026-11-19", "p") }
        val before = f.repository.current().revisions
        val draft = fr.jmethod.app.documents.NotebookCourseDraft(sessionKey = source.sessionKey, ordinal = 1, subjectId = "anglais", subjectLabel = source.subjectLabel,
            date = source.date, chapter = "Sans chapitre", title = "Ressource", description = "", documents = emptyList(), notice = null)
        f.creator.associate(created.course.id, draft, listOf(document()))
        val after = f.repository.current()
        val leaf = after.courses.single()
        assertEquals("C42", leaf.displayReference)
        assertEquals("Mes notes", leaf.description)
        assertEquals(listOf("fiche.odt"), leaf.documents!!.map { it.name })
        assertTrue(f.files.exists(leaf.documents!!.first()))
        assertEquals(listOf(draft.source), leaf.sources)
        assertEquals(before, after.revisions)
        assertEquals(after, fr.jmethod.app.data.DataMigrations.migrateNotebookKeys(after))
    }

    @Test
    fun sameNamedDocumentsKeepTheirOwnSourceAndBytes() = runBlocking {
        val f = fixture()
        fun session(id: String, day: String) = fr.jmethod.app.pronote.SessionEntry(id, "lesson-$id", java.time.LocalDate.parse(day), "ANGLAIS LV1",
            listOf(fr.jmethod.app.pronote.NotebookContent("Cours $id", "", emptyList(), "Cours", listOf(fr.jmethod.app.pronote.NotebookAttachment(fr.jmethod.app.pronote.NotebookAttachment.Kind.FILE, "file-$id", "fiche.odt")))))
        val a = session("a", "2026-11-18"); val b = session("b", "2026-11-19")
        val originA = source.copy(sessionKey = a.key, date = a.date.toString(), ordinal = 1)
        val originB = source.copy(sessionKey = b.key, date = b.date.toString(), ordinal = 1)
        val fileA = document().also { it.file.writeText("contenu A") }
        val fileB = document().also { it.file.writeText("contenu B") }
        val created = f.creator.create(form, originA, listOf(fileA))!!
        f.repository.update { state -> state.copy(courses = state.courses.map { leaf -> leaf.copy(documents = leaf.documents!!.map { it.copy(sourceSessionKey = null) }) }) }
        val draft = fr.jmethod.app.documents.NotebookCourseDraft(sessionKey = originB.sessionKey, ordinal = 1, subjectId = "anglais", subjectLabel = originB.subjectLabel,
            date = originB.date, chapter = "Sans chapitre", title = "B", description = "", documents = emptyList(), notice = null)
        f.creator.associate(created.course.id, draft, listOf(fileB))
        val state = f.repository.current(); val leaf = state.courses.single(); val docs = leaf.documents!!
        assertEquals(listOf(originA.sessionKey, originB.sessionKey), docs.map { it.sourceSessionKey })
        assertEquals(listOf("contenu A", "contenu B"), docs.map { f.files.fileOf(it)!!.readText() })
        val snapshot = fr.jmethod.app.pronote.NotebookSnapshot(a.date, b.date, listOf(a,b), emptyList(), 0, 2)
        assertEquals(listOf("file-b"), fr.jmethod.app.domain.RevisionSession.notebookDocumentCandidates(leaf, docs[1], snapshot).map { it.id })
        assertTrue(fr.jmethod.app.domain.RevisionSession.notebookPart(leaf, snapshot)!!.contents!!.all { it.attachments.isEmpty() })
        val text = fr.jmethod.app.domain.ImportExport.export(state, clock.now())
        val accepted = fr.jmethod.app.domain.ImportExport.parse(text) as fr.jmethod.app.domain.ImportExport.Result.Accepted
        assertEquals(docs, accepted.applyTo(fr.jmethod.app.data.DataMigrations.emptyDocument()).courses.single().documents)
    }

    @Test
    fun uncheckedFirstSourceIsNotHiddenOrMistakenForSecondSourcesDocument() = runBlocking {
        val f = fixture()
        fun session(id: String, day: String) = fr.jmethod.app.pronote.SessionEntry(id, "lesson-$id", java.time.LocalDate.parse(day), "ANGLAIS LV1",
            listOf(fr.jmethod.app.pronote.NotebookContent("Cours $id", "", emptyList(), "Cours", listOf(fr.jmethod.app.pronote.NotebookAttachment(fr.jmethod.app.pronote.NotebookAttachment.Kind.FILE, "file-$id", "fiche.odt")))))
        val a = session("a", "2026-11-18"); val b = session("b", "2026-11-19")
        val created = f.creator.create(form, source.copy(sessionKey = a.key, date = a.date.toString(), ordinal = 1), emptyList())!!
        val draft = fr.jmethod.app.documents.NotebookCourseDraft(sessionKey = b.key, ordinal = 1, subjectId = "anglais", subjectLabel = b.subject,
            date = b.date.toString(), chapter = "Sans chapitre", title = "B", description = "", documents = emptyList(), notice = null)
        f.creator.associate(created.course.id, draft, listOf(document()))
        val leaf = f.repository.current().courses.single(); val saved = leaf.documents!!.single()
        val snapshot = fr.jmethod.app.pronote.NotebookSnapshot(a.date, b.date, listOf(a,b), emptyList(), 0, 2)
        val part = fr.jmethod.app.domain.RevisionSession.notebookPart(leaf, snapshot)!!
        assertEquals(listOf("file-a"), part.contents!!.flatMap { it.attachments }.map { it.id })
        assertEquals("file-b", fr.jmethod.app.domain.RevisionSession.notebookDocumentCandidates(leaf, saved, snapshot).single().id)
        val ambiguous = fr.jmethod.app.domain.RevisionSession.notebookDocumentCandidates(leaf, saved.copy(sourceSessionKey = null), snapshot)
        assertTrue(ambiguous.isEmpty())
        assertTrue(fr.jmethod.app.domain.RevisionSession.notebookDocumentCandidates(leaf, saved.copy(sourceSessionKey = null), snapshot.copy(sessions = listOf(b))).isEmpty())
        assertNull(ambiguous.singleOrNull())
    }

    @Test
    fun addingAnEmptySourcePreservesLegacyDocumentsKnownOrigin() = runBlocking {
        val f = fixture()
        val a = source.copy(sessionKey = "pn1:2026-11-18|anglais lv1|1", ordinal = 1)
        val created = f.creator.create(form, a, listOf(document()))!!
        f.repository.update { state -> state.copy(courses = state.courses.map { leaf -> leaf.copy(documents = leaf.documents!!.map { it.copy(sourceSessionKey = null) }) }) }
        val original = f.repository.current().courses.single().documents!!.single()
        val draft = fr.jmethod.app.documents.NotebookCourseDraft(sessionKey = "pn1:2026-11-19|anglais lv1|1", ordinal = 1, subjectId = "anglais", subjectLabel = "ANGLAIS LV1",
            date = "2026-11-19", chapter = "Sans chapitre", title = "B", description = "", documents = emptyList(), notice = null)
        f.creator.associate(created.course.id, draft, emptyList())
        val leaf = f.repository.current().courses.single(); val saved = leaf.documents!!.single()
        assertEquals(original.id, saved.id)
        assertEquals(original.file, saved.file)
        assertEquals(a.sessionKey, saved.sourceSessionKey)
        assertEquals(a.sessionKey, fr.jmethod.app.domain.RevisionSession.documentSourceKey(leaf, saved))
        assertEquals(2, leaf.sources!!.size)
        assertTrue(f.files.exists(saved))
    }

    @Test
    fun deletionDuringPreparationIsNeverUndone() = runBlocking {
        val f = fixture()
        // Le formulaire est préparé alors que la séance n’a jamais été importée…
        val seen = f.repository.current().notebookImports[source.sessionKey]
        assertNull(seen)
        // …pendant ce temps un cours est créé puis supprimé (séance `dismissed`).
        val other = f.creator.create(form, source, emptyList(), CourseCreator.Expectation.Manual(null))!!
        f.creator.delete(other.course.id)
        assertEquals(NotebookImport.DISMISSED, f.repository.current().notebookImports.getValue(source.sessionKey).status)
        // La soumission préparée sur l’état périmé n’écrit rien et ne laisse aucun fichier.
        val late = f.creator.create(form, source, listOf(document()), CourseCreator.Expectation.Manual(seen))
        assertNull(late)
        assertNotNull(f.creator.lastRefusal)
        assertTrue(f.repository.current().courses.isEmpty())
        assertTrue(f.files.courseDir("course-x").listFiles().isNullOrEmpty())
        // Bouton manuel volontaire après suppression : autorisé (état vu = dismissed).
        val again = f.creator.create(form, source, emptyList(), CourseCreator.Expectation.Manual(f.repository.current().notebookImports[source.sessionKey]))
        assertNotNull(again)
    }

    @Test
    fun doubleSubmissionCreatesOneCourse() = runBlocking {
        val f = fixture()
        val results = (1..2).map { async(Dispatchers.Default) { f.creator.create(form, source, listOf(document()), CourseCreator.Expectation.Manual(null)) } }.awaitAll()
        assertEquals(1, results.count { it != null })
        val state = f.repository.current()
        assertEquals(1, state.courses.size)
        assertEquals(state.courses.single().id, state.notebookImports.getValue(source.sessionKey).courseId)
        // Les fichiers de la soumission refusée sont supprimés.
        val refusedDirs = java.io.File(f.files.courseDir("x").parentFile, "").listFiles()!!.map { it.name }
        assertEquals(listOf(state.courses.single().id), refusedDirs)
    }

    @Test
    fun automaticCreationRechecksSettingAndImportsWhenWriting() = runBlocking {
        val f = fixture()
        // Réglage désactivé au moment d’écrire : rien.
        assertNull(f.creator.create(form, source, emptyList(), CourseCreator.Expectation.Automatic))
        f.repository.update { it.copy(settings = it.settings.copy(notebookAutoCreate = true, notebookAutoCreateSince = "2026-11-12")) }
        val created = f.creator.create(form, source, emptyList(), CourseCreator.Expectation.Automatic)!!
        // Supprimé pendant que la passe automatique continue : la séance n’est jamais recréée.
        f.creator.delete(created.course.id)
        assertNull(f.creator.create(form, source, emptyList(), CourseCreator.Expectation.Automatic))
        assertTrue(f.repository.current().courses.isEmpty())
    }

    @Test
    fun concurrentSyncNeverRestoresADeletedCourse() = runBlocking {
        val f = fixture()
        val created = f.creator.create(form, source, emptyList(), CourseCreator.Expectation.Manual(null))!!
        val event = PronoteEvent("e1", "ANGLAIS", "anglais", "2026-11-19", "08:30", "09:25", isCancelled = false)
        // Synchronisations (premier plan et arrière-plan) et suppression entremêlées : chaque écriture
        // repart de l’état courant, aucune ne réécrit une copie antérieure à la suppression.
        listOf(
            async(Dispatchers.Default) { repeat(20) { f.repository.update { it.copy(pronoteEvents = listOf(event)) } } },
            async(Dispatchers.Default) { f.creator.delete(created.course.id) },
            async(Dispatchers.Default) { repeat(20) { f.repository.update { it.copy(settings = it.settings.copy(lastPronoteSync = "2026-11-18T19:00:00.000Z")) } } },
        ).awaitAll()
        val state = f.repository.current()
        assertTrue(state.courses.isEmpty())
        assertTrue(state.revisions.isEmpty())
        assertEquals(listOf(event), state.pronoteEvents)
        assertEquals(NotebookImport.DISMISSED, state.notebookImports.getValue(source.sessionKey).status)
    }
}
