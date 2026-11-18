package fr.jmethod.app.ui.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.domain.SubjectMatcher
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookRules
import fr.jmethod.app.pronote.NotebookSnapshot
import fr.jmethod.app.pronote.PronoteConnectionController
import fr.jmethod.app.pronote.PronoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class NotebookTab { CONTENTS, HOMEWORK }

/** État de l’aperçu. Rien n’est persisté : les identifiants de pièces jointes restent en mémoire. */
data class NotebookState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val snapshot: NotebookSnapshot? = null,
    val error: String? = null,
    val tab: NotebookTab = NotebookTab.CONTENTS,
    /** Matière J-Method choisie (depuis la fiche d’un cours) : seuls ses contenus sont affichés. */
    val subjectId: String? = null,
    val downloadingId: String? = null,
)

data class NotebookUiState(val state: NotebookState, val subjects: List<Subject>, val subjectName: String?)

sealed interface NotebookEvent {
    data class Open(val file: File) : NotebookEvent
    data class Message(val text: String) : NotebookEvent
}

/**
 * Cahier de textes en lecture seule, ouvert depuis Mes cours ou la fiche d’un cours. Les fichiers
 * téléchargés vont dans le cache privé (`notebook/`), effacé à la déconnexion et au changement d’enfant.
 */
class NotebookViewModel(
    private val controller: PronoteConnectionController,
    repository: AppRepository,
    private val cacheDir: File,
) : ViewModel() {
    private val state = MutableStateFlow(NotebookState())
    private var stamp: Any? = null
    private var readJob: Job? = null
    private var downloadJob: Job? = null
    /** Incrémenté à chaque réinitialisation : un résultat tardif d’une session précédente est ignoré. */
    private var resetCount = 0
    private val events = Channel<NotebookEvent>(Channel.BUFFERED)
    val eventFlow: Flow<NotebookEvent> = events.receiveAsFlow()

    val uiState: StateFlow<NotebookUiState?> = combine(repository.document.filterNotNull(), state) { document, current ->
        NotebookUiState(current, document.subjects, current.subjectId?.let { id -> document.subjects.firstOrNull { it.id == id }?.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        clearFiles()
        // Déconnexion ou changement d’enfant : l’aperçu et les fichiers sont effacés.
        viewModelScope.launch {
            controller.uiState.map { it.account?.selectedChildId to (it.account != null) }.distinctUntilChanged().collect { reset() }
        }
    }

    private fun notebookDir() = File(cacheDir, "notebook")

    private fun clearFiles() {
        notebookDir().deleteRecursively()
    }

    private fun reset() {
        resetCount++
        readJob?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        stamp = null
        clearFiles()
        state.update { NotebookState(open = it.open, tab = it.tab, subjectId = it.subjectId) }
    }

    fun open(subjectId: String? = null) {
        state.update { it.copy(open = true, subjectId = subjectId, tab = NotebookTab.CONTENTS) }
        if (state.value.snapshot == null && !state.value.loading) refresh()
    }

    fun close() = state.update { it.copy(open = false) }
    fun setTab(tab: NotebookTab) = state.update { it.copy(tab = tab) }
    fun setSubject(subjectId: String?) = state.update { it.copy(subjectId = subjectId) }

    fun refresh() {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        readJob = viewModelScope.launch {
            try {
                val generation = resetCount
                val read = controller.readNotebook()
                if (generation != resetCount) return@launch
                stamp = read.stamp
                // Nouvelle lecture : les fichiers d’une session précédente ne servent plus.
                withContext(Dispatchers.IO) { clearFiles() }
                state.update { it.copy(loading = false, snapshot = read.snapshot, error = null) }
            } catch (_: CancellationException) {
                state.update { it.copy(loading = false) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = message(error, "Lecture du cahier de textes impossible. Réessaie plus tard.")) }
            }
        }
    }

    fun download(attachment: NotebookAttachment) {
        if (attachment.kind != NotebookAttachment.Kind.FILE || state.value.downloadingId != null) return
        val session = stamp ?: return
        val generation = resetCount
        state.update { it.copy(downloadingId = attachment.id) }
        downloadJob = viewModelScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    File(notebookDir(), UUID.randomUUID().toString()).apply { mkdirs() }.let { File(it, NotebookRules.saveAsName(attachment.rawName)) }
                }
                // Nom brut `L` (même vide) pour l’adresse FichiersExternes, comme pronotepy.
                val reread = controller.downloadAttachment(session, attachment.id, attachment.rawName, target)
                if (reread != null && generation == resetCount) {
                    // Session renouvelée avant le téléchargement : l’aperçu reprend les identifiants à jour.
                    stamp = reread.stamp
                    state.update { it.copy(snapshot = reread.snapshot) }
                }
                if (generation != resetCount) {
                    withContext(Dispatchers.IO) { target.parentFile?.deleteRecursively() }
                    return@launch
                }
                events.send(NotebookEvent.Open(target))
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (generation == resetCount) {
                    events.send(NotebookEvent.Message(message(error, "Document indisponible. Actualise le cahier de textes puis réessaie.")))
                }
            } finally {
                if (generation == resetCount) state.update { it.copy(downloadingId = null) }
            }
        }
    }

    private fun message(error: Exception, fallback: String) = when (error) {
        is PronoteException -> if (error.code.startsWith("pronote-") || error.code == "http") fallback else error.message
        is IOException -> "Pronote est injoignable. Vérifie ta connexion internet, puis réessaie."
        else -> fallback
    }

    companion object {
        /** La matière Pronote d’une séance correspond-elle à la matière J-Method ? */
        fun matches(label: String, subjectId: String, subjects: List<Subject>): Boolean = SubjectMatcher.match(label, subjects)?.id == subjectId
    }
}
