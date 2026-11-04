import SwiftUI
import ConjunctionCore

/// The natal chart wheel, drawn with Canvas and animated in (Android `ChartWheel`): the zodiac ring
/// sweeps around from the Ascendant, the house spokes and planets appear, then the aspect web is
/// drawn across the centre. Reduce Motion shows the finished wheel at once.
struct ChartWheel: View {
    let chart: NatalChart
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var progress: Double = 0

    var body: some View {
        WheelCanvas(chart: chart, t: progress)
            .aspectRatio(1, contentMode: .fit)
            .onAppear { start() }
            .onChange(of: chart) { _, _ in start() }
            .accessibilityElement()
            .accessibilityLabel(description)
    }

    private func start() {
        if reduceMotion {
            progress = 1
        } else {
            progress = 0
            withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 2.6)) { progress = 1 }
        }
    }

    private var description: String {
        var s = "Birth chart wheel. "
        for b in chart.bodies { s += "\(b.name) \(formatLongitude(b.longitude)). " }
        if let a = chart.ascendant { s += "Ascendant \(formatLongitude(a))." }
        return s
    }
}

/// The drawing, with the reveal progress as animatable data (redrawn per frame, no relayout).
private struct WheelCanvas: View, Animatable {
    let chart: NatalChart
    var t: Double

    var animatableData: Double {
        get { t }
        set { t = newValue }
    }

    private func clamp(_ x: Double) -> Double { min(max(x, 0), 1) }

    private func polar(_ c: CGPoint, _ r: CGFloat, _ angleDeg: Double) -> CGPoint {
        let a = angleDeg * .pi / 180
        // Screen y grows downward, so counter-clockwise chart angles subtract.
        return CGPoint(x: c.x + r * CGFloat(cos(a)), y: c.y - r * CGFloat(sin(a)))
    }

    private func aspectColor(_ type: AspectType) -> Color {
        switch type {
        case .trine, .sextile: return Palette.gold
        case .square, .opposition: return Palette.roseCopper
        case .conjunction: return Palette.moonstone
        }
    }

