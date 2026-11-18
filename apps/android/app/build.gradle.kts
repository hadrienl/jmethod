plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Dossier partagé avec iOS : script d’automatisation, vecteurs protocole et vecteurs métier. */
val sharedDir: Directory = rootProject.layout.projectDirectory.dir("../../native/shared")

/** Copie au build le seul script partagé utile à l’APK (aucune copie versionnée). */
abstract class SharedScriptTask : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        script.get().asFile.copyTo(File(out, "PronoteAutomation.js"), overwrite = true)
    }
}

val sharedScript = tasks.register<SharedScriptTask>("sharedPronoteAutomation") {
    script.set(sharedDir.file("PronoteAutomation.js"))
    outputDir.set(layout.buildDirectory.dir("generated/sharedAssets"))
}

android {
    namespace = "fr.jmethod.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "fr.jmethod.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
        buildConfig = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Les tests JVM lisent directement les vecteurs partagés (protocol-vectors.json, domain/…).
    sourceSets["test"].resources.srcDir(sharedDir)
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = false }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(sharedScript, SharedScriptTask::outputDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
