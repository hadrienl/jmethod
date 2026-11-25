package fr.jmethod.app.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class PersonalHomework(val id: String, val subjectId: String, val description: String, val due: String, val done: Boolean = false)

@Serializable
data class AgendaFollowUp(
    val personal: List<PersonalHomework> = emptyList(),
    val localChecks: Map<String, Boolean> = emptyMap(),
    val cached: Map<String, CachedHomework> = emptyMap(),
)

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
