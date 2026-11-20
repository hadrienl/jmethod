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
