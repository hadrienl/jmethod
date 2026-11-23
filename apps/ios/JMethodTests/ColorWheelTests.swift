import XCTest
@testable import JMethod

/// Vecteurs communs avec Kotlin : `native/shared/domain/color-vectors.json`.
final class ColorWheelTests: XCTestCase {
    private func triple(_ value: Any?) throws -> ColorWheel.HSV {
        let numbers = try XCTUnwrap(value as? [NSNumber]).map(\.doubleValue)
        return ColorWheel.HSV(hue: numbers[0], saturation: numbers[1], value: numbers[2])
    }
    private func assertClose(_ a: ColorWheel.HSV?, _ b: ColorWheel.HSV, _ label: String) throws {
        let a = try XCTUnwrap(a, label)
        XCTAssertEqual(a.hue, b.hue, accuracy: 1e-6, label)
        XCTAssertEqual(a.saturation, b.saturation, accuracy: 1e-6, label)
        XCTAssertEqual(a.value, b.value, accuracy: 1e-6, label)
    }

    func testSharedVectors() throws {
        let fixture = try SharedVectors.object("color-vectors.json", subdirectory: "domain")
        for item in try XCTUnwrap(fixture["toHsv"] as? [[String: Any]]) {
            let hex = try XCTUnwrap(item["hex"] as? String)
            try assertClose(ColorWheel.hsv(hex), triple(item["hsv"]), "toHsv \(hex)")
        }
        for item in try XCTUnwrap(fixture["toHex"] as? [[String: Any]]) {
            XCTAssertEqual(ColorWheel.hex(try triple(item["hsv"])), item["hex"] as? String, "toHex \(String(describing: item["hsv"]))")
        }
        for item in try XCTUnwrap(fixture["parse"] as? [[String: Any]]) {
            XCTAssertEqual(ColorWheel.normalized(try XCTUnwrap(item["input"] as? String)), item["hex"] as? String, "parse \(String(describing: item["input"]))")
        }
        for item in try XCTUnwrap(fixture["ring"] as? [[String: Any]]) {
            let hue = ColorWheel.hue(dx: try XCTUnwrap(item["dx"] as? Double), dy: try XCTUnwrap(item["dy"] as? Double))
            if let expected = item["hue"] as? Double {
                XCTAssertEqual(try XCTUnwrap(hue), expected, accuracy: 1e-9)
                let direction = ColorWheel.ringDirection(hue: expected)
                XCTAssertEqual(ColorWheel.hue(dx: direction.dx, dy: direction.dy) ?? -1, expected, accuracy: 1e-9)
            } else { XCTAssertNil(hue) }
        }
        for item in try XCTUnwrap(fixture["square"] as? [[String: Any]]) {
            let n = { (key: String) in (item[key] as? NSNumber)?.doubleValue ?? .nan }
            let result = ColorWheel.square(x: n("x"), y: n("y"), width: n("width"), height: n("height"))
            XCTAssertEqual(result.saturation, n("saturation"), accuracy: 1e-9)
            XCTAssertEqual(result.value, n("value"), accuracy: 1e-9)
        }
        for item in try XCTUnwrap(fixture["keepHue"] as? [[String: Any]]) {
            let hex = try XCTUnwrap(item["hex"] as? String)
            try assertClose(ColorWheel.hsv(hex, keeping: triple(item["previous"])), triple(item["hsv"]), "keepHue \(hex)")
        }
        for item in try XCTUnwrap(fixture["contrast"] as? [[String: Any]]) {
            let ratio = try XCTUnwrap(ColorWheel.contrast(try XCTUnwrap(item["a"] as? String), try XCTUnwrap(item["b"] as? String)))
            XCTAssertEqual(ratio, try XCTUnwrap((item["ratio"] as? NSNumber)?.doubleValue), accuracy: 1e-4)
        }
        for item in try XCTUnwrap(fixture["step"] as? [[String: Any]]) {
            let n = { (key: String) in (item[key] as? NSNumber)?.doubleValue ?? .nan }
            XCTAssertEqual(ColorWheel.step(n("level"), by: n("delta")), n("result"), accuracy: 1e-9, "step \(item)")
        }
        for item in try XCTUnwrap(fixture["name"] as? [[String: Any]]) {
            XCTAssertEqual(ColorWheel.name(try triple(item["hsv"])), item["name"] as? String, "name \(String(describing: item["hsv"]))")
        }
        for item in try XCTUnwrap(fixture["needsOutline"] as? [[String: Any]]) {
            let hex = try XCTUnwrap(item["hex"] as? String)
            XCTAssertEqual(ColorWheel.needsOutline(hex), item["outline"] as? Bool, "outline \(hex)")
        }
    }

    /// Aller-retour exact sur une grille RGB, et tour complet de teinte à un canal près.
    func testRoundTripAndHueTurn() throws {
        for r in stride(from: 0, through: 255, by: 15) {
            for g in stride(from: 0, through: 255, by: 15) {
                for b in stride(from: 0, through: 255, by: 15) {
                    let hex = String(format: "#%02X%02X%02X", r, g, b)
                    XCTAssertEqual(ColorWheel.hex(try XCTUnwrap(ColorWheel.hsv(hex))), hex)
                }
            }
        }
        for degree in 0..<360 {
            let hex = ColorWheel.hex(.init(hue: Double(degree), saturation: 1, value: 1))
            let back = try XCTUnwrap(ColorWheel.hsv(hex))
            let distance = min(abs(back.hue - Double(degree)), 360 - abs(back.hue - Double(degree)))
            XCTAssertLessThanOrEqual(distance, 360.0 / 255, "teinte \(degree)")
        }
    }
}
