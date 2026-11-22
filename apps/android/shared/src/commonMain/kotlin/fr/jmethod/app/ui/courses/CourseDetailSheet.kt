package fr.jmethod.app.ui.courses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import fr.jmethod.app.ui.components.AdaptiveSheet
import fr.jmethod.app.ui.components.ScrollbarBox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.CourseDetailModel
import fr.jmethod.app.domain.RevisionSession
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.ui.documents.DocumentViewerScreen
import fr.jmethod.app.ui.documents.SheetDocument
import fr.jmethod.app.ui.today.CollapsibleDescription
import fr.jmethod.app.ui.today.NearbyDocumentsSection
import fr.jmethod.app.ui.today.NoDocumentLine
import fr.jmethod.app.ui.today.CourseDocumentsList
import fr.jmethod.app.ui.today.NotebookPartSection
import fr.jmethod.app.ui.today.SheetSectionTitle
import fr.jmethod.app.ui.platform.SystemBackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import fr.jmethod.app.ui.components.SubjectDot
import fr.jmethod.app.ui.components.Pill
import androidx.compose.ui.text.style.TextAlign
import fr.jmethod.app.ui.components.phaseColors
import fr.jmethod.app.ui.components.subjectColor

/** Fiche d’un cours (`CourseDetailModal`) : historique des J, validation, suppression confirmée. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    model: CourseDetailModel,
    onDismiss: () -> Unit,
    onValidate: (String) -> Unit,
    onDelete: () -> Unit,
    /** `null` si la matière du cours a disparu. */
    onOpenNotebook: (() -> Unit)? = null,
    /** Documents du cours et présence du fichier sur ce téléphone. */
    documents: List<Pair<CourseDocument, Boolean>> = emptyList(),
    onOpenDocument: (CourseDocument) -> Unit = {},
    onRedownload: ((CourseDocument) -> Unit)? = null,
    /** Contenu de la séance d’origine dans le cahier de textes (section 8.4) ; `null` pour un cours saisi. */
    notebookPart: RevisionSession.NotebookPart? = null,
    notebookLoading: Boolean = false,
    downloadingId: String? = null,
    onDownloadAttachment: (NotebookAttachment) -> Unit = {},
    onLink: (String) -> Unit = {},
    /** Document ouvert dans la feuille. */
    viewer: SheetDocument? = null,
    onCloseViewer: () -> Unit = {},
    /** Documents proches de la même matière (section 8.5). */
    nearby: List<RevisionSession.Nearby> = emptyList(),
    onOpenNearby: (RevisionSession.Nearby) -> Unit = {},
    onAttachNearby: (RevisionSession.Nearby) -> Unit = {},
    onRenameReference: (String) -> Unit = {},
) {
    var editedReference by rememberSaveable(model.course.id, model.course.displayReference) { mutableStateOf(model.course.displayReference) }
    var confirmDelete by rememberSaveable(model.course.id) { mutableStateOf(false) }
    AdaptiveSheet(onDismissRequest = onDismiss, gesturesEnabled = viewer == null, dismissOnBack = viewer == null) {
        SystemBackHandler(enabled = viewer != null, onBack = onCloseViewer)
        if (viewer != null) {
            Box(Modifier.fillMaxSize()) { DocumentViewerScreen(viewer.document, viewer.file, onClose = onCloseViewer, embedded = true) }
            return@AdaptiveSheet
        }
        val scroll = rememberScrollState()
        ScrollbarBox(scroll) { Column(
            Modifier.verticalScroll(scroll).padding(horizontal = JSpacing.xl).padding(bottom = JSpacing.xl).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(JSpacing.l),
        ) {
            Header(model, onDismiss)
            androidx.compose.material3.OutlinedTextField(value = editedReference, onValueChange = { editedReference = it },
                label = { Text("Référence personnelle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = editedReference.trim().isNotEmpty() && editedReference.length <= 24 && editedReference.trim() != model.course.displayReference,
                onClick = { onRenameReference(editedReference) }) { Text("Enregistrer la référence") }
            // Le cours : notes puis documents (mêmes accès que la fiche de séance).
            Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                SheetSectionTitle("Le cours")
                model.notes?.let { CollapsibleDescription(it) }
                if (documents.isNotEmpty()) CourseDocumentsList(documents, onOpenDocument, onRedownload) else NoDocumentLine(model.course.source != null)
            }
            NearbyDocumentsSection(model.subjectLabel, nearby, downloadingId, onOpenNearby, onAttachNearby)
            notebookPart?.let { NotebookPartSection(it, notebookLoading, downloadingId, onDownloadAttachment, onLink) }
            Column(
                Modifier.fillMaxWidth().clip(JShapes.inner).background(JColors.Slate50)
                    .border(1.dp, JColors.Slate200, JShapes.inner).padding(JSpacing.l)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text("Cycle des révisions", style = MaterialTheme.typography.labelLarge, color = JColors.Slate600)
                Text(model.progressLine, style = MaterialTheme.typography.titleMedium.tabular(), color = JColors.Violet800)
                Spacer(Modifier.height(JSpacing.s))
                LinearProgressIndicator(
                    progress = { model.progressPercent / 100f },
                    color = JColors.Violet700,
                    trackColor = JColors.Violet100,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }
            if (onOpenNotebook != null) {
                OutlinedButton(onClick = onOpenNotebook, shape = JShapes.control, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(JIcons.Notebook, contentDescription = null, modifier = Modifier.size(18.dp), tint = JColors.Violet700)
                    Spacer(Modifier.width(JSpacing.s))
                    Text("Voir le cahier de textes de ${model.subjectLabel}")
                }
            }
            Text(
                "Calendrier personnalisé des J",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            // Une seule action mise en avant : la prochaine séance à valider ; les suivantes restent discrètes.
            val nextId = RevisionSession.nextOfCourse(model.course.id, model.timeline.map { it.revision })?.id
            val nextToValidate = model.timeline.indexOfFirst { it.revisionId == nextId }
            Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                model.timeline.forEachIndexed { index, row -> TimelineRow(row, highlighted = index == nextToValidate, onValidate) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = JColors.Rose700),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(JSpacing.s))
                    Text("Supprimer ce cours")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss, shape = JShapes.control, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fermer") }
            }
        } }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ce cours ?") },
            text = { Text("${model.deleteConfirm}\n\nToutes ses séances de révision seront supprimées.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = JColors.Rose700),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Supprimer", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
            },
            containerColor = Color.White,
        )
    }
}

@Composable
private fun Header(model: CourseDetailModel, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubjectDot(model.subject?.color)
                Spacer(Modifier.width(JSpacing.s))
                Text(model.subjectLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = JColors.Slate700)
            }
            Spacer(Modifier.height(JSpacing.s))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = JColors.Violet700, fontFeatureSettings = "tnum")) { append("C${model.course.courseNumber}") }
                    append(" · ")
                    append(model.course.title)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(JSpacing.xs))
            Text(model.chapterLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            Text(model.courseDateLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            Text(model.createdLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            model.averageLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JColors.Slate900, fontWeight = FontWeight.SemiBold) }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Fermer la fiche du cours")
        }
    }
}

