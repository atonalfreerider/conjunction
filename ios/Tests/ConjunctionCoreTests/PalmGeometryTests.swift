import Foundation
import XCTest
@testable import ConjunctionCore

/// Synthetic palms (an upright right hand on light skin with drawn creases), the same cases as
/// the Android app's PalmGeometryTest, so both ports find the same lines.
final class PalmGeometryTests: XCTestCase {
    let w = 600, h = 800

    /// Wrist at the bottom, knuckles at y=300, fingers above; pinky on the image's left.
    func hand() -> [Point2] {
        var pts = [Point2](repeating: Point2(0, 0), count: 21)
        pts[0] = Point2(300, 650)
        pts[1] = Point2(400, 600); pts[2] = Point2(450, 540); pts[3] = Point2(490, 480); pts[4] = Point2(520, 430)
        func finger(_ mcp: Int, _ x: Double, _ len: Double) {
            pts[mcp] = Point2(x, 300)
            for k in 1...3 { pts[mcp + k] = Point2(x, 300 - len * Double(k) / 3) }
        }
        finger(5, 400, 210)
        finger(9, 330, 240)
        finger(13, 260, 220)
        finger(17, 190, 170)
        return pts
    }

    func skin() -> GrayImage {
        GrayImage(width: w, height: h, pixels: (0..<(w * h)).map { i in
            let x = i % w, y = i / w
            return 0.72 + 0.08 * Float(y) / Float(h) + 0.02 * (Float((x * 7919 + y * 104729) % 97) / 97 - 0.5)
        })
    }

    func drawLine(_ img: inout GrayImage, _ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, sag: Float = 0) {
        let steps = Int(hypot(x1 - x0, y1 - y0) * 2)
        for s in 0...steps {
            let t = Float(s) / Float(steps)
            let x = x0 + (x1 - x0) * t
            let y = y0 + (y1 - y0) * t + sag * 4 * t * (1 - t)
            for dy in -2...2 {
                for dx in -2...2 {
                    let px = Int(x + Float(dx)), py = Int(y + Float(dy))
                    if (0..<w).contains(px) && (0..<h).contains(py) {
                        img[px, py] = img[px, py] * (0.55 + 0.08 * Float(abs(dx) + abs(dy)))
                    }
                }
            }
        }
    }

    func drawFaintLine(_ img: inout GrayImage, _ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, darken: Float) {
        let steps = Int(hypot(x1 - x0, y1 - y0) * 2)
        for s in 0...steps {
            let t = Float(s) / Float(steps)
            let x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t
            for dx in -1...1 {
                let px = Int(x + Float(dx)), py = Int(y)
                if (0..<w).contains(px) && (0..<h).contains(py) { img[px, py] = img[px, py] * darken }
            }
        }
    }

    private func mean(_ v: [Double]) -> Double { v.reduce(0, +) / Double(v.count) }

    func testFindsHeartAndHeadLinesWhereTheyAreDrawn() throws {
        var img = skin()
        drawLine(&img, 185, 345, 370, 335, sag: -12)
        drawLine(&img, 230, 410, 400, 405)
        let d = PalmGeometry.detect(img, landmarks: hand())
        let heart = try XCTUnwrap(d.lines[.heart], "heart found: \(d.lines.keys)")
        let head = try XCTUnwrap(d.lines[.head], "head found: \(d.lines.keys)")
        XCTAssertEqual(mean(heart.polyline.map(\.y)), 340, accuracy: 25)
        XCTAssertEqual(mean(head.polyline.map(\.y)), 408, accuracy: 25)
    }

    func testFindsAFaintFateLineAfterContrastEnhancement() throws {
        var img = skin()
        drawLine(&img, 185, 345, 370, 335, sag: -12)
        drawLine(&img, 230, 410, 400, 405)
        drawFaintLine(&img, 300, 630, 305, 360, darken: 0.92)
        let d = PalmGeometry.detect(img, landmarks: hand())
        let fate = try XCTUnwrap(d.lines[.fate], "fate found: \(d.lines.keys)")
        XCTAssertEqual(mean(fate.polyline.map(\.x)), 302, accuracy: 20)
        XCTAssertNotNil(d.enhanced)
    }

    func testBlankPalmHasNoLongLines() throws {
        let d = PalmGeometry.detect(skin(), landmarks: hand())
        let f = try PalmGeometry.features(d, hand: .right, landmarks: hand())
        XCTAssertFalse(f.line(.heart).present && f.line(.heart).length > 0.8)
    }

    func testRectifyMapsKnucklesToTopAndWristToBottom() {
        let quad = PalmGeometry.palmQuad(hand())
        XCTAssertLessThan(PalmGeometry.toImage(quad, PalmGeometry.W / 2, 0).y, 320)
        XCTAssertGreaterThan(PalmGeometry.toImage(quad, PalmGeometry.W / 2, PalmGeometry.H - 1).y, 620)
    }

    func testFeaturesFeedCoreAnalyzer() throws {
        var img = skin()
        drawLine(&img, 185, 345, 370, 335)
        drawLine(&img, 380, 360, 360, 640, sag: 40)
        let d = PalmGeometry.detect(img, landmarks: hand(), handConfidence: 0.9)
        let f = try PalmGeometry.features(d, hand: .right, landmarks: hand())
        XCTAssertTrue((0.0...1.0).contains(f.quality))
        XCTAssertTrue(f.mounts.values.allSatisfy { (0.0...1.0).contains($0) })
        XCTAssertGreaterThan(f.fingers.fingerToPalm, 0.5)
    }
}
