package fr.jmethod.app.ui.courses

import fr.jmethod.app.ui.components.ScrollbarBox
import fr.jmethod.app.ui.platform.SystemBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
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
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import fr.jmethod.app.ui.components.fullScreenGutter
import fr.jmethod.app.ui.components.SubjectDot
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import fr.jmethod.app.ui.components.subjectColor
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
    SystemBackHandler(onBack = viewModel::closeCreate)
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
        val scrollState = rememberScrollState()
        ScrollbarBox(scrollState, Modifier.fillMaxSize().padding(padding)) {
        Column(
                Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = JSpacing.l + fullScreenGutter(), vertical = JSpacing.l),
                verticalArrangement = Arrangement.spacedBy(JSpacing.xl),
            ) {
                val count = state.intervalCount
                Text(
                    "Enregistre ta feuille, puis démarre son programme depuis Mes cours.",
                    style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
                )

                Column {
                    SectionLabel("Matière")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        state.subjects.forEach { subject ->
                            FilterChip(
                                selected = subject.id == state.formSubjectId,
                                onClick = { viewModel.setFormSubject(subject.id) },
                                label = { Text(subject.name) },
                                leadingIcon = { SubjectDot(subject.color) },
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

                if (form.notebook != null) {
                    Column {
                        Text("Destination des ressources", style = MaterialTheme.typography.titleSmall)
                        androidx.compose.material3.FilterChip(selected = form.existingTargetId == null,
                            onClick = { viewModel.setExistingTarget(null) }, label = { Text("Nouvelle feuille") })
                        state.courses.filter { it.subjectId == state.formSubjectId }.forEach { leaf ->
                            androidx.compose.material3.FilterChip(selected = form.existingTargetId == leaf.id,
                                onClick = { viewModel.setExistingTarget(leaf.id) }, label = { Text("Associer à ${leaf.displayReference} · ${leaf.title}") })
                        }
                        if (form.existingTargetId != null) Text("La référence, les notes et les révisions de cette feuille seront conservées.")
                    }
                }
                OutlinedTextField(
                    value = form.reference, onValueChange = viewModel::setReference,
                    label = { Text("Référence personnelle") }, placeholder = { Text("C${state.formCourseNumber}") },
                    singleLine = true, isError = form.reference.length > 24,
                    supportingText = { Text("Ton numéro de feuille, unique dans cette matière. Vide : attribution automatique.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            value = form.chapter,
                            onValueChange = viewModel::setChapter,
                            label = { Text("Chapitre (facultatif)") },
                            placeholder = { Text("ex. : Arithmétique, Guerre froide…") },
                            singleLine = true,
                            isError = false,

                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(JSpacing.m))
                        // Numéro attribué : même hauteur que le champ (56 dp), aligné sur son cadre.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(top = JSpacing.s).height(56.dp).widthIn(min = 72.dp).clip(JShapes.control).background(JColors.Violet50)
                                .padding(horizontal = JSpacing.m)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "Référence : ${form.reference.ifBlank { "C${state.formCourseNumber}" }}"
                                },
                        ) {
                            Text(form.reference.ifBlank { "C${state.formCourseNumber}" }, maxLines = 1, style = MaterialTheme.typography.titleMedium.tabular(), color = JColors.Violet800)
                            Text(if (form.reference.isBlank()) "auto" else "référence", style = MaterialTheme.typography.labelSmall, color = JColors.Violet800)
                        }
                    }
                    if (state.formSuggestions.isNotEmpty()) {
                        Spacer(Modifier.height(JSpacing.s))
                        Text("Chapitres existants :", style = MaterialTheme.typography.labelMedium, color = JColors.Slate600)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s)) {
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
                    label = { Text("Titre de la leçon (facultatif)") },
                    placeholder = { Text("ex. : Algorithme d’Euclide et PGCD") },
                    singleLine = true,
                    isError = false,

                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    val dateLabel = FrenchDates.format(form.date, FrenchDates::weekdayDayMonthYear).sentenceCase()
                    SectionLabel("Date de la feuille")
                    TextButton(
                        onClick = { pickingDate = true },
                        shape = JShapes.control,
                        contentPadding = PaddingValues(horizontal = JSpacing.l),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(JShapes.control).background(Color.White)
                            .border(1.dp, JColors.Slate300, JShapes.control)
                            .semantics { contentDescription = "Date du cours : $dateLabel. Modifier" },
                    ) {
                        Icon(Icons.Filled.DateRange, contentDescription = null, tint = JColors.Violet700)
                        Spacer(Modifier.width(JSpacing.m))
                        Text(dateLabel, color = JColors.Slate900, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("Modifier", color = JColors.Violet800, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(JSpacing.xs))
                    Text(
                        "Les révisions seront créées lorsque tu démarreras un programme.",
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

                if (form.notebook != null) {
                    NotebookDocuments(form, viewModel)
                }

                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = viewModel::closeCreate, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
                    Spacer(Modifier.width(JSpacing.m))
                    Button(onClick = viewModel::submitCreate, shape = JShapes.control, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(JSpacing.s))
                        Text(if (form.existingTargetId == null) "Enregistrer la feuille" else "Associer les ressources", fontWeight = FontWeight.SemiBold)
                    }
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
            DatePicker(state = pickerState, title = { Text("Date de la feuille", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
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
        modifier = Modifier.padding(bottom = JSpacing.s).semantics { heading() },
    )
}

/** Création depuis le cahier de textes : message éventuel et documents joints (cochés par défaut). */
@Composable
private fun NotebookDocuments(form: CourseFormState, viewModel: CoursesViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        form.notice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = JColors.Amber900,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(JColors.Amber50).padding(12.dp),
            )
        }
        Text("Documents joints", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = JColors.Slate700,
            modifier = Modifier.semantics { heading() })
        if (form.documents.isEmpty()) {
            Text("Aucun document joint à cette séance.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
        }
        form.documents.forEachIndexed { index, document ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .toggleable(value = document.selected, role = Role.Checkbox) { viewModel.toggleDocument(index) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(checked = document.selected, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(document.name, style = MaterialTheme.typography.bodyMedium)
                    document.extractedTitle?.let { Text("Titre lu : $it", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600) }
                }
            }
        }
    }
}
