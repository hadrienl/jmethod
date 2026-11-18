package fr.jmethod.app.ui.pronote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.AppContainer
import fr.jmethod.app.pronote.OfficialPage
import fr.jmethod.app.pronote.PronoteConnectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class PronoteSyncInfo(val lastSync: String?, val eventCount: Int)

/** Contrôleur de connexion créé par la plateforme, avec sa page officielle éventuelle (Android). */
class PronoteConnection(
    val controller: PronoteConnectionController,
    /** Page officielle Pronote (WebView) ; `null` pour la connexion directe de l’app ordinateur. */
    val page: OfficialPage? = null,
    /** Libère la page officielle à la fin du ViewModel. */
    val release: () -> Unit = {},
)

/**
 * Porte le contrôleur de connexion et, sur Android, la WebView officielle : tous deux survivent aux
 * recréations d’activité. La reprise de la session n’a lieu qu’une fois par processus.
 */
class PronoteViewModel(container: AppContainer, create: (CoroutineScope) -> PronoteConnection) : ViewModel() {
    private val connection = create(viewModelScope)
    val controller: PronoteConnectionController = connection.controller
    val page: OfficialPage? = connection.page

    val syncInfo: StateFlow<PronoteSyncInfo?> = container.repository.document.filterNotNull()
        .map { PronoteSyncInfo(it.settings.lastPronoteSync, it.pronoteEvents.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Reprise automatique au lancement (écran de chargement en attendant) : par jeton, ou
        // reconnexion silencieuse si seuls les identifiants sont enregistrés.
        controller.start()
    }

    override fun onCleared() {
        controller.dispose()
        connection.release()
        super.onCleared()
    }
}
