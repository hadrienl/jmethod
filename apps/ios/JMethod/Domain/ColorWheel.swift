import Foundation

/// Roue chromatique des matières (#8) : conversions HSV ↔ `#RRGGBB` identiques à Kotlin
/// (vecteurs `native/shared/domain/color-vectors.json`). Couleur opaque, sans canal alpha.
enum ColorWheel {
    /// Teinte dans [0, 360[, saturation et valeur dans [0, 1].
    struct HSV: Equatable, Sendable {
        var hue: Double
        var saturation: Double
        var value: Double

        init(hue: Double, saturation: Double, value: Double) {
            let turned = hue.truncatingRemainder(dividingBy: 360)
            self.hue = turned < 0 ? turned + 360 : turned
            self.saturation = min(1, max(0, saturation))
            self.value = min(1, max(0, value))
        }
    }

    /// Six chiffres hexadécimaux, avec ou sans `#`, espaces de bord ignorés ; normalisé en `#RRGGBB` majuscule.
    static func normalized(_ input: String) -> String? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("#") { text.removeFirst() }
        guard text.count == 6, text.allSatisfy(\.isHexDigit) else { return nil }
        return "#" + text.uppercased()
    }

    static func rgb(_ hex: String) -> (r: Int, g: Int, b: Int)? {
        guard let value = normalized(hex), let number = Int(value.dropFirst(), radix: 16) else { return nil }
        return ((number >> 16) & 0xFF, (number >> 8) & 0xFF, number & 0xFF)
    }

    static func hsv(_ hex: String) -> HSV? {
        guard let (r, g, b) = rgb(hex) else { return nil }
        let red = Double(r) / 255, green = Double(g) / 255, blue = Double(b) / 255
        let high = max(red, green, blue), low = min(red, green, blue), delta = high - low
        var hue: Double
        if delta == 0 { hue = 0 }
        else if high == red {
            hue = 60 * ((green - blue) / delta).truncatingRemainder(dividingBy: 6)
            if hue < 0 { hue += 360 }
        } else if high == green { hue = 60 * ((blue - red) / delta + 2) }
        else { hue = 60 * ((red - green) / delta + 4) }
        return HSV(hue: hue, saturation: high == 0 ? 0 : delta / high, value: high)
    }

    /// Canaux arrondis au plus proche (moitié vers le haut), comme Kotlin.
    static func hex(_ color: HSV) -> String {
        let chroma = color.value * color.saturation
        let sector = color.hue / 60
        let x = chroma * (1 - abs(sector.truncatingRemainder(dividingBy: 2) - 1))
        let m = color.value - chroma
        let (r, g, b): (Double, Double, Double)
        switch Int(sector) % 6 {
        case 0: (r, g, b) = (chroma, x, 0)
        case 1: (r, g, b) = (x, chroma, 0)
        case 2: (r, g, b) = (0, chroma, x)
        case 3: (r, g, b) = (0, x, chroma)
        case 4: (r, g, b) = (x, 0, chroma)
        default: (r, g, b) = (chroma, 0, x)
        }
        func channel(_ value: Double) -> String { String(format: "%02X", Int(((value + m) * 255 + 0.5).rounded(.down))) }
        return "#" + channel(r) + channel(g) + channel(b)
    }

    /// Lecture d'un code en gardant la teinte (et la saturation au noir) précédentes quand elles sont indéterminées,
    /// pour que les curseurs ne sautent pas au rouge en passant par le gris ou le noir.
    static func hsv(_ hex: String, keeping previous: HSV) -> HSV? {
        guard var color = hsv(hex) else { return nil }
        if color.value == 0 { color.hue = previous.hue; color.saturation = previous.saturation }
        else if color.saturation == 0 { color.hue = previous.hue }
        return color
    }

    /// Teinte d'un point de l'anneau relatif à son centre (y vers le bas) : 0 = rouge en haut, sens horaire. Nil au centre.
    static func hue(dx: Double, dy: Double) -> Double? {
        guard dx != 0 || dy != 0 else { return nil }
        var degrees = atan2(dx, -dy) * 180 / .pi
        if degrees < 0 { degrees += 360 }
        return degrees >= 360 ? 0 : degrees
    }

    /// Direction unitaire (y vers le bas) du curseur de l'anneau pour une teinte.
    static func ringDirection(hue: Double) -> (dx: Double, dy: Double) {
        let radians = hue * .pi / 180
        return (sin(radians), -cos(radians))
    }

    /// Carré : saturation de gauche à droite, valeur de haut (1) en bas (0) ; coordonnées bornées.
    static func square(x: Double, y: Double, width: Double, height: Double) -> (saturation: Double, value: Double) {
        let saturation = width > 0 ? min(1, max(0, x / width)) : 0
        let value = height > 0 ? 1 - min(1, max(0, y / height)) : 1
        return (saturation, value)
    }

    static func squarePoint(saturation: Double, value: Double, width: Double, height: Double) -> (x: Double, y: Double) {
        (min(1, max(0, saturation)) * width, (1 - min(1, max(0, value))) * height)
    }

    /// Pas de saturation ou de luminosité (clavier, lecteur d'écran) : la valeur est ramenée sur la grille du pas
    /// avant l'ajout, pour tomber sur des valeurs rondes ; résultat borné et arrondi à 1e-6.
    static func step(_ level: Double, by delta: Double) -> Double {
        let size = abs(delta)
        guard size > 0 else { return level }
        let grid = (level / size).rounded() * size
        return min(1, max(0, ((grid + delta) * 1e6).rounded() / 1e6))
    }

    /// Nom approximatif annoncé par VoiceOver et TalkBack, identique sur les deux plateformes.
    static func name(_ color: HSV) -> String {
        if color.value < 0.1 { return "noir" }
        if color.saturation < 0.1 { return color.value > 0.9 ? "blanc" : "gris" }
        switch color.hue {
        case ..<15: return "rouge"
        case ..<45: return "orange"
        case ..<70: return "jaune"
        case ..<150: return "vert"
        case ..<200: return "cyan"
        case ..<260: return "bleu"
        case ..<290: return "violet"
        case ..<345: return "rose"
        default: return "rouge"
        }
    }

    /// Luminance relative WCAG 2.
    static func luminance(_ hex: String) -> Double? {
        guard let (r, g, b) = rgb(hex) else { return nil }
        func linear(_ channel: Int) -> Double {
            let c = Double(channel) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }

    static func contrast(_ a: String, _ b: String) -> Double? {
        guard let first = luminance(a), let second = luminance(b) else { return nil }
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    /// Couleur trop claire sur fond blanc (< 3:1) : la pastille garde la couleur brute mais reçoit un contour neutre.
    static func needsOutline(_ hex: String) -> Bool { (contrast(hex, "#FFFFFF") ?? 1) < 3 }
}
