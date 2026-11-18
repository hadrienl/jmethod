package fr.jmethod.app.domain

import fr.jmethod.app.model.Subject
import java.text.Normalizer

/** Port de `matchSubject` (`src/lib/icalParser.ts`). */
object SubjectMatcher {
    private val combiningMarks = Regex("[̀-ͯ]")

    /** Classe `\s` de JavaScript (Java ne couvre que l’ASCII par défaut). */
    private const val JS_SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    /** `\b` de JavaScript : frontière entre `[A-Za-z0-9_]` et le reste. */
    private val physicalEducation = Regex(
        "(?<![A-Za-z0-9_])eps(?![A-Za-z0-9_])|sport|ed\\.?$JS_SPACE*physique|education physique",
    )

    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(combiningMarks, "")

    fun match(rawSummary: String, subjects: List<Subject>): Subject? {
        val summary = normalize(rawSummary)

        // L’EPS est une matière distincte, pas la physique-chimie.
        if (physicalEducation.containsMatchIn(summary)) return subjects.firstOrNull { it.id == "eps" }

        return subjects.firstOrNull { subject ->
            val name = normalize(subject.name)
            fun has(vararg parts: String) = parts.any { summary.contains(it) }
            when {
                summary.contains(name) -> true
                subject.id == "maths" && has("math", "mathematiques") -> true
                subject.id == "svt" && has("s.v.t", "svt", "sciences de la vie", "sciences vie") -> true
                subject.id == "physique-chimie" && has("physique", "chimie", "spc") -> true
                subject.id == "histoire-geo" && has("histoire", "geo", "emc", "h-g") -> true
                subject.id == "francais" && has("francais") -> true
                subject.id == "anglais" && has("anglais") -> true
                subject.id == "espagnol" && has("espagnol") -> true
                subject.id == "techno" && has("techno", "technologie") -> true
                subject.id == "arts-plastiques" && has("arts", "plastique") -> true
                subject.id == "musique" && has("musique", "chant") -> true
                else -> false
            }
        }
    }
}
