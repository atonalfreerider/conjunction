package com.metavirtuoso.conjunction.core.onboarding

import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking

/** The "About you" chips (SPEC 5d.5 screen 3); every field optional. */
data class AboutYou(
    val firstName: String? = null,
    val gender: Gender? = null,
    val seeking: Seeking? = null,
    val relationship: RelationshipStatus? = null,
    val lookingFor: LookingFor? = null,
    val interests: String? = null,
) {
    /** The model (or any second source) only fills chips that are still empty (SPEC 5d.5). */
    fun fillEmpty(other: AboutYou?): AboutYou = if (other == null) this else AboutYou(
        firstName = firstName ?: other.firstName,
        gender = gender ?: other.gender,
        seeking = seeking ?: other.seeking,
        relationship = relationship ?: other.relationship,
        lookingFor = lookingFor ?: other.lookingFor,
        interests = interests ?: other.interests,
    )
}

/**
 * Deterministic "About you" extraction (SPEC 5d.5, golden `testdata/golden_about_you.json`); twin of
 * Swift `AboutYouParser` with the same regex texts. Only explicit statements count: it never guesses
 * gender or orientation from a name.
 *
 * Order: (1) seeking clauses ("looking for / into / date / seeking ... men|women|both|everyone") are
 * read and removed, so "a man looking for a woman" is male seeking female; (2) first name after
 * "I'm / I am / my name is / call me" (skipping words such as "a", "single", "looking"); (3) own
 * gender from man/guy/male/... vs woman/girl/female/... (both present -> null); (4) relationship
 * status; (5) looking-for (dating words vs friends words; both -> BOTH); (6) interests after
 * "into / love / enjoy / like ..." up to punctuation, max 200 UTF-8 bytes.
 */
object AboutYouParser {
    const val MAX_INTERESTS_BYTES = 200
    const val MAX_FIRST_NAME_BYTES = 64

    private const val B = "(?<![\\p{L}0-9])"
    private const val E = "(?![\\p{L}0-9])"

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MEN = "men|guys|boys|man|guy|boy|males|male|gentlemen|dudes"
    private const val WOMEN = "women|girls|woman|girl|ladies|lady|females|female|gals"
    private const val ANY = "both|everyone|anyone|either|all genders|any gender|men (?:and|or) women|women (?:and|or) men|guys (?:and|or) girls|girls (?:and|or) guys"

    private val SEEKING = re(
        "$B(?:looking +for|looking +to +date|seeking|into|interested +in|want +to +date|wanna +date|attracted +to|dating|date|likes|like|loves|love)" +
            " +(?:(?:a|an|some|other|cute|nice|kind|single|only|mostly) +)*($ANY|$MEN|$WOMEN)$E",
    )
    private val ORIENTATION_BOTH = re("$B(?:bisexual|pansexual)$E")
    /** "my name is / call me X": any case. */
    private val NAME_STATED = re("$B(?:my +name +is|my +name's|call +me|name:) *(\\p{L}[\\p{L}\\p{M}'-]*)")
    /** "I'm X": only a capitalised word ("I'm funny" is not a name). */
    private val NAME_IM = re("$B(?:i'm|i +am|im) +(\\p{L}[\\p{L}\\p{M}'-]*)")

