package fr.jmethod.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.List
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

/** Onglets des jalons livrés (1 à 5). */
/** Icône « progrès » : trois barres (pas d’icône de graphique dans le jeu d’icônes de base). */
private val StatsIcon = androidx.compose.ui.graphics.vector.ImageVector.Builder("Stats", 24.dp, 24.dp, 24f, 24f)
    .addPath(androidx.compose.ui.graphics.vector.addPathNodes("M4 13h4v7H4zM10 8h4v12h-4zM16 4h4v16h-4z"), fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black))
    .build()

private enum class Tab { TODAY, PLANNING, COURSES, STATS, SETTINGS }

private enum class ToastTone { SUCCESS, WARNING, INFO }

private class ToastVisuals(val title: String, override val message: String, val tone: ToastTone) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = SnackbarDuration.Long
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun JMethodApp() {
    val application = LocalContext.current.applicationContext as JMethodApplication
    val container = application.container
    val todayViewModel: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(container.repository, container.clock) } })
    val pronoteViewModel: PronoteViewModel = viewModel(factory = viewModelFactory { initializer { PronoteViewModel(application, container) } })
    val planningViewModel: PlanningViewModel = viewModel(factory = viewModelFactory { initializer { PlanningViewModel(container.repository, container.clock) } })
    val coursesViewModel: CoursesViewModel = viewModel(factory = viewModelFactory { initializer { CoursesViewModel(container.repository, container.clock) } })
    val statsViewModel: StatsViewModel = viewModel(factory = viewModelFactory { initializer { StatsViewModel(container.repository, container.clock) } })
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container.repository, container.clock) } })
    val notebookViewModel: NotebookViewModel = viewModel(
        factory = viewModelFactory { initializer { NotebookViewModel(pronoteViewModel.controller, container.repository, application.cacheDir) } },
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
    val snackbar = remember { SnackbarHostState() }
    val celebration = remember { CelebrationState() }

    LaunchedEffect(todayViewModel) {
        todayViewModel.eventFlow.collect { event ->
            when (event) {
                TodayEvent.Confetti -> celebration.confetti()
                TodayEvent.Fireworks -> celebration.fireworks()
                is TodayEvent.Toast -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(ToastVisuals(event.title, event.message, if (event.warning) ToastTone.WARNING else ToastTone.SUCCESS))
                }
            }
        }
    }
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.eventFlow.collect { event ->
            if (event is SettingsEvent.Toast) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(ToastVisuals(event.title, event.message, if (event.warning) ToastTone.WARNING else ToastTone.SUCCESS))
            }
        }
    }
    LaunchedEffect(coursesViewModel) {
        coursesViewModel.eventFlow.collect { event ->
            when (event) {
                is CoursesEvent.Toast -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(ToastVisuals(event.title, event.message, if (event.info) ToastTone.INFO else ToastTone.SUCCESS))
                }
            }
        }
    }

    // Retour système : depuis un autre onglet, revient d’abord à Aujourd’hui.
    BackHandler(enabled = tab != Tab.TODAY && (pronote.access == AccessPhase.CONNECTED || pronote.access == AccessPhase.OFFLINE)) { tab = Tab.TODAY }

    // Retour au premier plan : reconnexion ou synchronisation si la dernière date de plus de 6 h.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { pronoteViewModel.controller.onForeground() }

    Box(Modifier.fillMaxSize()) {
        // La page officielle reste montée pendant toute la tentative (écran de connexion compris),
        // sous le contenu quand elle est masquée.
        if (pronote.browserStarted) {
            OfficialPageLayer(pronoteViewModel, pronote, Modifier.zIndex(if (pronote.showBrowser) 2f else -1f))
        }
        when (pronote.access) {
            AccessPhase.CHECKING -> LoadingScreen(onSkip = pronoteViewModel.controller::skipResume)
            AccessPhase.SIGNED_OUT -> LoginScreen(pronoteViewModel, pronote)
            AccessPhase.CONNECTED, AccessPhase.OFFLINE -> {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        if (pronote.access == AccessPhase.OFFLINE || pronote.reconnecting) OfflineBanner(pronote, pronoteViewModel.controller::retry)
                    },
                    snackbarHost = {
                        SnackbarHost(snackbar) { data ->
                            val visuals = data.visuals as? ToastVisuals
                            Snackbar(
                                containerColor = androidx.compose.ui.graphics.Color.White,
                                contentColor = JColors.Slate900,
                                dismissAction = {
                                    androidx.compose.material3.TextButton(onClick = { data.dismiss() }) { Text("Fermer") }
                                },
                            ) {
                                Column {
                                    if (visuals != null) {
                                        Text(
                                            visuals.title,
                                            fontWeight = FontWeight.Bold,
                                            color = when (visuals.tone) {
                                                ToastTone.WARNING -> JColors.Amber900
                                                ToastTone.SUCCESS -> JColors.Emerald800
                                                ToastTone.INFO -> JColors.Violet800
                                            },
                                        )
                                    }
                                    Text(data.visuals.message)
                                }
                            }
                        }
                    },
                    bottomBar = {
                        NavigationBar(containerColor = androidx.compose.ui.graphics.Color.White) {
                            val badge = today?.model?.badgeCount ?: 0
                            val colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = JColors.Violet800,
                                selectedTextColor = JColors.Violet800,
                                indicatorColor = JColors.Violet50,
                                unselectedIconColor = JColors.Slate600,
                                unselectedTextColor = JColors.Slate600,
                            )
                            NavigationBarItem(
                                selected = tab == Tab.TODAY,
                                onClick = { tab = Tab.TODAY },
                                icon = {
                                    BadgedBox(badge = {
                                        if (badge > 0) {
                                            Badge(containerColor = JColors.Violet200, contentColor = JColors.Violet900) { Text("$badge") }
                                        }
                                    }) { Icon(Icons.Filled.Home, contentDescription = null) }
                                },
                                label = { Text("Aujourd’hui") },
                                colors = colors,
                                modifier = Modifier.semantics {
                                    contentDescription = if (badge > 0) "Aujourd’hui, $badge séance${if (badge > 1) "s" else ""} à faire" else "Aujourd’hui"
                                },
                            )
                            NavigationBarItem(
                                selected = tab == Tab.PLANNING,
                                onClick = { tab = Tab.PLANNING },
                                icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                                label = { Text("Planning") },
                                colors = colors,
                            )
                            NavigationBarItem(
                                selected = tab == Tab.COURSES,
                                onClick = { tab = Tab.COURSES },
                                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                label = { Text("Cours") },
                                modifier = Modifier.semantics { contentDescription = "Mes cours" },
                                colors = colors,
                            )
                            NavigationBarItem(
                                selected = tab == Tab.STATS,
                                onClick = { tab = Tab.STATS },
                                icon = { Icon(StatsIcon, contentDescription = null) },
                                label = { Text("Progrès") },
                                colors = colors,
                                modifier = Modifier.semantics { contentDescription = "Mes progrès" },
                            )
                            NavigationBarItem(
                                selected = tab == Tab.SETTINGS,
                                onClick = { tab = Tab.SETTINGS },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                label = { Text("Réglages") },
                                colors = colors,
                            )
                        }
                    },
                    floatingActionButton = {
                        if (tab == Tab.COURSES && courses?.hasCourses == true) {
                            ExtendedFloatingActionButton(
                                onClick = { coursesViewModel.openCreate() },
                                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                text = { Text("Nouveau cours", fontWeight = FontWeight.Bold) },
                                containerColor = JColors.Violet700,
                                contentColor = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                    },
                ) { padding ->
                    when (tab) {
                        Tab.TODAY -> {
                            val state = today
                            if (state == null) {
                                Loading()
                            } else {
                                TodayScreen(
                                    state, padding,
                                    onScore = todayViewModel::openScore,
                                    onDetail = todayViewModel::openDetail,
                                    onAddCourse = coursesViewModel::openCreateFrom,
                                )
                            }
                        }
                        Tab.PLANNING -> {
                            val state = planning
                            if (state == null) {
                                Loading()
                            } else {
                                PlanningScreen(
                                    state, planningViewModel, padding,
                                    onAddCourseFrom = coursesViewModel::openCreateFrom,
                                    onCreateCourse = { coursesViewModel.openCreate() },
                                )
                                state.detail?.let { detail ->
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
                            val state = courses
                            if (state == null) {
                                Loading()
                            } else {
                                CoursesScreen(
                                    state, coursesViewModel, padding,
                                    onShowPlanning = { tab = Tab.PLANNING },
                                    onOpenNotebook = { notebookViewModel.open() },
                                )
                                state.detail?.let { detail ->
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
                                    )
                                }
                            }
                        }
                        Tab.STATS -> stats?.let { StatsScreen(it, padding) } ?: Loading()
                        Tab.SETTINGS -> settings?.let { SettingsScreen(it, settingsViewModel, pronoteViewModel, pronote, syncInfo, padding) } ?: Loading()
                    }

                    // Fond de la barre d’état : le contenu qui défile ne passe plus sous l’heure (edge-to-edge).
                    Box(
                        Modifier.fillMaxWidth().windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.Companion.statusBars)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)),
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
        // Création d’un cours : plein écran au-dessus de l’app, sous la page officielle Pronote.
        val form = courses
        val signedIn = pronote.access != AccessPhase.SIGNED_OUT && pronote.access != AccessPhase.CHECKING
        if (form != null && form.form.open && signedIn) {
            CourseFormScreen(form, coursesViewModel, Modifier.zIndex(1f))
        }
        // Cahier de textes : plein écran, lecture seule.
        val reader = notebook
        if (reader != null && reader.state.open && signedIn && !(form?.form?.open ?: false)) {
            NotebookScreen(reader, notebookViewModel, Modifier.zIndex(1f))
        }
        CelebrationOverlay(celebration, Modifier.zIndex(3f))
    }
}
