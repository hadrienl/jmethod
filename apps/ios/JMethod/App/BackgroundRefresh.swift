import BackgroundTasks
import Foundation

/// Actualisation en arrière-plan (`BGAppRefreshTask`) : le système choisit le moment, au plus tôt quelques heures
/// après la demande. Seule la connexion par jeton en HTTPS natif est utilisée ; jamais de page web.
enum BackgroundRefresh {
    static let identifier = "fr.jmethod.app.refresh"
    static let minimumInterval: TimeInterval = 4 * 60 * 60

    /// À appeler avant la fin du lancement (initialisation de l'app).
    @MainActor
    static func register(session: PronoteSession) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            let refresh = UncheckedTask(task: task)
            MainActor.assumeIsolated {
                let work = Task { @MainActor in
                    let result = await session.backgroundRefresh()
                    if session.hasSavedConnection { schedule() }
                    refresh.task.setTaskCompleted(success: result == .synced || result == .noConnection || result == .tokenRefused)
                }
                // Expiration : la tâche est annulée ; un jeton déjà renouvelé a été enregistré au moment de sa réception.
                refresh.task.expirationHandler = { work.cancel() }
            }
        }
    }

    /// Demande la prochaine actualisation (sans effet si aucune connexion n'est enregistrée).
    static func schedule(after interval: TimeInterval = minimumInterval) {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
    }
}

/// `BGTask` n'est pas annoté `Sendable` ; ses méthodes `setTaskCompleted` et `expirationHandler`
/// sont utilisables depuis n'importe quel fil d'après la documentation. La référence est immuable.
private struct UncheckedTask: @unchecked Sendable {
    let task: BGTask
}
