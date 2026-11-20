package fr.jmethod.app

/**
 * Écarts volontaires listés dans `native/shared/domain/DEVIATIONS.md`. Les vecteurs restent ceux
 * du web : chaque correction appliquée à une valeur attendue renvoie ici à son numéro Dn.
 */
object Deviations {
    private val ISO_NEXT_REVIEW = Regex("""Prochaine révision le (\d{4}-\d{2}-\d{2})\.""")

    /**
     * D14 : « Prochaine révision le 2026-11-27. » devient « demain », « après-demain » ou
     * « le vendredi 27 novembre », par rapport au jour réel [today] du cas (son horloge `now`).
     */
    fun d14(expected: String?, today: java.time.LocalDate): String? = expected?.replace(ISO_NEXT_REVIEW) { match ->
        "Prochaine révision ${fr.jmethod.app.domain.FrenchDates.relativeDay(java.time.LocalDate.parse(match.groupValues[1]), today)}."
    }

    /** Vrai si le message attendu du web contient une date ISO corrigée par D14. */
    fun touchedByD14(expected: String?): Boolean = expected != null && ISO_NEXT_REVIEW.containsMatchIn(expected)

    /** D7 : « 1 jours » du message de l’algorithme devient « 1 jour ». */
    fun d7Days(expected: String?): String? = expected?.replace(Regex("""\((1) jours( au lieu|\))"""), "($1 jour$2")

    /** D10 : libellés d’étape d’Aujourd’hui partout (Planning et détail de séance compris). */
    private val D10 = listOf("Encodage" to "Découvrir", "Consolidation" to "S’entraîner", "Récupération" to "Se tester")

    /** Remplace un libellé d’étape du web en tête de chaîne ou après « , » / « · ». */
    fun d10(expected: String?): String? {
        if (expected == null) return null
        var value = expected
        for ((web, native) in D10) {
            value = value!!
                .replace(Regex("""(^|, |· )$web(?=$| ·|,)"""), "$1$native")
        }
        return value
    }
}
