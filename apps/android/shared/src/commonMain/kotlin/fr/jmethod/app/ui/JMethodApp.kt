package fr.jmethod.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import fr.jmethod.app.ui.components.LocalContentWidth
import fr.jmethod.app.ui.components.NavigationMode
import fr.jmethod.app.ui.components.TwoColumnWidth
import fr.jmethod.app.ui.components.ScrollbarBox
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import fr.jmethod.app.domain.StatsModel
import fr.jmethod.app.pronote.PronoteUiState
import fr.jmethod.app.ui.components.LocalScreenGutter
import fr.jmethod.app.ui.components.ReadingWidth
import fr.jmethod.app.ui.components.DetailPaneWidth
import fr.jmethod.app.ui.components.LocalSheetMode
import fr.jmethod.app.ui.components.SheetMode
import fr.jmethod.app.ui.components.BrandMark
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import fr.jmethod.app.ui.components.motion
import fr.jmethod.app.ui.courses.CoursesUiState
import fr.jmethod.app.ui.planning.PlanningUiState
import fr.jmethod.app.ui.pronote.PronoteSyncInfo
import fr.jmethod.app.ui.settings.SettingsUiState
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.today.TodayUiState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExtendedFloatingActionButton
import fr.jmethod.app.domain.RevisionDetailModel
import fr.jmethod.app.domain.RevisionSession
import fr.jmethod.app.documents.CourseFiles
import fr.jmethod.app.documents.DocType
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.ui.documents.SheetDocument
import fr.jmethod.app.ui.notebook.NotebookUiState
import fr.jmethod.app.ui.today.SessionPage
import fr.jmethod.app.ui.today.SessionSheet
import fr.jmethod.app.ui.today.SessionSheetState
import fr.jmethod.app.ui.courses.CourseDetailSheet
import fr.jmethod.app.ui.courses.CourseFormScreen
import fr.jmethod.app.ui.courses.CoursesEvent
import fr.jmethod.app.ui.courses.CoursesScreen
import fr.jmethod.app.ui.courses.CoursesViewModel
import fr.jmethod.app.ui.planning.PlanningScreen
import fr.jmethod.app.ui.planning.PlanningViewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.jmethod.app.AppContainer
import fr.jmethod.app.ui.platform.LocalPlatform
import fr.jmethod.app.ui.platform.SystemBackHandler
import fr.jmethod.app.ui.pronote.PronoteConnection
import fr.jmethod.app.ui.components.LocalWindowLayout
import fr.jmethod.app.ui.components.WindowLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import fr.jmethod.app.pronote.AccessPhase
import fr.jmethod.app.ui.pronote.LoadingScreen
import fr.jmethod.app.ui.pronote.LoginScreen
import fr.jmethod.app.ui.pronote.OfflineBanner
import fr.jmethod.app.ui.pronote.PronoteViewModel
import fr.jmethod.app.ui.notebook.NotebookScreen
import fr.jmethod.app.ui.notebook.NotebookViewModel
import fr.jmethod.app.ui.notebook.NotebookEvent
import fr.jmethod.app.ui.settings.SettingsEvent
import fr.jmethod.app.ui.settings.SettingsScreen
import fr.jmethod.app.ui.settings.SettingsViewModel
import fr.jmethod.app.ui.stats.StatsScreen
import fr.jmethod.app.ui.stats.StatsViewModel
import androidx.compose.ui.unit.dp
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.today.TodayEvent
import fr.jmethod.app.ui.today.TodayScreen
import fr.jmethod.app.ui.today.TodayViewModel

private enum class Tab { TODAY, PLANNING, COURSES, STATS, SETTINGS }

/** Destinations de la navigation principale (barre en portrait, rail en paysage). */
private data class Destination(val tab: Tab, val label: String, val icon: ImageVector, val spoken: String)

private val Destinations = listOf(
    Destination(Tab.TODAY, "Aujourd’hui", Icons.Filled.Home, "Aujourd’hui"),
    Destination(Tab.PLANNING, "Planning", Icons.Filled.DateRange, "Planning"),
    Destination(Tab.COURSES, "Cours", JIcons.Book, "Mes cours"),
    Destination(Tab.STATS, "Progrès", JIcons.Stats, "Mes progrès"),
    Destination(Tab.SETTINGS, "Réglages", Icons.Filled.Settings, "Réglages"),
)

private enum class ToastTone { SUCCESS, WARNING, INFO }

private class ToastVisuals(val title: String, override val message: String, val tone: ToastTone) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = SnackbarDuration.Long
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = JColors.Violet700) }
}

