package fr.jmethod.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.domain.ImportExport
import fr.jmethod.app.domain.SettingsLogic
import fr.jmethod.app.domain.SubjectColorDraft
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Subject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Modifications non enregistrées (rien n’est appliqué avant « Enregistrer », comme le web). */
data class SettingsDraft(
    val base: AppSettings,
    val sensitivity: Double,
    val emergencyThreshold: Int,
    val intervals: List<Int>,
    val newInterval: String = "",
    val soundEnabled: Boolean = base.soundEnabled,
) {
    val dirty: Boolean get() = sensitivity != base.sensitivity || emergencyThreshold != base.emergencyThreshold || intervals != base.intervals || soundEnabled != base.soundEnabled

    companion object {
        fun of(settings: AppSettings) = SettingsDraft(settings, settings.sensitivity, settings.emergencyThreshold, settings.intervals)
    }
}

data class SettingsUiState(
    val draft: SettingsDraft,
    val subjects: List<Subject>,
    val courseCount: Int,
    val revisionCount: Int,
    /** Import lu et validé, en attente de confirmation (il remplace les données). */
    val pendingImport: ImportExport.Result.Accepted?,
    val document: fr.jmethod.app.model.AppDocument,
)

sealed interface SettingsEvent {
    data class Toast(val title: String, val message: String, val warning: Boolean = false) : SettingsEvent
    /** Texte prêt à écrire dans le fichier choisi par l’utilisateur (Storage Access Framework). */
    data class Export(val fileName: String) : SettingsEvent
}

