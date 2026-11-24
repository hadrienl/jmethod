package fr.jmethod.app.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.RevisionStreak
import fr.jmethod.app.ui.components.SurfaceCard
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JSpacing

/** Statique pour respecter également la réduction des animations ; le texte porte tout l'état. */
@Composable
internal fun RevisionFlame(summary: RevisionStreak.Summary, modifier: Modifier = Modifier) {
    SurfaceCard(modifier) {
        Row(Modifier.fillMaxWidth().padding(JSpacing.l).clearAndSetSemantics {
            contentDescription = "${RevisionStreak.days(summary.count)} de révision. ${summary.title}. ${summary.message} Record : ${RevisionStreak.days(summary.record)}. La flamme suit le programme de ta première ouverture du jour."
            liveRegion = LiveRegionMode.Polite
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JSpacing.m)) {
            Icon(JIcons.Flame, contentDescription = null, modifier = Modifier.size(40.dp),
                tint = if (summary.status == "earned") JColors.Amber900 else JColors.Slate500)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${RevisionStreak.days(summary.count)} de révision", style = MaterialTheme.typography.titleMedium)
                Text(summary.title, style = MaterialTheme.typography.labelLarge)
                Text(summary.message, style = MaterialTheme.typography.bodySmall)
                Text("Record : ${RevisionStreak.days(summary.record)}", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                Text("La flamme suit le programme de ta première ouverture du jour.", style = MaterialTheme.typography.labelSmall, color = JColors.Slate500)
            }
        }
    }
}
