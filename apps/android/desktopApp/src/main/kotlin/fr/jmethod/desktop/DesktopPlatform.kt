package fr.jmethod.desktop

import fr.jmethod.app.domain.SuccessSound
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import fr.jmethod.app.pronote.PronoteUiState
import fr.jmethod.app.ui.platform.FileSink
import fr.jmethod.app.ui.platform.FileSource
import fr.jmethod.app.ui.platform.PlatformServices
import fr.jmethod.app.ui.pronote.PronoteViewModel
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.util.Locale

/** Services de l’ordinateur : app par défaut du système, navigateur, boîtes « Enregistrer » et « Ouvrir ». */
class DesktopPlatform(private val window: () -> Frame?, private val cacheDir: File) : PlatformServices {
    override val device = "cet ordinateur"
    override val isDesktop = true
    override val backupNote = ""
    override val appVersion: String = System.getProperty("jmethod.version") ?: "—"

    private val audioExecutor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "J-Method audio").apply { isDaemon = true }
    }
    private val audioGeneration = AtomicLong()
    private var successAudio: Clip? = null

    override fun playSuccessSound(sound: SuccessSound) {
        if (window()?.isActive != true) return
        val request = audioGeneration.incrementAndGet()
        audioExecutor.execute {
            if (audioGeneration.get() != request) return@execute
            closeClip()
            val asset = javaClass.getResource("/raw/${sound.asset}.wav") ?: return@execute
            runCatching {
                val clip = AudioSystem.getClip()
                successAudio = clip
                AudioSystem.getAudioInputStream(asset).use(clip::open)
                clip.addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP) audioExecutor.execute {
                        if (successAudio == clip) closeClip()
                    }
                }
                if (audioGeneration.get() == request) clip.start() else closeClip()
            }.onFailure { closeClip() }
        }
    }

    override fun stopSuccessSound() {
        audioGeneration.incrementAndGet()
        audioExecutor.execute { closeClip() }
    }

    private fun closeClip() {
        val clip = successAudio
        successAudio = null
        clip?.close()
    }

    private fun desktop(action: Desktop.Action): Desktop? =
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().takeIf { it.isSupported(action) } else null

    /**
     * Ouvre le document avec l’app par défaut du système. Un document actif (HTML, SVG…) est d’abord
     * copié en `.txt` dans le cache : il s’ouvre comme texte, jamais exécuté par un navigateur.
     */
    override fun openFile(file: File): Boolean {
        val desktop = desktop(Desktop.Action.OPEN) ?: return false
        return runCatching {
            val target = if (isActive(file.name)) inert(file) else file
            desktop.open(target)
            true
        }.getOrDefault(false)
    }

    /** Sur ordinateur, « Ouvrir avec… » ouvre le document dans l’app par défaut du système. */
    override fun openWith(file: File, name: String): Boolean {
        val desktop = desktop(Desktop.Action.OPEN) ?: return false
        return runCatching {
            val target = if (isActive(name)) inert(file) else file
            desktop.open(target)
            true
        }.getOrDefault(false)
    }

    override fun openLink(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        runCatching { desktop(Desktop.Action.BROWSE)?.browse(URI(url)) }
    }

    @Composable
    override fun rememberExportLauncher(onChosen: (FileSink?) -> Unit): (String) -> Unit {
        val callback = rememberUpdatedState(onChosen)
        return { name ->
            val dialog = FileDialog(window(), "Exporter mes données", FileDialog.SAVE).apply { file = name }
            dialog.isVisible = true
            val chosen = dialog.file?.let { File(dialog.directory, it) }
            callback.value(chosen?.let { file -> FileSink { file.outputStream() } })
        }
    }

    @Composable
    override fun rememberImportLauncher(onChosen: (FileSource?) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onChosen)
        return {
            val dialog = FileDialog(window(), "Importer une sauvegarde", FileDialog.LOAD).apply {
                setFilenameFilter { _, fileName -> fileName.lowercase(Locale.ROOT).endsWith(".json") || fileName.lowercase(Locale.ROOT).endsWith(".txt") }
            }
            dialog.isVisible = true
            val chosen = dialog.file?.let { File(dialog.directory, it) }
            callback.value(chosen?.let { file -> FileSource { file.inputStream() } })
        }
    }

    /** Connexion directe : aucune page officielle n’est affichée sur ordinateur. */
    @Composable
    override fun OfficialPageLayer(viewModel: PronoteViewModel, state: PronoteUiState, modifier: Modifier) = Unit

    private fun isActive(name: String) =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("html", "htm", "xhtml", "svg", "xml", "js", "mht", "mhtml")

    private fun inert(file: File): File {
        val dir = File(cacheDir, "text-view").apply { mkdirs() }
        return File(dir, file.name + ".txt").also { file.copyTo(it, overwrite = true) }
    }
}
