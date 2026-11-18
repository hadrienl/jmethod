package fr.jmethod.app.ui

import androidx.compose.foundation.background
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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExtendedFloatingActionButton
import fr.jmethod.app.domain.RevisionDetailModel
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.jmethod.app.JMethodApplication
import fr.jmethod.app.pronote.AccessPhase
import fr.jmethod.app.ui.pronote.LoadingScreen
import fr.jmethod.app.ui.pronote.LoginScreen
import fr.jmethod.app.ui.pronote.OfficialPageLayer
import fr.jmethod.app.ui.pronote.OfflineBanner
import fr.jmethod.app.ui.pronote.PronoteViewModel
import fr.jmethod.app.ui.notebook.NotebookScreen
import fr.jmethod.app.ui.notebook.NotebookViewModel
import fr.jmethod.app.ui.notebook.NotebookEvent
import fr.jmethod.app.ui.notebook.openFile
import fr.jmethod.app.ui.documents.DocumentViewerScreen
import fr.jmethod.app.ui.documents.openWith
import fr.jmethod.app.ui.settings.SettingsEvent
import fr.jmethod.app.ui.settings.SettingsScreen
import fr.jmethod.app.ui.settings.SettingsViewModel
import fr.jmethod.app.ui.stats.StatsScreen
import fr.jmethod.app.ui.stats.StatsViewModel
import androidx.compose.ui.unit.dp
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.today.RevisionDetailSheet
import fr.jmethod.app.ui.today.ScoreSheet
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

