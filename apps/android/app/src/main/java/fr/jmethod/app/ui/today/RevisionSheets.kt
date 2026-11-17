package fr.jmethod.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.ScorePreview
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.SCORE_INITIAL
import fr.jmethod.app.model.SCORE_MAX
import fr.jmethod.app.model.SCORE_MIN
import fr.jmethod.app.model.SCORE_PRESETS
import fr.jmethod.app.model.SCORE_STEP
import fr.jmethod.app.model.Subject
import fr.jmethod.app.model.info
import fr.jmethod.app.ui.theme.JColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private fun bandColor(band: ScorePreview.Band) = when (band) {
    ScorePreview.Band.ROSE -> JColors.Rose700
    ScorePreview.Band.AMBER -> JColors.Amber700
    ScorePreview.Band.SKY -> JColors.Sky700
    ScorePreview.Band.EMERALD -> JColors.Emerald700
    ScorePreview.Band.INDIGO -> JColors.Indigo700
}

private fun toneColors(tone: ScorePreview.Tone): Pair<Color, Color> = when (tone) {
    ScorePreview.Tone.EMERGENCY -> JColors.Rose50 to JColors.Rose700
    ScorePreview.Tone.SHORTENED -> JColors.Amber50 to JColors.Amber700
    ScorePreview.Tone.EXPANDED -> JColors.Emerald50 to JColors.Emerald700
    ScorePreview.Tone.EXACT -> JColors.Sky50 to JColors.Sky700
}

/** Curseur 0–100 par pas de 5 (20 intervalles). */
@Composable
private fun ScoreSlider(score: Int, onChange: (Int) -> Unit, description: String) {
    Slider(
        value = score.toFloat(),
        onValueChange = { onChange(((it / SCORE_STEP).roundToInt() * SCORE_STEP).coerceIn(SCORE_MIN, SCORE_MAX)) },
        valueRange = SCORE_MIN.toFloat()..SCORE_MAX.toFloat(),
        steps = (SCORE_MAX - SCORE_MIN) / SCORE_STEP - 1,
        colors = SliderDefaults.colors(thumbColor = JColors.Violet700, activeTrackColor = JColors.Violet700, inactiveTrackColor = JColors.Violet100),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = description
            stateDescription = "$score sur 100"
        },
    )
}

@Composable
private fun SheetHeader(subject: Subject?, badge: String?, title: String, chapter: String, closeLabel: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(subjectColor(subject?.color ?: "#64748B")))
                Spacer(Modifier.width(8.dp))
                Text(subject?.name ?: "Matière", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (badge != null) {
                Spacer(Modifier.height(8.dp))
                Text(badge, style = MaterialTheme.typography.labelMedium, color = JColors.Slate700)
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text("Chapitre : $chapter", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
        }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = closeLabel)
        }
    }
}

