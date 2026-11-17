package fr.jmethod.app

import android.app.Application
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentStore
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.pronote.SessionVault
import fr.jmethod.app.pronote.TokenVault
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
        clock = clock,
        scope = scope,
        io = Dispatchers.IO,
    )
    val vault: SessionVault = TokenVault(application)
}

class JMethodApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
