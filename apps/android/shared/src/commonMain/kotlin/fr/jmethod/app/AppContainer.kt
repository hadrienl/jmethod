package fr.jmethod.app

import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.data.AppDocumentStore
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.documents.CourseCreator
import fr.jmethod.app.documents.CourseFiles
import fr.jmethod.app.pronote.BackgroundScheduler
import fr.jmethod.app.pronote.Lesson
import fr.jmethod.app.pronote.LoginStore
import fr.jmethod.app.pronote.PronoteEventMapper
import fr.jmethod.app.pronote.PronoteSessionGuard
import fr.jmethod.app.pronote.PronoteThrottle
import fr.jmethod.app.pronote.SessionVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Dépendances de l’app, créées une fois par processus (pas de framework d’injection). Les services
 * propres au système (coffre, identifiants, limites, synchronisation d’arrière-plan) sont fournis
 * par l’app Android ou l’app ordinateur.
 *
 * @param filesDir stockage privé non cache : `jmethod-data.json` et `documents/`.
 * @param cacheDir cache privé : pièces jointes du cahier de textes.
 */
class AppContainer(
    filesDir: File,
    val cacheDir: File,
    val vault: SessionVault,
    val loginStore: LoginStore,
    /** Erreur 25 et plafond des reconnexions silencieuses, communs au premier plan et à l’arrière-plan. */
    val throttle: PronoteThrottle,
    val scheduler: BackgroundScheduler?,
    val clock: AppClock = AppClock.SYSTEM,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = AppRepository(
        store = AppDocumentStore(File(filesDir, DATA_FILE)),
        scope = scope,
        io = Dispatchers.IO,
        clock = clock,
    )

    /** Un seul verrou Pronote par processus, partagé par l’app et la synchronisation d’arrière-plan. */
    val sessionGuard = PronoteSessionGuard()

    /** Documents des cours (stockage privé non cache) et création commune formulaire / automatique. */
    val courseFiles = CourseFiles(filesDir)
    val courseCreator = CourseCreator(repository, clock, courseFiles)

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

    /** Déconnexion : créneaux importés et date de synchronisation retirés ; cours et révisions restent. */
    suspend fun clearLessons() {
        repository.update { document ->
            document.copy(pronoteEvents = emptyList(), settings = document.settings.copy(lastPronoteSync = null))
        }
    }

    companion object {
        /** Nom du fichier de données, inchangé depuis la première version Android. */
        const val DATA_FILE = "jmethod-data.json"
    }
}
