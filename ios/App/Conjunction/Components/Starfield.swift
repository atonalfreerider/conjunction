import SwiftUI

/// One procedurally placed star (positions are 0..1 fractions of the screen).
private struct Star {
    let x: CGFloat, y: CGFloat, radius: CGFloat, depth: CGFloat, twinkle: CGFloat, warm: Bool
}

/// Deterministic star layer (a tiny LCG, so the sky is the same on every launch).
private let stars: [Star] = {
    var seed: UInt64 = 7
    func next() -> CGFloat {
        seed = seed &* 6364136223846793005 &+ 1442695040888963407
        return CGFloat(seed >> 11) / CGFloat(UInt64(1) << 53)
    }
    return (0..<90).map { _ in
        Star(x: next(), y: next(), radius: 0.6 + next() * 1.8, depth: 0.2 + next() * 0.8, twinkle: next() * 2 * .pi, warm: next() < 0.35)
    }
}()

/// The app's living backdrop (Android `StarfieldBackground`): the painted starfield drifting very
/// slowly, a procedural star layer on top that moves with parallax and twinkles, and a soft vignette
/// so text stays readable. Only the Canvas and the image's transform change per frame; the content
/// on top is laid out once. With Reduce Motion everything holds still.
struct StarfieldBackground<Content: View>: View {
    var background = "bg_starfield"
    var dim: Double = 0.35
    @ViewBuilder var content: Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Palette.midnight.ignoresSafeArea()
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { timeline in
                let t = timeline.date.timeIntervalSinceReferenceDate
                // 90 s drift, reversing; 7 s twinkle.
                let cycle = (t / 90).truncatingRemainder(dividingBy: 2)
                let d = reduceMotion ? 0.5 : CGFloat(cycle < 1 ? cycle : 2 - cycle)
                let tw = reduceMotion ? 0 : CGFloat((t / 7).truncatingRemainder(dividingBy: 1)) * 2 * .pi
                GeometryReader { geo in
                    let w = geo.size.width, h = geo.size.height
                    Image(background).resizable().scaledToFill()
                        .frame(width: w, height: h)
                        .scaleEffect(1.08)
                        .offset(x: (d - 0.5) * w * 0.05, y: (d - 0.5) * h * 0.03)
                        .clipped()
                    Canvas { ctx, size in
                        for s in stars {
                            let px = ((s.x + d * 0.04 * s.depth).truncatingRemainder(dividingBy: 1)) * size.width
                            let py = ((s.y + d * 0.025 * s.depth).truncatingRemainder(dividingBy: 1)) * size.height
                            let glow = 0.45 + 0.55 * ((sin(tw + s.twinkle) + 1) / 2)
                            let color = s.warm ? Palette.goldPale : Palette.moonstone
                            let halo = s.radius * 3.2
                            ctx.fill(Path(ellipseIn: CGRect(x: px - halo, y: py - halo, width: 2 * halo, height: 2 * halo)),
                                     with: .color(color.opacity(0.18 * glow)))
                            ctx.fill(Path(ellipseIn: CGRect(x: px - s.radius, y: py - s.radius, width: 2 * s.radius, height: 2 * s.radius)),
                                     with: .color(color.opacity(0.85 * glow)))
                        }
                        let top = dim >= 0.7 ? dim : dim * 0.6
                        let vignette = Gradient(stops: [
                            .init(color: Palette.midnight.opacity(top), location: 0),
                            .init(color: .clear, location: 0.45),
                            .init(color: Palette.midnight.opacity(min(dim + 0.25, 1)), location: 1),
                        ])
                        ctx.fill(Path(CGRect(origin: .zero, size: size)),
                                 with: .linearGradient(vignette, startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
                    }
                }
                .ignoresSafeArea()
            }
            .accessibilityHidden(true)
            content
        }
    }
}

/// Common tab page: starfield, title, scrolling content capped to a readable width.
struct TabPage<Content: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var content: Content

    var body: some View {
        StarfieldBackground {
            ScrollView {
                VStack(spacing: 16) {
                    SectionTitle(text: title, subtitle: subtitle)
                    content
                    Spacer().frame(height: 24)
                }
                .frame(maxWidth: 640)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }
}
