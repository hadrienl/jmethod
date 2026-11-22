package fr.jmethod.app.ui.courses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.RevisionProgrammes
import fr.jmethod.app.ui.components.SurfaceCard
import java.time.LocalDate

/** Le choix des feuilles précède toujours la création de leur calendrier. */
@Composable
internal fun RevisionProgrammePanel(state: CoursesUiState, viewModel: CoursesViewModel) {
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var startDate by remember(state.defaultStartDate) { mutableStateOf(state.defaultStartDate) }
    val selectedSubject = state.courses.firstOrNull { it.id in selected }?.subjectId
    val rhythm = RevisionProgrammes.selectedRhythm(state.settings)
    SurfaceCard {
        Text("Mes programmes de révision", style = MaterialTheme.typography.titleMedium)
        Text("Enregistre tes feuilles, puis choisis celles à réviser ensemble. Chaque J portera sur cette sélection.")
        Button(onClick = { selected = emptySet(); startDate = state.defaultStartDate; open = true }) {
            Text("Démarrer un programme")
        }
        val duplicates = state.courses.groupBy { it.subjectId to it.displayReference.lowercase() }.values.filter { it.size > 1 }
        if (duplicates.isNotEmpty()) Text("Certains anciens cours ont la même référence dans une matière. Ouvre leurs fiches pour les renommer : aucun regroupement ni renumérotation n’a été effectué.")
        state.programmes.forEach { programme ->
            val references = state.courses.filter { it.id in programme.courseIds }.joinToString(" + ") { it.displayReference }
            Text("$references · J0 le ${programme.startDate} · ${if (programme.adaptiveEnabled) "adaptatif" else "dates fixes"}")
            if (programme.needsReview) Text("Rythme historique à vérifier dans les réglages ; les dates sont conservées tant qu’il reste ambigu.")
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Choisir mes feuilles de cours") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sélectionne une ou plusieurs feuilles de la même matière. Les feuilles ajoutées plus tard garderont leur propre programme.")
                state.courses.forEach { course ->
                    val hasPending = state.revisions.any { it.appliesTo(course.id) && it.status != fr.jmethod.app.model.RevisionStatus.COMPLETED }
                    val enabled = !hasPending && (selectedSubject == null || selectedSubject == course.subjectId)
                    Row {
                        Checkbox(checked = course.id in selected, enabled = enabled, onCheckedChange = { checked ->
                            selected = if (checked) selected + course.id else selected - course.id
                        })
                        Column {
                            Text("${course.displayReference} · ${course.title}")
                            Text(state.subjects.firstOrNull { it.id == course.subjectId }?.name.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            if (hasPending) Text("Programme déjà en cours", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(value = startDate, onValueChange = { startDate = it }, label = { Text("Date de J0 (AAAA-MM-JJ)") }, singleLine = true)
                Text("${rhythm.name} · ${if (state.settings.adaptiveEnabled) "adaptation aux scores activée" else "dates fixes"}")
                rhythm.steps.forEach { step ->
                    val date = runCatching { LocalDate.parse(startDate).plusDays(step.offsetDays.toLong()).toString() }.getOrDefault("—")
                    Text("J${step.offsetDays} · $date · ${when(step.phase) {
                        fr.jmethod.app.model.Phase.ENCODAGE -> "Encodage"
                        fr.jmethod.app.model.Phase.CONSOLIDATION -> "Consolidation"
                        fr.jmethod.app.model.Phase.RECUPERATION -> "Récupération"
                    }}")
                }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty() && runCatching { LocalDate.parse(startDate) }.isSuccess,
            onClick = { viewModel.startProgramme(selected.toList(), startDate) { open = false } }) { Text("Créer les J") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Annuler") } },
    )
}
