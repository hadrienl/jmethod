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
    /**
     * Condition vérifiée **au moment d’écrire**, dans la transformation du document courant : une
     * création préparée sur un état périmé (cours créé, supprimé ou réglage changé entre-temps) n’écrit rien.
     */
    sealed interface Expectation {
        /** Création automatique : réglage toujours actif et séance jamais traitée (ni `created` ni `dismissed`). */
        data object Automatic : Expectation
        /** Bouton « Créer un cours » : l’entrée `notebookImports` doit être restée celle vue au début (`null` = jamais importée). */
        data class Manual(val seen: NotebookImport?) : Expectation
    }

    /** Écriture refusée parce que l’état a changé depuis la préparation (aucun cours créé). */
    @Volatile
    var lastRefusal: String? = null
        private set

    private fun allowed(document: fr.jmethod.app.model.AppDocument, source: CourseSource, expectation: Expectation): Boolean {
        val entry = document.notebookImports[source.sessionKey]
        return when (expectation) {
            // Ceinture et bretelles : aucun cours existant pour la même séance (date, matière, rang).
            Expectation.Automatic -> document.settings.notebookAutoCreate == true && entry == null &&
                fr.jmethod.app.domain.NotebookCourses.courseFor(source, document.courses) == null
            is Expectation.Manual -> entry == expectation.seen
        }
    }

    suspend fun create(
        form: CourseEditing.Form,
        source: CourseSource?,
        documents: List<PendingDocument>,
        expectation: Expectation = Expectation.Manual(null),
        stillCurrent: () -> Boolean = { true },
    ): CourseEditing.Created? {
        val state = repository.current()
        val nextNumber = (state.courses.filter { it.subjectId == form.subjectId }.maxOfOrNull { it.courseNumber } ?: 0) + 1
        val reference = form.reference?.trim()?.takeIf { it.isNotEmpty() } ?: "C$nextNumber"
        if (reference.length > 24 || state.courses.any { it.subjectId == form.subjectId && it.displayReference.equals(reference, true) }) {
            lastRefusal = "Cette référence existe déjà dans la matière, ou dépasse 24 caractères."
            return null
        }
        val personalForm = form.copy(reference = reference, title = form.title.trim().ifEmpty { reference }, chapter = form.chapter.trim().ifEmpty { "Sans chapitre" })
        if (!CourseEditing.isValid(personalForm)) return null
        val now = clock.now()
        val courseId = "course-${java.util.UUID.randomUUID()}"
        val stored = documents.filter { it.selected && it.file.isFile }.take(fr.jmethod.app.domain.NotebookCourses.MAX_DOCUMENTS).mapNotNull {
            runCatching { files.store(courseId, it.file, it.name, it.type, it.extractedTitle).copy(sourceSessionKey = source?.sessionKey) }.getOrNull()
        }
        var created: CourseEditing.Created? = null
        var refused = false
        repository.update { document ->
            // Idempotence : une séance déjà créée, supprimée entre-temps ou traitée par un autre chemin
            // (double soumission, création automatique concurrente) n’est jamais recréée.
            if (!stillCurrent() || (source != null && !allowed(document, source, expectation))) {
                refused = true
                return@update document
            }
            val result = CourseEditing.create(document.courses, document.revisions, emptyList(), personalForm, now, AppClock.ZONE)
                ?: return@update document
            if (document.courses.any { it.id == courseId || (it.subjectId == personalForm.subjectId && it.displayReference.equals(reference, true)) }) {
                refused = true
                return@update document
            }
            val course = result.course.copy(id = courseId, source = source, sources = source?.let { listOf(it) }, documents = stored.takeIf { it.isNotEmpty() },
                courseNumber = reference.removePrefix("C").toIntOrNull() ?: nextNumber)
            created = result.copy(course = course, courses = listOf(course) + result.courses.drop(1))
            document.copy(
                courses = listOf(course) + result.courses.drop(1),
                revisions = result.revisions,
                notebookImports = if (source == null) document.notebookImports else document.notebookImports +
                    (source.sessionKey to NotebookImport(NotebookImport.CREATED, course.id, AppDocumentCodec.isoMillis(now).dropLast(5) + "Z")),
            )
        }
        if (created == null) files.deleteCourse(courseId)
        lastRefusal = if (refused) "Cette séance a été traitée entre-temps : aucun cours n’a été créé." else null
        return created
    }

    /** Associe une ressource à une feuille existante, sans créer de C ni de J. */
    suspend fun associate(courseId: String, draft: NotebookCourseDraft, documents: List<PendingDocument>) {
        val source = draft.source
        val initial = requireNotNull(repository.current().courses.firstOrNull { it.id == courseId }) { "La feuille n’existe plus." }
        require(initial.subjectId == draft.subjectId) { "Choisis une feuille de la même matière." }
        val requested = documents.filter { it.selected }
        require(requested.map { it.name }.distinct().size == requested.size) { "Plusieurs fichiers de cette séance portent le même nom. Sélectionne-en un seul à la fois." }
        require(requested.none { candidate -> initial.documents.orEmpty().any { it.name == candidate.name && fr.jmethod.app.domain.RevisionSession.documentSourceKey(initial, it) == null } }) { "Un ancien fichier de même nom a une provenance ambiguë. Les ressources restent accessibles dans le cahier de textes." }
        val selected = requested.filterNot { fr.jmethod.app.domain.RevisionSession.hasDocumentFrom(initial, it.name, source.sessionKey) }
        require(initial.documents.orEmpty().size + selected.size <= fr.jmethod.app.domain.NotebookCourses.MAX_DOCUMENTS) { "Une feuille peut contenir au plus 10 documents." }
        val stored = mutableListOf<CourseDocument>()
        try {
            selected.forEach { stored += files.store(courseId, it.file, it.name, it.type, it.extractedTitle).copy(sourceSessionKey = source.sessionKey) }
            repository.update { document ->
                val course = requireNotNull(document.courses.firstOrNull { it.id == courseId }) { "La feuille n’existe plus." }
                require(course.subjectId == draft.subjectId)
                require(allowed(document, source, Expectation.Manual(draft.seenImport))) { "Cette séance a été traitée entre-temps. Rouvre-la avant de l’associer." }
                val docs = course.documents.orEmpty().map { existing ->
                    existing.copy(sourceSessionKey = fr.jmethod.app.domain.RevisionSession.documentSourceKey(course, existing))
                }
                require(docs.size + stored.size <= fr.jmethod.app.domain.NotebookCourses.MAX_DOCUMENTS && stored.none { added -> docs.any { it.name == added.name && (fr.jmethod.app.domain.RevisionSession.documentSourceKey(course, it) == added.sourceSessionKey || fr.jmethod.app.domain.RevisionSession.documentSourceKey(course, it) == null) } }) { "Les documents de la feuille ont changé. Réessaie." }
                val sources = (course.sources ?: listOfNotNull(course.source)) + source
                document.copy(courses = document.courses.map { if (it.id == courseId) it.copy(source = it.source ?: source,
                    sources = sources.distinctBy { it.sessionKey }, documents = (docs + stored).takeIf { it.isNotEmpty() }) else it },
                    notebookImports = document.notebookImports + (source.sessionKey to NotebookImport(NotebookImport.CREATED, courseId, AppDocumentCodec.isoMillis(clock.now()))))
            }
        } catch (error: Exception) {
            stored.forEach { files.fileOf(it)?.delete() }
            throw error
        }
    }

    /**
     * « Joindre à ce cours » (section 8.5) : copie [source] dans les documents du cours (nouvel `id`, même
     * nom, [extractedTitle] conservé), sans toucher au cours d’origine ni au titre. `null` si le cours a
     * disparu, a déjà un document de ce nom ou en a déjà 10.
     */
    suspend fun attach(courseId: String, source: File, name: String, type: DocType, extractedTitle: String?, mime: String? = null, sourceSessionKey: String? = null): CourseDocument? {
        val current = repository.current().courses.firstOrNull { it.id == courseId } ?: return null
        if (!source.isFile || current.documents.orEmpty().let { docs -> docs.size >= fr.jmethod.app.domain.NotebookCourses.MAX_DOCUMENTS || docs.any { it.name == name && (sourceSessionKey == null || fr.jmethod.app.domain.RevisionSession.documentSourceKey(current, it) == sourceSessionKey || fr.jmethod.app.domain.RevisionSession.documentSourceKey(current, it) == null) } }) return null
        val stored = files.store(courseId, source, name, type, extractedTitle).copy(sourceSessionKey = sourceSessionKey).let { if (mime != null) it.copy(mime = mime) else it }
        var attached = false
        repository.update { document ->
            val course = document.courses.firstOrNull { it.id == courseId }
            val docs = course?.documents.orEmpty()
            if (course == null || docs.size >= fr.jmethod.app.domain.NotebookCourses.MAX_DOCUMENTS || docs.any { it.name == name && (sourceSessionKey == null || fr.jmethod.app.domain.RevisionSession.documentSourceKey(course, it) == sourceSessionKey || fr.jmethod.app.domain.RevisionSession.documentSourceKey(course, it) == null) }) return@update document
            attached = true
            document.copy(courses = document.courses.map { if (it.id == courseId) it.copy(documents = docs + stored) else it })
        }
        if (!attached) files.fileOf(stored)?.delete()
        return stored.takeIf { attached }
    }

    /** Suppression : fichiers effacés et séance marquée `dismissed` (jamais recréée automatiquement). */
    suspend fun delete(courseId: String): CourseEditing.Deleted? {
        var deleted: CourseEditing.Deleted? = null
        var blocked = false
        repository.update { document ->
            if (document.programmes.any { courseId in it.courseIds }) {
                blocked = true
                deleted = CourseEditing.Deleted(document.courses, document.revisions, CourseEditing.Toast("Feuille conservée",
                    "Cette feuille appartient à un programme. Son historique et ses documents sont conservés.", CourseEditing.ToastType.INFO))
                return@update document
            }
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
        if (!blocked) files.deleteCourse(courseId)
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
    /** Entrée `notebookImports` de la séance au moment du clic (`null` = jamais importée). */
    val seenImport: NotebookImport? = null,
    /** Rang de la séance parmi celles de même date et même matière. */
    val ordinal: Int = 1,
) {
    val source get() = CourseSource(fr.jmethod.app.domain.NotebookCourses.SOURCE_KIND, sessionKey, subjectLabel, date, ordinal)
}
