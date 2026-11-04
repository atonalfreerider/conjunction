package com.metavirtuoso.conjunction.nearby

import com.metavirtuoso.conjunction.R

/**
 * What the UI shows for Nearby (SPEC 5c). Everything here lives only on this phone; it is built
 * from what nearby phones sent over Bluetooth (docs/P2P_PROTOCOL.md) and stored by [P2pStore].
 */

/** On-device screening result for something a stranger or friend sent (photo or chat text). */
enum class Screening(val wire: String) {
    /** Not checked yet (the on-device model runs only while the app is in the foreground). */
    PENDING("pending"),
    /** Checked and looks fine. */
    SAFE("safe"),
    /** Looks like it breaks the Community Guidelines: hidden until the person chooses to see it. */
    FLAGGED("flagged"),
    /** Photos only: this phone could not check it (no image-capable on-device model): blurred until tapped. */
    UNCHECKED("unchecked");

    companion object {
        fun fromWire(v: String?): Screening = entries.firstOrNull { it.wire == v } ?: PENDING
    }
}

/** The match-card fields (SPEC 5b): derived from the peer's hello + card. Sign keys are lower-case ("leo"). */
data class MatchCard(
    val firstName: String?,
    val age: Int,
    val sun: String,
    val moon: String,
    val rising: String?,
    val chineseAnimal: String,
    val relationshipStatus: String,
    val lookingFor: String,
    val hasPhoto: Boolean,
    val bio: String? = null,
)

/** One encounter (a qualifying match with someone nearby), keyed by the peer's profile fingerprint. */
data class EncounterCard(
    /** Profile fingerprint (docs/P2P_PROTOCOL.md 7.4): local only, never sent. */
    val encounterId: String,
    /** "dating" | "friends". */
    val kind: String,
    /** "pending" | "matched" (both said yes: [friendId] is set). */
    val status: String,
    val total: Int,
    val western: Int,
    val chinese: Int,
    val blood: Int?,
    val highlights: List<String>,
    val birthdayTwin: Boolean,
    val exactTwin: Boolean,
    val card: MatchCard?,
    /** Received photo file (JPEG) and its on-device screening result; null when none was sent. */
    val photoPath: String? = null,
    val photoScreening: Screening = Screening.PENDING,
    /** My answer: "right" | "left" | null. A swipe made out of range is sent at the next encounter. */
    val mySwipe: String? = null,
    /** Set once both said yes and keys were confirmed. */
    val friendId: String? = null,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
)

/** A friend (mutual match) as the UI shows it. */
data class FriendSummary(
    val id: String,
    val kind: String,
    val firstName: String,
    val bio: String?,
    val sun: String?,
    val moon: String?,
    val rising: String?,
    val chineseAnimal: String?,
    val photoPath: String?,
    val photoScreening: Screening,
    val safetyCode: String,
    val since: Long,
    val lastSeen: Long,
    /** A secure link to this friend is open right now (messages go straight through). */
    val inRange: Boolean,
    val unread: Int,
    val queued: Int,
    val lastMessage: String?,
)

enum class ChatStatus(val wire: String) {
    /** Waiting for the next time the two phones are near each other. */
    QUEUED("queued"),
    /** Written to the Bluetooth link; waiting for the delivery receipt. */
    SENT("sent"),
    DELIVERED("delivered"),
    READ("read"),
    /** Incoming messages. */
    RECEIVED("received");

    companion object {
        fun fromWire(v: String?): ChatStatus = entries.firstOrNull { it.wire == v } ?: QUEUED
    }
}

data class ChatLine(
    val id: String,
    val friendId: String,
    val mine: Boolean,
    val text: String,
    /** Sender's clock, ms since the epoch. */
    val ts: Long,
    val status: ChatStatus,
    /** Incoming only. */
    val screening: Screening = Screening.SAFE,
    /** Incoming only: I have seen it (a read receipt is due / was sent). */
    val seen: Boolean = false,
    val readReceiptSent: Boolean = false,
)

/** Event kinds for local notifications and the in-app full-screen popups. */
enum class NearbyEventKind(val wire: String) {
    MATCH("match"),
    BIRTHDAY_TWIN("birthday_twin"),
    EXACT_TWIN("exact_twin"),
    /** Both swiped right ("It's a Conjunction!"): the chat opens. */
    CONJUNCTION("conjunction"),
    MESSAGE("message");

    val isTwin: Boolean get() = this == BIRTHDAY_TWIN || this == EXACT_TWIN

    companion object {
        fun fromWire(value: String?): NearbyEventKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Something to show: a full-screen popup while the app is open, or the deep link of a tapped local
 * notification. [encounterId] = profile fingerprint of a match; [friendId] = a friend's id.
 */
data class NearbyEvent(
    val kind: NearbyEventKind,
    val encounterId: String?,
    val friendId: String?,
    val matchKind: String? = null,
    val total: Int? = null,
) {
    companion object {
        const val EXTRA_KIND = "conjunction.kind"
        const val EXTRA_ENCOUNTER = "conjunction.encounter"
        const val EXTRA_FRIEND = "conjunction.friend"
        const val EXTRA_MATCH_KIND = "conjunction.matchKind"
        const val EXTRA_TOTAL = "conjunction.total"

        fun fromExtras(get: (String) -> String?): NearbyEvent? {
            val kind = NearbyEventKind.fromWire(get(EXTRA_KIND)) ?: return null
            return NearbyEvent(kind, get(EXTRA_ENCOUNTER), get(EXTRA_FRIEND), get(EXTRA_MATCH_KIND), get(EXTRA_TOTAL)?.toIntOrNull())
        }

        fun fromIntent(intent: android.content.Intent?): NearbyEvent? {
            val extras = intent?.extras ?: return null
            return fromExtras { extras.getString(it) }
        }
    }
}

/** Report reasons (legal/IN_APP_CONSENT_COPY.md section 8). [code] goes into the email subject. */
enum class ReportReason(val code: String, val label: Int) {
    HARASSMENT("harassment", R.string.report_reason_harassment),
    SEXUAL("sexual", R.string.report_reason_sexual),
    SCAM("scam", R.string.report_reason_scam),
    FAKE("fake_profile", R.string.report_reason_fake),
    HATE("hate", R.string.report_reason_hate),
    MINOR("child_safety", R.string.report_reason_minor),
    OTHER("other", R.string.report_reason_other),
}
