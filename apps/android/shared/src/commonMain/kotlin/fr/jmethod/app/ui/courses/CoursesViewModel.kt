package fr.jmethod.app.ui.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.domain.CourseDetailModel
import fr.jmethod.app.domain.CourseEditing
import fr.jmethod.app.domain.CoursesListModel
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Subject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import fr.jmethod.app.documents.CourseCreator
import fr.jmethod.app.documents.CourseFiles
import fr.jmethod.app.documents.NotebookCourseDraft
import fr.jmethod.app.documents.PendingDocument
import fr.jmethod.app.model.CourseDocument

/**
 * Formulaire de création. Comme `AddCourseModal`, il garde son état d’une ouverture à l’autre :
 * le chapitre est conservé, la matière et la date ne changent qu’avec un préremplissage, la date
 * initiale est celle du lancement ; le titre et la description sont vidés à chaque ouverture.
 */
data class CourseFormState(
    val open: Boolean = false,
    /** `null` : première matière de la liste (`subjects[0]?.id || 'maths'`). */
    val subjectId: String? = null,
    val chapter: String = "",
    val title: String = "",
    val date: String,
    val description: String = "",
    /** Erreurs affichées après une tentative de création incomplète. */
    val showErrors: Boolean = false,
    /** Séance du cahier de textes d’origine (création depuis le cahier) et documents joints proposés. */
    val notebook: NotebookCourseDraft? = null,
    val documents: List<PendingDocument> = emptyList(),
    val notice: String? = null,
    val reference: String = "",
    val existingTargetId: String? = null,
)

data class CoursesUiState(
    val search: String,
    val subjectFilter: String?,
    val list: CoursesListModel,
    val subjects: List<Subject>,
    val hasCourses: Boolean,
    val detail: CourseDetailModel?,
    val form: CourseFormState,
    /** Documents du cours ouvert, avec la présence du fichier sur ce téléphone. */
    val detailDocuments: List<Pair<CourseDocument, Boolean>>,
    /** Matière effective du formulaire. */
    val formSubjectId: String?,
    val formCourseNumber: Int,
    val formSuggestions: List<String>,
    /** Nombre d’échéances générées par les intervalles actuels (écart D8). */
    val intervalCount: Int,
    val courses: List<fr.jmethod.app.model.Course> = emptyList(),
    val revisions: List<fr.jmethod.app.model.Revision> = emptyList(),
    val programmes: List<fr.jmethod.app.model.RevisionProgramme> = emptyList(),
    val defaultStartDate: String = "",
    val settings: fr.jmethod.app.model.AppSettings = fr.jmethod.app.model.AppSettings(),
)

sealed interface CoursesEvent {
    data class Toast(val title: String, val message: String, val info: Boolean) : CoursesEvent
}

