import Foundation
import ConjunctionCore
#if canImport(FoundationModels)
import FoundationModels
#endif

/// On-device AI helpers for streamlined onboarding (SPEC 5d.5; Android `ai/OnboardingAssistant`).
/// Every call:
/// - runs only when Apple's on-device model is available NOW (iOS 26 + Apple Intelligence; on iOS
///   17-25 every call returns nil and the screens use the deterministic parsers only);
/// - has a hard timeout (4 s extraction, 6 s bio) and returns nil on timeout, error, or a value that
///   fails validation, so the caller silently keeps its deterministic result;
/// - is cancellable: it runs in the caller's task (`.task(id:)`), so new input or leaving the
///   screen drops a late answer.
/// Birth and about-you use guided generation (`@Generable`); the fields go straight to
/// `OnboardingExtraction.validate*`. Nothing typed is stored as typed, nothing leaves the phone.
struct OnboardingAssistant {
    var model: OnDeviceModel = .shared

    func birth(_ text: String, today: LocalDate) async -> BirthExtraction? {
        guard model.isAvailable else { return nil }
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            let prompt = OnboardingPrompt.birth(text, today: today)
            return await Self.withTimeout(OnboardingExtraction.extractionTimeoutMs) {
                guard let a = try? await GuidedOnboarding.birth(prompt) else { return nil }
                return OnboardingExtraction.validateBirth(date: a.date, time: a.time, timeUnknown: a.timeUnknown, place: a.place, today: today)
            }
        }
        #endif
        return nil
    }

    func aboutYou(_ text: String) async -> AboutYou? {
        guard model.isAvailable else { return nil }
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            let prompt = OnboardingPrompt.aboutYou(text)
            return await Self.withTimeout(OnboardingExtraction.extractionTimeoutMs) {
                guard let a = try? await GuidedOnboarding.aboutYou(prompt) else { return nil }
                return OnboardingExtraction.validateAboutYou(firstName: a.firstName, gender: a.gender, seeking: a.seeking,
                                                             relationship: a.relationship, lookingFor: a.lookingFor, interests: a.interests)
            }
        }
        #endif
        return nil
    }

    /// A model-written bio line, or nil (the caller shows `fallbackBio`). Text only on iOS.
    func bio(_ charts: OwnerCharts, lookingFor: LookingFor?, interests: String?, reroll: Bool) async -> String? {
        guard model.isAvailable else { return nil }
        let prompt = BioPrompt.build(sun: charts.natal.sunSign, moon: charts.natal.moonSign, rising: charts.natal.risingSign,
                                     chineseAnimal: charts.chinese.yearAnimal, lookingFor: lookingFor, interests: interests)
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            return await Self.withTimeout(OnboardingExtraction.bioTimeoutMs) {
                Self.cleanBio(try? await GuidedOnboarding.text(prompt, temperature: reroll ? 0.9 : nil))
            }
        }
        #endif
        return nil
    }

    /// Shown at once; "Another" increments [seed].
    static func fallbackBio(_ charts: OwnerCharts, interests: String?, seed: Int) -> String {
        let i = interests?.trimmingCharacters(in: .whitespacesAndNewlines)
        return FallbackReadings.bio(charts.natal, interests: (i?.isEmpty ?? true) ? nil : i, seed: seed)
    }

    /// One line, <= 140 characters, through ReadingCleaner and the local contact-info filter; an
    /// answer that is only emoji / punctuation is rejected.
    static func cleanBio(_ raw: String?) -> String? {
        guard let cleaned = ReadingCleaner.clean(raw) else { return nil }
        let quotes = CharacterSet(charactersIn: "\"“”")
        guard let line = cleaned.split(separator: "\n").map({ $0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: quotes) })
            .first(where: { !$0.isEmpty }) else { return nil }
        guard line.contains(where: { $0.isLetter }), !TextHeuristics.flags(line) else { return nil }
        return line.unicodeScalars.count <= BioPrompt.maxChars ? line : nil
    }

    /// Runs [work] with a hard deadline: returns nil as soon as [ms] pass (or the caller is
    /// cancelled), without waiting for the work to stop; the work is then cancelled. A task group
    /// would wait for a model call that ignores cancellation, so the race uses one continuation
    /// that resumes once.
    static func withTimeout<T: Sendable>(_ ms: Int64, _ work: @escaping @Sendable () async -> T?) async -> T? {
        let gate = TimeoutGate<T>()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (cont: CheckedContinuation<T?, Never>) in
                let worker = Task { gate.finish(await work()) }
                gate.start(cont, worker: worker)
                Task {
                    try? await Task.sleep(nanoseconds: UInt64(max(ms, 0)) * 1_000_000)
                    gate.finish(nil)
                }
            }
        } onCancel: {
            gate.finish(nil)
        }
    }
}

