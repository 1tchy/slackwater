package io.openwaters.slackwater.iwls

import java.time.Instant

internal const val WEEK_SECONDS = 604_800L

/** One IWLS sample: epoch milliseconds and the provider's value, unconverted. */
data class ChsSample(val tMillis: Long, val value: Double)

/** A request window; IWLS treats both ends as inclusive. */
data class ChsChunk(val start: Instant, val end: Instant) {
    /** A full week on the epoch grid, so its identity is the same on every day it is planned. */
    val isCacheable: Boolean
        get() = start.nano == 0 && Math.floorMod(start.epochSecond, WEEK_SECONDS) == 0L &&
            end == start.plusSeconds(WEEK_SECONDS)

    /** A full week's identity never changes, so an empty response kept under it would be a missing week for good. */
    fun canCache(samples: List<ChsSample>): Boolean = isCacheable && samples.isNotEmpty()
}

data class IwlsStation(
    val id: String,
    val officialName: String,
    val latitude: Double,
    val longitude: Double,
    val timeSeries: List<String>,
)

data class IwlsMetadata(val floodDirection: Double?, val ebbDirection: Double?)

/** Every failure the client reports, apart from cancellation; `cause` keeps the transport's or decoder's own error. */
sealed class IwlsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Another attempt would get the same answer. */
    class Permanent(message: String, cause: Throwable? = null) : IwlsException(message, cause)

    /** The request might succeed later; the application's queue decides when. */
    class Transient(message: String, cause: Throwable? = null) : IwlsException(message, cause)
}
