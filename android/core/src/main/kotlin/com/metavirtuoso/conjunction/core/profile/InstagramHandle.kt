package com.metavirtuoso.conjunction.core.profile

/**
 * Instagram username normalisation and validation (SPEC 5d.6, golden `testdata/golden_instagram.json`).
 * Twin of Swift `InstagramHandle` and Python `conjunction_p2p.instagram`.
 *
 * There is no Instagram sign-in or import: the user types or pastes a handle or a profile link
 * (Instagram > Share profile > Copy link), or shares it to the app (Android ACTION_SEND).
 */
object InstagramHandle {
    const val MAX_LENGTH = 30

    /** A first path segment that is an Instagram page, not a profile. */
    val RESERVED_SEGMENTS: Set<String> =
        "p reel reels stories explore accounts direct tv about developer legal web oauth".split(' ').toSet()

    private const val HOST = "instagram.com/"

    /** `^[a-z0-9._]{1,30}$` with at least one letter or digit: the wire rule for `profile.ig`. */
    fun isValid(handle: String?): Boolean {
        if (handle == null || handle.length !in 1..MAX_LENGTH) return false
        var alnum = false
        for (c in handle) {
            when (c) {
                in 'a'..'z', in '0'..'9' -> alnum = true
                '.', '_' -> Unit
                else -> return false
            }
        }
        return alnum
    }

    /**
     * User input -> normalised handle, or null when it is not a valid Instagram username:
     * 1. text containing `instagram.com/` (any case, any subdomain/scheme): the first path segment
     *    after the first occurrence (up to `/ ? #` or whitespace); `_u` -> the next segment; a
     *    reserved segment (post, reel, ...) -> invalid;
     * 2. otherwise trim whitespace and strip one leading `@`;
     * 3. ASCII-lowercase, then [isValid].
     */
    fun normalize(text: String?): String? {
        if (text == null) return null
        val at = asciiLower(text).indexOf(HOST)
        val candidate = if (at >= 0) {
            var (seg, end) = segment(text, at + HOST.length)
            if (asciiLower(seg) == "_u") {
                seg = if (end < text.length && text[end] == '/') segment(text, end + 1).first else ""
            }
            if (asciiLower(seg) in RESERVED_SEGMENTS) return null
            seg
        } else {
            trim(text).removePrefix("@")
        }
        return asciiLower(candidate).takeIf(::isValid)
    }

    /** Receiver leniency for `profile.ig`: a string failing [isValid] is treated as null. */
    fun received(ig: String?): String? = ig?.takeIf(::isValid)

    /** The link the Instagram chip opens (app via verified app link, else the browser). */
    fun profileUrl(handle: String): String = "https://www.instagram.com/$handle/"

    /** Unicode White_Space, spelled out so Kotlin, Swift and Python trim identically. */
    fun isWhitespace(c: Char): Boolean = c in '\u0009'..'\u000D' || c == ' ' || c == '\u0085' || c == ' ' ||
        c == ' ' || c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' ||
        c == ' ' || c == '　'

    internal fun trim(s: String): String = s.trim(::isWhitespace)

    private fun asciiLower(s: String): String =
        buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }

    private fun segment(s: String, start: Int): Pair<String, Int> {
        var end = start
        while (end < s.length && s[end] != '/' && s[end] != '?' && s[end] != '#' && !isWhitespace(s[end])) end++
        return s.substring(start.coerceAtMost(s.length), end) to end
    }
}

/**
 * Sender state for the chat `profile` toward ONE friend (SPEC 5d.6); persist it with the friend
 * record. Twin of Swift `InstagramShareState` and Python `instagram.ShareState`.
 *
 * - [rev]: profile revision for this friend: 0 when the friendship is created, +1 whenever the
 *   effective value changes (handle edited or removed, per-friend switch flipped).
 * - [ackedRev]: highest rev the friend acknowledged (-1 = none).
 * - [inFlightId] / [inFlightRev]: the last `profile` sent and not yet acked.
 * - [value]: the effective value at [rev] ([effective]).
 *
 * Flow: on entering SECURE (new friend or friend nearby) and whenever [rev] changes on an open SECURE
 * link, if [needsSend]: `id = link.sendProfile(rev, value)` FIRST (before queued chats), then
 * `state = state.sent(id)`; on `LinkEvent.Delivered(id)`: `state = state.onAck(id)`.
 */
data class InstagramShareState(
    val value: String? = null,
    val rev: Int = 0,
    val ackedRev: Int = -1,
    val inFlightId: String? = null,
    val inFlightRev: Int = -1,
) {
    val needsSend: Boolean get() = ackedRev < rev

    /** Applies the current effective value; bumps [rev] only when it changed. */
    fun withEffective(newValue: String?): InstagramShareState =
        if (newValue == value) this else copy(value = newValue, rev = if (rev == Int.MAX_VALUE) rev else rev + 1)

    fun sent(id: String): InstagramShareState = copy(inFlightId = id, inFlightRev = rev)

    /** An ack for any other id is a chat ack and leaves this unchanged. */
    fun onAck(id: String): InstagramShareState =
        if (id != inFlightId) this else copy(ackedRev = maxOf(ackedRev, inFlightRev), inFlightId = null, inFlightRev = -1)

    companion object {
        /** A new friendship: rev 0 with the current effective value (so exactly one `profile` per side). */
        fun initial(handle: String?, shareWithThisFriend: Boolean) = InstagramShareState(effective(handle, shareWithThisFriend))

        /** The handle if one is set AND sharing with this friend is on, else null. */
        fun effective(handle: String?, shareWithThisFriend: Boolean): String? =
            handle?.takeIf { shareWithThisFriend && InstagramHandle.isValid(it) }

        /** Receiver: store (peerIg, peerIgRev) only when [rev] > the stored peerIgRev (initial -1). */
        fun acceptReceived(storedPeerIgRev: Int, rev: Int): Boolean = rev > storedPeerIgRev
    }
}
