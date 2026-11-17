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

/** Type d’espace Pronote : page `eleve.html` ou `parent.html`. */
enum class AccountType(val page: String) { STUDENT("eleve.html"), PARENT("parent.html") }

data class LoginForm(
    /** Code de l’établissement (`abcd1234`), host ou adresse complète. */
    val school: String = "",
    val accountType: AccountType = AccountType.STUDENT,
    val username: String = "",
    val password: String = "",
)

/** Établissement et type de compte mémorisés pour préremplir le formulaire (jamais le mot de passe). */
interface LoginPreferences {
    fun load(): Pair<String, AccountType>?
    fun save(school: String, accountType: AccountType)
}

data class PronoteAccount(val name: String, val children: List<Child>, val selectedChildId: String?)

/** Accès à l’app : il faut une connexion Pronote active (ou un jeton enregistré, hors ligne). */
enum class AccessPhase {
    /** Jeton enregistré : reprise en cours au lancement (écran de chargement). */
    CHECKING,
    /** Aucun jeton, jeton refusé ou déconnexion : écran de connexion plein écran. */
    SIGNED_OUT,
    /** Session reprise ou nouvelle connexion, emploi du temps lu. */
    CONNECTED,
    /** Jeton conservé mais Pronote injoignable : l’app s’ouvre sur les données locales. */
    OFFLINE,
}

