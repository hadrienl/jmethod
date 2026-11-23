package fr.jmethod.app.domain

import fr.jmethod.app.model.Subject

/**
 * Brouillon du sélecteur de couleur d’une matière (#8). Rien n’est enregistré avant la confirmation :
 * les gestes, les touches et la saisie ne modifient que ce brouillon, en mémoire.
 *
 * [hex] est la dernière couleur valide (échantillon, aperçu et curseurs) ; [text] le contenu du champ,
 * tel que saisi, pour ne pas déplacer le curseur de saisie à chaque caractère.
 */
data class SubjectColorDraft(
    val subjectId: String,
    val subjectName: String,
    /** Couleur enregistrée à l’ouverture, normalisée (couleur affichée par défaut si le code est illisible). */
    val original: String,
    val hue: Double,
    val saturation: Double,
    val value: Double,
    val hex: String,
    val text: String,
    val saving: Boolean = false,
    /** Échec de la dernière confirmation, affiché pour réessayer. */
    val failure: String? = null,
    /** Matière supprimée pendant l’édition : la confirmation ne la recrée pas. */
    val removed: Boolean = false,
    /** Incrémenté quand le champ doit reprendre [text] (geste, validation) ; inchangé pendant la saisie. */
    val textRevision: Int = 0,
) {
    val hsv: ColorWheel.HSV get() = ColorWheel.HSV(hue, saturation, value)

    /** Code du champ normalisé en `#RRGGBB`, `null` s’il est incomplet ou invalide. */
    val textColor: String? get() = ColorWheel.normalized(text)

    val textError: String? get() = if (textColor == null) INVALID_MESSAGE else null

    val changed: Boolean get() = textColor != null && textColor != original

    val canSave: Boolean get() = textColor != null && !saving && !removed

    fun withHue(hue: Double) = fromWheel(hsv.copy(hue = hue))

    fun withSquare(saturation: Double, value: Double) = fromWheel(hsv.copy(saturation = saturation, value = value))

    /** Clavier et lecteur d’écran : la teinte fait le tour, saturation et luminosité sont bornées. */
    fun stepHue(degrees: Double) = withHue(hue + degrees)

    fun stepSaturation(delta: Double) = withSquare((saturation + delta).coerceIn(0.0, 1.0), value)

    fun stepValue(delta: Double) = withSquare(saturation, (value + delta).coerceIn(0.0, 1.0))

    /** Geste ou touche sur la roue : le champ reprend le code de la nouvelle couleur. */
    private fun fromWheel(color: ColorWheel.HSV): SubjectColorDraft {
        val code = ColorWheel.hex(color)
        return copy(
            hue = color.hue, saturation = color.saturation, value = color.value, hex = code, text = code,
            failure = null, textRevision = textRevision + 1,
        )
    }

    /**
     * Saisie : le texte est gardé tel quel. Un code complet met à jour la roue et l’aperçu (teinte
     * conservée au gris et au noir) ; une saisie partielle garde le dernier aperçu valide.
     */
    fun withText(input: String): SubjectColorDraft {
        val text = input.take(MAX_INPUT)
        val code = ColorWheel.normalized(text) ?: return copy(text = text, failure = null)
        val color = ColorWheel.hsv(code, hsv) ?: return copy(text = text, failure = null)
        return copy(hue = color.hue, saturation = color.saturation, value = color.value, hex = code, text = text, failure = null)
    }

    /** Code complet validé (perte du focus, Entrée) : le champ prend sa forme normalisée. */
    fun committed(): SubjectColorDraft = textColor?.let { if (it == text) this else copy(text = it, textRevision = textRevision + 1) } ?: this

    companion object {
        const val INVALID_MESSAGE = "Code incomplet : saisis 6 chiffres ou lettres de A à F, par exemple #2563EB."
        const val FAILURE_MESSAGE = "La couleur n’a pas pu être enregistrée. Vérifie l’espace disponible, puis réessaie."
        const val REMOVED_MESSAGE = "Cette matière a été supprimée : sa couleur ne peut plus être enregistrée."

        /** Couleur affichée pour un code illisible (`subjectColor` de l’interface). */
        const val FALLBACK = "#64748B"
        private const val MAX_INPUT = 16

        fun open(subject: Subject): SubjectColorDraft {
            val original = ColorWheel.normalized(subject.color) ?: FALLBACK
            val color = ColorWheel.hsv(original) ?: ColorWheel.HSV(0.0, 0.0, 0.5)
            return SubjectColorDraft(subject.id, subject.name, original, color.hue, color.saturation, color.value, original, original)
        }
    }
}
