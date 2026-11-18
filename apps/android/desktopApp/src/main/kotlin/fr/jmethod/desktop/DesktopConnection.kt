package fr.jmethod.desktop

import fr.jmethod.app.AppContainer
import fr.jmethod.app.pronote.LoginFlow
import fr.jmethod.app.pronote.NativeTransport
import fr.jmethod.app.pronote.NoOfficialPage
import fr.jmethod.app.pronote.PronoteConnectionController
import fr.jmethod.app.pronote.PronoteException
import fr.jmethod.app.ui.pronote.PronoteConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/**
 * Contrôleur de connexion de l’app ordinateur : connexion directe par identifiant et mot de passe
 * (protocole natif, lecture seule), sans page officielle ni QR ; aucune synchronisation d’arrière-plan.
 */
fun desktopConnection(container: AppContainer, scope: CoroutineScope): PronoteConnection = PronoteConnection(
    PronoteConnectionController(
        scope = scope,
        io = Dispatchers.IO,
        page = NoOfficialPage,
        vault = container.vault,
        transport = NativeTransport(),
        decodeQr = { throw PronoteException("qr", "QR non utilisé sur ordinateur.") },
        automationScript = { "" },
        today = { container.clock.today() },
        storeLessons = { container.storeLessons(it) },
        clearLessons = { container.clearLessons() },
        loginStore = container.loginStore,
        guard = container.sessionGuard,
        scheduler = null,
        now = { container.clock.now() },
        lastSync = {
            container.repository.document.value?.settings?.lastPronoteSync?.let { runCatching { Instant.parse(it) }.getOrNull() }
        },
        throttle = container.throttle,
        loginFlow = LoginFlow.DIRECT,
        device = "cet ordinateur",
    ),
)
