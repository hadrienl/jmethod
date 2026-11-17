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

    /** Import (écart D13) : pas de valeur coercée, un champ mal typé fait refuser le fichier. */
    val strictJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val pretty = Json { prettyPrint = true }

    fun encode(document: AppDocument): String = json.encodeToString(AppDocument.serializer(), document)

    fun decode(text: String): AppDocument = json.decodeFromString(AppDocument.serializer(), text)

    /**
     * Export au format de `exportAllData` (sans `pronoteEvents`), `exportDate` en ISO UTC à la milliseconde,
     * texte identique à `JSON.stringify(…, null, 2)` (indentation, nombres, échappements, ordre des clés du web).
     */
    fun export(document: AppDocument, now: java.time.Instant): String {
        val full = json.encodeToJsonElement(AppDocument.serializer(), document).jsonObject
        val exported = kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive("1.0"))
            put("exportDate", kotlinx.serialization.json.JsonPrimitive(isoMillis(now)))
            for (key in listOf("courses", "revisions", "subjects", "settings")) {
                val value = full.getValue(key)
                put(key, when (key) {
                    "revisions" -> reorderRevisions(value)
                    "settings" -> reorder(value as JsonObject, settingsKeyOrder)
                    else -> value
                })
            }
        }
        return JsStringify.stringify(exported)
    }

    /** Ordre des clés d’une révision dans le stockage du web (création, rattrapage, puis validation). */
    private val revisionKeyOrder = listOf(
        "id", "courseId", "stepIndex", "stepLabel", "scheduledDate", "status", "phase",
        "isEmergencySession", "adjustedIntervalDays", "completedDate", "score",
    )

    /** Ordre des réglages du web (`DEFAULT_SETTINGS`, puis `lastPronoteSync` ajouté à la synchronisation). */
    private val settingsKeyOrder = listOf(
        "intervals", "sensitivity", "emergencyThreshold", "pronoteIcalUrl", "soundEnabled", "userName", "userGrade", "lastPronoteSync",
    )

    private fun reorder(item: JsonObject, order: List<String>): JsonObject {
        val ordered = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        order.forEach { key -> item[key]?.let { ordered[key] = it } }
        item.forEach { (key, v) -> if (key !in ordered) ordered[key] = v }
        return JsonObject(ordered)
    }

    private fun reorderRevisions(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val array = value as? kotlinx.serialization.json.JsonArray ?: return value
        return kotlinx.serialization.json.JsonArray(array.map { element -> (element as? JsonObject)?.let { reorder(it, revisionKeyOrder) } ?: element })
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

/** `JSON.stringify(value, null, 2)` de JavaScript pour un arbre JSON. */
object JsStringify {
    fun stringify(element: kotlinx.serialization.json.JsonElement): String = StringBuilder().also { write(it, element, "") }.toString()

    private fun write(out: StringBuilder, element: kotlinx.serialization.json.JsonElement, indent: String) {
        when (element) {
            is JsonObject -> {
                if (element.isEmpty()) { out.append("{}"); return }
                val inner = "$indent  "
                out.append("{\n")
                element.entries.forEachIndexed { index, (key, value) ->
                    out.append(inner); quote(out, key); out.append(": ")
                    write(out, value, inner)
                    if (index < element.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append(indent).append('}')
            }
            is kotlinx.serialization.json.JsonArray -> {
                if (element.isEmpty()) { out.append("[]"); return }
                val inner = "$indent  "
                out.append("[\n")
                element.forEachIndexed { index, value ->
                    out.append(inner)
                    write(out, value, inner)
                    if (index < element.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append(indent).append(']')
            }
            kotlinx.serialization.json.JsonNull -> out.append("null")
            is kotlinx.serialization.json.JsonPrimitive -> when {
                element.isString -> quote(out, element.content)
                else -> out.append(number(element.content))
            }
        }
    }

    /** Nombre au format JavaScript : `1.0` → `1`, `true`/`false` inchangés. */
    private fun number(content: String): String {
        val value = content.toDoubleOrNull() ?: return content
        if (content.contains('.') || content.contains('e') || content.contains('E')) {
            if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e21) return value.toLong().toString()
        }
        return content
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { out.append(c).append(text[i + 1]); i++ }
                Character.isSurrogate(c) -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }
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
