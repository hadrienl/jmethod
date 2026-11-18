package fr.jmethod.app.pronote

/** Stockage sécurisé de la session mobile Pronote. */
interface SessionVault {
    fun load(): Credentials?
    /** Enregistre le jeton (et efface le marquage [needsRelogin]). */
    fun save(credentials: Credentials)
    fun selectedChildId(): String?
    fun saveSelectedChildId(childId: String?)
    /** Pronote a refusé le jeton en arrière-plan : une reconnexion est à faire au prochain premier plan. */
    fun needsRelogin(): Boolean
    fun markNeedsRelogin()
    fun clear()
}
