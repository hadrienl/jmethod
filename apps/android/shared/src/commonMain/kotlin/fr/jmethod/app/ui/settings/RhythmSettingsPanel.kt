package fr.jmethod.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.jmethod.app.model.*
import fr.jmethod.app.domain.RevisionProgrammes
import fr.jmethod.app.ui.components.SurfaceCard

internal fun Phase.rhythmLabel(): String = when (this) {
    Phase.ENCODAGE -> "Encodage"
    Phase.CONSOLIDATION -> "Consolidation"
    Phase.RECUPERATION -> "Récupération"
}

@Composable
internal fun RhythmSettingsPanel(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val base = RevisionProgrammes.selectedRhythm(ui.document.settings)
    var steps by remember(base) { mutableStateOf(base.steps) }
    var adaptive by remember(ui.document.settings.adaptiveEnabled) { mutableStateOf(ui.document.settings.adaptiveEnabled) }
    var day by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmApply by remember { mutableStateOf(false) }
    val rhythm = base.copy(revision = base.revision + if (steps != base.steps) 1 else 0, steps = steps)
    val valid = runCatching { RevisionProgrammes.validate(rhythm) }.isSuccess
    SurfaceCard {
        Text("Rythme de révision", style = MaterialTheme.typography.titleMedium)
        Text("Les jours sont comptés depuis J0. Chaque programme conserve une copie de ce rythme et de son mode.")
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("J${step.offsetDays}", modifier = Modifier.weight(1f))
                var expanded by remember(step.id) { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) { Text(step.phase.rhythmLabel()) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Phase.entries.forEach { phase -> DropdownMenuItem(text = { Text(phase.rhythmLabel()) }, onClick = {
                            steps = steps.map { if (it.id == step.id) it.copy(phase = phase) else it }; expanded = false
                        }) }
                    }
                }
                TextButton(enabled = index != 0, onClick = { steps = steps.filter { it.id != step.id } }) { Text("Retirer") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("Nouveau jour J") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val value = day.toIntOrNull()
                if (value == null || value !in 1..3650 || steps.any { it.offsetDays == value }) message = "Saisis un jour unique entre 1 et 3650."
                else {
                    steps = (steps + RhythmStep("day-$value", value, if (value <= 3) Phase.ENCODAGE else if (value <= 25) Phase.CONSOLIDATION else Phase.RECUPERATION)).sortedBy { it.offsetDays }
                    day = ""; message = null
                }
            }) { Text("Ajouter") }
        }
        Row {
            Checkbox(checked = adaptive, onCheckedChange = { adaptive = it })
            Text("Adapter les dates aux scores des nouveaux programmes")
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { steps = RevisionRhythm.DEFAULT.steps }) { Text("Rétablir J0 à J100") }
        Button(enabled = valid && !ui.draft.dirty, onClick = { viewModel.saveRhythm(rhythm, adaptive, false) }) { Text("Enregistrer pour les nouveaux programmes") }
        OutlinedButton(enabled = valid && !ui.draft.dirty && ui.document.revisions.any { it.status != RevisionStatus.COMPLETED }, onClick = { confirmApply = true }) { Text("Appliquer aux programmes en cours…") }
        if (ui.draft.dirty) Text("Enregistre ou annule d’abord les autres réglages modifiés.")
    }
    if (confirmApply) {
        val preview = runCatching { RevisionProgrammes.reapply(ui.document, ui.document.programmes.map { it.id }.toSet(), rhythm) }.getOrNull()
        AlertDialog(onDismissRequest = { confirmApply = false }, title = { Text("Vérifier les nouvelles dates") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Les séances terminées et les rattrapages restent inchangés. Les nouvelles dates sont calculées depuis le J0 de chaque programme. Une date passée devient une révision en retard. Le mode fixe ou adaptatif de chaque programme est conservé.")
                ui.document.programmes.filter { p -> ui.document.revisions.any { it.programmeId == p.id && it.status != RevisionStatus.COMPLETED } }.forEach { programme ->
                    Text(ui.document.courses.filter { it.id in programme.courseIds }.joinToString(" + ") { it.displayReference }, style = MaterialTheme.typography.titleSmall)
                    val old = ui.document.revisions.filter { it.programmeId == programme.id && it.status != RevisionStatus.COMPLETED && it.isEmergencySession != true }
                    val updated = preview?.revisions.orEmpty().filter { it.programmeId == programme.id && it.status != RevisionStatus.COMPLETED && it.isEmergencySession != true }
                    old.filter { r -> updated.none { it.nominalOffsetDays == r.nominalOffsetDays } }.forEach { Text("${it.stepLabel} · ${it.scheduledDate} → retirée") }
                    updated.forEach { revision ->
                        val previous = old.firstOrNull { it.nominalOffsetDays == revision.nominalOffsetDays }?.scheduledDate ?: "nouvelle"
                        Text("${revision.stepLabel} · $previous → ${revision.scheduledDate} · ${revision.phase.rhythmLabel()}")
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = preview != null, onClick = { viewModel.saveRhythm(rhythm, adaptive, true); confirmApply = false }) { Text("Appliquer et enregistrer") } }, dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Annuler") } })
    }
}
