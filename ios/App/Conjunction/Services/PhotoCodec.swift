import UIKit
import ImageIO

/// Photo sizes and decoding (Android `PhotoCodec`): the owner's profile photo is kept at 1080 px;
/// the Bluetooth card gets a copy of at most 640 px and 61 440 bytes (SPEC 5c).
enum PhotoCodec {
    static let maxSide = 640
    static let maxBytes = 61_440

    /// Decodes [data] downsampled so the longest side is at most [maxEdge], applying EXIF
    /// orientation (ImageIO thumbnailing never decodes the full image).
    static func decode(_ data: Data, maxEdge: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxEdge,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// Redraws [image] upright (orientation .up) with its longest side at most [maxEdge] pixels.
    static func normalized(_ image: UIImage, maxEdge: CGFloat) -> UIImage {
        let w = image.size.width * image.scale, h = image.size.height * image.scale
        let f = min(1, maxEdge / max(w, h, 1))
        let size = CGSize(width: max(1, (w * f).rounded()), height: max(1, (h * f).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    /// The owner's photo file as a card photo: longest side <= 640 and <= 61 440 bytes, or nil.
    static func cardPhoto(_ url: URL) -> (jpeg: [UInt8], w: Int, h: Int)? {
        guard let data = try? Data(contentsOf: url), let decoded = decode(data, maxEdge: maxSide) else { return nil }
        var image = normalized(decoded, maxEdge: CGFloat(maxSide))
        for _ in 0..<6 {
            for quality in [0.85, 0.75, 0.65, 0.55, 0.45] {
                if let jpeg = image.jpegData(compressionQuality: quality), jpeg.count <= maxBytes {
                    return ([UInt8](jpeg), Int(image.size.width * image.scale), Int(image.size.height * image.scale))
                }
            }
            image = normalized(image, maxEdge: max(image.size.width, image.size.height) * image.scale * 0.8)
        }
        return nil
    }

    /// A picked profile photo, downscaled to 1080 px JPEG for keeping on the phone.
    static func profileJPEG(from data: Data) -> Data? {
        guard let decoded = decode(data, maxEdge: 1080) else { return nil }
        return normalized(decoded, maxEdge: 1080).jpegData(compressionQuality: 0.88)
    }
}
