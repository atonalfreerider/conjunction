import Foundation

// Palm measurements, ported from android/core .../palm/PalmFeatures.kt. The platform detector
// (iOS Vision VNDetectHumanHandPoseRequest + contour detection) supplies landmarks and crease
// polylines; this code turns them into a typed, image-free PalmFeatures.

/// Palmistry's four hand shapes (palm proportion x finger length).
public enum HandShape: Int, CaseIterable, Sendable {
    /// Square palm, short fingers.
    case earth
    /// Square palm, long fingers.
    case air
    /// Long palm, short fingers.
    case fire
    /// Long palm, long fingers.
    case water

    public var displayName: String { ["Earth", "Air", "Fire", "Water"][rawValue] }
    public var key: String { displayName.lowercased() }
    public var name: String { key.uppercased() }
}

public enum PalmLine: Int, CaseIterable, Sendable {
    case heart, head, life, fate
    public var displayName: String { ["heart", "head", "life", "fate"][rawValue] }
    public var key: String { displayName }
    public var name: String { key.uppercased() }
}

public enum Mount: Int, CaseIterable, Sendable {
    case venus, jupiter, saturn, apollo, mercury, luna, mars
    public var displayName: String { ["Venus", "Jupiter", "Saturn", "Apollo", "Mercury", "Luna", "Mars"][rawValue] }
    public var name: String { displayName.uppercased() }
}

public enum Hand: String, CaseIterable, Sendable {
    case left = "LEFT", right = "RIGHT"
    public var name: String { rawValue }
}

public enum LineLength: String, CaseIterable, Sendable {
    case absent = "ABSENT", short = "SHORT", medium = "MEDIUM", long = "LONG"
    public var name: String { rawValue }
}

/// Measurements of one palm line, all dimensionless so they do not depend on photo resolution.
/// [length]: polyline length / palm width (heart, head) or / palm length (life, fate).
/// [curvature]: 0 = straight .. 1 = strongly curved (see PalmAnalyzer.curvature).
/// [depth]: 0 = faint .. 1 = deep (local edge contrast reported by the platform detector).
public struct LineFeature: Hashable, Sendable {
    public var present: Bool
    public var length: Double
    public var curvature: Double
    public var depth: Double
    public var breaks: Int
    public var forked: Bool

    public init(present: Bool, length: Double = 0.0, curvature: Double = 0.0, depth: Double = 0.5,
                breaks: Int = 0, forked: Bool = false) {
        self.present = present
        self.length = length
        self.curvature = curvature
        self.depth = depth
        self.breaks = breaks
        self.forked = forked
    }

    public var lengthClass: LineLength {
        if !present { return .absent }
        if length < LineFeature.shortBelow { return .short }
        if length < LineFeature.longFrom { return .medium }
        return .long
    }

    public var isCurved: Bool { curvature >= LineFeature.curvedFrom }

    public static let shortBelow = 0.55
    public static let longFrom = 0.80
    public static let curvedFrom = 0.35
    public static let deepFrom = 0.65
    public static let faintBelow = 0.35
    public static let absent = LineFeature(present: false)
}

public struct FingerRatios: Hashable, Sendable {
    /// Index finger length / ring finger length (the "2D:4D" ratio, ~0.95-1.0 typical).
    public var indexToRing: Double
    /// Middle finger length / palm length (~0.8-0.95 typical).
    public var fingerToPalm: Double

    public init(indexToRing: Double, fingerToPalm: Double) {
        self.indexToRing = indexToRing
        self.fingerToPalm = fingerToPalm
    }
}

/// Typed description of a palm, produced on device from hand landmarks + line detection, consumed
/// by PalmReadingPrompt and the fallback reading. Contains no image data.
public struct PalmFeatures: Hashable, Sendable {
    public var hand: Hand
    public var handShape: HandShape
    /// Palm width (index to little-finger knuckle) / palm length (wrist to middle knuckle).
    public var palmRatio: Double
    public var lines: [PalmLine: LineFeature]
    public var fingers: FingerRatios
    /// Mount prominence 0..1 when the platform could estimate it (shading), else empty.
    public var mounts: [Mount: Double]
    /// 0..1 confidence of the detection (lighting, focus, coverage).
    public var quality: Double

    public init(hand: Hand, handShape: HandShape, palmRatio: Double, lines: [PalmLine: LineFeature],
                fingers: FingerRatios, mounts: [Mount: Double] = [:], quality: Double = 1.0) {
        self.hand = hand
        self.handShape = handShape
        self.palmRatio = palmRatio
        self.lines = lines
        self.fingers = fingers
        self.mounts = mounts
        self.quality = quality
    }

    public func line(_ l: PalmLine) -> LineFeature { lines[l] ?? .absent }
}

