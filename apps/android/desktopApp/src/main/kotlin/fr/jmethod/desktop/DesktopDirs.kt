package fr.jmethod.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Dossiers privés de l’app : données (`jmethod-data.json`, `documents/`) et cache (pièces jointes).
 * Windows : `%APPDATA%\J-Method` et `%LOCALAPPDATA%\J-Method\Cache` ; macOS :
 * `~/Library/Application Support/J-Method` et `~/Library/Caches/J-Method`. La propriété système
 * `jmethod.dataDir` remplace le tout (essais, captures avec des données de démonstration).
 */
class DesktopDirs(val data: File, val cache: File) {
    companion object {
        const val APP = "J-Method"

        fun resolve(): DesktopDirs {
            System.getProperty("jmethod.dataDir")?.takeIf { it.isNotBlank() }?.let { custom ->
                val root = File(custom)
                return DesktopDirs(root, File(root, "cache")).also { it.create() }
            }
            val home = File(System.getProperty("user.home"))
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val dirs = when {
                os.contains("win") -> {
                    val roaming = System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
                    val local = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(home, "AppData/Local")
                    DesktopDirs(File(roaming, APP), File(File(local, APP), "Cache"))
                }
                os.contains("mac") -> DesktopDirs(File(home, "Library/Application Support/$APP"), File(home, "Library/Caches/$APP"))
                else -> {
                    val data = System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
                    val cache = System.getenv("XDG_CACHE_HOME")?.let(::File) ?: File(home, ".cache")
                    DesktopDirs(File(data, APP), File(cache, APP))
                }
            }
            return dirs.also { it.create() }
        }
    }

    /** Crée les dossiers, réservés à l’utilisateur quand le système le permet (macOS, Linux). */
    fun create() {
        listOf(data, cache).forEach { dir ->
            dir.mkdirs()
            runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
        }
    }
}
