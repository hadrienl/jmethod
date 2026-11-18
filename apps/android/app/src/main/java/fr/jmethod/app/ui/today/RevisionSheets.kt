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
import fr.jmethod.app.domain.RevisionDetailModel
import fr.jmethod.app.domain.RevisionSession
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.ui.documents.DocumentViewerScreen
import fr.jmethod.app.ui.documents.SheetDocument
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheetProperties
import fr.jmethod.app.domain.ScorePreview
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.SCORE_INITIAL
import fr.jmethod.app.model.SCORE_MAX
import fr.jmethod.app.model.SCORE_MIN
import fr.jmethod.app.model.SCORE_PRESETS
import fr.jmethod.app.model.SCORE_STEP
import fr.jmethod.app.model.Subject
import fr.jmethod.app.model.info
import fr.jmethod.app.ui.components.phaseColors
import fr.jmethod.app.ui.components.subjectColor
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import fr.jmethod.app.ui.components.Pill
import fr.jmethod.app.ui.components.SubjectDot
import fr.jmethod.app.model.Phase
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
        colors = SliderDefaults.colors(
            thumbColor = JColors.Violet700, activeTrackColor = JColors.Violet700, inactiveTrackColor = JColors.Violet100,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = description
            stateDescription = "$score sur 100"
        },
    )
}

@Composable
private fun SheetHeader(subject: Subject?, phase: Phase?, badge: String?, title: String, chapter: String, closeLabel: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubjectDot(subject?.color)
                Spacer(Modifier.width(JSpacing.s))
                Text(subject?.name ?: "Matière", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = JColors.Slate700)
            }
            Spacer(Modifier.height(JSpacing.s))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(JSpacing.xs))
            Text("Chapitre : $chapter", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            if (badge != null && phase != null) {
                Spacer(Modifier.height(JSpacing.s))
                val (background, foreground) = phaseColors(phase)
                Pill(badge, background, foreground)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = closeLabel)
        }
    }
}

