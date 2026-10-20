package io.openwaters.slackwater

import java.time.Instant
import kotlin.math.*

internal data class RawExtreme(val time: Instant, val level: Double, val high: Boolean)
internal fun checkEventCapacity(hours: Double, bracket: Double) {
    require(hours.isFinite() && hours / bracket + 2 <= Int.MAX_VALUE) { "Event search exceeds indexed range" }
}
internal fun bisectZero(a0: Double, b0: Double, fa0: Double, params: List<PreparedParam>, evaluate: (Double, List<PreparedParam>) -> Double): Double {
    var a = a0
    var b = b0
    var fa = fa0
    while (true) {
        val mid = (a + b) / 2
        if (b - a < 1.0 / 3600) return mid
        val f = evaluate(mid, params)
        if (f == 0.0) return mid
        if ((fa > 0) == (f > 0)) { a = mid; fa = f } else b = mid
    }
}

// Illinois interpolation matches packages/engine/src/harmonics/extremes.ts curve knots.
private fun derivativeRoot(a0: Double, b0: Double, fa0: Double, fb0: Double, params: List<PreparedParam>): Double {
    var a = a0; var b = b0; var fa = fa0; var fb = fb0
    var kept = 0
    while (true) {
        val t = (a * fb - b * fa) / (fb - fa)
        if (b - a < 1.0 / 3600) return t
        val ft = evalHPrime(t, params)
        if (ft == 0.0) return t
        if ((fa > 0) == (ft > 0)) {
            a = t; fa = ft
            if (kept == -1) fb /= 2
            kept = -1
        } else {
            b = t; fb = ft
            if (kept == 1) fa /= 2
            kept = 1
        }
    }
}

// The furthest the filter looks from an extreme for what decides it: past the fortnightly
// (MSf, 14.8 d) and monthly (MM, 27.6 d) cycles that set a sub-threshold tide's extremes.
internal const val MAX_CONTEXT_DAYS = 32L
// How far the search goes for an extreme's nearest turn, however far away: past a year, so a
// tide made of the annual and semiannual cycles alone still has its neighbours.
internal const val MAX_LOOKAHEAD_DAYS = 400L
private const val DAY_SECONDS = 86400L

// Extremes in [from, to], independent of the window (slackwater#436): turning points come from
// whole UTC days, each searched from its own midnight (`provider(day)`), and each extreme is kept
// by its persistence. Days past the window load only while an extreme is undecided. A threshold
// of zero keeps every turning point. Behaviour matches swift/Sources/SlackwaterKit/Extremes.swift.
internal fun findExtremes(from: Instant, to: Instant, prominenceThreshold: Double, provider: (Instant) -> ParamProvider): List<RawExtreme> {
    if (to < from) return emptyList()
    val firstDay = Math.floorDiv(from.epochSecond, DAY_SECONDS)
    val lastDay = Math.floorDiv(to.epochSecond, DAY_SECONDS)
    val bracket = turningBracket(provider(Instant.ofEpochSecond(firstDay * DAY_SECONDS))) ?: return emptyList()
    checkEventCapacity((lastDay - firstDay + 1 + 2 * MAX_LOOKAHEAD_DAYS) * 24.0, bracket)
    fun dayTurns(day: Long) = turningPoints(provider(Instant.ofEpochSecond(day * DAY_SECONDS)))
    // One sequence indexed from the first turn of firstDay: `after` holds index 0 onward,
    // `before` index -1 backward. Days load at either end as a scan reaches them.
    val after = mutableListOf<RawExtreme>()
    val before = mutableListOf<RawExtreme>()
    var nextDay = firstDay
    var prevDay = firstDay - 1
    val turn = fun(k: Int): RawExtreme? {
        if (k >= 0) {
            while (after.size <= k && nextDay <= lastDay + MAX_LOOKAHEAD_DAYS) after += dayTurns(nextDay++)
            return after.getOrNull(k)
        }
        while (before.size < -k && prevDay >= firstDay - MAX_LOOKAHEAD_DAYS) before += dayTurns(prevDay--).asReversed()
        return before.getOrNull(-k - 1)
    }
    val results = mutableListOf<RawExtreme>()
    var k = 0
    while (true) {
        val extreme = turn(k) ?: break
        if (extreme.time > to) break
        // A threshold of zero keeps every turning point, unfiltered.
        if (extreme.time >= from && (prominenceThreshold <= 0 || persists(turn, k, prominenceThreshold))) results += extreme
        k++
    }
    return results
}

