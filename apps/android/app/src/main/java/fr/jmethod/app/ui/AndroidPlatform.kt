package fr.jmethod.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import fr.jmethod.app.pronote.PronoteUiState
import fr.jmethod.app.ui.platform.FileSink
import fr.jmethod.app.ui.platform.FileSource
import fr.jmethod.app.ui.platform.PlatformServices
import fr.jmethod.app.ui.pronote.PronoteViewModel
import fr.jmethod.app.ui.pronote.WebViewOfficialPage
import fr.jmethod.app.ui.theme.JColors
import java.io.File
import java.util.Locale

/** Services Android : FileProvider, Custom Tabs, sélecteurs de documents et WebView officielle. */
class AndroidPlatform(private val context: Context) : PlatformServices {
    override val device = "ce téléphone"
    override val isDesktop = false
    override val backupNote = "Les sauvegardes Android sont désactivées."
    override val appVersion: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"

    /**
     * Ouvre une pièce jointe téléchargée par une URI `content://` en lecture seule. Un document actif
     * (HTML, SVG) est présenté comme texte : jamais exécuté.
     */
    override fun openFile(file: File): Boolean {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val type = mimeType(file.name)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(view)
            true
        } catch (_: ActivityNotFoundException) {
            // Aucune app de lecture : proposer de transmettre le fichier (jamais son adresse Pronote).
            val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(Intent.createChooser(send, "Aucune app ne lit ce document. L’envoyer vers…"))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    /**
     * « Ouvrir avec… » : le fichier local en lecture seule par `FileProvider` (aucune adresse Pronote).
     * Un document actif (HTML, SVG) est présenté comme texte.
     */
    override fun openWith(file: File, name: String): Boolean {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(view, "Ouvrir $name avec…"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Ouvre un lien `http(s)` déjà validé dans le navigateur externe, jamais dans la WebView Pronote. */
    override fun openLink(url: String) {
        try {
            // Navigateur intégré (Custom Tabs), sans cookies ni WebView de l’app.
            androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    @Composable
    override fun rememberExportLauncher(onChosen: (FileSink?) -> Unit): (String) -> Unit {
        val resolver = LocalContext.current.contentResolver
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            onChosen(uri?.let { FileSink { resolver.openOutputStream(it, "wt")!! } })
        }
        return { name -> launcher.launch(name) }
    }

    @Composable
    override fun rememberImportLauncher(onChosen: (FileSource?) -> Unit): () -> Unit {
        val resolver = LocalContext.current.contentResolver
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            onChosen(uri?.let { FileSource { resolver.openInputStream(it)!! } })
        }
        return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    }

    /**
     * Couche de la page officielle, placée à la racine pour ne jamais être détachée (rotation, écran de
     * connexion ou onglets). Masquée, elle garde une taille non nulle sous le contenu, ignore les touchers
     * et reste hors de l’arbre d’accessibilité ; visible (ENT, vérification), elle couvre l’écran avec un
     * titre et un bouton Annuler.
     */
    @Composable
    override fun OfficialPageLayer(viewModel: PronoteViewModel, state: PronoteUiState, modifier: Modifier) {
        val page = viewModel.page as? WebViewOfficialPage ?: return
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
                factory = { page.attach(activity) },
                onRelease = { page.detach(it) },
                update = { view ->
                    view.alpha = if (visible) 1f else 0f
                    view.isEnabled = visible
                    view.isFocusable = visible
                    view.isFocusableInTouchMode = visible
                    view.importantForAccessibility =
                        if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    // Masquée, la page officielle n’est pas proposée aux gestionnaires de mots de passe.
                    view.importantForAutofill =
                        if (visible) View.IMPORTANT_FOR_AUTOFILL_AUTO else View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    view.setOnTouchListener { _, _: MotionEvent -> !visible }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    private companion object {
        const val AUTHORITY = "fr.jmethod.app.files"

        /** Type MIME d’ouverture ; un document actif (HTML, SVG…) est présenté comme texte. */
        fun mimeType(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val active = extension in setOf("html", "htm", "xhtml", "svg", "xml", "js", "mht", "mhtml")
            return if (active) "text/plain" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }
}
