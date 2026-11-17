package fr.jmethod.app.data

import fr.jmethod.app.model.AppDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Encodage du document : compatible avec le stockage et l’export JSON du web. */
object AppDocumentCodec {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    private val pretty = Json { prettyPrint = true }

    fun encode(document: AppDocument): String = json.encodeToString(AppDocument.serializer(), document)

    fun decode(text: String): AppDocument = json.decodeFromString(AppDocument.serializer(), text)

    /** Export au format de `exportAllData` (sans `pronoteEvents`), `exportDate` en ISO UTC à la milliseconde. */
    fun export(document: AppDocument, now: java.time.Instant): String {
        val full = json.encodeToJsonElement(AppDocument.serializer(), document).jsonObject
        val exported = kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive("1.0"))
            put("exportDate", kotlinx.serialization.json.JsonPrimitive(isoMillis(now)))
            for (key in listOf("courses", "revisions", "subjects", "settings")) put(key, full.getValue(key))
        }
        return pretty.encodeToString(JsonObject.serializer(), exported)
    }

    /** `Date.toISOString()` : toujours trois décimales. */
    fun isoMillis(instant: java.time.Instant): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC).format(instant)

    /**
     * Lit une sauvegarde exportée par le web (`exportAllData`) ou par une app native.
     * Comme `importAllData`, `courses` et `revisions` sont obligatoires ; les autres clés
     * absentes reprennent les valeurs par défaut.
     */
    fun decodeExport(text: String): AppDocument? = runCatching {
        val root: JsonObject = json.parseToJsonElement(text).jsonObject
        if (root["courses"] == null || root["revisions"] == null) return null
        json.decodeFromJsonElement(AppDocument.serializer(), root).copy(initialized = true, dataVersion = DataMigrations.CURRENT_VERSION)
    }.getOrNull()
}

/**
 * Fichier JSON unique dans le stockage privé de l’app. L’écriture passe par un fichier
 * temporaire synchronisé sur disque puis renommé atomiquement : un arrêt brutal laisse
 * soit l’ancienne version, soit la nouvelle, jamais un document tronqué.
 */
class AppDocumentStore(private val file: File) {

    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Loaded(val document: AppDocument) : ReadResult
        /** Fichier illisible, mis de côté pour ne pas l’écraser silencieusement. */
        data class Corrupted(val keptAs: File) : ReadResult
    }

    fun read(): ReadResult {
        if (!file.exists()) return ReadResult.Missing
        return try {
            ReadResult.Loaded(AppDocumentCodec.decode(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            val kept = File(file.parentFile, "${file.nameWithoutExtension}.corrompu-${System.currentTimeMillis()}.json")
            file.renameTo(kept)
            ReadResult.Corrupted(kept)
        }
    }

    @Synchronized
    fun write(document: AppDocument) {
        val directory = requireNotNull(file.absoluteFile.parentFile)
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(AppDocumentCodec.encode(document).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }
}
