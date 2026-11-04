import Foundation

/// Grayscale image, row-major, values 0..1 (0 = black).
public struct GrayImage: Sendable {
    public let width: Int
    public let height: Int
    public var pixels: [Float]

    public init(width: Int, height: Int, pixels: [Float]? = nil) {
        precondition(width > 0 && height > 0)
        self.width = width
        self.height = height
        self.pixels = pixels ?? [Float](repeating: 0, count: width * height)
        precondition(self.pixels.count == width * height)
    }

    @inline(__always) public subscript(x: Int, y: Int) -> Float {
        get { pixels[y * width + x] }
        set { pixels[y * width + x] = newValue }
    }

    /// Bilinear sample; outside the image returns the nearest edge value.
    public func sample(_ x: Float, _ y: Float) -> Float {
        let cx = min(max(x, 0), Float(width - 1))
        let cy = min(max(y, 0), Float(height - 1))
        let x0 = Int(cx), y0 = Int(cy)
        let x1 = min(x0 + 1, width - 1), y1 = min(y0 + 1, height - 1)
        let fx = cx - Float(x0), fy = cy - Float(y0)
        let top = self[x0, y0] * (1 - fx) + self[x1, y0] * fx
        let bottom = self[x0, y1] * (1 - fx) + self[x1, y1] * fx
        return top * (1 - fy) + bottom * fy
    }
}

/// One detected crease: its polyline in image pixels, contrast and number of gaps along it.
public struct DetectedLine: Sendable {
    public let polyline: [Point2]
    public let depth: Double
    public let breaks: Int
}

/// What the image analysis found, ready for `PalmAnalyzer.analyze`.
public struct PalmDetection: Sendable {
    public let lines: [PalmLine: DetectedLine]
    /// Mount prominence 0..1 (0.5 = average brightness).
    public let mounts: [Mount: Double]
    /// 0..1: how much usable crease contrast the photo had and how many lines were found.
    public let quality: Double
    /// The rectified palm after contrast enhancement (creases dark): the "scanner view".
    public let enhanced: GrayImage?
}

/// Palm crease analysis in plain Swift: a line-for-line port of the Android app's
/// `palm/PalmGeometry.kt` (SPEC 4), so both platforms measure the same lines from the same photo.
///
/// 1. Rectify the palm quadrilateral (pinky MCP, index MCP, wrist widened) into a 240 x 300 grid.
/// 2. Heavy contrast: CLAHE (8 x 8 tiles, clip 4), then a wide high-pass divided by local deviation.
/// 3. Multi-scale Hessian ridge filter (sigma 1.4 / 2.4 / 3.8): strength and direction per cell.
/// 4. Per line, the longest run of direction-matching strong cells in its band (gaps <= 3 steps);
///    the fate line uses a lower threshold.
/// 5. Mounts: mean brightness of each pad relative to the palm (a rough, documented heuristic).
///
/// The 21 landmarks follow the MediaPipe order, which Vision's hand-pose joints map onto.
public enum PalmGeometry {
    public static let W = 240
    public static let H = 300
    private static let S = W / 120

    public struct Band: Sendable {
        public let line: PalmLine
        public let u0: Float, u1: Float, v0: Float, v1: Float
        public let horizontal: Bool
        public let maxAngleDeg: Float
    }

    /// Search bands on the rectified palm (u: pinky 0 → thumb 1, v: knuckles 0 → wrist 1).
    public static let bands: [Band] = [
        Band(line: .heart, u0: 0.0, u1: 0.85, v0: 0.06, v1: 0.27, horizontal: true, maxAngleDeg: 35),
        Band(line: .head, u0: 0.15, u1: 1.0, v0: 0.28, v1: 0.55, horizontal: true, maxAngleDeg: 35),
        Band(line: .life, u0: 0.55, u1: 1.0, v0: 0.25, v1: 1.0, horizontal: false, maxAngleDeg: 60),
        Band(line: .fate, u0: 0.32, u1: 0.68, v0: 0.35, v1: 1.0, horizontal: false, maxAngleDeg: 30),
    ]