/** Libellé d’onglet sur une ligne : il rétrécit légèrement plutôt que de se couper avec un grand texte. */
@Composable
private fun NavLabel(text: String) {
    // Libellés de même taille dans toute la barre : l’agrandissement système est plafonné à 110 %
    // (comme les barres d’onglets iOS), puis le libellé ne rétrécit que s’il ne tient toujours pas.
    val fontScale = LocalDensity.current.fontScale
    val size = MaterialTheme.typography.labelMedium.fontSize * (minOf(fontScale, 1.1f) / fontScale)
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = size, stepSize = 0.25.sp),
    )
}

@Composable
private fun NavIcon(destination: Destination, badge: Int) {
    if (destination.tab == Tab.TODAY && badge > 0) {
        BadgedBox(badge = {
            Badge(containerColor = JColors.Violet700, contentColor = Color.White) { Text("$badge") }
        }) { Icon(destination.icon, contentDescription = null) }
    } else {
        Icon(destination.icon, contentDescription = null)
    }
}

private fun spokenLabel(destination: Destination, badge: Int): String =
    if (destination.tab == Tab.TODAY && badge > 0) "Aujourd’hui, $badge séance${if (badge > 1) "s" else ""} à faire" else destination.spoken

@Composable
private fun AppNavigationBar(current: Tab, badge: Int, onSelect: (Tab) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = JColors.Violet800,
        selectedTextColor = JColors.Violet800,
        indicatorColor = JColors.Violet100,
        unselectedIconColor = JColors.Slate600,
        unselectedTextColor = JColors.Slate600,
    )
    Column {
        HorizontalDivider(color = JColors.Slate200)
        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            Destinations.forEach { destination ->
                NavigationBarItem(
                    selected = current == destination.tab,
                    onClick = { onSelect(destination.tab) },
                    icon = { NavIcon(destination, badge) },
                    label = { NavLabel(destination.label) },
                    colors = colors,
                    modifier = Modifier.semantics { contentDescription = spokenLabel(destination, badge) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNavigationRail(current: Tab, badge: Int, showBrand: Boolean = false, onSelect: (Tab) -> Unit) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = JColors.Violet800,
        selectedTextColor = JColors.Violet800,
        indicatorColor = JColors.Violet100,
        unselectedIconColor = JColors.Slate600,
        unselectedTextColor = JColors.Slate600,
    )
    Row(Modifier.width(NavigationMode.RAIL.width)) {
        // En-tête seulement sur une fenêtre assez haute : les cinq destinations doivent tenir dans la
        // hauteur d’un téléphone en paysage.
        NavigationRail(
            containerColor = Color.White,
            header = if (showBrand) {
                { BrandMark(40.dp, Modifier.padding(top = JSpacing.m)) }
            } else {
                null
            },
        ) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Destinations.forEach { destination ->
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
                        tooltip = { PlainTooltip { Text(spokenLabel(destination, badge)) } },
                        state = rememberTooltipState(),
                    ) {
                        NavigationRailItem(
                            selected = current == destination.tab,
                            onClick = { onSelect(destination.tab) },
                            icon = { NavIcon(destination, badge) },
                            label = { NavLabel(destination.label) },
                            colors = colors,
                            modifier = Modifier.semantics { contentDescription = spokenLabel(destination, badge) },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
        VerticalDivider(color = JColors.Slate200)
    }
}

@Composable
private fun AppNavigationSidebar(current: Tab, badge: Int, onSelect: (Tab) -> Unit) {
    Row(Modifier.width(NavigationMode.SIDEBAR.width).fillMaxHeight().background(Color.White)) {
        val scroll = rememberScrollState()
        ScrollbarBox(scroll, Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(JSpacing.m).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(JSpacing.s),
            ) {
                Row(
                    Modifier.padding(JSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(JSpacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark(36.dp)
                    Text("J-Method", style = MaterialTheme.typography.titleMedium)
                }
                Destinations.forEach { destination ->
                    val selected = current == destination.tab
                    var focused by remember { mutableStateOf(false) }
                    val color = if (selected) JColors.Violet800 else JColors.Slate700
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(JShapes.control)
                            .background(if (selected) JColors.Violet100 else Color.Transparent)
                            .border(2.dp, if (focused) JColors.Violet700 else Color.Transparent, JShapes.control)
                            .onFocusChanged { focused = it.isFocused }
                            .selectable(selected, role = Role.Tab, onClick = { onSelect(destination.tab) })
                            .semantics { contentDescription = spokenLabel(destination, badge) }
                            .padding(horizontal = JSpacing.m, vertical = JSpacing.s),
                        horizontalArrangement = Arrangement.spacedBy(JSpacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(destination.icon, contentDescription = null, tint = color)
                        Text(destination.spoken, Modifier.weight(1f).clearAndSetSemantics {}, color = color,
                            style = MaterialTheme.typography.labelLarge)
                        if (destination.tab == Tab.TODAY && badge > 0) {
                            Badge(Modifier.clearAndSetSemantics {}, containerColor = JColors.Violet700) { Text("$badge") }
                        }
                    }
                }
            }
        }
        VerticalDivider(color = JColors.Slate200)
    }
}

/** Snackbar sombre à marge et coins arrondis : titre coloré selon le ton, message, « Fermer ». */
@Composable
private fun ToastSnackbar(data: SnackbarData) {
    val visuals = data.visuals as? ToastVisuals
    Snackbar(
        modifier = Modifier.padding(horizontal = JSpacing.m, vertical = JSpacing.s),
        shape = JShapes.inner,
        containerColor = JColors.Slate900,
        contentColor = Color.White,
        dismissAction = {
            TextButton(onClick = { data.dismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = JColors.Violet200)) { Text("Fermer") }
        },
    ) {
        Column(Modifier.padding(vertical = JSpacing.xs)) {
            if (visuals != null) {
                Text(
                    visuals.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = when (visuals.tone) {
                        ToastTone.WARNING -> JColors.Amber300
                        ToastTone.SUCCESS -> JColors.Lime200
                        ToastTone.INFO -> JColors.Violet200
                    },
                )
            }
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Racine de l’app, commune à Android et à l’ordinateur.
 * @param createConnection contrôleur de connexion Pronote de la plateforme (page officielle ou connexion directe).
 */
@Composable
fun JMethodApp(container: AppContainer, createConnection: (CoroutineScope) -> PronoteConnection) {
    var dayReady by remember(container) { mutableStateOf(false) }
    val readError by container.repository.loadError.collectAsStateWithLifecycle()
    // Gel de la journée avant toute reprise Pronote. Un échec d’écriture n’empêche pas d’utiliser l’app (comme iOS) :
    // le retour au premier plan réessaie et l’annonce.
    LaunchedEffect(container) {
        container.repository.tryOpenRevisionDay()
        dayReady = true
    }
    val error = readError
    if (error != null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Column { Text("Données indisponibles"); Text(error) } }
        return
    }
    if (!dayReady) { Loading(); return }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalWindowLayout provides WindowLayout(maxWidth, maxHeight)) {
            AppContent(container, createConnection)
        }
    }
}

@Composable
private fun AppContent(container: AppContainer, createConnection: (CoroutineScope) -> PronoteConnection) {
    val todayViewModel: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(container.repository, container.clock) } })
    val pronoteViewModel: PronoteViewModel = viewModel(factory = viewModelFactory { initializer { PronoteViewModel(container, createConnection) } })
    val planningViewModel: PlanningViewModel = viewModel(factory = viewModelFactory { initializer { PlanningViewModel(container.repository, container.clock) } })
    val coursesViewModel: CoursesViewModel = viewModel(factory = viewModelFactory { initializer { CoursesViewModel(container.repository, container.clock, container.courseCreator, container.courseFiles) } })
    val statsViewModel: StatsViewModel = viewModel(factory = viewModelFactory { initializer { StatsViewModel(container.repository, container.clock) } })
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container.repository, container.clock) } })
    val notebookViewModel: NotebookViewModel = viewModel(
        factory = viewModelFactory { initializer { NotebookViewModel(pronoteViewModel.controller, container.repository, container.cacheDir, container.courseCreator, container.courseFiles, container.clock) } },
    )

    val loadError by container.repository.loadError.collectAsStateWithLifecycle()
    if (loadError != null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp), contentAlignment = Alignment.Center) {
            Column { Text("Données indisponibles", style = MaterialTheme.typography.headlineSmall); Text(loadError!!) }
        }
        return
    }
    val today by todayViewModel.uiState.collectAsStateWithLifecycle()
    val pronote by pronoteViewModel.controller.uiState.collectAsStateWithLifecycle()
    val syncInfo by pronoteViewModel.syncInfo.collectAsStateWithLifecycle()
    val planning by planningViewModel.uiState.collectAsStateWithLifecycle()
    val courses by coursesViewModel.uiState.collectAsStateWithLifecycle()
    val stats by statsViewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val notebook by notebookViewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    // Document ouvert dans la fiche de séance ou de cours (visionneuse poussée dans la feuille).
    var sheetViewer by remember { mutableStateOf<SheetDocument?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val celebration = remember { CelebrationState() }

    val haptics = LocalHapticFeedback.current

    // Une seule bulle à la fois ; une validation réussie donne un léger retour haptique (réglage système respecté).
    suspend fun toast(visuals: ToastVisuals) {
        snackbar.currentSnackbarData?.dismiss()
        if (visuals.tone == ToastTone.SUCCESS) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        snackbar.showSnackbar(visuals)
    }

    LaunchedEffect(todayViewModel) {
        todayViewModel.eventFlow.collect { event ->
            when (event) {
                TodayEvent.Confetti -> celebration.confetti()
                TodayEvent.Fireworks -> celebration.fireworks()
                is TodayEvent.Toast -> toast(ToastVisuals(event.title, event.message, if (event.warning) ToastTone.WARNING else ToastTone.SUCCESS))
            }
        }
    }
    val platform = LocalPlatform.current
    val noApp = "Aucune app de ${platform.device} ne sait ouvrir ce document."
    LaunchedEffect(notebookViewModel) {
        notebookViewModel.eventFlow.collect { event ->
            when (event) {
                is NotebookEvent.Open -> if (!platform.openFile(event.file)) {
                    snackbar.showSnackbar(ToastVisuals("Document", noApp, ToastTone.WARNING))
                }
                // Pièce jointe demandée depuis une fiche : visionneuse dans la feuille si elle est encore ouverte.
                is NotebookEvent.Preview -> if (today?.session != null || courses?.detail != null) {
                    val preview = CourseDocument("notebook-preview", event.name, DocType.mimeOf(event.name, null), event.file.length(), "")
                    sheetViewer = SheetDocument(preview, event.file)
                } else if (!platform.openFile(event.file)) {
                    snackbar.showSnackbar(ToastVisuals("Document", noApp, ToastTone.WARNING))
                }
                is NotebookEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(ToastVisuals("Cahier de textes", event.text, ToastTone.INFO))
                }
                is NotebookEvent.CreateCourse -> coursesViewModel.openFromNotebook(event.draft)
                is NotebookEvent.ViewCourse -> {
                    notebookViewModel.close()
                    tab = Tab.COURSES
                    coursesViewModel.openDetail(event.courseId)
                }
            }
        }
    }
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.eventFlow.collect { event ->
            if (event is SettingsEvent.Toast) {
                toast(ToastVisuals(event.title, event.message, if (event.warning) ToastTone.WARNING else ToastTone.SUCCESS))
            }
        }
    }
    LaunchedEffect(coursesViewModel) {
        coursesViewModel.eventFlow.collect { event ->
            when (event) {
                is CoursesEvent.Toast -> toast(ToastVisuals(event.title, event.message, if (event.info) ToastTone.INFO else ToastTone.SUCCESS))
            }
        }
    }

    // Retour système : depuis un autre onglet, revient d’abord à Aujourd’hui.
    SystemBackHandler(enabled = tab != Tab.TODAY && (pronote.access == AccessPhase.CONNECTED || pronote.access == AccessPhase.OFFLINE)) { tab = Tab.TODAY }

    // Retour au premier plan : reconnexion ou synchronisation si la dernière date de plus de 6 h.
    val lifecycleScope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        lifecycleScope.launch {
            container.repository.tryOpenRevisionDay()?.let { toast(ToastVisuals("Journée non enregistrée", it, ToastTone.WARNING)) }
            pronoteViewModel.controller.onForeground()
        }
    }
    // Ordinateur : l’app reste souvent ouverte ; même règle qu’au retour au premier plan, vérifiée
    // toutes les 15 minutes (synchronisation si la dernière date de plus de 6 h). Aucun service système.
    if (platform.isDesktop) {
        LaunchedEffect(pronoteViewModel) {
            while (true) {
                kotlinx.coroutines.delay(15 * 60_000L)
                // Fenêtre réduite après minuit : la journée est figée avant que la synchronisation ne la modifie.
                container.repository.tryOpenRevisionDay()
                pronoteViewModel.controller.onForeground()
            }
        }
    }

    // Document de fiche ouvert dans la feuille, le panneau ou le volet de détail.
    val openDocument: (CourseDocument) -> Unit = { document ->
        container.courseFiles.fileOf(document)?.takeIf { it.isFile }?.let { sheetViewer = SheetDocument(document, it) }
    }

    /** Fiche de séance (section 8), ouverte depuis Aujourd’hui, le Planning ou la fiche d’un cours. */
    val sessionSheet: @Composable () -> Unit = {
        today?.let { state ->
            state.session?.let { session ->
                SessionSheetHost(
                    session, state, notebook, pronote, container.courseFiles, sheetViewer,
                    todayViewModel, notebookViewModel, coursesViewModel,
                    onOpenDocument = openDocument,
                    onCloseViewer = { sheetViewer = null },
                    onBackToToday = {
                        sheetViewer = null
                        todayViewModel.closeSheets()
                        tab = Tab.TODAY
                    },
                    onDismiss = {
                        sheetViewer = null
                        todayViewModel.closeSheets()
                    },
                )
            }
        }
    }

    /** Fiche d’un cours (historique des J, documents, cahier de textes). */
    val courseSheet: @Composable () -> Unit = {
        courses?.detail?.let { detail ->
            val course = detail.course
            LaunchedEffect(course.id, course.source != null) {
                if (pronote.access == AccessPhase.CONNECTED) notebookViewModel.ensureRead() // cahier (8.1) et documents proches (8.5)
            }
            val documents = remember(course.documents) { course.documents.orEmpty().map { it to container.courseFiles.exists(it) } }
            CourseDetailSheet(
                detail,
                onDismiss = {
                    sheetViewer = null
                    coursesViewModel.closeDetail()
                },
                // « Valider » ouvre la fiche de la séance (section 8.4).
                onValidate = { revisionId ->
                    sheetViewer = null
                    coursesViewModel.closeDetail()
                    todayViewModel.openSession(revisionId)
                },
                onDelete = { coursesViewModel.delete(course.id) },
                onRenameReference = { coursesViewModel.renameReference(course.id, it) },
                onOpenNotebook = detail.subject?.let { subject ->
                    {
                        coursesViewModel.closeDetail()
                        notebookViewModel.open(subject.id)
                    }
                },
                documents = documents,
                onOpenDocument = openDocument,
                onRedownload = if (course.source != null || course.documents.orEmpty().any { it.sourceSessionKey != null }) {
                    { document -> notebookViewModel.redownload(course, document) }
                } else {
                    null
                },
                notebookPart = RevisionSession.notebookPart(course, notebook?.state?.snapshot),
                notebookLoading = notebook?.state?.loading == true,
                downloadingId = notebook?.state?.downloadingId,
                onDownloadAttachment = { notebookViewModel.download(it, inApp = true) },
                onLink = { platform.openLink(it) },
                viewer = sheetViewer,
                onCloseViewer = { sheetViewer = null },
                nearby = today?.document?.let { doc ->
                    RevisionSession.nearbyDocuments(course, doc.courses, notebook?.state?.snapshot, doc.subjects, container.courseFiles::exists)
                }.orEmpty(),
                onOpenNearby = { item -> openNearby(item, openDocument, notebookViewModel) },
                onAttachNearby = { item -> attachNearby(course.id, item, coursesViewModel, notebookViewModel) },
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // La page officielle reste montée pendant toute la tentative (écran de connexion compris),
        // sous le contenu quand elle est masquée.
        if (pronote.browserStarted) {
            platform.OfficialPageLayer(pronoteViewModel, pronote, Modifier.zIndex(if (pronote.showBrowser) 2f else -1f))
        }
        when (pronote.access) {
            AccessPhase.CHECKING -> LoadingScreen(onSkip = pronoteViewModel.controller::skipResume)
            AccessPhase.SIGNED_OUT -> LoginScreen(pronoteViewModel, pronote)
            AccessPhase.CONNECTED, AccessPhase.OFFLINE -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val layout = LocalWindowLayout.current
                val navigation = layout.navigationMode
                val sideNavigation = navigation != NavigationMode.BOTTOM
                val twoPane = layout.twoPane
                val dockCourse = twoPane && tab == Tab.COURSES && courses?.detail != null
                val dockSession = twoPane && !dockCourse && (tab == Tab.TODAY || tab == Tab.PLANNING) && today?.session != null
                val docked = dockCourse || dockSession
                val pane = if (docked) layout.detailWidth else 0.dp
                val available = layout.listWidth(docked)
                val courseState = rememberSaveableStateHolder()
                val sessionState = rememberSaveableStateHolder()
                val tabs = rememberSaveableStateHolder()
                val badge = today?.model?.badgeCount ?: 0
                val tall = maxHeight >= 560.dp
                // Changer d’onglet ferme la fiche ancrée qui n’y a plus sa place (comme une feuille sur téléphone,
                // qui masque la barre d’onglets tant qu’elle est ouverte).
                val selectTab: (Tab) -> Unit = { next ->
                    if (dockCourse && next != Tab.COURSES) {
                        sheetViewer = null
                        coursesViewModel.closeDetail()
                    }
                    if (dockSession && next != Tab.TODAY && next != Tab.PLANNING) {
                        sheetViewer = null
                        todayViewModel.closeSheets()
                    }
                    tab = next
                }
                Row(Modifier.fillMaxSize()) {
                    // Marque en tête du rail seulement si la hauteur le permet (téléphone en paysage : non).
                    when (navigation) {
                        NavigationMode.BOTTOM -> Unit
                        NavigationMode.RAIL -> AppNavigationRail(tab, badge, showBrand = tall, onSelect = selectTab)
                        NavigationMode.SIDEBAR -> AppNavigationSidebar(tab, badge, onSelect = selectTab)
                    }
                    CompositionLocalProvider(LocalContentWidth provides available) {
                        Scaffold(
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.background,
                            contentWindowInsets = if (sideNavigation) {
                                ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                            } else {
                                ScaffoldDefaults.contentWindowInsets
                            },
                            topBar = {
                                if (pronote.access == AccessPhase.OFFLINE || pronote.reconnecting) OfflineBanner(pronote, pronoteViewModel.controller::retry)
                            },
                            snackbarHost = { SnackbarHost(snackbar) { data -> ToastSnackbar(data) } },
                            bottomBar = { if (!sideNavigation) AppNavigationBar(tab, badge, onSelect = selectTab) },
                            floatingActionButton = {
                                AnimatedVisibility(
                                    visible = tab == Tab.COURSES && courses?.hasCourses == true,
                                    enter = fadeIn(motion()) + scaleIn(motion(), initialScale = 0.9f),
                                    exit = fadeOut(motion(120)) + scaleOut(motion(120), targetScale = 0.9f),
                                ) {
                                    ExtendedFloatingActionButton(
                                        onClick = { coursesViewModel.openCreate() },
                                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                        text = { Text("Nouveau cours") },
                                        shape = JShapes.inner,
                                        containerColor = JColors.Violet700,
                                        contentColor = Color.White,
                                        // Le libellé animé du bouton étendu n’est pas exposé à TalkBack : nom explicite.
                                        // Deux volets : le bouton reste sur la liste, à gauche de la fiche.
                                        modifier = Modifier.padding(end = pane).semantics { contentDescription = "Nouveau cours" },
                                    )
                                }
                            },
                        ) { padding ->
                            // Changement d’onglet : fondu court (coupé si les animations du système sont désactivées).
                            Row(Modifier.fillMaxSize()) {
                                AnimatedContent(
                                    targetState = tab,
                                    transitionSpec = { fadeIn(motion(200)) togetherWith fadeOut(motion(90)) },
                                    label = "onglet",
                                    modifier = Modifier.weight(1f),
                                ) { current ->
                                    val multiColumn = available >= TwoColumnWidth && LocalDensity.current.fontScale <= 1.3f
                                    val capacity = when (current) {
                                        Tab.PLANNING -> 1440.dp
                                        Tab.COURSES -> if (multiColumn) 1320.dp else ReadingWidth
                                        Tab.TODAY, Tab.STATS, Tab.SETTINGS -> if (multiColumn) 1480.dp else ReadingWidth
                                    }
                                    val gutter = ((available - capacity) / 2).coerceAtLeast(0.dp)
                                    tabs.SaveableStateProvider(current.name) {
                                        CompositionLocalProvider(LocalScreenGutter provides gutter) {
                                            TabContent(
                                                current, padding, today, planning, courses, stats, settings, pronote, syncInfo,
                                                todayViewModel, planningViewModel, coursesViewModel, settingsViewModel, pronoteViewModel, notebookViewModel,
                                                onSelectTab = { tab = it },
                                                onReview = { revisionId ->
                                                    todayViewModel.openSession(revisionId)
                                                    val document = today?.document
                                                    val course = document?.revisions?.firstOrNull { it.id == revisionId }
                                                        ?.let { r -> document.courses.firstOrNull { it.id == r.courseId } }
                                                    sheetViewer = RevisionSession.principalDocument(course)?.let { principal ->
                                                        container.courseFiles.fileOf(principal)?.takeIf { it.isFile }?.let { SheetDocument(principal, it) }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                                if (pane > 0.dp) {
                                    VerticalDivider(color = JColors.Slate200)
                                    // Volet de détail : la fiche ouverte, à côté de la liste qui reste utilisable.
                                    Box(Modifier.width(DetailPaneWidth).fillMaxHeight().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
                                        CompositionLocalProvider(LocalSheetMode provides SheetMode.PANE) {
                                            if (dockCourse) {
                                                courseState.SaveableStateProvider("course") { courseSheet() }
                                            } else {
                                                sessionState.SaveableStateProvider("session") { sessionSheet() }
                                            }
                                        }
                                    }
                                }
                            }

                            // Fond opaque de la barre d’état : le contenu qui défile ne transparaît pas sous l’heure.
                            Box(
                                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                                    .background(MaterialTheme.colorScheme.background),
                            )

                            // Fiches ouvertes depuis n’importe quel onglet, sans changer d’onglet (section 8) ;
                            // feuilles sur téléphone, panneau latéral ou volet de détail sur grande fenêtre.
                            if (!dockSession && today?.session != null) sessionState.SaveableStateProvider("session") { sessionSheet() }
                            if (!dockCourse && courses?.detail != null) courseState.SaveableStateProvider("course") { courseSheet() }
                            LaunchedEffect(courses?.detail != null, today?.session != null) {
                                if (courses?.detail == null) courseState.removeState("course")
                                if (today?.session == null) sessionState.removeState("session")
                            }
                        }
                    }
                }
            }
        }
        val signedIn = pronote.access != AccessPhase.SIGNED_OUT && pronote.access != AccessPhase.CHECKING
        // Création d’un cours : plein écran au-dessus de l’app, sous la page officielle Pronote ; glisse depuis le bas.
        val formOpen = courses?.form?.open == true && signedIn
        AnimatedVisibility(
            visible = formOpen,
            enter = slideInVertically(motion(260)) { it / 6 } + fadeIn(motion(200)),
            exit = slideOutVertically(motion(200)) { it / 6 } + fadeOut(motion(160)),
            modifier = Modifier.zIndex(1f),
        ) {
            courses?.let { CourseFormScreen(it, coursesViewModel) }
        }
        // Cahier de textes : plein écran, lecture seule ; glisse depuis la droite comme une page.
        AnimatedVisibility(
            visible = notebook?.state?.open == true && signedIn && !formOpen,
            enter = slideInHorizontally(motion(260)) { it / 5 } + fadeIn(motion(200)),
            exit = slideOutHorizontally(motion(200)) { it / 5 } + fadeOut(motion(160)),
            modifier = Modifier.zIndex(1f),
        ) {
            notebook?.let { NotebookScreen(it, notebookViewModel, snackbar) }
        }
        CelebrationOverlay(celebration, Modifier.zIndex(3f))
    }
}

/** Contenu d’un onglet et ses feuilles propres (détail du Planning, fiche d’un cours). */
@Composable
private fun TabContent(
    tab: Tab,
    padding: PaddingValues,
    today: TodayUiState?,
    planning: PlanningUiState?,
    courses: CoursesUiState?,
    stats: StatsModel?,
    settings: SettingsUiState?,
    pronote: PronoteUiState,
    syncInfo: PronoteSyncInfo?,
    todayViewModel: TodayViewModel,
    planningViewModel: PlanningViewModel,
    coursesViewModel: CoursesViewModel,
    settingsViewModel: SettingsViewModel,
    pronoteViewModel: PronoteViewModel,
    notebookViewModel: NotebookViewModel,
    onSelectTab: (Tab) -> Unit,
    onReview: (String) -> Unit,
) {
    when (tab) {
        Tab.TODAY -> {
            if (today == null) {
                Loading()
            } else {
                TodayScreen(
                    today, padding,
                    onScore = todayViewModel::openScore,
                    onDetail = { todayViewModel.openSession(it) },
                    onAddCourse = coursesViewModel::openCreateFrom,
                    onReview = onReview,
                    onViewCourse = coursesViewModel::openDetail,
                )
            }
        }
        Tab.PLANNING -> {
            if (planning == null) {
                Loading()
            } else {
                PlanningScreen(
                    planning, planningViewModel, padding,
                    onAddCourseFrom = coursesViewModel::openCreateFrom,
                    onCreateCourse = { coursesViewModel.openCreate() },
                    onOpenRevision = { todayViewModel.openSession(it) },
                )
            }
        }
        Tab.COURSES -> {
            if (courses == null) {
                Loading()
            } else {
                CoursesScreen(
                    courses, coursesViewModel, padding,
                    onShowPlanning = { onSelectTab(Tab.PLANNING) },
                    onOpenNotebook = { notebookViewModel.open() },
                )
            }
        }
        Tab.STATS -> stats?.let { StatsScreen(it, padding) } ?: Loading()
        Tab.SETTINGS -> settings?.let {
            val notebookState = notebookViewModel.uiState.collectAsStateWithLifecycle().value
            SettingsScreen(
                it, settingsViewModel, pronoteViewModel, pronote, syncInfo, padding,
                autoCreate = notebookState?.autoCreate == true,
                importOffer = notebookState?.state?.importOffer,
                onAutoCreateChange = notebookViewModel::setAutoCreate,
                onImportOffer = notebookViewModel::answerImportOffer,
            )
        } ?: Loading()
    }
}

/** Fiche de séance (section 8) : données du cours, du cahier de textes et de l’enchaînement. */
@Composable
private fun SessionSheetHost(
    session: SessionSheetState,
    state: TodayUiState,
    notebook: NotebookUiState?,
    pronote: PronoteUiState,
    courseFiles: CourseFiles,
    viewer: SheetDocument?,
    todayViewModel: TodayViewModel,
    notebookViewModel: NotebookViewModel,
    coursesViewModel: CoursesViewModel,
    onOpenDocument: (CourseDocument) -> Unit,
    onCloseViewer: () -> Unit,
    onBackToToday: () -> Unit,
    onDismiss: () -> Unit,
) {
    val platform = LocalPlatform.current
    val document = state.document
    val model = RevisionDetailModel.build(session.revisionId, document.courses, document.revisions, document.subjects)
    if (model == null) {
        // Séance ou cours disparu (suppression, restauration) : la fiche se ferme.
        LaunchedEffect(session.revisionId) { onDismiss() }
        return
    }
    val course = model.course
    LaunchedEffect(course.id, course.source != null) {
        if (pronote.access == AccessPhase.CONNECTED) notebookViewModel.ensureRead() // cahier (8.1) et documents proches (8.5)
    }
    val documents = model.members.flatMap { it.documents.orEmpty() }.map { it to courseFiles.exists(it) }
    val page = session.page
    val next = (page as? SessionPage.Result)?.let { result -> document.revisions.firstOrNull { it.id == result.nextRevisionId } }
    val remaining = state.model.due.size
    SessionSheet(
        state = session,
        model = model,
        settings = document.settings.copy(adaptiveEnabled = document.programmes.firstOrNull { it.id == model.revision.programmeId }?.let { it.adaptiveEnabled && !it.needsReview } ?: true),
        today = state.model.today,
        documents = documents,
        notebookParts = RevisionSession.notebookParts(model.members, notebook?.state?.snapshot),
        notebookLoading = notebook?.state?.loading == true,
        downloadingId = notebook?.state?.downloadingId,
        nextLabel = next?.let { RevisionSession.nextLabel(it, document.courses, document.subjects) },
        remainingLabel = if (remaining > 0) "Encore $remaining séance${if (remaining > 1) "s" else ""} au programme ce soir." else null,
        viewer = viewer,
        nearby = RevisionSession.nearbyDocuments(course, document.courses, notebook?.state?.snapshot, document.subjects, courseFiles::exists),
        onOpenNearby = { item -> openNearby(item, onOpenDocument, notebookViewModel) },
        onAttachNearby = { item -> attachNearby(course.id, item, coursesViewModel, notebookViewModel) },
        onOpenDocument = onOpenDocument,
        onCloseViewer = onCloseViewer,
        onRedownload = if (model.members.any { it.source != null || it.documents.orEmpty().any { doc -> doc.sourceSessionKey != null } }) {
            { doc -> notebookViewModel.redownload(model.members.firstOrNull { leaf -> leaf.documents.orEmpty().any { it.id == doc.id } } ?: course, doc) }
        } else {
            null
        },
        onDownloadAttachment = { notebookViewModel.download(it, inApp = true) },
        onLink = { platform.openLink(it) },
        onPage = todayViewModel::showPage,
        onSubmit = { score -> todayViewModel.submitScore(session.revisionId, score) },
        onNext = { id ->
            onCloseViewer()
            todayViewModel.openSession(id)
        },
        onBackToToday = onBackToToday,
        onDismiss = onDismiss,
    )
}

/** Document proche : ouvert dans la feuille (téléchargé au besoin depuis le cahier). */
private fun openNearby(item: RevisionSession.Nearby, onOpenDocument: (CourseDocument) -> Unit, notebookViewModel: NotebookViewModel) {
    when (item) {
        is RevisionSession.Nearby.FromCourse -> onOpenDocument(item.document)
        is RevisionSession.Nearby.FromNotebook -> notebookViewModel.download(item.attachment, inApp = true)
    }
}

/** « Joindre à ce cours » : copie du document, l’autre cours n’est pas modifié. */
private fun attachNearby(courseId: String, item: RevisionSession.Nearby, coursesViewModel: CoursesViewModel, notebookViewModel: NotebookViewModel) {
    when (item) {
        is RevisionSession.Nearby.FromCourse -> coursesViewModel.attachFromCourse(courseId, item.document)
        is RevisionSession.Nearby.FromNotebook -> notebookViewModel.attachToCourse(courseId, item.sessionKey, item.attachment)
    }
}
