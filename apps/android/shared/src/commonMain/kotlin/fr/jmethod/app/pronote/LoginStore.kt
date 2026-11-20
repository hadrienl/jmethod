package fr.jmethod.app.pronote

/**
 * Établissement, type de compte et, si « Rester connecté » est activé, identifiant et mot de passe.
 * Ils ne quittent jamais le téléphone, sauf vers la page officielle Pronote.
 */
data class SavedLogin(
    val school: String,
    val accountType: AccountType,
    val username: String? = null,
    val password: String? = null,
    /** Reconnexion silencieuse permise ; désactivée par une déconnexion volontaire. */
    val autoReconnect: Boolean = true,
) {
    override fun toString(): String = "SavedLogin(<masqué>)"
}

interface LoginStore {
    fun load(): SavedLogin?
    fun save(login: SavedLogin)
    /** Supprime le mot de passe (refusé par Pronote) ; établissement et identifiant restent. */
    fun forgetPassword()
    /** Supprime identifiant et mot de passe (déconnexion) ; établissement et type restent pour préremplir. */
    fun forgetCredentials()
}
