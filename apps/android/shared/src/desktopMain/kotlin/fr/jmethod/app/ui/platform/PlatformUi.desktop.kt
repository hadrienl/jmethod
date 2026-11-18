package fr.jmethod.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Pile des gestionnaires « Retour » de la fenêtre : la touche Échap appelle le plus récent qui est
 * actif (fiche, visionneuse, formulaire plein écran…). Les dialogues gèrent Échap eux-mêmes.
 */
object DesktopBack {
    private class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    /** Appelé par la fenêtre pour Échap : vrai si un gestionnaire a consommé la touche. */
    fun dispatch(): Boolean {
        val entry = synchronized(entries) { entries.lastOrNull { it.enabled } } ?: return false
        entry.onBack()
        return true
    }

    @Composable
    internal fun Register(enabled: Boolean, onBack: () -> Unit) {
        val current = rememberUpdatedState(onBack)
        val entry = remember { Entry(enabled) { current.value() } }
        SideEffect { entry.enabled = enabled }
        DisposableEffect(entry) {
            synchronized(entries) { entries += entry }
            onDispose { synchronized(entries) { entries -= entry } }
        }
    }
}

@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = DesktopBack.Register(enabled, onBack)

/** Ordinateur : animations conservées (aucun réglage système commun à Windows et macOS lu ici). */
actual fun animationsEnabled(): Boolean = true

actual fun decodeImage(file: File, maxSide: Int): ImageBitmap? = runCatching {
    val image = ImageIO.read(file) ?: return null
    val scale = minOf(1.0, maxSide.toDouble() / maxOf(image.width, image.height))
    val result = if (scale >= 1.0) {
        image
    } else {
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { scaled ->
            val g = scaled.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, width, height, null)
            g.dispose()
        }
    }
    result.toComposeImageBitmap()
}.getOrNull()

actual fun openPdf(file: File): PdfPages? = runCatching {
    val document: PDDocument = Loader.loadPDF(file)
    val renderer = PDFRenderer(document)
    object : PdfPages {
        override val pageCount: Int = document.numberOfPages

        override fun render(index: Int, width: Int): ImageBitmap? = runCatching {
            val box = document.getPage(index).cropBox
            val points = if (document.getPage(index).rotation % 180 == 0) box.width else box.height
            val scale = (width / points.coerceAtLeast(1f)).coerceIn(0.1f, 8f)
            renderer.renderImage(index, scale, ImageType.RGB).toComposeImageBitmap()
        }.getOrNull()

        override fun close() {
            runCatching { document.close() }
        }
    }
}.getOrNull()

@Composable
actual fun VerticalScrollbar(state: androidx.compose.foundation.ScrollState, modifier: androidx.compose.ui.Modifier) =
    androidx.compose.foundation.VerticalScrollbar(androidx.compose.foundation.rememberScrollbarAdapter(state), modifier)

@Composable
actual fun VerticalScrollbar(state: androidx.compose.foundation.lazy.LazyListState, modifier: androidx.compose.ui.Modifier) =
    androidx.compose.foundation.VerticalScrollbar(androidx.compose.foundation.rememberScrollbarAdapter(state), modifier)

actual fun androidx.compose.ui.Modifier.escapeToBack(): androidx.compose.ui.Modifier = this.then(
    androidx.compose.ui.Modifier.onKeyEvent { event ->
        event.key == androidx.compose.ui.input.key.Key.Escape &&
            event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && DesktopBack.dispatch()
    },
)