/** Autoévaluation (« Faire le point »), port de `ScoreModal`, affichée dans la fiche de séance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScorePage(
    revision: Revision,
    course: Course,
    subject: Subject?,
    settings: AppSettings,
    submitting: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onSubmit: (Int) -> Unit,
) {
    var score by rememberSaveable(revision.id) { mutableIntStateOf(SCORE_INITIAL) }
    val preview = ScorePreview.of(score, settings.sensitivity, settings.emergencyThreshold)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = JSpacing.xl).padding(bottom = JSpacing.xl).navigationBarsPadding(),
    ) {
        SheetHeader(
            subject, revision.phase, "${revision.phase.info.label} · ${revision.stepLabel}", "C${course.courseNumber} · ${course.title}",
            course.chapter, "Fermer la fiche", onClose,
        )
        Spacer(Modifier.height(JSpacing.xl))
        Text("De quoi te souviens-tu sans regarder ton cours ?", style = MaterialTheme.typography.titleMedium)
        Text("Avec 50/100 on garde ton rythme actuel.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(JShapes.inner).background(JColors.Slate50)
                .border(1.dp, JColors.Slate200, JShapes.inner).padding(JSpacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$score", style = MaterialTheme.typography.displayMedium.tabular(), fontWeight = FontWeight.Black, color = bandColor(preview.scoreBand))
                Text(" / 100", style = MaterialTheme.typography.titleMedium, color = JColors.Slate500, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            ScoreSlider(score, { score = it }, "Ton niveau de mémorisation sur 100")
            // Cinq repères sur une ligne ; avec un grand texte, trois puis deux pour ne jamais couper un mot.
            val perRow = if (LocalDensity.current.fontScale > 1.15f) 3 else SCORE_PRESETS.size
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(JSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(JSpacing.xs),
                maxItemsInEachRow = perRow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SCORE_PRESETS.forEach { preset ->
                    val selected = score == preset.value
                    Column(
                        Modifier.weight(1f).heightIn(min = 56.dp).clip(JShapes.badge)
                            .background(if (selected) JColors.Violet100 else Color.White)
                            .border(if (selected) 2.dp else 1.dp, if (selected) JColors.Violet700 else JColors.Slate200, JShapes.badge)
                            .clickable(role = Role.RadioButton) { score = preset.value }
                            .semantics { this.selected = selected }
                            .padding(horizontal = JSpacing.xs, vertical = JSpacing.s),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(preset.label, style = MaterialTheme.typography.labelLarge.tabular(), fontWeight = FontWeight.Bold,
                            color = if (selected) JColors.Violet900 else JColors.Slate900)
                        Text(
                            preset.sub, style = MaterialTheme.typography.labelSmall, color = if (selected) JColors.Violet800 else JColors.Slate600,
                            textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelSmall.fontSize, stepSize = 0.5.sp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(JSpacing.l))
        val (background, foreground) = toneColors(preview.tone)
        Column(
            Modifier.fillMaxWidth().clip(JShapes.inner).background(background).padding(JSpacing.l)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(preview.title, color = foreground, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(preview.description, color = foreground, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(JSpacing.xl))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("Voir le cours") }
            Spacer(Modifier.width(JSpacing.m))
            Button(
                onClick = { onSubmit(score) },
                enabled = !submitting,
                shape = JShapes.control,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Valider la révision", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Fiche de séance (« Réviser », section 8 de `notebook-to-course.md`) : une feuille avec sa propre
 * navigation (fiche, document, autoévaluation, résultat et séance suivante). Ouverte depuis
 * Aujourd’hui, le Planning et la fiche d’un cours ; détail porté de `RevisionDetailModal`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    state: SessionSheetState,
    model: RevisionDetailModel,
    settings: AppSettings,
    today: String,
    documents: List<Pair<CourseDocument, Boolean>>,
    notebookPart: RevisionSession.NotebookPart?,
    notebookLoading: Boolean,
    downloadingId: String?,
    nextLabel: String?,
    remainingLabel: String?,
    viewer: SheetDocument?,
    nearby: List<RevisionSession.Nearby>,
    onOpenNearby: (RevisionSession.Nearby) -> Unit,
    onAttachNearby: (RevisionSession.Nearby) -> Unit,
    onOpenDocument: (CourseDocument) -> Unit,
    onCloseViewer: () -> Unit,
    onRedownload: ((CourseDocument) -> Unit)?,
    onDownloadAttachment: (NotebookAttachment) -> Unit,
    onLink: (String) -> Unit,
    onPage: (SessionPage) -> Unit,
    onSubmit: (Int) -> Unit,
    onNext: (String) -> Unit,
    onBackToToday: () -> Unit,
    onDismiss: () -> Unit,
) {
    val page = state.page
    val inner = viewer != null || page is SessionPage.Score
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        // Document ouvert : le zoom et le défilement ne doivent pas faire glisser la feuille.
        sheetGesturesEnabled = viewer == null,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !inner),
    ) {
        // Retour : document puis autoévaluation reviennent à la fiche.
        BackHandler(enabled = inner) {
            if (viewer != null) onCloseViewer() else onPage(SessionPage.Main)
        }
        when {
            viewer != null -> Box(Modifier.fillMaxSize()) {
                DocumentViewerScreen(viewer.document, viewer.file, onClose = onCloseViewer, embedded = true)
            }
            page is SessionPage.Score -> ScorePage(
                model.revision, model.course, model.subject, settings, state.submitting,
                onBack = { onPage(SessionPage.Main) }, onClose = onDismiss, onSubmit = onSubmit,
            )
            page is SessionPage.Result -> ResultPage(page, nextLabel, remainingLabel, onNext = { onNext(page.nextRevisionId) }, onBackToToday = onBackToToday, onClose = onDismiss)
            else -> MainPage(
                model, today, documents, notebookPart, notebookLoading, downloadingId,
                onOpenDocument, onRedownload, onDownloadAttachment, onLink, nearby, onOpenNearby, onAttachNearby,
                onScore = { onPage(SessionPage.Score) }, onClose = onDismiss,
            )
        }
    }
}

@Composable
private fun MainPage(
    model: RevisionDetailModel,
    today: String,
    documents: List<Pair<CourseDocument, Boolean>>,
    notebookPart: RevisionSession.NotebookPart?,
    notebookLoading: Boolean,
    downloadingId: String?,
    onOpenDocument: (CourseDocument) -> Unit,
    onRedownload: ((CourseDocument) -> Unit)?,
    onDownloadAttachment: (NotebookAttachment) -> Unit,
    onLink: (String) -> Unit,
    nearby: List<RevisionSession.Nearby>,
    onOpenNearby: (RevisionSession.Nearby) -> Unit,
    onAttachNearby: (RevisionSession.Nearby) -> Unit,
    onScore: () -> Unit,
    onClose: () -> Unit,
) {
    val revision = model.revision
    val course = model.course
    var historyOpen by rememberSaveable(revision.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = JSpacing.xl).padding(bottom = JSpacing.l),
            verticalArrangement = Arrangement.spacedBy(JSpacing.l),
        ) {
            // 1. En-tête : matière, Jn, étape, titre, chapitre · Cn, retard.
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SubjectDot(model.subject.color)
                        Spacer(Modifier.width(JSpacing.s))
                        Text(model.subjectName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = JColors.Slate700)
                    }
                    Spacer(Modifier.height(JSpacing.s))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s), verticalArrangement = Arrangement.spacedBy(JSpacing.xs)) {
                        val (phaseBackground, phaseForeground) = phaseColors(revision.phase)
                        Pill("${model.stepLabel} · ${model.phaseLabel}", phaseBackground, phaseForeground)
                        RevisionSession.lateLabel(revision, today)?.let { Pill(it, JColors.Rose50, JColors.Rose700) }
                    }
                    Spacer(Modifier.height(JSpacing.s))
                    Text(course.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(JSpacing.xs))
                    Text("${course.chapter} · C${course.courseNumber}", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                    Text(model.plannedLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Fermer la fiche de la séance")
                }
            }
            // 2. Le cours : description puis documents.
            Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                SheetSectionTitle("Le cours")
                model.description?.let { CollapsibleDescription(it) }
                if (documents.isNotEmpty()) {
                    CourseDocumentsList(documents, onOpenDocument, onRedownload)
                } else {
                    NoDocumentLine(fromNotebook = course.source != null)
                }
            }
            // Documents proches de la même matière (section 8.5).
            NearbyDocumentsSection(model.subjectName, nearby, downloadingId, onOpenNearby, onAttachNearby)
            // 3. Dans le cahier de textes.
            notebookPart?.let { NotebookPartSection(it, notebookLoading, downloadingId, onDownloadAttachment, onLink) }
            // 4. Séances précédentes (repliable).
            Column(Modifier.fillMaxWidth().clip(JShapes.inner).border(1.dp, JColors.Slate200, JShapes.inner)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { historyOpen = !historyOpen }
                        .semantics { stateDescription = if (historyOpen) "Ouvert" else "Fermé" }
                        .padding(horizontal = JSpacing.l, vertical = JSpacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(model.historyTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(if (historyOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                if (historyOpen) {
                    Column(Modifier.padding(horizontal = JSpacing.l).padding(bottom = JSpacing.m)) {
                        if (model.historyEmpty != null) {
                            Text(model.historyEmpty, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
                        }
                        model.history.forEach { past ->
                            Row(Modifier.fillMaxWidth().padding(vertical = JSpacing.s).semantics(mergeDescendants = true) {}) {
                                Text(past.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(past.detail, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
                            }
                        }
                    }
                }
            }
        }
        // 5. Action principale toujours visible.
        HorizontalDivider(color = JColors.Slate100)
        Box(Modifier.fillMaxWidth().padding(horizontal = JSpacing.xl, vertical = JSpacing.m).navigationBarsPadding()) {
            if (model.completedLine != null) {
                Row(
                    Modifier.fillMaxWidth().clip(JShapes.inner).background(JColors.Emerald50).padding(JSpacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JColors.Emerald800)
                    Spacer(Modifier.width(8.dp))
                    Text(model.completedLine, color = JColors.Emerald950)
                }
            } else {
                Button(
                    onClick = onScore,
                    shape = JShapes.control,
                    colors = ButtonDefaults.buttonColors(containerColor = JColors.Violet700, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = JSpacing.l, vertical = JSpacing.m),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Faire le point", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Résultat de la validation et enchaînement (section 8.2). */
