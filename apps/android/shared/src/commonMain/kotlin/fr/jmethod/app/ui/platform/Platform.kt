package fr.jmethod.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import fr.jmethod.app.pronote.PronoteUiState
import fr.jmethod.app.ui.pronote.PronoteViewModel
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Flux ouvert sur le fichier choisi par l’utilisateur (export d’une sauvegarde). */
fun interface FileSink {
    fun open(): OutputStream
}

/** Flux ouvert sur le fichier choisi par l’utilisateur (import d’une sauvegarde). */
fun interface FileSource {
    fun open(): InputStream
}

/**
 * Services du système fournis par l’app Android (`:app`) ou l’app ordinateur (`:desktopApp`) :
 * ouverture de documents et de liens, sélecteurs de fichiers, page officielle Pronote.
 */
interface PlatformServices {
    /** Appareil dans les textes : « ce téléphone » ou « cet ordinateur ». */
    val device: String

    /** Vrai pour l’app ordinateur (connexion directe, clavier et souris). */
    val isDesktop: Boolean

    /** Phrase « À propos » sur les sauvegardes du système (vide si rien à dire). */
    val backupNote: String

    /** Version affichée dans « À propos ». */
    val appVersion: String

    /** Ouvre un document local en lecture seule avec l’app par défaut ; faux si aucune ne convient. */
    fun openFile(file: File): Boolean

    /** « Ouvrir avec… » : choix de l’app qui ouvre le document. */
    fun openWith(file: File, name: String): Boolean

    /** Lien du cahier de textes : navigateur du système, jamais une page web de l’app. */
    fun openLink(url: String)

    /** « Enregistrer sous » : le lanceur reçoit le nom proposé ; [onChosen] reçoit `null` si l’utilisateur annule. */
    @Composable
    fun rememberExportLauncher(onChosen: (FileSink?) -> Unit): (suggestedName: String) -> Unit

    /** « Ouvrir » un fichier de sauvegarde ; [onChosen] reçoit `null` si l’utilisateur annule. */
    @Composable
    fun rememberImportLauncher(onChosen: (FileSource?) -> Unit): () -> Unit

    /** Page officielle Pronote (Android) ; rien sur ordinateur, où la connexion est directe. */
    @Composable
    fun OfficialPageLayer(viewModel: PronoteViewModel, state: PronoteUiState, modifier: Modifier)
}

val LocalPlatform = staticCompositionLocalOf<PlatformServices> { error("PlatformServices non fourni") }
