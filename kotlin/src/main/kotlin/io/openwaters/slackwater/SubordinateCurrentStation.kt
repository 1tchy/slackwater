package io.openwaters.slackwater

import java.time.Instant
import kotlin.math.abs

/** NOAA event-table reduction; the sampled curve is a drawing between events. */
class SubordinateCurrentStation(
    private val reference: CurrentStation,
    val slackBeforeFloodOffsetSeconds: Double,
    val slackBeforeEbbOffsetSeconds: Double,
    val floodTimeOffsetSeconds: Double,
    val ebbTimeOffsetSeconds: Double,
    val floodSpeedRatio: Double,
    val ebbSpeedRatio: Double,
    floodDirectionDegrees: Double? = null,
    ebbDirectionDegrees: Double? = null
) {
    val floodDirectionDegrees = direction(floodDirectionDegrees)
    val ebbDirectionDegrees = direction(ebbDirectionDegrees)
    private val pad: Double
    init {
        val offsets = listOf(slackBeforeFloodOffsetSeconds, slackBeforeEbbOffsetSeconds, floodTimeOffsetSeconds, ebbTimeOffsetSeconds)
        require(offsets.all { it.isFinite() }) { "Time offsets must be finite" }
        require(floodSpeedRatio.isFinite() && ebbSpeedRatio.isFinite() && floodSpeedRatio >= 0 && ebbSpeedRatio >= 0) { "Speed ratios must be finite and nonnegative" }
        // Exact-window reference events need the next maximum to label a trailing slack's phase.
        pad = offsets.maxOf { abs(it) } + 8 * 3600
    }
    internal fun reduce(referenceEvents: List<CurrentEvent>): List<CurrentEvent> = referenceEvents.mapIndexed { index, e ->
        when (e.kind) {
            CurrentEventKind.MAX_FLOOD -> CurrentEvent(shifted(e.time, floodTimeOffsetSeconds), e.speedKnots * floodSpeedRatio, e.kind, floodDirectionDegrees)
            CurrentEventKind.MAX_EBB -> CurrentEvent(shifted(e.time, ebbTimeOffsetSeconds), e.speedKnots * ebbSpeedRatio, e.kind, ebbDirectionDegrees)
            CurrentEventKind.SLACK -> {
                val next = referenceEvents.drop(index + 1).firstOrNull { it.kind != CurrentEventKind.SLACK }
                val offset = if (next?.kind == CurrentEventKind.MAX_EBB) slackBeforeEbbOffsetSeconds else slackBeforeFloodOffsetSeconds
                CurrentEvent(shifted(e.time, offset), 0.0, e.kind)
            }
        }
    }.sortedBy { it.time }

    fun events(from: Instant, to: Instant): List<CurrentEvent> {
        if (to < from) return emptyList()
        return reduce(reference.events(shifted(from, -pad), shifted(to, pad))).filter { it.time >= from && it.time <= to }
    }
    fun speeds(from: Instant, to: Instant, stepSeconds: Double = 600.0): List<CurrentPoint> {
        val timeline = makeTimeline(from, to, stepSeconds)
        if (timeline.items.isEmpty()) return emptyList()
        val knots = events(shifted(timeline.items.first(), -8 * 3600.0), shifted(timeline.items.last(), 8 * 3600.0))
        return halfCosineCurve(knots.map { it.time to it.speedKnots }, timeline.items).map { CurrentPoint(it.time, it.value) }
    }
}
