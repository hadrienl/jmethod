package fr.jmethod.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.isRoot
import fr.jmethod.app.ui.platform.DesktopBack
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import fr.jmethod.app.AppContainer
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.documents.PendingDocument
import fr.jmethod.app.documents.DocType
import fr.jmethod.app.domain.CourseEditing
import fr.jmethod.app.pronote.AccountType
import fr.jmethod.app.pronote.FileThrottle
import fr.jmethod.app.pronote.Lesson
import fr.jmethod.app.pronote.MemoryVault
import fr.jmethod.app.pronote.SavedLogin
import fr.jmethod.app.pronote.SecretLoginStore
import fr.jmethod.app.pronote.SecretStore
import fr.jmethod.app.ui.JMethodApp
import fr.jmethod.app.ui.platform.LocalPlatform
import fr.jmethod.app.ui.theme.JMethodTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.imageio.ImageIO

/**
 * Visite guidée de l’app ordinateur, hors CI : captures à plusieurs largeurs de fenêtre et, avec
 * `-Pjmethod.demoLogin=true`, connexion réelle à la démonstration publique d’Index Education par le
 * formulaire (Entrée), « Rester connecté » dans le coffre du système puis reconnexion au lancement
 * suivant. Sans démonstration, l’app s’ouvre hors ligne sur des données locales d’exemple.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopTour {
    private class MapSecrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override val available = true
        override fun read(name: String) = values[name]
        override fun write(name: String, value: String) { values[name] = value }
        override fun delete(name: String) { values.remove(name) }
    }

    private class Owners : ViewModelStoreOwner, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun tour() {
        val out = System.getProperty("jmethod.tour")
        assumeTrue(out != null)
        val dir = File(out!!).apply { mkdirs() }
        val dataDir = File(dir, "data").apply { deleteRecursively(); mkdirs() }
        val demo = System.getProperty("jmethod.demoLogin") == "true"
        val secrets: SecretStore = if (demo) systemSecretStore(dataDir, SERVICE) else MapSecrets()
        try {
            if (demo) clearSecrets(secrets)
            val container = container(dataDir, secrets)
            runBlocking { seed(container, dataDir) }
            if (!demo) {
                // Hors ligne : identifiants fictifs et blocage d’une heure, l’app s’ouvre sur les données locales.
                SecretLoginStore(secrets).save(SavedLogin("abcd1234", AccountType.STUDENT, "eleve.exemple", "x"))
                container.throttle.block(Instant.now().plusSeconds(3600))
                runBlocking { container.storeLessons(sampleLessons()) }
            }
            val owners = Owners()
            scene(1280, 820, container, owners) {
                if (demo) {
                    waitForText("Se connecter")
                    shot(dir, "wide-1280-login")
                    onNodeWithText("Code ou adresse de l’établissement").performTextInput("demo")
                    onNodeWithText("Identifiant").performTextInput("demonstration")
                    // Un mauvais mot de passe d’abord : refus de Pronote affiché sous le bouton.
                    onNodeWithText("Mot de passe").performTextInput("mauvais-mot-de-passe")
                    onNodeWithText("Mot de passe").performKeyInput { pressKey(Key.Enter) }
                    waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Pronote a refusé", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    shot(dir, "wide-1280-login-rejected")
                    onNodeWithText("Mot de passe").performTextInput("pronotevs")
                    // Entrée envoie le formulaire.
                    onNodeWithText("Mot de passe").performKeyInput { pressKey(Key.Enter) }
                }
                waitUntil(timeoutMillis = 90_000) { onAllNodesWithContentDescription("Planning").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                shot(dir, "wide-1280-today")
                tab("Planning"); shot(dir, "wide-1280-planning")
                tab("Mes cours"); shot(dir, "wide-1280-courses")
                onAllNodesWithText("Additionner des fractions").onFirst().performClick()
                waitForIdle(); shot(dir, "wide-1280-courses-two-pane")
                // « Valider » J0 : la fiche de séance s’ouvre en panneau latéral, le PDF du cours s’y affiche.
                onAllNodesWithText("Valider").onFirst().performClick()
                waitForIdle(); shot(dir, "wide-1280-session-panel")
                onAllNodesWithText("fiche-fractions.pdf").onFirst().performClick()
                waitForIdle(); shot(dir, "wide-1280-pdf-viewer")
                // Échap : le document se ferme, puis la fiche.
                back(); back()
                assertEquals(1, onAllNodes(isRoot()).fetchSemanticsNodes().size)
                tab("Mes progrès"); shot(dir, "wide-1280-stats")
                tab("Réglages"); shot(dir, "wide-1280-settings")
                tab("Aujourd’hui", substring = true)
            }
            scene(720, 900, container, owners) {
                waitForIdle(); shot(dir, "medium-720-today")
            }
            scene(390, 844, container, owners) {
                waitForIdle(); shot(dir, "compact-390-today")
                tab("Planning"); shot(dir, "compact-390-planning")
                tab("Mes cours"); shot(dir, "compact-390-courses")
                onAllNodesWithText("Additionner des fractions").onFirst().performClick()
                waitForIdle(); shot(dir, "compact-390-course-sheet")
                back()
                assertEquals(1, onAllNodes(isRoot()).fetchSemanticsNodes().size)
            }
            if (demo) {
                // « Rester connecté » : identifiants dans le Trousseau, puis reconnexion silencieuse au lancement suivant.
                val stored = SecretLoginStore(secrets).load()
                assertNotNull(stored?.password)
                assertEquals("demonstration", stored?.username)
                val next = container(dataDir, secrets)
                scene(1280, 820, next, Owners()) {
                    waitUntil(timeoutMillis = 90_000) { onAllNodesWithContentDescription("Planning").fetchSemanticsNodes().isNotEmpty() }
                    waitForIdle(); shot(dir, "wide-1280-relaunch")
                }
            }
        } finally {
            if (demo) clearSecrets(secrets)
        }
    }

    private fun container(dataDir: File, secrets: SecretStore) = AppContainer(
        filesDir = dataDir,
        cacheDir = File(dataDir, "cache"),
        vault = MemoryVault(secrets),
        loginStore = SecretLoginStore(secrets),
        throttle = FileThrottle(File(dataDir, "pronote-throttle.properties")),
        scheduler = null,
        clock = AppClock.SYSTEM,
    )

    private fun clearSecrets(secrets: SecretStore) {
        secrets.delete("pronote-login")
        secrets.delete("pronote-child")
    }

    private fun scene(width: Int, height: Int, container: AppContainer, owners: Owners, block: DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width, height) {
            setContent {
                CompositionLocalProvider(
                    LocalPlatform provides DesktopPlatform({ null }, container.cacheDir),
                    LocalViewModelStoreOwner provides owners,
                    LocalLifecycleOwner provides owners,
                ) {
                    JMethodTheme { JMethodApp(container) { scope -> desktopConnection(container, scope) } }
                }
            }
            block()
        }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** Échap dans la fenêtre : même effet que la touche, par la pile des retours. */
    private fun DesktopComposeUiTest.back() {
        runOnUiThread { DesktopBack.dispatch() }
        waitForIdle()
        Thread.sleep(500)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.tab(label: String, substring: Boolean = false) {
        onAllNodesWithContentDescription(label, substring = substring).onFirst().performClick()
        waitForIdle()
    }

    private fun DesktopComposeUiTest.shot(dir: File, name: String) {
        Thread.sleep(400)
        waitForIdle()
        ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    /** Cours d’exemple (aucune donnée réelle) et une fiche PDF générée. */
    private suspend fun seed(container: AppContainer, dataDir: File) {
        container.repository.current()
        val today = LocalDate.now(AppClock.ZONE)
        val pdf = File(dataDir, "fiche-fractions.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 22f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText("Fiche : additionner des fractions")
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                listOf("1. Mettre au meme denominateur.", "2. Additionner les numerateurs.", "3. Simplifier le resultat.").forEach {
                    stream.newLineAtOffset(0f, -32f)
                    stream.showText(it)
                }
                stream.endText()
            }
            document.save(pdf)
        }
        val courses = listOf(
            Triple("maths", "Fractions", "Additionner des fractions") to 1L,
            Triple("francais", "Grammaire", "Le complément d’objet direct") to 2L,
            Triple("histoire-geo", "La Révolution française", "La prise de la Bastille") to 4L,
            Triple("anglais", "Present perfect", "Have you ever…?") to 7L,
            Triple("svt", "La cellule", "Observer une cellule au microscope") to 14L,
        )
        courses.forEachIndexed { index, (course, daysAgo) ->
            val form = CourseEditing.Form(course.first, course.second, course.third, today.minusDays(daysAgo).toString(), "Exemple de cours pour la visite guidée.")
            val documents = if (index == 0) listOf(PendingDocument("fiche-fractions.pdf", pdf, DocType.PDF, "Fiche : additionner des fractions")) else emptyList()
            container.courseCreator.create(form, null, documents)
            delay(5)
        }
    }

    private fun sampleLessons(): List<Lesson> {
        val start = LocalDate.now(AppClock.ZONE)
        val subjects = listOf("MATHEMATIQUES", "FRANCAIS", "HISTOIRE-GEOGRAPHIE", "ANGLAIS LV1", "SVT")
        return (0 until 10).flatMap { day ->
            val date = start.plusDays(day.toLong())
            if (date.dayOfWeek.value >= 6) emptyList() else (0 until 4).map { slot ->
                val hour = 8 + slot * 2
                Lesson(
                    id = "exemple-$day-$slot", subject = subjects[(day + slot) % subjects.size],
                    start = "${date}T%02d:00".format(hour), end = "${date}T%02d:55".format(hour),
                    teacher = "M. Exemple", room = "Salle ${100 + slot}", cancelled = false,
                )
            }
        }
    }

    private companion object {
        /** Service du coffre réservé aux essais : jamais celui de l’app installée. */
        const val SERVICE = "J-Method essai"
    }
}
