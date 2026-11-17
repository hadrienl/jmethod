package fr.jmethod.app.ui.pronote

import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.jmethod.app.pronote.PronoteUiState
import fr.jmethod.app.ui.theme.JColors
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

private val syncFormat = DateTimeFormatter.ofPattern("d MMMM 'à' HH:mm", Locale.FRENCH).withZone(ZoneId.of("Europe/Paris"))

private fun formatSync(value: String?): String? = value?.let { runCatching { syncFormat.format(Instant.parse(it)) }.getOrNull() }

/** Onglet Réglages du jalon 1 : uniquement la connexion au compte Pronote. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: PronoteViewModel, state: PronoteUiState, syncInfo: PronoteSyncInfo?, contentPadding: PaddingValues) {
    val controller = viewModel.controller
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
            .padding(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
        Text("Compte Pronote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })

        StatusCard(state)

        val account = state.account
        if (account != null) {
            SectionCard {
                Text(account.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                formatSync(syncInfo?.lastSync)?.let {
                    Text("Dernière synchronisation le $it", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
                }
                if (syncInfo != null) {
                    Text(
                        "${syncInfo.eventCount} créneau${if (syncInfo.eventCount > 1) "x" else ""} sur les 14 prochains jours",
                        style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
                    )
                }
                if (account.children.size > 1) {
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
                    Button(onClick = { controller.refresh() }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Actualiser") }
                    OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Se déconnecter") }
                }
            }
        }

        if (state.browserStarted && !state.showBrowser) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.automationStopped && !state.busy) {
                    Button(onClick = { controller.resumeAutomation() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reprendre l’association") }
                }
                OutlinedButton(onClick = { controller.setBrowserVisible(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Afficher la page Pronote") }
                TextButton(onClick = { controller.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
            }
        } else if (state.busy && !state.browserStarted) {
            TextButton(onClick = { controller.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
        }

        if (account == null && !state.browserStarted && !state.busy) {
            LoginForm(viewModel, state)
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = JColors.Violet700)
                Spacer(Modifier.width(8.dp))
                Text("Tes données restent sur ce téléphone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                "J-Method lit seulement ton emploi du temps, sans rien modifier dans Pronote. Ton mot de passe n’est jamais enregistré : " +
                    "seul un jeton de connexion chiffré est gardé sur ce téléphone.",
                style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
            )
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Se déconnecter de Pronote ?") },
            text = { Text("Le jeton de connexion sera supprimé de ce téléphone. Les cours déjà importés restent affichés.") },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; controller.disconnect() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Se déconnecter") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun StatusCard(state: PronoteUiState) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (state.isError) JColors.Rose50 else JColors.Violet50, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(state.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (state.isError) JColors.Rose800 else JColors.Violet900)
        Spacer(Modifier.height(4.dp))
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
        if (state.busy) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = JColors.Violet700, trackColor = JColors.Violet100)
        }
    }
}

@Composable
private fun LoginForm(viewModel: PronoteViewModel, state: PronoteUiState) {
    val controller = viewModel.controller
    val form = state.form
    val canSubmit = !state.busy && form.address.isNotBlank() && form.username.isNotBlank() && form.password.isNotEmpty()
    SectionCard {
        Text("Connecter mon compte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = form.address,
            onValueChange = controller::onAddressChange,
            label = { Text("Adresse de ton espace Pronote") },
            supportingText = { Text("Par exemple : school.index-education.net") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.username,
            onValueChange = controller::onUsernameChange,
            label = { Text("Identifiant") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )
        OutlinedTextField(
            value = form.password,
            onValueChange = controller::onPasswordChange,
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) controller.submitCredentials() }),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )
        Text(
            "Tes identifiants sont transmis uniquement à la page officielle de Pronote et ne sont jamais enregistrés.",
            style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
        )
        Button(onClick = controller::submitCredentials, enabled = canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Se connecter")
        }
        OutlinedButton(onClick = controller::openOfficialPage, enabled = !state.busy && form.address.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Ouvrir la page Pronote officielle")
        }
        Text(
            "Utilise la page officielle si ton établissement passe par un ENT ou demande une vérification.",
            style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
        )
        if (state.hasSavedSession) {
            TextButton(onClick = controller::restoreSaved, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reprendre la session enregistrée") }
        }
    }
}

/**
 * Couche de la page officielle, placée à la racine pour ne jamais être détachée en changeant
 * d’onglet. Masquée, elle garde une taille non nulle sous le contenu, ignore les touchers et
 * reste hors de l’arbre d’accessibilité ; visible, elle couvre l’écran avec ses commandes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfficialPageLayer(viewModel: PronoteViewModel, state: PronoteUiState, modifier: Modifier = Modifier) {
    val controller = viewModel.controller
    val visible = state.showBrowser
    val activity = LocalContext.current
    BackHandler(enabled = visible) { controller.setBrowserVisible(false) }
    Column(
        modifier.fillMaxSize().background(if (visible) Color.White else Color.Transparent)
            .then(if (visible) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
    ) {
        if (visible) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = JColors.Slate600, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.automationStopped && !state.busy) {
                        TextButton(onClick = controller::resumeAutomation, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reprendre l’association") }
                    }
                    TextButton(onClick = { controller.setBrowserVisible(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Masquer la page") }
                    TextButton(onClick = { controller.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
                }
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
