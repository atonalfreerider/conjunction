import Foundation

/// Small angle helpers shared by every engine (port of astro/Angles.kt). All public angles in this
/// library are degrees.
public enum Angles {
    public static let deg = Double.pi / 180.0
    public static let rad = 180.0 / Double.pi
    public static let arcsec = 1.0 / 3600.0

    /// Normalises to [0, 360). Kotlin's Double `%` is fmod, Swift's truncatingRemainder.
    public static func norm360(_ degrees: Double) -> Double {
        let r = degrees.truncatingRemainder(dividingBy: 360.0)
        return r < 0.0 ? r + 360.0 : (r >= 360.0 ? r - 360.0 : r)
    }

    /// Normalises to [-180, 180).
    public static func norm180(_ degrees: Double) -> Double { norm360(degrees + 180.0) - 180.0 }

    /// Smallest separation between two longitudes, in [0, 180].
    public static func separation(_ a: Double, _ b: Double) -> Double { abs(norm180(a - b)) }

    public static func normRadians(_ radians: Double) -> Double {
        let twoPi = 2.0 * Double.pi
        let r = radians.truncatingRemainder(dividingBy: twoPi)
        return r < 0.0 ? r + twoPi : r
    }
}

extension Math {
    /// Math.floorMod for Int.
    static func floorMod(_ x: Int, _ y: Int) -> Int { x - floorDiv(x, y) * y }
}

extension Comparable {
    /// Kotlin's coerceIn.
    func coerceIn(_ lower: Self, _ upper: Self) -> Self { Swift.min(Swift.max(self, lower), upper) }
}
