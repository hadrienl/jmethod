package fr.jmethod.app.ui.planning

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import fr.jmethod.app.ui.components.ScrollbarBox
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.FrenchDates
import fr.jmethod.app.domain.PlanningMode
import fr.jmethod.app.domain.PlanningModel
import fr.jmethod.app.domain.displayLabel
import fr.jmethod.app.domain.sentenceCase
import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import fr.jmethod.app.ui.components.Pill
import fr.jmethod.app.ui.components.ScreenHeader
import fr.jmethod.app.ui.components.SubjectDot
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.style.TextDecoration
import fr.jmethod.app.ui.components.SurfaceCard
import fr.jmethod.app.ui.components.screenPadding
import fr.jmethod.app.ui.components.subjectColor
import java.time.LocalDate

@Composable
fun PlanningScreen(
    state: PlanningUiState,
    viewModel: PlanningViewModel,
    contentPadding: PaddingValues,
    onAddCourseFrom: (PronoteEvent) -> Unit,
    onCreateCourse: () -> Unit,
    /** Fiche de la séance (section 8.1), commune à Aujourd’hui. */
    onOpenRevision: (String) -> Unit,
) {
    val selection = state.selection
    val mode = selection.mode
    val days = state.model.days
    val wide = fr.jmethod.app.ui.components.LocalWindowLayout.current.wide
    val listState = rememberLazyListState()
    ScrollbarBox(listState, Modifier.fillMaxSize(), barPadding = contentPadding) {
    LazyColumn(
            state = listState,
            contentPadding = screenPadding(contentPadding, 24.dp),
            verticalArrangement = Arrangement.spacedBy(JSpacing.m),
        ) {
            if (wide) {
                // Paysage ou grand écran : en-tête compact sur deux lignes, qui défile avec le contenu.
                item(key = "header") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JSpacing.m)) {
                        Text("Planning", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                        ModeSelector(mode, viewModel::setMode, Modifier.weight(1f))
                        TodayButton(viewModel)
                    }
                }
                item(key = "navigation") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JSpacing.m)) {
                        PeriodNavigation(state.model.period, viewModel, Modifier.weight(1f))
                        Filters(state, viewModel, Modifier.weight(1f))
                    }
                }
            } else {
                item(key = "header") {
                    ScreenHeader("Planning", "Les matières, les étapes et les séances à revoir.") { TodayButton(viewModel) }
                }
                item(key = "mode") { ModeSelector(mode, viewModel::setMode) }
                item(key = "navigation") { PeriodNavigation(state.model.period, viewModel) }
                item(key = "filters") { Filters(state, viewModel) }
            }
            if (!state.hasCourses) {
                item(key = "no-course") { NoCourseCard(onCreateCourse) }
            }
            when (mode) {
                PlanningMode.MONTH -> {
                    item(key = "grid") { MonthGrid(days, state.today) { viewModel.openDay(LocalDate.parse(it)) } }
                    val withContent = days.filter { it.tiles.isNotEmpty() || it.events.isNotEmpty() }
                    if (withContent.isEmpty()) {
                        item(key = "month-empty") {
                            Text("Aucune séance prévue ce mois-ci.", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center)
                        }
                    }
                    items(withContent, key = { it.date }) { day -> DaySection(day, mode, onOpenRevision, onAddCourseFrom) }
                }
                else -> items(days, key = { it.date }) { day -> DaySection(day, mode, onOpenRevision, onAddCourseFrom) }
            }
        }
    }
}

@Composable
private fun TodayButton(viewModel: PlanningViewModel) {
    OutlinedButton(
        onClick = viewModel::goToToday,
        shape = JShapes.control,
        border = BorderStroke(1.dp, JColors.Slate200),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = JSpacing.m),
    ) {
        Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp), tint = JColors.Violet800)
        Spacer(Modifier.width(JSpacing.s))
        Text("Aujourd’hui", color = JColors.Violet800, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModeSelector(mode: PlanningMode, onSelect: (PlanningMode) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(PlanningMode.DAY to "Jour", PlanningMode.WEEK to "Semaine", PlanningMode.MONTH to "Mois")
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().semantics { contentDescription = "Période affichée" }) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = JColors.Violet100,
                    activeContentColor = JColors.Violet900,
                    inactiveContainerColor = Color.White,
                    inactiveContentColor = JColors.Slate600,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(label, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PeriodNavigation(period: String, viewModel: PlanningViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.move(-1) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Période précédente")
            }
            Text(
                period.sentenceCase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite; heading() },
            )
            IconButton(onClick = { viewModel.move(1) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Période suivante")
            }
        }
    }
}

