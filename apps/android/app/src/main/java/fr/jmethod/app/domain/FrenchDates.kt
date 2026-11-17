package fr.jmethod.app.domain

import fr.jmethod.app.model.Phase
import fr.jmethod.app.model.info
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * Formats de date de date-fns (`locale: fr`) utilisés par le Planning et Mes cours. Les noms sont
 * écrits ici plutôt que tirés des données de locale de la plateforme, qui varient selon les
 * versions d’Android et du JDK.
 */
object FrenchDates {
    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )
    private val MONTHS_SHORT = listOf(
        "janv.", "févr.", "mars", "avr.", "mai", "juin",
        "juil.", "août", "sept.", "oct.", "nov.", "déc.",
    )
    /** Indexés par `DayOfWeek.value - 1` (lundi = 0). */
    private val DAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val DAYS_SHORT = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")

    private fun two(value: Int) = value.toString().padStart(2, '0')
    private fun month(date: LocalDate) = MONTHS[date.monthValue - 1]
    private fun monthShort(date: LocalDate) = MONTHS_SHORT[date.monthValue - 1]
    private fun day(date: LocalDate) = DAYS[date.dayOfWeek.value - 1]
    private fun dayShort(date: LocalDate) = DAYS_SHORT[date.dayOfWeek.value - 1]

    /** `EEEE d MMMM yyyy` : « lundi 30 mars 2026 ». */
    fun weekdayDayMonthYear(date: LocalDate) = "${day(date)} ${date.dayOfMonth} ${month(date)} ${date.year}"
    /** `EEEE d MMMM` : « lundi 30 mars ». */
    fun weekdayDayMonth(date: LocalDate) = "${day(date)} ${date.dayOfMonth} ${month(date)}"
    /** `EEEE d MMM` : « lundi 19 oct. ». */
    fun weekdayDayMonthShort(date: LocalDate) = "${day(date)} ${date.dayOfMonth} ${monthShort(date)}"
    /** `EEE d` : « lun. 30 ». */
    fun weekdayShortDay(date: LocalDate) = "${dayShort(date)} ${date.dayOfMonth}"
    /** `MMMM yyyy` : « mars 2026 ». */
    fun monthYear(date: LocalDate) = "${month(date)} ${date.year}"
    /** `d MMM` : « 19 oct. ». */
    fun dayMonthShort(date: LocalDate) = "${date.dayOfMonth} ${monthShort(date)}"
    /** `d MMM yyyy` : « 25 oct. 2026 ». */
    fun dayMonthShortYear(date: LocalDate) = "${date.dayOfMonth} ${monthShort(date)} ${date.year}"
    /** `d MMMM yyyy` : « 16 novembre 2026 ». */
    fun dayMonthYear(date: LocalDate) = "${date.dayOfMonth} ${month(date)} ${date.year}"
    /** `d MMMM` : « 16 novembre ». */
    fun dayMonth(date: LocalDate) = "${date.dayOfMonth} ${month(date)}"
    /** `dd MMMM yyyy` : « 05 octobre 2026 ». */
    fun paddedDayMonthYear(date: LocalDate) = "${two(date.dayOfMonth)} ${month(date)} ${date.year}"
    /** `dd MMM yyyy` : « 05 oct. 2026 ». */
    fun paddedDayMonthShortYear(date: LocalDate) = "${two(date.dayOfMonth)} ${monthShort(date)} ${date.year}"
    /** `dd/MM/yyyy`. */
    fun numeric(date: LocalDate) = "${two(date.dayOfMonth)}/${two(date.monthValue)}/${date.year}"
    /** `dd/MM`. */
    fun numericShort(date: LocalDate) = "${two(date.dayOfMonth)}/${two(date.monthValue)}"

    /** `parseISO` d’une chaîne `yyyy-MM-dd` (éventuellement suivie d’une heure). */
    fun parse(value: String): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()

    /** Formate [value] si elle est lisible, sinon la renvoie telle quelle. */
    fun format(value: String, formatter: (LocalDate) -> String): String = parse(value)?.let(formatter) ?: value
}

/**
 * Écart D11 : typographie française, majuscule initiale seulement en début de titre
 * (« Lundi 30 mars »), là où le web met une majuscule à chaque mot par CSS.
 */
fun String.sentenceCase(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.FRENCH) else it.toString() }

/**
 * Écart D10 : les apps natives disent Découvrir / S’entraîner / Se tester partout, y compris dans
 * le Planning et le détail de séance (le web y affiche Encodage / Consolidation / Récupération).
 */
val Phase.displayLabel: String get() = info.label

/** Repli de la casse et des accents (écart D9) : minuscules, NFD, suppression des diacritiques. */
fun String.foldForSearch(): String =
    Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Écart D7 : accord singulier/pluriel (« 1 jour », « 2 jours »). */
fun plural(count: Int, singular: String, pluralForm: String = singular + "s") = if (count > 1) pluralForm else singular