/// 2-D point in image pixels (y down).
public struct Point2: Hashable, Sendable {
    public var x: Double
    public var y: Double
    public init(_ x: Double, _ y: Double) { self.x = x; self.y = y }
    public init(x: Double, y: Double) { self.x = x; self.y = y }
}

/// Invalid detector input (Kotlin's require failures).
public enum PalmAnalysisError: Error, Equatable, CustomStringConvertible, Sendable {
    case landmarkCount(Int)
    case degenerateLandmarks
    public var description: String {
        switch self {
        case .landmarkCount(let n): return "Expected 21 hand landmarks, got \(n)"
        case .degenerateLandmarks: return "Degenerate hand landmarks"
        }
    }
}

/// Turns platform detections into PalmFeatures. The 21 landmarks use the MediaPipe Hand Landmarker
/// order, which Apple Vision's VNHumanHandPoseObservation joints map onto one-to-one:
/// 0 wrist; 1-4 thumb CMC, MCP, IP, tip; 5-8 index MCP, PIP, DIP, tip; 9-12 middle; 13-16 ring;
/// 17-20 little finger.
public enum PalmAnalyzer {
    public static let squarePalmFrom = 0.80
    public static let longFingersFrom = 0.88

    public static func classifyHandShape(palmRatio: Double, fingerToPalm: Double) -> HandShape {
        let square = palmRatio >= squarePalmFrom
        let longFingers = fingerToPalm >= longFingersFrom
        switch (square, longFingers) {
        case (true, false): return .earth
        case (true, true): return .air
        case (false, false): return .fire
        case (false, true): return .water
        }
    }

    /// - Parameters:
    ///   - landmarks: 21 points (see type doc).
    ///   - lines: detected crease polylines per line (image pixels, ordered along the line); a missing
    ///     key means not found.
    ///   - depth: optional 0..1 contrast per line from the edge detector.
    ///   - quality: overall 0..1 detection confidence.
    public static func analyze(hand: Hand, landmarks: [Point2], lines: [PalmLine: [Point2]],
                               depth: [PalmLine: Double] = [:], breaks: [PalmLine: Int] = [:],
                               mounts: [Mount: Double] = [:], quality: Double = 1.0) throws -> PalmFeatures {
        guard landmarks.count == 21 else { throw PalmAnalysisError.landmarkCount(landmarks.count) }
        let palmLength = dist(landmarks[0], landmarks[9])
        let palmWidth = dist(landmarks[5], landmarks[17])
        guard palmLength > 0.0 && palmWidth > 0.0 else { throw PalmAnalysisError.degenerateLandmarks }
        func finger(_ mcp: Int) -> Double {
            dist(landmarks[mcp], landmarks[mcp + 1]) + dist(landmarks[mcp + 1], landmarks[mcp + 2]) +
                dist(landmarks[mcp + 2], landmarks[mcp + 3])
        }
        let index = finger(5), middle = finger(9), ring = finger(13)
        let palmRatio = palmWidth / palmLength
        let fingers = FingerRatios(indexToRing: index / ring, fingerToPalm: middle / palmLength)

        var features: [PalmLine: LineFeature] = [:]
        for line in PalmLine.allCases {
            guard let pts = lines[line], pts.count >= 2 else { features[line] = .absent; continue }
            let scale = line == .heart || line == .head ? palmWidth : palmLength
            features[line] = LineFeature(present: true, length: polylineLength(pts) / scale, curvature: curvature(pts),
                                         depth: (depth[line] ?? 0.5).coerceIn(0.0, 1.0), breaks: breaks[line] ?? 0)
        }
        return PalmFeatures(hand: hand, handShape: classifyHandShape(palmRatio: palmRatio, fingerToPalm: fingers.fingerToPalm),
                            palmRatio: palmRatio, lines: features, fingers: fingers,
                            mounts: mounts.mapValues { $0.coerceIn(0.0, 1.0) }, quality: quality.coerceIn(0.0, 1.0))
    }

    public static func polylineLength(_ points: [Point2]) -> Double {
        var total = 0.0
        for i in 0..<max(points.count - 1, 0) { total += dist(points[i], points[i + 1]) }
        return total
    }

    /// (arc length / chord length - 1) * 5, clamped to 0..1: 0 straight, ~0.5 a gentle arc, 1 a
    /// half-circle or more.
    public static func curvature(_ points: [Point2]) -> Double {
        guard let first = points.first, let last = points.last else { return 1.0 }
        let chord = dist(first, last)
        if chord <= 0.0 { return 1.0 }
        return ((polylineLength(points) / chord - 1.0) * 5.0).coerceIn(0.0, 1.0)
    }

    private static func dist(_ a: Point2, _ b: Point2) -> Double { hypot(a.x - b.x, a.y - b.y) }
}