private fun turningBracket(provider: ParamProvider): Double? {
    val maxSpeed = provider.params(0.0).maxOfOrNull { it.speedRadiansPerHour } ?: return null
    return if (maxSpeed > 0) PI / (2 * maxSpeed) else null
}

// Every root of h' in the day `provider` is anchored at. Node corrections refresh every 24 hours,
// so one set holds for the whole day. A root on a bracket boundary or on midnight is not guaranteed.
private fun turningPoints(provider: ParamProvider): List<RawExtreme> {
    val bracket = turningBracket(provider) ?: return emptyList()
    val params = provider.params(0.0)
    val found = mutableListOf<RawExtreme>()
    var previous = 0.0
    var dPrevious = evalHPrime(previous, params)
    var next = bracket
    while (true) {
        val bound = min(next, 24.0)
        val derivative = evalHPrime(bound, params)
        if (dPrevious != 0.0 && derivative != 0.0 && (dPrevious > 0) != (derivative > 0)) {
            val root = derivativeRoot(previous, bound, dPrevious, derivative, params)
            // A root at exactly midnight is not guaranteed (CONTRACT.md): the next day's first bracket opens on h' = 0.
            if (root < 24) found += RawExtreme(shifted(provider.start, root * 3600), evalH(root, params), evalHDoublePrime(root, params) < 0)
        }
        if (bound >= 24) break
        previous = bound; dPrevious = derivative; next += bracket
    }
    return found
}

// A high persists when, in each direction, the tide falls at least the threshold below it before
// rising above it; a low, mirrored. Away from a list's ends and exact ties, the same result as
// removing the closest pair below the threshold, smallest first, but decided from each extreme's
// own neighbourhood. An equal turn counts as
// higher on the left only, so one of two equal copies survives. The nearest turn always counts,
// however far; past it the scan stops MAX_CONTEXT_DAYS from the extreme, and undecided there or
// at the end of the sequence is dropped, which after a longer sub-threshold run can leave two of
// a kind in a row.
private fun persists(turn: (Int) -> RawExtreme?, k: Int, prominenceThreshold: Double): Boolean {
    val extreme = turn(k)!!
    val sign = if (extreme.high) 1.0 else -1.0
    val h = extreme.level * sign
    for (dir in intArrayOf(-1, 1)) {
        var decided = false
        var j = k + dir
        while (true) {
            val other = turn(j) ?: break
            // The nearest turn counts up to MAX_LOOKAHEAD_DAYS away; past it the scan stops MAX_CONTEXT_DAYS
            // from the extreme. Both are measured from the extreme, never the window.
            val limit = if (j == k + dir) MAX_LOOKAHEAD_DAYS else MAX_CONTEXT_DAYS
            if (abs(secondsBetweenInstants(extreme.time, other.time)) > limit * DAY_SECONDS) break
            val v = other.level * sign
            if (if (dir < 0) v >= h else v > h) break
            if (h - v >= prominenceThreshold) { decided = true; break }
            j += dir
        }
        if (!decided) return false
    }
    return true
}

private fun secondsBetweenInstants(a: Instant, b: Instant) = java.time.Duration.between(a, b).toNanos() / 1e9

internal fun filterExtremes(extremes: List<RawExtreme>, prominenceThreshold: Double): List<RawExtreme> {
    val turn = { k: Int -> extremes.getOrNull(k) }
    return extremes.indices.filter { persists(turn, it, prominenceThreshold) }.map { extremes[it] }
}
