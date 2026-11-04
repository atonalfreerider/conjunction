import UIKit
import Vision
import ConjunctionCore

struct PalmScanError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Result of a scan: the features for the reading, plus what the result screen draws.
struct PalmScan {
    let features: PalmFeatures
    let detection: PalmDetection
    let landmarks: [Point2]
    let photo: UIImage
}

/// On-device palm scan: Vision's `VNDetectHumanHandPoseRequest` finds the 21 hand joints (mapped
/// onto the MediaPipe order the Android app uses); ConjunctionCore's `PalmGeometry` straightens the
/// palm, runs the heavy contrast pass (CLAHE 8x8, clip 4, high-pass) and the multi-scale Hessian
/// ridge filter to trace the heart, head, life and fate creases; `PalmAnalyzer` turns that into
/// `PalmFeatures`. The photo stays in memory and is never uploaded.
enum PalmScanner {
    static let maxEdge: CGFloat = 1280
    static let minPalmPx = 120.0

    /// Vision joints in MediaPipe landmark order (0 wrist; 1-4 thumb; 5-8 index; ...; 17-20 little).
    private static let joints: [VNHumanHandPoseObservation.JointName] = [
        .wrist,
        .thumbCMC, .thumbMP, .thumbIP, .thumbTip,
        .indexMCP, .indexPIP, .indexDIP, .indexTip,
        .middleMCP, .middlePIP, .middleDIP, .middleTip,
        .ringMCP, .ringPIP, .ringDIP, .ringTip,
        .littleMCP, .littlePIP, .littleDIP, .littleTip,
    ]

    static func scan(_ photo: UIImage, hand: Hand) async throws -> PalmScan {
        try await Task.detached(priority: .userInitiated) { try scanNow(photo, hand: hand) }.value
    }

    private static func scanNow(_ photo: UIImage, hand: Hand) throws -> PalmScan {
        let upright = PhotoCodec.normalized(photo, maxEdge: maxEdge)
        guard let cg = upright.cgImage else { throw PalmScanError(message: "That photo could not be opened.") }
        let w = Double(cg.width), h = Double(cg.height)
        let request = VNDetectHumanHandPoseRequest()
        request.maximumHandCount = 1
        do {
            try VNImageRequestHandler(cgImage: cg, orientation: .up, options: [:]).perform([request])
        } catch {
            throw PalmScanError(message: "The hand model could not run on this phone (\(error.localizedDescription)).")
        }
        guard let observation = request.results?.first,
              let points = try? observation.recognizedPoints(.all) else {
            throw PalmScanError(message: "No hand found. Hold your open palm flat, fingers apart, in good light.")
        }
        var landmarks: [Point2] = []
        for joint in joints {
            guard let p = points[joint], p.confidence > 0.1 else {
                throw PalmScanError(message: "Part of your hand is hidden. Show the whole open palm with fingers apart.")
            }
            // Vision: normalised, origin bottom-left. Image pixels: origin top-left.
            landmarks.append(Point2(Double(p.location.x) * w, (1 - Double(p.location.y)) * h))
        }
        let palmPx = hypot(landmarks[0].x - landmarks[9].x, landmarks[0].y - landmarks[9].y)
        if palmPx < minPalmPx { throw PalmScanError(message: "Your palm is too small in the photo. Move the phone closer.") }
        let gray = try grayscale(cg)
        let detection = PalmGeometry.detect(gray, landmarks: landmarks, handConfidence: observation.confidence)
        let features: PalmFeatures
        do {
            features = try PalmGeometry.features(detection, hand: hand, landmarks: landmarks)
        } catch {
            throw PalmScanError(message: "The hand outline could not be measured. Try again with the palm flat.")
        }
        return PalmScan(features: features, detection: detection, landmarks: landmarks, photo: upright)
    }

    /// Luma weighted toward green/blue (skin is red; creases show best in green and blue), 0..1.
    static func grayscale(_ cg: CGImage) throws -> GrayImage {
        let w = cg.width, h = cg.height
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        let ok: Bool = rgba.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { throw PalmScanError(message: "That photo could not be read.") }
        var px = [Float](repeating: 0, count: w * h)
        for i in 0..<(w * h) {
            let r = Float(rgba[i * 4]), g = Float(rgba[i * 4 + 1]), b = Float(rgba[i * 4 + 2])
            px[i] = (0.15 * r + 0.6 * g + 0.25 * b) / 255
        }
        return GrayImage(width: w, height: h, pixels: px)
    }

    /// The "scanner view": the straightened, contrast-enhanced palm with creases glowing gold on midnight.
    static func scannerImage(_ enhanced: GrayImage) -> UIImage? {
        let w = enhanced.width, h = enhanced.height
        let dark: (Float, Float, Float) = (0x0B / 255, 0x0A / 255, 0x2A / 255)
        let gold: (Float, Float, Float) = (0xF5 / 255, 0xE6 / 255, 0xA8 / 255)
        var bytes = [UInt8](repeating: 255, count: w * h * 4)
        for i in 0..<(w * h) {
            var t = min(max((0.55 - enhanced.pixels[i]) / 0.45, 0), 1)
            t = t * t * (3 - 2 * t)
            bytes[i * 4] = UInt8((dark.0 + (gold.0 - dark.0) * t) * 255)
            bytes[i * 4 + 1] = UInt8((dark.1 + (gold.1 - dark.1) * t) * 255)
            bytes[i * 4 + 2] = UInt8((dark.2 + (gold.2 - dark.2) * t) * 255)
        }
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let cg = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                               space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// "Save photo": app-private storage only, erased by Delete all my data.
    static func save(_ photo: UIImage) -> Bool {
        guard let data = photo.jpegData(compressionQuality: 0.9) else { return false }
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        f.locale = Locale(identifier: "en_US_POSIX")
        AppFiles.write(data, to: AppFiles.palmsDir.appendingPathComponent("palm_\(f.string(from: Date())).jpg"))
        return true
    }
}
