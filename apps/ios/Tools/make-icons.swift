// Génère l'icône de l'app (claire, sombre, teintée) et le logo de l'écran de lancement, par CoreGraphics.
// Usage : swift apps/ios/Tools/make-icons.swift apps/ios/JMethod/Assets.xcassets
// Dessin : un « j » géométrique blanc dont le point est une pastille citron, sur un fond violet.
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let catalog = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "apps/ios/JMethod/Assets.xcassets")

func rgb(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}

struct Palette {
    var backgroundTop: CGColor?
    var backgroundBottom: CGColor?
    var glyph: CGColor
    var dot: CGColor
}

/// Dessine le monogramme dans un carré de 1024 unités (y vers le bas).
func drawMark(_ context: CGContext, palette: Palette) {
    // Tige et crochet : un seul trait à bouts ronds, séparé du point par un espace net.
    let stem = CGMutablePath()
    let x: CGFloat = 636, top: CGFloat = 462, bend: CGFloat = 638, radius: CGFloat = 130
    stem.move(to: CGPoint(x: x, y: top))
    stem.addLine(to: CGPoint(x: x, y: bend))
    stem.addArc(center: CGPoint(x: x - radius, y: bend), radius: radius, startAngle: 0, endAngle: .pi * 0.86, clockwise: false)
    context.setLineCap(.round)
    context.setLineJoin(.round)
    context.setLineWidth(140)
    context.setStrokeColor(palette.glyph)
    context.addPath(stem)
    context.strokePath()
    // Point citron.
    context.setFillColor(palette.dot)
    context.fillEllipse(in: CGRect(x: x - 90, y: 180, width: 180, height: 180))
}

func render(size: Int, palette: Palette, cornerRadius: CGFloat? = nil) -> CGImage {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                            // L'icône est opaque, sans couche alpha (exigence de l'App Store) ; le logo arrondi garde la sienne.
                            bitmapInfo: (cornerRadius == nil ? CGImageAlphaInfo.noneSkipLast : .premultipliedLast).rawValue)!
    let scale = CGFloat(size) / 1024
    // Repère y vers le bas, en unités 1024.
    context.translateBy(x: 0, y: CGFloat(size))
    context.scaleBy(x: scale, y: -scale)
    let bounds = CGRect(x: 0, y: 0, width: 1024, height: 1024)
    if let radius = cornerRadius {
        context.addPath(CGPath(roundedRect: bounds, cornerWidth: radius, cornerHeight: radius, transform: nil))
        context.clip()
    }
    if let top = palette.backgroundTop, let bottom = palette.backgroundBottom {
        let gradient = CGGradient(colorsSpace: space, colors: [top, bottom] as CFArray, locations: [0, 1])!
        context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: 0), end: CGPoint(x: 1024, y: 1024), options: [])
    }
    drawMark(context, palette: palette)
    return context.makeImage()!
}

func write(_ image: CGImage, _ url: URL) {
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("écriture impossible : \(url.path)") }
}

func json(_ object: Any, _ url: URL) {
    let data = try! JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys])
    try! data.write(to: url)
}

let violet600: UInt32 = 0x7C3AED, violet800: UInt32 = 0x5B21B6, violet950: UInt32 = 0x2E1065
let lime: UInt32 = 0xD9F99D, lime300: UInt32 = 0xBEF264

let light = Palette(backgroundTop: rgb(violet600), backgroundBottom: rgb(violet800), glyph: rgb(0xFFFFFF), dot: rgb(lime))
let dark = Palette(backgroundTop: rgb(0x241047), backgroundBottom: rgb(0x120726), glyph: rgb(0xDDD6FE), dot: rgb(lime300))
// Teintée : niveaux de gris, la teinte choisie par l'utilisateur s'applique à la luminance.
let tinted = Palette(backgroundTop: rgb(0x000000), backgroundBottom: rgb(0x000000), glyph: rgb(0xFFFFFF), dot: rgb(0xBDBDBD))

// Icône
let iconSet = catalog.appendingPathComponent("AppIcon.appiconset")
write(render(size: 1024, palette: light), iconSet.appendingPathComponent("AppIcon.png"))
write(render(size: 1024, palette: dark), iconSet.appendingPathComponent("AppIcon-Dark.png"))
write(render(size: 1024, palette: tinted), iconSet.appendingPathComponent("AppIcon-Tinted.png"))
json([
    "images": [
        ["filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"],
        ["appearances": [["appearance": "luminosity", "value": "dark"]], "filename": "AppIcon-Dark.png",
         "idiom": "universal", "platform": "ios", "size": "1024x1024"],
        ["appearances": [["appearance": "luminosity", "value": "tinted"]], "filename": "AppIcon-Tinted.png",
         "idiom": "universal", "platform": "ios", "size": "1024x1024"],
    ],
    "info": ["author": "xcode", "version": 1],
], iconSet.appendingPathComponent("Contents.json"))

// Logo de l'écran de lancement et de l'écran d'attente : 96 pt, coins arrondis.
let logoSet = catalog.appendingPathComponent("BrandLogo.imageset")
for scale in 1...3 {
    write(render(size: 96 * scale, palette: light, cornerRadius: 230), logoSet.appendingPathComponent("BrandLogo@\(scale)x.png"))
}
json([
    "images": (1...3).map { ["filename": "BrandLogo@\($0)x.png", "idiom": "universal", "scale": "\($0)x"] },
    "info": ["author": "xcode", "version": 1],
], logoSet.appendingPathComponent("Contents.json"))

// Fond de l'écran de lancement : fond de l'app (slate-50).
let colorSet = catalog.appendingPathComponent("LaunchBackground.colorset")
try? FileManager.default.createDirectory(at: colorSet, withIntermediateDirectories: true)
json([
    "colors": [["color": ["color-space": "srgb", "components": ["red": "0xF8", "green": "0xFA", "blue": "0xFC", "alpha": "1.000"]],
                "idiom": "universal"]],
    "info": ["author": "xcode", "version": 1],
], colorSet.appendingPathComponent("Contents.json"))

json(["info": ["author": "xcode", "version": 1]], catalog.appendingPathComponent("Contents.json"))
print("Icônes écrites dans \(catalog.path)")
