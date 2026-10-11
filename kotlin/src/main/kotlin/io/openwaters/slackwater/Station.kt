package io.openwaters.slackwater

import java.time.Instant

/** Offline harmonic tide station; the additive offset is in meters in the caller's datum. */
class Station(constituents: List<HarmonicConstituent>, val offsetMetres: Double = 0.0) {
    internal val constituents = constituents.toList()
    init { require(offsetMetres.isFinite()) { "Offset must be finite" } }

    /** Includes the start floored and end ceiled to stepSeconds. */
    fun heights(from: Instant, to: Instant, stepSeconds: Double = 600.0): List<TidePoint> {
        val timeline = makeTimeline(from, to, stepSeconds)
        if (timeline.items.isEmpty()) return emptyList()
        val p = ParamProvider(constituents, offsetMetres, timeline.start, timeline.endHour)
        return timeline.items.zip(timeline.hours).map { (time, hour) -> TidePoint(time, evalH(hour, p.params(hour))) }
    }
    /** Analytic derivative in meters/hour, on the height timeline. */
    fun rates(from: Instant, to: Instant, stepSeconds: Double = 600.0): List<TideRatePoint> {
        val timeline = makeTimeline(from, to, stepSeconds)
        if (timeline.items.isEmpty()) return emptyList()
        val p = ParamProvider(constituents, offsetMetres, timeline.start, timeline.endHour)
        return timeline.items.zip(timeline.hours).map { (time, hour) -> TideRatePoint(time, evalHPrime(hour, p.params(hour))) }
    }
}
