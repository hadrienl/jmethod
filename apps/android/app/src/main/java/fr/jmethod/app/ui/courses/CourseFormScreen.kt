package fr.jmethod.app.ui.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.CourseEditing
import fr.jmethod.app.domain.FrenchDates
import fr.jmethod.app.domain.sentenceCase
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.today.subjectColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Création d’un cours (`AddCourseModal`), en plein écran sur téléphone. L’état vit dans
 * [CoursesViewModel] : il survit à la rotation et se conserve d’une ouverture à l’autre comme sur le web.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseFormScreen(state: CoursesUiState, viewModel: CoursesViewModel, modifier: Modifier = Modifier) {
    val form = state.form
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = viewModel::closeCreate)
    val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = JColors.Slate50,
        topBar = {
            TopAppBar(
                title = { Text("Nouveau cours", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeCreate) { Icon(Icons.Filled.Close, contentDescription = "Annuler la création du cours") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val count = state.intervalCount
            Text(
                if (count > 1) "Génère automatiquement les $count échéances de révision." else "Génère automatiquement l’échéance de révision.",
                style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
            )

            Column {
                SectionLabel("Matière")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    state.subjects.forEach { subject ->
                        FilterChip(
                            selected = subject.id == state.formSubjectId,
                            onClick = { viewModel.setFormSubject(subject.id) },
                            label = { Text(subject.name) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(subjectColor(subject.color))) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.White,
                                selectedContainerColor = JColors.Violet100,
                                selectedLabelColor = JColors.Violet900,
                            ),
                            modifier = Modifier.semantics { role = Role.RadioButton },
                        )
                    }
                }
            }

            Column {
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = form.chapter,
                        onValueChange = viewModel::setChapter,
                        label = { Text("Chapitre") },
                        placeholder = { Text("ex. : Arithmétique, Guerre froide…") },
                        singleLine = true,
                        isError = form.showErrors && form.chapter.isBlank(),
                        supportingText = if (form.showErrors && form.chapter.isBlank()) {
                            { Text("Indique le chapitre du cours.") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = fieldColors,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) {
                            contentDescription = "Numéro attribué automatiquement : C${state.formCourseNumber}"
                        },
                    ) {
                        Text(
                            "C${state.formCourseNumber}",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = JColors.Sky700,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                        Text("Numéro auto", style = MaterialTheme.typography.labelSmall, color = JColors.Slate600)
                    }
                }
                if (state.formSuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Chapitres existants :", style = MaterialTheme.typography.labelMedium, color = JColors.Slate600)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.formSuggestions.forEach { chapter ->
                            AssistChip(
                                onClick = { viewModel.setChapter(chapter) },
                                label = { Text(chapter) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = form.title,
                onValueChange = viewModel::setTitle,
                label = { Text("Titre de la leçon") },
                placeholder = { Text("ex. : Algorithme d’Euclide et PGCD") },
                singleLine = true,
                isError = form.showErrors && form.title.isBlank(),
                supportingText = if (form.showErrors && form.title.isBlank()) {
                    { Text("Indique le titre de la leçon.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                val dateLabel = FrenchDates.format(form.date, FrenchDates::weekdayDayMonthYear).sentenceCase()
                SectionLabel("Date du cours (J0)")
                TextButton(
                    onClick = { pickingDate = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .semantics { contentDescription = "Date du cours : $dateLabel. Modifier" },
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, tint = JColors.Violet700)
                    Spacer(Modifier.width(12.dp))
                    Text(dateLabel, color = JColors.Slate900, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("Modifier", color = JColors.Violet800, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "La première révision (Découvrir, J0) est programmée ce jour-là.",
                    style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
                )
            }

            OutlinedTextField(
                value = form.description,
                onValueChange = viewModel::setDescription,
                label = { Text("Notes ou notions clés (facultatif)") },
                placeholder = { Text("ex. : définitions p. 45, exercices 12 et 14…") },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = viewModel::closeCreate, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = viewModel::submitCreate, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(CourseEditing.submitLabel(state.formCourseNumber), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (pickingDate) {
        val initial = FrenchDates.parse(form.date) ?: LocalDate.now()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.setDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    pickingDate = false
                }) { Text("Valider") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Annuler") } },
        ) {
            DatePicker(state = pickerState, title = { Text("Date du cours (J0)", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = JColors.Slate700,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
    )
}