@Composable
private fun TimelineRow(row: CourseDetailModel.Row, highlighted: Boolean, onValidate: (String) -> Unit) {
    val shape = JShapes.control
    val (background, border) = when (row.state) {
        CourseDetailModel.State.COMPLETED -> JColors.Slate50 to JColors.Slate200
        CourseDetailModel.State.OVERDUE -> JColors.Rose50 to JColors.Rose700.copy(alpha = 0.3f)
        CourseDetailModel.State.PENDING -> Color.White to JColors.Slate200
    }
    val (phaseBackground, phaseForeground) = phaseColors(row.phase)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(background).border(1.dp, border, shape).padding(start = JSpacing.m, end = JSpacing.s, top = JSpacing.s, bottom = JSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.stepLabel.substringBefore(" "),
                style = MaterialTheme.typography.labelLarge.tabular(),
                fontWeight = FontWeight.Bold,
                color = phaseForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 44.dp).clip(JShapes.badge).background(phaseBackground)
                    .padding(horizontal = JSpacing.s, vertical = JSpacing.xs)
                    .semantics { contentDescription = row.stepLabel },
            )
            Spacer(Modifier.width(JSpacing.m))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.phaseLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (row.emergencyBadge) {
                        Spacer(Modifier.width(JSpacing.s))
                        Pill("Rattrapage", JColors.Rose50, JColors.Rose700)
                    }
                }
                Text(row.plannedLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                row.doneLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JColors.Emerald700) }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (row.canValidate && highlighted) {
            Button(
                onClick = { onValidate(row.revisionId) },
                shape = JShapes.control,
                colors = ButtonDefaults.buttonColors(containerColor = JColors.Violet700, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = JSpacing.l),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Valider ${row.stepLabel}" },
            ) { Text(row.right, fontWeight = FontWeight.SemiBold) }
        } else if (row.canValidate) {
            TextButton(
                onClick = { onValidate(row.revisionId) },
                shape = JShapes.control,
                colors = ButtonDefaults.textButtonColors(contentColor = JColors.Violet800),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Valider ${row.stepLabel}" },
            ) { Text(row.right, fontWeight = FontWeight.SemiBold) }
        } else {
            Row(
                Modifier.clip(JShapes.badge).background(Color.White).border(1.dp, JColors.Slate200, JShapes.badge)
                    .padding(horizontal = JSpacing.s, vertical = JSpacing.xs)
                    .semantics(mergeDescendants = true) { contentDescription = "Faite, ${row.right}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JColors.Emerald700, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(JSpacing.xs))
                Text(row.right, style = MaterialTheme.typography.labelMedium.tabular(), fontWeight = FontWeight.Bold)
            }
        }
    }
}