    private struct P {
        var x: Float, y: Float
        static func + (a: P, b: P) -> P { P(x: a.x + b.x, y: a.y + b.y) }
        static func - (a: P, b: P) -> P { P(x: a.x - b.x, y: a.y - b.y) }
        static func * (a: P, k: Float) -> P { P(x: a.x * k, y: a.y * k) }
    }

    private static func p(_ q: Point2) -> P { P(x: Float(q.x), y: Float(q.y)) }

    /// Corners of the palm quad in image space: [topLeft, topRight, bottomRight, bottomLeft].
    public static func palmQuad(_ landmarks: [Point2]) -> [Point2] {
        let p17 = p(landmarks[17]), p5 = p(landmarks[5]), w = p(landmarks[0])
        let mid = (p5 + p17) * 0.5
        let halfAcross = (p5 - mid) * 0.95
        let tl = p17 + (p17 - p5) * 0.08
        let tr = p5
        let bl = w - halfAcross
        let br = w + halfAcross
        return [tl, tr, br, bl].map { Point2(Double($0.x), Double($0.y)) }
    }

    public static func rectify(_ image: GrayImage, landmarks: [Point2]) -> GrayImage {
        let q = palmQuad(landmarks).map(p)
        let (tl, tr, br, bl) = (q[0], q[1], q[2], q[3])
        var out = GrayImage(width: W, height: H)
        for j in 0..<H {
            let v = (Float(j) + 0.5) / Float(H)
            let left = tl + (bl - tl) * v
            let right = tr + (br - tr) * v
            for i in 0..<W {
                let u = (Float(i) + 0.5) / Float(W)
                let pt = left + (right - left) * u
                out[i, j] = image.sample(pt.x, pt.y)
            }
        }
        return out
    }

    public static func gaussianBlur(_ img: GrayImage, sigma: Float) -> GrayImage {
        let radius = max(1, Int((sigma * 3).rounded()))
        var kernel = (0...(radius * 2)).map { k -> Float in
            let d = Float(k - radius)
            return exp(-(d * d) / (2 * sigma * sigma))
        }
        let sum = kernel.reduce(0, +)
        for k in kernel.indices { kernel[k] /= sum }
        let w = img.width, h = img.height
        var tmp = [Float](repeating: 0, count: w * h)
        img.pixels.withUnsafeBufferPointer { src in
            for y in 0..<h {
                let row = y * w
                for x in 0..<w {
                    var acc: Float = 0
                    for k in -radius...radius { acc += kernel[k + radius] * src[row + min(max(x + k, 0), w - 1)] }
                    tmp[row + x] = acc
                }
            }
        }
        var out = [Float](repeating: 0, count: w * h)
        tmp.withUnsafeBufferPointer { src in
            for y in 0..<h {
                for x in 0..<w {
                    var acc: Float = 0
                    for k in -radius...radius { acc += kernel[k + radius] * src[min(max(y + k, 0), h - 1) * w + x] }
                    out[y * w + x] = acc
                }
            }
        }
        return GrayImage(width: w, height: h, pixels: out)
    }

