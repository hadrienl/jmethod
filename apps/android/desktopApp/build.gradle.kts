import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * App ordinateur (Windows, macOS) : fenêtre Compose Desktop, connexion directe à Pronote, coffre du
 * système pour « Rester connecté ». Lancement : `./gradlew :desktopApp:run` ; installeurs :
 * `packageMsi` (sur Windows) et `packageDmg` (sur macOS).
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Version de l’app ordinateur (MAJEUR.MINEUR.CORRECTIF, majeur ≥ 1 exigé par MSI et DMG). La CI la
 * tire de l’étiquette `desktop-vX.Y.Z` (`-Pjmethod.desktopVersion=X.Y.Z`).
 */
val desktopVersion: String = providers.gradleProperty("jmethod.desktopVersion").orElse("1.0.0").get()

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets.main {
    resources.srcDir(rootProject.layout.projectDirectory.dir("../../native/shared/sound-assets"))
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.mp.compose.material3)
    implementation(libs.mp.compose.icons.core)
    implementation(libs.mp.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.org.json)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(libs.junit)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(libs.pdfbox)
}

// Visite guidée optionnelle (captures et connexion réelle à la démonstration publique), jamais en CI :
// ./gradlew :desktopApp:test --tests '*DesktopTour*' -Pjmethod.tour=<dossier> [-Pjmethod.demoLogin=true] [-Pjmethod.tourSize=1366x768]
tasks.withType<Test>().configureEach {
    listOf("jmethod.tour", "jmethod.demoLogin", "jmethod.tourSize").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

compose.desktop {
    application {
        mainClass = "fr.jmethod.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Djmethod.version=$desktopVersion")
        // Essais locaux seulement : dossier de données et service du coffre séparés (`-Pjmethod.dataDir=…`).
        providers.gradleProperty("jmethod.dataDir").orNull?.let { jvmArgs += "-Djmethod.dataDir=$it" }
        providers.gradleProperty("jmethod.secretService").orNull?.let { jvmArgs += "-Djmethod.secretService=$it" }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "J-Method"
            packageVersion = desktopVersion
            description = "Révisions espacées à partir de Pronote"
            vendor = "J-Method"
            copyright = "J-Method"
            // Modules du JDK embarqué en plus de ceux de Compose : suggestRuntimeModules (java.instrument,
            // jdk.unsupported), HTTPS ECDHE (jdk.crypto.ec), lecteurs d’écran Windows (jdk.accessibility).
            modules("java.instrument", "java.naming", "jdk.accessibility", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                menuGroup = "J-Method"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                // Identifiant de mise à niveau stable : une nouvelle version remplace l’ancienne.
                upgradeUuid = "6f1d3c52-9a7e-4f0b-8b3e-2f9a51c7d4e1"
                iconFile.set(project.file("icons/j-method.ico"))
            }
            macOS {
                bundleID = "fr.jmethod.desktop"
                iconFile.set(project.file("icons/j-method.icns"))
            }
            linux {
                iconFile.set(project.file("icons/j-method.png"))
            }
        }
    }
}
