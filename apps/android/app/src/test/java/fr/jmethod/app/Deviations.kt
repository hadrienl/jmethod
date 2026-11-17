package fr.jmethod.app

/**
 * Écarts volontaires listés dans `native/shared/domain/DEVIATIONS.md`. Les vecteurs restent ceux
 * du web : chaque correction appliquée à une valeur attendue renvoie ici à son numéro Dn.
 */
object Deviations {
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
