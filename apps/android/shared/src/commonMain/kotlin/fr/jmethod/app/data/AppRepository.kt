package fr.jmethod.app.data

import fr.jmethod.app.model.AppDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Source de vérité des données de révision. Les modifications sont sérialisées par un
 * verrou et persistées dans l’ordre, avant d’être publiées.
 */
class AppRepository(
    private val store: AppDocumentStore,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: AppClock = AppClock.SYSTEM,
) {
    private val failure = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = failure.asStateFlow()
    private val mutex = Mutex()
    private val state = MutableStateFlow<AppDocument?>(null)

    /** `null` tant que le document n’est pas chargé. */
    val document: StateFlow<AppDocument?> = state.asStateFlow()

    init {
        scope.launch(io) {
            try { mutex.withLock {
                val read = store.read()
                if (read is AppDocumentStore.ReadResult.Corrupted) throw IllegalStateException("Le fichier de données est illisible. Il a été conservé dans ${read.keptAs.absolutePath} et n’a pas été remplacé.")
                val loaded = (read as? AppDocumentStore.ReadResult.Loaded)?.document
                val document = if (loaded == null || !loaded.initialized) {
                    // Premier lancement : aucune donnée fictive, seulement la configuration par défaut.
                    DataMigrations.emptyDocument().also { store.write(it) }
                } else {
                    fr.jmethod.app.domain.RevisionStreak.reconcile(DataMigrations.migrate(loaded), clock.now()).also { if (it != loaded) store.write(it) }
                }
                state.value = document
            } } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { failure.value = error.message ?: "Les données enregistrées ne peuvent pas être ouvertes." }
        }
    }

    suspend fun current(): AppDocument = document.filterNotNull().first()

    /** Première ouverture fonctionnelle ou retour au premier plan : avant toute reprise Pronote. */
    suspend fun openRevisionDay(): AppDocument = update { fr.jmethod.app.domain.RevisionStreak.reconcile(it, clock.now(), openCurrent = true) }

    /** Applique [transform] au document courant, l’écrit sur disque puis le publie. */
    suspend fun update(transform: (AppDocument) -> AppDocument): AppDocument {
        current()
        return mutex.withLock {
            val before = fr.jmethod.app.domain.RevisionStreak.reconcile(state.value!!, clock.now())
            val updated = transform(before)
            if (updated == state.value) return@withLock updated
            withContext(io) { store.write(updated) }
            state.value = updated
            updated
        }
    }
}
