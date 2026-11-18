import SwiftUI

/// Feu d'artifice natif (Canvas + TimelineView) quand la dernière séance du soir est validée,
/// et petite gerbe pour une séance validée à 75/100 ou plus. Rien n'est animé si « Réduire les animations » est actif.
struct CelebrationLayer: View {
    let celebration: Int
    let confetti: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var show: Show?

    struct Show: Identifiable, Equatable {
        let id = UUID()
        let bursts: [Burst]
        let start = Date()
        let duration: TimeInterval
    }

    var body: some View {
        ZStack {
            if let show {
                FireworksCanvas(show: show)
                    .id(show.id)
                    .task(id: show.id) {
                        try? await Task.sleep(for: .seconds(show.duration))
                        if self.show?.id == show.id { self.show = nil }
                    }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .ignoresSafeArea()
        .zIndex(15)
        .onChange(of: celebration) { old, new in
            guard new > old else { return }
            AccessibilityNotification.Announcement("Toutes tes révisions sont terminées. Bonne soirée !").post()
            guard !reduceMotion else { return }
            show = Show(bursts: Burst.finale(), duration: 5)
        }
        .onChange(of: confetti) { old, new in
            guard new > old, !reduceMotion, show == nil else { return }
            show = Show(bursts: [Burst.make(origin: CGPoint(x: 0.5, y: 0.7), delay: 0, count: 50, spread: .pi / 3, speed: 0.9,
                                            colors: ["#38BDF8", "#10B981", "#F59E0B", "#8B5CF6"])], duration: 3)
        }
    }
}

struct Burst: Equatable {
    struct Particle: Equatable {
        let angle: Double
        let speed: Double
        let color: Color
        let size: CGFloat
        let spin: Double
    }

    let origin: CGPoint
    let delay: TimeInterval
    let particles: [Particle]

    static let finaleColors = ["#7C3AED", "#FBBF24", "#38BDF8", "#34D399", "#FB7185"]

    /// Cinq gerbes espacées de 0,4 s, comme `CompletionFireworks.tsx`.
    static func finale() -> [Burst] {
        let origins = [CGPoint(x: 0.25, y: 0.3), CGPoint(x: 0.75, y: 0.4), CGPoint(x: 0.5, y: 0.2), CGPoint(x: 0.2, y: 0.5), CGPoint(x: 0.8, y: 0.25)]
        return origins.enumerated().map { index, origin in
            make(origin: origin, delay: Double(index) * 0.4, count: 75, spread: 2 * .pi, speed: 1, colors: finaleColors)
        }
    }

    /// `spread` en radians autour de la verticale (vers le haut).
    static func make(origin: CGPoint, delay: TimeInterval, count: Int, spread: Double, speed: Double, colors: [String]) -> Burst {
        let particles = (0..<count).map { _ in
            Particle(
                angle: -.pi / 2 + Double.random(in: -spread / 2...spread / 2),
                speed: speed * Double.random(in: 0.35...1),
                color: Color(hex: colors.randomElement() ?? "#7C3AED"),
                size: CGFloat.random(in: 5...9),
                spin: Double.random(in: -8...8)
            )
        }
        return Burst(origin: origin, delay: delay, particles: particles)
    }
}

private struct FireworksCanvas: View {
    let show: CelebrationLayer.Show
    private let lifetime: TimeInterval = 2.4

    var body: some View {
        TimelineView(.animation) { context in
            Canvas { canvas, size in
                let elapsed = context.date.timeIntervalSince(show.start)
                let scale = min(size.width, size.height) * 0.75
                for burst in show.bursts {
                    let t = elapsed - burst.delay
                    guard t >= 0, t <= lifetime else { continue }
                    let alpha = max(0, 1 - t / lifetime)
                    let originX = burst.origin.x * size.width
                    let originY = burst.origin.y * size.height
                    for particle in burst.particles {
                        // Vitesse amortie et gravité, comme une gerbe de confettis.
                        let travel = particle.speed * scale * 0.55 * (1 - exp(-2.2 * t))
                        let x = originX + cos(particle.angle) * travel
                        let y = originY + sin(particle.angle) * travel + 0.18 * scale * t * t
                        var piece = canvas
                        piece.opacity = alpha
                        piece.translateBy(x: x, y: y)
                        piece.rotate(by: .radians(particle.spin * t))
                        let rect = CGRect(x: -particle.size / 2, y: -particle.size / 4, width: particle.size, height: particle.size / 2)
                        piece.fill(Path(roundedRect: rect, cornerRadius: 1.5), with: .color(particle.color))
                    }
                }
            }
        }
    }
}
