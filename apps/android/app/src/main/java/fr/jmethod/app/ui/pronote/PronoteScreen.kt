package fr.jmethod.app.ui.pronote

import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.jmethod.app.pronote.AccessPhase
import fr.jmethod.app.pronote.AccountType
import fr.jmethod.app.pronote.PronoteUiState
import fr.jmethod.app.ui.theme.JColors
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardShape = RoundedCornerShape(24.dp)

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, JColors.Slate200),
    ) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}

@Composable
private fun Brand() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            buildAnnotatedString {
                append("J-")
                withStyle(SpanStyle(color = JColors.Violet700)) { append("Method") }
            },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() },
        )
        Text("Un peu chaque jour.", style = MaterialTheme.typography.bodyLarge, color = JColors.Slate600)
    }
}

/** Écran sobre affiché pendant la reprise de la connexion au lancement. */
@Composable
fun LoadingScreen(onSkip: () -> Unit) {
    var showSkip by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(8_000)
        showSkip = true
    }
    Column(
        Modifier.fillMaxSize().background(JColors.Slate50).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Brand()
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator(color = JColors.Violet700)
        Spacer(Modifier.height(16.dp))
        Text(
            "Connexion à Pronote…",
            style = MaterialTheme.typography.bodyLarge,
            color = JColors.Slate600,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (showSkip) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ouvrir sans attendre") }
        }
    }
}

/** Connexion classique plein écran, sans barre d’onglets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: PronoteViewModel, state: PronoteUiState) {
    val controller = viewModel.controller
    val form = state.form
    val working = state.busy || state.browserStarted
    val canSubmit = !working && form.school.isNotBlank() && form.username.isNotBlank() && form.password.isNotEmpty()
    Box(Modifier.fillMaxSize().background(JColors.Slate50), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Brand() }
            SectionCard {
                Text("Connexion à Pronote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                OutlinedTextField(
                    value = form.school,
                    onValueChange = controller::onSchoolChange,
                    label = { Text("Code ou adresse de l’établissement") },
                    placeholder = { Text("abcd1234") },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AccountType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = form.accountType == type,
                            onClick = { controller.onAccountTypeChange(type) },
                            enabled = !working,
                            shape = SegmentedButtonDefaults.itemShape(index, AccountType.entries.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(if (type == AccountType.STUDENT) "Élève" else "Parent") }
                    }
                }
                OutlinedTextField(
                    value = form.username,
                    onValueChange = controller::onUsernameChange,
                    label = { Text("Identifiant") },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = controller::onPasswordChange,
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) controller.submitCredentials() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
                Button(
                    onClick = controller::submitCredentials,
                    enabled = canSubmit,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    if (working) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = JColors.Violet700)
                        Spacer(Modifier.size(12.dp))
                        Text("Connexion…")
                    } else {
                        Text("Se connecter", fontWeight = FontWeight.Bold)
                    }
                }
                val feedback = if (working) state.message else state.formError
                if (feedback != null) {
                    Text(
                        feedback,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (working) JColors.Slate600 else JColors.Rose700,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** Bandeau hors ligne : l’app reste utilisable sur les données locales. */
@Composable
fun OfflineBanner(state: PronoteUiState, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(JColors.Amber50).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(state.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = JColors.Amber900)
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = JColors.Amber900)
        }
        if (state.busy) {
            CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp, color = JColors.Amber900)
        } else {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Réessayer", color = JColors.Amber900, fontWeight = FontWeight.Bold) }
        }
    }
}

private val syncFormat = DateTimeFormatter.ofPattern("d MMMM 'à' HH:mm", Locale.FRENCH).withZone(ZoneId.of("Europe/Paris"))

private fun formatSync(value: String?): String? = value?.let { runCatching { syncFormat.format(Instant.parse(it)) }.getOrNull() }

/** Onglet Réglages du jalon 1 : uniquement le compte Pronote. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: PronoteViewModel, state: PronoteUiState, syncInfo: PronoteSyncInfo?, contentPadding: PaddingValues) {
    val controller = viewModel.controller
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
        Text("Compte Pronote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })

        if ((state.busy || state.isError) && state.access == AccessPhase.CONNECTED) {
            Column(
                Modifier.fillMaxWidth().background(if (state.isError) JColors.Rose50 else JColors.Violet50, RoundedCornerShape(16.dp)).padding(16.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(state.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (state.isError) JColors.Rose800 else JColors.Violet900)
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
                if (state.busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = JColors.Violet700, trackColor = JColors.Violet100)
                }
            }
        }

        SectionCard {
            val account = state.account
            Text(account?.name ?: "Compte enregistré", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            formatSync(syncInfo?.lastSync)?.let {
                Text("Dernière synchronisation le $it", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
            }
            if (syncInfo != null) {
                Text(
                    "${syncInfo.eventCount} cours sur les 14 prochains jours",
                    style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
                )
            }
            if (account != null && account.children.size > 1) {
                Text("Enfant", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    account.children.forEach { child ->
                        FilterChip(
                            selected = account.selectedChildId == child.id,
                            onClick = { if (account.selectedChildId != child.id) controller.selectChild(child.id) },
                            enabled = !state.busy,
                            label = { Text(child.name) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = controller::retry, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Actualiser") }
                OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Se déconnecter") }
            }
        }

        Text(
            "J-Method lit ton emploi du temps sans rien modifier dans Pronote. Ton mot de passe n’est jamais enregistré sur ce téléphone.",
            style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Se déconnecter de Pronote ?") },
            text = { Text("Ton emploi du temps importé de Pronote sera retiré de ce téléphone. Tes cours et tes révisions restent enregistrés.") },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; controller.disconnect() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Se déconnecter") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
            },
        )
    }
}

/**
 * Couche de la page officielle, placée à la racine pour ne jamais être détachée (rotation, écran de
 * connexion ou onglets). Masquée, elle garde une taille non nulle sous le contenu, ignore les touchers
 * et reste hors de l’arbre d’accessibilité ; visible (ENT, vérification), elle couvre l’écran avec un
 * titre et un bouton Annuler.
 */
@Composable
fun OfficialPageLayer(viewModel: PronoteViewModel, state: PronoteUiState, modifier: Modifier = Modifier) {
    val controller = viewModel.controller
    val visible = state.showBrowser
    val activity = LocalContext.current
    BackHandler(enabled = visible) { controller.cancel() }
    Column(
        modifier.fillMaxSize().background(if (visible) Color.White else Color.Transparent)
            .then(if (visible) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
    ) {
        if (visible) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                }
                TextButton(onClick = { controller.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
            }
        }
        AndroidView(
            factory = { viewModel.page.attach(activity) },
            onRelease = { viewModel.page.detach(it) },
            update = { view ->
                view.alpha = if (visible) 1f else 0f
                view.isEnabled = visible
                view.isFocusable = visible
                view.isFocusableInTouchMode = visible
                view.importantForAccessibility =
                    if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                view.setOnTouchListener { _, _: MotionEvent -> !visible }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
