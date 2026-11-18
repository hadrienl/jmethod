package fr.jmethod.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import fr.jmethod.app.ui.JMethodApp
import fr.jmethod.app.ui.theme.JMethodTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Écran de démarrage système (monogramme), remplacé dès la première image par l’app.
        installSplashScreen()
        // Barres système transparentes à icônes sombres : l’app reste claire en mode sombre.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            JMethodTheme { JMethodApp() }
        }
    }
}
