package fr.jmethod.app.pronote

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verrou commun au premier plan et à l’arrière-plan (un seul objet par processus) : une seule
 * connexion ou lecture Pronote à la fois, donc jamais deux utilisations du même jeton mobile
 * qui se le feraient refuser après rotation.
 */
class PronoteSessionGuard {
    /** Sérialise connexions et lectures. */
    val mutex = Mutex()
    /** Protège l’écriture du jeton contre une déconnexion simultanée. */
    val vaultLock = Any()
    /** Incrémenté uniquement par la déconnexion : aucun résultat tardif ne peut recréer une session oubliée. */
    val epoch = AtomicInteger(0)
}
