import Foundation
import UIKit
import ConjunctionCore
#if canImport(SensitiveContentAnalysis)
import SensitiveContentAnalysis
#endif

/// On-device screening of what other people send (SPEC 5c "Safety without a server").
///
/// Text: ConjunctionCore's deterministic rules run first, in the engine, and can only add flags.
/// What they let through is asked of the on-device model (Foundation Models) for a one-word
/// verdict, but only while the app is in the foreground; in the background the message waits as
/// `.pending` and is checked when the app opens. Without the model the rules' verdict stands.
///
/// Photos: Apple's SensitiveContentAnalysis when the person has turned on Sensitive Content
/// Warning (or Communication Safety) — the framework's policy says so. Otherwise the photo is
/// `.unchecked` and shown blurred until the person taps "Show photo". A file that is not a
/// decodable image is `.flagged`.
final class ContentScreener: @unchecked Sendable {
    private let model: OnDeviceModel

    init(model: OnDeviceModel = .shared) { self.model = model }

    func screenText(_ text: String, foreground: Bool) async -> Screening {
        if TextHeuristics.flags(text) { return .flagged }
        guard model.isAvailable else { return .safe }
        guard foreground else { return .pending }
        do {
            let answer = try await model.generate(TextHeuristics.textPrompt(text))
            return TextHeuristics.parseVerdict(answer) ?? .safe
        } catch {
            return .safe
        }
    }

    func screenPhoto(at url: URL) async -> Screening {
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data), let cg = image.cgImage else { return .flagged }
        #if canImport(SensitiveContentAnalysis)
        let analyzer = SCSensitivityAnalyzer()
        guard analyzer.analysisPolicy != .disabled else { return .unchecked }
        do {
            let result = try await analyzer.analyzeImage(cg)
            return result.isSensitive ? .flagged : .safe
        } catch {
            return .unchecked
        }
        #else
        _ = cg
        return .unchecked
        #endif
    }
}
