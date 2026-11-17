package fr.jmethod.app.model

/** Port de `src/lib/constants.ts`. */
val DEFAULT_INTERVALS: List<Int> = listOf(0, 1, 2, 4, 7, 14, 25, 45, 60, 90)

val DEFAULT_SUBJECTS: List<Subject> = listOf(
    Subject("maths", "Mathématiques", "#38BDF8", "Algèbre, Géométrie, Arithmétique, Probabilités"),
    Subject("francais", "Français", "#EF4444", "Grammaire, Littérature, Rédaction, Orthographe"),
    Subject("physique-chimie", "Physique-chimie", "#6366F1", "Énergie, Mouvement, Organisation de la matière"),
    Subject("histoire-geo", "Histoire-géographie", "#94A3B8", "Histoire, Géographie, EMC"),
    Subject("espagnol", "Espagnol", "#1D4ED8", "Vocabulaire, Grammaire, Conjugaison"),
    Subject("musique", "Musique", "#84CC16", "Éducation musicale et chant"),
    Subject("svt", "S.V.T.", "#14B8A6", "Sciences de la Vie et de la Terre, Génétique"),
    Subject("anglais", "Anglais", "#8B5CF6", "Grammar, Vocabulary, Reading & Listening"),
    Subject("techno", "Technologie", "#B45309", "Design, Informatique, Systèmes automatisés"),
    Subject("arts-plastiques", "Arts plastiques", "#EC4899", "Pratiques artistiques, Histoire des arts"),
)

val DEFAULT_SETTINGS: AppSettings = AppSettings()

/** Libellés et couleurs des étapes (`PHASE_CONFIG`). */
data class PhaseInfo(
    val label: String,
    val color: Long,
    val description: String,
    /** Conseil affiché dans Aujourd’hui (DashboardView). */
    val hint: String,
    val symbol: String,
)

val PHASE_CONFIG: Map<Phase, PhaseInfo> = mapOf(
    Phase.ENCODAGE to PhaseInfo(
        "Découvrir", 0xFF2563EB, "Apprendre et comprendre un nouveau cours (J0)",
        "Comprends et reformule ton cours.", "01",
    ),
    Phase.CONSOLIDATION to PhaseInfo(
        "S’entraîner", 0xFFF97316, "Revoir pour fixer la mémorisation (J1, J2, J4)",
        "Entraîne-toi à retrouver les idées clés.", "02",
    ),
    Phase.RECUPERATION to PhaseInfo(
        "Se tester", 0xFF10B981, "Rappel actif sans regarder le cours (J7 à J90)",
        "Essaie de te rappeler sans regarder.", "03",
    ),
)

val Phase.info: PhaseInfo get() = PHASE_CONFIG.getValue(this)

/** Préréglages de l’autoévaluation (`ScoreModal`). */
data class ScorePreset(val value: Int, val label: String, val sub: String)

val SCORE_PRESETS: List<ScorePreset> = listOf(
    ScorePreset(0, "0", "Pas encore"),
    ScorePreset(25, "25", "Difficile"),
    ScorePreset(50, "50", "Ça revient"),
    ScorePreset(75, "75", "Maîtrisé"),
    ScorePreset(100, "100", "Parfait"),
)

/** Curseur de score : 0 à 100 par pas de 5, valeur initiale 50. */
const val SCORE_MIN = 0
const val SCORE_MAX = 100
const val SCORE_STEP = 5
const val SCORE_INITIAL = 50
