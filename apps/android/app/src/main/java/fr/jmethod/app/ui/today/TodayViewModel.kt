package fr.jmethod.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.domain.ScorePreview
import fr.jmethod.app.domain.ScoreSubmission
import fr.jmethod.app.domain.TodayModel
import fr.jmethod.app.model.AppDocument
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    val document: AppDocument,
    val model: TodayModel,
    /** Séance ouverte dans l’autoévaluation rapide (« Faire le point »). */
    val scoringRevisionId: String? = null,
    /** Séance ouverte dans le détail. */
    val detailRevisionId: String? = null,
)

sealed interface TodayEvent {
    data class Toast(val title: String, val message: String, val warning: Boolean) : TodayEvent
    /** Petite pluie de confettis (autoévaluation à 75 ou plus). */
    data object Confetti : TodayEvent
    /** Feu d’artifice : la dernière séance due de la soirée vient d’être faite. */
    data object Fireworks : TodayEvent
}

class TodayViewModel(
    private val repository: AppRepository,
    private val clock: AppClock,
) : ViewModel() {

    /** Date réelle, recalculée toutes les 30 s tant que l’écran est actif (changement de jour). */
    private val todayFlow: Flow<String> = flow {
        while (true) {
            emit(clock.today().toString())
            delay(30_000)
        }
    }.distinctUntilChanged()

    private val sheets = MutableStateFlow<Pair<String?, String?>>(null to null)
    private val events = Channel<TodayEvent>(Channel.BUFFERED)
    val eventFlow: Flow<TodayEvent> = events.receiveAsFlow()

    val uiState: StateFlow<TodayUiState?> = combine(repository.document.filterNotNull(), todayFlow, sheets) { document, today, open ->
        TodayUiState(
            document = document,
            model = TodayModel.build(today, document.courses, document.revisions, document.subjects, document.pronoteEvents),
            scoringRevisionId = open.first,
            detailRevisionId = open.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openScore(revisionId: String) = sheets.update { revisionId to null }
    fun openDetail(revisionId: String) = sheets.update { null to revisionId }
    fun closeSheets() = sheets.update { null to null }

    /**
     * Valide une séance : date réelle de validation, replanification à partir du jour préparé.
     * @param withConfetti vrai depuis l’autoévaluation rapide (comme `ScoreModal`).
     */
    fun submitScore(revisionId: String, score: Int, withConfetti: Boolean) {
        closeSheets()
        viewModelScope.launch {
            var outcome: ScoreSubmission.Outcome? = null
            repository.update { document ->
                val result = ScoreSubmission.submit(
                    document.courses, document.revisions, document.settings, revisionId, score, clock.now(), AppClock.ZONE,
                )
                outcome = result
                if (result.ignored) document else document.copy(revisions = result.revisions)
            }
            val done = outcome ?: return@launch
            val toast = done.toast ?: return@launch
            if (withConfetti) {
                val settings = repository.current().settings
                if (ScorePreview.of(score, settings.sensitivity, settings.emergencyThreshold).confettiOnSubmit) events.send(TodayEvent.Confetti)
            }
            if (done.celebrate) events.send(TodayEvent.Fireworks)
            events.send(TodayEvent.Toast(toast.title, toast.message, toast.type == ScoreSubmission.ToastType.WARNING))
        }
    }
}
