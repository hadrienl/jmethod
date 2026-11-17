package fr.jmethod.app.ui.today

import androidx.compose.foundation.BorderStroke
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
import fr.jmethod.app.ui.theme.JColors
import java.util.Locale

private val CardShape = RoundedCornerShape(24.dp)

/** Couleurs des étapes dans Aujourd’hui (classes `bg-*-50 text-*-800` du web). */
internal fun phaseColors(phase: Phase): Pair<Color, Color> = when (phase) {
    Phase.ENCODAGE -> JColors.Blue50 to JColors.Blue800
    Phase.CONSOLIDATION -> JColors.Orange50 to JColors.Orange800
    Phase.RECUPERATION -> JColors.Emerald50 to JColors.Emerald800
}

internal fun subjectColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(JColors.Slate500)

@Composable
private fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, JColors.Slate200),
    ) { content() }
}

@Composable
fun TodayScreen(
    state: TodayUiState,
    contentPadding: PaddingValues,
    onScore: (String) -> Unit,
    onDetail: (String) -> Unit,
) {
    val model = state.model
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (model.allDoneBanner) item(key = "done") { AllDoneBanner() }
        item(key = "header") { Header(model) }
        item(key = "hero") { Hero(model, onScore) }
        item(key = "progress") { Progress(model) }
        item(key = "phases") { PhaseLegend() }
        item(key = "programme") { ProgrammeHeader(model) }
        if (model.overdueBanner != null) {
            item(key = "overdue") {
                Text(
                    model.overdueBanner,
                    color = JColors.Amber900,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(JColors.Amber50).padding(16.dp),
                )
            }
        }
        if (model.emptyProgramme) {
            item(key = "empty") { EmptyProgramme() }
        } else {
            items(model.groups, key = { "group-" + it.subject.id }) { group -> SubjectGroup(group, onScore, onDetail) }
        }
        item(key = "tomorrow") { TomorrowClasses(model) }
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(JColors.Emerald50)
            .border(1.dp, JColors.Emerald200, RoundedCornerShape(16.dp)).padding(20.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text("Toutes tes révisions sont terminées !", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = JColors.Emerald950)
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
private fun Hero(model: TodayModel, onScore: (String) -> Unit) {
    val hero = model.hero
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(JColors.Violet700).padding(24.dp),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("POUR DEMAIN", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            hero.title,
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
        } else {
            Text("Tes révisions pour demain sont à jour. Bonne soirée !", color = JColors.Violet100, style = MaterialTheme.typography.bodyMedium)
        }
        val revision = hero.revision
        if (revision != null) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onScore(revision.id) },
                colors = ButtonDefaults.buttonColors(containerColor = JColors.Lime200, contentColor = JColors.Violet950),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Faire le point sur ce cours", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Progress(model: TodayModel) {
    SurfaceCard {
        Column(Modifier.padding(20.dp)) {
            Text("Tes petits progrès", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                Text("${model.completed.size}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(8.dp))
                Text(model.progressSuffix, style = MaterialTheme.typography.bodyLarge, color = JColors.Slate600, modifier = Modifier.padding(bottom = 6.dp))
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
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(background).clearAndSetSemantics {},
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
        Text("Dans ton programme", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(8.dp))
        Text(model.dueLabel, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate500, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun EmptyProgramme() {
    SurfaceCard {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JColors.Emerald700, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text("Tout est à jour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Aucune séance à préparer pour demain.", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SubjectGroup(group: TodayModel.Group, onScore: (String) -> Unit, onDetail: (String) -> Unit) {
    val color = subjectColor(group.subject.color)
    SurfaceCard {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(20.dp).semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.subject.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        Modifier.fillMaxWidth().background(if (row.done) JColors.Slate50 else Color.White).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.badge,
                color = foreground,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (row.doneLabel != null) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = JColors.Emerald800, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(row.doneLabel, color = JColors.Emerald800, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Voir le détail de la séance", role = Role.Button) { onDetail(row.revision.id) }
                .padding(vertical = 4.dp),
        ) {
            Text(row.courseLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate500)
            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        if (row.canValidate) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onScore(row.revision.id) },
                colors = ButtonDefaults.buttonColors(containerColor = JColors.Violet50, contentColor = JColors.Violet800),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
private fun TomorrowClasses(model: TodayModel) {
    SurfaceCard {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Icon(Icons.Filled.DateRange, contentDescription = null, tint = JColors.Violet700)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Ta journée de cours de demain", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                    Text("${model.tomorrowDate} · Depuis Pronote", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (model.tomorrowEvents.isEmpty()) {
                Text("Aucun cours importé pour demain.", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    model.tomorrowEvents.forEach { event -> TomorrowEventCard(event) }
                }
            }
        }
    }
}

@Composable
private fun TomorrowEventCard(event: TodayModel.TomorrowEvent) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (event.cancelled) JColors.Slate50 else Color.White)
            .border(1.dp, if (event.cancelled) JColors.Slate300 else JColors.Slate200, shape)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row {
            Text(
                event.timeRange.removeSuffix(" – "),
                style = MaterialTheme.typography.bodySmall,
                color = JColors.Slate600,
                modifier = Modifier.weight(1f),
            )
            if (event.trailing != null) {
                Text(
                    event.trailing,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (event.cancelled) JColors.Rose800 else JColors.Slate600,
                    fontWeight = if (event.cancelled) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(event.subjectName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (event.teacher != null) {
            Spacer(Modifier.height(4.dp))
            Text(event.teacher, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
        }
    }
}
