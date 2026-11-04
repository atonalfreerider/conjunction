import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Where the phone's on-device model stands, for the UI.
enum ModelAvailability: Equatable {
    case available
    /// Why not, in words for a status line.
    case unavailable(String)
}

/// A generation failure, with the Android app's wording where it had one.
struct ModelFailure: LocalizedError, Equatable {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Private, foreground-only text generation with Apple's on-device model (Foundation Models,
/// iOS 26 + Apple Intelligence): the iOS counterpart of the Android app's Gemini Nano client, in
/// the same shape as Sundial's HoroscopeService. Prompts and answers never leave the phone. On
/// iOS 17-25, or without Apple Intelligence, every call throws and the caller falls back to
/// ConjunctionCore's bundled readings.
final class OnDeviceModel: @unchecked Sendable {
    static let shared = OnDeviceModel()

    /// The session's instructions; everything a reading needs, including its limits, is in the prompt.
    static let instructions = "You write short readings and checks for Conjunction, an astrology app. Follow the request exactly and reply with the requested text only."

    var availability: ModelAvailability {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) { return AppleIntelligence.availability() }
        #endif
        return .unavailable("On-device readings need iOS 26 and Apple Intelligence.")
    }

    var isAvailable: Bool { availability == .available }

    /// One prompt, one answer (a fresh session per request, as each Android request stood alone).
    func generate(_ prompt: String, instructions: String = OnDeviceModel.instructions) async throws -> String {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            if case .unavailable(let why) = AppleIntelligence.availability() { throw ModelFailure(why) }
            return try await AppleIntelligence.respond(instructions: instructions, prompt: prompt)
        }
        #endif
        throw ModelFailure("On-device readings need iOS 26 and Apple Intelligence.")
    }
}

#if canImport(FoundationModels)
@available(iOS 26.0, *)
private enum AppleIntelligence {
    static func availability() -> ModelAvailability {
        let availability = SystemLanguageModel.default.availability
        if case .available = availability { return .available }
        if case .unavailable(let reason) = availability { return .unavailable(message(for: reason)) }
        return .unavailable("Apple Intelligence is not available right now.")
    }

    static func respond(instructions: String, prompt: String) async throws -> String {
        let session = LanguageModelSession(instructions: instructions)
        do {
            let response = try await session.respond(to: prompt)
            return response.content
        } catch let error as LanguageModelSession.GenerationError {
            throw ModelFailure(message(for: error))
        }
    }

    private static func message(for reason: SystemLanguageModel.Availability.UnavailableReason) -> String {
        if case .deviceNotEligible = reason { return "Apple Intelligence is not available on this device." }
        if case .appleIntelligenceNotEnabled = reason { return "Turn on Apple Intelligence in Settings to write readings on this device." }
        if case .modelNotReady = reason { return "Apple Intelligence is still preparing. Try again in a few minutes." }
        return "Apple Intelligence is not available right now."
    }

    private static func message(for error: LanguageModelSession.GenerationError) -> String {
        if case .guardrailViolation = error { return "Apple Intelligence declined to write this. Please try again." }
        if case .assetsUnavailable = error { return "Apple Intelligence is still preparing. Try again in a few minutes." }
        if case .unsupportedLanguageOrLocale = error { return "Apple Intelligence does not support this device's language yet." }
        let description = error.localizedDescription
        return description.isEmpty ? "On-device generation failed" : description
    }
}
#endif