    /// Contrast-limited adaptive histogram equalisation on a 0..1 image.
    public static func clahe(_ img: GrayImage, tilesX: Int = 8, tilesY: Int = 8, clip: Float = 4) -> GrayImage {
        let bins = 256
        let tw = Float(img.width) / Float(tilesX)
        let th = Float(img.height) / Float(tilesY)
        func bin(_ v: Float) -> Int { Int((min(max(v, 0), 1) * Float(bins - 1)).rounded()) }
        var maps = [[[Float]]](repeating: [], count: tilesY)
        for ty in 0..<tilesY {
            for tx in 0..<tilesX {
                var hist = [Float](repeating: 0, count: bins)
                let xa = Int(Float(tx) * tw), xb = min(Int(Float(tx + 1) * tw), img.width)
                let ya = Int(Float(ty) * th), yb = min(Int(Float(ty + 1) * th), img.height)
                var n = 0
                for y in ya..<yb { for x in xa..<xb { hist[bin(img[x, y])] += 1; n += 1 } }
                let limit = max(1, clip * Float(n) / Float(bins))
                var excess: Float = 0
                for b in 0..<bins where hist[b] > limit { excess += hist[b] - limit; hist[b] = limit }
                let add = excess / Float(bins)
                var cdf = [Float](repeating: 0, count: bins)
                var acc: Float = 0
                for b in 0..<bins { acc += hist[b] + add; cdf[b] = acc / Float(max(1, n)) }
                maps[ty].append(cdf)
            }
        }
        var out = GrayImage(width: img.width, height: img.height)
        for y in 0..<img.height {
            for x in 0..<img.width {
                let b = bin(img[x, y])
                let fx = min(max((Float(x) + 0.5) / tw - 0.5, 0), Float(tilesX) - 1)
                let fy = min(max((Float(y) + 0.5) / th - 0.5, 0), Float(tilesY) - 1)
                let x0 = Int(fx), y0 = Int(fy)
                let x1 = min(x0 + 1, tilesX - 1), y1 = min(y0 + 1, tilesY - 1)
                let ax = fx - Float(x0), ay = fy - Float(y0)
                let top = maps[y0][x0][b] * (1 - ax) + maps[y0][x1][b] * ax
                let bottom = maps[y1][x0][b] * (1 - ax) + maps[y1][x1][b] * ax
                out[x, y] = top * (1 - ay) + bottom * ay
            }
        }
        return out
    }

    /// High-pass and contrast-normalise (step 2, after `clahe`).
    public static func flatten(_ img: GrayImage) -> GrayImage {
        let low = gaussianBlur(img, sigma: 10 * Float(S))
        let diff = GrayImage(width: img.width, height: img.height, pixels: zip(img.pixels, low.pixels).map { $0 - $1 })
        let variance = gaussianBlur(GrayImage(width: img.width, height: img.height, pixels: diff.pixels.map { $0 * $0 }),
                                    sigma: 10 * Float(S))
        return GrayImage(width: img.width, height: img.height,
                         pixels: zip(diff.pixels, variance.pixels).map { $0 / sqrt($1 + 1e-4) })
    }

    /// Step 3 over several crease widths: per pixel the strongest scale-normalised response.
    public static func multiScaleRidges(_ img: GrayImage, sigmas: [Float] = [1.4, 2.4, 3.8]) -> (strength: [Float], angle: [Float]) {
        var strength = [Float](repeating: 0, count: img.pixels.count)
        var angle = [Float](repeating: 0, count: img.pixels.count)
        for sigma in sigmas {
            let (st, an) = ridges(img, sigma: sigma)
            let norm = sigma * sigma
            for i in st.indices {
                let v = st[i] * norm
                if v > strength[i] { strength[i] = v; angle[i] = an[i] }
            }
        }
        return (strength, angle)
    }

    /// Ridge strength and line direction (degrees from horizontal, -90..90) per pixel at one scale.
    public static func ridges(_ img: GrayImage, sigma: Float = 1.6) -> (strength: [Float], angle: [Float]) {
        let s = gaussianBlur(img, sigma: sigma)
        var strength = [Float](repeating: 0, count: s.pixels.count)
        var angle = [Float](repeating: 0, count: s.pixels.count)
        guard s.width > 2, s.height > 2 else { return (strength, angle) }
        for y in 1..<(s.height - 1) {
            for x in 1..<(s.width - 1) {
                let ixx = s[x + 1, y] - 2 * s[x, y] + s[x - 1, y]
                let iyy = s[x, y + 1] - 2 * s[x, y] + s[x, y - 1]
                let ixy = (s[x + 1, y + 1] - s[x + 1, y - 1] - s[x - 1, y + 1] + s[x - 1, y - 1]) / 4
                let tr = ixx + iyy
                let det = ixx * iyy - ixy * ixy
                let disc = sqrt(max(0, tr * tr / 4 - det))
                let l1 = tr / 2 + disc
                strength[y * s.width + x] = max(0, l1)
                let across: Float = abs(ixy) > 1e-6 ? atan2(l1 - ixx, ixy) : (ixx >= iyy ? 0 : Float.pi / 2)
                var along = across * 180 / Float.pi + 90
                while along > 90 { along -= 180 }
                while along < -90 { along += 180 }
                angle[y * s.width + x] = along
            }
        }
        return (strength, angle)
    }

