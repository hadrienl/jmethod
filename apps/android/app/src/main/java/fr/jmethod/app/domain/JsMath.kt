package fr.jmethod.app.domain

/**
 * `Math.round` de JavaScript : demi arrondi vers +∞ (`2.5 → 3`, `-2.5 → -2`).
 * `java.lang.Math.round` a la même définition ; ne pas utiliser `rint` ni `kotlin.math.round` (demi pair).
 */
fun jsRound(value: Double): Int = Math.round(value).toInt()
