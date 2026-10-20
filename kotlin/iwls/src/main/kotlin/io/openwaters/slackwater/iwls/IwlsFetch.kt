package io.openwaters.slackwater.iwls

import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor

/** The rules of docs/iwls-fetch.md that need no network. */
object IwlsFetch {
    const val BASE = "https://api-iwls.dfo-mpo.gc.ca/api/v1"

    /** The start of `now`'s calendar day in `zone`: the first midnight of a repeated one, or the first instant of a day that skips it. */
    fun localMidnight(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    /** Newest first, on the absolute 7-day epoch grid, spanning at least `days` and less than `days + 7`. */
    fun chunkPlan(days: Double, end: Instant): List<ChsChunk> {
        require(days.isFinite() && days > 0) { "chunkPlan needs a positive, finite duration" }
        val endSeconds = end.epochSecond + end.nano / 1e9
        val chunks = mutableListOf<ChsChunk>()
        var t = floor((endSeconds - days * 86_400) / WEEK_SECONDS).toLong() * WEEK_SECONDS
        while (t < endSeconds) {
            val next = t + WEEK_SECONDS
            chunks += ChsChunk(Instant.ofEpochSecond(t), if (next < endSeconds) Instant.ofEpochSecond(next) else end)
            t = next
        }
        return chunks.reversed()
    }
}
