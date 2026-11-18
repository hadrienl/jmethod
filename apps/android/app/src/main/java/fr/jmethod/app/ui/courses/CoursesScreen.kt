package fr.jmethod.app.ui.courses

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.CoursesListModel
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import fr.jmethod.app.ui.components.EmptyState
import fr.jmethod.app.ui.components.Pill
import fr.jmethod.app.ui.components.ScreenHeader
import fr.jmethod.app.ui.components.SubjectDot
import androidx.compose.material.icons.filled.Add
import fr.jmethod.app.ui.components.SurfaceCard
import fr.jmethod.app.ui.components.screenPadding
import fr.jmethod.app.ui.components.subjectColor

@Composable
fun CoursesScreen(
    state: CoursesUiState,
    viewModel: CoursesViewModel,
    contentPadding: PaddingValues,
    onShowPlanning: () -> Unit,
    onOpenNotebook: () -> Unit,
) {
    LazyColumn(
        contentPadding = screenPadding(contentPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(JSpacing.l),
    ) {
        item(key = "header") {
            ScreenHeader(state.list.header, "Tous tes cours classés par matière et par chapitre, avec le suivi de leurs révisions.")
        }
        item(key = "notebook") { NotebookEntry(onOpenNotebook) }
        if (!state.hasCourses) {
            item(key = "first") { FirstCourse(onCreate = { viewModel.openCreate() }, onShowPlanning = onShowPlanning) }
            return@LazyColumn
        }
        item(key = "search") { SearchField(state.search, viewModel::setSearch) }
        item(key = "chips") { SubjectChips(state, viewModel::setSubjectFilter) }
        if (state.list.empty) {
            item(key = "empty") {
                SurfaceCard {
                    EmptyState(Icons.Filled.Search, "Aucun cours trouvé", "Essaie de modifier ta recherche ou tes filtres.")
                }
            }
        }
        items(state.list.groups, key = { "subject-" + it.subject.id }) { group -> SubjectGroup(group, viewModel::openDetail) }
    }
}

/** Entrée du cahier de textes Pronote (lecture seule). */
@Composable
private fun NotebookEntry(onOpen: () -> Unit) {
    val shape = JShapes.inner
    Row(
        Modifier.fillMaxWidth().clip(shape).background(JColors.Violet50).border(1.dp, JColors.Violet200, shape)
            .clickable(role = Role.Button, onClickLabel = "Ouvrir le cahier de textes") { onOpen() }
            .semantics(mergeDescendants = true) {}
            .padding(JSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(JShapes.control).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(JIcons.Notebook, contentDescription = null, tint = JColors.Violet700, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(JSpacing.m))
        Column(Modifier.weight(1f)) {
            Text("Cahier de textes", style = MaterialTheme.typography.titleMedium, color = JColors.Violet900)
            Text(
                "Contenus de séance, devoirs et pièces jointes publiés dans Pronote.",
                style = MaterialTheme.typography.bodySmall, color = JColors.Slate700,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = JColors.Violet800)
    }
}

@Composable
private fun FirstCourse(onCreate: () -> Unit, onShowPlanning: () -> Unit) {
    SurfaceCard {
        EmptyState(
            icon = JIcons.School,
            title = "Aucun cours pour l’instant",
            message = "Crée ton premier cours : ses révisions seront planifiées automatiquement, de Découvrir à Se tester. " +
                "Tu peux aussi l’ajouter depuis ton emploi du temps, avec « Ajouter ce cours » dans Aujourd’hui ou dans le Planning.",
        ) {
            Button(onClick = onCreate, shape = JShapes.control, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(JSpacing.s))
                Text("Créer mon premier cours", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onShowPlanning, shape = JShapes.control, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Voir le Planning")
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Cours, chapitre ou C1…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Effacer la recherche") } }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = JShapes.control,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Color.White, focusedContainerColor = Color.White,
            unfocusedBorderColor = JColors.Slate200,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SubjectChips(state: CoursesUiState, onSelect: (String?) -> Unit) {
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = Color.White,
        selectedContainerColor = JColors.Violet100,
        selectedLabelColor = JColors.Violet900,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s), modifier = Modifier.semantics { contentDescription = "Filtrer par matière" }) {
        item(key = "all") {
            FilterChip(
                selected = state.subjectFilter == null,
                onClick = { onSelect(null) },
                label = { Text("Toutes") },
                colors = colors,
            )
        }
        items(state.subjects, key = { it.id }) { subject ->
            FilterChip(
                selected = state.subjectFilter == subject.id,
                onClick = { onSelect(subject.id) },
                label = { Text(subject.name) },
                leadingIcon = { SubjectDot(subject.color) },
                colors = colors,
            )
        }
    }
}

@Composable
private fun SubjectGroup(group: CoursesListModel.SubjectGroup, onOpen: (String) -> Unit) {
    SurfaceCard {
        Column(Modifier.padding(JSpacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
                SubjectDot(group.subject.color, 12.dp)
                Spacer(Modifier.width(JSpacing.m))
                Text(group.subject.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(JSpacing.s))
                Text(group.countLabel, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            }
            group.chapters.forEach { chapter ->
                Spacer(Modifier.height(JSpacing.l))
                Text(
                    "Chapitre : ${chapter.chapter}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = JColors.Slate700,
                    modifier = Modifier.semantics { heading() },
                )
                chapter.courses.forEach { card ->
                    Spacer(Modifier.height(JSpacing.s))
                    CourseCard(card, onOpen)
                }
            }
        }
    }
}

@Composable
private fun CourseCard(card: CoursesListModel.Card, onOpen: (String) -> Unit) {
    val shape = JShapes.inner
    Column(
        Modifier.fillMaxWidth().clip(shape).background(JColors.Slate50).border(1.dp, JColors.Slate200, shape)
            .clickable(role = Role.Button, onClickLabel = "Ouvrir la fiche du cours") { onOpen(card.course.id) }
            .semantics(mergeDescendants = true) {}
            .padding(JSpacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(card.badge, JColors.Violet100, JColors.Violet800)
            Spacer(Modifier.weight(1f))
            if (card.lastScoreLine != null) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = JColors.Amber700, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(JSpacing.xs))
                Text(card.lastScoreLine, style = MaterialTheme.typography.labelMedium.tabular(), color = JColors.Emerald800, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(JSpacing.s))
        Text(card.course.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(card.dateLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
        Spacer(Modifier.height(JSpacing.m))
        Row {
            Text("Progression", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600, modifier = Modifier.weight(1f))
            Text(card.counter, style = MaterialTheme.typography.bodySmall.tabular(), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(JSpacing.s))
        LinearProgressIndicator(
            progress = { card.progress / 100f },
            color = JColors.Violet700,
            trackColor = JColors.Slate200,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = "Progression : ${card.progress} %" },
        )
        if (card.next != null) {
            Spacer(Modifier.height(JSpacing.s))
            Row {
                Text(card.next.label, style = MaterialTheme.typography.bodySmall, color = JColors.Slate700, modifier = Modifier.weight(1f))
                Text(card.next.date, style = MaterialTheme.typography.bodySmall.tabular(), color = JColors.Slate600)
            }
        }
    }
}
