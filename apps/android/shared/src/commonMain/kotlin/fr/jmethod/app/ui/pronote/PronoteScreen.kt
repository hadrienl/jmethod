package fr.jmethod.app.ui.pronote

import fr.jmethod.app.ui.components.ScrollbarBox
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
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fr.jmethod.app.pronote.AccessPhase
import fr.jmethod.app.pronote.AccountType
import fr.jmethod.app.pronote.PronoteUiState
import fr.jmethod.app.ui.platform.LocalPlatform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.components.BrandMark
import fr.jmethod.app.ui.components.SectionTitle
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardShape = JShapes.card

/** Icônes œil (tracés Material « visibility » et « visibility_off »), sans dépendance aux icônes étendues. */
private fun eyeVector(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

private val EyeIcon = eyeVector(
    "Eye",
    "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
)

private val EyeOffIcon = eyeVector(
    "EyeOff",
    "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
)

@Composable
private fun SectionCard(modifier: Modifier = Modifier, padding: androidx.compose.ui.unit.Dp = JSpacing.l, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, JColors.Slate200),
    ) { Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(JSpacing.m)) { content() } }
}

@Composable
private fun Brand() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BrandMark(72.dp)
        Spacer(Modifier.height(JSpacing.l))
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
        Spacer(Modifier.height(JSpacing.xxl))
        CircularProgressIndicator(color = JColors.Violet700, trackColor = JColors.Violet100, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(JSpacing.l))
        Text(
            "Connexion à Pronote…",
            style = MaterialTheme.typography.bodyLarge,
            color = JColors.Slate600,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (showSkip) {
            Spacer(Modifier.height(JSpacing.l))
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
    val platform = LocalPlatform.current
    Box(Modifier.fillMaxSize().background(JColors.Slate50), contentAlignment = Alignment.TopCenter) {
        val scrollState = rememberScrollState()
        ScrollbarBox(scrollState, Modifier.fillMaxSize()) {
        Column(
                Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxSize().verticalScroll(scrollState)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(JSpacing.l)
                    // Ordinateur : Entrée envoie le formulaire depuis n’importe quel champ (les boutons gardent la leur).
                    .onKeyEvent { event ->
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (platform.isDesktop && enter && event.type == KeyEventType.KeyUp && canSubmit) {
                            controller.submitCredentials()
                            true
                        } else {
                            false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(JSpacing.xl),
            ) {
                Spacer(Modifier.height(JSpacing.xl))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Brand() }
                SectionCard(padding = JSpacing.xl) {
                    Text("Connexion à Pronote", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    var showHelp by rememberSaveable { mutableStateOf(false) }
                    Column {
                        Text(
                            "Utilise ton identifiant et ton mot de passe Pronote, pas ceux d’ÉduConnect.",
                            style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
                        )
                        TextButton(
                            onClick = { showHelp = true },
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("En savoir plus", fontWeight = FontWeight.Bold) }
                    }
                    if (showHelp) SignInHelpSheet(onDismiss = { showHelp = false })
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
                    var passwordVisible by rememberSaveable { mutableStateOf(false) }
                    OutlinedTextField(
                        value = form.password,
                        onValueChange = controller::onPasswordChange,
                        label = { Text("Mot de passe") },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = if (passwordVisible && !form.passwordFromStore) VisualTransformation.None else PasswordVisualTransformation(),
                        // Un mot de passe enregistré n’est jamais révélable : l’œil n’existe que pour une saisie.
                        trailingIcon = if (form.passwordFromStore) null else {
                            {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) EyeOffIcon else EyeIcon,
                                        contentDescription = if (passwordVisible) "Masquer le mot de passe" else "Afficher le mot de passe",
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (canSubmit) controller.submitCredentials() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    )
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = form.stayConnected, enabled = !working, role = Role.Switch, onValueChange = controller::onStayConnectedChange),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Rester connecté", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = form.stayConnected, onCheckedChange = null, enabled = !working)
                    }
                    if (form.stayConnected) {
                        Text(
                            "Ton identifiant et ton mot de passe sont enregistrés de façon chiffrée, uniquement sur ${platform.device}, pour te reconnecter sans les ressaisir.",
                            style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
                        )
                    }
                    Button(
                        onClick = controller::submitCredentials,
                        enabled = canSubmit,
                        shape = JShapes.control,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        if (working) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = JColors.Violet700)
                            Spacer(Modifier.size(JSpacing.m))
                            Text("Connexion…")
                        } else {
                            Text("Se connecter", fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(onClick = controller::useLocally, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Continuer sans Pronote") }
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
/** Compte Pronote en tête des Réglages : synchronisation, enfant suivi, déconnexion. */
fun PronoteAccountSection(viewModel: PronoteViewModel, state: PronoteUiState, syncInfo: PronoteSyncInfo?) {
    val controller = viewModel.controller
    val platform = LocalPlatform.current
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.m)) {
        SectionTitle("Compte Pronote", Modifier.padding(top = JSpacing.s))

        if ((state.busy || state.isError) && state.access == AccessPhase.CONNECTED) {
            Column(
                Modifier.fillMaxWidth().background(if (state.isError) JColors.Rose50 else JColors.Violet50, JShapes.inner).padding(JSpacing.l)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(state.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (state.isError) JColors.Rose800 else JColors.Violet900)
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
                if (state.busy) {
                    Spacer(Modifier.height(JSpacing.m))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = JColors.Violet700, trackColor = JColors.Violet100)
                }
            }
        }

        SectionCard {
            val account = state.account
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(JColors.Violet50), contentAlignment = Alignment.Center) {
                    Icon(JIcons.School, contentDescription = null, tint = JColors.Violet700, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(JSpacing.m))
                Text(account?.name ?: "Compte enregistré", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
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
                Text("Enfant", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s)) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s), verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                Button(onClick = controller::retry, enabled = !state.busy, shape = JShapes.control, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(JSpacing.s))
                    Text("Actualiser")
                }
                OutlinedButton(onClick = { confirmDisconnect = true }, shape = JShapes.control, modifier = Modifier.heightIn(min = 48.dp)) { Text("Se déconnecter") }
            }
        }

        Text(
            "J-Method lit ton emploi du temps et ton cahier de textes sans rien modifier dans Pronote. " + if (state.hasStoredPassword) {
                "Ton identifiant et ton mot de passe sont enregistrés de façon chiffrée, uniquement sur ${platform.device}."
            } else if (platform.isDesktop) {
                "Ton mot de passe n’est pas enregistré : il te sera demandé au prochain lancement de l’app."
            } else {
                "Ton mot de passe n’est pas enregistré : seule ta connexion à Pronote est gardée, de façon chiffrée, sur ${platform.device}."
            },
            style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600,
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Se déconnecter de Pronote ?") },
            text = {
                Text(
                    "Ton emploi du temps importé sera retiré de ${platform.device}. Tes cours et tes révisions restent enregistrés. " +
                        if (state.hasStoredPassword) {
                            "Tes identifiants restent enregistrés pour préremplir la connexion, sauf si tu choisis de les oublier."
                        } else {
                            "L’établissement reste prérempli, sauf si tu choisis de l’oublier."
                        },
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { confirmDisconnect = false; controller.disconnect() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Se déconnecter")
                    }
                    TextButton(onClick = { confirmDisconnect = false; controller.disconnect(forgetCredentials = true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Se déconnecter et oublier mes identifiants")
                    }
                    TextButton(onClick = { confirmDisconnect = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
                }
            },
        )
    }
}
