package fr.jmethod.app.ui.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import fr.jmethod.app.domain.RevisionSession
import fr.jmethod.app.ui.theme.JIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.jmethod.app.domain.TodayModel
import fr.jmethod.app.model.PHASE_CONFIG
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.ui.components.SurfaceCard
import fr.jmethod.app.ui.components.phaseColors
import fr.jmethod.app.ui.components.screenPadding
import fr.jmethod.app.ui.components.subjectColor
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import fr.jmethod.app.ui.components.SubjectDot
import fr.jmethod.app.ui.components.EmptyState
import fr.jmethod.app.ui.components.Pill
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextDecoration
import java.util.Locale

private val CardShape = JShapes.card

@Composable
fun TodayScreen(
    state: TodayUiState,
    contentPadding: PaddingValues,
    onScore: (String) -> Unit,
    onDetail: (String) -> Unit,
    onAddCourse: (PronoteEvent) -> Unit,
    /** « Revoir le cours » : fiche de la séance ouverte sur le document principal. */
    onReview: (String) -> Unit = {},
    /** « Voir le cours » d’un créneau de demain déjà importé. */
    onViewCourse: (String) -> Unit = {},
) {
    val model = state.model
    LazyColumn(
        contentPadding = screenPadding(contentPadding, 24.dp),
        verticalArrangement = Arrangement.spacedBy(JSpacing.l),
    ) {
        if (model.allDoneBanner) item(key = "done") { AllDoneBanner() }
        item(key = "header") { Header(model) }
        val hasCourses = state.document.courses.isNotEmpty()
        item(key = "hero") { Hero(model, hasCourses, onScore, onReview) }
        if (hasCourses) item(key = "progress") { Progress(model) }
        item(key = "phases") { PhaseLegend() }
        item(key = "programme") { ProgrammeHeader(model) }
        if (model.overdueBanner != null) {
            item(key = "overdue") {
                Text(
                    model.overdueBanner,
                    color = JColors.Amber900,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(JShapes.inner).background(JColors.Amber50).padding(JSpacing.l),
                )
            }
        }
        if (model.emptyProgramme) {
            item(key = "empty") { EmptyProgramme(hasCourses) }
        } else {
            items(model.groups, key = { "group-" + it.subject.id }) { group -> SubjectGroup(group, onScore, onDetail) }
        }
        item(key = "tomorrow") {
            TomorrowClasses(model, onViewCourse) { id -> state.document.pronoteEvents.firstOrNull { it.id == id }?.let(onAddCourse) }
        }
        item(key = "footer") {
            Text(
                "Pas besoin de tout apprendre en un jour. Juste un peu, chaque jour.",
                style = MaterialTheme.typography.bodyMedium,
                color = JColors.Slate500,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AllDoneBanner() {
    Column(
        Modifier.fillMaxWidth().clip(JShapes.inner).background(JColors.Emerald50)
            .border(1.dp, JColors.Emerald200, JShapes.inner).padding(JSpacing.l)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text("Toutes tes révisions sont terminées !", style = MaterialTheme.typography.titleMedium, color = JColors.Emerald950)
        Spacer(Modifier.height(8.dp))
        Text("Tes révisions pour demain sont prêtes. Bonne soirée !", style = MaterialTheme.typography.bodyMedium, color = JColors.Emerald950)
    }
}

@Composable
private fun Header(model: TodayModel) {
    Column {
        Text(
            model.headerDate.uppercase(Locale.FRENCH),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = JColors.Slate500,
            modifier = Modifier.semantics { contentDescription = model.headerDate },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            buildAnnotatedString {
                append("Un petit pas, ")
                withStyle(SpanStyle(color = JColors.Violet700)) { append("chaque jour.") }
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text("Ce soir, prépare tes révisions pour demain.", style = MaterialTheme.typography.bodyLarge, color = JColors.Slate600)
    }
}

@Composable
private fun Hero(model: TodayModel, hasCourses: Boolean, onScore: (String) -> Unit, onReview: (String) -> Unit) {
    val hero = model.hero
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(JColors.Violet700).padding(24.dp),
    ) {
        Row(
            Modifier.clip(JShapes.pill).background(Color.White.copy(alpha = 0.15f)).padding(horizontal = JSpacing.m, vertical = JSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("POUR DEMAIN", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(JSpacing.l))
        Text(
            if (hasCourses) hero.title else "Rien à préparer ce soir.",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        if (hero.course != null) {
            Text(
                buildAnnotatedString {
                    append("Commence par ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(hero.subjectLabel.orEmpty()) }
                    append(".\n")
                    append(hero.courseTitle.orEmpty())
                },
                color = JColors.Violet100,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (hasCourses) {
            Text("Tes révisions pour demain sont à jour. Bonne soirée !", color = JColors.Violet100, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "Aucun cours n’est enregistré sur ce téléphone. Ajoute ton premier cours depuis ton emploi du temps de demain, juste en dessous, ou depuis Mes cours.",
                color = JColors.Violet100, style = MaterialTheme.typography.bodyMedium,
            )
        }
        val revision = hero.revision
        if (revision != null) {
            Spacer(Modifier.height(JSpacing.l))
            Button(
                onClick = { onScore(revision.id) },
                colors = ButtonDefaults.buttonColors(containerColor = JColors.Lime200, contentColor = JColors.Violet950),
                shape = JShapes.control,
                contentPadding = PaddingValues(horizontal = JSpacing.l, vertical = JSpacing.m),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Faire le point sur ce cours", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (RevisionSession.hasDocuments(hero.course)) {
                Spacer(Modifier.height(JSpacing.s))
                OutlinedButton(
                    onClick = { onReview(revision.id) },
                    shape = JShapes.control,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = JSpacing.l, vertical = JSpacing.m),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(JIcons.Book, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Revoir le cours", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Progress(model: TodayModel) {
    SurfaceCard {
        Column(Modifier.padding(JSpacing.l)) {
            Text("Tes petits progrès", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                Text("${model.completed.size}", style = MaterialTheme.typography.displaySmall.tabular(), fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(8.dp))
                Text(model.progressSuffix, style = MaterialTheme.typography.bodyLarge, color = JColors.Slate600, modifier = Modifier.padding(bottom = JSpacing.s))
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { model.progress / 100f },
                color = JColors.Violet700,
                trackColor = JColors.Violet100,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(12.dp).semantics {
                    contentDescription = "Séances terminées aujourd’hui : ${model.progress} %"
                },
            )
            Spacer(Modifier.height(16.dp))
            Text(model.progressMessage, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
        }
    }
}

@Composable
private fun PhaseLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics { contentDescription = "Comment réviser" }) {
        for (phase in Phase.entries) {
            val info = PHASE_CONFIG.getValue(phase)
            val (background, foreground) = phaseColors(phase)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Box(
                    Modifier.size(40.dp).clip(JShapes.control).background(background).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) { Text(info.symbol, color = foreground, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(info.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(info.hint, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                }
            }
        }
    }
}

@Composable
private fun ProgrammeHeader(model: TodayModel) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
        Text("Dans ton programme", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(8.dp))
        Text(model.dueLabel, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate500, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun EmptyProgramme(hasCourses: Boolean) {
    SurfaceCard {
        EmptyState(
            icon = Icons.Filled.CheckCircle,
            tint = JColors.Emerald700,
            title = if (hasCourses) "Tout est à jour" else "Ton programme est vide",
            message = if (hasCourses) {
                "Aucune séance à faire pour l’instant. Tu peux ajouter un cours depuis ton emploi du temps ci-dessous."
            } else {
                "Ajoute un cours depuis ton emploi du temps ci-dessous : ses séances apparaîtront ici."
            },
        )
    }
}

@Composable
private fun SubjectGroup(group: TodayModel.Group, onScore: (String) -> Unit, onDetail: (String) -> Unit) {
    val color = subjectColor(group.subject.color)
    SurfaceCard {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(JSpacing.l).semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SubjectDot(group.subject.color, 12.dp)
                Spacer(Modifier.width(JSpacing.m))
                Column(Modifier.weight(1f)) {
                    Text(group.subject.name, style = MaterialTheme.typography.titleMedium)
                    Text(group.countLabel, style = MaterialTheme.typography.bodySmall, color = JColors.Slate500)
                }
            }
            group.rows.forEach { row ->
                HorizontalDivider(color = JColors.Slate100)
                RevisionRow(row, onScore, onDetail)
            }
        }
    }
}

@Composable
private fun RevisionRow(row: TodayModel.Row, onScore: (String) -> Unit, onDetail: (String) -> Unit) {
    val (background, foreground) = phaseColors(row.phase)
    Column(
        Modifier.fillMaxWidth().background(if (row.done) JColors.Slate50 else Color.White).padding(horizontal = JSpacing.l, vertical = JSpacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(row.badge, background, foreground)
            Spacer(Modifier.weight(1f))
            if (row.doneLabel != null) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = JColors.Emerald800, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(row.doneLabel, color = JColors.Emerald800, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(JShapes.badge)
                .clickable(onClickLabel = "Ouvrir la fiche de la séance", role = Role.Button) { onDetail(row.revision.id) }
                .padding(vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.courseLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate500, modifier = Modifier.weight(1f, fill = false))
                if (row.hasDocuments) {
                    Spacer(Modifier.width(JSpacing.s))
                    Icon(
                        JIcons.Attachment, contentDescription = "Documents du cours", tint = JColors.Violet700,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        if (row.canValidate) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onScore(row.revision.id) },
                colors = ButtonDefaults.buttonColors(containerColor = JColors.Violet50, contentColor = JColors.Violet800),
                shape = JShapes.control,
                contentPadding = PaddingValues(horizontal = JSpacing.l, vertical = JSpacing.m),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Faire le point : ${row.title}"
                },
            ) {
                Text("Faire le point", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TomorrowClasses(model: TodayModel, onViewCourse: (String) -> Unit, onAddCourse: (String) -> Unit) {
    SurfaceCard {
        Column(Modifier.padding(vertical = JSpacing.l)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = JSpacing.l).semantics(mergeDescendants = true) {},
            ) {
                Box(Modifier.size(40.dp).clip(JShapes.control).background(JColors.Violet50), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, tint = JColors.Violet700, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(JSpacing.m))
                Column {
                    Text("Ta journée de cours de demain", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text("${model.tomorrowDate} · Depuis Pronote", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
                }
            }
            Spacer(Modifier.height(JSpacing.s))
            if (model.tomorrowEvents.isEmpty()) {
                Text(
                    "Aucun cours importé pour demain.",
                    style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
                    modifier = Modifier.padding(horizontal = JSpacing.l, vertical = JSpacing.s),
                )
            } else {
                model.tomorrowEvents.forEachIndexed { index, event ->
                    if (index > 0) HorizontalDivider(color = JColors.Slate100, modifier = Modifier.padding(start = 76.dp))
                    TomorrowEventRow(event, onViewCourse, onAddCourse)
                }
            }
        }
    }
}

/** Un créneau de demain : heures en colonne, matière, professeur et salle ; « Ajouter ce cours » si possible. */
@Composable
private fun TomorrowEventRow(event: TodayModel.TomorrowEvent, onViewCourse: (String) -> Unit, onAddCourse: (String) -> Unit) {
    val start = event.timeRange.substringBefore(" – ")
    val end = event.timeRange.substringAfter(" – ", "")
    val muted = event.cancelled
    Row(Modifier.fillMaxWidth().padding(start = JSpacing.l, end = JSpacing.s, top = JSpacing.m, bottom = if (event.canAddCourse || event.courseId != null) 0.dp else JSpacing.m)) {
        Column(Modifier.widthIn(min = 48.dp).clearAndSetSemantics {}) {
            Text(start, style = MaterialTheme.typography.labelLarge.tabular(), color = if (muted) JColors.Slate500 else JColors.Slate900, softWrap = false)
            if (end.isNotEmpty()) Text(end, style = MaterialTheme.typography.labelMedium.tabular(), color = JColors.Slate500, softWrap = false)
        }
        Spacer(Modifier.width(JSpacing.m))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = JSpacing.s).semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(event.timeRange.removeSuffix(" – "), event.subjectName, event.teacher, event.trailing).joinToString(", ")
                },
            ) {
                Text(
                    event.subjectName,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (muted) JColors.Slate500 else JColors.Slate900,
                    textDecoration = if (muted) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
                if (event.trailing != null) {
                    Spacer(Modifier.width(JSpacing.s))
                    if (muted) {
                        Pill(event.trailing, JColors.Rose50, JColors.Rose800)
                    } else {
                        Text(event.trailing, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                    }
                }
            }
            if (event.teacher != null) {
                Text(event.teacher, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            }
            val courseId = event.courseId
            if (courseId != null) {
                ViewCourseButton(event.subjectName) { onViewCourse(courseId) }
            } else if (event.canAddCourse) {
                AddCourseButton(event.subjectName) { onAddCourse(event.id) }
            }
        }
    }
}

/** « Voir le cours » : créneau dont le cours existe déjà (séance du cahier importée). */
@Composable
private fun ViewCourseButton(subjectName: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        shape = JShapes.control,
        contentPadding = PaddingValues(horizontal = JSpacing.s, vertical = JSpacing.s),
        modifier = Modifier.offset(x = (-8).dp).heightIn(min = 48.dp).semantics { contentDescription = "Voir le cours : $subjectName" },
    ) {
        Icon(JIcons.Book, contentDescription = null, tint = JColors.Violet800, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(JSpacing.s))
        Text("Voir le cours", color = JColors.Violet800, fontWeight = FontWeight.Bold)
    }
}

/** « Ajouter ce cours » : ouvre la création préremplie depuis un créneau Pronote maintenu. */
@Composable
internal fun AddCourseButton(subjectName: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        shape = JShapes.control,
        contentPadding = PaddingValues(horizontal = JSpacing.s, vertical = JSpacing.s),
        modifier = modifier.offset(x = (-8).dp).heightIn(min = 48.dp).semantics { contentDescription = "Ajouter ce cours : $subjectName" },
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = JColors.Violet800, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(JSpacing.s))
        Text("Ajouter ce cours", color = JColors.Violet800, fontWeight = FontWeight.Bold)
    }
}