/// Resumes [withTimeout]'s continuation exactly once (work, deadline or cancellation, whichever
/// comes first) and then cancels the work.
private final class TimeoutGate<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var cont: CheckedContinuation<T?, Never>?
    private var worker: Task<Void, Never>?
    private var finished = false
    private var early: T?? = nil

    func start(_ c: CheckedContinuation<T?, Never>, worker w: Task<Void, Never>) {
        lock.lock()
        if finished {
            let value = early ?? nil
            lock.unlock()
            w.cancel()
            c.resume(returning: value)
            return
        }
        cont = c
        worker = w
        lock.unlock()
    }

    func finish(_ value: T?) {
        lock.lock()
        if finished { lock.unlock(); return }
        finished = true
        let c = cont, w = worker
        cont = nil
        worker = nil
        if c == nil { early = .some(value) }
        lock.unlock()
        w?.cancel()
        c?.resume(returning: value)
    }
}

#if canImport(FoundationModels)
/// Guided-generation shapes for the two extraction prompts (SPEC 5d.5 JSON fields).
@available(iOS 26.0, *)
@Generable
struct BirthAnswer {
    @Guide(description: "Birth date as YYYY-MM-DD, or null when the text does not state it")
    var date: String?
    @Guide(description: "Birth time as HH:MM on a 24-hour clock, or null when the text does not state it")
    var time: String?
    @Guide(description: "True only when the text says the birth time is unknown")
    var timeUnknown: Bool
    @Guide(description: "Birth place as written (city, region, country), or null")
    var place: String?
}

@available(iOS 26.0, *)
@Generable
struct AboutYouAnswer {
    @Guide(description: "The person's first name only if the text states it, else null")
    var firstName: String?
    @Guide(description: "male or female, only if the text states it; never guessed from a name")
    var gender: String?
    @Guide(description: "male, female or both: who they want to meet, only if stated")
    var seeking: String?
    @Guide(description: "single or in_a_relationship, only if stated")
    var relationship: String?
    @Guide(description: "dating, friends or both, only if stated")
    var lookingFor: String?
    @Guide(description: "Their interests in a few words, only if stated")
    var interests: String?
}

@available(iOS 26.0, *)
enum GuidedOnboarding {
    static func birth(_ prompt: String) async throws -> BirthAnswer {
        let session = LanguageModelSession(instructions: OnDeviceModel.instructions)
        return try await session.respond(to: prompt, generating: BirthAnswer.self).content
    }

    static func aboutYou(_ prompt: String) async throws -> AboutYouAnswer {
        let session = LanguageModelSession(instructions: OnDeviceModel.instructions)
        return try await session.respond(to: prompt, generating: AboutYouAnswer.self).content
    }

    static func text(_ prompt: String, temperature: Double?) async throws -> String {
        let session = LanguageModelSession(instructions: OnDeviceModel.instructions)
        return try await session.respond(to: prompt, options: GenerationOptions(temperature: temperature)).content
    }
}
#endif