/** [scope] : portée du ViewModel, annulée à sa fin ; les tests en fournissent une sans fil principal. */
class SettingsViewModel internal constructor(
    private val repository: AppRepository,
    private val clock: AppClock,
    scope: kotlinx.coroutines.CoroutineScope,
) : ViewModel(scope) {
    constructor(repository: AppRepository, clock: AppClock) :
        this(repository, clock, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate))

    private val draft = MutableStateFlow<SettingsDraft?>(null)
    /** Sélecteur de couleur ouvert (#8) : brouillon gardé ici pour survivre aux changements de configuration. */
    private val colorDraft = MutableStateFlow<SubjectColorDraft?>(null)
    val colorPicker: StateFlow<SubjectColorDraft?> = colorDraft.asStateFlow()
    private val pending = MutableStateFlow<ImportExport.Result.Accepted?>(null)
    private val events = Channel<SettingsEvent>(Channel.BUFFERED)
    val eventFlow: Flow<SettingsEvent> = events.receiveAsFlow()
    /** Demandes d’export, consommées par l’écran (sélecteur de fichier du système). */
    private val exports = Channel<SettingsEvent.Export>(Channel.CONFLATED)
    val exportFlow: Flow<SettingsEvent.Export> = exports.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState?> = combine(repository.document.filterNotNull(), draft, pending) { document, current, import ->
        // Un brouillon périmé (réglages changés ailleurs, import) repart des réglages enregistrés.
        val effective = current?.takeIf { it.base == document.settings } ?: SettingsDraft.of(document.settings)
        SettingsUiState(effective, document.subjects, document.courses.size, document.revisions.size, import, document)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun edit(transform: (SettingsDraft) -> SettingsDraft) {
        val current = uiState.value?.draft ?: return
        draft.value = transform(current)
    }

    fun setSoundEnabled(value: Boolean) = edit { it.copy(soundEnabled = value) }
    fun setSensitivity(value: Double) = edit { it.copy(sensitivity = SettingsLogic.snapSensitivity(value)) }
    fun setThreshold(value: Int) = edit { it.copy(emergencyThreshold = value.coerceIn(SettingsLogic.THRESHOLD_MIN, SettingsLogic.THRESHOLD_MAX)) }
    fun setNewInterval(value: String) = edit { it.copy(newInterval = value) }

    /** Comme le web : le champ n’est vidé que si le palier est ajouté. */
    fun addInterval() = edit {
        val updated = SettingsLogic.addInterval(it.intervals, it.newInterval)
        if (updated == it.intervals) it else it.copy(intervals = updated, newInterval = "")
    }

    fun removeInterval(value: Int) = edit { it.copy(intervals = SettingsLogic.removeInterval(it.intervals, value)) }
    fun resetIntervals() = edit { it.copy(intervals = SettingsLogic.resetIntervals()) }
    fun discard() { draft.value = null }

    fun save() {
        val current = uiState.value?.draft ?: return
        viewModelScope.launch {
            repository.update { document ->
                document.copy(
                    settings = SettingsLogic.save(
                        document.settings, document.settings.pronoteIcalUrl, current.sensitivity, current.emergencyThreshold, current.intervals,
                    ).copy(soundEnabled = current.soundEnabled),
                )
            }
            draft.value = null
            events.send(SettingsEvent.Toast("Réglages enregistrés", "Ils s’appliquent aux prochaines validations et aux nouveaux cours."))
        }
    }

    fun saveRhythm(rhythm: fr.jmethod.app.model.RevisionRhythm, adaptive: Boolean, applyToOngoing: Boolean) {
        viewModelScope.launch {
            try {
                fr.jmethod.app.domain.RevisionProgrammes.validate(rhythm)
                repository.update { document ->
                    val updated = document.copy(settings = document.settings.copy(rhythm = rhythm,
                        intervals = rhythm.steps.map { it.offsetDays }, adaptiveEnabled = adaptive))
                    if (applyToOngoing) fr.jmethod.app.domain.RevisionStreak.releaseMovedByApp(updated,
                        fr.jmethod.app.domain.RevisionProgrammes.reapply(updated, document.programmes.map { it.id }.toSet(), rhythm), clock.now()) else updated
                }
                events.send(SettingsEvent.Toast("Rythme enregistré", if (applyToOngoing) "Les séances réalisées et les rattrapages ont été conservés." else "Les programmes existants gardent leur rythme et leur mode."))
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { events.send(SettingsEvent.Toast("Rythme non enregistré", error.message ?: "Enregistrement impossible.", true)) }
        }
    }

    /** Sélecteur ouvert sur une matière : toujours repartir de sa couleur enregistrée. */
    fun openSubjectColor(subjectId: String) {
        val subject = repository.document.value?.subjects?.firstOrNull { it.id == subjectId } ?: return
        colorDraft.value = SubjectColorDraft.open(subject)
    }

    /** Geste, touche ou saisie : brouillon en mémoire seulement, aucune écriture disque. Figé pendant l’enregistrement. */
    fun editSubjectColor(transform: (SubjectColorDraft) -> SubjectColorDraft) {
        colorDraft.update { current -> if (current == null || current.saving) current else transform(current) }
    }

    /** Annuler, fermer, Échap ou Retour : le brouillon est abandonné (sauf pendant une écriture en cours). */
    fun cancelSubjectColor() {
        if (colorDraft.value?.saving == true) return
        colorDraft.value = null
    }

    /**
     * Enregistrer : seule la couleur de cette matière change, les brouillons des autres réglages restent
     * intacts. Le sélecteur se ferme après la sauvegarde ; un échec garde le brouillon pour réessayer.
     */
    fun saveSubjectColor() {
        val draft = colorDraft.value ?: return
        val color = draft.textColor ?: return
        if (!draft.canSave) return
        if (!draft.changed) {
            colorDraft.value = null
            return
        }
        colorDraft.value = draft.committed().copy(saving = true, failure = null)
        viewModelScope.launch {
            val outcome = try {
                var present = false
                repository.update { document ->
                    val subjects = SettingsLogic.confirmedSubjectColor(document.subjects, draft.subjectId, color)
                    present = subjects != null
                    if (subjects == null) document else document.copy(subjects = subjects)
                }
                if (present) ColorOutcome.SAVED else ColorOutcome.REMOVED
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { ColorOutcome.FAILED }
            colorDraft.update { current ->
                if (current?.subjectId != draft.subjectId) current else when (outcome) {
                    ColorOutcome.SAVED -> null
                    ColorOutcome.REMOVED -> current.copy(saving = false, removed = true, failure = SubjectColorDraft.REMOVED_MESSAGE)
                    ColorOutcome.FAILED -> current.copy(saving = false, failure = SubjectColorDraft.FAILURE_MESSAGE)
                }
            }
            if (outcome == ColorOutcome.SAVED) events.send(SettingsEvent.Toast("Couleur enregistrée", "La nouvelle couleur de ${draft.subjectName} est appliquée partout."))
        }
    }

    private enum class ColorOutcome { SAVED, REMOVED, FAILED }

    fun requestExport() {
        viewModelScope.launch { exports.send(SettingsEvent.Export(ImportExport.fileName(clock.today()))) }
    }

    /** Texte de l’export au moment de l’écriture (format `exportAllData`). */
    suspend fun exportText(): String = ImportExport.export(repository.current(), clock.now())

    fun exported(success: Boolean) {
        viewModelScope.launch {
            events.send(
                if (success) SettingsEvent.Toast("Export réussi", "Fichier de sauvegarde enregistré.")
                else SettingsEvent.Toast("Export impossible", "Le fichier n’a pas pu être écrit. Choisis un autre emplacement.", warning = true),
            )
        }
    }

    /** Fichier choisi : lu et validé (D13) ; s’il est accepté, une confirmation est demandée. */
    fun importText(text: String?) {
        viewModelScope.launch {
            // Lecture et validation hors du thread principal (fichier potentiellement volumineux).
            val result = withContext(Dispatchers.Default) {
                if (text == null) ImportExport.Result.Rejected(ImportExport.ERROR_TITLE, ImportExport.INVALID_MESSAGE) else ImportExport.parse(text)
            }
            when (result) {
                is ImportExport.Result.Accepted -> pending.value = result
                is ImportExport.Result.Rejected -> events.send(SettingsEvent.Toast(result.title, result.message, warning = true))
            }
        }
    }

    fun cancelImport() { pending.value = null }

    fun confirmImport() {
        val accepted = pending.value ?: return
        pending.value = null
        viewModelScope.launch {
            repository.update { fr.jmethod.app.domain.RevisionStreak.reconcile(accepted.applyTo(it), clock.now(), openCurrent = true) }
            draft.value = null
            events.send(SettingsEvent.Toast("Sauvegarde restaurée", "${accepted.courses.size} cours et ${accepted.revisions.size} séances importés."))
        }
    }
}
