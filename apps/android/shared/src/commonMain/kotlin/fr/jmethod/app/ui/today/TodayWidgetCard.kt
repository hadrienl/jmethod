package fr.jmethod.app.ui.today

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.RevisionProgrammes
import fr.jmethod.app.domain.RevisionSession
import fr.jmethod.app.domain.TodayModel
import fr.jmethod.app.domain.TodayWidgetMode
import fr.jmethod.app.domain.TodayWidgetState
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.Subject
import fr.jmethod.app.resources.*
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import org.jetbrains.compose.resources.painterResource

private val art = mapOf("rest" to Res.drawable.widget_rest, "ready" to Res.drawable.widget_ready,
    "focus" to Res.drawable.widget_focus, "motion" to Res.drawable.widget_motion, "joy" to Res.drawable.widget_joy)

private fun sessions(count: Int) = "$count séance${if (count > 1) "s" else ""}"

@Composable
internal fun TodayWidgetCard(
    state: TodayWidgetState,
    courses: List<Course>,
    subjects: List<Subject>,
    offline: Boolean,
    refreshing: Boolean,
    onStart: (String) -> Unit,
    onReview: (String) -> Unit,
    onCourses: () -> Unit,
    onPrepare: () -> Unit,
    onShowProgramme: () -> Unit,
) {
    val mode = state.mode
    val title = when (mode) {
        TodayWidgetMode.REST -> "Soirée tranquille."
        TodayWidgetMode.READY -> "Un petit tour de révision ?"
        TodayWidgetMode.FOCUS -> "Une séance à la fois."
        TodayWidgetMode.PROGRESS -> "Tu avances !"
        TodayWidgetMode.DONE -> "C’est fait pour ce soir !"
        TodayWidgetMode.RETURN -> "Content de te retrouver !"
        TodayWidgetMode.RESUME -> "On reprend tranquillement ?"
        TodayWidgetMode.PLAN -> "Reprenons une séance à la fois."
        TodayWidgetMode.PLAN_ACTIVE -> if (state.completed > 0) "Tu as repris le rythme." else "Ton programme de reprise"
        TodayWidgetMode.PLAN_DONE -> "Ton programme de ce soir est terminé !"
        TodayWidgetMode.NEW_WORK -> "Nouvelles séances disponibles"
    }
    val message = when (mode) {
        TodayWidgetMode.REST -> "Aucun J prévu ce soir."
        TodayWidgetMode.RETURN -> if (state.remaining == 0) "Aucun J prévu ce soir." else "${sessions(state.remaining)} à faire ce soir."
        TodayWidgetMode.DONE -> "Toutes tes révisions sont terminées."
        TodayWidgetMode.RESUME -> "${sessions(state.overdue)} à reprendre."
        TodayWidgetMode.PLAN -> "Tu as ${sessions(state.overdue)} en attente. Choisis ton programme de ce soir."
        TodayWidgetMode.PLAN_ACTIVE, TodayWidgetMode.PROGRESS -> "${sessions(state.completed)} sur ${state.completed + state.remaining} terminées."
        TodayWidgetMode.PLAN_DONE -> "${sessions(state.outsidePlan)} à reprendre hors de ta sélection."
        else -> "${sessions(state.remaining)} à faire ce soir."
    }
    val waitingForData = refreshing && mode in setOf(TodayWidgetMode.REST, TodayWidgetMode.RETURN) && state.remaining == 0
    val next = state.next
    val course = courses.firstOrNull { it.id == next?.courseId }
    val action = when (mode) {
        TodayWidgetMode.PLAN -> "Préparer ma reprise" to onPrepare
        TodayWidgetMode.PLAN_DONE -> "Voir la suite" to onPrepare
        TodayWidgetMode.DONE -> "Voir mon programme" to onShowProgramme
        else -> if (next == null) "Voir mes cours" to onCourses else {
            val label = when (mode) {
                TodayWidgetMode.RESUME -> "Reprendre"
                TodayWidgetMode.PLAN_ACTIVE, TodayWidgetMode.PROGRESS -> "Continuer"
                else -> "Commencer"
            }
            label to { onStart(next.id) }
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(JShapes.card).background(JColors.Violet100).padding(JSpacing.l),
        verticalArrangement = Arrangement.spacedBy(JSpacing.m),
    ) {
        if (offline || refreshing) {
            Text(if (refreshing) "Actualisation de ton programme…" else "Hors ligne · Programme enregistré sur cet appareil",
                style = MaterialTheme.typography.labelMedium, color = JColors.Slate600)
        }
        BoxWithConstraints {
            val width = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f)
            Row(horizontalArrangement = Arrangement.spacedBy(JSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(JSpacing.xs)) {
                    Text(if (waitingForData) "Ton programme du soir" else title,
                        style = MaterialTheme.typography.headlineSmall, color = JColors.Violet950,
                        modifier = Modifier.semantics { heading() })
                    if (!waitingForData) Text(message, color = JColors.Slate700)
                }
                if (width >= 240.dp) {
                    Image(painterResource(art.getValue(mode.art)), contentDescription = null,
                        modifier = Modifier.size(if (width >= 520.dp) 128.dp else 72.dp))
                }
            }
        }
        if (state.removedFromPlan > 0) {
            Text("Séances retirées de ta sélection car elles ne sont plus disponibles : ${state.removedFromPlan}.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.completed > 0 && state.remaining > 0 && (mode == TodayWidgetMode.PROGRESS || mode == TodayWidgetMode.PLAN_ACTIVE)) {
            LinearProgressIndicator(progress = { state.completed.toFloat() / (state.completed + state.remaining) }, modifier = Modifier.fillMaxWidth())
        }
        if (next != null && course != null) {
            val subject = subjects.firstOrNull { it.id == course.subjectId }?.name
            Text(listOfNotNull(subject, next.stepLabel, RevisionProgrammes.references(next, courses)).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge)
            Text(course.title, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = action.second) { Text(action.first) }
        if (next != null && RevisionSession.hasDocuments(course)) {
            OutlinedButton(onClick = { onReview(next.id) }) { Text("Revoir le cours") }
        }
        if (mode == TodayWidgetMode.PLAN_ACTIVE) {
            TextButton(onClick = onPrepare) { Text("Modifier ma reprise") }
        }
    }
}

@Composable
internal fun ResumeDialog(
    model: TodayModel,
    courses: List<Course>,
    subjects: List<Subject>,
    current: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val available = model.due.mapTo(mutableSetOf()) { it.id }
    val overdue = model.overdue.mapTo(mutableSetOf()) { it.id }
    var selected by rememberSaveable {
        mutableStateOf(ArrayList(current.filter { it in available }.ifEmpty { TodayWidgetState.suggested(model.due, model.overdue) }))
    }
    val checked = selected.filter { it in available }
    val choose: (String) -> Unit = { id ->
        selected = ArrayList(if (id in checked) checked - id else checked + id)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Préparer ma reprise") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                item { Text("Choisis les séances à faire. Les autres resteront au programme.") }
                item { Text("À reprendre", style = MaterialTheme.typography.titleSmall) }
                items(model.overdue, key = { it.id }) { ResumeItem(it, courses, subjects, it.id in checked, choose) }
                item { Text("Prévues ce soir", style = MaterialTheme.typography.titleSmall) }
                items(model.due.filter { it.id !in overdue }, key = { it.id }) { ResumeItem(it, courses, subjects, it.id in checked, choose) }
            }
        },
        confirmButton = { Button(enabled = checked.isNotEmpty(), onClick = { onSave(checked) }) { Text("Enregistrer (${checked.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun ResumeItem(revision: Revision, courses: List<Course>, subjects: List<Subject>, selected: Boolean, choose: (String) -> Unit) {
    val course = courses.firstOrNull { it.id == revision.courseId } ?: return
    Row(
        Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox, onValueChange = { choose(revision.id) })
            .padding(vertical = JSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(JSpacing.m), verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, onCheckedChange = null)
        Column {
            Text(course.title)
            Text(listOfNotNull(subjects.firstOrNull { it.id == course.subjectId }?.name,
                revision.stepLabel, RevisionProgrammes.references(revision, courses)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
