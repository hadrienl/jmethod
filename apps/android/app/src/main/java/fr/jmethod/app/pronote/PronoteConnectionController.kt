package fr.jmethod.app.pronote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Page officielle Pronote (WebView masquée ou visible), pilotée par le contrôleur. */
interface OfficialPage {
    /** URL actuelle du cadre principal, lue au moment de la validation d’un message. */
    val currentUrl: String?
    fun load(url: String)
    fun evaluate(script: String)
    /** Libère la WebView (fin de tentative). Une prochaine tentative en recrée une. */
    fun release()
    /** Supprime cookies et stockage web de la page officielle (déconnexion). */
    fun clearBrowsingData()
}

data class LoginForm(val address: String = "", val username: String = "", val password: String = "")

data class PronoteAccount(val name: String, val children: List<Child>, val selectedChildId: String?)

data class PronoteUiState(
    val title: String = "Compte Pronote",
    val message: String = "Connecte ton compte pour importer ton emploi du temps des deux prochaines semaines.",
    val busy: Boolean = false,
    /** Une tentative utilise la page officielle (visible ou masquée). */
    val browserStarted: Boolean = false,
    val showBrowser: Boolean = false,
    /** Le script est arrêté : l’utilisateur peut relancer l’association sur la page courante. */
    val automationStopped: Boolean = false,
    val hasSavedSession: Boolean = false,
    val account: PronoteAccount? = null,
    val form: LoginForm = LoginForm(),
    val isError: Boolean = false,
)

/**
 * Flux de connexion Pronote repris du POC validé : formulaire natif, page officielle masquée,
 * QR automatique, client HTTPS natif, reprise par jeton. Toute tentative porte un numéro
 * ([generation]) vérifié aux frontières réseau ; une annulation ou une déconnexion invalide
 * les résultats tardifs, y compris l’enregistrement du jeton.
 */
