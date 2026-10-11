package io.openwaters.slackwater

import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.test.*

internal fun checkCurrentAccuracy(station: JsonObject, events: List<CurrentEvent>, timeMinutes: Double, knots: Double, threshold: Double = 0.0): Int {
    var checked = 0
    var weak = 0
    for (item in station.array("events")) {
        val e = item.obj()
        if (e.string("kind") == "slack") continue
        if (abs(e.number("speed")) < threshold) { weak++; continue }
        val kind = currentKind(e.string("kind"))
        val match = assertNotNull(events.filter { it.kind == kind }.minByOrNull { abs(secondsBetween(it.time, e.instant("time"))) })
        assertTrue(abs(secondsBetween(match.time, e.instant("time"))) < timeMinutes * 60, "${e.string("kind")} ${e.string("time")}")
        assertTrue(abs(abs(match.speedKnots) - abs(e.number("speed"))) < knots)
        checked++
    }
    println("NOAA events: $checked compared, $weak weak extrema reported without strict timing")
    assertTrue(checked > 0)
    return checked
}
internal fun oracleSpan(station: JsonObject): Pair<java.time.Instant, java.time.Instant> {
    val times = station.array("events").map { it.obj().instant("time") }
    return times.min().minusSeconds(3600) to times.max().plusSeconds(3600)
}
class CurrentAccuracyTest {
    @Test fun harmonicCurrentNoaaAccuracy() {
        val h = fixture("currents-golden-harmonic")
        val (from, to) = oracleSpan(h)
        checkCurrentAccuracy(h, h.current().events(from, to), 20.0, 0.30)
    }
    @Test fun homePassesNoaaAccuracy() {
        val cases = fixture("currents-golden-home").array("stations")
        assertEquals(6, cases.size)
        for (item in cases) {
            val c = item.obj()
            val s = c.current()
            val (from, to) = oracleSpan(c)
            checkCurrentAccuracy(c, s.events(from, to), 20.0, 0.35, 0.75)
            for (row in c.array("events")) {
                val e = row.obj()
                if (e.string("kind") == "slack" || abs(e.number("speed")) < 0.75) continue
                val t = e.instant("time")
                val speed = s.speeds(t.minusSeconds(30), t.plusSeconds(30), 60.0).first().speedKnots
                assertTrue(if (e.string("kind") == "maxFlood") speed > 0 else speed < 0)
            }
        }
    }
}
