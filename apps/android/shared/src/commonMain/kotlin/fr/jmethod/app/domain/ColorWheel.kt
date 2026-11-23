package fr.jmethod.app.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Roue chromatique des matières (#8) : conversions HSV ↔ `#RRGGBB` identiques à Swift
 * (vecteurs `native/shared/domain/color-vectors.json`). Couleur opaque, sans canal alpha.
 */
object ColorWheel {
    /** Teinte dans [0, 360[, saturation et valeur dans [0, 1]. */
    class HSV(hue: Double, saturation: Double, value: Double) {
        val hue: Double = (hue % 360).let { if (it < 0) it + 360 else it }
        val saturation: Double = saturation.coerceIn(0.0, 1.0)
        val value: Double = value.coerceIn(0.0, 1.0)
        fun copy(hue: Double = this.hue, saturation: Double = this.saturation, value: Double = this.value) = HSV(hue, saturation, value)
        override fun equals(other: Any?) = other is HSV && other.hue == hue && other.saturation == saturation && other.value == value
        override fun hashCode() = listOf(hue, saturation, value).hashCode()
        override fun toString() = "HSV($hue, $saturation, $value)"
    }

    data class RGB(val r: Int, val g: Int, val b: Int)

    /** Six chiffres hexadécimaux, avec ou sans `#`, espaces de bord ignorés ; normalisé en `#RRGGBB` majuscule. */
    fun normalized(input: String): String? {
        val text = input.trim().removePrefix("#")
        if (text.length != 6 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return "#" + text.uppercase()
    }

    fun rgb(hex: String): RGB? {
        val number = normalized(hex)?.drop(1)?.toInt(16) ?: return null
        return RGB((number shr 16) and 0xFF, (number shr 8) and 0xFF, number and 0xFF)
    }

    fun hsv(hex: String): HSV? {
        val (r, g, b) = rgb(hex) ?: return null
        val red = r / 255.0; val green = g / 255.0; val blue = b / 255.0
        val high = maxOf(red, green, blue); val low = minOf(red, green, blue); val delta = high - low
        val hue = when {
            delta == 0.0 -> 0.0
            high == red -> (60 * (((green - blue) / delta) % 6)).let { if (it < 0) it + 360 else it }
            high == green -> 60 * ((blue - red) / delta + 2)
            else -> 60 * ((red - green) / delta + 4)
        }
        return HSV(hue, if (high == 0.0) 0.0 else delta / high, high)
    }

    /** Canaux arrondis au plus proche (moitié vers le haut), comme Swift. */
    fun hex(color: HSV): String {
        val chroma = color.value * color.saturation
        val sector = color.hue / 60
        val x = chroma * (1 - abs(sector % 2 - 1))
        val m = color.value - chroma
        val (r, g, b) = when (sector.toInt() % 6) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        fun channel(value: Double) = floor((value + m) * 255 + 0.5).toInt().toString(16).uppercase().padStart(2, '0')
        return "#" + channel(r) + channel(g) + channel(b)
    }

    /**
     * Lecture d'un code en gardant la teinte (et la saturation au noir) précédentes quand elles sont indéterminées,
     * pour que les curseurs ne sautent pas au rouge en passant par le gris ou le noir.
     */
    fun hsv(hex: String, previous: HSV): HSV? {
        val color = hsv(hex) ?: return null
        return when {
            color.value == 0.0 -> color.copy(hue = previous.hue, saturation = previous.saturation)
            color.saturation == 0.0 -> color.copy(hue = previous.hue)
            else -> color
        }
    }

    /** Teinte d'un point de l'anneau relatif à son centre (y vers le bas) : 0 = rouge en haut, sens horaire. Null au centre. */
    fun hue(dx: Double, dy: Double): Double? {
        if (dx == 0.0 && dy == 0.0) return null
        var degrees = Math.toDegrees(atan2(dx, -dy))
        if (degrees < 0) degrees += 360
        return if (degrees >= 360) 0.0 else degrees
    }

    /** Direction unitaire (y vers le bas) du curseur de l'anneau pour une teinte. */
    fun ringDirection(hue: Double): Pair<Double, Double> {
        val radians = Math.toRadians(hue)
        return sin(radians) to -cos(radians)
    }

    /** Carré : saturation de gauche à droite, valeur de haut (1) en bas (0) ; coordonnées bornées. */
    fun square(x: Double, y: Double, width: Double, height: Double): Pair<Double, Double> {
        val saturation = if (width > 0) (x / width).coerceIn(0.0, 1.0) else 0.0
        val value = if (height > 0) 1 - (y / height).coerceIn(0.0, 1.0) else 1.0
        return saturation to value
    }

    fun squarePoint(saturation: Double, value: Double, width: Double, height: Double): Pair<Double, Double> =
        saturation.coerceIn(0.0, 1.0) * width to (1 - value.coerceIn(0.0, 1.0)) * height

    /**
     * Pas de saturation ou de luminosité (clavier, lecteur d’écran) : la valeur est ramenée sur la grille du pas
     * avant l’ajout, pour tomber sur des valeurs rondes ; résultat borné et arrondi à 1e-6.
     */
    fun step(level: Double, delta: Double): Double {
        val size = abs(delta)
        if (size == 0.0) return level
        val grid = Math.round(level / size) * size
        return (Math.round((grid + delta) * 1e6) / 1e6).coerceIn(0.0, 1.0)
    }

    /** Nom approximatif annoncé par VoiceOver et TalkBack, identique sur les deux plateformes. */
    fun name(color: HSV): String = when {
        color.value < 0.1 -> "noir"
        color.saturation < 0.1 -> if (color.value > 0.9) "blanc" else "gris"
        color.hue < 15 -> "rouge"
        color.hue < 45 -> "orange"
        color.hue < 70 -> "jaune"
        color.hue < 150 -> "vert"
        color.hue < 200 -> "cyan"
        color.hue < 260 -> "bleu"
        color.hue < 290 -> "violet"
        color.hue < 345 -> "rose"
        else -> "rouge"
    }

    /** Luminance relative WCAG 2. */
    fun luminance(hex: String): Double? {
        val (r, g, b) = rgb(hex) ?: return null
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }

    fun contrast(a: String, b: String): Double? {
        val first = luminance(a) ?: return null
        val second = luminance(b) ?: return null
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    /** Couleur trop claire sur fond blanc (< 3:1) : la pastille garde la couleur brute mais reçoit un contour neutre. */
    fun needsOutline(hex: String): Boolean = (contrast(hex, "#FFFFFF") ?: 1.0) < 3
}
