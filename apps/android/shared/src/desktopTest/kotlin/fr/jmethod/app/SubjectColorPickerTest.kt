package fr.jmethod.app

import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppDocumentStore
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.domain.SubjectColorDraft
import fr.jmethod.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Brouillon et confirmation du sélecteur de couleur (#8), au niveau du ViewModel : rien n’est écrit
 * avant « Enregistrer », seule la matière choisie change, une erreur garde le brouillon.
 */
class SubjectColorPickerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir: File = Files.createTempDirectory("jm-colors").toFile()
    private val repository = AppRepository(AppDocumentStore(File(dir, "data.json")), scope, Dispatchers.Unconfined)
    private val viewModel = SettingsViewModel(repository, AppClock { Instant.parse("2026-11-23T18:00:00Z") }, scope)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun picker(): SubjectColorDraft? = viewModel.colorPicker.value

    /** Les écritures passent par le dispatcher immédiat ; on attend quand même la fin d’un enregistrement. */
    private fun awaitSaved() {
        val deadline = System.currentTimeMillis() + 5_000
        while (picker()?.saving == true && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse("enregistrement bloqué", picker()?.saving == true)
    }

    private fun storedFiles(): Map<String, String> =
        dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText() }

    @Test
    fun openingWithoutChangesShowsTheStoredColourAndWritesNothing() = runBlocking {
        val before = repository.current()
        val subject = before.subjects.first()
        val files = storedFiles()
        viewModel.openSubjectColor(subject.id)
        val draft = picker()!!
        assertEquals(subject.color.uppercase(), draft.hex)
        assertEquals(draft.hex, draft.text)
        assertFalse(draft.changed)
        // Enregistrer sans rien modifier ferme le sélecteur sans écrire.
        viewModel.saveSubjectColor()
        awaitSaved()
        assertNull(picker())
        assertEquals(before, repository.current())
        assertEquals(files, storedFiles())
    }

    @Test
    fun savingChangesOnlyThisSubjectAndKeepsOtherSettingsDrafts() = runBlocking {
        val collector = scope.launch { viewModel.uiState.collect {} }
        val before = repository.current()
        val subject = before.subjects[1]
        viewModel.setSensitivity(0.7)
        assertTrue(viewModel.uiState.value!!.draft.dirty)

        viewModel.openSubjectColor(subject.id)
        viewModel.editSubjectColor { it.withHue(120.0) }
        viewModel.editSubjectColor { it.withText("2563eb") }
        assertEquals("2563eb", picker()!!.text)
        assertEquals("#2563EB", picker()!!.hex)
        viewModel.saveSubjectColor()
        awaitSaved()

        assertNull(picker())
        val after = repository.current()
        assertEquals("#2563EB", after.subjects.first { it.id == subject.id }.color)
        assertEquals(before.subjects.filter { it.id != subject.id }, after.subjects.filter { it.id != subject.id })
        assertEquals(before.subjects.map { it.id }, after.subjects.map { it.id })
        assertEquals(before.settings, after.settings)
        assertEquals(before.courses, after.courses)
        // Le brouillon des réglages n’est ni perdu ni appliqué.
        val settingsDraft = viewModel.uiState.value!!.draft
        assertTrue(settingsDraft.dirty)
        assertEquals(0.7, settingsDraft.sensitivity, 1e-9)
        collector.cancel()
    }

    @Test
    fun cancellingPersistsNothing() = runBlocking {
        val before = repository.current()
        val files = storedFiles()
        viewModel.openSubjectColor(before.subjects.first().id)
        viewModel.editSubjectColor { it.withSquare(0.3, 0.4) }
        viewModel.editSubjectColor { it.stepHue(15.0) }
        assertTrue(picker()!!.changed)
        viewModel.cancelSubjectColor()
        assertNull(picker())
        assertEquals(before, repository.current())
        assertEquals(files, storedFiles())
        // Un nouveau sélecteur repart de la couleur enregistrée.
        viewModel.openSubjectColor(before.subjects.first().id)
        assertEquals(before.subjects.first().color.uppercase(), picker()!!.hex)
    }

    @Test
    fun invalidOrPartialCodeIsKeptButCannotBeSaved() = runBlocking {
        val before = repository.current()
        viewModel.openSubjectColor(before.subjects.first().id)
        val valid = picker()!!.hex
        for (input in listOf("#2563E", "#2563EG", "", "#12345678")) {
            viewModel.editSubjectColor { it.withText(input) }
            val draft = picker()!!
            assertEquals(input, draft.text)
            assertEquals("dernier aperçu valide conservé pour « $input »", valid, draft.hex)
            assertFalse(draft.canSave)
            assertNotNull(draft.textError)
            viewModel.saveSubjectColor()
            assertNotNull(picker())
            assertEquals(before, repository.current())
        }
        // Une saisie complète sans « # » est acceptée puis normalisée à la validation.
        viewModel.editSubjectColor { it.withText(" abcdef") }
        assertTrue(picker()!!.canSave)
        viewModel.editSubjectColor { it.committed() }
        assertEquals("#ABCDEF", picker()!!.text)
    }

    @Test
    fun greyCodeKeepsTheHueOfTheWheel() = runBlocking {
        viewModel.openSubjectColor(repository.current().subjects.first().id)
        viewModel.editSubjectColor { it.withHue(200.0) }
        viewModel.editSubjectColor { it.withText("#808080") }
        assertEquals(200.0, picker()!!.hue, 1e-9)
        assertEquals(0.0, picker()!!.saturation, 1e-9)
    }

    @Test
    fun retypingTheDisplayedCodeDoesNotMoveTheCursors() = runBlocking {
        viewModel.openSubjectColor(repository.current().subjects.first().id)
        // Couleur peu saturée : relire son code arrondi donnerait une autre teinte (220° au lieu de 205°).
        viewModel.editSubjectColor { it.withHue(205.0).withSquare(0.02, 0.5) }
        val shown = picker()!!
        for (input in listOf(shown.hex.lowercase(), shown.hex.removePrefix("#"), shown.hex)) {
            viewModel.editSubjectColor { it.withText(input) }
            val draft = picker()!!
            assertEquals(input, draft.text)
            assertEquals(205.0, draft.hue, 1e-9)
            assertEquals(0.02, draft.saturation, 1e-9)
            assertEquals(0.5, draft.value, 1e-9)
            assertEquals(shown.hex, draft.hex)
        }
    }

    @Test
    fun subjectDeletedDuringEditingIsNotRecreated() = runBlocking {
        val subject = repository.current().subjects.first()
        viewModel.openSubjectColor(subject.id)
        viewModel.editSubjectColor { it.withText("#123ABC") }
        repository.update { document -> document.copy(subjects = document.subjects.filter { it.id != subject.id }) }
        val before = repository.current()
        viewModel.saveSubjectColor()
        awaitSaved()
        assertEquals(before, repository.current())
        assertTrue(repository.current().subjects.none { it.id == subject.id })
        val draft = picker()!!
        assertTrue(draft.removed)
        assertFalse(draft.canSave)
        assertEquals(SubjectColorDraft.REMOVED_MESSAGE, draft.failure)
    }

    @Test
    fun writeFailureKeepsTheDraftAndAllowsRetrying() = runBlocking {
        val before = repository.current()
        val subject = before.subjects.first()
        viewModel.openSubjectColor(subject.id)
        viewModel.editSubjectColor { it.withText("#0F766E") }
        // Fichier temporaire remplacé par un dossier : l’écriture échoue quelle que soit la plateforme.
        val blockers = listOf("data.json.tmp", "data-v2.json.tmp", "data-v3.json.tmp").map { File(dir, it).apply { mkdirs() } }
        viewModel.saveSubjectColor()
        awaitSaved()
        val failed = picker()!!
        assertEquals(SubjectColorDraft.FAILURE_MESSAGE, failed.failure)
        assertEquals("#0F766E", failed.hex)
        assertTrue(failed.canSave)
        assertEquals(before, repository.current())

        blockers.forEach { it.deleteRecursively() }
        viewModel.saveSubjectColor()
        awaitSaved()
        assertNull(picker())
        assertEquals("#0F766E", repository.current().subjects.first { it.id == subject.id }.color)
    }
}
