package fr.jmethod.app.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.LocalDate

@Serializable
data class PersonalHomework(val id: String, val subjectId: String, val description: String, val due: String, val done: Boolean = false)

@Serializable
data class AgendaFollowUp(
    val personal: List<PersonalHomework> = emptyList(),
    val localChecks: Map<String, Boolean> = emptyMap(),
    val cached: Map<String, CachedHomework> = emptyMap(),
    val coverage: Map<String, AgendaCoverage> = emptyMap(),
)

@Serializable
data class AgendaCoverage(val from: String, val to: String, val observedAt: String) {
    companion object {
        /** Charge le premier créneau contigu manquant ; une ancienne lecture isolée ne masque pas un trou. */
        fun adjacentAnchor(today: LocalDate, coverage: Collection<AgendaCoverage>, previous: Boolean): LocalDate? {
            val ranges = coverage.mapNotNull {
                runCatching { LocalDate.parse(it.from)..LocalDate.parse(it.to) }.getOrNull()
            }
            if (ranges.none { today in it }) return null
            if (previous) {
                var start = today
                for (range in ranges.sortedByDescending { it.endInclusive }) {
                    if (range.start < start && range.endInclusive >= start.minusDays(1)) start = range.start
                }
                return start.minusDays(14)
            }
            var end = today
            for (range in ranges.sortedBy { it.start }) {
                if (range.endInclusive > end && range.start <= end.plusDays(1)) end = range.endInclusive
            }
            return end.plusDays(15)
        }
    }
}

@Serializable
data class CachedHomework(
    val key: String,
    val due: String,
    val subject: String,
    val description: String,
    val remoteDone: Boolean?,
    val observedAt: String,
    val documentNames: List<String>,
    val unavailable: Boolean = false,
    val sourceTag: String? = null,
)

fun agendaFingerprint(parts: List<String>): String {
    val input = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
