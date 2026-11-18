package fr.jmethod.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import java.io.Closeable
import java.io.File

/**
 * Retour système : bouton ou geste Retour sur Android, touche Échap sur ordinateur. Le gestionnaire
 * le plus récent (et actif) l’emporte, comme `BackHandler`.
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Les animations sont désactivées dans les réglages d’accessibilité du système. */
expect fun animationsEnabled(): Boolean

/** Image d’un document, réduite pour que son plus grand côté reste sous [maxSide] pixels. */
expect fun decodeImage(file: File, maxSide: Int = 3000): ImageBitmap?

/** Pages d’un PDF rendues en images (PdfRenderer sur Android, PDFBox sur ordinateur). Non thread-safe. */
interface PdfPages : Closeable {
    val pageCount: Int

    /** Page [index] rendue sur fond blanc à [width] pixels de large ; `null` si elle est illisible. */
    fun render(index: Int, width: Int): ImageBitmap?
}

/** Ouvre un PDF local ; `null` s’il ne peut pas être lu. */
expect fun openPdf(file: File): PdfPages?
