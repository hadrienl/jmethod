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
import fr.jmethod.app.ui.today.SurfaceCard
import fr.jmethod.app.ui.today.screenPadding
import fr.jmethod.app.ui.today.subjectColor
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
    LazyColumn(contentPadding = screenPadding(contentPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "header") {
            Column {
                Text("Mes progrès", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text("Ta régularité, tes scores et ce qui reste à consolider.", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
            }
        }
        item(key = "metrics") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric("Taux de réalisation", "${model.completionRate} %", model.completionLine, JColors.Slate900, Modifier.weight(1f))
                    Metric(
                        "Score moyen",
                        if (model.overallAvgScore == null) "—" else "${model.overallAvgScore}/100",
                        model.balanceLabel,
                        JColors.Sky700,
                        Modifier.weight(1f),
                        spoken = if (model.overallAvgScore == null) "Score moyen : pas encore de note" else null,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(
                        "Régularité", model.streakLabel, model.streakLine, JColors.Orange800, Modifier.weight(1f),
                        spoken = "Régularité : ${model.streakDays} ${if (model.streakDays > 1) "jours consécutifs" else "jour"}",
                    )
                    Metric("Cours suivis", "${model.coursesCount}", model.subjectsLine, JColors.Indigo700, Modifier.weight(1f))
                }
            }
        }
        if (model.weakSubjects.isNotEmpty()) {
            item(key = "weak") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(JColors.Amber50)
                        .border(1.dp, JColors.Amber700.copy(alpha = 0.3f), RoundedCornerShape(16.dp)).padding(16.dp),
                ) {
                    Text("Matières à retravailler", fontWeight = FontWeight.Bold, color = JColors.Amber900, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Leur score moyen est sous l’équilibre (50/100). Les prochaines séances de ces matières sont déjà rapprochées pour t’aider.",
                        style = MaterialTheme.typography.bodySmall, color = JColors.Amber900,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        model.weakSubjects.forEach { row ->
                            Text(
                                "${row.subject.name} (${row.avgScore}/100)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = JColors.Amber900,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        item(key = "subjects-title") {
            Text("Par matière", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
        }
        if (model.subjects.isEmpty()) {
            item(key = "empty") {
                SurfaceCard {
                    Text(
                        "Tes progrès par matière apparaîtront ici dès que tu auras ajouté un cours.",
                        style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600, modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
        items(model.subjects, key = { it.subject.id }) { row -> SubjectBar(row) }
    }
}

@Composable
private fun Metric(title: String, value: String, line: String, color: Color, modifier: Modifier, spoken: String? = null) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, JColors.Slate200, RoundedCornerShape(16.dp)).padding(14.dp)
            .semantics(mergeDescendants = true) { if (spoken != null) contentDescription = "$spoken. $line" },
    ) {
        Text(title.uppercase(java.util.Locale.FRENCH), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = JColors.Slate600)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = color)
        Spacer(Modifier.height(2.dp))
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
        Column(Modifier.padding(16.dp).clearAndSetSemantics { contentDescription = spoken }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(row.subject.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${row.rate} %", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = JColors.Sky700)
            }
            Spacer(Modifier.height(2.dp))
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
            Spacer(Modifier.height(8.dp))
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
