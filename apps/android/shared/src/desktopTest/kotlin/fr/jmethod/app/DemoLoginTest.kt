package fr.jmethod.app

import fr.jmethod.app.pronote.Credentials
import fr.jmethod.app.pronote.NativeTransport
import fr.jmethod.app.pronote.PronoteClient
import fr.jmethod.app.pronote.PronotePolicy
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Connexion directe réelle à la démonstration publique d’Index Education (identifiants publiés par
 * l’éditeur). Désactivée par défaut : `-Pjmethod.demoLogin=true`. Une seule connexion par exécution.
 */
class DemoLoginTest {
    @Test
    fun directLoginReadsDemoTimetableAndNotebook() {
        assumeTrue(System.getProperty("jmethod.demoLogin") == "true")
        val url = PronotePolicy.directLoginPage(PronotePolicy.schoolAddress("demo", fr.jmethod.app.pronote.AccountType.STUDENT))
        val client = PronoteClient(NativeTransport(), Credentials(url, "demonstration", "pronotevs", uuid = ""))
        try {
            client.login(PronoteClient.Mode.PASSWORD)
        } catch (error: fr.jmethod.app.pronote.PronoteException) {
            println("demo: échec code=${error.code}")
            throw error
        }
        println("demo: compte=${client.accountName.isNotBlank()} enfants=${client.children.size} onglets=${client.authorizedTabs?.size}")
        val today = LocalDate.now(ZoneId.of("Europe/Paris"))
        val lessons = client.timetable(today, 14)
        println("demo: cours sur 14 jours=${lessons.size} premier=${lessons.firstOrNull()?.start}")
        val notebook = runCatching { client.notebook(today) }
        println("demo: cahier=${notebook.map { "${it.sessions.size} séances, ${it.homework.size} devoirs" }.getOrElse { "erreur " + (it as? fr.jmethod.app.pronote.PronoteException)?.code }}")
    }
}
