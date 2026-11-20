package fr.jmethod.app

import android.app.Application
import fr.jmethod.app.pronote.BackgroundSync
import fr.jmethod.app.pronote.KeystoreAppIdentity
import fr.jmethod.app.pronote.KeystoreLoginStore
import fr.jmethod.app.pronote.NativeTransport
import fr.jmethod.app.pronote.PrefsThrottle
import fr.jmethod.app.pronote.TokenVault
import fr.jmethod.app.work.WorkManagerScheduler
import kotlinx.coroutines.Dispatchers

class JMethodApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** UUID d’application stable (repris de la session enregistrée à la première utilisation). */
    lateinit var appIdentity: KeystoreAppIdentity
        private set

    /** Synchronisation d’arrière-plan (WorkManager), même dépôt et même verrou que le premier plan. */
    lateinit var backgroundSync: BackgroundSync
        private set

    override fun onCreate() {
        super.onCreate()
        // Même fichier de données (`files/jmethod-data.json`) et mêmes alias Keystore qu’avant le module partagé.
        val container = AppContainer(
            filesDir = filesDir,
            cacheDir = cacheDir,
            vault = TokenVault(this),
            loginStore = KeystoreLoginStore(this),
            throttle = PrefsThrottle(this),
            scheduler = WorkManagerScheduler(this),
        )
        this.container = container
        appIdentity = KeystoreAppIdentity(this) { container.vault.load()?.uuid }
        backgroundSync = BackgroundSync(
            vault = container.vault,
            guard = container.sessionGuard,
            transport = NativeTransport(),
            io = Dispatchers.IO,
            today = { container.clock.today() },
            storeLessons = container::storeLessons,
            throttle = container.throttle,
            now = { container.clock.now() },
        )
    }
}
