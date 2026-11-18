package fr.jmethod.app

import android.app.Application
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.data.AppDocumentStore
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.pronote.BackgroundScheduler
import fr.jmethod.app.pronote.BackgroundSync
import fr.jmethod.app.pronote.KeystoreLoginStore
import fr.jmethod.app.pronote.Lesson
import fr.jmethod.app.pronote.LoginStore
import fr.jmethod.app.pronote.NativeTransport
import fr.jmethod.app.pronote.PronoteEventMapper
import fr.jmethod.app.pronote.PronoteSessionGuard
import fr.jmethod.app.pronote.PronoteThrottle
import fr.jmethod.app.pronote.PrefsThrottle
import fr.jmethod.app.pronote.KeystoreAppIdentity
import fr.jmethod.app.pronote.SessionVault
import fr.jmethod.app.pronote.TokenVault
import fr.jmethod.app.work.WorkManagerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Dépendances de l’app, créées une fois par processus (pas de framework d’injection). */
class AppContainer(application: Application) {
    val clock: AppClock = AppClock.SYSTEM
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = AppRepository(
        store = AppDocumentStore(File(application.filesDir, "jmethod-data.json")),
        scope = scope,
        io = Dispatchers.IO,
    )
    val vault: SessionVault = TokenVault(application)
    val loginStore: LoginStore = KeystoreLoginStore(application)
    /** Un seul verrou Pronote par processus, partagé par l’app et la synchronisation d’arrière-plan. */
    val sessionGuard = PronoteSessionGuard()
    val scheduler: BackgroundScheduler = WorkManagerScheduler(application)

    /** Documents des cours (stockage privé non cache) et création commune formulaire / automatique. */
    val courseFiles = fr.jmethod.app.documents.CourseFiles(application.filesDir)
    val courseCreator = fr.jmethod.app.documents.CourseCreator(repository, clock, courseFiles)

    /** Erreur 25 et plafond des reconnexions silencieuses, communs au premier plan et à l’arrière-plan. */
    val throttle: PronoteThrottle = PrefsThrottle(application)

    /** UUID d’application stable (repris de la session enregistrée à la première utilisation). */
    val appIdentity = KeystoreAppIdentity(application) { vault.load()?.uuid }

    /** Enregistre les créneaux lus (même dépôt et même verrou que le premier plan). */
    suspend fun storeLessons(lessons: List<Lesson>, stillValid: () -> Boolean = { true }) {
        val syncedAt = AppDocumentCodec.isoMillis(clock.now())
        repository.update { document ->
            if (!stillValid()) return@update document
            document.copy(
                pronoteEvents = PronoteEventMapper.toEvents(lessons, document.subjects),
                settings = document.settings.copy(lastPronoteSync = syncedAt),
            )
        }
    }

    val backgroundSync = BackgroundSync(
        vault = vault,
        guard = sessionGuard,
        transport = NativeTransport(),
        io = Dispatchers.IO,
        today = { clock.today() },
        storeLessons = ::storeLessons,
        throttle = throttle,
        now = { clock.now() },
    )
}

class JMethodApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