data class PronoteUiState(
    val access: AccessPhase = AccessPhase.SIGNED_OUT,
    val title: String = "Compte Pronote",
    val message: String = "Connecte ton compte pour importer ton emploi du temps des deux prochaines semaines.",
    val busy: Boolean = false,
    /** Une tentative utilise la page officielle (visible ou masquée). */
    val browserStarted: Boolean = false,
    val showBrowser: Boolean = false,
    val hasSavedSession: Boolean = false,
    val account: PronoteAccount? = null,
    val form: LoginForm = LoginForm(),
    val isError: Boolean = false,
    /** Erreur affichée sous le bouton « Se connecter ». */
    val formError: String? = null,
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
    /** Supprime les créneaux Pronote importés (déconnexion). */
    private val clearLessons: suspend () -> Unit = {},
    private val loginPreferences: LoginPreferences? = null,
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

    init {
        val saved = vault.load() != null
        state.update {
            if (saved) {
                it.copy(hasSavedSession = true, access = AccessPhase.CHECKING, busy = true, title = "Reconnexion à Pronote",
                    message = "Vérification de la session enregistrée…")
            } else {
                it.copy(hasSavedSession = false, access = AccessPhase.SIGNED_OUT,
                    title = "Connecte ton compte Pronote", message = "J-Method importe ton emploi du temps des deux prochaines semaines.")
            }
        }
        loginPreferences?.load()?.let { (school, type) ->
            state.update { it.copy(form = it.form.copy(school = school, accountType = type)) }
        }
    }

    // region Formulaire

    fun onSchoolChange(value: String) = state.update { it.copy(form = it.form.copy(school = value), formError = null) }
    fun onAccountTypeChange(value: AccountType) = state.update { it.copy(form = it.form.copy(accountType = value), formError = null) }
    fun onUsernameChange(value: String) = state.update { it.copy(form = it.form.copy(username = value), formError = null) }
    fun onPasswordChange(value: String) = state.update { it.copy(form = it.form.copy(password = value), formError = null) }

    fun submitCredentials() {
        val form = state.value.form
        val login = form.username.trim()
        val secret = form.password
        if (state.value.busy || state.value.browserStarted || login.isEmpty() || secret.isEmpty() || form.school.isBlank()) return
        val url = PronotePolicy.schoolAddress(form.school, form.accountType)
        val uri = try {
            PronotePolicy.validatedUrl(url).also { require(!it.path.substringAfterLast('/').startsWith("mobile.")) }
        } catch (_: Exception) {
            state.update { it.copy(formError = "Établissement introuvable. Saisis le code de ton établissement (par exemple abcd1234) ou l’adresse de ta page Pronote.") }
            return
        }
        // La page d’une adresse complète prime sur le choix Élève/Parent : le choix est mis à jour.
        val type = if (uri.path.endsWith(AccountType.PARENT.page)) AccountType.PARENT else AccountType.STUDENT
        state.update { it.copy(form = it.form.copy(password = "", accountType = type), formError = null) }
        open(url, uri, login to secret)
    }

    // endregion

    // region Page officielle

    private fun newPin() = random.nextInt(10_000).toString().padStart(4, '0')

    private fun open(url: String, uri: java.net.URI, credentials: Pair<String, String>) {
        cancel(reset = false)
        generation.incrementAndGet()
        nonce = UUID.randomUUID().toString()
        pin = newPin()
        origin = uri.scheme + "://" + uri.authority
        claimed = false
        injected = false
        pendingCredentials = credentials
        state.update {
            it.copy(browserStarted = true, showBrowser = false, busy = true, isError = false, formError = null,
                title = "Connexion à Pronote", message = "Connexion en cours…")
        }
        page.load(url)
        startWatchdog(90_000, "Pronote n’a pas répondu à temps. Vérifie l’établissement et ta connexion internet, puis réessaie.")
        if (!webMessagesSupported) failure("Mets à jour Android System WebView depuis le Play Store, puis réessaie.")
    }

    fun onPageStarted() {
        injected = false
    }

    fun onPageFinished(url: String?) {
        if (!state.value.browserStarted || claimed || url == null || injected) return
        if (!PronotePolicy.isTrustedPage(url, origin)) {
            // ENT/CAS hors de l’origine Pronote : la page est affichée, les identifiants n’y sont jamais saisis.
            if (url.startsWith("https://") && !state.value.showBrowser) {
                pendingCredentials = null
                reveal("Connexion par l’ENT", "Ton établissement passe par son ENT : termine la connexion dans cette page.")
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
        page.evaluate("window.__JM_CONFIG=$config;\n${automationScript()}")
    }

    /**
     * Le formulaire officiel n’a pas pu être rempli : la page est affichée pour que l’utilisateur se
     * connecte lui-même, et l’automatisation est réarmée (nouveau nonce, nouveau code) pour la suite.
     */
    private fun rearmOnVisiblePage(title: String, text: String) {
        stopScript()
        generation.incrementAndGet()
        nonce = UUID.randomUUID().toString()
        pin = newPin()
        pendingCredentials = null
        reveal(title, text)
        if (PronotePolicy.isTrustedPage(page.currentUrl, origin)) inject()
    }

    fun onBridgeMessage(raw: String?, sourceOrigin: String, isMainFrame: Boolean) {
        if (!state.value.browserStarted || claimed) return
        val event = BridgeGate.accept(raw, BridgeGate.Attempt(nonce, pin, origin), sourceOrigin, isMainFrame, page.currentUrl) ?: return
        when (event) {
            BridgeGate.Event.Authenticated -> {
                hidePage()
                startWatchdog(45_000, "La connexion a pris trop de temps. Réessaie dans quelques instants.")
            }
            is BridgeGate.Event.Progress -> if (event.stage == "mfa-done") {
                // Vérification terminée par l’utilisateur : le script reprend seul, la page est masquée.
                hidePage()
                startWatchdog(45_000, "La connexion a pris trop de temps. Réessaie dans quelques instants.")
            } else if (!state.value.showBrowser) {
                state.update { it.copy(message = progressMessage(event.stage)) }
            }
            is BridgeGate.Event.Failure -> when (event.code) {
                "LOGIN_REJECTED" -> rejected()
                // Non terminal : la tentative (nonce, code) reste valide, le script attend la fin de la vérification.
                "MFA_REQUIRED" -> reveal("Vérification de sécurité", "Pronote demande une vérification : termine-la dans cette page, la connexion continuera ensuite.")
                "LOGIN_FORM_NOT_FOUND", "LOGIN_STALLED" ->
                    rearmOnVisiblePage("Termine la connexion", "Connecte-toi dans cette page Pronote : J-Method continuera ensuite automatiquement.")
                else -> failure("La connexion à Pronote n’a pas abouti. Réessaie dans quelques instants.")
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

    private fun hidePage() = state.update {
        it.copy(showBrowser = false, busy = true, isError = false, title = "Connexion à Pronote", message = "Connexion en cours…")
    }

    private fun progressMessage(stage: String) = when (stage) {
        "filling-login", "submitting-login" -> "Connexion en cours…"
        else -> "Préparation de ton emploi du temps…"
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

    /** Page officielle affichée en plein écran (ENT, vérification) ; la tentative continue, délai étendu. */
    private fun reveal(title: String, text: String) {
        state.update { it.copy(busy = false, showBrowser = true, title = title, message = text, isError = false) }
        startWatchdog(600_000, "La connexion a expiré. Réessaie quand tu veux.")
    }

    /** Échec : la tentative est abandonnée et le formulaire revient avec un message. */
    private fun failure(text: String) {
        cancel(reset = false)
        page.release()
        state.update { it.copy(browserStarted = false, showBrowser = false, isError = true, title = "Connexion impossible", message = text, formError = text) }
    }

    /** Identifiants refusés : la page reste masquée et le formulaire natif revient. */
    private fun rejected() = failure(REJECTED)

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
        state.update { it.copy(busy = true, isError = false, title = "Connexion à Pronote", message = "Récupération de ton emploi du temps…") }
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
                state.value.form.let { loginPreferences?.save(it.school.trim(), it.accountType) }
                binding.adopted = true
                val adopted = Session(connection, binding)
                session = adopted
                state.update { it.copy(browserStarted = false, showBrowser = false, hasSavedSession = true, form = it.form.copy(password = "")) }
                page.release()
                sync(attempt, adopted)
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (attempt == generation.get()) failure(messageFor(error, "La connexion à Pronote n’a pas abouti. Réessaie dans quelques instants."))
            }
        }
    }

    /** Reprise par jeton enregistré (au lancement ou à la demande). */
    fun restoreSaved() {
        if (state.value.browserStarted || activeJob?.isActive == true) return
        val credentials = vault.load() ?: run {
            state.update { it.copy(hasSavedSession = false, busy = false, access = AccessPhase.SIGNED_OUT) }
            return
        }
        val attempt = generation.incrementAndGet()
        state.update { it.copy(busy = true, isError = false, title = "Reconnexion à Pronote", message = "Connexion en cours…") }
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
                if (!signOutIfRefused(error)) {
                    // Problème réseau ou serveur : le jeton est conservé et l’app s’ouvre hors ligne.
                    state.update {
                        it.copy(busy = false, isError = true, access = AccessPhase.OFFLINE, title = offlineTitle(error),
                            message = messageFor(error, "La reconnexion à Pronote a échoué. Réessaie plus tard."))
                    }
                }
            }
        }
    }

    /** Jeton refusé (ou vérification de sécurité exigée) : il est effacé et l’écran de connexion revient. */
    private fun signOutIfRefused(error: Exception): Boolean {
        if (error !is PronoteException || error.code !in setOf("login", "mfa")) return false
        session = null
        synchronized(vaultLock) { vault.clear() }
        state.update {
            it.copy(busy = false, hasSavedSession = false, account = null, isError = true, access = AccessPhase.SIGNED_OUT,
                title = "Reconnecte-toi",
                message = if (error.code == "mfa") messageFor(error, "") else "Ta connexion à Pronote a expiré. Reconnecte-toi pour continuer.",
                formError = if (error.code == "mfa") messageFor(error, "") else "Ta connexion à Pronote a expiré. Reconnecte-toi pour continuer.")
        }
        return true
    }

    private fun offlineTitle(error: Exception) = if (error is IOException) "Hors ligne" else "Pronote indisponible"

    /** Bandeau hors ligne : nouvel essai de reprise ou de synchronisation, sans déconnecter. */
    fun retry() {
        if (session == null) restoreSaved() else refresh()
    }

    /** Ouvre l’app sur les données locales sans attendre la fin de la reprise. */
    fun skipResume() {
        if (state.value.access != AccessPhase.CHECKING) return
        cancel(reset = false)
        state.update {
            it.copy(access = AccessPhase.OFFLINE, isError = true, title = "Synchronisation interrompue",
                message = "Les données enregistrées sur ce téléphone sont affichées. Réessaie quand tu veux.")
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
                it.copy(busy = false, isError = false, hasSavedSession = true, access = AccessPhase.CONNECTED, title = "Pronote connecté",
                    message = "Emploi du temps à jour.",
                    account = PronoteAccount(connection.accountName, connection.children, connection.selectedChildId))
            }
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            if (attempt == generation.get() && !signOutIfRefused(error)) {
                state.update {
                    it.copy(busy = false, isError = true, title = if (it.access == AccessPhase.CONNECTED) "Synchronisation impossible" else offlineTitle(error),
                        access = if (it.access == AccessPhase.CONNECTED) AccessPhase.CONNECTED else AccessPhase.OFFLINE,
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

    /** Messages sans détail technique ; les codes internes ne sont jamais affichés. */
    private fun messageFor(error: Exception, fallback: String): String = when {
        error is PronoteException && error.code == "mfa" ->
            "Pronote demande une vérification de sécurité. Reconnecte-toi pour la terminer."
        error is PronoteException && error.code == "pronote-25" ->
            "Pronote a bloqué temporairement les connexions après trop d’essais. Réessaie plus tard."
        error is PronoteException && error.code == "url" -> error.message
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
        claimed = true
        if (state.value.browserStarted) stopScript()
        state.update {
            // Reprise interrompue au lancement : le jeton reste, l’app s’ouvre hors ligne.
            val access = if (it.access == AccessPhase.CHECKING) AccessPhase.OFFLINE else it.access
            it.copy(busy = false, access = access)
        }
        if (reset) {
            page.release()
            state.update {
                it.copy(browserStarted = false, showBrowser = false, isError = false, title = "Connexion annulée",
                    message = "Tu peux relancer la connexion quand tu veux.")
            }
        }
    }

    /** Déconnexion : jeton, enfant choisi, cookies, stockage web et créneaux importés supprimés ; cours et révisions restent. */
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
                access = AccessPhase.SIGNED_OUT, title = "Tu es déconnecté", formError = null,
                message = "Ton emploi du temps importé a été retiré de ce téléphone. Tes cours et tes révisions restent enregistrés.")
        }
        scope.launch { clearLessons() }
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
