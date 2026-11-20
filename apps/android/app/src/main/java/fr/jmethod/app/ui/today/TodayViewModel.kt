package fr.jmethod.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.domain.RevisionSession
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
    /** Fiche de séance ouverte (section 8 de `notebook-to-course.md`), depuis n’importe quel onglet. */
    val session: SessionSheetState? = null,
)

/** Page affichée dans la fiche de séance ; les documents s’y ouvrent aussi (état tenu par l’écran). */
sealed interface SessionPage {
    /** Fiche : en-tête, le cours, le cahier de textes, séances précédentes, « Faire le point ». */
    data object Main : SessionPage
    /** Autoévaluation (« Faire le point »). */
    data object Score : SessionPage
    /** Résultat de la validation et séance suivante du programme du soir. */
    data class Result(val title: String, val message: String, val warning: Boolean, val nextRevisionId: String) : SessionPage
}

data class SessionSheetState(
    val revisionId: String,
    val page: SessionPage = SessionPage.Main,
    /** Validation en cours : un second appui est ignoré. */
    val submitting: Boolean = false,
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

    private val sheet = MutableStateFlow<SessionSheetState?>(null)
    private val events = Channel<TodayEvent>(Channel.BUFFERED)
    val eventFlow: Flow<TodayEvent> = events.receiveAsFlow()

    val uiState: StateFlow<TodayUiState?> = combine(repository.document.filterNotNull(), todayFlow, sheet) { document, today, open ->
        TodayUiState(
            document = document,
            model = TodayModel.build(today, document.courses, document.revisions, document.subjects, document.pronoteEvents),
            session = open,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ouvre la fiche d’une séance (Aujourd’hui, Planning, fiche d’un cours). */
    fun openSession(revisionId: String, page: SessionPage = SessionPage.Main) {
        sheet.value = SessionSheetState(revisionId, page)
    }

    /** « Faire le point » : la fiche s’ouvre directement sur l’autoévaluation (retour vers la fiche). */
    fun openScore(revisionId: String) = openSession(revisionId, SessionPage.Score)

    fun showPage(page: SessionPage) = sheet.update { current -> current?.takeIf { !it.submitting }?.copy(page = page) ?: current }

    fun closeSheets() {
        sheet.value = null
    }

    /**
     * Valide la séance de la fiche : date réelle de validation, replanification à partir du jour préparé.
     * S’il reste des séances dues ce soir, la fiche affiche le résultat et propose la suivante (8.2) ;
     * sinon elle se ferme (feu d’artifice si le programme vient d’être terminé).
     */
    fun submitScore(revisionId: String, score: Int) {
        val open = sheet.value
        if (open == null || open.revisionId != revisionId || open.submitting) return
        sheet.value = open.copy(submitting = true)
        viewModelScope.launch {
            var outcome: ScoreSubmission.Outcome? = null
            var next: String? = null
            repository.update { document ->
                val result = ScoreSubmission.submit(
                    document.courses, document.revisions, document.settings, revisionId, score, clock.now(), AppClock.ZONE,
                )
                outcome = result
                if (!result.ignored) {
                    // Première séance due dans l’ordre affiché d’Aujourd’hui (comme iOS).
                    next = RevisionSession.nextInDisplayOrder(result.revisions, document.courses, document.subjects, result.today, revisionId)?.id
                }
                if (result.ignored) document else document.copy(revisions = result.revisions)
            }
            val done = outcome
            val toast = done?.toast
            val stillOpen = sheet.value?.revisionId == revisionId
            if (done == null || toast == null) {
                // Séance déjà validée ou disparue : retour à la fiche.
                if (stillOpen) sheet.value = SessionSheetState(revisionId)
                return@launch
            }
            val warning = toast.type == ScoreSubmission.ToastType.WARNING
            val following = next
            if (following != null && stillOpen) {
                sheet.value = SessionSheetState(revisionId, SessionPage.Result(toast.title, toast.message, warning, following))
                return@launch
            }
            if (stillOpen) sheet.value = null
            val settings = repository.current().settings
            if (ScorePreview.of(score, settings.sensitivity, settings.emergencyThreshold).confettiOnSubmit && !done.celebrate) events.send(TodayEvent.Confetti)
            if (done.celebrate) {
                events.send(TodayEvent.Fireworks)
                events.send(TodayEvent.Toast("Programme terminé", toast.message, warning))
            } else {
                events.send(TodayEvent.Toast(toast.title, toast.message, warning))
            }
        }
    }
}
