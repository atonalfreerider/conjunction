import SwiftUI

// The ornamental building blocks every screen uses (Android ui/components/Ornaments.kt).

/// A band of gold light sliding over [mask] forever. The gradient is laid out once, three times as
/// wide as the view, and only its offset animates: offset is a render-time transform, so the
/// shimmer never re-lays out the text or panel under it (the Android app had to learn this too).
struct GoldShimmerFill<Mask: View>: View {
    var duration: Double = 6
    let mask: Mask
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var phase: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            let w = max(geo.size.width, 1)
            LinearGradient(colors: GoldBrush.shimmerStops + GoldBrush.shimmerStops.reversed().dropFirst(),
                           startPoint: UnitPoint(x: 0, y: 0), endPoint: UnitPoint(x: 1, y: 0.12))
                .frame(width: w * 3, height: geo.size.height)
                .offset(x: -2 * w + phase * 2 * w)
        }
        .allowsHitTesting(false)
        .mask(mask)
        .onAppear {
            if reduceMotion {
                phase = 0.35
            } else {
                withAnimation(.linear(duration: duration).repeatForever(autoreverses: false)) { phase = 1 }
            }
        }
    }
}

/// Text painted with shimmering gold leaf.
struct GoldText: View {
    let text: String
    var style: TypeStyle = .headlineMedium
    var alignment: TextAlignment = .center
    var heading = false

    init(_ text: String, style: TypeStyle = .headlineMedium, alignment: TextAlignment = .center, heading: Bool = false) {
        self.text = text
        self.style = style
        self.alignment = alignment
        self.heading = heading
    }

    private var label: some View {
        Text(text).typeStyle(style).multilineTextAlignment(alignment)
    }

    var body: some View {
        label
            .foregroundStyle(Palette.gold)
            .overlay(GoldShimmerFill(mask: label))
            .accessibilityAddTraits(heading ? .isHeader : [])
    }
}

/// Art Nouveau rule: gold hairline with a diamond and two dots in the middle.
struct OrnateDivider: View {
    var width: CGFloat = 220

    var body: some View {
        Canvas { ctx, size in
            let cy = size.height / 2, cx = size.width / 2
            let line = Gradient(colors: [.clear, Palette.gold, Palette.goldPale, Palette.gold, .clear])
            var left = Path()
            left.move(to: CGPoint(x: 0, y: cy))
            left.addLine(to: CGPoint(x: cx - 14, y: cy))
            var right = Path()
            right.move(to: CGPoint(x: cx + 14, y: cy))
            right.addLine(to: CGPoint(x: size.width, y: cy))
            let shading = GraphicsContext.Shading.linearGradient(line, startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0))
            ctx.stroke(left, with: shading, lineWidth: 1.2)
            ctx.stroke(right, with: shading, lineWidth: 1.2)
            var diamond = Path()
            diamond.move(to: CGPoint(x: cx, y: cy - 6))
            diamond.addLine(to: CGPoint(x: cx + 6, y: cy))
            diamond.addLine(to: CGPoint(x: cx, y: cy + 6))
            diamond.addLine(to: CGPoint(x: cx - 6, y: cy))
            diamond.closeSubpath()
            ctx.fill(diamond, with: .linearGradient(Gradient(colors: [Palette.goldPale, Palette.gold, Palette.goldDeep]),
                                                    startPoint: CGPoint(x: cx, y: cy - 6), endPoint: CGPoint(x: cx, y: cy + 6)))
            for dx in [-11.0, 11.0] {
                ctx.fill(Path(ellipseIn: CGRect(x: cx + dx - 1.8, y: cy - 1.8, width: 3.6, height: 3.6)), with: .color(Palette.goldPale))
            }
        }
        .frame(width: width, height: 16)
        .accessibilityHidden(true)
    }
}

/// An octagon with cut corners (Compose CutCornerShape).
struct CutCornerShape: InsettableShape {
    var cut: CGFloat
    var inset: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: inset, dy: inset)
        let c = min(cut, r.width / 2, r.height / 2)
        var p = Path()
        p.move(to: CGPoint(x: r.minX + c, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX - c, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.minY + c))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - c))
        p.addLine(to: CGPoint(x: r.maxX - c, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX + c, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY - c))
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + c))
        p.closeSubpath()
        return p
    }

    func inset(by amount: CGFloat) -> CutCornerShape { CutCornerShape(cut: cut, inset: inset + amount) }
}