@Composable
private fun AppNavigationRail(current: Tab, badge: Int, onSelect: (Tab) -> Unit) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = JColors.Violet800,
        selectedTextColor = JColors.Violet800,
        indicatorColor = JColors.Violet100,
        unselectedIconColor = JColors.Slate600,
        unselectedTextColor = JColors.Slate600,
    )
    Row {
        // Pas d’en-tête : les cinq destinations doivent tenir dans la hauteur d’un téléphone en paysage.
        NavigationRail(containerColor = Color.White) {
            Spacer(Modifier.weight(1f))
            Destinations.forEach { destination ->
                NavigationRailItem(
                    selected = current == destination.tab,
                    onClick = { onSelect(destination.tab) },
                    icon = { NavIcon(destination, badge) },
                    label = { NavLabel(destination.label) },
                    colors = colors,
                    modifier = Modifier.semantics { contentDescription = spokenLabel(destination, badge) },
                )
            }
            Spacer(Modifier.weight(1f))
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

@Composable
fun JMethodApp() {
    val application = LocalContext.current.applicationContext as JMethodApplication
    val container = application.container
    val todayViewModel: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(container.repository, container.clock) } })
    val pronoteViewModel: PronoteViewModel = viewModel(factory = viewModelFactory { initializer { PronoteViewModel(application, container) } })
    val planningViewModel: PlanningViewModel = viewModel(factory = viewModelFactory { initializer { PlanningViewModel(container.repository, container.clock) } })
    val coursesViewModel: CoursesViewModel = viewModel(factory = viewModelFactory { initializer { CoursesViewModel(container.repository, container.clock, container.courseCreator, container.courseFiles) } })
    val statsViewModel: StatsViewModel = viewModel(factory = viewModelFactory { initializer { StatsViewModel(container.repository, container.clock) } })
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container.repository, container.clock) } })
    val notebookViewModel: NotebookViewModel = viewModel(
        factory = viewModelFactory { initializer { NotebookViewModel(pronoteViewModel.controller, container.repository, application.cacheDir, container.courseCreator, container.courseFiles, container.clock) } },
    )

    val today by todayViewModel.uiState.collectAsStateWithLifecycle()
    val pronote by pronoteViewModel.controller.uiState.collectAsStateWithLifecycle()
    val syncInfo by pronoteViewModel.syncInfo.collectAsStateWithLifecycle()
    val planning by planningViewModel.uiState.collectAsStateWithLifecycle()
    val courses by coursesViewModel.uiState.collectAsStateWithLifecycle()
    val stats by statsViewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val notebook by notebookViewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var viewing by remember { mutableStateOf<Pair<String, fr.jmethod.app.model.CourseDocument>?>(null) }
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
    val appContext = LocalContext.current
    LaunchedEffect(notebookViewModel) {
        notebookViewModel.eventFlow.collect { event ->
            when (event) {
                is NotebookEvent.Open -> if (!openFile(appContext, event.file)) {
                    snackbar.showSnackbar(ToastVisuals("Document", "Aucune app de ce téléphone ne sait ouvrir ce document.", ToastTone.WARNING))
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
    BackHandler(enabled = tab != Tab.TODAY && (pronote.access == AccessPhase.CONNECTED || pronote.access == AccessPhase.OFFLINE)) { tab = Tab.TODAY }

    // Retour au premier plan : reconnexion ou synchronisation si la dernière date de plus de 6 h.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { pronoteViewModel.controller.onForeground() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // La page officielle reste montée pendant toute la tentative (écran de connexion compris),
        // sous le contenu quand elle est masquée.
        if (pronote.browserStarted) {
            OfficialPageLayer(pronoteViewModel, pronote, Modifier.zIndex(if (pronote.showBrowser) 2f else -1f))
        }
        when (pronote.access) {
            AccessPhase.CHECKING -> LoadingScreen(onSkip = pronoteViewModel.controller::skipResume)
            AccessPhase.SIGNED_OUT -> LoginScreen(pronoteViewModel, pronote)
            AccessPhase.CONNECTED, AccessPhase.OFFLINE -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // Paysage ou grand écran : rail latéral et colonne de lecture centrée.
                val rail = maxWidth >= 600.dp && maxWidth > maxHeight
                val available = maxWidth - if (rail) 81.dp else 0.dp
                val gutter = ((available - ReadingWidth) / 2).coerceAtLeast(0.dp)
                val badge = today?.model?.badgeCount ?: 0
                Row(Modifier.fillMaxSize()) {
                    if (rail) AppNavigationRail(tab, badge) { tab = it }
                    CompositionLocalProvider(LocalScreenGutter provides gutter) {
                        Scaffold(
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.background,
                            contentWindowInsets = if (rail) {
                                ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                            } else {
                                ScaffoldDefaults.contentWindowInsets
                            },
                            topBar = {
                                if (pronote.access == AccessPhase.OFFLINE || pronote.reconnecting) OfflineBanner(pronote, pronoteViewModel.controller::retry)
                            },
                            snackbarHost = { SnackbarHost(snackbar) { data -> ToastSnackbar(data) } },
                            bottomBar = { if (!rail) AppNavigationBar(tab, badge) { tab = it } },
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
                                        modifier = Modifier.semantics { contentDescription = "Nouveau cours" },
                                    )
                                }
                            },
                        ) { padding ->
                            // Changement d’onglet : fondu court (coupé si les animations du système sont désactivées).
                            AnimatedContent(
                                targetState = tab,
                                transitionSpec = { fadeIn(motion(200)) togetherWith fadeOut(motion(90)) },
                                label = "onglet",
                            ) { current ->
                                TabContent(
                                    current, padding, today, planning, courses, stats, settings, pronote, syncInfo,
                                    todayViewModel, planningViewModel, coursesViewModel, settingsViewModel, pronoteViewModel, notebookViewModel,
                                    onSelectTab = { tab = it },
                                    courseFiles = container.courseFiles,
                                    onViewDocument = { courseId, document ->
                                        coursesViewModel.closeDetail()
                                        viewing = courseId to document
                                    },
                                )
                            }

                            // Fond opaque de la barre d’état : le contenu qui défile ne transparaît pas sous l’heure.
                            Box(
                                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                                    .background(MaterialTheme.colorScheme.background),
                            )

                            // Feuilles d’Aujourd’hui ouvertes depuis n’importe quel onglet (validation depuis la fiche d’un cours).
                            today?.let { state ->
                                val document = state.document
                                state.scoringRevisionId?.let { id ->
                                    val revision = document.revisions.firstOrNull { it.id == id }
                                    val course = revision?.let { r -> document.courses.firstOrNull { it.id == r.courseId } }
                                    if (revision != null && course != null) {
                                        ScoreSheet(
                                            revision, course, document.subjects.firstOrNull { it.id == course.subjectId }, document.settings,
                                            onDismiss = todayViewModel::closeSheets,
                                            onSubmit = { score -> todayViewModel.submitScore(revision.id, score, withConfetti = true) },
                                        )
                                    }
                                }
                                state.detailRevisionId?.let { id ->
                                    RevisionDetailModel.build(id, document.courses, document.revisions, document.subjects)?.let { detail ->
                                        RevisionDetailSheet(
                                            detail,
                                            onDismiss = todayViewModel::closeSheets,
                                            onValidate = { score -> todayViewModel.submitScore(detail.revision.id, score, withConfetti = false) },
                                        )
                                    }
                                }
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
        // Document d’un cours : visionneuse dans l’app ; la fiche du cours se rouvre à la fermeture.
        viewing?.let { (courseId, document) ->
            val file = container.courseFiles.fileOf(document)
            if (file != null && file.isFile) {
                DocumentViewerScreen(document, file, onClose = {
                    viewing = null
                    coursesViewModel.openDetail(courseId)
                }, modifier = Modifier.zIndex(1.5f))
            } else {
                viewing = null
            }
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
    courseFiles: fr.jmethod.app.documents.CourseFiles,
    onViewDocument: (courseId: String, document: fr.jmethod.app.model.CourseDocument) -> Unit,
) {
    val context = LocalContext.current
    when (tab) {
        Tab.TODAY -> {
            if (today == null) {
                Loading()
            } else {
                TodayScreen(
                    today, padding,
                    onScore = todayViewModel::openScore,
                    onDetail = todayViewModel::openDetail,
                    onAddCourse = coursesViewModel::openCreateFrom,
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
                )
                planning.detail?.let { detail ->
                    RevisionDetailSheet(
                        detail,
                        onDismiss = planningViewModel::closeDetail,
                        onValidate = { score ->
                            planningViewModel.closeDetail()
                            todayViewModel.submitScore(detail.revision.id, score, withConfetti = false)
                        },
                    )
                }
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
                courses.detail?.let { detail ->
                    CourseDetailSheet(
                        detail,
                        onDismiss = coursesViewModel::closeDetail,
                        // Comme le web : la fiche se ferme et l’autoévaluation s’ouvre.
                        onValidate = { revisionId ->
                            coursesViewModel.closeDetail()
                            todayViewModel.openScore(revisionId)
                        },
                        onDelete = { coursesViewModel.delete(detail.course.id) },
                        onOpenNotebook = detail.subject?.let { subject ->
                            {
                                coursesViewModel.closeDetail()
                                notebookViewModel.open(subject.id)
                            }
                        },
                        documents = courses.detailDocuments,
                        onOpenDocument = { document -> onViewDocument(detail.course.id, document) },
                        onShareDocument = { document -> courseFiles.fileOf(document)?.let { openWith(context, it, document.name) } },
                        onRedownload = if (detail.course.source != null) {
                            { document -> notebookViewModel.redownload(detail.course, document) }
                        } else {
                            null
                        },
                    )
                }
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
