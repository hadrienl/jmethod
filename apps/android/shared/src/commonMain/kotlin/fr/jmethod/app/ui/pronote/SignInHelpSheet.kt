package fr.jmethod.app.ui.pronote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import fr.jmethod.app.ui.components.AdaptiveSheet
import fr.jmethod.app.ui.components.ScrollbarBox
import fr.jmethod.app.ui.components.SheetMode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing

/** Aide du formulaire de connexion (texte de référence : `native/shared/sign-in-help.md`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInHelpSheet(onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss, wideMode = SheetMode.DIALOG) {
        val scroll = rememberScrollState()
        ScrollbarBox(scroll) { Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).navigationBarsPadding()
                .padding(start = JSpacing.xl, end = JSpacing.xl, bottom = JSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(JSpacing.l),
        ) {
            Text("Se connecter à Pronote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })

            HelpSection("Le code de l’établissement") {
                Paragraph("Si tu passes d’habitude par l’ENT pour ouvrir Pronote, voici comment trouver le code :")
                Step(1, "Connecte-toi à l’ENT comme d’habitude, depuis un navigateur.")
                Step(2, "Ouvre Pronote depuis l’ENT.")
                Step(3, "Regarde l’adresse de la page Pronote. Sur téléphone, touche la barre d’adresse pour l’afficher en entier. Elle ressemble à :")
                Text(
                    "https://abcd1234.index-education.net/pronote/eleve.html",
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = JColors.Slate900,
                    modifier = Modifier.fillMaxWidth().background(JColors.Slate50, JShapes.control).padding(JSpacing.m),
                )
                Step(4, "Le code est la partie avant « .index-education.net », ici abcd1234. Tu peux aussi coller l’adresse entière.")
            }

            HelpSection("Quel identifiant et quel mot de passe ?") {
                Paragraph("Il faut l’identifiant et le mot de passe Pronote, pas ceux d’ÉduConnect ni de l’ENT. L’app se connecte directement à Pronote, sans passer par l’ENT.")
                Paragraph("L’établissement les donne en général à la rentrée. Si tu ne les as pas ou plus, demande-les à la vie scolaire.")
                Paragraph("Pour les essayer dans un navigateur, ouvre l’adresse de Pronote en remplaçant la fin par « eleve.html?login=true » (ou « parent.html?login=true ») : la page de connexion de Pronote s’affiche sans passer par l’ENT.")
            }

            HelpSection("Élève ou Parent") {
                Paragraph("Choisis Élève pour le compte de l’élève, Parent pour celui d’un responsable. Chaque compte a ses propres identifiants.")
            }

            Button(onClick = onDismiss, shape = JShapes.control, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("J’ai compris", fontWeight = FontWeight.Bold)
            }
        } }
    }
}

@Composable
private fun HelpSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(JSpacing.m), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(JColors.Violet100, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = JColors.Violet700)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600, modifier = Modifier.weight(1f))
    }
}
