package com.adriennezoo.companion.replay

import java.time.Instant

/**
 * Shared UTC clock contract used by P1 (pack timing validation & metadata)
 * and P3 (synchronized historical replay engine).
 *
 * All calculations are strictly UTC epoch milliseconds and never depend on
 * local timezones, daylight saving rules, or installation timestamps.
 */
interface ReplayClock {
    fun nowUtcMillis(): Long
}

/**
 * Production clock backed by UTC system epoch time.
 */
object SystemReplayClock : ReplayClock {
    override fun nowUtcMillis(): Long = System.currentTimeMillis()
}

/**
 * Deterministic clock for automated replay testing and preview tooling.
 */
class FixedReplayClock(
    private var fixedUtcMillis: Long
) : ReplayClock {
    override fun nowUtcMillis(): Long = fixedUtcMillis

    fun setNowUtcMillis(utcMillis: Long) {
        fixedUtcMillis = utcMillis
    }

    fun advanceByMillis(deltaMillis: Long) {
        fixedUtcMillis += deltaMillis
    }
}

enum class ReplayPhase {
    LEGACY_UNSYNCHRONIZED,
    PRE_START,
    ACTIVE,
    END_OF_DATA
}

data class ReplayWindowStatus(
    val phase: ReplayPhase,
    val elapsedMillis: Long,
    val durationMillis: Long
)

object ReplayTimeContract {
    /**
     * Parses an ISO-8601 UTC timestamp string (e.g. "2026-11-12T00:00:00Z") or numeric epoch millis
     * into UTC epoch milliseconds. Returns null if invalid or non-positive.
     */
    fun parseUtcTimestampMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { millis ->
            return if (millis > 0L) millis else null
        }
        return try {
            val instant = Instant.parse(trimmed)
            val millis = instant.toEpochMilli()
            if (millis > 0L) millis else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Calculates non-negative elapsed milliseconds since [replayStartAtUtcMillis].
     * Returns 0 if [nowUtcMillis] is before [replayStartAtUtcMillis].
     */
    fun elapsedMillis(replayStartAtUtcMillis: Long, nowUtcMillis: Long): Long {
        return maxOf(0L, nowUtcMillis - replayStartAtUtcMillis)
    }

    /**
     * Resolves the high-level replay window phase for a pack/timeline given its
     * [replayStartAtUtcMillis], [timelineDurationMillis], and current [clock] instant.
     */
    fun resolveWindowStatus(
        replayStartAtUtcMillis: Long?,
        timelineDurationMillis: Long,
        clock: ReplayClock = SystemReplayClock
    ): ReplayWindowStatus {
        if (replayStartAtUtcMillis == null) {
            return ReplayWindowStatus(
                phase = ReplayPhase.LEGACY_UNSYNCHRONIZED,
                elapsedMillis = 0L,
                durationMillis = maxOf(0L, timelineDurationMillis)
            )
        }
        val now = clock.nowUtcMillis()
        if (now < replayStartAtUtcMillis) {
            return ReplayWindowStatus(
                phase = ReplayPhase.PRE_START,
                elapsedMillis = 0L,
                durationMillis = maxOf(0L, timelineDurationMillis)
            )
        }
        val elapsed = elapsedMillis(replayStartAtUtcMillis, now)
        val safeDuration = maxOf(0L, timelineDurationMillis)
        val phase = if (safeDuration in 1L..elapsed) {
            ReplayPhase.END_OF_DATA
        } else {
            ReplayPhase.ACTIVE
        }
        return ReplayWindowStatus(
            phase = phase,
            elapsedMillis = elapsed,
            durationMillis = safeDuration
        )
    }
}
