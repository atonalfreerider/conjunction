package com.metavirtuoso.conjunction.nearby

import com.metavirtuoso.conjunction.core.p2p.P2pConstants

/**
 * "Close by" (SPEC 5c, protocol section 3): an advertiser seen with RSSI >= [rssiThreshold] dBm in
 * at least [minSightings] scan results within [windowMs]. Only RSSI is used; nothing is turned into a
 * distance or a location. Pure logic (tested on the JVM).
 */
class ProximityGate(
    var rssiThreshold: Int = P2pConstants.PROXIMITY_RSSI_DBM,
    private val minSightings: Int = P2pConstants.PROXIMITY_MIN_SIGHTINGS,
    private val windowMs: Long = P2pConstants.PROXIMITY_WINDOW_MS,
) {
    private val sightings = HashMap<String, ArrayDeque<Long>>()

    /** Records one scan result; true when [address] now counts as close by. */
    fun observe(address: String, rssi: Int, nowMs: Long): Boolean {
        val q = sightings.getOrPut(address) { ArrayDeque() }
        while (q.isNotEmpty() && nowMs - q.first() > windowMs) q.removeFirst()
        if (rssi >= rssiThreshold) q.addLast(nowMs)
        if (q.isEmpty()) sightings.remove(address)
        return q.size >= minSightings
    }

    /** Advertisers currently close by (for "N phones nearby" on the Nearby screen). */
    fun closeBy(nowMs: Long): Int = sightings.values.count { q -> q.count { nowMs - it <= windowMs } >= minSightings }

    fun prune(nowMs: Long) {
        sightings.values.forEach { q -> while (q.isNotEmpty() && nowMs - q.first() > windowMs) q.removeFirst() }
        sightings.values.removeAll { it.isEmpty() }
    }

    fun clear() = sightings.clear()
}

/**
 * When the central side may (re)connect to an advertiser address (protocol section 3): not while a
 * link to it is open or opening, and not within a cool-down after a finished link. Failed
 * connection attempts back off exponentially (5 s, 10 s, ... up to 120 s).
 */
class ReconnectPolicy {
    private val until = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()

    fun mayConnect(address: String, nowMs: Long): Boolean = (until[address] ?: 0L) <= nowMs

    /** A link to [address] ended normally with [outcome]. */
    fun finished(address: String, outcome: LinkOutcome, nowMs: Long) {
        failures.remove(address)
        until[address] = nowMs + cooldownFor(outcome)
    }

    /** Connecting or setting up the GATT link failed. */
    fun failed(address: String, nowMs: Long) {
        val n = (failures[address] ?: 0) + 1
        failures[address] = n
        until[address] = nowMs + minOf(P2pConstants.RECONNECT_BACKOFF_MS, FIRST_BACKOFF_MS shl (n - 1).coerceAtMost(5))
    }

    /** Lift "finished" cool-downs (e.g. the person queued a message for a friend who may be in range). */
    fun expedite(nowMs: Long) {
        until.keys.removeAll { it !in failures.keys }
        until.values.removeAll { it <= nowMs }
    }

    /** Forgets addresses whose cool-down ended long ago (advertiser addresses rotate, so the maps would only grow). */
    fun prune(nowMs: Long) {
        val stale = until.filterValues { nowMs - it > FORGET_AFTER_MS }.keys
        until.keys.removeAll(stale)
        failures.keys.removeAll(stale)
    }

    fun clear() {
        until.clear()
        failures.clear()
    }

    companion object {
        const val FIRST_BACKOFF_MS = 5_000L
        /** No match / declined / hidden: do not ask the same advertiser again for a while (addresses rotate anyway). */
        const val SETTLED_COOLDOWN_MS = 10 * 60_000L
        const val FORGET_AFTER_MS = 30 * 60_000L

        fun cooldownFor(outcome: LinkOutcome): Long = when (outcome) {
            LinkOutcome.NO_MATCH, LinkOutcome.DONE, LinkOutcome.DEDUP -> SETTLED_COOLDOWN_MS
            LinkOutcome.ERROR, LinkOutcome.TIMEOUT, LinkOutcome.SHUTDOWN -> P2pConstants.RECONNECT_BACKOFF_MS
        }
    }
}
