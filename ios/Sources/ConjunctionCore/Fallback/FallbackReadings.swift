import Foundation

/// Deterministic readings assembled from the bundled text in content_en.json (port of
/// fallback/FallbackReadings.kt), used whenever the on-device model is unavailable (unsupported
/// device, Apple Intelligence off, model not ready, generation failed). Readings never leave the
/// phone and never fall back to a cloud model. The same inputs give the same text on Android and
/// iOS (pinned by testdata/golden_prompts.json).
public enum FallbackReadings {
    private static func pick(_ list: [String], _ seed: Int) -> String {
        list[Math.floorMod(seed, list.count)]
    }

    /// Kotlin's fold of String.replace("{key}", value): literal, every occurrence, in order.
    private static func fill(_ template: String, _ pairs: (String, String)...) -> String {
        pairs.reduce(template) { acc, kv in acc.replacingOccurrences(of: "{\(kv.0)}", with: kv.1, options: .literal) }
    }

    /// Kotlin's "${map[key]}": the string, or "null" when absent.
    private static func s(_ v: Any?) -> String { (v as? String) ?? "null" }

    /// replaceFirstChar(Char::uppercase).
    private static func upperFirst(_ text: String) -> String {
        guard let first = text.unicodeScalars.first else { return text }
        return String(first).uppercased() + String(text.unicodeScalars.dropFirst())
    }

    public static func daily(_ chart: NatalChart, _ transitsNow: Transits, _ date: LocalDate) -> String {
        let daily = Content.obj(Content.root["daily"])
        let seed = date.epochDay + 7 * chart.sunSign.ordinal + 13 * chart.moonSign.ordinal
        let moonSign = transitsNow.bodies.first { $0.planet.key == "moon" }!.sign
        let moonDaily = Content.text("signs", moonSign.key, "daily")
        let opening = fill(pick(Content.strings(daily["openings"]), seed),
                           ("moonSign", moonSign.displayName), ("moonDaily", moonDaily))
        // Integer division truncates toward zero, as Kotlin's Long division does.
        let sunLine = fill(pick(Content.strings(daily["sunLines"]), seed / 3),
                           ("sunSign", chart.sunSign.displayName), ("sunGift", Content.text("signs", chart.sunSign.key, "gift")))
        let transit: String
        if let first = transitsNow.aspectsToNatal.first {
            transit = fill(s(Content.obj(daily["transit"])[first.type.rawValue.lowercased()]),
                           ("transit", first.a.displayName), ("natal", first.b.displayName))
        } else {
            transit = s(daily["quietSky"])
        }
        let closing = pick(Content.strings(daily["closings"]), seed / 9)
        return [opening, sunLine, transit, closing].joined(separator: " ")
    }

    public static func natal(_ chart: NatalChart, _ section: NatalSection) -> String {
        let planet = Content.entry("planets", section.planetKey)
        if section == .rising {
            guard let rising = chart.risingSign else { return s(Content.root["risingUnknown"]) }
            let sign = Content.entry("signs", rising.key)
            return [
                fill(s(planet["lead"]), ("sign", rising.displayName)),
                s(planet["focus"]),
                "You tend to arrive \(Content.strings(sign["keywords"]).joined(separator: ", ")), like \(s(sign["image"])).",
                s(Content.root["natalClosing"]),
            ].joined(separator: " ")
        }
        let body = chart.body(section.planet!)
        let sign = Content.entry("signs", body.sign.key)
        var parts = [
            fill(s(planet["lead"]), ("sign", body.sign.displayName)),
            s(planet["focus"]),
            "In \(body.sign.displayName) it brings \(s(sign["gift"])); the growing edge is \(s(sign["shadow"])).",
        ]
        if section == .love { parts.append("In love, \(s(sign["love"])).") }
        if section == .drive || section == .discipline { parts.append("In your work, \(s(sign["work"])).") }
        if let house = body.house { parts.append(Content.strings(Content.root["houses"])[house - 1]) }
        if body.retrograde { parts.append("Its retrograde motion at your birth turns this energy inward, toward reflection before action.") }
        if let asp = chart.aspects.first(where: { $0.a == body.planet || $0.b == body.planet }) {
            let other = asp.a == body.planet ? asp.b : asp.a
            parts.append("Here \(body.planet.displayName) \(s(Content.obj(Content.root["aspects"])[asp.type.rawValue.lowercased()])) \(other.displayName), weaving the two stories together.")
        }
        parts.append(s(Content.root["natalClosing"]))
        return parts.joined(separator: " ")
    }

