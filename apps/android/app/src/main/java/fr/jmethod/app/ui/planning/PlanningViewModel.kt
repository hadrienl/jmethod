package fr.jmethod.app.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.data.todayTicks
import fr.jmethod.app.domain.PlanningMode
import fr.jmethod.app.domain.PlanningModel
import fr.jmethod.app.domain.RevisionDetailModel
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.Subject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/** Choix de l’utilisateur dans le Planning (survit à la rotation avec le ViewModel). */
data class PlanningSelection(
    /** Semaine par défaut, comme le web. */
    val mode: PlanningMode = PlanningMode.WEEK,
    val date: LocalDate,
    val subjectId: String? = null,
    val phase: Phase? = null,
    val detailRevisionId: String? = null,
)

data class PlanningUiState(
    val selection: PlanningSelection,
    val today: LocalDate,
    val model: PlanningModel,
    val subjects: List<Subject>,
    val hasCourses: Boolean,
    val pronoteEvents: List<PronoteEvent>,
    /** Séance ouverte ; `null` si elle n’existe plus ou si sa matière a disparu (comme le web). */
    val detail: RevisionDetailModel?,
)

class PlanningViewModel(
    repository: AppRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val selection = MutableStateFlow(PlanningSelection(date = clock.today()))

    val uiState: StateFlow<PlanningUiState?> = combine(
        repository.document.filterNotNull(), clock.todayTicks(), selection,
    ) { document, today, current ->
        PlanningUiState(
            selection = current,
            today = today,
            model = PlanningModel.build(
                today, current.mode, current.date, current.subjectId, current.phase,
                document.courses, document.revisions, document.subjects, document.pronoteEvents,
            ),
            subjects = document.subjects,
            hasCourses = document.courses.isNotEmpty(),
            pronoteEvents = document.pronoteEvents,
            detail = current.detailRevisionId?.let { RevisionDetailModel.build(it, document.courses, document.revisions, document.subjects) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMode(mode: PlanningMode) = selection.update { it.copy(mode = mode) }
    fun move(direction: Int) = selection.update { it.copy(date = PlanningModel.navigate(it.mode, it.date, direction)) }
    fun goToToday() = selection.update { it.copy(date = clock.today()) }
    /** Depuis la grille du mois : ouvre le jour touché. */
    fun openDay(date: LocalDate) = selection.update { it.copy(mode = PlanningMode.DAY, date = date) }
    fun setSubject(subjectId: String?) = selection.update { it.copy(subjectId = subjectId) }
    fun setPhase(phase: Phase?) = selection.update { it.copy(phase = phase) }
    fun openDetail(revisionId: String) = selection.update { it.copy(detailRevisionId = revisionId) }
    fun closeDetail() = selection.update { it.copy(detailRevisionId = null) }
}
