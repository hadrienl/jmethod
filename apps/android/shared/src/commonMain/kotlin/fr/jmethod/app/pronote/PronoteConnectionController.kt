package fr.jmethod.app.pronote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
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
    /** Supprime cookies, stockage web et cache de la page officielle (connexion réussie, déconnexion). */
    fun clearBrowsingData()
}

/**
 * Parcours de connexion. OFFICIAL_PAGE (Android, iOS) : page officielle masquée, QR, protocole mobile
 * et jeton. DIRECT (ordinateur) : identifiant et mot de passe envoyés par le protocole natif à la page
 * `?login=true`, sans page officielle ni jeton ; la session vit en mémoire le temps du lancement.
 */
enum class LoginFlow { OFFICIAL_PAGE, DIRECT }

/** Type d’espace Pronote : page `eleve.html` ou `parent.html`. */
enum class AccountType(val page: String) { STUDENT("eleve.html"), PARENT("parent.html") }

data class LoginForm(
    /** Code de l’établissement (`abcd1234`), host ou adresse complète. */
    val school: String = "",
    val accountType: AccountType = AccountType.STUDENT,
    val username: String = "",
    val password: String = "",
    /** « Rester connecté » : identifiant et mot de passe enregistrés chiffrés après une connexion réussie. */
    val stayConnected: Boolean = true,
    /**
     * Le champ contient le mot de passe enregistré, prérempli et masqué : il ne peut jamais être
     * affiché. Dès la première modification, il est remplacé par la seule saisie de l’utilisateur.
     */
    val passwordFromStore: Boolean = false,
) {
    override fun toString(): String =
        "LoginForm(school=$school, accountType=$accountType, username=<masqué>, password=<masqué>, stayConnected=$stayConnected)"

    /** Nouvelle valeur du champ mot de passe ; un mot de passe enregistré n’est jamais conservé partiellement. */
    fun editPassword(value: String): LoginForm {
        if (!passwordFromStore) return copy(password = value)
        val typed = if (value.length > password.length && value.startsWith(password)) value.substring(password.length) else ""
        return copy(password = typed, passwordFromStore = false)
    }
}

data class PronoteAccount(val name: String, val children: List<Child>, val selectedChildId: String?)

/** Accès à l’app : il faut une connexion Pronote active (ou une session enregistrée, hors ligne). */
enum class AccessPhase {
    /** Session enregistrée : reprise en cours au lancement (écran de chargement). */
    CHECKING,
    /** Aucune session, jeton refusé sans identifiants enregistrés ou déconnexion : écran de connexion. */
    SIGNED_OUT,
    /** Session reprise ou nouvelle connexion, emploi du temps lu. */
    CONNECTED,
    /** Session conservée mais Pronote injoignable : l’app s’ouvre sur les données locales. */
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
    /** Reconnexion silencieuse en cours avec les identifiants enregistrés (bandeau discret). */
    val reconnecting: Boolean = false,
    /** Identifiant et mot de passe enregistrés (« Rester connecté »). */
    val hasStoredPassword: Boolean = false,
    val account: PronoteAccount? = null,
    val form: LoginForm = LoginForm(),
    val isError: Boolean = false,
    /** Erreur affichée sous le bouton « Se connecter ». */
    val formError: String? = null,
)

