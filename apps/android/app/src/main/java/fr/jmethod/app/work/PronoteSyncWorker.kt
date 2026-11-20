package fr.jmethod.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.jmethod.app.JMethodApplication
import fr.jmethod.app.pronote.BackgroundScheduler
import fr.jmethod.app.pronote.BackgroundSync
import java.util.concurrent.TimeUnit

/** Synchronisation périodique de l’emploi du temps, réseau connecté requis. */
class PronoteSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return when ((applicationContext as JMethodApplication).backgroundSync.run()) {
            // Erreur 25, jeton refusé ou autre échec : pas de nouvel essai avant la prochaine période.
            BackgroundSync.Result.RETRY -> Result.retry()
            else -> Result.success()
        }
    }
}

class WorkManagerScheduler(context: Context) : BackgroundScheduler {
    private val context = context.applicationContext

    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<PronoteSyncWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(6, TimeUnit.HOURS)
            // Nouvel essai seulement après une erreur réseau, espacé d’au moins 30 minutes.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    private companion object {
        const val NAME = "pronote-sync"
    }
}
