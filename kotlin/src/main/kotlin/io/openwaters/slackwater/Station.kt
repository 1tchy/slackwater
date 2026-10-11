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
    fun extremes(from: Instant, to: Instant): List<TideExtreme> = extremes(from, to, 0.01)
    internal fun extremes(from: Instant, to: Instant, prominenceThreshold: Double): List<TideExtreme> {
        if (to < from) return emptyList()
        val start = instant(kotlin.math.floor(epochSeconds(from) / 600) * 600)
        val end = kotlin.math.ceil(epochSeconds(to) / 600) * 600
        val endHour = (end - epochSeconds(start)) / 3600
        val p = ParamProvider(constituents, offsetMetres, start, endHour)
        return findExtremes(0.0, endHour, p, prominenceThreshold).map {
            TideExtreme(it.time, it.level, if (it.high) ExtremeKind.HIGH else ExtremeKind.LOW)
        }
    }
}