    public static func percentile(_ values: [Float], _ p: Float) -> Float {
        let sorted = values.sorted()
        let i = min(max(Int((Float(sorted.count - 1) * p).rounded()), 0), sorted.count - 1)
        return sorted[i]
    }

    /// A run found in one band, in rectified grid coordinates (x = column, y = row).
    public struct Run: Sendable {
        public let points: [(Int, Int)]
        public let depth: Double
        public let breaks: Int
    }

    /// Step 4 for one band; nil when no run of at least a quarter of the band was found.
    public static func measureLine(strength: [Float], angle: [Float], band: Band, threshold: Float, strongest: Float) -> Run? {
        let x0 = Int(band.u0 * Float(W)), x1 = min(Int(band.u1 * Float(W)), W - 1)
        let y0 = Int(band.v0 * Float(H)), y1 = min(Int(band.v1 * Float(H)), H - 1)
        let mainRange = band.horizontal ? Array(x0...x1) : Array(y0...y1)
        let crossRange = band.horizontal ? Array(y0...y1) : Array(x0...x1)
        let best: [(Int, Float)] = mainRange.map { m in
            var bestC = -1
            var bestS: Float = 0
            for c in crossRange {
                let x = band.horizontal ? m : c
                let y = band.horizontal ? c : m
                let i = y * W + x
                let s = strength[i]
                if s < threshold { continue }
                let a = angle[i]
                let dev = band.horizontal ? abs(a) : 90 - abs(a)
                if dev > band.maxAngleDeg { continue }
                if s > bestS { bestS = s; bestC = c }
            }
            return (bestC, bestS)
        }
        var runStart = -1, bestStart = 0, bestEnd = -1, gap = 0, lastC = -1
        for (k, pair) in best.enumerated() {
            let c = pair.0
            if c >= 0 && (lastC < 0 || abs(c - lastC) <= 6 * S) {
                if runStart < 0 { runStart = k }
                lastC = c
                gap = 0
                if k - runStart > bestEnd - bestStart { bestStart = runStart; bestEnd = k }
            } else {
                gap += 1
                if gap > 3 * S { runStart = -1; lastC = -1; gap = 0 }
            }
        }
        let runLength = bestEnd >= bestStart ? bestEnd - bestStart + 1 : 0
        if runLength < mainRange.count / 4 || runLength < 4 * S { return nil }
        let hits = (bestStart...bestEnd).filter { best[$0].0 >= 0 }
        var breaks = 0
        if hits.count > 1 { for i in 1..<hits.count where hits[i] - hits[i - 1] > S { breaks += 1 } }
        let pts: [(Int, Int)] = hits.map { k in
            let m = mainRange[0] + k
            let c = best[k].0
            return band.horizontal ? (m, c) : (c, m)
        }
        let mean = hits.map { Double(best[$0].1) }.reduce(0, +) / Double(max(hits.count, 1))
        let depth = min(max(mean / Double(max(strongest, 1e-6)), 0), 1)
        return Run(points: pts, depth: depth, breaks: breaks)
    }

    /// Maps a rectified grid cell centre back to image pixels (inverse of `rectify`).
    public static func toImage(_ quad: [Point2], _ gx: Int, _ gy: Int) -> Point2 {
        let q = quad.map(p)
        let (tl, tr, br, bl) = (q[0], q[1], q[2], q[3])
        let u = (Float(gx) + 0.5) / Float(W)
        let v = (Float(gy) + 0.5) / Float(H)
        let left = tl + (bl - tl) * v
        let right = tr + (br - tr) * v
        let pt = left + (right - left) * u
        return Point2(Double(pt.x), Double(pt.y))
    }