    var body: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let outer = min(size.width, size.height) / 2 * 0.98
            let signInner = outer * 0.82, houseInner = outer * 0.70, aspectRadius = outer * 0.50
            let asc = chart.ascendant
            let ring = clamp(t / 0.45)
            let bodiesT = clamp((t - 0.35) / 0.35)
            let aspectsT = clamp((t - 0.65) / 0.35)
            let goldStops = Gradient(colors: [Palette.goldDeep, Palette.goldPale, Palette.gold, Palette.goldDeep, Palette.goldPale, Palette.goldDeep])
            let gold = GraphicsContext.Shading.conicGradient(goldStops, center: c)
            func angle(_ lon: Double) -> Double { ChartWheelGeometry.wheelAngle(lon, ascendant: asc) }
            func line(_ a: CGPoint, _ b: CGPoint, _ color: Color, _ width: CGFloat, dash: [CGFloat] = []) {
                var p = Path()
                p.move(to: a)
                p.addLine(to: b)
                ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: width, dash: dash))
            }
            func text(_ s: String, _ at: CGPoint, size: CGFloat, color: Color, opacity: Double) {
                var inner = ctx
                inner.opacity = opacity
                inner.draw(Text(s).font(.system(size: size)).foregroundColor(color), at: at)
            }

            // Glow and ground.
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - outer, y: c.y - outer, width: 2 * outer, height: 2 * outer)),
                     with: .radialGradient(Gradient(colors: [Palette.dusk.opacity(0.85), Palette.midnight.opacity(0.9)]),
                                           center: c, startRadius: 0, endRadius: outer))
            // Ring outlines sweep in from the Ascendant (the left, 180 deg).
            for (i, r) in [outer, signInner, houseInner, aspectRadius].enumerated() {
                var arc = Path()
                arc.addArc(center: c, radius: r, startAngle: .degrees(180), endAngle: .degrees(180 + 360 * ring), clockwise: false)
                ctx.stroke(arc, with: gold, style: StrokeStyle(lineWidth: i == 0 ? 2.2 : 1.1, lineCap: .round))
            }
            // Sign segments: boundary spokes, degree ticks, glyphs.
            for sign in ZodiacSign.allCases {
                let start = angle(Double(sign.ordinal) * 30)
                let reveal = clamp(ring * 12 - Double(sign.ordinal))
                if reveal <= 0 { continue }
                line(polar(c, signInner, start), polar(c, outer, start), Palette.gold.opacity(0.7 * reveal), 1)
                for d in stride(from: 5, to: 30, by: 5) {
                    let a = angle(Double(sign.ordinal) * 30 + Double(d))
                    let len = d == 15 ? 0.05 : 0.025
                    line(polar(c, signInner, a), polar(c, signInner + outer * len, a), Palette.gold.opacity(0.45 * reveal), 0.8)
                }
                text(ChartWheelGeometry.textGlyph(sign.glyph), polar(c, (outer + signInner) / 2, start + 15),
                     size: outer / 14, color: Palette.goldPale, opacity: reveal)
            }
            // House cusps and angles.
            if let houses = chart.houses {
                for (index, cusp) in houses.enumerated() {
                    let a = angle(cusp)
                    let isAngle = index % 3 == 0
                    line(polar(c, isAngle ? aspectRadius * 0.15 : aspectRadius, a), polar(c, signInner, a),
                         isAngle ? Palette.goldPale.opacity(bodiesT) : Palette.gold.opacity(0.35 * bodiesT),
                         isAngle ? 1.6 : 0.8, dash: isAngle ? [] : [6, 6])
                    text("\(index + 1)", polar(c, houseInner - outer * 0.045, a + 15), size: outer / 30,
                         color: Palette.moonstoneDim, opacity: bodiesT * 0.8)
                }
            }
            if asc != nil {
                let p = polar(c, outer * 0.9, 180)
                text("ASC", CGPoint(x: p.x + 14, y: p.y - outer / 20), size: outer / 26, color: Palette.goldPale, opacity: bodiesT)
            }
            // Aspect web.
            if aspectsT > 0 {
                for aspect in chart.aspects where aspect.type != .conjunction {
                    let pa = polar(c, aspectRadius, angle(chart.body(aspect.a).longitude))
                    let pb = polar(c, aspectRadius, angle(chart.body(aspect.b).longitude))
                    let end = CGPoint(x: pa.x + (pb.x - pa.x) * aspectsT, y: pa.y + (pb.y - pa.y) * aspectsT)
                    let tight = min(max(1 - aspect.orb / 8, 0.25), 1)
                    line(pa, end, aspectColor(aspect.type).opacity(0.75 * tight), 0.6 + tight)
                }
            }
            // Planets: glyph on the house ring, a tick at the true longitude.
            let spread = ChartWheelGeometry.spreadLongitudes(chart.bodies.map(\.longitude))
            for (i, b) in chart.bodies.enumerated() {
                let trueA = angle(b.longitude), shownA = angle(spread[i])
                line(polar(c, signInner, trueA), polar(c, signInner - outer * 0.03, trueA), Palette.goldPale.opacity(bodiesT), 1.6)
                let dot = polar(c, aspectRadius, trueA)
                ctx.fill(Path(ellipseIn: CGRect(x: dot.x - 2, y: dot.y - 2, width: 4, height: 4)), with: .color(Palette.goldPale.opacity(bodiesT)))
                let r = (houseInner + aspectRadius) / 2 + (1 - bodiesT) * outer * 0.2
                let color: Color
                switch b.planet {
                case .sun: color = Palette.gold
                case .venus, .mars: color = Palette.roseCopper
                default: color = Palette.moonstone
                }
                text(ChartWheelGeometry.textGlyph(b.planet.glyph) + (b.retrograde ? "℞" : ""), polar(c, r, shownA),
                     size: outer / 15, color: color, opacity: bodiesT)
            }
            // Centre jewel.
            let jr = outer * 0.035 * (0.5 + 0.5 * t)
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - jr, y: c.y - jr, width: 2 * jr, height: 2 * jr)), with: gold)
            if t > 0.999 {
                let mr = outer * 0.014
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - mr, y: c.y - mr, width: 2 * mr, height: 2 * mr)), with: .color(Palette.midnight))
            }
        }
    }
}
