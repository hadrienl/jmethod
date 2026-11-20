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
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<AppDocument?>(null)

    /** `null` tant que le document n’est pas chargé. */
    val document: StateFlow<AppDocument?> = state.asStateFlow()

    init {
        scope.launch(io) {
            mutex.withLock {
                val loaded = (store.read() as? AppDocumentStore.ReadResult.Loaded)?.document
                val document = if (loaded == null || !loaded.initialized) {
                    // Premier lancement : aucune donnée fictive, seulement la configuration par défaut.
                    DataMigrations.emptyDocument().also { store.write(it) }
                } else {
                    DataMigrations.migrate(loaded).also { if (it != loaded) store.write(it) }
                }
                state.value = document
            }
        }
    }

    suspend fun current(): AppDocument = document.filterNotNull().first()

    /** Applique [transform] au document courant, l’écrit sur disque puis le publie. */
    suspend fun update(transform: (AppDocument) -> AppDocument): AppDocument {
        current()
        return mutex.withLock {
            val updated = transform(state.value!!)
            withContext(io) { store.write(updated) }
            state.value = updated
            updated
        }
    }
}
