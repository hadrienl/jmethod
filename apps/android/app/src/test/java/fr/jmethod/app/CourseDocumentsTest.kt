package fr.jmethod.app

import fr.jmethod.app.documents.CourseFiles
import fr.jmethod.app.documents.DocType
import fr.jmethod.app.domain.ImportExport
import fr.jmethod.app.model.AppDocument
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.model.CourseSource
import fr.jmethod.app.model.NotebookImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Documents des cours : stockage privé, suppression, export et restauration des nouveaux champs. */
class CourseDocumentsTest {
    @Test
    fun storesDeletesAndRefusesPathsOutsideDocuments() {
        val root = Files.createTempDirectory("jm-files").toFile()
        val files = CourseFiles(root)
        val source = Files.createTempFile("doc", ".pdf").toFile().apply { writeText("%PDF-1.4") }
        val stored = files.store("course-1", source, "Chapitre 2.pdf", DocType.PDF, "La Révolution")
        assertTrue(stored.file.startsWith("documents/course-1/doc-"))
        assertTrue(stored.file.endsWith(".pdf"))
        assertEquals("application/pdf", stored.mime)
        assertEquals(8L, stored.size)
        assertTrue(files.exists(stored))
        // Chemin venu d’une restauration : jamais hors de `documents/`.
        assertNull(files.fileOf(stored.copy(file = "../jmethod-data.json")))
        assertNull(files.fileOf(stored.copy(file = "documents/../../x")))
        files.deleteCourse("course-1")
        assertFalse(files.exists(stored))
        root.deleteRecursively()
    }

    @Test
    fun newFieldsSurviveExportAndRestore() {
        val course = Course(
            "course-1", "maths", "Chapitre 4", 1, "Les fractions", "2026-11-18", null, "2026-11-18",
            source = CourseSource("pronote-notebook", "pn1:2026-11-18|mathematiques|1", "MATHEMATIQUES", "2026-11-18", 1),
            documents = listOf(CourseDocument("doc-1", "Les fractions.odt", DocType.ODT.mime, 1941, "documents/course-1/doc-1.odt", "Les fractions")),
        )
        val imports = mapOf(
            "pn1:2026-11-18|mathematiques|1" to NotebookImport(NotebookImport.CREATED, "course-1", "2026-11-18T19:02:11Z"),
            "pn1:2026-11-13|mathematiques|1" to NotebookImport(NotebookImport.DISMISSED, "course-x", "2026-11-14T18:00:00Z"),
        )
        val document = AppDocument(
            courses = listOf(course), notebookImports = imports,
            settings = AppSettings(notebookAutoCreate = true, notebookAutoCreateSince = "2026-11-04"),
        )
        val text = ImportExport.export(document, Instant.parse("2026-11-18T19:00:00Z"))
        assertTrue(text.contains("\"notebookImports\""))
        assertTrue(text.contains("\"extractedTitle\": \"Les fractions\""))
        val restored = (ImportExport.parse(text) as ImportExport.Result.Accepted).applyTo(AppDocument())
        assertEquals(listOf(course), restored.courses)
        assertEquals(imports, restored.notebookImports)
        assertEquals(true, restored.settings.notebookAutoCreate)
        assertEquals("2026-11-04", restored.settings.notebookAutoCreateSince)
        // Une sauvegarde web sans ces clés garde les séances importées de l’appareil.
        val web = (ImportExport.parse("""{"courses":[],"revisions":[]}""") as ImportExport.Result.Accepted).applyTo(document)
        assertEquals(imports, web.notebookImports)
        // Sans cours importé, l’export reste celui du web (pas de clé ajoutée).
        assertFalse(ImportExport.export(AppDocument(), Instant.EPOCH).contains("notebook"))
    }
}
