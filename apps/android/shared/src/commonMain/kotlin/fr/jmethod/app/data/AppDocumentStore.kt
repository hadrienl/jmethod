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

    class UnsupportedVersion : IllegalStateException("Ces données nécessitent une version plus récente de J-Method. Elles n’ont pas été modifiées.")
    fun decode(text: String): AppDocument {
        val root = json.parseToJsonElement(text).jsonObject
        val visit = root["todayWidgetVisit"]?.let { value ->
            runCatching { json.decodeFromJsonElement(fr.jmethod.app.domain.TodayWidgetVisit.serializer(), value) }.getOrNull()
        }
        val document = json.decodeFromJsonElement(AppDocument.serializer(), JsonObject(root - "todayWidgetVisit"))
            .copy(todayWidgetVisit = visit)
        if (document.schemaVersion !in 1..3) throw UnsupportedVersion()
        // Un journal incohérent ne doit pas coûter tout le document : un nouveau suivi démarre à la prochaine ouverture.
        val streak = document.streakState
        if (streak != null && runCatching { fr.jmethod.app.domain.RevisionStreak.validate(streak) }.isFailure) return document.copy(streakState = null)
        return document
    }

    /**
     * Export au format de `exportAllData` (sans `pronoteEvents`), `exportDate` en ISO UTC à la milliseconde,
     * texte identique à `JSON.stringify(…, null, 2)` (indentation, nombres, échappements, ordre des clés du web).
     */
    fun export(document: AppDocument, now: java.time.Instant): String {
        val full = json.encodeToJsonElement(AppDocument.serializer(), document).jsonObject
        val exported = kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(if (document.schemaVersion >= 3) "3.0" else if (document.schemaVersion >= 2) "2.0" else "1.0"))
            put("exportDate", kotlinx.serialization.json.JsonPrimitive(isoMillis(now)))
            if (document.schemaVersion >= 2) {
                put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(document.schemaVersion))
                put("programmes", full.getValue("programmes"))
                if (document.schemaVersion >= 3) {
                    full["streakState"]?.let { put("streakState", it) }
                    if (document.todayWidgetVisit != null) put("todayWidgetVisit", full.getValue("todayWidgetVisit"))
                }
            }
            // `notebookImports` (cahier de textes → cours) seulement s’il existe : ignoré par le web.
            val keys = listOf("courses", "revisions", "subjects", "settings") + if (document.notebookImports.isEmpty()) emptyList() else listOf("notebookImports")
            for (key in keys) {
                val value = full.getValue(key)
                put(key, when (key) {
                    "revisions" -> reorderRevisions(value)
                    "settings" -> reorder(if (document.schemaVersion < 2) JsonObject((value as JsonObject).filterKeys { it != "rhythm" && it != "adaptiveEnabled" }) else value as JsonObject, settingsKeyOrder)
                    else -> value
                })
            }
        }
        val payload = if (document.schemaVersion < 2) exported else kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(if (document.schemaVersion >= 3) "3.0" else "2.0"))
            put("exportDate", kotlinx.serialization.json.JsonPrimitive(isoMillis(now)))
            put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(document.schemaVersion))
            put("data", exported)
        }
        return JsStringify.stringify(payload)
    }

    /** Ordre des clés d’une révision dans le stockage du web (création, rattrapage, puis validation). */
    private val revisionKeyOrder = listOf(
        "id", "courseId", "stepIndex", "stepLabel", "scheduledDate", "status", "phase",
        "isEmergencySession", "adjustedIntervalDays", "completedDate", "score",
    )

    /** Ordre des réglages du web (`DEFAULT_SETTINGS`, puis `lastPronoteSync` ajouté à la synchronisation). */
    private val settingsKeyOrder = listOf(
        "intervals", "sensitivity", "emergencyThreshold", "pronoteIcalUrl", "soundEnabled", "userName", "userGrade", "lastPronoteSync",
        "notebookAutoCreate", "notebookAutoCreateSince",
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
        val envelope: JsonObject = json.parseToJsonElement(text).jsonObject
        if (envelope["data"] != null && (envelope["schemaVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content !in setOf("2", "3")) return null
        val root = (envelope["data"] as? JsonObject) ?: envelope
        val schema = (root["schemaVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 1
        if (schema !in 1..3) return null
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
    // Le document v1 reste intact : sauvegarde avant migration et protection contre une ancienne app.
    private val v2File = File(file.parentFile, "${file.nameWithoutExtension}-v2.json")
    private val v3File = File(file.parentFile, "${file.nameWithoutExtension}-v3.json")
    private fun readableFile() = when { v3File.exists() -> v3File; v2File.exists() -> v2File; else -> file }


    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Loaded(val document: AppDocument) : ReadResult
        /** Fichier illisible, mis de côté pour ne pas l’écraser silencieusement. */
        data class Corrupted(val keptAs: File) : ReadResult
    }

    fun read(): ReadResult {
        val selected = readableFile()
        if (!selected.exists()) return ReadResult.Missing
        return try {
            ReadResult.Loaded(AppDocumentCodec.decode(selected.readText(Charsets.UTF_8)))
        } catch (error: AppDocumentCodec.UnsupportedVersion) {
            throw error
        } catch (_: Exception) {
            val kept = File(file.parentFile, "${selected.nameWithoutExtension}.corrompu-${System.currentTimeMillis()}.json")
            check(selected.renameTo(kept)) { "Le fichier illisible ne peut pas être mis de côté." }
            ReadResult.Corrupted(kept)
        }
    }

    @Synchronized
    fun write(document: AppDocument) {
        require(document.schemaVersion <= 3) { "Version de document non prise en charge." }
        val target = when { document.schemaVersion >= 3 -> v3File; document.schemaVersion >= 2 -> v2File; else -> file }
        val directory = requireNotNull(file.absoluteFile.parentFile)
        directory.mkdirs()
        val temporary = File(directory, "${target.name}.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(AppDocumentCodec.encode(document).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }
}
