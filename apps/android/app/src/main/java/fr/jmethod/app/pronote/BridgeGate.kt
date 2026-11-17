package fr.jmethod.app.pronote

import org.json.JSONObject

/**
 * Contrôle des messages du bridge (`native/shared/CONTRACT.md`). Un message n’est accepté que
 * s’il vient du cadre principal de l’origine exacte de la tentative, depuis une page desktop
 * Élèves/Parents, avec la version, le nonce et une forme attendus. Aucun contenu n’est journalisé.
 */
object BridgeGate {
    const val MAX_MESSAGE = 2_800_000
    private const val PNG_PREFIX = "data:image/png;base64,"

    data class Attempt(val nonce: String, val pin: String, val origin: String)

    sealed interface Event {
        data object Authenticated : Event
        data class Progress(val stage: String) : Event
        data class Failure(val code: String) : Event
        data class Qr(val image: String) : Event
    }

    val stages = setOf("filling-login", "submitting-login", "mfa-done", "opening-qr", "filling-code", "generating-qr")
    val errorCodes = setOf(
        "LOGIN_REJECTED", "LOGIN_STALLED", "LOGIN_FORM_NOT_FOUND", "MFA_REQUIRED",
        "LOGIN_TIMEOUT", "QR_TIMEOUT", "QR_TOO_LARGE", "AUTOMATION_FAILED",
    )

    fun accept(
        raw: String?,
        attempt: Attempt,
        sourceOrigin: String,
        isMainFrame: Boolean,
        currentPageUrl: String?,
    ): Event? {
        if (raw == null || raw.length > MAX_MESSAGE || !isMainFrame) return null
        if (sourceOrigin != attempt.origin) return null
        if (!PronotePolicy.isTrustedPage(currentPageUrl, attempt.origin)) return null
        val event = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        if (event.optInt("version") != 1 || event.optString("nonce") != attempt.nonce) return null
        if (event.optString("origin") != attempt.origin || !PronotePolicy.isTrustedPage(event.optString("url"), attempt.origin)) return null
        return when (event.optString("type")) {
            "authenticated" -> Event.Authenticated
            "progress" -> Event.Progress(event.optString("stage").takeIf { it in stages } ?: "")
            "error" -> Event.Failure(event.optString("code").takeIf { it in errorCodes } ?: "AUTOMATION_FAILED")
            "qr" -> {
                val image = event.optString("image")
                if (event.optString("pin") != attempt.pin || !image.startsWith(PNG_PREFIX) || image.length >= MAX_MESSAGE) null
                else Event.Qr(image)
            }
            else -> null
        }
    }
}