@Composable
private fun ResultPage(
    result: SessionPage.Result,
    nextLabel: String?,
    remainingLabel: String?,
    onNext: () -> Unit,
    onBackToToday: () -> Unit,
    onClose: () -> Unit,
) {
    val (background, foreground) = if (result.warning) JColors.Amber50 to JColors.Amber900 else JColors.Emerald50 to JColors.Emerald800
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = JSpacing.xl).padding(bottom = JSpacing.xl).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(JSpacing.l),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
                    Icon(if (result.warning) Icons.Filled.Warning else Icons.Filled.CheckCircle, contentDescription = null, tint = foreground)
                }
                Spacer(Modifier.height(JSpacing.m))
                Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(JSpacing.xs))
                Text(result.message, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Fermer la fiche")
            }
        }
        remainingLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600) }
        Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
            if (nextLabel != null) {
                Button(
                    onClick = onNext,
                    shape = JShapes.control,
                    colors = ButtonDefaults.buttonColors(containerColor = JColors.Violet700, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = JSpacing.l, vertical = JSpacing.m),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(nextLabel, fontWeight = FontWeight.Bold, maxLines = 3, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(JSpacing.s))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            OutlinedButton(onClick = onBackToToday, shape = JShapes.control, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Revenir à Aujourd’hui")
            }
        }
    }
}
