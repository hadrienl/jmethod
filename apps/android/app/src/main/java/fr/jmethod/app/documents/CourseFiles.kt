package fr.jmethod.app.documents

import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.domain.CourseEditing
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.model.CourseSource
import fr.jmethod.app.model.NotebookImport
import java.io.File
import java.util.UUID

/**
 * Documents des cours, copiés dans le stockage privé non cache (`filesDir/documents/<courseId>/`),
 * exclu des sauvegardes. La déconnexion Pronote ne les supprime pas ; la suppression du cours, si.
 */
class CourseFiles(private val root: File) {
    fun courseDir(courseId: String) = File(root, "documents/${safe(courseId)}")

    fun fileOf(document: CourseDocument): File? {
        val file = File(root, document.file).canonicalFile
        // Chemin relatif venu d’une restauration : il doit rester sous `documents/`.
        val base = File(root, "documents").canonicalFile
        return file.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    fun exists(document: CourseDocument) = fileOf(document)?.isFile == true

    /** Copie [source] (cache temporaire) dans le dossier du cours et renvoie ses métadonnées. */
    fun store(courseId: String, source: File, name: String, type: DocType, extractedTitle: String?): CourseDocument {
        val id = "doc-" + UUID.randomUUID().toString()
        val extension = DocType.extensionOf(name) ?: type.extension
        val relative = "documents/${safe(courseId)}/$id" + if (extension.isNotEmpty()) ".$extension" else ""
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        return CourseDocument(id, name, DocType.mimeOf(name, type), target.length(), relative, extractedTitle)
    }

    /** Remplace le contenu d’un document à retélécharger. */
    fun replace(document: CourseDocument, source: File): CourseDocument? {
        val target = fileOf(document) ?: return null
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        return document.copy(size = target.length())
    }

    fun deleteCourse(courseId: String) {
        courseDir(courseId).deleteRecursively()
    }

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/** Document téléchargé du cahier de textes, en attente de création du cours (cache temporaire). */
data class PendingDocument(
    val name: String,
    val file: File,
    val type: DocType,
    val extractedTitle: String?,
    val selected: Boolean = true,
    /** Type extractible détecté (le premier est le document principal). */
    val extractable: Boolean = false,
)

/**
 * Création d’un cours, commune au formulaire et à la création automatique : documents copiés dans
 * le dossier du cours, `source` et `notebookImports` enregistrés avec le cours, en une seule écriture.
 */
class CourseCreator(private val repository: AppRepository, private val clock: AppClock, private val files: CourseFiles) {
    suspend fun create(form: CourseEditing.Form, source: CourseSource?, documents: List<PendingDocument>): CourseEditing.Created? {
        if (!CourseEditing.isValid(form)) return null
        val now = clock.now()
        val courseId = "course-${now.toEpochMilli()}"
        val stored = documents.filter { it.selected && it.file.isFile }.take(fr.jmethod.app.domain.NotebookCourses.MAX_DOCUMENTS).mapNotNull {
            runCatching { files.store(courseId, it.file, it.name, it.type, it.extractedTitle) }.getOrNull()
        }
        var created: CourseEditing.Created? = null
        repository.update { document ->
            // Création automatique concurrente : une séance déjà importée ne l’est jamais deux fois.
            if (source != null && document.notebookImports[source.sessionKey]?.status == NotebookImport.CREATED &&
                document.courses.any { it.id == document.notebookImports[source.sessionKey]?.courseId }
            ) {
                return@update document
            }
            val result = CourseEditing.create(document.courses, document.revisions, document.settings.intervals, form, now, AppClock.ZONE)
                ?: return@update document
            val course = result.course.copy(source = source, documents = stored.takeIf { it.isNotEmpty() })
            created = result.copy(course = course, courses = listOf(course) + result.courses.drop(1))
            document.copy(
                courses = listOf(course) + result.courses.drop(1),
                revisions = result.revisions,
                notebookImports = if (source == null) document.notebookImports else document.notebookImports +
                    (source.sessionKey to NotebookImport(NotebookImport.CREATED, course.id, AppDocumentCodec.isoMillis(now).dropLast(5) + "Z")),
            )
        }
        if (created == null) files.deleteCourse(courseId)
        return created
    }

    /** Suppression : fichiers effacés et séance marquée `dismissed` (jamais recréée automatiquement). */
    suspend fun delete(courseId: String): CourseEditing.Deleted? {
        var deleted: CourseEditing.Deleted? = null
        repository.update { document ->
            val course = document.courses.firstOrNull { it.id == courseId }
            val result = CourseEditing.delete(document.courses, document.revisions, courseId)
            deleted = result
            val key = course?.source?.sessionKey
            document.copy(
                courses = result.courses,
                revisions = result.revisions,
                notebookImports = if (key == null) document.notebookImports else document.notebookImports +
                    (key to NotebookImport(NotebookImport.DISMISSED, courseId, AppDocumentCodec.isoMillis(clock.now()).dropLast(5) + "Z")),
            )
        }
        files.deleteCourse(courseId)
        return deleted
    }
}

/** Formulaire prérempli depuis une séance du cahier de textes (`notebook-to-course.md`, section 3). */
data class NotebookCourseDraft(
    val sessionKey: String,
    /** `null` : matière non reconnue, l’utilisateur la choisit. */
    val subjectId: String?,
    val subjectLabel: String,
    val date: String,
    val chapter: String,
    val title: String,
    val description: String,
    val documents: List<PendingDocument>,
    /** Message affiché dans le formulaire (document non téléchargé, matière à choisir). */
    val notice: String?,
) {
    val source get() = CourseSource(fr.jmethod.app.domain.NotebookCourses.SOURCE_KIND, sessionKey, subjectLabel, date)
}
