import UIKit
import Vision
import ConjunctionCore

/// Face-centred profile photo (SPEC 5d.5 screen 4; Android `data/ProfilePhotoCrop`): the largest
/// face from Vision `VNDetectFaceRectanglesRequest` on the upright image, the 4:5 crop from
/// `PhotoCropGeometry`, then a 512 x 640 JPEG of at most 60 KB (quality 0.85 -> 0.40). The image is
/// redrawn from pixels, so no EXIF (location, camera, time) is written.
enum ProfilePhotoCrop {
    /// Decodes a picked photo upright, at most 2048 px on the long side.
    static func load(_ data: Data) -> UIImage? {
        guard let decoded = PhotoCodec.decode(data, maxEdge: 2048) else { return nil }
        return PhotoCodec.normalized(decoded, maxEdge: 2048)
    }

    /// Largest face, in the image's pixel coordinates (top-left origin), or nil.
    static func detectFace(_ image: UIImage) -> PhotoCropGeometry.Face? {
        guard let cg = image.cgImage else { return nil }
        let request = VNDetectFaceRectanglesRequest()
        let handler = VNImageRequestHandler(cgImage: cg, orientation: .up, options: [:])
        guard (try? handler.perform([request])) != nil,
              let best = request.results?.max(by: { $0.boundingBox.height < $1.boundingBox.height }) else { return nil }
        let w = Double(cg.width), h = Double(cg.height)
        // Vision boxes are normalised with a bottom-left origin; the box covers brows to chin, so
        // the full face (hairline to chin) is about 1.25 times as tall.
        let box = best.boundingBox
        let height = Double(box.height) * h * 1.25
        let cx = Double(box.midX) * w
        let cy = (1 - Double(box.midY)) * h - height * 0.08
        return PhotoCropGeometry.Face(cx: cx, cy: cy, height: height)
    }

    static func initialCrop(_ image: UIImage) -> PhotoCropGeometry.Rect {
        let size = pixelSize(image)
        return PhotoCropGeometry.cropFor(width: size.width, height: size.height, face: detectFace(image))
    }

    static func pixelSize(_ image: UIImage) -> (width: Double, height: Double) {
        if let cg = image.cgImage { return (Double(cg.width), Double(cg.height)) }
        return (Double(image.size.width * image.scale), Double(image.size.height * image.scale))
    }

    /// The [crop] of [image] at 512 x 640 as a JPEG of at most 60 KB.
    static func render(_ image: UIImage, crop: PhotoCropGeometry.Rect) -> Data? {
        guard let cg = image.cgImage,
              let part = cg.cropping(to: CGRect(x: crop.x, y: crop.y, width: crop.width, height: crop.height).integral) else { return nil }
        let out = CGSize(width: PhotoCropGeometry.outWidth, height: PhotoCropGeometry.outHeight)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        var rendered = UIGraphicsImageRenderer(size: out, format: format).image { _ in
            UIImage(cgImage: part).draw(in: CGRect(origin: .zero, size: out))
        }
        var last: Data?
        for q in PhotoCropGeometry.qualitySteps {
            guard let jpeg = rendered.jpegData(compressionQuality: q) else { continue }
            last = jpeg
            if jpeg.count <= PhotoCropGeometry.maxBytes { return jpeg }
        }
        // Rare: a very detailed photo; scale down until it fits.
        while let l = last, l.count > PhotoCropGeometry.maxBytes, rendered.size.width > 64 {
            rendered = PhotoCodec.normalized(rendered, maxEdge: max(rendered.size.width, rendered.size.height) * 0.85)
            last = rendered.jpegData(compressionQuality: 0.4)
        }
        return last
    }
}