    /** Words after "I'm" that are not a name. */
    val NOT_NAMES: Set<String> = (
        "a an the single looking into in on at from with married engaged taken not just here new so very really also " +
            "seeking searching dating interested open shy happy living based originally currently over about " +
            "bi bisexual pansexual straight gay lesbian queer " + "$MEN $WOMEN".replace('|', ' ')
        ).split(' ').filter { it.isNotEmpty() }.toSet()
    private val MALE = re("$B(?:$MEN)$E")
    private val FEMALE = re("$B(?:$WOMEN)$E")
    private val NOT_SINGLE = re("${B}not +single$E")
    private val SINGLE = re("${B}single$E")
    private val IN_RELATIONSHIP = re(
        "$B(?:married|engaged|taken|partnered|in +a +relationship|my +(?:partner|boyfriend|girlfriend|husband|wife|fianc[eé]e?)" +
            "|have +a +(?:partner|boyfriend|girlfriend|husband|wife))$E",
    )
    private val DATING = re("$B(?:date|dates|dating|romance|romantic|relationship|love +of +my +life|soulmate|partner)$E")
    private val FRIENDS = re("$B(?:friends|friend|friendship|friendships|platonic|buddies|buddy|pals)$E")
    private val MY_FRIENDS = re("${B}(?:my|our) +friends?$E")
    private val INTERESTS = re(
        "$B(?:i'm +into|i +am +into|into|love|loves|enjoy|enjoys|i +like|like|likes|passionate +about|interested +in|fan +of|hobbies(?: +are| +include)?:?)" +
            " +([^,.;!?\\n]+)",
    )
    private val INTEREST_STOP = re("(?:^| +)(?:and +)?(?:looking|seeking|who|that|but|want|wanting|hoping|searching)$E.*$")
    private val NOT_INTEREST = re("^(?:to|be|it|that|this|you|me|$MEN|$WOMEN|$ANY)$E")

    fun parse(text: String): AboutYou {
        var s = BirthTextParser.normalizeSpaces(text)

        // 1. Seeking clauses (then removed so their gender words are not read as the user's own).
        var seekMale = false
        var seekFemale = false
        while (true) {
            val m = SEEKING.find(s) ?: break
            val target = m.groupValues[1].lowercase()
            when {
                Regex("^(?:$ANY)$").matches(target) -> { seekMale = true; seekFemale = true }
                Regex("^(?:$MEN)$").matches(target) -> seekMale = true
                else -> seekFemale = true
            }
            s = s.substring(0, m.range.first) + " , " + s.substring(m.range.last + 1)
        }
        if (ORIENTATION_BOTH.containsMatchIn(s)) { seekMale = true; seekFemale = true }
        val seeking = when {
            seekMale && seekFemale -> Seeking.BOTH
            seekMale -> Seeking.MALE
            seekFemale -> Seeking.FEMALE
            else -> null
        }

        // 2. First name.
        var firstName: String? = null
        val candidates = NAME_STATED.findAll(s).map { it.groupValues[1] } +
            NAME_IM.findAll(s).map { it.groupValues[1] }.filter { it[0].isUpperCase() }
        for (w in candidates) {
            if (w.lowercase().trimEnd('\'', '-') in NOT_NAMES) continue
            firstName = OnboardingExtraction.cleanFirstName(w.replaceFirstChar { it.uppercaseChar() })
            if (firstName != null) break
        }

        // 3. Own gender.
        val male = MALE.containsMatchIn(s)
        val female = FEMALE.containsMatchIn(s)
        val gender = when {
            male && !female -> Gender.MALE
            female && !male -> Gender.FEMALE
            else -> null
        }

        // 4. Relationship status.
        val notSingle = NOT_SINGLE.containsMatchIn(s)
        val single = !notSingle && SINGLE.containsMatchIn(s)
        val inRel = notSingle || IN_RELATIONSHIP.containsMatchIn(s)
        val relationship = when {
            single && !inRel -> RelationshipStatus.SINGLE
            inRel && !single -> RelationshipStatus.IN_A_RELATIONSHIP
            else -> null
        }

        // 5. Looking for (status words such as "in a relationship" / "my partner" are not dating intent).
        val intent = IN_RELATIONSHIP.replace(MY_FRIENDS.replace(BirthTextParser.normalizeSpaces(text), " "), " ")
        val dating = DATING.containsMatchIn(intent)
        val friends = FRIENDS.containsMatchIn(intent)
        val lookingFor = when {
            dating && friends -> LookingFor.BOTH
            friends -> LookingFor.FRIENDS
            dating -> LookingFor.DATING
            else -> null
        }

        // 6. Interests.
        val parts = INTERESTS.findAll(s).map { INTEREST_STOP.replace(it.groupValues[1], "").trim() }
            .filter { it.isNotEmpty() && !NOT_INTEREST.containsMatchIn(it) }
            .distinct().toList()
        val interests = OnboardingExtraction.cleanInterests(parts.joinToString(", "))

        return AboutYou(firstName, gender, seeking, relationship, lookingFor, interests)
    }
}
