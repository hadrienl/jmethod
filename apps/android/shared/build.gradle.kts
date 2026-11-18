import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Code commun à l’app Android et à l’app ordinateur : modèle et stockage JSON, logique métier,
 * client du protocole Pronote, cahier de textes, analyse des documents et écrans Compose.
 * Les deux cibles sont des JVM : le code commun utilise directement le JDK (java.time, javax.crypto…).
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Dossier partagé avec iOS : vecteurs protocole et vecteurs métier lus par les tests JVM. */
val sharedDir: Directory = rootProject.layout.projectDirectory.dir("../../native/shared")

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.mp.compose.runtime)
            implementation(libs.mp.compose.foundation)
            implementation(libs.mp.compose.ui)
            implementation(libs.mp.compose.material3)
            implementation(libs.mp.compose.icons.core)
            implementation(libs.mp.lifecycle.viewmodel.compose)
            implementation(libs.mp.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // org.json est fourni par Android ; l’app ordinateur l’embarque (voir desktopMain).
            compileOnly(libs.org.json)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.activity.compose)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.org.json)
                implementation(libs.pdfbox)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.org.json)
            }
            resources.srcDir(sharedDir)
        }
    }
}

android {
    namespace = "fr.jmethod.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
