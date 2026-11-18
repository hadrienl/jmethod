package fr.jmethod.app.ui.documents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import fr.jmethod.app.documents.DocType
import fr.jmethod.app.documents.DocumentAnalyzer
import fr.jmethod.app.documents.ReadingBlock
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.ui.theme.JColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * « Ouvrir avec… » : le fichier local en lecture seule par `FileProvider` (aucune adresse Pronote).
 * Un document actif (HTML, SVG) est présenté comme texte.
 */
fun openWith(context: Context, file: File, name: String): Boolean {
    val uri = FileProvider.getUriForFile(context, "fr.jmethod.app.files", file)
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val active = extension in setOf("html", "htm", "xhtml", "svg", "xml", "js", "mht", "mhtml")
    val type = if (active) "text/plain" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(Intent.createChooser(view, "Ouvrir $name avec…"))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Visionneuse dans l’app : PDF (PdfRenderer), images, ou vue « Lecture » pour les autres formats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(document: CourseDocument, file: File, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    val type = remember(file) { DocumentAnalyzer.detect(file, document.name) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = if (type == DocType.PDF || type.isImage) JColors.Slate100 else Color.White,
        topBar = {
            TopAppBar(
                title = { Text(document.name, maxLines = 1, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer le document") } },
                actions = { TextButton(onClick = { openWith(context, file, document.name) }) { Text("Ouvrir avec…") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                type == DocType.PDF -> PdfPages(file)
                type.isImage -> ZoomableImage(file)
                else -> ReadingView(file, document.name)
            }
        }
    }
}

/** Zoom à deux doigts et déplacement, communs au PDF et aux images. */
@Composable
private fun Zoomable(content: @Composable (Modifier) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    content(
        // Défilement libre tant que le document n’est pas agrandi ; déplacement ensuite.
        Modifier.fillMaxSize().transformable(state, canPan = { scale > 1f }).graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        },
    )
}

@Composable
private fun PdfPages(file: File) {
    val lock = remember { Mutex() }
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) {
        ReadingFallback("Ce PDF ne peut pas être affiché ici. Utilise « Ouvrir avec… ».")
        return
    }
    val count = renderer.pageCount
    Zoomable { zoom ->
        LazyColumn(zoom, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items((0 until count).toList()) { index ->
                val bitmap by produceState<Bitmap?>(null, index) {
                    value = withContext(Dispatchers.IO) {
                        lock.withLock {
                            runCatching {
                                renderer.openPage(index).use { page ->
                                    val width = 1400
                                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(android.graphics.Color.WHITE)
                                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                }
                val image = bitmap
                if (image == null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Image(
                        image.asImageBitmap(),
                        contentDescription = "Page ${index + 1} sur $count",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().background(Color.White),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 3000 || bounds.outHeight / sample > 3000) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Zoomable { zoom ->
        Image(image.asImageBitmap(), contentDescription = "Image du document", contentScale = ContentScale.Fit, modifier = zoom)
    }
}

/** Vue « Lecture » : texte structuré extrait (titres, paragraphes, listes), aperçu simplifié. */
@Composable
private fun ReadingView(file: File, name: String) {
    val blocks by produceState<List<ReadingBlock>?>(null, file) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val analysis = DocumentAnalyzer.analyze(file, name)
                analysis.readingView ?: when (analysis.type) {
                    DocType.TXT, DocType.MD -> DocumentAnalyzer.textReading(file.readBytes(), analysis.type == DocType.MD)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }
    }
    val current = blocks
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (current.isEmpty()) {
        ReadingFallback("Aperçu indisponible pour ce document. Utilise « Ouvrir avec… ».")
        return
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Aperçu simplifié : mise en page, images et tableaux ne sont pas reproduits.",
                style = MaterialTheme.typography.bodySmall,
                color = JColors.Slate600,
                modifier = Modifier.fillMaxWidth().background(JColors.Slate50).padding(10.dp),
            )
        }
        items(current) { block ->
            when (block.kind) {
                ReadingBlock.Kind.HEADING -> Text(
                    block.text,
                    style = if ((block.level ?: 1) <= 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                ReadingBlock.Kind.LIST_ITEM -> Text(
                    "•  ${block.text}", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = (8 + 16 * ((block.level ?: 1) - 1)).dp),
                )
                ReadingBlock.Kind.PARAGRAPH -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ReadingFallback(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = JColors.Slate700, modifier = Modifier.semantics { contentDescription = text })
    }
}
