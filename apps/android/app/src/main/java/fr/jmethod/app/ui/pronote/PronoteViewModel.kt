package fr.jmethod.app.ui.pronote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewFeature
import fr.jmethod.app.AppContainer
import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.pronote.AccessPhase
import fr.jmethod.app.pronote.NativeTransport
import fr.jmethod.app.pronote.PronoteConnectionController
import fr.jmethod.app.pronote.PronoteEventMapper
import fr.jmethod.app.pronote.QrImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class PronoteSyncInfo(val lastSync: String?, val eventCount: Int)

/**
 * Porte le contrôleur de connexion et la WebView officielle : tous deux survivent aux
 * recréations d’activité. La reprise par jeton n’a lieu qu’une fois par processus.
 */
class PronoteViewModel(application: Application, private val container: AppContainer) : AndroidViewModel(application) {
    val page = WebViewOfficialPage(application)

    private val script by lazy {
        application.assets.open("PronoteAutomation.js").bufferedReader().use { it.readText() }
    }

    val controller = PronoteConnectionController(
        scope = viewModelScope,
        io = Dispatchers.IO,
        page = page,
        vault = container.vault,
        transport = NativeTransport(),
        decodeQr = QrImageDecoder::decode,
        automationScript = { script },
        today = { container.clock.today() },
        storeLessons = { lessons ->
            val syncedAt = AppDocumentCodec.isoMillis(container.clock.now())
            container.repository.update { document ->
                document.copy(
                    pronoteEvents = PronoteEventMapper.toEvents(lessons, document.subjects),
                    settings = document.settings.copy(lastPronoteSync = syncedAt),
                )
            }
        },
        clearLessons = {
            container.repository.update { document ->
                document.copy(pronoteEvents = emptyList(), settings = document.settings.copy(lastPronoteSync = null))
            }
        },
        loginPreferences = container.loginPreferences,
        webMessagesSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
    )

    val syncInfo: StateFlow<PronoteSyncInfo?> = container.repository.document.filterNotNull()
        .map { PronoteSyncInfo(it.settings.lastPronoteSync, it.pronoteEvents.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        page.listener = object : OfficialPageListener {
            override fun onPageStarted() = controller.onPageStarted()
            override fun onPageFinished(url: String?) = controller.onPageFinished(url)
            override fun onBridgeMessage(raw: String?, sourceOrigin: String, isMainFrame: Boolean) =
                controller.onBridgeMessage(raw, sourceOrigin, isMainFrame)
            override fun onRendererGone() = controller.cancel()
        }
        // Reprise automatique de la session enregistrée au lancement (écran de chargement en attendant).
        if (controller.uiState.value.access == AccessPhase.CHECKING) controller.restoreSaved()
    }

    override fun onCleared() {
        controller.dispose()
        page.listener = null
        page.release()
        super.onCleared()
    }
}
