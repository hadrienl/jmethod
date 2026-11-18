package fr.jmethod.app.domain

import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.data.DataMigrations
import fr.jmethod.app.model.AppDocument
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.DEFAULT_INTERVALS
import fr.jmethod.app.model.Revision
import fr.jmethod.app.model.Subject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant

/** Réglages, portés de `SettingsView.tsx` (suite `settingsUpdate`). */
object SettingsLogic {
    const val SENSITIVITY_MIN = 0.1
    const val SENSITIVITY_MAX = 0.8
    const val SENSITIVITY_STEP = 0.05
    const val THRESHOLD_MIN = 10
    const val THRESHOLD_MAX = 40
    const val THRESHOLD_STEP = 5

    /**
     * `parseInt(value, 10)` de JavaScript : blancs initiaux ignorés, signe facultatif, préfixe de
     * chiffres (`"12abc"` → 12, `"1.9"` → 1, `"1e3"` → 1). `null` pour `NaN` (ou hors des entiers).
     */
    fun jsParseInt(value: String): Long? {
        val text = value.trimStart { it.isWhitespace() || it == ' ' || it == '﻿' }
        var index = 0
        var negative = false
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            negative = text[index] == '-'
            index++
        }
        val start = index
        while (index < text.length && text[index] in '0'..'9') index++
        if (index == start) return null
        val digits = text.substring(start, index).trimStart('0').ifEmpty { "0" }
        if (digits.length > 10) return null
        val number = digits.toLong()
        return if (negative) -number else number
    }

    /** Ajout d’un palier : nombre `>= 0` absent de la liste, puis tri. */
    fun addInterval(intervals: List<Int>, value: String): List<Int> {
        val parsed = jsParseInt(value) ?: return intervals
        if (parsed < 0 || parsed > Int.MAX_VALUE) return intervals
        val number = parsed.toInt()
        if (number in intervals) return intervals
        return (intervals + number).sorted()
    }

    /** Retrait d’un palier : seulement s’il en reste plus de deux. */
    fun removeInterval(intervals: List<Int>, value: Int): List<Int> =
        if (intervals.size > 2) intervals.filter { it != value } else intervals

    /** J0 n’a pas de bouton de retrait. */
    fun removeButtonShown(value: Int) = value != 0

    fun resetIntervals(): List<Int> = DEFAULT_INTERVALS

    /** « Enregistrer toutes les modifications » : autres champs conservés, URL nettoyée, paliers triés. */
    fun save(settings: AppSettings, icalUrl: String, sensitivity: Double, emergencyThreshold: Int, intervals: List<Int>): AppSettings =
        settings.copy(
            pronoteIcalUrl = icalUrl.trim(),
            sensitivity = sensitivity,
            emergencyThreshold = emergencyThreshold,
            intervals = intervals.sorted(),
        )

    /** `String(number)` de JavaScript pour les valeurs du curseur. */
    fun jsNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

    /** Valeur du curseur ramenée au pas de 0,05 (évite les imprécisions du curseur natif). */
    fun snapSensitivity(value: Double): Double =
        (Math.round(value / SENSITIVITY_STEP) * SENSITIVITY_STEP).let { Math.round(it * 100) / 100.0 }.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)

    fun sensitivityLabel(sensitivity: Double) = "Impact max : ±${jsRound(sensitivity * 100)}%"

    fun thresholdLabel(threshold: Int) = "Seuil d'urgence (Rattrapage J+1) : < $threshold/100"

    /** Seule la couleur change ; l’ordre et les autres champs sont conservés. */
    fun subjectColor(subjects: List<Subject>, subjectId: String, color: String): List<Subject> =
        subjects.map { if (it.id == subjectId) it.copy(color = color) else it }
}

/**
 * Export et import JSON compatibles avec le web (`exportAllData`, `importAllData`, suite
 * `importExport`), avec l’écart D13 : un fichier dont `courses`, `revisions` ou `subjects` ne
 * sont pas des tableaux d’objets du bon type est refusé avec un message clair.
 */
