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
)

data class CoursesUiState(
    val search: String,
    val subjectFilter: String?,
    val list: CoursesListModel,
    val subjects: List<Subject>,
    val hasCourses: Boolean,
    val detail: CourseDetailModel?,
    val form: CourseFormState,
    /** Matière effective du formulaire. */
    val formSubjectId: String?,
    val formCourseNumber: Int,
    val formSuggestions: List<String>,
    /** Nombre d’échéances générées par les intervalles actuels (écart D8). */
    val intervalCount: Int,
)

sealed interface CoursesEvent {
    data class Toast(val title: String, val message: String, val info: Boolean) : CoursesEvent
}

class CoursesViewModel(
    private val repository: AppRepository,
    private val clock: AppClock,
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
        CoursesUiState(
            search = filters.search,
            subjectFilter = filters.subjectId,
            list = CoursesListModel.build(document.courses, document.revisions, document.subjects, filters.search, filters.subjectId),
            subjects = document.subjects,
            hasCourses = document.courses.isNotEmpty(),
            detail = filters.detailId?.let { id -> document.courses.firstOrNull { it.id == id } }
                ?.let { CourseDetailModel.build(it, document.subjects, document.revisions) },
            form = formState,
            formSubjectId = formSubject,
            formCourseNumber = CourseEditing.courseNumber(document.courses, formSubject, formState.chapter),
            formSuggestions = CourseEditing.chapterSuggestions(document.courses, formSubject, formState.chapter),
            intervalCount = document.settings.intervals.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        )
    }

    fun openCreateFrom(event: PronoteEvent) {
        viewModelScope.launch { openCreate(CourseEditing.prefill(event, repository.current().subjects)) }
    }

    fun closeCreate() = form.update { it.copy(open = false, showErrors = false) }
    fun setFormSubject(subjectId: String) = form.update { it.copy(subjectId = subjectId) }
    fun setChapter(value: String) = form.update { it.copy(chapter = value) }
    fun setTitle(value: String) = form.update { it.copy(title = value) }
    fun setDate(value: String) = form.update { it.copy(date = value) }
    fun setDescription(value: String) = form.update { it.copy(description = value) }

    fun submitCreate() {
        val current = form.value
        // Titre et chapitre obligatoires (`required` du formulaire web), après `trim`.
        if (current.title.isBlank() || current.chapter.isBlank()) {
            form.update { it.copy(showErrors = true) }
            return
        }
        form.update { it.copy(open = false, showErrors = false) }
        viewModelScope.launch {
            var created: CourseEditing.Created? = null
            repository.update { document ->
                val subjectId = current.subjectId ?: document.subjects.firstOrNull()?.id ?: "maths"
                val input = CourseEditing.Form(subjectId, current.chapter, current.title, current.date, current.description)
                val result = CourseEditing.create(document.courses, document.revisions, document.settings.intervals, input, clock.now(), AppClock.ZONE)
                created = result
                if (result == null) document else document.copy(courses = result.courses, revisions = result.revisions)
            }
            created?.toast?.let { events.send(CoursesEvent.Toast(it.title, it.message, it.type == CourseEditing.ToastType.INFO)) }
        }
    }

    /** Suppression confirmée : le cours et toutes ses révisions ; les créneaux Pronote restent. */
    fun delete(courseId: String) {
        detailCourseId.value = null
        viewModelScope.launch {
            var deleted: CourseEditing.Deleted? = null
            repository.update { document ->
                val result = CourseEditing.delete(document.courses, document.revisions, courseId)
                deleted = result
                document.copy(courses = result.courses, revisions = result.revisions)
            }
            deleted?.toast?.let { events.send(CoursesEvent.Toast(it.title, it.message, it.type == CourseEditing.ToastType.INFO)) }
        }
    }
}
