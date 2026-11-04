import SwiftUI
import ConjunctionCore

/// Synastry bi-wheel (SPEC 5d.4; Android `ui/chart/SynastryWheel`): zodiac ring; inner ring = me,
/// outer ring = them, each with Sun, Moon, Venus, Mars and the Ascendant (when known) at their true
/// longitudes; cross-aspect lines for every in-orb `lines` entry, gold for conjunction / sextile /
/// trine, rose-copper for square / opposition, width by weight. Their points show sign glyphs only
/// (never degrees). It draws itself in 1.2 s: the progress is the Canvas view's animatable data, so
/// only the drawing changes per frame (no relayout). Reduce Motion shows the finished wheel.
struct SynastryWheel: View {
    let me: CompatProfile
    let them: CompatProfile
    let lines: [Compatibility.SynastryLine]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress: Double = 0

    var body: some View {
        BiWheelCanvas(me: me, them: them, lines: lines, t: progress)
            .aspectRatio(1, contentMode: .fit)
            .onAppear(perform: start)
            .onChange(of: them) { _, _ in start() }
            .accessibilityElement()
            .accessibilityLabel("Synastry wheel: your points inside, theirs outside, \(lines.count) aspects between you.")
    }

    private func start() {
        if reduceMotion {
            progress = 1
        } else {
            progress = 0
            withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 1.2)) { progress = 1 }
        }
    }
}

private struct BiWheelCanvas: View, Animatable {
    let me: CompatProfile
    let them: CompatProfile
    let lines: [Compatibility.SynastryLine]
    var t: Double

    var animatableData: Double {
        get { t }
        set { t = newValue }
    }

    private func clamp(_ x: Double) -> Double { min(max(x, 0), 1) }

    private func polar(_ c: CGPoint, _ r: CGFloat, _ angleDeg: Double) -> CGPoint {
        let a = angleDeg * .pi / 180
        return CGPoint(x: c.x + r * CGFloat(cos(a)), y: c.y - r * CGFloat(sin(a)))
    }

    var body: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let outer = min(size.width, size.height) / 2 * 0.98
            let signInner = outer * 0.84, theirRing = outer * 0.70, myRing = outer * 0.46
            let theirDots = theirRing - outer * 0.08
            let ring = clamp(t / 0.5)
            let pointsT = clamp((t - 0.3) / 0.4)
            let linesT = clamp((t - 0.6) / 0.4)
            let goldStops = Gradient(colors: [Palette.goldDeep, Palette.goldPale, Palette.gold, Palette.goldDeep, Palette.goldPale, Palette.goldDeep])
            let gold = GraphicsContext.Shading.conicGradient(goldStops, center: c)
            func angle(_ lon: Double) -> Double { ChartWheelGeometry.wheelAngle(lon, ascendant: nil) }
            func line(_ a: CGPoint, _ b: CGPoint, _ color: Color, _ width: CGFloat) {
                var p = Path()
                p.move(to: a)
                p.addLine(to: b)
                ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
            }
            func text(_ s: String, _ at: CGPoint, size: CGFloat, color: Color, opacity: Double) {
                var inner = ctx
                inner.opacity = opacity
                inner.draw(Text(s).font(.system(size: size)).foregroundColor(color), at: at)
            }

            ctx.fill(Path(ellipseIn: CGRect(x: c.x - outer, y: c.y - outer, width: 2 * outer, height: 2 * outer)),
                     with: .radialGradient(Gradient(colors: [Palette.dusk.opacity(0.85), Palette.midnight.opacity(0.92)]),
                                           center: c, startRadius: 0, endRadius: outer))
            for (r, w) in [(outer, 2.2), (signInner, 1.1), (theirDots, 0.8), (myRing + outer * 0.07, 0.8)] as [(CGFloat, CGFloat)] {
                var arc = Path()
                arc.addArc(center: c, radius: r, startAngle: .degrees(180), endAngle: .degrees(180 + 360 * ring), clockwise: false)
                ctx.stroke(arc, with: gold, style: StrokeStyle(lineWidth: w, lineCap: .round))
            }
            for sign in ZodiacSign.allCases {
                let start = angle(Double(sign.ordinal) * 30)
                let reveal = clamp(ring * 12 - Double(sign.ordinal))
                if reveal <= 0 { continue }
                line(polar(c, signInner, start), polar(c, outer, start), Palette.gold.opacity(0.6 * reveal), 1)
                text(ChartWheelGeometry.textGlyph(sign.glyph), polar(c, (outer + signInner) / 2, start + 15),
                     size: outer / 14, color: Palette.goldPale, opacity: reveal)
            }
            let mine = Dictionary(uniqueKeysWithValues: CompatView.points(me).map { ($0.0.rawValue, $0.1) })
            let theirs = Dictionary(uniqueKeysWithValues: CompatView.points(them).map { ($0.0.rawValue, $0.1) })
            // Cross-aspect lines from my point (inner ring) to theirs (outer ring).
            if linesT > 0 {
                for l in lines {
                    guard let a = mine[l.pointA.rawValue], let b = theirs[l.pointB.rawValue] else { continue }
                    let pa = polar(c, myRing, angle(a))
                    let pb = polar(c, theirDots, angle(b))
                    let end = CGPoint(x: pa.x + (pb.x - pa.x) * linesT, y: pa.y + (pb.y - pa.y) * linesT)
                    let tight = min(max(1 - Double(l.orbCentideg) / Double(max(l.aspect.orb, 1)), 0.3), 1)
                    let color = l.isHarmonious ? Palette.gold : Palette.roseCopper
                    line(pa, end, color.opacity(0.85 * tight), 0.6 + CGFloat(l.weight) * 0.4)
                }
            }
            func drawPoints(_ points: [(Compatibility.Point, Double)], radius: CGFloat, dotRadius: CGFloat, color: Color, withSign: Bool) {
                let spread = ChartWheelGeometry.spreadLongitudes(points.map { $0.1 }, minGap: 11)
                for (i, (p, lon)) in points.enumerated() {
                    let dot = polar(c, dotRadius, angle(lon))
                    ctx.fill(Path(ellipseIn: CGRect(x: dot.x - 2.4, y: dot.y - 2.4, width: 4.8, height: 4.8)), with: .color(color.opacity(pointsT)))
                    text(CompatView.pointGlyph(p), polar(c, radius, angle(spread[i])), size: outer / 16, color: color, opacity: pointsT)
                    if withSign {
                        // Their points: sign glyph only, never degrees.
                        text(ChartWheelGeometry.textGlyph(ZodiacSign.of(lon).glyph), polar(c, radius + outer * 0.075, angle(spread[i])),
                             size: outer / 24, color: Palette.moonstoneDim, opacity: pointsT)
                    }
                }
            }
            drawPoints(CompatView.points(them), radius: theirRing, dotRadius: theirDots, color: Color(hex: 0xEB7B63), withSign: true)
            drawPoints(CompatView.points(me), radius: myRing - outer * 0.1, dotRadius: myRing, color: Palette.goldPale, withSign: false)
            let jr = outer * 0.03 * (0.5 + 0.5 * t)
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - jr, y: c.y - jr, width: 2 * jr, height: 2 * jr)), with: gold)
        }
    }
}
