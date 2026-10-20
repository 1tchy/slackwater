package io.openwaters.slackwater.iwls

import java.time.Instant

internal const val WEEK_SECONDS = 604_800L

/** One IWLS sample: epoch milliseconds and the provider's value, unconverted. */
data class ChsSample(val tMillis: Long, val value: Double)

/** A request window; IWLS treats both ends as inclusive. */
data class ChsChunk(val start: Instant, val end: Instant) {
    /** A full week on the epoch grid, so its identity is the same on every day it is planned. */
    val isCacheable: Boolean
        get() = end.epochSecond - start.epochSecond == WEEK_SECONDS && end.nano == start.nano

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

sealed class IwlsException(message: String) : Exception(message) {
    /** Another attempt would get the same answer. */
    class Permanent(message: String) : IwlsException(message)

    /** The request might succeed later; the application's queue decides when. */
    class Transient(message: String) : IwlsException(message)
}
