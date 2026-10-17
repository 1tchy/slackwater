package io.openwaters.slackwater

import java.time.Instant
import kotlin.math.abs
import kotlin.test.*

class ExtremesTest {
    @Test fun extremesMatchVictoria() {
        val f = fixture("extremes-victoria")
        val actual = Station(fixture("prediction-victoria").constituents()).extremes(f.instant("start"), f.instant("end"))
        assertEquals(f.array("extremes").size, actual.size)
        for ((a, item) in actual.zip(f.array("extremes"))) {
            val e = item.obj()
            assertEquals(e.string("kind"), a.kind.name.lowercase())
            assertTrue(abs(secondsBetween(a.time, e.instant("time"))) < 60)
            assertTrue(abs(a.heightMetres - e.number("height")) < 0.02)
        }
    }
    @Test fun analyticTurnsAndConstantStations() {
        val t = Instant.parse("2026-01-01T00:00:00Z")
        val s = Station(listOf(HarmonicConstituent("S2", 1.0, 90.0)))
        val events = s.extremes(t, t.plusSeconds(86400))
        assertEquals(listOf(3.0, 9.0, 15.0, 21.0), events.map { kotlin.math.round(secondsBetween(t, it.time) / 3600) })
        for ((i, e) in events.withIndex()) {
            assertTrue(abs(secondsBetween(t.plusSeconds((3L + i * 6) * 3600), e.time)) < 1)
            assertEquals(if (i % 2 == 0) ExtremeKind.HIGH else ExtremeKind.LOW, e.kind)
        }
        for (station in listOf(Station(emptyList()), Station(emptyList(), 2.0), Station(listOf(HarmonicConstituent("unknown", 1.0, 0.0))))) {
            assertTrue(station.extremes(t, t.plusSeconds(86400)).isEmpty())
        }
        assertTrue(s.extremes(t.plusSeconds(1), t).isEmpty())
    }
    private fun raw(hour: Double, level: Double, high: Boolean) = RawExtreme(hour, Instant.EPOCH.plusSeconds((hour * 3600).toLong()), level, high)
    @Test fun prominenceRemovesSmallestPairsAndDuplicates() {
        val input = listOf(raw(0.0, 0.0, false), raw(6.0, 1.002, true), raw(7.0, 0.995, false), raw(8.0, 1.0, true), raw(14.0, 0.0, false))
        assertEquals(listOf(0.0, 1.002, 0.0), filterExtremes(input, 0.01).map { it.level })
        assertEquals(input, filterExtremes(input, 0.0))
        val duplicate = listOf(raw(0.0, -0.4, false), raw(7.6, 0.03, true), raw(7.6005, 0.03, true), raw(10.4, -0.008, false))
        assertEquals(listOf(false, true, false), filterExtremes(duplicate, 0.01).map { it.high })
        assertEquals(input.take(3), filterExtremes(input.take(3), 1.0))
    }
    @Test fun contextKeepsBoundaryEventsConsistent() {
        val s = Station(listOf(HarmonicConstituent("M2", 1.0, 0.0), HarmonicConstituent("M4", 0.29, 180.5), HarmonicConstituent("M8", 0.001, 0.0)))
        val t = Instant.parse("2025-01-01T00:00:00Z")
        val long = s.extremes(t.minusSeconds(86400), t.plusSeconds(2 * 86400))
        for ((begin, end) in listOf(1L to 10L, 2L to 10L, 6L to 13L, 6L to 14L)) {
            val from = t.plusSeconds(begin * 3600)
            val to = t.plusSeconds(end * 3600)
            val short = s.extremes(from, to)
            val cropped = long.filter { it.time >= from && it.time <= to }
            assertEquals(cropped.map { it.kind }, short.map { it.kind })
            for ((a, b) in short.zip(cropped)) {
                assertTrue(abs(secondsBetween(a.time, b.time)) < 5)
                assertTrue(abs(a.heightMetres - b.heightMetres) < 1e-4)
            }
        }
    }
}
