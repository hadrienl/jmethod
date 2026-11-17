package fr.jmethod.app.ui

import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.jmethod.app.JMethodApplication
import fr.jmethod.app.ui.pronote.OfficialPageLayer
import fr.jmethod.app.ui.pronote.PronoteViewModel
import fr.jmethod.app.ui.pronote.SettingsScreen
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.today.RevisionDetailSheet
import fr.jmethod.app.ui.today.ScoreSheet
import fr.jmethod.app.ui.today.TodayEvent
import fr.jmethod.app.ui.today.TodayScreen
import fr.jmethod.app.ui.today.TodayViewModel

/** Onglets livrés au jalon 1 uniquement. */
private enum class Tab { TODAY, SETTINGS }

private class ToastVisuals(val title: String, override val message: String, val warning: Boolean) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = SnackbarDuration.Long
}

@Composable
fun JMethodApp() {
    val application = LocalContext.current.applicationContext as JMethodApplication
    val container = application.container
    val todayViewModel: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(container.repository, container.clock) } })
    val pronoteViewModel: PronoteViewModel = viewModel(factory = viewModelFactory { initializer { PronoteViewModel(application, container) } })

    val today by todayViewModel.uiState.collectAsStateWithLifecycle()
    val pronote by pronoteViewModel.controller.uiState.collectAsStateWithLifecycle()
    val syncInfo by pronoteViewModel.syncInfo.collectAsStateWithLifecycle()
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
                    snackbar.showSnackbar(ToastVisuals(event.title, event.message, event.warning))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // La page officielle reste montée pendant toute la tentative, sous le contenu quand elle est masquée.
        if (pronote.browserStarted) {
            OfficialPageLayer(pronoteViewModel, pronote, Modifier.zIndex(if (pronote.showBrowser) 2f else -1f))
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
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
                                Text(visuals.title, fontWeight = FontWeight.Bold, color = if (visuals.warning) JColors.Amber900 else JColors.Emerald800)
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
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Réglages") },
                        colors = colors,
                    )
                }
            },
        ) { padding ->
            when (tab) {
                Tab.TODAY -> {
                    val state = today
                    if (state == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        TodayScreen(state, padding, onScore = todayViewModel::openScore, onDetail = todayViewModel::openDetail)
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
                            val revision = document.revisions.firstOrNull { it.id == id }
                            val course = revision?.let { r -> document.courses.firstOrNull { it.id == r.courseId } }
                            if (revision != null && course != null) {
                                RevisionDetailSheet(
                                    revision, course, document.subjects.firstOrNull { it.id == course.subjectId }, document.revisions,
                                    onDismiss = todayViewModel::closeSheets,
                                    onValidate = { score -> todayViewModel.submitScore(revision.id, score, withConfetti = false) },
                                )
                            }
                        }
                    }
                }
                Tab.SETTINGS -> SettingsScreen(pronoteViewModel, pronote, syncInfo, padding)
            }
        }
        CelebrationOverlay(celebration, Modifier.zIndex(3f))
    }
}
