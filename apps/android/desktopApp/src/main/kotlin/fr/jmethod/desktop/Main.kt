package fr.jmethod.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.jmethod.app.AppContainer
import fr.jmethod.app.pronote.FileThrottle
import fr.jmethod.app.pronote.MemoryVault
import fr.jmethod.app.pronote.SecretLoginStore
import fr.jmethod.app.ui.JMethodApp
import fr.jmethod.app.ui.platform.DesktopBack
import fr.jmethod.app.ui.platform.LocalPlatform
import fr.jmethod.app.ui.theme.JMethodTheme
import java.awt.Dimension
import java.io.File
import javax.imageio.ImageIO

fun main() {
    val dirs = DesktopDirs.resolve()
    // Nom du service dans le coffre du système ; remplaçable pour les essais (`-Djmethod.secretService`).
    val secrets = systemSecretStore(dirs.data, System.getProperty("jmethod.secretService") ?: DesktopDirs.APP)
    val container = AppContainer(
        filesDir = dirs.data,
        cacheDir = dirs.cache,
        vault = MemoryVault(secrets),
        loginStore = SecretLoginStore(secrets),
        throttle = FileThrottle(File(dirs.data, "pronote-throttle.properties")),
        scheduler = null,
    )
    val icon = DesktopPlatform::class.java.getResourceAsStream("/j-method.png")?.use { ImageIO.read(it) }?.let { BitmapPainter(it.toComposeImageBitmap()) }

    application {
        val state = rememberWindowState(size = DpSize(1180.dp, 800.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = ::exitApplication,
            title = "J-Method",
            icon = icon,
            state = state,
            // Échap non consommée (les dialogues la gèrent eux-mêmes) : ferme la fiche, la visionneuse ou
            // l’écran plein écran du dessus, puis revient à Aujourd’hui.
            onKeyEvent = { event -> event.key == Key.Escape && event.type == KeyEventType.KeyDown && DesktopBack.dispatch() },
        ) {
            remember { window.minimumSize = Dimension(360, 600) }
            val platform = remember { DesktopPlatform({ window }, dirs.cache) }
            CompositionLocalProvider(LocalPlatform provides platform) {
                JMethodTheme { JMethodApp(container) { scope -> desktopConnection(container, scope) } }
            }
        }
    }
}