class PronoteConnectionController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val page: OfficialPage,
    private val vault: SessionVault,
    private val transport: PronoteTransport,
    private val decodeQr: (String) -> JSONObject,
    private val automationScript: () -> String,
    private val today: () -> LocalDate,
    /** Convertit et enregistre les créneaux lus ; appelé uniquement pour la tentative courante. */
    private val storeLessons: suspend (List<Lesson>) -> Unit,
    private val webMessagesSupported: Boolean = true,
    private val random: SecureRandom = SecureRandom(),
) {
    private val state = MutableStateFlow(PronoteUiState())
    val uiState: StateFlow<PronoteUiState> = state.asStateFlow()

    private val generation = AtomicInteger(0)
    private val vaultLock = Any()
    private val sessionMutex = Mutex()
    /** Incrémenté uniquement par la déconnexion : aucun résultat tardif ne peut recréer une session oubliée. */
    private val sessionEpoch = AtomicInteger(0)
    private var session: Session? = null
    private var activeJob: Job? = null
    private var watchdog: Job? = null
    private var nonce = ""
    private var pin = ""
    private var origin = ""
    private var claimed = false
    private var injected = false
    /** Identifiants du formulaire natif : en mémoire seulement, effacés à leur unique transmission. */
    private var pendingCredentials: Pair<String, String>? = null
    private var credentialMode = false

    init {
        val saved = vault.load() != null
        state.update { it.copy(hasSavedSession = saved) }
    }

    // region Formulaire

    fun onAddressChange(value: String) = state.update { it.copy(form = it.form.copy(address = value)) }
    fun onUsernameChange(value: String) = state.update { it.copy(form = it.form.copy(username = value)) }
    fun onPasswordChange(value: String) = state.update { it.copy(form = it.form.copy(password = value)) }

    fun submitCredentials() {
        val form = state.value.form
        val login = form.username.trim()
        val secret = form.password
        if (state.value.busy || login.isEmpty() || secret.isEmpty() || form.address.isBlank()) return
        state.update { it.copy(form = it.form.copy(password = "")) }
        open(form.address, login to secret)
    }

    fun openOfficialPage() {
        if (state.value.busy) return
        open(state.value.form.address, null)
    }

    // endregion

    // region Page officielle

    private fun newPin() = random.nextInt(10_000).toString().padStart(4, '0')

    private fun open(input: String, credentials: Pair<String, String>?) {
        val url = PronotePolicy.normalizedAddress(input)
        state.update { it.copy(form = it.form.copy(address = url)) }
        val uri = try {
            PronotePolicy.validatedUrl(url).also { require(!it.path.substringAfterLast('/').startsWith("mobile.")) }
        } catch (_: Exception) {
            state.update {
                it.copy(
                    title = "Adresse à vérifier", isError = true,
                    message = "Saisis l’adresse de ton espace Élèves ou Parents, par exemple school.index-education.net.",
                )
            }
            return
        }
        cancel(reset = false)
        generation.incrementAndGet()
        nonce = UUID.randomUUID().toString()
        pin = newPin()
        origin = uri.scheme + "://" + uri.authority
        claimed = false
        injected = false
        pendingCredentials = credentials
        credentialMode = credentials != null
        state.update {
            if (credentialMode) {
                it.copy(browserStarted = true, showBrowser = false, busy = true, automationStopped = false, isError = false,
                    title = "Connexion à Pronote", message = "Ouverture de la page Pronote…")
            } else {
                it.copy(browserStarted = true, showBrowser = true, busy = false, automationStopped = false, isError = false,
                    title = "Connecte-toi à Pronote",
                    message = "La connexion et les vérifications restent dans la page officielle. L’association sera ensuite automatique.")
            }
        }
        page.load(url)
        if (credentialMode) startWatchdog(90_000, "Pronote n’a pas répondu à temps. Vérifie l’adresse, puis réessaie ou termine la connexion dans la page affichée.")
        else startWatchdog(600_000, EXPIRED)
        if (!webMessagesSupported) failure("Mets à jour Android System WebView pour permettre l’association automatique sécurisée.")
    }

    fun onPageStarted() {
        injected = false
    }

    fun onPageFinished(url: String?) {
        if (!state.value.browserStarted || claimed || url == null || injected) return
        if (!PronotePolicy.isTrustedPage(url, origin)) {
            // ENT/CAS hors de l’origine Pronote : les identifiants ne sont jamais saisis ailleurs.
            if (credentialMode && url.startsWith("https://")) {
                reveal("Connexion par l’ENT", "Ton établissement passe par un ENT : termine la connexion dans la page affichée.", scriptStopped = false)
            }
            return
        }
        inject()
    }

    private fun inject() {
        injected = true
        val config = JSONObject().put("nonce", nonce).put("pin", pin).put("allowedOrigin", origin).put("demo", false)
        pendingCredentials?.let { (login, secret) -> config.put("credentials", JSONObject().put("username", login).put("password", secret)) }
        pendingCredentials = null
        state.update { it.copy(automationStopped = false) }
        page.evaluate("window.__JM_CONFIG=$config;\n${automationScript()}")
    }

    fun setBrowserVisible(visible: Boolean) {
        if (state.value.browserStarted) state.update { it.copy(showBrowser = visible) }
    }

    /** Relance l’automatisation sur la page Pronote courante (après MFA, ENT ou erreur), avec un nouveau nonce et un nouveau code. */
    fun resumeAutomation() {
        if (!state.value.browserStarted || !PronotePolicy.isTrustedPage(page.currentUrl, origin)) {
            state.update { it.copy(message = "Reviens sur la page Pronote de ton établissement, puis relance l’association.") }
            return
        }
        stopScript()
        generation.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        nonce = UUID.randomUUID().toString()
        pin = newPin()
        claimed = false
        pendingCredentials = null
        credentialMode = false
        state.update {
            it.copy(title = "Association automatique", isError = false,
                message = "Termine la connexion si besoin : l’association reprendra dès que Pronote sera ouvert.")
        }
        inject()
        startWatchdog(600_000, EXPIRED)
    }

    fun onBridgeMessage(raw: String?, sourceOrigin: String, isMainFrame: Boolean) {
        if (!state.value.browserStarted || claimed) return
        val event = BridgeGate.accept(raw, BridgeGate.Attempt(nonce, pin, origin), sourceOrigin, isMainFrame, page.currentUrl) ?: return
        when (event) {
            BridgeGate.Event.Authenticated -> {
                state.update {
                    it.copy(showBrowser = false, busy = true, isError = false, title = "Association automatique",
                        message = "Préparation du QR code temporaire…")
                }
                startWatchdog(45_000, "L’association a pris trop de temps. Reprends la connexion dans Pronote.")
            }
            is BridgeGate.Event.Progress -> state.update { it.copy(message = progressMessage(event.stage)) }
            is BridgeGate.Event.Failure -> when (event.code) {
                "LOGIN_REJECTED" -> if (credentialMode) rejected() else failure(REJECTED)
                "MFA_REQUIRED" -> reveal("Vérification demandée", "Pronote demande une vérification supplémentaire : termine-la dans la page affichée.", scriptStopped = true)
                "LOGIN_FORM_NOT_FOUND" -> reveal("Connexion à terminer", "Le formulaire de connexion Pronote n’a pas été reconnu : connecte-toi directement dans la page affichée.", scriptStopped = true)
                "LOGIN_STALLED" -> reveal("Connexion à terminer", "Pronote ne répond pas après l’envoi de tes identifiants : vérifie la page affichée.", scriptStopped = true)
                else -> failure("L’association automatique s’est arrêtée. Affiche Pronote, puis relance l’association si nécessaire.")
            }
            is BridgeGate.Event.Qr -> {
                // Réclamation atomique du premier QR : tout message ultérieur est ignoré.
                claimed = true
                watchdog?.cancel()
                stopScript()
                connectQr(event.image, pin)
            }
        }
    }

    private fun progressMessage(stage: String) = when (stage) {
        "filling-login" -> "Saisie de tes identifiants…"
        "submitting-login" -> "Connexion à Pronote…"
        "opening-qr" -> "Ouverture du QR code…"
        "filling-code" -> "Création du code temporaire…"
        "generating-qr" -> "Génération du QR code…"
        else -> "Association en cours…"
    }

    private fun stopScript() = page.evaluate("window.__JM_PRONOTE_POC?.stop()")

    private fun startWatchdog(millis: Long, text: String) {
        watchdog?.cancel()
        val attempt = generation.get()
        watchdog = scope.launch {
            delay(millis)
            if (attempt == generation.get() && !claimed) failure(text)
        }
    }

    private fun reveal(title: String, text: String, scriptStopped: Boolean) {
        pendingCredentials = null
        credentialMode = false
        state.update { it.copy(busy = false, showBrowser = true, title = title, message = text, automationStopped = scriptStopped, isError = false) }
        if (scriptStopped) watchdog?.cancel() else startWatchdog(600_000, EXPIRED)
    }

    private fun failure(text: String) {
        watchdog?.cancel()
        pendingCredentials = null
        credentialMode = false
        val started = state.value.browserStarted
        state.update {
            it.copy(busy = false, title = "Connexion à reprendre", message = text, isError = true,
                automationStopped = started, showBrowser = started)
        }
        if (started) stopScript()
    }

    /** Identifiants refusés : la page reste masquée et le formulaire natif revient. */
    private fun rejected() {
        cancel(reset = false)
        page.release()
        state.update {
            it.copy(browserStarted = false, showBrowser = false, title = "Identifiants refusés", isError = true, message = REJECTED)
        }
    }

    // endregion

    // region Session native

    /**
     * Liaison d’un client à une tentative. Les requêtes ne partent que pour la tentative courante ;
     * le jeton renouvelé est enregistré aussitôt, sauf après une déconnexion ou, tant que le client
     * n’est pas adopté, après l’annulation de sa tentative.
     */
    private inner class Binding(@Volatile var attempt: Int) {
        private val epoch = sessionEpoch.get()
        @Volatile var adopted = false

        private fun current() = attempt == generation.get() && epoch == sessionEpoch.get()

        val transport = PronoteTransport { method, url, body, headers ->
            if (!current()) throw CancellationException()
            this@PronoteConnectionController.transport.request(method, url, body, headers).also { if (!current()) throw CancellationException() }
        }

        val save: (Credentials) -> Unit = { credentials ->
            synchronized(vaultLock) {
                if (epoch == sessionEpoch.get() && (adopted || attempt == generation.get())) vault.save(credentials)
            }
        }
    }

    private class Session(val client: PronoteClient, val binding: Binding)

    private fun connectQr(image: String, code: String) {
        val attempt = generation.get()
        state.update { it.copy(busy = true, isError = false, title = "Connexion native", message = "Vérification du jeton et récupération des cours…") }
        activeJob = scope.launch {
            try {
                val binding = Binding(attempt)
                val connection = sessionMutex.withLock {
                    withContext(io) {
                        val credentials = PronotePolicy.credentialsFromQr(decodeQr(image), code)
                        PronoteClient(binding.transport, credentials, binding.save).also { it.login(PronoteClient.Mode.QR) }
                    }
                }
                if (attempt != generation.get()) return@launch
                synchronized(vaultLock) { if (attempt == generation.get()) vault.saveSelectedChildId(null) }
                binding.adopted = true
                val adopted = Session(connection, binding)
                session = adopted
                state.update { it.copy(browserStarted = false, showBrowser = false, automationStopped = false, hasSavedSession = true, form = it.form.copy(username = "", password = "")) }
                page.release()
                sync(attempt, adopted)
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (attempt == generation.get()) failure(messageFor(error, "Connexion impossible. Retourne à Pronote pour réessayer."))
            }
        }
    }

    /** Reprise par jeton enregistré (au lancement ou à la demande). */
    fun restoreSaved() {
        if (state.value.busy || state.value.browserStarted) return
        val credentials = vault.load() ?: run {
            state.update { it.copy(hasSavedSession = false) }
            return
        }
        val attempt = generation.incrementAndGet()
        state.update { it.copy(busy = true, isError = false, title = "Reconnexion à Pronote", message = "Vérification de la session enregistrée…") }
        activeJob = scope.launch {
            try {
                val binding = Binding(attempt)
                val connection = sessionMutex.withLock {
                    withContext(io) { PronoteClient(binding.transport, credentials, binding.save).also { it.login(PronoteClient.Mode.TOKEN) } }
                }
                if (attempt != generation.get()) return@launch
                binding.adopted = true
                val adopted = Session(connection, binding)
                session = adopted
                sync(attempt, adopted)
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (attempt != generation.get()) return@launch
                if (error is PronoteException && error.code == "login") {
                    // Jeton refusé : il est définitivement invalide, une nouvelle association est nécessaire.
                    synchronized(vaultLock) { vault.clear() }
                    state.update {
                        it.copy(busy = false, hasSavedSession = false, account = null, isError = true, title = "Session expirée",
                            message = "Pronote n’accepte plus la session enregistrée. Reconnecte ton compte.")
                    }
                } else {
                    state.update {
                        it.copy(busy = false, isError = true, title = "Synchronisation impossible",
                            message = messageFor(error, "La reconnexion à Pronote a échoué. Réessaie plus tard."))
                    }
                }
            }
        }
    }

    /** Relit les 14 prochains jours (session existante, reconnectée par jeton si elle a expiré). */
    fun refresh(childId: String? = null) {
        val current = session
        if (current == null) {
            restoreSaved()
            return
        }
        if (state.value.busy) return
        val attempt = generation.get()
        if (childId != null) synchronized(vaultLock) { vault.saveSelectedChildId(childId) }
        activeJob = scope.launch { sync(attempt, current, childId) }
    }

    fun selectChild(childId: String) = refresh(childId)

    private suspend fun sync(attempt: Int, current: Session, childId: String? = null) {
        val connection = current.client
        current.binding.attempt = attempt
        state.update { it.copy(busy = true, isError = false, title = "Synchronisation", message = "Lecture de l’emploi du temps des 14 prochains jours…") }
        try {
            val lessons = sessionMutex.withLock {
                withContext(io) {
                    val wanted = childId ?: synchronized(vaultLock) { vault.selectedChildId() }
                    try {
                        read(connection, wanted)
                    } catch (error: PronoteException) {
                        // Session Pronote expirée : une seule reconnexion par jeton, puis nouvel essai.
                        if (error.code !in setOf("pronote-10", "pronote-22", "http")) throw error
                        connection.login(PronoteClient.Mode.TOKEN)
                        read(connection, wanted)
                    }
                }
            }
            if (attempt != generation.get()) return
            storeLessons(lessons)
            if (attempt != generation.get()) return
            state.update {
                it.copy(busy = false, isError = false, hasSavedSession = true, title = "Pronote connecté",
                    message = "Emploi du temps à jour. Le jeton de connexion reste chiffré sur ce téléphone.",
                    account = PronoteAccount(connection.accountName, connection.children, connection.selectedChildId))
            }
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            if (attempt == generation.get()) {
                state.update {
                    it.copy(busy = false, isError = true, title = "Synchronisation impossible",
                        message = messageFor(error, "Lecture de l’emploi du temps impossible. Réessaie plus tard."),
                        account = PronoteAccount(connection.accountName, connection.children, connection.selectedChildId))
                }
            }
        }
    }

    private fun read(connection: PronoteClient, childId: String?): List<Lesson> {
        if (childId != null && connection.children.any { it.id == childId }) connection.selectChild(childId)
        return connection.timetable(today(), 14)
    }

    private fun messageFor(error: Exception, fallback: String): String = when {
        error is PronoteException && error.code == "mfa" ->
            "Pronote demande une vérification de sécurité : reconnecte ton compte depuis la page officielle."
        error is PronoteException && error.code == "pronote-25" ->
            "Pronote a bloqué temporairement les connexions après trop d’essais. Réessaie plus tard."
        error is PronoteException -> error.message
        error is IOException -> "Pronote est injoignable. Vérifie ta connexion internet, puis réessaie."
        else -> fallback
    }

    // endregion

    /** Annule la tentative en cours et invalide tous ses résultats tardifs. */
    fun cancel(reset: Boolean = true) {
        generation.incrementAndGet()
        watchdog?.cancel()
        activeJob?.cancel()
        activeJob = null
        pendingCredentials = null
        credentialMode = false
        claimed = true
        if (state.value.browserStarted) stopScript()
        state.update { it.copy(busy = false, automationStopped = false) }
        if (reset) {
            page.release()
            state.update {
                it.copy(browserStarted = false, showBrowser = false, isError = false, title = "Connexion annulée",
                    message = "Tu peux relancer la connexion quand tu veux.")
            }
        }
    }

    /** Déconnexion : jeton, enfant choisi, cookies et stockage de la page officielle supprimés. */
    fun disconnect() {
        cancel(reset = false)
        session = null
        synchronized(vaultLock) {
            sessionEpoch.incrementAndGet()
            vault.clear()
        }
        page.clearBrowsingData()
        page.release()
        state.update {
            it.copy(browserStarted = false, showBrowser = false, account = null, hasSavedSession = false, isError = false,
                title = "Compte Pronote déconnecté",
                message = "Le jeton de connexion a été supprimé de ce téléphone. Les cours déjà importés restent affichés.")
        }
    }

    fun dispose() {
        generation.incrementAndGet()
        watchdog?.cancel()
        activeJob?.cancel()
    }

    private companion object {
        const val EXPIRED = "La tentative de connexion a expiré. Relance-la pour continuer."
        const val REJECTED = "Pronote a refusé l’identifiant ou le mot de passe. Vérifie-les puis réessaie."
    }
}
