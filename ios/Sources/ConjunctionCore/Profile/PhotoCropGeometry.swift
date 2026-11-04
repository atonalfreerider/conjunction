import Foundation

/// Face-centred 4:5 profile photo crop (SPEC 5d.5 screen 4), the geometry shared with Android
/// `data/ProfilePhotoCrop.cropFor`: the face centre 40% from the top and the face about 35% of the
/// crop's height; no face -> the largest centred 4:5 crop. Rectangles are in source pixels.
public enum PhotoCropGeometry {
    public static let outWidth = 512
    public static let outHeight = 640
    public static let maxBytes = 60 * 1024
    /// JPEG quality steps tried in order until the file fits [maxBytes].
    public static let qualitySteps: [Double] = stride(from: 85, through: 40, by: -5).map { Double($0) / 100 }

    /// A detected face: centre and height (chin to hairline), source pixels.
    public struct Face: Equatable, Sendable {
        public let cx: Double
        public let cy: Double
        public let height: Double
        public init(cx: Double, cy: Double, height: Double) {
            self.cx = cx
            self.cy = cy
            self.height = height
        }
    }

    public struct Rect: Equatable, Sendable {
        public let x: Double, y: Double, width: Double, height: Double
        public init(x: Double, y: Double, width: Double, height: Double) {
            self.x = x
            self.y = y
            self.width = width
            self.height = height
        }
    }

    public static func cropFor(width w: Double, height h: Double, face: Face?) -> Rect {
        let maxH = min(h, w * 5 / 4)
        guard let face else {
            let cw = maxH * 4 / 5
            return Rect(x: (w - cw) / 2, y: (h - maxH) / 2, width: cw, height: maxH)
        }
        let ch = min(max(face.height / 0.35, min(maxH, 64)), maxH)
        let cw = ch * 4 / 5
        let left = min(max(face.cx - cw / 2, 0), w - cw)
        let top = min(max(face.cy - ch * 0.40, 0), h - ch)
        return Rect(x: left, y: top, width: cw, height: ch)
    }

    /// The crop after a drag of ([dx], [dy]) frame points and a pinch of [zoom], for a frame
    /// [frameWidth] points wide; it stays 4:5 and inside the [w] x [h] image.
    public static func moved(_ c: Rect, width w: Double, height h: Double, frameWidth: Double, dx: Double, dy: Double,
                             zoom: Double) -> Rect {
        let scale = frameWidth / max(c.width, 1)
        let maxW = min(w, h * 4 / 5)
        let newW = min(max(c.width / max(zoom, 0.01), min(64, maxW)), maxW)
        let newH = newW * 5 / 4
        let cx = c.x + c.width / 2 - dx / scale, cy = c.y + c.height / 2 - dy / scale
        let left = min(max(cx - newW / 2, 0), w - newW)
        let top = min(max(cy - newH / 2, 0), h - newH)
        return Rect(x: left, y: top, width: newW, height: newH)
    }
}
