import SwiftUI
import UIKit

/// "Celestial Art Nouveau" (SPEC, Look and feel): the same tokens as the Android app's `Palette`,
/// typography and gold brushes, so both platforms look like one app.
enum Palette {
    static let midnight = Color(hex: 0x0B0A2A)
    static let inkViolet = Color(hex: 0x1C1442)
    static let dusk = Color(hex: 0x2A1F5C)
    static let twilight = Color(hex: 0x3B2E78)
    static let gold = Color(hex: 0xD4AF37)
    static let goldDeep = Color(hex: 0xA8842A)
    static let goldPale = Color(hex: 0xF5E6A8)
    static let roseCopper = Color(hex: 0xC97B63)
    static let moonstone = Color(hex: 0xEDE6F7)
    static let moonstoneDim = Color(hex: 0xB9B0CC)
    static let garnet = Color(hex: 0xB23A48)
    static let verdigris = Color(hex: 0x6FB3A2)
    static let scrim = Color(hex: 0x07061C).opacity(0.8)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

/// Gold-leaf gradients (Android `GoldBrushes`).
enum GoldBrush {
    static let leafStops: [Color] = [Palette.goldDeep, Palette.gold, Palette.goldPale, Palette.gold, Palette.goldDeep]
    static let leaf = LinearGradient(colors: leafStops, startPoint: .topLeading, endPoint: .bottomTrailing)
    static let leafHorizontal = LinearGradient(colors: leafStops, startPoint: .leading, endPoint: .trailing)
    static let vertical = LinearGradient(colors: [Palette.goldPale, Palette.gold, Palette.goldDeep], startPoint: .top, endPoint: .bottom)
    /// The band of light that sweeps across gold (drawn wide and slid, see `GoldShimmer`).
    static let shimmerStops: [Color] = [Palette.goldDeep, Palette.gold, Palette.goldPale, Color.white.opacity(0.95),
                                        Palette.goldPale, Palette.gold, Palette.goldDeep]
}

/// The app's type scale (Android `ConjunctionTypography`): Cinzel Decorative for display, Cinzel for
/// labels, Cormorant Garamond for body text. Sizes are points and scale with Dynamic Type.
enum TypeStyle {
    case displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall
    case titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall

    var size: CGFloat {
        switch self {
        case .displayLarge: return 44
        case .displayMedium: return 34
        case .displaySmall, .headlineLarge: return 28
        case .headlineMedium: return 24
        case .headlineSmall, .titleLarge, .bodyLarge: return 20
        case .bodyMedium: return 18
        case .titleMedium, .bodySmall: return 16
        case .labelLarge: return 15
        case .titleSmall: return 14
        case .labelMedium: return 13
        case .labelSmall: return 11
        }
    }

    /// Letter spacing in em (Compose `letterSpacing`).
    var trackingEm: CGFloat {
        switch self {
        case .displayLarge: return 0.02
        case .headlineSmall: return 0.06
        case .titleLarge, .titleMedium: return 0.08
        case .titleSmall, .labelMedium: return 0.1
        case .labelLarge, .labelSmall: return 0.12
        default: return 0
        }
    }

    var relativeTo: Font.TextStyle {
        switch self {
        case .displayLarge, .displayMedium, .displaySmall: return .largeTitle
        case .headlineLarge, .headlineMedium: return .title
        case .headlineSmall, .titleLarge: return .title2
        case .titleMedium, .titleSmall: return .headline
        case .bodyLarge, .bodyMedium: return .body
        case .bodySmall: return .callout
        case .labelLarge, .labelMedium: return .subheadline
        case .labelSmall: return .caption
        }
    }

    /// PostScript / family names to try, best first.
    fileprivate var faces: [String] {
        switch self {
        case .displayLarge, .displayMedium, .headlineLarge: return ["CinzelDecorative-Bold"]
        case .displaySmall, .headlineMedium: return ["CinzelDecorative-Regular"]
        case .labelLarge: return ["CinzelRoman-Bold", "Cinzel-Bold", "Cinzel", "Cinzel-Regular"]
        case .headlineSmall, .titleLarge, .titleMedium, .titleSmall, .labelMedium, .labelSmall:
            return ["Cinzel-SemiBold", "CinzelRoman-SemiBold", "Cinzel", "Cinzel-Regular"]
        case .bodyLarge, .bodyMedium, .bodySmall: return ["CormorantGaramond-Medium", "Cormorant Garamond", "CormorantGaramond-Regular"]
        }
    }

    fileprivate var weight: Font.Weight? {
        switch self {
        case .headlineSmall, .titleLarge, .titleMedium, .titleSmall, .labelMedium, .labelSmall: return .semibold
        case .labelLarge: return .bold
        case .bodyLarge, .bodyMedium, .bodySmall: return .medium
        default: return nil
        }
    }

    var font: Font { FontCache.font(self, italic: false) }
    var italicFont: Font { FontCache.font(self, italic: true) }
}

/// Resolves the bundled faces once (they are registered from Info.plist `UIAppFonts`).
private enum FontCache {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cache: [String: Font] = [:]

    static func font(_ style: TypeStyle, italic: Bool) -> Font {
        let key = "\(style)|\(italic)"
        lock.lock()
        defer { lock.unlock() }
        if let hit = cache[key] { return hit }
        let made = resolve(style, italic: italic)
        cache[key] = made
        return made
    }

    private static func resolve(_ style: TypeStyle, italic: Bool) -> Font {
        let faces: [String]
        if italic {
            faces = style.size >= 18 ? ["CormorantGaramond-MediumItalic", "CormorantGaramond-Italic"] : ["CormorantGaramond-Italic"]
        } else {
            faces = style.faces
        }
        for name in faces where UIFont(name: name, size: style.size) != nil {
            var f = Font.custom(name, size: style.size, relativeTo: style.relativeTo)
            // A family name picks the variable font's default instance; ask for the weight explicitly.
            if !name.contains("-"), let w = style.weight { f = f.weight(w) }
            return italic && !name.contains("Italic") ? f.italic() : f
        }
        let fallback = Font.system(size: style.size, weight: style.weight ?? .regular, design: .serif)
        return italic ? fallback.italic() : fallback
    }
}

extension View {
    /// Applies a type style: font and letter spacing.
    func typeStyle(_ style: TypeStyle, italic: Bool = false) -> some View {
        font(italic ? style.italicFont : style.font).tracking(style.size * style.trackingEm)
    }
}
