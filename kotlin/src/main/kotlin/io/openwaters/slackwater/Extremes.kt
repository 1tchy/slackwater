package io.openwaters.slackwater

import java.time.Instant
import kotlin.math.*

internal data class RawExtreme(val hour: Double, val time: Instant, val level: Double, val high: Boolean)
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

// Bracket/context/filter behavior: swift/Sources/SlackwaterKit/Extremes.swift.
internal fun findExtremes(fromHour: Double, toHour: Double, provider: ParamProvider, prominenceThreshold: Double): List<RawExtreme> {
    var params = provider.params(max(0.0, fromHour))
    var generation = provider.generation
    val maxSpeed = params.maxOfOrNull { it.speedRadiansPerHour } ?: return emptyList()
    if (maxSpeed <= 0) return emptyList()
    val bracket = PI / (2 * maxSpeed)
    // shortcut: fixed context can miss a longer sub-threshold run; extend it if fixtures expose one.
    val context = ceil(25 / bracket) * bracket
    val searchTo = toHour + context
    val found = mutableListOf<RawExtreme>()
    var previous = fromHour - context
    var dPrevious = evalHPrime(previous, params)
    var next = previous + bracket
    while (next <= searchTo + bracket) {
        val fresh = provider.params(previous)
        if (provider.generation != generation) {
            params = fresh; generation = provider.generation
            dPrevious = evalHPrime(previous, params)
        }
        val bound = min(next, searchTo)
        val derivative = evalHPrime(bound, params)
        if (dPrevious != 0.0 && derivative != 0.0 && (dPrevious > 0) != (derivative > 0)) {
            val root = bisectZero(previous, bound, dPrevious, params, ::evalHPrime)
            found += RawExtreme(root, shifted(provider.start, root * 3600), evalH(root, params), evalHDoublePrime(root, params) < 0)
        }
        if (bound >= searchTo) break
        previous = bound; dPrevious = derivative; next += bracket
    }
    return filterExtremes(found, prominenceThreshold).filter { it.hour >= fromHour && it.hour <= toHour }
}

internal fun filterExtremes(extremes: List<RawExtreme>, prominenceThreshold: Double): List<RawExtreme> {
    val kept = extremes.toMutableList()
    // shortcut: quadratic rescans; use a heap if long sub-threshold spans become slow.
    while (kept.size > 3) {
        val worst = (1 until kept.size - 2).minBy { abs(kept[it + 1].level - kept[it].level) }
        if (abs(kept[worst + 1].level - kept[worst].level) >= prominenceThreshold) break
        val count = if (kept[worst].high == kept[worst + 1].high) 1 else 2
        repeat(count) { kept.removeAt(worst) }
    }
    return kept
}
