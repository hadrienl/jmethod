package fr.jmethod.app.ui.pronote

import androidx.webkit.WebViewFeature
import fr.jmethod.app.JMethodApplication
import fr.jmethod.app.pronote.NativeTransport
import fr.jmethod.app.pronote.PronoteConnectionController
import fr.jmethod.app.pronote.QrImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/**
 * Contrôleur de connexion Android : page officielle masquée (WebView), QR et protocole mobile.
 * Créé par le `PronoteViewModel`, il survit aux recréations d’activité avec sa WebView.
 */
fun androidConnection(application: JMethodApplication, scope: CoroutineScope): PronoteConnection {
    val container = application.container
    val page = WebViewOfficialPage(application)
    val script by lazy { application.assets.open("PronoteAutomation.js").bufferedReader().use { it.readText() } }
    val controller = PronoteConnectionController(
        scope = scope,
        io = Dispatchers.IO,
        page = page,
        vault = container.vault,
        transport = NativeTransport(),
        decodeQr = QrImageDecoder::decode,
        automationScript = { script },
        today = { container.clock.today() },
        storeLessons = { container.storeLessons(it) },
        clearLessons = { container.clearLessons() },
        loginStore = container.loginStore,
        guard = container.sessionGuard,
        scheduler = container.scheduler,
        now = { container.clock.now() },
        lastSync = {
            container.repository.document.value?.settings?.lastPronoteSync?.let { runCatching { Instant.parse(it) }.getOrNull() }
        },
        webMessagesSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
        throttle = container.throttle,
        appUuid = application.appIdentity::uuid,
    )
    page.listener = object : OfficialPageListener {
        override fun onPageStarted() = controller.onPageStarted()
        override fun onPageFinished(url: String?) = controller.onPageFinished(url)
        override fun onBridgeMessage(raw: String?, sourceOrigin: String, isMainFrame: Boolean) =
            controller.onBridgeMessage(raw, sourceOrigin, isMainFrame)
        override fun onRendererGone() = controller.cancel()
    }
    return PronoteConnection(controller, page) {
        page.listener = null
        page.release()
    }
}
