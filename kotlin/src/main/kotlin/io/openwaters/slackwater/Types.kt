package io.openwaters.slackwater

import java.time.Instant

/** Amplitude is meters for tides or knots for currents; phase is Greenwich degrees. */
data class HarmonicConstituent(val name: String, val amplitude: Double, val phaseDegrees: Double) {
    init { require(amplitude.isFinite() && phaseDegrees.isFinite()) { "Constituent constants must be finite" } }
}
data class TidePoint(val time: Instant, val heightMetres: Double)
data class TideRatePoint(val time: Instant, val rateMetresPerHour: Double)
