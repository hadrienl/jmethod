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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.today.phaseColors
import fr.jmethod.app.ui.today.subjectColor

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
) {
    var confirmDelete by rememberSaveable(model.course.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(model, onDismiss)
            model.notes?.let { notes ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(JColors.Slate50)
                        .border(1.dp, JColors.Slate200, RoundedCornerShape(12.dp)).padding(12.dp),
                ) {
                    Text("Notes de cours", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = JColors.Slate600)
                    Spacer(Modifier.height(4.dp))
                    Text(notes, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(JColors.Slate50)
                    .border(1.dp, JColors.Slate200, RoundedCornerShape(12.dp)).padding(16.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text("Cycle des révisions", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(model.progressLine, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = JColors.Sky700)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { model.progressPercent / 100f },
                    color = JColors.Violet700,
                    trackColor = JColors.Slate200,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                )
            }
            if (onOpenNotebook != null) {
                OutlinedButton(onClick = onOpenNotebook, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Voir le cahier de textes de ${model.subjectLabel}")
                }
            }
            Text(
                "Calendrier personnalisé des J",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = JColors.Slate700,
                modifier = Modifier.semantics { heading() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                model.timeline.forEach { row -> TimelineRow(row, onValidate) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = JColors.Rose700),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Supprimer ce cours")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Fermer") }
            }
        }
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
                Box(Modifier.size(10.dp).clip(CircleShape).background(subjectColor(model.subject?.color ?: "#64748B")))
                Spacer(Modifier.width(8.dp))
                Text(model.subjectLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(model.chapterLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = JColors.Sky700, fontFamily = FontFamily.Monospace)) { append("C${model.course.courseNumber}") }
                    append(" ")
                    append(model.course.title)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
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
private fun TimelineRow(row: CourseDetailModel.Row, onValidate: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val (background, border) = when (row.state) {
        CourseDetailModel.State.COMPLETED -> JColors.Slate50 to JColors.Slate200
        CourseDetailModel.State.OVERDUE -> JColors.Rose50 to JColors.Rose700.copy(alpha = 0.3f)
        CourseDetailModel.State.PENDING -> Color.White to JColors.Slate200
    }
    val (phaseBackground, phaseForeground) = phaseColors(row.phase)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(background).border(1.dp, border, shape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.stepLabel.substringBefore(" "),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = phaseForeground,
                modifier = Modifier.widthIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(phaseBackground)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .semantics { contentDescription = row.stepLabel },
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.phaseLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (row.emergencyBadge) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Rattrapage",
                            style = MaterialTheme.typography.labelSmall,
                            color = JColors.Rose700,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(JColors.Rose50).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(row.plannedLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                row.doneLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JColors.Emerald700) }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (row.canValidate) {
            Button(
                onClick = { onValidate(row.revisionId) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JColors.Violet50, contentColor = JColors.Violet800),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Valider ${row.stepLabel}" },
            ) { Text(row.right, fontWeight = FontWeight.SemiBold) }
        } else {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, JColors.Slate300, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Faite, ${row.right}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JColors.Emerald700, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(row.right, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}