@Composable
private fun Filters(state: PlanningUiState, viewModel: PlanningViewModel, modifier: Modifier = Modifier) {
    val selection = state.selection
    Row(horizontalArrangement = Arrangement.spacedBy(JSpacing.s), verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        val subject = state.subjects.firstOrNull { it.id == selection.subjectId }
        DropdownFilter(
            label = subject?.name ?: "Matière",
            description = "Matière : ${subject?.name ?: "toutes les matières"}",
            active = subject != null,
            dotColor = subject?.color,
            options = listOf<Pair<String?, String>>(null to "Toutes les matières") + state.subjects.map { it.id to it.name },
            optionColor = { id -> state.subjects.firstOrNull { it.id == id }?.color },
            onSelect = viewModel::setSubject,
            modifier = Modifier.weight(1f),
        )
        DropdownFilter(
            label = selection.phase?.displayLabel ?: "Étape",
            description = "Étape : ${selection.phase?.displayLabel ?: "toutes les étapes"}",
            active = selection.phase != null,
            dotColor = null,
            options = listOf<Pair<Phase?, String>>(null to "Toutes les étapes") + Phase.entries.map { it to it.displayLabel },
            optionColor = { null },
            onSelect = viewModel::setPhase,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun <T> DropdownFilter(
    label: String,
    description: String,
    active: Boolean,
    dotColor: String?,
    options: List<Pair<T, String>>,
    optionColor: (T) -> String?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            leadingIcon = dotColor?.let { color -> { SubjectDot(color) } },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Color.White,
                selectedContainerColor = JColors.Violet100,
                selectedLabelColor = JColors.Violet900,
            ),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = description
                role = Role.DropdownList
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = optionColor(value)?.let { color -> { SubjectDot(color) } },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun NoCourseCard(onCreateCourse: () -> Unit) {
    SurfaceCard {
        Row(Modifier.padding(JSpacing.l), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(JShapes.control).background(JColors.Violet50), contentAlignment = Alignment.Center) {
                Icon(JIcons.School, contentDescription = null, tint = JColors.Violet700, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(JSpacing.m))
            Column(Modifier.weight(1f)) {
                Text("Aucun cours pour l’instant", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(JSpacing.xs))
                Text(
                    "Tes séances de révision apparaîtront ici. Ajoute un cours depuis un créneau de ton emploi du temps, ou crée-le directement.",
                    style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
                )
                Spacer(Modifier.height(JSpacing.m))
                Button(onClick = onCreateCourse, shape = JShapes.control, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(JSpacing.s))
                    Text("Créer un cours", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private val WEEKDAY_INITIALS = listOf("L" to "lundi", "M" to "mardi", "M" to "mercredi", "J" to "jeudi", "V" to "vendredi", "S" to "samedi", "D" to "dimanche")

/** Grille compacte du mois sur téléphone : le jour touché s’ouvre en mode Jour. */
@Composable
private fun MonthGrid(days: List<PlanningModel.Day>, today: LocalDate, onOpenDay: (String) -> Unit) {
    SurfaceCard {
        Column(Modifier.padding(JSpacing.s)) {
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
                WEEKDAY_INITIALS.forEach { (initial, _) ->
                    Text(initial, style = MaterialTheme.typography.labelMedium, color = JColors.Slate500, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(JSpacing.xs))
            days.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day -> MonthCell(day, LocalDate.parse(day.date) == today, onOpenDay, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(day: PlanningModel.Day, isToday: Boolean, onOpenDay: (String) -> Unit, modifier: Modifier) {
    val date = LocalDate.parse(day.date)
    val outside = day.outsideMonth == true
    val count = day.tiles.size
    val pending = day.tiles.count { !it.done }
    val description = buildString {
        append(FrenchDates.weekdayDayMonth(date).sentenceCase())
        append(", ")
        append(day.countLabel.substringBefore(" · "))
        if (day.events.isNotEmpty()) append(", ${day.events.size} cours à l’emploi du temps")
        if (isToday) append(", aujourd’hui")
    }
    Column(
        modifier
            .heightIn(min = 56.dp)
            .padding(2.dp)
            .clip(JShapes.control)
            .background(if (isToday) JColors.Violet100 else Color.Transparent)
            .clickable(onClickLabel = "Voir ce jour") { onOpenDay(day.date) }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium.tabular(),
            fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Medium,
            color = when {
                isToday -> JColors.Violet900
                outside -> JColors.Slate500
                else -> JColors.Slate900
            },
        )
        Spacer(Modifier.height(2.dp))
        if (count > 0) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (pending > 0) Color.White else JColors.Emerald800,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (pending > 0) JColors.Violet700 else JColors.Emerald50)
                    .padding(horizontal = 6.dp),
            )
        } else if (day.events.isNotEmpty()) {
            Box(Modifier.padding(top = 4.dp).size(6.dp).clip(CircleShape).background(JColors.Slate300))
        }
    }
}

@Composable
private fun DaySection(
    day: PlanningModel.Day,
    mode: PlanningMode,
    onOpen: (String) -> Unit,
    onAddCourseFrom: (PronoteEvent) -> Unit,
) {
    val shape = JShapes.card
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.White)
            .border(if (day.isToday) 2.dp else 1.dp, if (day.isToday) JColors.Violet700 else JColors.Slate200, shape)
            .padding(JSpacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
            val heading = if (mode == PlanningMode.MONTH) FrenchDates.weekdayDayMonthShort(LocalDate.parse(day.date)) else day.heading
            Text(
                heading.sentenceCase(),
                style = MaterialTheme.typography.titleMedium,
                color = if (day.outsideMonth == true) JColors.Slate600 else JColors.Slate900,
                modifier = Modifier.weight(1f),
            )
            Text(day.countLabel, style = MaterialTheme.typography.bodySmall, color = if (day.isToday) JColors.Violet800 else JColors.Slate600,
                fontWeight = if (day.isToday) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (day.events.isNotEmpty()) {
            Spacer(Modifier.height(JSpacing.s))
            Timetable(day, expandedByDefault = mode == PlanningMode.DAY, onAddCourseFrom)
        }
        if (day.tiles.isNotEmpty()) {
            Spacer(Modifier.height(JSpacing.s))
            Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                day.tiles.forEach { tile -> Tile(tile, onOpen) }
            }
        } else if (day.empty != null) {
            Spacer(Modifier.height(JSpacing.xs))
            Text(day.empty, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate500)
        }
    }
}

@Composable
private fun Timetable(day: PlanningModel.Day, expandedByDefault: Boolean, onAddCourseFrom: (PronoteEvent) -> Unit) {
    var open by rememberSaveable(day.date) { mutableStateOf(expandedByDefault) }
    Column(Modifier.fillMaxWidth().clip(JShapes.control).background(JColors.Slate50)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "Ouvert" else "Fermé" }
                .padding(horizontal = JSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(day.timetableSummary.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = JColors.Slate600)
        }
        if (open) {
            Column(Modifier.padding(start = JSpacing.m, end = JSpacing.xs, bottom = JSpacing.xs)) {
                day.events.forEach { line ->
                    val cancelled = line.event.isCancelled
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                        // Heure en colonne fixe, puis la matière : les noms longs passent à la ligne sans décaler l’heure.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(vertical = JSpacing.xs).clearAndSetSemantics { contentDescription = line.label },
                        ) {
                            Text(
                                line.event.startTime,
                                style = MaterialTheme.typography.labelLarge.tabular(),
                                color = if (cancelled) JColors.Slate500 else JColors.Slate700,
                                softWrap = false,
                                modifier = Modifier.widthIn(min = 48.dp).padding(end = JSpacing.xs),
                            )
                            SubjectDot(line.subjectColor, 8.dp)
                            Spacer(Modifier.width(JSpacing.s))
                            Text(
                                line.event.subjectName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (cancelled) JColors.Slate500 else JColors.Slate900,
                                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (cancelled) {
                                Spacer(Modifier.width(JSpacing.s))
                                Pill("Annulé", JColors.Rose50, JColors.Rose800)
                            }
                        }
                        if (line.canAddCourse) {
                            TextButton(
                                onClick = { onAddCourseFrom(line.event) },
                                shape = JShapes.control,
                                contentPadding = PaddingValues(horizontal = JSpacing.m),
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Ajouter ce cours : ${line.event.subjectName}, ${line.event.startTime}" },
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = JColors.Violet800, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(JSpacing.xs))
                                Text("Ajouter", color = JColors.Violet800, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(tile: PlanningModel.Tile, onOpen: (String) -> Unit) {
    val shape = JShapes.inner
    val color = subjectColor(tile.subject.color)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (tile.done) JColors.Slate50 else Color.White)
            .border(1.dp, JColors.Slate200, shape)
            .clickable(onClickLabel = "Ouvrir la séance") { onOpen(tile.revisionId) }
            .clearAndSetSemantics {
                contentDescription = tile.accessibilityLabel
                role = Role.Button
            }
            .height(IntrinsicSize.Min)
            .heightIn(min = 48.dp),
    ) {
        Box(Modifier.width(JSpacing.xs).fillMaxHeight().background(color))
        Column(Modifier.weight(1f).padding(horizontal = JSpacing.m, vertical = JSpacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tile.subjectName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill(tile.stepLabel, JColors.Slate100, JColors.Slate700)
            }
            Spacer(Modifier.height(JSpacing.xs))
            Text(tile.phaseLine, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            Text(tile.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(tile.chapter, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            Spacer(Modifier.height(JSpacing.s))
            if (tile.done) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JColors.Emerald800, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(JSpacing.xs))
                    Text(tile.footer, style = MaterialTheme.typography.bodySmall, color = JColors.Emerald800, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(tile.footer, style = MaterialTheme.typography.bodySmall, color = JColors.Violet800, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
