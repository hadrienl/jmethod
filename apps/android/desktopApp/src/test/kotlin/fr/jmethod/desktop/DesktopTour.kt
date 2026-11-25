package fr.jmethod.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import fr.jmethod.app.ui.platform.DesktopBack
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import fr.jmethod.app.domain.FrenchDates
import fr.jmethod.app.domain.TodayWidgetVisit
import fr.jmethod.app.domain.sentenceCase
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
import org.junit.Assert.assertTrue
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
        // Taille des captures larges (`-Pjmethod.tourSize=1366x768` pour le Microsoft Store, 1366×768 minimum).
        val (wideW, wideH) = (System.getProperty("jmethod.tourSize") ?: "1280x820").split('x').map { it.toInt() }
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
            val viewportWidth = mutableIntStateOf(wideW)
            scene(wideW, wideH, container, owners, { viewportWidth.intValue }) {
                if (demo) {
                    waitForText("Se connecter")
                    shot(dir, "wide-$wideW-login")
                    onNodeWithText("Code ou adresse de l’établissement").performTextInput("demo")
                    onNodeWithText("Identifiant").performTextInput("demonstration")
                    // Un mauvais mot de passe d’abord : refus de Pronote affiché sous le bouton.
                    onNodeWithText("Mot de passe").performTextInput("mauvais-mot-de-passe")
                    onNodeWithText("Mot de passe").performKeyInput { pressKey(Key.Enter) }
                    waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Pronote a refusé", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    shot(dir, "wide-$wideW-login-rejected")
                    onNodeWithText("Mot de passe").performTextInput("pronotevs")
                    // Entrée envoie le formulaire.
                    onNodeWithText("Mot de passe").performKeyInput { pressKey(Key.Enter) }
                }
                waitUntil(timeoutMillis = 90_000) { onAllNodesWithContentDescription("Planning").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                if (wideW >= 1280) assertTodayHeader(inline = true)
                shot(dir, "wide-$wideW-today")
                if (!demo) {
                    val day = LocalDate.now(AppClock.ZONE)
                    runBlocking {
                        container.repository.update { it.copy(todayWidgetVisit = TodayWidgetVisit(day.minusDays(4).toString())) }
                        container.repository.recordTodayVisit()
                    }
                    waitForText("Préparer ma reprise")
                    onNodeWithText("Préparer ma reprise").performClick()
                    waitForText("Enregistrer (3)")
                    shot(dir, "wide-$wideW-resume-selection")
                    onNodeWithText("Enregistrer (3)").performClick()
                    waitForText("Continuer")
                    assertEquals(3, runBlocking { container.repository.current() }.todayWidgetVisit?.selection?.size)
                    shot(dir, "wide-$wideW-resume-started")
                    runBlocking {
                        container.repository.update { it.copy(todayWidgetVisit = TodayWidgetVisit(day.toString())) }
                    }
                }
                tab("Agenda", substring = true)
                waitForText("Devoirs")
                shot(dir, "wide-$wideW-agenda")
                tab("Planning"); shot(dir, "wide-$wideW-planning")
                tab("Mes cours"); shot(dir, "wide-$wideW-courses")
                onAllNodesWithText("Additionner des fractions").onFirst().performClick()
                waitForIdle(); shot(dir, "wide-$wideW-courses-two-pane")
                if (wideW >= 1200) {
                    onAllNodesWithContentDescription("Mes cours").onFirst().assertIsSelected()
                    onNodeWithText("Référence personnelle").performTextReplacement("C42")
                    for (width in listOf(599, 600, 1199, 1200, 1599, 1600).filter { it <= wideW }) {
                        runOnUiThread { viewportWidth.intValue = width }
                        waitForIdle()
                        waitForText("C42")
                        waitForText("fiche-fractions.pdf")
                        assertEquals(1, onAllNodesWithText("fiche-fractions.pdf").fetchSemanticsNodes().size)
                        shot(dir, "resize-$width-course")
                    }
                    runOnUiThread { viewportWidth.intValue = wideW }
                    waitForIdle()
                }
                // « Valider » J0 : la fiche de séance s’ouvre en panneau latéral, le PDF du cours s’y affiche.
                onAllNodesWithText("Valider").onFirst().performClick()
                waitForIdle(); shot(dir, "wide-$wideW-session-panel")
                onAllNodesWithText("fiche-fractions.pdf").onFirst().performClick()
                waitForIdle(); shot(dir, "wide-$wideW-pdf-viewer")
                // Échap : le document se ferme, puis la fiche.
                back(); back()
                assertEquals(1, onAllNodes(isRoot()).fetchSemanticsNodes().size)
                tab("Mes progrès"); shot(dir, "wide-$wideW-stats")
                tab("Réglages"); shot(dir, "wide-$wideW-settings")
                tab("Aujourd’hui", substring = true)
            }
            scene(720, 900, container, owners) {
                waitForIdle()
                val planning = onAllNodesWithContentDescription("Planning").onFirst().getUnclippedBoundsInRoot()
                assertTrue("Le rail commence en haut de la fenêtre", planning.top < 200.dp)
                shot(dir, "medium-720-today")
            }
            scene(390, 844, container, owners) {
                waitForIdle()
                assertTodayHeader(inline = false)
                shot(dir, "compact-390-today")
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
                scene(wideW, wideH, next, Owners()) {
                    waitUntil(timeoutMillis = 90_000) { onAllNodesWithContentDescription("Planning").fetchSemanticsNodes().isNotEmpty() }
                    waitForIdle(); shot(dir, "wide-$wideW-relaunch")
                }
            }
        } finally {
            if (demo) clearSecrets(secrets)
        }
    }

    @Test
    fun rhythmSettingsRemainAccessibleWithSaveBar() {
        val out = System.getProperty("jmethod.tour")
        assumeTrue(out != null)
        val captures = File(requireNotNull(out)).apply { mkdirs() }
        val dataDir = kotlin.io.path.createTempDirectory("jmethod-rhythm-tour").toFile()
        try {
            val secrets = MapSecrets()
            val container = container(dataDir, secrets)
            runBlocking { seed(container, dataDir) }
            SecretLoginStore(secrets).save(SavedLogin("abcd1234", AccountType.STUDENT, "eleve.exemple", "x"))
            container.throttle.block(Instant.now().plusSeconds(3600))

            for ((width, fontScale) in listOf(1440 to 1f, 390 to 1.6f, 320 to 2f)) {
                scene(width, 900, container, Owners(), fontScale = fontScale) {
                    waitUntil(timeoutMillis = 30_000) {
                        onAllNodesWithContentDescription("Planning").fetchSemanticsNodes().isNotEmpty()
                    }
                    if (width < 400) {
                        tab("Plus")
                        onNodeWithText("Réglages").performClick()
                    } else {
                        tab("Réglages")
                    }
                    val title = onNodeWithText("Rythme de révision")
                    title.performScrollTo().assertIsDisplayed()
                    val inRhythm = hasAnyAncestor(hasTestTag("revision-rhythm-card"))
                    val day = onNode(hasText("J0") and inRhythm)
                    day.performScrollTo().assertIsDisplayed()
                    val phase = onAllNodes(hasText("Encodage") and inRhythm).onFirst()
                    val remove = onAllNodes(hasText("Retirer") and inRhythm).onFirst()
                    fun centerY(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot().let {
                        (it.top.value + it.bottom.value) / 2
                    }
                    assertTrue(kotlin.math.abs(centerY(phase) - centerY(day)) < 2)
                    assertTrue(kotlin.math.abs(centerY(remove) - centerY(day)) < 2)
                    shot(captures, "rhythm-$width-card")

                    onAllNodesWithContentDescription("Effets de réussite").onFirst().performScrollTo().performClick()
                    val save = onNodeWithTag("settings-save-bar")
                    save.assertIsDisplayed()
                    val inSaveBar = hasAnyAncestor(hasTestTag("settings-save-bar"))
                    for (label in listOf("Modifications non enregistrées", "Annuler", "Enregistrer")) {
                        onNode(hasText(label) and inSaveBar).assertIsDisplayed()
                    }
                    val addField = onNode(hasText("Nouveau jour J") and inRhythm)
                    addField.performScrollTo().assertIsDisplayed()
                    shot(captures, "rhythm-$width-new-day")
                    val apply = onNodeWithText("Appliquer aux programmes en cours…")
                    apply.performScrollTo().assertIsDisplayed()
                    assertTrue(apply.getUnclippedBoundsInRoot().bottom <= save.getUnclippedBoundsInRoot().top)
                    shot(captures, "rhythm-$width-save-bar")

                    val last = onNodeWithText("Tes cours, tes séances et tes réglages restent", substring = true)
                    last.performScrollTo().assertIsDisplayed()
                    assertTrue(last.getUnclippedBoundsInRoot().bottom <= save.getUnclippedBoundsInRoot().top + 1.dp)
                }
            }
        } finally {
            dataDir.deleteRecursively()
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

    private fun scene(
        width: Int,
        height: Int,
        container: AppContainer,
        owners: Owners,
        viewportWidth: () -> Int = { width },
        fontScale: Float? = null,
        block: DesktopComposeUiTest.() -> Unit,
    ) =
        runDesktopComposeUiTest(width, height) {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides (fontScale?.let { Density(density.density, it) } ?: density),
                    LocalPlatform provides DesktopPlatform({ null }, container.cacheDir),
                    LocalViewModelStoreOwner provides owners,
                    LocalLifecycleOwner provides owners,
                ) {
                    Box(Modifier.width(viewportWidth().dp)) {
                        JMethodTheme { JMethodApp(container) { scope -> desktopConnection(container, scope) } }
                    }
                }
            }
            block()
        }

    private fun DesktopComposeUiTest.assertTodayHeader(inline: Boolean) {
        val date = FrenchDates.weekdayDayMonth(LocalDate.now(AppClock.ZONE)).sentenceCase()
        val dateBounds = onNodeWithText(date).getUnclippedBoundsInRoot()
        val flame = onAllNodesWithContentDescription("La flamme suit le programme", substring = true)
            .onFirst().getUnclippedBoundsInRoot()
        if (inline) {
            assertTrue("La flamme est à droite de la date", flame.left >= dateBounds.right)
            assertTrue("La flamme est à la hauteur de la date", flame.top < dateBounds.bottom)
        } else {
            assertTrue("La flamme passe sous la date", flame.top >= dateBounds.bottom)
        }
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
            val created = container.courseCreator.create(form, null, documents)
            if (created != null) container.repository.update { document ->
                fr.jmethod.app.domain.RevisionProgrammes.start(document, listOf(created.course.id), form.date)
            }
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
