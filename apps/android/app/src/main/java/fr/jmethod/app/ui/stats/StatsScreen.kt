package fr.jmethod.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.data.todayTicks
import fr.jmethod.app.domain.StatsModel
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import fr.jmethod.app.ui.components.EmptyState
import fr.jmethod.app.ui.components.ScreenHeader
import fr.jmethod.app.ui.components.SectionTitle
import fr.jmethod.app.ui.components.SubjectDot
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import fr.jmethod.app.ui.components.SurfaceCard
import fr.jmethod.app.ui.components.screenPadding
import fr.jmethod.app.ui.components.subjectColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

class StatsViewModel(repository: AppRepository, clock: AppClock) : ViewModel() {
    val uiState: StateFlow<StatsModel?> = combine(repository.document.filterNotNull(), clock.todayTicks()) { document, today ->
        StatsModel.build(document.courses, document.revisions, document.subjects, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Mes progrès (`StatsView`) : quatre indicateurs, matières à travailler, barres par matière. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(model: StatsModel, contentPadding: PaddingValues) {
    LazyColumn(contentPadding = screenPadding(contentPadding), verticalArrangement = Arrangement.spacedBy(JSpacing.l)) {
        item(key = "header") {
            ScreenHeader("Mes progrès", "Ta régularité, tes scores et ce qui reste à consolider.")
        }
        item(key = "metrics") {
            // Deux rangées de deux indicateurs de même hauteur, même avec un grand texte.
            Column(verticalArrangement = Arrangement.spacedBy(JSpacing.m)) {
                Row(horizontalArrangement = Arrangement.spacedBy(JSpacing.m), modifier = Modifier.height(IntrinsicSize.Min)) {
                    Metric("Taux de réalisation", "${model.completionRate} %", model.completionLine, JColors.Slate900, Modifier.weight(1f))
                    Metric(
                        "Score moyen",
                        if (model.overallAvgScore == null) "—" else "${model.overallAvgScore}/100",
                        model.balanceLabel,
                        JColors.Slate900,
                        Modifier.weight(1f),
                        spoken = if (model.overallAvgScore == null) "Score moyen : pas encore de note" else null,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(JSpacing.m), modifier = Modifier.height(IntrinsicSize.Min)) {
                    Metric(
                        "Régularité", model.streakLabel, model.streakLine, JColors.Slate900, Modifier.weight(1f),
                        spoken = "Régularité : ${model.streakDays} ${if (model.streakDays > 1) "jours consécutifs" else "jour"}",
                    )
                    Metric("Cours suivis", "${model.coursesCount}", model.subjectsLine, JColors.Slate900, Modifier.weight(1f))
                }
            }
        }
        if (model.weakSubjects.isNotEmpty()) {
            item(key = "weak") {
                Column(
                    Modifier.fillMaxWidth().clip(JShapes.inner).background(JColors.Amber50)
                        .border(1.dp, JColors.Amber700.copy(alpha = 0.3f), JShapes.inner).padding(JSpacing.l),
                ) {
                    Text("Matières à retravailler", fontWeight = FontWeight.Bold, color = JColors.Amber900, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(JSpacing.xs))
                    Text(
                        "Leur score moyen est sous l’équilibre (50/100). Les prochaines séances de ces matières sont déjà rapprochées pour t’aider.",
                        style = MaterialTheme.typography.bodySmall, color = JColors.Amber900,
                    )
                    Spacer(Modifier.height(JSpacing.m))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s), verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                        model.weakSubjects.forEach { row ->
                            Text(
                                "${row.subject.name} (${row.avgScore}/100)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = JColors.Amber900,
                                modifier = Modifier.clip(JShapes.badge).background(Color.White).padding(horizontal = JSpacing.m, vertical = JSpacing.s),
                            )
                        }
                    }
                }
            }
        }
        item(key = "subjects-title") {
            SectionTitle("Par matière", Modifier.padding(top = JSpacing.s))
        }
        if (model.subjects.isEmpty()) {
            item(key = "empty") {
                SurfaceCard {
                    EmptyState(JIcons.Stats, "Pas encore de progrès à afficher", "Tes progrès par matière apparaîtront ici dès que tu auras ajouté un cours.")
                }
            }
        }
        items(model.subjects, key = { it.subject.id }) { row -> SubjectBar(row) }
    }
}

@Composable
private fun Metric(title: String, value: String, line: String, color: Color, modifier: Modifier, spoken: String? = null) {
    Column(
        modifier.fillMaxHeight().clip(JShapes.inner).background(Color.White).border(1.dp, JColors.Slate200, JShapes.inner).padding(JSpacing.l)
            .semantics(mergeDescendants = true) { if (spoken != null) contentDescription = "$spoken. $line" },
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = JColors.Slate600)
        Spacer(Modifier.height(JSpacing.s))
        Text(value, style = MaterialTheme.typography.headlineMedium.tabular(), fontWeight = FontWeight.ExtraBold, color = color)
        Spacer(Modifier.height(JSpacing.xs))
        Text(line, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
    }
}

@Composable
private fun SubjectBar(row: StatsModel.SubjectRow) {
    val color = subjectColor(row.subject.color)
    val spoken = buildString {
        append("${row.subject.name}, ${row.coursesCount} cours, ${row.completedRevs} séances faites sur ${row.totalRevs}, ${row.rate} %")
        row.avgScore?.let { append(", moyenne $it sur 100") }
    }
    SurfaceCard {
        Column(Modifier.padding(JSpacing.l).clearAndSetSemantics { contentDescription = spoken }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubjectDot(row.subject.color, 12.dp)
                Spacer(Modifier.width(JSpacing.m))
                Text(row.subject.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${row.rate} %", style = MaterialTheme.typography.titleSmall.tabular(), color = JColors.Violet800)
            }
            Spacer(Modifier.height(JSpacing.xs))
            Row {
                Text(row.line, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600, modifier = Modifier.weight(1f))
                if (row.avgScore != null) {
                    Text(
                        "Moyenne : ${row.avgScore}/100",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (row.averageTone == StatsModel.Tone.GOOD) JColors.Emerald800 else JColors.Amber900,
                    )
                }
            }
            Spacer(Modifier.height(JSpacing.m))
            LinearProgressIndicator(
                progress = { row.rate / 100f },
                color = color,
                trackColor = JColors.Slate100,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
        }
    }
}