/** Autoévaluation rapide (« Faire le point »), port de `ScoreModal`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreSheet(
    revision: Revision,
    course: Course,
    subject: Subject?,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSubmit: (Int) -> Unit,
) {
    var score by rememberSaveable(revision.id) { mutableIntStateOf(SCORE_INITIAL) }
    val preview = ScorePreview.of(score, settings.sensitivity, settings.emergencyThreshold)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        ) {
            SheetHeader(
                subject, "${revision.phase.info.label} · ${revision.stepLabel}", "C${course.courseNumber} : ${course.title}",
                course.chapter, "Fermer l’autoévaluation", onDismiss,
            )
            Spacer(Modifier.height(20.dp))
            Text("De quoi te souviens-tu sans regarder ton cours ?", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("Avec 50/100 on garde ton rythme actuel.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(JColors.Slate50)
                    .border(1.dp, JColors.Slate200, RoundedCornerShape(12.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$score", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = bandColor(preview.scoreBand))
                    Text(" / 100", style = MaterialTheme.typography.titleMedium, color = JColors.Slate500, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                ScoreSlider(score, { score = it }, "Ton niveau de mémorisation sur 100")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    SCORE_PRESETS.forEach { preset ->
                        val selected = score == preset.value
                        Column(
                            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (selected) JColors.Violet100 else Color.White)
                                .border(1.dp, if (selected) JColors.Violet700 else JColors.Slate200, RoundedCornerShape(8.dp))
                                .clickable(role = Role.RadioButton) { score = preset.value }
                                .semantics { this.selected = selected }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(preset.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            Text(preset.sub, style = MaterialTheme.typography.labelSmall, color = JColors.Slate600, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val (background, foreground) = toneColors(preview.tone)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(background).padding(14.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(preview.title.uppercase(Locale.FRENCH), color = foreground, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { contentDescription = preview.title })
                Spacer(Modifier.height(4.dp))
                Text(preview.description, color = foreground, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onSubmit(score) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Valider la révision", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)
private val shortDate = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH)
private fun formatDate(value: String, formatter: DateTimeFormatter) = runCatching { LocalDate.parse(value).format(formatter) }.getOrDefault(value)

/** Détail d’une séance (Jn, étape, séances précédentes, validation), port de `RevisionDetailModal`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevisionDetailSheet(
    revision: Revision,
    course: Course,
    subject: Subject?,
    revisions: List<Revision>,
    onDismiss: () -> Unit,
    onValidate: (Int) -> Unit,
) {
    var score by rememberSaveable(revision.id) { mutableIntStateOf(SCORE_INITIAL) }
    var confirming by rememberSaveable(revision.id) { mutableStateOf(false) }
    var historyOpen by rememberSaveable(revision.id) { mutableStateOf(false) }
    val completed = revision.status == RevisionStatus.COMPLETED
    val history = revisions
        .filter {
            it.courseId == course.id && it.id != revision.id && it.status == RevisionStatus.COMPLETED &&
                (!completed || (it.completedDate ?: it.scheduledDate) <= (revision.completedDate ?: revision.scheduledDate))
        }
        .sortedWith { a, b ->
            val byDate = (b.completedDate ?: b.scheduledDate).compareTo(a.completedDate ?: a.scheduledDate)
            if (byDate != 0) byDate else b.stepIndex - a.stepIndex
        }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(subject, null, "C${course.courseNumber} · ${course.title}", course.chapter, "Fermer le détail de la séance", onDismiss)
            val (phaseBackground, phaseForeground) = phaseColors(revision.phase)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(JColors.Slate50)
                    .border(1.dp, JColors.Slate200, RoundedCornerShape(16.dp)).padding(16.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(revision.stepLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        revision.phase.info.label, color = phaseForeground, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(phaseBackground).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("Prévue le ${formatDate(revision.scheduledDate, longDate)}", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
            }
            course.description?.takeIf { it.isNotBlank() }?.let { description ->
                Column {
                    Text("À revoir", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(6.dp))
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, JColors.Slate200, RoundedCornerShape(16.dp))) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { historyOpen = !historyOpen }
                        .semantics { stateDescription = if (historyOpen) "Ouvert" else "Fermé" }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sessions précédentes (${history.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(if (historyOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                if (historyOpen) {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                        if (history.isEmpty()) {
                            Text("Aucune session terminée avant celle-ci.", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
                        }
                        history.forEach { past ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                                Text("${past.stepLabel} · ${past.phase.info.label}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(
                                    "${formatDate(past.completedDate ?: past.scheduledDate, shortDate)} · ${past.score?.let { "$it/100" } ?: "Sans note"}",
                                    style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
                                )
                            }
                        }
                    }
                }
            }
            if (completed) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(JColors.Emerald50).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JColors.Emerald800)
                    Spacer(Modifier.width(8.dp))
                    val date = revision.completedDate?.let { " le ${formatDate(it, shortDate)}" }.orEmpty()
                    Text("Séance terminée$date · ${revision.score ?: "—"}/100", color = JColors.Emerald950)
                }
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(JColors.Slate50).padding(16.dp)) {
                    Text("Ta mémorisation : $score/100", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ScoreSlider(score, { score = it; confirming = false }, "Ta mémorisation")
                    Text("50 conserve le rythme prévu. Ta note ajuste les prochaines révisions.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                }
                if (confirming) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(JColors.Violet50)
                            .border(1.dp, JColors.Violet200, RoundedCornerShape(16.dp)).padding(16.dp),
                    ) {
                        Text("Terminer cette séance avec $score/100 ?", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(6.dp))
                        Text("Elle sera enregistrée comme faite aujourd’hui et les prochaines dates seront ajustées.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { onValidate(score) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Confirmer la validation")
                            }
                            OutlinedButton(onClick = { confirming = false }, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Revenir")
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = { confirming = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Valider la séance")
                    }
                }
            }
        }
    }
}