/// The filigree frame: double gold rule with cut corners, corner fleurons and edge studs, as one
/// filled shape (so it can be gilded by `GoldShimmerFill`).
struct FiligreeFrame: Shape {
    var inset: CGFloat = 4
    var corner: CGFloat = 14

    func path(in rect: CGRect) -> Path {
        var p = Path()
        let outer = CutCornerShape(cut: corner).path(in: rect.insetBy(dx: inset, dy: inset))
        p.addPath(outer.strokedPath(StrokeStyle(lineWidth: 1.6)))
        let inner = CutCornerShape(cut: corner * 0.8).path(in: rect.insetBy(dx: inset + 5, dy: inset + 5))
        p.addPath(inner.strokedPath(StrokeStyle(lineWidth: 0.8)))
        let r: CGFloat = 3.2
        let w = rect.width, h = rect.height, o = inset + corner * 0.5
        for c in [CGPoint(x: o, y: o), CGPoint(x: w - o, y: o), CGPoint(x: o, y: h - o), CGPoint(x: w - o, y: h - o)] {
            // A ring: fleuron with a midnight centre.
            p.addPath(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
                .strokedPath(StrokeStyle(lineWidth: r * 0.9)))
        }
        for c in [CGPoint(x: w / 2, y: inset), CGPoint(x: w / 2, y: h - inset), CGPoint(x: inset, y: h / 2), CGPoint(x: w - inset, y: h / 2)] {
            var d = Path()
            d.move(to: CGPoint(x: c.x, y: c.y - r * 1.4))
            d.addLine(to: CGPoint(x: c.x + r * 1.4, y: c.y))
            d.addLine(to: CGPoint(x: c.x, y: c.y + r * 1.4))
            d.addLine(to: CGPoint(x: c.x - r * 1.4, y: c.y))
            d.closeSubpath()
            p.addPath(d)
        }
        return p
    }
}

/// The standard content panel: ink-violet glass with a gilded filigree frame, sized by its content.
struct OrnatePanel<Content: View>: View {
    var padding: CGFloat = 22
    var alignment: HorizontalAlignment = .leading
    var spacing: CGFloat = 6
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: alignment, spacing: spacing) { content }
            .frame(maxWidth: .infinity, alignment: Alignment(horizontal: alignment, vertical: .center))
            .padding(padding)
            .background(
                LinearGradient(colors: [Palette.inkViolet.opacity(0.92), Palette.midnight.opacity(0.94)], startPoint: .top, endPoint: .bottom)
                    .clipShape(CutCornerShape(cut: 14))
            )
            .overlay(
                ZStack {
                    FiligreeFrame().fill(Palette.gold)
                    GoldShimmerFill(duration: 9, mask: FiligreeFrame())
                }
                .allowsHitTesting(false)
            )
    }
}

/// Section heading: engraved gold title with an ornamental rule under it.
struct SectionTitle: View {
    let text: String
    var subtitle: String?

    var body: some View {
        VStack(spacing: 4) {
            GoldText(text, style: .headlineMedium, heading: true)
            OrnateDivider()
            if let subtitle {
                Text(subtitle).typeStyle(.bodyMedium, italic: true).foregroundStyle(Palette.moonstoneDim)
                    .multilineTextAlignment(.center).padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

/// Primary action: a gold-leaf ingot button with a slow shimmer.
struct GoldButton: View {
    let title: String
    var enabled = true
    var fullWidth = true
    let action: () -> Void

    init(_ title: String, enabled: Bool = true, fullWidth: Bool = true, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.fullWidth = fullWidth
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title.uppercased())
                .typeStyle(.labelLarge)
                .foregroundStyle(Palette.midnight)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .padding(.vertical, 14)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 52)
                .background(
                    ZStack {
                        Rectangle().fill(GoldBrush.leaf)
                        GoldShimmerFill(duration: 6, mask: Rectangle())
                    }
                )
                .clipShape(CutCornerShape(cut: 10))
                .overlay(CutCornerShape(cut: 10).stroke(Palette.goldPale.opacity(0.8), lineWidth: 1))
                .contentShape(CutCornerShape(cut: 10))
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}

/// Secondary action: gold outline on glass.
struct GhostButton: View {
    let title: String
    var color: Color = Palette.goldPale
    var enabled = true
    var fullWidth = true
    let action: () -> Void

    init(_ title: String, color: Color = Palette.goldPale, enabled: Bool = true, fullWidth: Bool = true, action: @escaping () -> Void) {
        self.title = title
        self.color = color
        self.enabled = enabled
        self.fullWidth = fullWidth
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title.uppercased())
                .typeStyle(.labelMedium)
                .foregroundStyle(color)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 48)
                .background(Palette.midnight.opacity(0.55).clipShape(CutCornerShape(cut: 10)))
                .overlay(CutCornerShape(cut: 10).stroke(GoldBrush.leaf, lineWidth: 1))
                .contentShape(CutCornerShape(cut: 10))
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}

struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1).opacity(configuration.isPressed ? 0.85 : 1)
    }
}

