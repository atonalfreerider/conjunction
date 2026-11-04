package com.metavirtuoso.conjunction.core.p2p

import java.util.UUID

/**
 * Constants of the Conjunction peer-to-peer protocol (docs/P2P_PROTOCOL.md, sections 2, 4, 10).
 * Keep identical to tools/p2p-sim/conjunction_p2p/consts.py.
 */
object P2pConstants {
    val SERVICE_UUID: UUID = UUID.fromString("7c0a1e5f-6a3b-4e2d-9f11-c0471c710001")
    /** Write (with response) only; the central sends frames. */
    val INBOX_UUID: UUID = UUID.fromString("7c0a1e5f-6a3b-4e2d-9f11-c0471c710002")
    /** Notify only; the peripheral sends frames. */
    val OUTBOX_UUID: UUID = UUID.fromString("7c0a1e5f-6a3b-4e2d-9f11-c0471c710003")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PROTOCOL_VERSION = 1
    const val PROTOCOL_MIN_VERSION = 1

    const val FRAME_VERSION = 0x01
    const val FRAME_HEADER_LEN = 7
    const val DEFAULT_ATT_MTU = 23
    const val REQUESTED_ATT_MTU = 517
    const val MAX_FRAME_LEN = 512
    const val MIN_FRAME_LEN = 20

    const val MAX_JSON_LEN = 16_384
    const val MAX_BLOB_LEN = 61_440
    const val MAX_BODY_LEN = 2 + MAX_JSON_LEN + MAX_BLOB_LEN
    const val MAX_CONCURRENT_REASSEMBLIES = 4
    const val REASSEMBLY_TIMEOUT_MS = 15_000L
    const val HELLO_TIMEOUT_MS = 10_000L
    const val KEYX_TIMEOUT_MS = 30_000L
    const val MATCH_LINK_TIMEOUT_MS = 600_000L
    /** Photos are sent only when frameLen >= this (ATT_MTU >= 185). */
    const val PHOTO_MIN_FRAME_LEN = 182

    const val MAX_FIRST_NAME_BYTES = 64
    const val MAX_BIO_BYTES = 280
    const val MAX_CHAT_TEXT_BYTES = 4000
    const val MAX_READ_REFS = 64
    const val MAX_PHOTO_SIDE = 640

    const val FRIEND_TAG_LEN = 8
    const val FRIEND_TAGS_MAX = 64
    const val FRIEND_TAGS_REAL_MAX = 56
    const val FRIEND_TAGS_BLOCK = 8

    /** Inbound token buckets: capacity and refill per second. */
    const val RATE_FRAMES_CAP = 300; const val RATE_FRAMES_PER_S = 150
    const val RATE_MESSAGES_CAP = 30; const val RATE_MESSAGES_PER_S = 5
    const val RATE_CHATS_CAP = 10; const val RATE_CHATS_PER_S = 1

    const val EPHEMERAL_ID_BYTES = 16
    const val EPHEMERAL_ID_ROTATION_MS = 15 * 60 * 1000L

    /** Proximity gate (SPEC 5c): RSSI >= this in >= [PROXIMITY_MIN_SIGHTINGS] scans within [PROXIMITY_WINDOW_MS]. */
    const val PROXIMITY_RSSI_DBM = -80
    const val PROXIMITY_MIN_SIGHTINGS = 2
    const val PROXIMITY_WINDOW_MS = 30_000L
    const val MAX_OPEN_LINKS = 4
    const val RECONNECT_BACKOFF_MS = 120_000L

    /** Largest frame for a negotiated ATT_MTU: min(ATT_MTU - 3, 512), at least 20. */
    fun maxFrameLen(attMtu: Int): Int = (attMtu - 3).coerceAtMost(MAX_FRAME_LEN).coerceAtLeast(MIN_FRAME_LEN)
}