object ImportExport {
    const val ERROR_TITLE = "Erreur d’importation"
    const val INVALID_MESSAGE = "Le fichier fourni est invalide."

    sealed interface Result {
        /** Données lues, à appliquer après confirmation. */
        data class Accepted(
            val courses: List<Course>,
            val revisions: List<Revision>,
            /** `null` : matières conservées (clé absente ou nulle). */
            val subjects: List<Subject>?,
            /** `null` : réglages conservés ; sinon stockés tels quels puis complétés par les valeurs par défaut. */
            val settings: AppSettings?,
        ) : Result {
            /** État après import : les créneaux Pronote de l’appareil sont conservés. */
            fun applyTo(document: AppDocument): AppDocument = document.copy(
                courses = courses,
                revisions = revisions,
                subjects = subjects ?: document.subjects,
                settings = settings ?: document.settings,
                initialized = true,
                dataVersion = DataMigrations.CURRENT_VERSION,
            )
        }

        data class Rejected(val title: String, val message: String) : Result
    }

    fun fileName(today: java.time.LocalDate) = "jmethod-sauvegarde-$today.json"

    fun export(document: AppDocument, now: Instant): String = AppDocumentCodec.export(document, now)

    /** JavaScript : `!value` pour une valeur JSON. */
    private fun falsy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> true
        is JsonPrimitive -> value.booleanOrNull == false || (!value.isString && value.content.toDoubleOrNull() == 0.0) ||
            (value.isString && value.content.isEmpty())
        else -> false
    }

    private fun shapeError(what: String) = Result.Rejected(ERROR_TITLE, "Ce fichier n’est pas une sauvegarde J-Method : $what.")

    fun parse(text: String): Result {
        val root = runCatching { AppDocumentCodec.json.parseToJsonElement(text) }.getOrNull()
        if (root !is JsonObject) return Result.Rejected(ERROR_TITLE, INVALID_MESSAGE)
        if (falsy(root["courses"]) || falsy(root["revisions"])) return Result.Rejected(ERROR_TITLE, INVALID_MESSAGE)
        val json = AppDocumentCodec.strictJson
        // D13 : validation de forme avant tout remplacement.
        val coursesElement = root["courses"]
        if (coursesElement !is JsonArray || coursesElement.any { it !is JsonObject }) return shapeError("la liste des cours est illisible")
        val courses = runCatching { json.decodeFromJsonElement(ListSerializer(Course.serializer()), coursesElement) }.getOrNull()
            ?: return shapeError("un cours est incomplet ou mal formé")
        val revisionsElement = root["revisions"]
        if (revisionsElement !is JsonArray || revisionsElement.any { it !is JsonObject }) return shapeError("la liste des séances est illisible")
        val revisions = runCatching { json.decodeFromJsonElement(ListSerializer(Revision.serializer()), revisionsElement) }.getOrNull()
            ?: return shapeError("une séance est incomplète ou mal formée")
        val subjectsElement = root["subjects"]
        val subjects = when (subjectsElement) {
            null, JsonNull -> null
            !is JsonArray -> return shapeError("la liste des matières est illisible")
            else -> {
                if (subjectsElement.any { it !is JsonObject }) return shapeError("la liste des matières est illisible")
                runCatching { json.decodeFromJsonElement(ListSerializer(Subject.serializer()), subjectsElement) }.getOrNull()
                    ?: return shapeError("une matière est incomplète ou mal formée")
            }
        }
        val settingsElement = root["settings"]
        val settings = when (settingsElement) {
            null, JsonNull -> null
            !is JsonObject -> return shapeError("les réglages sont illisibles")
            else -> runCatching { AppDocumentCodec.json.decodeFromJsonElement(AppSettings.serializer(), settingsElement) }.getOrNull()
                ?: return shapeError("les réglages sont illisibles")
        }
        return Result.Accepted(courses, revisions, subjects, settings)
    }
}
