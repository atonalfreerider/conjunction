import Foundation

/// On-device model prompts for streamlined onboarding (SPEC 5d.5), identical wording to Kotlin
/// `prompt.OnboardingPrompt` (pinned by testdata/golden_prompts.json `onboarding`). Answers are
/// validated by `OnboardingExtraction`; the deterministic parsers run first and the model only
/// fills chips that are still empty. The model never sees Instagram data and fetches nothing.
public enum OnboardingPrompt {
    /// Longest user text passed to the model, in code points.
    public static let maxInputCodePoints = 500

    public static func birth(_ text: String, today: LocalDate) -> String {
        [
            "Extract birth details from the text between <<< and >>>. Answer with one JSON object and nothing else, exactly these keys:",
            "{\"date\":\"YYYY-MM-DD\" or null,\"time\":\"HH:MM\" in 24-hour time or null,\"timeUnknown\":true or false,\"place\":string or null}",
            "Fill a field only if the text states it; never guess. \"timeUnknown\" is true only if the text says the birth time is not known. " +
                "\"place\" is the birth town or city with its region or country, as written. Today is \(today); a birth date is never in the future.",
            "<<<\(sanitize(text))>>>",
        ].joined(separator: "\n")
    }

    public static func aboutYou(_ text: String) -> String {
        [
            "Extract profile details from the text between <<< and >>>. Answer with one JSON object and nothing else, exactly these keys:",
            "{\"firstName\":string or null,\"gender\":\"male\" or \"female\" or null,\"seeking\":\"male\" or \"female\" or \"both\" or null," +
                "\"relationship\":\"single\" or \"in_a_relationship\" or null,\"lookingFor\":\"dating\" or \"friends\" or \"both\" or null,\"interests\":string or null}",
            "Fill a field only if the text states it. Never guess gender or orientation from a name or from interests. " +
                "\"seeking\" is who they want to meet; \"lookingFor\" is dating, friends or both; \"interests\" is a short comma-separated list of hobbies in their own words.",
            "<<<\(sanitize(text))>>>",
        ].joined(separator: "\n")
    }

    /// User text for a prompt: line breaks and other control characters become spaces, the
    /// delimiters are dropped, the result is trimmed and capped at `maxInputCodePoints` code points.
    public static func sanitize(_ text: String) -> String {
        var flat = String.UnicodeScalarView()
        for c in text.unicodeScalars {
            flat.append(c.value < 0x20 || c.value == 0x7F || c.value == 0x2028 || c.value == 0x2029 ? " " : c)
        }
        let t = String(flat).replacingOccurrences(of: "<<<", with: "", options: .literal)
            .replacingOccurrences(of: ">>>", with: "", options: .literal).trim()
        let scalars = t.unicodeScalars
        return scalars.count <= maxInputCodePoints ? t : String(String.UnicodeScalarView(scalars.prefix(maxInputCodePoints)))
    }
}

/// "Write one for me" (SPEC 5d.5 screen 4): a one-line dating-card bio prompt, identical wording to
/// Kotlin `prompt.BioPrompt` (golden_prompts.json `charts[].bio`). iOS passes text only. The answer
/// passes ReadingCleaner and the local contact-info filter; the fallback is `FallbackReadings.bio`.
public enum BioPrompt {
    public static let maxChars = 140

    public static func build(sun: ZodiacSign, moon: ZodiacSign, rising: ZodiacSign?, chineseAnimal: ChineseAnimal,
                             lookingFor: LookingFor?, interests: String?) -> String {
        let signs = "\(sun.displayName) sun, \(moon.displayName) moon" + (rising.map { ", \($0.displayName) rising" } ?? "")
        let goal: String
        switch lookingFor {
        case .dating?: goal = " They are here to date."
        case .friends?: goal = " They are here to make friends."
        case .both?: goal = " They are open to dating and friendship."
        case nil: goal = ""
        }
        let likes = interests.map(OnboardingPrompt.sanitize).flatMap { $0.isEmpty ? nil : " Their interests: \($0)." } ?? ""
        return "Write one short bio line for a dating card, at most \(maxChars) characters, in the first person. " +
            "The person is a \(signs), born in the Year of the \(chineseAnimal.displayName).\(goal)\(likes)\n" +
            "Make it warm, playful and specific, with a light celestial touch. No hashtags, no emoji-only answer, no names, no age, " +
            "no contact details, no links, no quotation marks. Answer with the bio line only."
    }
}