class CoursesViewModel(
    private val repository: AppRepository,
    private val clock: AppClock,
    private val creator: CourseCreator,
    private val files: CourseFiles,
) : ViewModel() {
    private val search = MutableStateFlow("")
    private val subjectFilter = MutableStateFlow<String?>(null)
    private val detailCourseId = MutableStateFlow<String?>(null)
    private val form = MutableStateFlow(CourseFormState(date = clock.today().toString()))
    private val events = Channel<CoursesEvent>(Channel.BUFFERED)
    val eventFlow: Flow<CoursesEvent> = events.receiveAsFlow()

    private data class Filters(val search: String, val subjectId: String?, val detailId: String?)

    val uiState: StateFlow<CoursesUiState?> = combine(
        repository.document.filterNotNull(),
        combine(search, subjectFilter, detailCourseId, ::Filters),
        form,
    ) { document, filters, formState ->
        val formSubject = formState.subjectId ?: document.subjects.firstOrNull()?.id ?: "maths"
        val detailCourse = filters.detailId?.let { id -> document.courses.firstOrNull { it.id == id } }
        CoursesUiState(
            detailDocuments = detailCourse?.documents.orEmpty().map { it to files.exists(it) },
            search = filters.search,
            subjectFilter = filters.subjectId,
            list = CoursesListModel.build(document.courses, document.revisions, document.subjects, filters.search, filters.subjectId),
            subjects = document.subjects,
            hasCourses = document.courses.isNotEmpty(),
            detail = filters.detailId?.let { id -> document.courses.firstOrNull { it.id == id } }
                ?.let { CourseDetailModel.build(it, document.subjects, document.revisions) },
            form = formState,
            formSubjectId = formSubject,
            formCourseNumber = (document.courses.filter { it.subjectId == formSubject }.maxOfOrNull { it.courseNumber } ?: 0) + 1,
            formSuggestions = CourseEditing.chapterSuggestions(document.courses, formSubject, formState.chapter),
            intervalCount = fr.jmethod.app.domain.RevisionProgrammes.selectedRhythm(document.settings).steps.size,
            courses = document.courses, revisions = document.revisions, programmes = document.programmes,
            defaultStartDate = clock.today().plusDays(1).toString(), settings = document.settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startProgramme(ids: List<String>, date: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.update { fr.jmethod.app.domain.RevisionProgrammes.start(it, ids, date) }
                onSuccess()
                events.send(CoursesEvent.Toast("Programme créé", "Les feuilles sélectionnées ont leur propre rythme de révision.", false))
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { events.send(CoursesEvent.Toast("Programme non créé", error.message ?: "Enregistrement impossible.", true)) }
        }
    }

    fun renameReference(id: String, value: String) {
        viewModelScope.launch {
            try {
                repository.update { doc ->
                    val course = requireNotNull(doc.courses.firstOrNull { it.id == id })
                    val reference = value.trim()
                    require(reference.isNotEmpty() && reference.length <= 24) { "Saisis une référence de 1 à 24 caractères." }
                    require(doc.courses.none { it.id != id && it.subjectId == course.subjectId && it.displayReference.equals(reference, true) }) { "Cette référence existe déjà dans cette matière." }
                    doc.copy(courses = doc.courses.map { if (it.id == id) it.copy(reference = reference) else it })
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { events.send(CoursesEvent.Toast("Référence inchangée", error.message ?: "Enregistrement impossible.", true)) }
        }
    }

    fun setSearch(value: String) { search.value = value }
    fun setSubjectFilter(subjectId: String?) { subjectFilter.value = subjectId }
    fun openDetail(courseId: String) { detailCourseId.value = courseId }
    fun closeDetail() { detailCourseId.value = null }

    /** Ouvre le formulaire, éventuellement prérempli depuis un créneau Pronote. */
    fun openCreate(prefill: CourseEditing.Prefill? = null) = form.update { current ->
        current.copy(
            open = true,
            subjectId = prefill?.subjectId ?: current.subjectId,
            date = prefill?.date ?: current.date,
            title = prefill?.title.orEmpty(),
            description = "",
            showErrors = false,
            notebook = null,
            documents = emptyList(),
            notice = null, reference = "", existingTargetId = null,
        )
    }

    /** Formulaire prérempli depuis une séance du cahier de textes (documents déjà téléchargés). */
    fun openFromNotebook(draft: NotebookCourseDraft) = form.update { current ->
        current.copy(
            open = true,
            subjectId = draft.subjectId ?: current.subjectId,
            chapter = draft.chapter,
            title = draft.title,
            date = draft.date,
            description = draft.description,
            showErrors = false,
            notebook = draft,
            documents = draft.documents,
            notice = draft.notice, reference = "", existingTargetId = null,
        )
    }

    fun toggleDocument(index: Int) = form.update { current ->
        current.copy(documents = current.documents.mapIndexed { i, doc -> if (i == index) doc.copy(selected = !doc.selected) else doc })
    }

    fun openCreateFrom(event: PronoteEvent) {
        viewModelScope.launch { openCreate(CourseEditing.prefill(event, repository.current().subjects)) }
    }

    fun closeCreate() = form.update { it.copy(open = false, showErrors = false) }
    fun setFormSubject(subjectId: String) = form.update { it.copy(subjectId = subjectId, existingTargetId = null) }
    fun setExistingTarget(id: String?) = form.update { it.copy(existingTargetId = id) }
    fun setChapter(value: String) = form.update { it.copy(chapter = value) }
    fun setTitle(value: String) = form.update { it.copy(title = value) }
    fun setDate(value: String) = form.update { it.copy(date = value) }
    fun setReference(value: String) = form.update { it.copy(reference = value) }
    fun setDescription(value: String) = form.update { it.copy(description = value) }

    fun submitCreate() {
        val current = form.value
        // Double tap : le formulaire est déjà fermé par la première soumission, rien n’est renvoyé.
        if (!current.open) return
        // Titre et chapitre obligatoires (`required` du formulaire web), après `trim`.
        if (current.reference.length > 24) {
            form.update { it.copy(showErrors = true) }
            return
        }
        form.update { it.copy(open = false, showErrors = false, notebook = null, documents = emptyList(), notice = null) }
        viewModelScope.launch {
            val subjectId = current.subjectId ?: repository.current().subjects.firstOrNull()?.id ?: "maths"
            val input = CourseEditing.Form(subjectId, current.chapter, current.title, current.date, current.description, current.reference)
            val notebook = current.notebook
            if (notebook != null && current.existingTargetId != null) {
                try {
                    withContext(Dispatchers.IO) { creator.associate(current.existingTargetId, notebook, current.documents) }
                    events.send(CoursesEvent.Toast("Ressource associée", "La feuille conserve sa référence et son programme.", false))
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { events.send(CoursesEvent.Toast("Association impossible", error.message ?: "Réessaie.", true)) }
                return@launch
            }
            try {
                val created = withContext(Dispatchers.IO) {
                    creator.create(input, notebook?.source, current.documents, CourseCreator.Expectation.Manual(notebook?.seenImport))
                }
                created?.let { events.send(CoursesEvent.Toast("Feuille enregistrée", "${it.course.displayReference} est disponible. Démarre son programme pour créer les J.", false)) }
                if (created == null) creator.lastRefusal?.let { events.send(CoursesEvent.Toast("Feuille non créée", it, true)) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { events.send(CoursesEvent.Toast("Feuille non créée", error.message ?: "Enregistrement impossible.", true)) }
        }
    }

    /** Suppression confirmée : le cours et toutes ses révisions ; les créneaux Pronote restent. */
    /** « Joindre à ce cours » depuis le document d’un autre cours (section 8.5) : copie, l’autre cours est inchangé. */
    fun attachFromCourse(courseId: String, document: fr.jmethod.app.model.CourseDocument) {
        val file = files.fileOf(document)?.takeIf { it.isFile } ?: return
        viewModelScope.launch {
            val owner = repository.current().courses.firstOrNull { leaf -> leaf.documents.orEmpty().any { it.id == document.id } }
            val sourceKey = document.sourceSessionKey ?: owner?.let { fr.jmethod.app.domain.RevisionSession.documentSourceKey(it, document) }
            val attached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                creator.attach(
                    courseId, file, document.name, fr.jmethod.app.documents.DocType.displayTypeOf(document.name),
                    document.extractedTitle, document.mime, sourceKey,
                )
            }
            events.send(
                if (attached != null) CoursesEvent.Toast("Document joint", "« ${document.name} » est joint à ce cours.", info = false)
                else CoursesEvent.Toast("Document", "Ce document n’a pas pu être joint au cours.", info = true),
            )
        }
    }

    fun delete(courseId: String) {
        detailCourseId.value = null
        viewModelScope.launch {
            // Documents supprimés, séance marquée `dismissed` : jamais recréée automatiquement.
            val deleted = withContext(Dispatchers.IO) { creator.delete(courseId) }
            deleted?.toast?.let { events.send(CoursesEvent.Toast(it.title, it.message, it.type == CourseEditing.ToastType.INFO)) }
        }
    }
}