    public static func eastern(_ chinese: ChineseChart, _ bloodType: BloodType?) -> String {
        let animal = Content.obj(Content.entry("chinese", "animals")[chinese.yearAnimal.key])
        let section = Content.obj(Content.root["chinese"])
        let elements = Content.obj(section["elements"])
        let masters = Content.obj(section["dayMasters"])
        var parts = [
            "You are a \(chinese.yinYang.displayName) \(chinese.yearElement.displayName) \(chinese.yearAnimal.displayName): \(s(animal["traits"])), with a gift for \(s(animal["gift"])).",
            s(elements[chinese.yearElement.key]),
            "The tradition's gentle warning for your sign is \(s(animal["shadow"])).",
        ]
        if chinese.yearsDiffer {
            parts.append(fill(s(section["yearVsLunar"]),
                              ("lunarAnimal", chinese.lunarYearAnimal.displayName), ("yearAnimal", chinese.yearAnimal.displayName)))
        }
        let dm = chinese.dayMaster
        parts.append("Your Day Master is \(dm.label) (\(dm.hanzi)), pictured as \(s(masters[dm.name.lowercased()])).")
        if let bloodType {
            let t = BloodTypeReading.traits(bloodType)
            parts.append("\(s(Content.root["bloodIntro"])) \(t.title): \(t.strengths) \(t.love)")
        }
        return parts.joined(separator: " ")
    }

    public static func palm(_ features: PalmFeatures) -> String {
        let palm = Content.obj(Content.root["palm"])
        let lines = Content.obj(palm["lines"])
        var parts = [s(palm["intro"]), s(Content.obj(palm["shapes"])[features.handShape.key])]
        for line in PalmLine.allCases {
            let f = features.line(line)
            let text = Content.obj(lines[line.key])
            if f.lengthClass == .absent {
                parts.append(s(text["absent"]))
                continue
            }
            parts.append(s(text[f.lengthClass.name.lowercased()]))
            parts.append(s(text[f.isCurved ? "curved" : "straight"]))
            let depth = Content.obj(palm["depth"])
            if f.depth >= LineFeature.deepFrom {
                parts.append(fill(s(depth["deep"]), ("line", upperFirst(line.displayName))))
            } else if f.depth < LineFeature.faintBelow {
                parts.append(fill(s(depth["faint"]), ("line", upperFirst(line.displayName))))
            }
        }
        let fingers = Content.obj(palm["fingers"])
        let r = features.fingers.indexToRing
        parts.append(s(fingers[r > 1.02 ? "indexLonger" : (r < 0.98 ? "ringLonger" : "even")]))
        parts.append(s(palm["closing"]))
        return parts.joined(separator: " ")
    }

    /// Compatibility blurb without the model (one or two sentences).
    public static func compatibility(_ a: CompatProfile, _ b: CompatProfile, _ score: CompatResult, _ kind: MatchKind) -> String {
        let compat = Content.obj(Content.root["compat"])
        let bands = Content.arrOfObj(compat["bands"])
        guard let band = bands.first(where: { score.total >= (($0["min"] as? NSNumber)?.intValue ?? Int.max) }) else {
            fatalError("content_en.json: no compat band for \(score.total)")
        }
        var parts: [String] = []
        if score.isExactTwin { parts.append(s(compat["exactTwins"])) }
        else if score.isBirthdayTwin { parts.append(s(compat["twins"])) }
        parts.append(s(band[kind.wire]))
        let rel = Content.obj(compat["chineseRelations"])
        let relKey: String
        switch score.chineseRelation {
        case .same, .sameSelfPunishment: relKey = "same"
        case .trine: relKey = "trine"
        case .harmony: relKey = "harmony"
        case .clash: relKey = "clash"
        case .harm: relKey = "harm"
        case .neutral: relKey = "neutral"
        }
        parts.append(fill(s(rel[relKey]), ("animal", a.chineseAnimal.displayName), ("animalA", a.chineseAnimal.displayName),
                          ("animalB", b.chineseAnimal.displayName)))
        return parts.joined(separator: " ")
    }
}
