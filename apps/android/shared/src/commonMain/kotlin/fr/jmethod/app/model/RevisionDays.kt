package fr.jmethod.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RevisionDayStatus {
    @SerialName("pending") PENDING,
    @SerialName("earned") EARNED,
    @SerialName("rest") REST,
    @SerialName("missed") MISSED,
}

@Serializable
data class RevisionDay(
    val date: String,
    val requiredRevisionIds: List<String>,
    val completedRevisionIds: List<String>,
    val frozenAt: String,
    val finalizedAt: String? = null,
    val status: RevisionDayStatus,
)

@Serializable
data class StreakState(
    val trackingStartDate: String,
    val lastReconciledDate: String,
    val currentCount: Int = 0,
    val bestCount: Int = 0,
    val days: List<RevisionDay>,
)