    private static func regionMean(_ img: GrayImage, _ u0: Float, _ u1: Float, _ v0: Float, _ v1: Float) -> Float {
        var sum: Float = 0
        var n = 0
        let ya = Int(v0 * Float(H)), yb = min(Int(v1 * Float(H)), H)
        let xa = Int(u0 * Float(W)), xb = min(Int(u1 * Float(W)), W)
        if ya < yb && xa < xb {
            for y in ya..<yb { for x in xa..<xb { sum += img[x, y]; n += 1 } }
        }
        return n == 0 ? 0 : sum / Float(n)
    }

    /// Step 5: mount prominence 0..1 (0.5 = as bright as the palm on average).
    public static func measureMounts(_ rectified: GrayImage) -> [Mount: Double] {
        let count = Float(rectified.pixels.count)
        let mean = rectified.pixels.reduce(0, +) / count
        let spread = max(1e-3, sqrt(rectified.pixels.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / count))
        func rel(_ u0: Float, _ u1: Float, _ v0: Float, _ v1: Float) -> Double {
            min(max(0.5 + Double((regionMean(rectified, u0, u1, v0, v1) - mean) / (4 * spread)), 0), 1)
        }
        return [
            .venus: rel(0.7, 1, 0.55, 0.95),
            .luna: rel(0, 0.3, 0.55, 0.95),
            .mars: rel(0, 0.25, 0.3, 0.55),
            .jupiter: rel(0.75, 1, 0, 0.18),
            .saturn: rel(0.5, 0.75, 0, 0.18),
            .apollo: rel(0.25, 0.5, 0, 0.18),
            .mercury: rel(0, 0.25, 0, 0.18),
        ]
    }

    /// Runs steps 1-5. [image] is the full photo in grayscale; [landmarks] its 21 hand landmarks in
    /// pixels; [handConfidence] the hand detector's confidence.
    public static func detect(_ image: GrayImage, landmarks: [Point2], handConfidence: Float = 1) -> PalmDetection {
        let quad = palmQuad(landmarks)
        let rectified = rectify(image, landmarks: landmarks)
        let enhanced = clahe(rectified)
        let flat = flatten(enhanced)
        let (strength, angle) = multiScaleRidges(flat)
        let threshold = max(percentile(strength, 0.80), 1e-4)
        let fateThreshold = max(percentile(strength, 0.70), 1e-4)
        let strongest = max(percentile(strength, 0.99), threshold)
        var lines: [PalmLine: DetectedLine] = [:]
        for band in bands {
            let bar = band.line == .fate ? fateThreshold : threshold
            if let run = measureLine(strength: strength, angle: angle, band: band, threshold: bar, strongest: strongest) {
                lines[band.line] = DetectedLine(polyline: run.points.map { toImage(quad, $0.0, $0.1) }, depth: run.depth,
                                                breaks: run.breaks)
            }
        }
        let contrast = min(max(Double((strongest - percentile(strength, 0.5)) / max(strongest, 1e-6)), 0), 1)
        let quality = min(max(0.4 * Double(handConfidence) + 0.3 * contrast + 0.3 * Double(lines.count) / 4.0, 0), 1)
        return PalmDetection(lines: lines, mounts: measureMounts(rectified), quality: quality, enhanced: enhanced)
    }

    /// Detection plus core's analyzer: the features the reading is written from.
    public static func features(_ detection: PalmDetection, hand: Hand, landmarks: [Point2]) throws -> PalmFeatures {
        try PalmAnalyzer.analyze(hand: hand, landmarks: landmarks,
                                 lines: detection.lines.mapValues(\.polyline),
                                 depth: detection.lines.mapValues(\.depth),
                                 breaks: detection.lines.mapValues(\.breaks),
                                 mounts: detection.mounts, quality: detection.quality)
    }
}