/**
 * Flux de connexion Pronote repris du POC validé : formulaire natif, page officielle masquée,
 * QR automatique, client HTTPS natif, reprise par jeton, reconnexion silencieuse avec les
 * identifiants enregistrés. Toute tentative porte un numéro ([generation]) vérifié aux frontières
 * réseau ; une annulation ou une déconnexion invalide les résultats tardifs.
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
    private val loginStore: LoginStore? = null,
    /** Verrou partagé avec la synchronisation d’arrière-plan. */
    private val guard: PronoteSessionGuard = PronoteSessionGuard(),
    private val scheduler: BackgroundScheduler? = null,
    private val now: () -> Instant = { Instant.now() },
    /** Date de la dernière synchronisation enregistrée dans le document. */
    private val lastSync: () -> Instant? = { null },
    private val webMessagesSupported: Boolean = true,
    private val random: SecureRandom = SecureRandom(),
    /** Téléchargement des pièces jointes du cahier de textes (même hôte, cookies de la session). */
    private val files: FileTransport = NativeFileTransport(),
    /** Erreur 25 et plafond des reconnexions silencieuses, partagés avec l’arrière-plan. */
    private val throttle: PronoteThrottle = MemoryThrottle(),
    /** UUID d’application stable, utilisé pour toutes les connexions par QR. */
    private val appUuid: () -> String = { UUID.randomUUID().toString() },
    private val loginFlow: LoginFlow = LoginFlow.OFFICIAL_PAGE,
    /** Appareil dans les messages : « ce téléphone » ou « cet ordinateur ». */
    private val device: String = "ce téléphone",
) {
    private val direct = loginFlow == LoginFlow.DIRECT
    /** Reconnexion d’une session établie : par jeton (mobile) ou par mot de passe (connexion directe). */
    private val reloginMode = if (direct) PronoteClient.Mode.PASSWORD else PronoteClient.Mode.TOKEN
    private val state = MutableStateFlow(PronoteUiState())
    val uiState: StateFlow<PronoteUiState> = state.asStateFlow()

    private val generation = AtomicInteger(0)
    private var session: Session? = null
    private var activeJob: Job? = null
    private var watchdog: Job? = null
    private var nonce = ""
    private var pin = ""
    private var origin = ""
    private var claimed = false
    private var injected = false
    /** Identifiants du formulaire : transmis une seule fois à la page officielle. */
    private var pendingCredentials: Pair<String, String>? = null
    /** Identifiants de la tentative en cours, enregistrés seulement après une connexion réussie. */
    private var attemptLogin: SavedLogin? = null
    private var attemptStayConnected = false
    /** Tentative en cours : reconnexion silencieuse avec les identifiants enregistrés. */
    private var silent = false
    /** Une seule reconnexion silencieuse automatique par jeton refusé. */
    private var silentUsed = false

    init {
        val stored = loginStore?.load()
        val hasPassword = stored?.username != null && stored.password != null
        // Après une déconnexion volontaire, les identifiants servent seulement à préremplir.
        val autoReconnect = hasPassword && stored?.autoReconnect == true
        var token = vault.load() != null
        if (token && vault.needsRelogin()) {
            // Jeton refusé en arrière-plan : il est abandonné au profit d’une reconnexion au premier plan.
            synchronized(guard.vaultLock) { vault.clear() }
            token = false
        }
        state.update {
            val form = if (stored == null) it.form else prefilled(it.form, stored)
            if (token || autoReconnect) {
                it.copy(hasSavedSession = token, hasStoredPassword = hasPassword, access = AccessPhase.CHECKING, busy = true,
                    title = "Reconnexion à Pronote", message = "Connexion en cours…", form = form)
            } else {
                it.copy(hasSavedSession = false, hasStoredPassword = false, access = AccessPhase.SIGNED_OUT, form = form,
                    title = "Connecte ton compte Pronote", message = "J-Method importe ton emploi du temps des deux prochaines semaines.")
            }
        }
    }

    /** Formulaire prérempli avec tout ce qui est enregistré, mot de passe compris (champ masqué). */
    private fun prefilled(form: LoginForm, stored: SavedLogin) = form.copy(
        school = stored.school, accountType = stored.accountType,
        username = stored.username.orEmpty(), password = stored.password.orEmpty(),
        passwordFromStore = stored.password != null,
    )

    /** Lancement : reprise par jeton, ou reconnexion silencieuse si seuls les identifiants sont enregistrés. */
    fun start() {
        if (state.value.access != AccessPhase.CHECKING) return
        if (throttle.isBlocked(now())) {
            showBlocked()
            return
        }
        if (vault.load() != null) restoreSaved() else startSilent(automatic = true)
    }

    /** Erreur 25 mémorisée : aucun essai, l’app s’ouvre sur les données locales avec un message clair. */
    private fun showBlocked() {
        val hasSession = vault.load() != null || state.value.hasStoredPassword
        state.update {
            if (it.access == AccessPhase.SIGNED_OUT && !hasSession) {
                it.copy(busy = false, reconnecting = false, isError = true, formError = PronoteThrottle.BLOCKED_MESSAGE, message = PronoteThrottle.BLOCKED_MESSAGE)
            } else {
                it.copy(busy = false, reconnecting = false, isError = true,
                    access = if (it.access == AccessPhase.CONNECTED) AccessPhase.CONNECTED else AccessPhase.OFFLINE,
                    title = "Pronote indisponible", message = PronoteThrottle.BLOCKED_MESSAGE)
            }
        }
    }

    /** Erreur 25 : plus aucun essai automatique pendant au moins une heure. */
    private fun noteThrottle(error: Throwable?) {
        if (PronoteThrottle.isBlockedError(error)) throttle.block(now().plus(PronoteThrottle.BLOCK_DURATION))
    }

    // region Formulaire

    fun onSchoolChange(value: String) = state.update { it.copy(form = it.form.copy(school = value), formError = null) }
    fun onAccountTypeChange(value: AccountType) = state.update { it.copy(form = it.form.copy(accountType = value), formError = null) }
    fun onUsernameChange(value: String) = state.update { it.copy(form = it.form.copy(username = value), formError = null) }
    fun onPasswordChange(value: String) = state.update { it.copy(form = it.form.editPassword(value), formError = null) }
    fun onStayConnectedChange(value: Boolean) = state.update { it.copy(form = it.form.copy(stayConnected = value)) }

    fun submitCredentials() {
        val form = state.value.form
        val login = form.username.trim()
        val secret = form.password
        if (state.value.busy || state.value.browserStarted || login.isEmpty() || secret.isEmpty() || form.school.isBlank()) return
        val target = resolve(form.school, form.accountType) ?: run {
            state.update { it.copy(formError = "Établissement introuvable. Saisis le code de ton établissement (par exemple abcd1234) ou l’adresse de ta page Pronote.") }
            return
        }
        val (url, uri, type) = target
        state.update { it.copy(form = it.form.copy(password = "", passwordFromStore = false, accountType = type), formError = null) }
        val saved = SavedLogin(form.school.trim(), type, login, secret)
        if (direct) {
            openDirect(url, uri, login to secret, silentAttempt = false, login = saved, stayConnected = form.stayConnected)
        } else {
            open(url, uri, login to secret, silentAttempt = false, login = saved, stayConnected = form.stayConnected)
        }
    }

    /** Adresse validée ; la page d’une adresse complète prime sur le choix Élève/Parent. */
    private fun resolve(school: String, type: AccountType): Triple<String, URI, AccountType>? {
        val url = PronotePolicy.schoolAddress(school, type)
        val uri = try {
            PronotePolicy.validatedUrl(url).also { require(!it.path.substringAfterLast('/').startsWith("mobile.")) }
        } catch (_: Exception) {
            return null
        }
        return Triple(url, uri, if (uri.path.endsWith(AccountType.PARENT.page)) AccountType.PARENT else AccountType.STUDENT)
    }

    // endregion

    // region Page officielle

    private fun newPin() = random.nextInt(10_000).toString().padStart(4, '0')

    private fun open(
        url: String,
        uri: URI,
        credentials: Pair<String, String>,
        silentAttempt: Boolean,
        login: SavedLogin? = null,
        stayConnected: Boolean = false,
    ) {
        invalidate()
        silent = silentAttempt
        attemptLogin = login
        attemptStayConnected = stayConnected
        generation.incrementAndGet()
        nonce = UUID.randomUUID().toString()
        pin = newPin()
        origin = uri.scheme + "://" + uri.authority
        claimed = false
        injected = false
        pendingCredentials = credentials
        state.update {
            it.copy(browserStarted = true, showBrowser = false, busy = true, isError = false, formError = null,
                title = if (silent) "Reconnexion à Pronote" else "Connexion à Pronote", message = "Connexion en cours…")
        }
        page.load(PronotePolicy.directLoginPage(url))
        startWatchdog(90_000, "Pronote n’a pas répondu à temps. Vérifie l’établissement et ta connexion internet, puis réessaie.", offline = true)
        if (!webMessagesSupported) failure("Mets à jour Android System WebView depuis le Play Store, puis réessaie.")
    }

    /**
     * Reconnexion silencieuse : même parcours que le formulaire (page masquée, identifiants, QR) avec
     * les identifiants enregistrés, sans écran de connexion. ENT et vérifications affichent la page.
     */
    private fun startSilent(automatic: Boolean = false) {
        if (throttle.isBlocked(now())) {
            showBlocked()
            return
        }
        // Ordinateur : la connexion au lancement est voulue par l’utilisateur (il ouvre l’app), elle
        // n’est pas soumise au plafond horaire des reconnexions silencieuses ; l’erreur 25 reste bloquante.
        if (automatic && !(direct && state.value.access == AccessPhase.CHECKING) && !throttle.silentAllowed(now())) {
            if (canReconnectSilently()) silentDeferred() else signedOut("Ta connexion à Pronote a expiré. Reconnecte-toi pour continuer.")
            return
        }
        val stored = loginStore?.load()
        val username = stored?.username
        val password = stored?.password
        val target = stored?.let { resolve(it.school, it.accountType) }
        if (username == null || password == null || target == null) {
            signedOut("Ta connexion à Pronote a expiré. Reconnecte-toi pour continuer.")
            return
        }
        silentUsed = true
        throttle.recordSilentAttempt(now())
        state.update { it.copy(reconnecting = true) }
        if (direct) {
            openDirect(target.first, target.second, username to password, silentAttempt = true)
        } else {
            open(target.first, target.second, username to password, silentAttempt = true)
        }
    }

    /**
     * Connexion directe (ordinateur) : FonctionParametres, Identification et Authentification par le
     * protocole natif sur `<page>?login=true`, puis les mêmes lectures que le mobile. Le mot de passe
     * n’est gardé qu’en mémoire pour les reconnexions de la session (et dans le coffre du système si
     * « Rester connecté » est activé).
     */
    private fun openDirect(
        url: String,
        uri: URI,
        credentials: Pair<String, String>,
        silentAttempt: Boolean,
        login: SavedLogin? = null,
        stayConnected: Boolean = false,
    ) {
        invalidate()
        silent = silentAttempt
        attemptLogin = login
        attemptStayConnected = stayConnected
        val attempt = generation.incrementAndGet()
        origin = uri.scheme + "://" + uri.authority
        state.update {
            it.copy(browserStarted = false, showBrowser = false, busy = true, isError = false, formError = null,
                title = if (silent) "Reconnexion à Pronote" else "Connexion à Pronote", message = "Connexion en cours…")
        }
        val keepChild = silentAttempt
        activeJob = scope.launch {
            try {
                val binding = Binding(attempt)
                val connection = guard.mutex.withLock {
                    withContext(io) {
                        val start = Credentials(PronotePolicy.directLoginPage(url), credentials.first, credentials.second, uuid = "")
                        PronoteClient(binding.transport, start, binding.save, now).also { it.login(PronoteClient.Mode.PASSWORD) }
                    }
                }
                if (attempt != generation.get()) return@launch
                // Nouveau compte saisi : l’enfant suivi est oublié ; reconnexion du même compte : il est gardé.
                if (!keepChild) synchronized(guard.vaultLock) { if (attempt == generation.get()) vault.saveSelectedChildId(null) }
                rememberLogin()
                binding.adopted = true
                val adopted = Session(connection, binding)
                session = adopted
                silent = false
                state.update { it.copy(hasSavedSession = true, form = it.form.copy(password = "", passwordFromStore = false)) }
                sync(attempt, adopted)
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                noteThrottle(error)
                if (attempt != generation.get()) return@launch
                when {
                    error is PronoteException && error.code == "login" -> rejected()
                    error is PronoteException && error.code == "mfa" -> failure(DIRECT_MFA)
                    else -> failure(messageFor(error, "La connexion à Pronote n’a pas abouti. Réessaie dans quelques instants."), offline = error is IOException)
                }
            }
        }
        // Délai maximal de la tentative : au-delà, elle est abandonnée comme sur mobile.
        scope.launch {
            delay(90_000)
            if (attempt == generation.get() && session?.binding?.attempt != attempt && activeJob?.isActive == true) {
                failure("Pronote n’a pas répondu à temps. Vérifie l’établissement et ta connexion internet, puis réessaie.", offline = true)
            }
        }
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
        // Configuration (identifiants compris) posée seulement sur l’origine attendue et dans le cadre principal.
        val expected = JSONObject.quote(origin)
        page.evaluate("if (location.origin === $expected && window.top === window) { window.__JM_CONFIG=$config;\n}\n${automationScript()}")
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
                startWatchdog(45_000, "La connexion a pris trop de temps. Réessaie dans quelques instants.", offline = true)
            }
            is BridgeGate.Event.Progress -> if (event.stage == "mfa-done") {
                // Vérification terminée par l’utilisateur : le script reprend seul, la page est masquée.
                hidePage()
                startWatchdog(45_000, "La connexion a pris trop de temps. Réessaie dans quelques instants.", offline = true)
            } else if (!state.value.showBrowser) {
                state.update { it.copy(message = progressMessage(event.stage)) }
            }
            is BridgeGate.Event.Failure -> when (event.code) {
                "LOGIN_REJECTED" -> rejected()
                // Non terminal : la tentative (nonce, code) reste valide, le script attend la fin de la vérification.
                "MFA_REQUIRED" -> reveal("Vérification de sécurité", "Pronote demande une vérification : termine-la dans cette page, la connexion continuera ensuite.")
                "LOGIN_FORM_NOT_FOUND", "LOGIN_STALLED" ->
                    rearmOnVisiblePage("Termine la connexion", "Connecte-toi dans cette page Pronote : J-Method continuera ensuite automatiquement.")
                else -> failure("La connexion à Pronote n’a pas abouti. Réessaie dans quelques instants.", offline = true)
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
        it.copy(showBrowser = false, busy = true, isError = false, title = if (silent) "Reconnexion à Pronote" else "Connexion à Pronote", message = "Connexion en cours…")
    }

    private fun progressMessage(stage: String) = when (stage) {
        "filling-login", "submitting-login" -> "Connexion en cours…"
        else -> "Préparation de ton emploi du temps…"
    }

    private fun stopScript() = page.evaluate("window.__JM_PRONOTE_POC?.stop()")

    private fun startWatchdog(millis: Long, text: String, offline: Boolean = false) {
        watchdog?.cancel()
        val attempt = generation.get()
        watchdog = scope.launch {
            delay(millis)
            if (attempt == generation.get() && !claimed) failure(text, offline)
        }
    }

    /** Page officielle affichée en plein écran (ENT, vérification) ; la tentative continue, délai étendu. */
    private fun reveal(title: String, text: String) {
        state.update { it.copy(busy = false, showBrowser = true, title = title, message = text, isError = false) }
        startWatchdog(600_000, "La connexion a expiré. Réessaie quand tu veux.")
    }

    /**
     * Échec : la tentative est abandonnée. Formulaire : message sous le bouton. Reconnexion silencieuse :
     * bandeau hors ligne si Pronote ne répond pas ([offline]), écran de connexion sinon.
     */
    private fun failure(text: String, offline: Boolean = false) {
        val wasSilent = silent
        invalidate()
        page.release()
        if (wasSilent) {
            if (offline) {
                state.update {
                    it.copy(browserStarted = false, showBrowser = false, busy = false, reconnecting = false, isError = true,
                        access = AccessPhase.OFFLINE, title = "Reconnexion impossible", message = text)
                }
            } else {
                signedOut(text)
            }
            return
        }
        state.update {
            it.copy(browserStarted = false, showBrowser = false, busy = false, isError = true, title = "Connexion impossible",
                message = text, formError = text,
                // Le mot de passe enregistré (s’il existe encore) reste prérempli.
                form = loginStore?.load()?.password.let { stored -> it.form.copy(password = stored.orEmpty(), passwordFromStore = stored != null) })
        }
    }

    /** Identifiants refusés : la page reste masquée ; un mot de passe enregistré refusé est supprimé. */
    private fun rejected() {
        // Mot de passe refusé : celui qui est enregistré est supprimé, établissement et identifiant restent.
        loginStore?.forgetPassword()
        state.update { it.copy(hasStoredPassword = false, form = it.form.copy(password = "", passwordFromStore = false)) }
        if (silent) {
            failure("Pronote a refusé le mot de passe enregistré. Saisis-le à nouveau pour te reconnecter.")
        } else {
            failure(REJECTED)
        }
    }

    private fun signedOut(text: String) {
        session = null
        scheduler?.cancel()
        state.update {
            it.copy(browserStarted = false, showBrowser = false, busy = false, reconnecting = false, hasSavedSession = false,
                account = null, isError = true, access = AccessPhase.SIGNED_OUT, title = "Reconnecte-toi", message = text, formError = text)
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
        private val epoch = guard.epoch.get()
        @Volatile var adopted = false
        /**
         * Connexion par jeton en cours : elle va jusqu’à l’enregistrement du jeton renouvelé, même si la
         * tentative est annulée entre-temps (seule une déconnexion l’arrête). La génération est contrôlée
         * avant l’envoi et à l’adoption du résultat.
         */
        @Volatile var tokenLogin = false

        fun disconnected() = epoch != guard.epoch.get()

        private fun current() = !disconnected() && (tokenLogin || attempt == generation.get())

        val transport = PronoteTransport { method, url, body, headers ->
            if (!current()) throw CancellationException()
            this@PronoteConnectionController.transport.request(method, url, body, headers).also { if (!current()) throw CancellationException() }
        }

        val save: (Credentials) -> Unit = { credentials ->
            synchronized(guard.vaultLock) {
                if (epoch == guard.epoch.get() && (tokenLogin || adopted || attempt == generation.get())) vault.save(credentials)
            }
        }
    }

    private inner class Session(val client: PronoteClient, val binding: Binding)

    /** Connexion par jeton non interruptible jusqu’à l’enregistrement du jeton renouvelé. */
    private fun loginByToken(binding: Binding, client: PronoteClient) {
        binding.tokenLogin = true
        try {
            client.login(reloginMode)
        } finally {
            binding.tokenLogin = false
        }
    }

    private fun connectQr(image: String, code: String) {
        val attempt = generation.get()
        state.update { it.copy(busy = true, isError = false, message = "Récupération de ton emploi du temps…") }
        activeJob = scope.launch {
            try {
                val binding = Binding(attempt)
                val connection = guard.mutex.withLock {
                    withContext(io) {
                        val credentials = PronotePolicy.credentialsFromQr(decodeQr(image), code, appUuid(), origin)
                        PronoteClient(binding.transport, credentials, binding.save, now).also { it.login(PronoteClient.Mode.QR) }
                    }
                }
                if (attempt != generation.get()) return@launch
                synchronized(guard.vaultLock) { if (attempt == generation.get()) vault.saveSelectedChildId(null) }
                rememberLogin()
                binding.adopted = true
                val adopted = Session(connection, binding)
                session = adopted
                silent = false
                state.update { it.copy(browserStarted = false, showBrowser = false, hasSavedSession = true, form = it.form.copy(password = "", passwordFromStore = false)) }
                // Le jeton suffit désormais : cookies, stockage et cache de la page officielle sont effacés.
                page.clearBrowsingData()
                page.release()
                sync(attempt, adopted)
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                noteThrottle(error)
                if (attempt != generation.get()) return@launch
                if (error is PronoteException && error.code == "mfa") {
                    // Vérification exigée par l’API après l’enregistrement du jeton : ce jeton ne servira pas,
                    // il est effacé (sinon chaque lancement recommencerait) et la page officielle est affichée.
                    synchronized(guard.vaultLock) { vault.clear() }
                    claimed = false
                    rearmOnVisiblePage(
                        "Vérification de sécurité",
                        "Pronote demande une vérification : termine-la dans cette page, la connexion continuera ensuite.",
                    )
                    return@launch
                }
                failure(messageFor(error, "La connexion à Pronote n’a pas abouti. Réessaie dans quelques instants."), offline = error is IOException)
            }
        }
    }

    /** Après une connexion réussie par le formulaire : identifiants enregistrés selon « Rester connecté ». */
    private fun rememberLogin() {
        val login = attemptLogin ?: return
        attemptLogin = null
        val store = loginStore ?: return
        // « Rester connecté » désactivé : le mot de passe enregistré est toujours effacé.
        if (attemptStayConnected) store.save(login.copy(autoReconnect = true)) else store.save(SavedLogin(login.school, login.accountType))
        state.update { it.copy(hasStoredPassword = attemptStayConnected) }
    }

    /** Reprise par jeton enregistré (au lancement ou à la demande). */
    fun restoreSaved() {
        if (state.value.browserStarted || activeJob?.isActive == true) return
        if (throttle.isBlocked(now())) {
            showBlocked()
            return
        }
        if (vault.load() == null) {
            handleRefusedToken(null)
            return
        }
        val attempt = generation.incrementAndGet()
        state.update { it.copy(busy = true, isError = false, title = "Reconnexion à Pronote", message = "Connexion en cours…") }
        activeJob = scope.launch {
            try {
                val binding = Binding(attempt)
                val connection = guard.mutex.withLock {
                    // Lu sous le verrou : la synchronisation d’arrière-plan a pu renouveler le jeton.
                    // Génération contrôlée avant l’envoi : une reprise annulée ne part pas.
                    if (attempt != generation.get()) throw CancellationException()
                    val credentials = vault.load() ?: throw PronoteException("login", "")
                    if (vault.needsRelogin()) throw PronoteException("login", "")
                    // Une fois partie, la connexion par jeton va jusqu’à l’enregistrement du jeton renouvelé
                    // (« Ouvrir sans attendre », retour ou fin du ViewModel ne la coupent pas).
                    withContext(io + NonCancellable) {
                        PronoteClient(binding.transport, credentials, binding.save, now).also { loginByToken(binding, it) }
                    }
                }
                if (attempt != generation.get()) return@launch
                binding.adopted = true
                val adopted = Session(connection, binding)
                session = adopted
                sync(attempt, adopted)
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                noteThrottle(error)
                if (attempt != generation.get()) return@launch
                if (!handleRefusedToken(error)) {
                    // Problème réseau ou serveur : le jeton est conservé et l’app s’ouvre hors ligne.
                    state.update {
                        it.copy(busy = false, isError = true, access = AccessPhase.OFFLINE, title = offlineTitle(error),
                            message = messageFor(error, "La reconnexion à Pronote a échoué. Réessaie plus tard."))
                    }
                }
            }
        }
    }

    /**
     * Jeton refusé (ou vérification de sécurité exigée) : il est effacé. Avec des identifiants
     * enregistrés, une seule reconnexion silencieuse est tentée ; sinon l’écran de connexion revient.
     * @param error `null` si aucun jeton n’est enregistré.
     */
    private fun handleRefusedToken(error: Exception?): Boolean {
        if (error != null && (error !is PronoteException || error.code !in BackgroundSync.REFUSED)) return false
        session = null
        synchronized(guard.vaultLock) { vault.clear() }
        state.update { it.copy(hasSavedSession = false, account = null) }
        if (canReconnectSilently()) {
            when {
                throttle.isBlocked(now()) -> showBlocked()
                // Reconnexion silencieuse déjà faite ou plafond horaire : hors ligne, « Réessayer » reste possible.
                silentUsed || !throttle.silentAllowed(now()) -> silentDeferred()
                // Lancé hors du traitement en cours, qui est invalidé par la nouvelle tentative.
                else -> scope.launch { startSilent(automatic = true) }
            }
        } else {
            val mfa = error is PronoteException && error.code == "mfa"
            signedOut(if (mfa) messageFor(error, "") else "Ta connexion à Pronote a expiré. Reconnecte-toi pour continuer.")
        }
        return true
    }

    /** Identifiants enregistrés et reconnexion automatique permise (pas de déconnexion volontaire). */
    private fun canReconnectSilently() =
        state.value.hasStoredPassword && loginStore?.load()?.let { it.autoReconnect && it.username != null && it.password != null } == true

    /**
     * Reconnexion silencieuse automatique non autorisée pour l’instant : l’app s’ouvre hors ligne sur les
     * données locales, avec le bandeau et « Réessayer » (comme iOS). L’écran de connexion est réservé à
     * l’absence de connexion enregistrée, à la déconnexion volontaire et au mot de passe refusé.
     */
    private fun silentDeferred() {
        session = null
        state.update {
            it.copy(browserStarted = false, showBrowser = false, busy = false, reconnecting = false, isError = true,
                access = AccessPhase.OFFLINE, title = "Reconnexion en attente",
                message = "Ta connexion à Pronote a expiré. Touche Réessayer pour te reconnecter.")
        }
    }

    private fun offlineTitle(error: Exception) = if (error is IOException) "Hors ligne" else "Pronote indisponible"

    /** Bandeau hors ligne ou bouton Actualiser : nouvel essai, sans déconnecter (action de l’utilisateur). */
    fun retry() {
        if (throttle.isBlocked(now())) {
            showBlocked()
            return
        }
        when {
            session != null -> refresh()
            vault.load() != null -> restoreSaved()
            state.value.hasStoredPassword && !state.value.browserStarted -> startSilent()
            else -> signedOut("Ta connexion à Pronote a expiré. Reconnecte-toi pour continuer.")
        }
    }

    /**
     * Retour au premier plan : reconnexion si l’arrière-plan a trouvé le jeton refusé, sinon
     * synchronisation si la dernière date de plus de six heures.
     */
    fun onForeground() {
        val current = state.value
        if (current.access !in setOf(AccessPhase.CONNECTED, AccessPhase.OFFLINE) || current.busy || current.browserStarted) return
        if (activeJob?.isActive == true) return
        // Erreur 25 récente : aucun essai automatique.
        if (throttle.isBlocked(now())) return
        // Sans session ni jeton (reconnexion silencieuse déjà tentée) : seul l’utilisateur relance.
        if (session == null && vault.load() == null) return
        if (vault.load() != null && vault.needsRelogin()) {
            handleRefusedToken(PronoteException("login", ""))
            return
        }
        val last = lastSync()
        if (last == null || Duration.between(last, now()) > FOREGROUND_SYNC_AFTER) retry()
    }

    /** Les feuilles personnelles et leurs programmes ne nécessitent pas de compte Pronote. */
    fun useLocally() {
        if (state.value.access != AccessPhase.SIGNED_OUT || state.value.busy || state.value.browserStarted) return
        state.update { it.copy(access = AccessPhase.OFFLINE, isError = false, title = "Cours personnels", message = "Utilisation locale sans Pronote. Tes feuilles et révisions sont enregistrées sur $device.") }
    }

    /** Ouvre l’app sur les données locales sans attendre la fin de la reprise. */
    fun skipResume() {
        if (state.value.access != AccessPhase.CHECKING) return
        cancel(reset = false)
        page.release()
        state.update {
            it.copy(access = AccessPhase.OFFLINE, browserStarted = false, showBrowser = false, isError = true, title = "Synchronisation interrompue",
                message = "Les données enregistrées sur $device sont affichées. Réessaie quand tu veux.")
        }
    }

    /** Relit les 14 prochains jours (session existante, reconnectée par jeton si elle a expiré). */
    fun refresh(childId: String? = null) {
        val current = session
        if (current == null) {
            retry()
            return
        }
        if (state.value.busy) return
        val attempt = generation.get()
        if (childId != null) synchronized(guard.vaultLock) { vault.saveSelectedChildId(childId) }
        activeJob = scope.launch { sync(attempt, current, childId) }
    }

    fun selectChild(childId: String) = refresh(childId)

    private suspend fun sync(attempt: Int, current: Session, childId: String? = null) {
        val connection = current.client
        current.binding.attempt = attempt
        state.update { it.copy(busy = true, isError = false, title = "Synchronisation", message = "Lecture de l’emploi du temps des 14 prochains jours…") }
        try {
            val lessons = guard.mutex.withLock {
                withContext(io) {
                    val wanted = childId ?: synchronized(guard.vaultLock) { vault.selectedChildId() }
                    withSession(current) { read(connection, wanted) }
                }
            }
            if (attempt != generation.get()) return
            storeLessons(lessons)
            if (attempt != generation.get()) return
            // Synchronisation réussie : une nouvelle reconnexion silencieuse redevient possible.
            silentUsed = false
            scheduler?.schedule()
            state.update {
                it.copy(busy = false, isError = false, reconnecting = false, hasSavedSession = true, access = AccessPhase.CONNECTED,
                    title = "Pronote connecté", message = "Emploi du temps à jour.",
                    account = PronoteAccount(connection.accountName, connection.children, connection.selectedChildId))
            }
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            noteThrottle(error)
            if (attempt == generation.get() && !handleRefusedToken(error)) {
                state.update {
                    it.copy(busy = false, isError = true, reconnecting = false,
                        title = if (it.access == AccessPhase.CONNECTED) "Synchronisation impossible" else offlineTitle(error),
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

    /** Lecture du cahier de textes : instantané et marque de la session qui l’a lu. */
    class NotebookRead(val snapshot: NotebookSnapshot, val stamp: Any)

    /**
     * Cahier de textes en lecture seule (28 jours) avec la session courante, sous le verrou partagé.
     * Une session expirée est reconnectée une fois par jeton, comme la synchronisation.
     */
    suspend fun readNotebook(): NotebookRead {
        val current = session ?: throw PronoteException("offline", NOTEBOOK_OFFLINE)
        if (throttle.isBlocked(now())) throw PronoteException(PronoteThrottle.BLOCKED_CODE, PronoteThrottle.BLOCKED_MESSAGE)
        return try {
            readNotebookLocked(current)
        } catch (error: PronoteException) {
            noteThrottle(error)
            throw error
        }
    }

    private suspend fun readNotebookLocked(current: Session): NotebookRead {
        return guard.mutex.withLock {
            if (session !== current || current.binding.disconnected()) throw PronoteException("offline", NOTEBOOK_OFFLINE)
            withContext(io) {
                val connection = current.client
                val wanted = synchronized(guard.vaultLock) { vault.selectedChildId() }
                fun read(): NotebookSnapshot {
                    if (wanted != null && connection.children.any { it.id == wanted }) connection.selectChild(wanted)
                    return connection.notebook(today())
                }
                val snapshot = withSession(current) { read() }
                NotebookRead(snapshot, connection.sessionStamp)
            }
        }
    }

    /**
     * Règle d’expiration de session (`PronoteSessionRules`) : reconnexion préventive après 10 minutes
     * d’inactivité, puis une reconnexion par jeton et un rejeu si la session s’avère perdue.
     */
    private fun <T> withSession(current: Session, read: () -> T): T =
        PronoteSessionRules.run(current.client, now(), relogin = { reloginByToken(current) }, read = read)

    /** Reconnexion par jeton depuis un état propre, avec le jeton le plus récent du coffre. */
    private fun reloginByToken(current: Session) {
        vault.load()?.let { current.client.useCredentials(it) }
        loginByToken(current.binding, current.client)
    }

    /**
     * Télécharge une pièce jointe lue avec la session [stamp] vers [target] (cache privé). Si la session
     * a changé (inactivité, reconnexion), le cahier de textes est relu avant le GET : les identifiants
     * de pièces jointes sont liés à la session. Un échec est retenté une fois après reconnexion.
     * @return la nouvelle lecture du cahier de textes si elle a eu lieu, sinon `null`.
     */
    suspend fun downloadAttachment(stamp: Any, id: String, name: String, target: java.io.File): NotebookRead? {
        val current = session ?: throw PronoteException("offline", NOTEBOOK_OFFLINE)
        if (throttle.isBlocked(now())) throw PronoteException(PronoteThrottle.BLOCKED_CODE, PronoteThrottle.BLOCKED_MESSAGE)
        return try {
            guard.mutex.withLock {
                // Contrôlé sous le verrou, juste avant le GET : rien ne part après une déconnexion.
                if (session !== current || current.binding.disconnected()) throw PronoteException("offline", NOTEBOOK_OFFLINE)
                withContext(io) {
                    val connection = current.client
                    var reread: NotebookRead? = null
                    fun refreshIds() {
                        val wanted = synchronized(guard.vaultLock) { vault.selectedChildId() }
                        if (wanted != null && connection.children.any { it.id == wanted }) connection.selectChild(wanted)
                        val snapshot = connection.notebook(today())
                        val present = snapshot.sessions.any { s -> s.contents.any { c -> c.attachments.any { it.id == id } } } ||
                            snapshot.homework.any { h -> h.attachments.any { it.id == id } }
                        reread = NotebookRead(snapshot, connection.sessionStamp)
                        if (!present) throw PronoteException("download", PronoteClient.DOWNLOAD_UNAVAILABLE)
                    }
                    if (connection.isIdle(now())) {
                        reloginByToken(current)
                        refreshIds()
                    } else if (connection.sessionStamp !== stamp) {
                        refreshIds()
                    }
                    try {
                        connection.download(id, name, files, target)
                    } catch (error: PronoteException) {
                        // Statut inattendu : la session a pu expirer. Une reconnexion, une relecture, un essai.
                        if (error.code != "download") throw error
                        reloginByToken(current)
                        refreshIds()
                        connection.download(id, name, files, target)
                    }
                    reread
                }
            }
        } catch (error: PronoteException) {
            noteThrottle(error)
            throw error
        }
    }

    /** Messages sans détail technique ; les codes internes ne sont jamais affichés. */
    private fun messageFor(error: Exception, fallback: String): String = when {
        error is PronoteException && error.code == "mfa" && direct -> DIRECT_MFA
        error is PronoteException && error.code == "mfa" ->
            "Pronote demande une vérification de sécurité. Reconnecte-toi pour la terminer."
        error is PronoteException && error.code == PronoteThrottle.BLOCKED_CODE -> PronoteThrottle.BLOCKED_MESSAGE
        error is PronoteException && error.code == "url" -> error.message
        error is IOException -> "Pronote est injoignable. Vérifie ta connexion internet, puis réessaie."
        else -> fallback
    }

    /** Portée locale opaque : ni mot de passe, ni identifiant de devoir. */
    fun agendaScope(): String? {
        val current = state.value
        if (current.access != AccessPhase.CONNECTED && current.access != AccessPhase.OFFLINE) return null
        val token = synchronized(guard.vaultLock) { vault.load() }
        val local = loginStore?.load()
        val username = token?.username?.takeIf { it.isNotBlank() } ?: local?.username?.takeIf { it.isNotBlank() } ?: return null
        val address = token?.url ?: local?.let { runCatching { PronotePolicy.schoolAddress(it.school, it.accountType) }.getOrNull() } ?: return null
        val uri = runCatching { java.net.URI(address) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val child = current.account?.selectedChildId ?: synchronized(guard.vaultLock) { vault.selectedChildId() }
        if (uri.path.endsWith("parent.html", true) && child.isNullOrBlank()) return null
        return fr.jmethod.app.model.agendaFingerprint(listOf(host, uri.port.toString(), uri.path.replace("/mobile.", "/"), username, child.orEmpty()))
    }

    // endregion

    /** Invalide la tentative en cours et tous ses résultats tardifs, sans changer l’écran. */
    private fun invalidate() {
        generation.incrementAndGet()
        watchdog?.cancel()
        activeJob?.cancel()
        activeJob = null
        pendingCredentials = null
        attemptLogin = null
        silent = false
        claimed = true
        if (state.value.browserStarted) stopScript()
    }

    /** Annulation par l’utilisateur. */
    fun cancel(reset: Boolean = true) {
        val wasSilent = silent
        invalidate()
        state.update {
            // Reprise interrompue au lancement : la session reste, l’app s’ouvre hors ligne.
            val access = if (it.access == AccessPhase.CHECKING) AccessPhase.OFFLINE else it.access
            it.copy(busy = false, reconnecting = false, access = access)
        }
        if (reset) {
            page.release()
            if (wasSilent && vault.load() == null) {
                signedOut("Reconnecte-toi pour continuer.")
            } else {
                state.update {
                    it.copy(browserStarted = false, showBrowser = false, isError = false, title = "Connexion annulée",
                        message = "Tu peux relancer la connexion quand tu veux.", formError = null)
                }
            }
        }
    }

    /**
     * Déconnexion : jeton, enfant suivi, cookies, stockage web, créneaux importés et synchronisation
     * d’arrière-plan supprimés ; cours et révisions restent. Les identifiants enregistrés sont gardés
     * pour préremplir le formulaire (sans reconnexion automatique), sauf si [forgetCredentials].
     */
    fun disconnect(forgetCredentials: Boolean = false) {
        cancel(reset = false)
        session = null
        synchronized(guard.vaultLock) {
            guard.epoch.incrementAndGet()
            vault.clear()
        }
        val store = loginStore
        if (store != null) {
            if (forgetCredentials) store.forgetCredentials() else store.load()?.let { store.save(it.copy(autoReconnect = false)) }
        }
        val kept = if (forgetCredentials) null else store?.load()
        scheduler?.cancel()
        page.clearBrowsingData()
        page.release()
        state.update {
            it.copy(browserStarted = false, showBrowser = false, account = null, hasSavedSession = false,
                hasStoredPassword = kept?.password != null, reconnecting = false, isError = false, access = AccessPhase.SIGNED_OUT,
                title = "Tu es déconnecté", formError = null,
                form = if (kept != null) prefilled(it.form, kept) else it.form.copy(username = "", password = "", passwordFromStore = false),
                message = "Ton emploi du temps importé a été retiré de $device. Tes cours et tes révisions restent enregistrés.")
        }
        scope.launch { clearLessons() }
    }

    fun dispose() {
        generation.incrementAndGet()
        watchdog?.cancel()
        activeJob?.cancel()
    }

    private companion object {
        const val NOTEBOOK_OFFLINE = "Le cahier de textes se lit avec ta connexion Pronote. Actualise depuis les Réglages ou réessaie quand tu es en ligne."
        const val DIRECT_MFA = "Ton compte Pronote demande une double authentification (code personnel ou appareil de confiance). " +
            "J-Method sur ordinateur ne la prend pas encore en charge : utilise l’app sur ton téléphone en attendant."
        const val REJECTED = "Pronote a refusé l’identifiant ou le mot de passe. Utilise ceux de Pronote, pas ceux d’ÉduConnect, puis réessaie."
        val FOREGROUND_SYNC_AFTER: Duration = Duration.ofHours(6)
    }
}