/// Round artwork (zodiac sign, animal, blood type, element) with a soft gold halo.
struct ArtMedallion: View {
    let art: String
    var size: CGFloat = 120
    var label: String?

    var body: some View {
        ZStack {
            Circle().fill(RadialGradient(colors: [Palette.gold.opacity(0.35), .clear], center: .center, startRadius: 0, endRadius: size / 2))
            Image(art).resizable().scaledToFill().frame(width: size * 0.92, height: size * 0.92).clipShape(Circle())
        }
        .frame(width: size, height: size)
        .accessibilityElement()
        .accessibilityLabel(label ?? "")
        .accessibilityHidden(label == nil)
    }
}

/// Waiting indicator: three planets orbiting a gold sun (still when motion is reduced).
struct OrreryLoader: View {
    var size: CGFloat = 72
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { timeline in
            Canvas { ctx, sz in
                let c = CGPoint(x: sz.width / 2, y: sz.height / 2)
                let r = min(sz.width, sz.height) / 2
                let angle = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 4) / 4 * 360
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - r * 0.16, y: c.y - r * 0.16, width: r * 0.32, height: r * 0.32)),
                         with: .linearGradient(Gradient(colors: [Palette.goldPale, Palette.gold, Palette.goldDeep]),
                                               startPoint: CGPoint(x: c.x, y: c.y - r * 0.16), endPoint: CGPoint(x: c.x, y: c.y + r * 0.16)))
                for (i, ring) in [(0.42, 1.0), (0.68, 0.62), (0.92, 0.38)].enumerated() {
                    let rr = r * ring.0
                    ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr)),
                               with: .color(Palette.gold.opacity(0.35)), lineWidth: 0.8)
                    let a = ((reduceMotion ? 90 * Double(i) : angle * ring.1) + Double(i) * 120) * .pi / 180
                    let p = CGPoint(x: c.x + rr * cos(a), y: c.y + rr * sin(a))
                    let pr = r * 0.07
                    ctx.fill(Path(ellipseIn: CGRect(x: p.x - pr, y: p.y - pr, width: 2 * pr, height: 2 * pr)),
                             with: .color(i == 1 ? Palette.roseCopper : Palette.moonstone))
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityLabel("Working")
    }
}

/// Small engraved label + value pair.
struct LabelValue: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased()).typeStyle(.labelSmall).foregroundStyle(Palette.gold)
            Text(value).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone)
        }
    }
}

/// Small italic note in dim moonstone.
struct FinePrint: View {
    let text: String
    var alignment: TextAlignment = .leading
    init(_ text: String, alignment: TextAlignment = .leading) {
        self.text = text
        self.alignment = alignment
    }

    var body: some View {
        Text(text).typeStyle(.bodySmall, italic: true).foregroundStyle(Palette.moonstoneDim).multilineTextAlignment(alignment)
            .frame(maxWidth: .infinity, alignment: alignment == .center ? .center : .leading)
    }
}

/// Engraved panel heading ("PLACEMENTS").
struct PanelHeading: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).typeStyle(.titleMedium).foregroundStyle(Palette.gold).accessibilityAddTraits(.isHeader)
    }
}

/// "✦  text" bullet line.
struct StarBullet: View {
    let text: String
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("✦").typeStyle(.bodyMedium).foregroundStyle(Palette.gold)
            Text(text).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
