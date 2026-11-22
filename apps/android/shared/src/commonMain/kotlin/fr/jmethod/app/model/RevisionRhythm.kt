package fr.jmethod.app.model

import kotlinx.serialization.Serializable

@Serializable
data class RhythmStep(val id: String, val offsetDays: Int, val phase: Phase)

@Serializable
data class RevisionRhythm(val id: String, val name: String, val revision: Int, val steps: List<RhythmStep>) {
    companion object {
        val DEFAULT = RevisionRhythm("default", "Rythme des J", 1, listOf(0, 1, 3, 7, 14, 25, 45, 60, 100).map {
            RhythmStep("day-$it", it, when { it <= 3 -> Phase.ENCODAGE; it <= 25 -> Phase.CONSOLIDATION; else -> Phase.RECUPERATION })
        })
    }
}

@Serializable
data class RevisionProgramme(
    val id: String,
    val subjectId: String,
    val courseIds: List<String>,
    val startDate: String,
    val rhythmSnapshot: RevisionRhythm,
    val adaptiveEnabled: Boolean,
    val needsReview: Boolean = false,
)
