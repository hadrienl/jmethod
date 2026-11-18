package fr.jmethod.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.jmethod.app.documents.DocType
import fr.jmethod.app.domain.RevisionSession
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.ui.notebook.NotebookContentBlock
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing

/** Titre de section des fiches (séance, cours). */
@Composable
internal fun SheetSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
}

/** Description du cours, repliée au-delà de 6 lignes (« Afficher plus »). */
@Composable
internal fun CollapsibleDescription(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = JColors.Slate700,
            maxLines = if (expanded) Int.MAX_VALUE else RevisionSession.DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = JSpacing.s),
                modifier = Modifier.offset(x = (-8).dp).heightIn(min = 48.dp),
            ) { Text(if (expanded) "Afficher moins" else "Afficher plus", color = JColors.Violet800, fontWeight = FontWeight.SemiBold) }
        }
    }
}

private fun typeIcon(document: CourseDocument): ImageVector = typeIcon(document.name)

private fun typeIcon(name: String): ImageVector {
    val type = DocType.displayTypeOf(name)
    return when {
        type == DocType.PDF -> JIcons.Pdf
        type.isImage -> JIcons.Image
        else -> JIcons.Document
    }
}

/**
 * Documents du cours : icône du type, nom, titre lu. Toucher un document l’ouvre dans la feuille ;
 * le premier (document principal) porte « Revoir le cours ». Un fichier absent se retélécharge.
 */
@Composable
internal fun CourseDocumentsList(
    documents: List<Pair<CourseDocument, Boolean>>,
    onOpen: (CourseDocument) -> Unit,
    onRedownload: ((CourseDocument) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
        documents.forEachIndexed { index, (document, present) ->
            val principal = index == 0
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(JShapes.control)
                    .background(if (principal) JColors.Violet50 else JColors.Slate50)
                    .then(if (present) Modifier.clickable(onClickLabel = "Ouvrir dans l’app", role = Role.Button) { onOpen(document) } else Modifier)
                    .padding(start = JSpacing.m, end = JSpacing.xs, top = JSpacing.s, bottom = JSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(JShapes.badge).background(androidx.compose.ui.graphics.Color.White), contentAlignment = Alignment.Center) {
                    Icon(typeIcon(document), contentDescription = null, tint = JColors.Violet700, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(JSpacing.m))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(document.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val title = document.extractedTitle?.takeIf { it.isNotBlank() && it != document.name }
                    when {
                        !present -> Text("À retélécharger", style = MaterialTheme.typography.bodySmall, color = JColors.Amber900)
                        title != null -> Text(title, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                when {
                    !present && onRedownload != null ->
                        TextButton(onClick = { onRedownload(document) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retélécharger") }
                    present && principal ->
                        TextButton(onClick = { onOpen(document) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Revoir le cours", color = JColors.Violet800, fontWeight = FontWeight.Bold)
                        }
                    present -> Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = JColors.Slate500,
                        modifier = Modifier.padding(end = JSpacing.s),
                    )
                }
            }
        }
    }
}

/** « Dans le cahier de textes » : contenus de la séance d’origine, ou la seule ligne de la séance. */
@Composable
internal fun NotebookPartSection(
    part: RevisionSession.NotebookPart,
    loading: Boolean,
    downloadingId: String?,
    onDownload: (NotebookAttachment) -> Unit,
    onLink: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
        SheetSectionTitle("Dans le cahier de textes")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Icon(JIcons.Notebook, contentDescription = null, tint = JColors.Violet700, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(JSpacing.s))
            Text(part.sourceLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600, modifier = Modifier.weight(1f))
            if (loading && part.contents == null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = JColors.Violet700)
            }
        }
        val contents = part.contents
        if (!contents.isNullOrEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(JShapes.inner).border(1.dp, JColors.Slate200, JShapes.inner)
                    .padding(horizontal = JSpacing.l, vertical = JSpacing.m),
                verticalArrangement = Arrangement.spacedBy(JSpacing.s),
            ) {
                contents.forEach { content -> NotebookContentBlock(content, downloadingId, onDownload, onLink) }
            }
        }
    }
}

/** « Aucun document joint à cette séance dans Pronote » (section 8.5). */
@Composable
internal fun NoDocumentLine(fromNotebook: Boolean) {
    Text(
        if (fromNotebook) "Aucun document joint à cette séance dans Pronote" else "Aucun document joint à ce cours",
        style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
    )
}

/**
 * « Documents proches en <matière> » (section 8.5) : 5 au plus, « Afficher tout » au-delà. Toucher une
 * ligne ouvre le document dans la feuille ; « Joindre à ce cours » le copie dans les documents du cours.
 */
@Composable
internal fun NearbyDocumentsSection(
    subjectName: String,
    items: List<RevisionSession.Nearby>,
    downloadingId: String?,
    onOpen: (RevisionSession.Nearby) -> Unit,
    onAttach: (RevisionSession.Nearby) -> Unit,
) {
    if (items.isEmpty()) return
    var all by rememberSaveable(subjectName, items.size) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
        SheetSectionTitle("Documents proches en $subjectName")
        val shown = if (all) items else items.take(RevisionSession.NEARBY_VISIBLE)
        shown.forEach { item ->
            val busy = item is RevisionSession.Nearby.FromNotebook && downloadingId == item.attachment.id
            val blocked = item is RevisionSession.Nearby.FromNotebook && downloadingId != null
            Column(
                Modifier.fillMaxWidth().clip(JShapes.control).border(1.dp, JColors.Slate200, JShapes.control)
                    .clickable(enabled = !blocked, onClickLabel = "Ouvrir dans l’app", role = Role.Button) { onOpen(item) }
                    .padding(start = JSpacing.m, end = JSpacing.xs, top = JSpacing.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Box(Modifier.size(36.dp).clip(JShapes.badge).background(JColors.Slate50), contentAlignment = Alignment.Center) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = JColors.Violet700)
                        } else {
                            Icon(typeIcon(item.name), contentDescription = null, tint = JColors.Slate600, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(JSpacing.m))
                    Column(Modifier.weight(1f).padding(end = JSpacing.s)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        item.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(item.dayLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate500)
                    }
                }
                TextButton(
                    onClick = { onAttach(item) },
                    enabled = !blocked,
                    contentPadding = PaddingValues(horizontal = JSpacing.s),
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                ) { Text("Joindre à ce cours", color = if (blocked) JColors.Slate500 else JColors.Violet800, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (items.size > RevisionSession.NEARBY_VISIBLE) {
            TextButton(
                onClick = { all = !all },
                contentPadding = PaddingValues(horizontal = JSpacing.s),
                modifier = Modifier.offset(x = (-8).dp).heightIn(min = 48.dp),
            ) { Text(if (all) "Afficher moins" else "Afficher tout (${items.size})", color = JColors.Violet800, fontWeight = FontWeight.SemiBold) }
        }
    }
}
