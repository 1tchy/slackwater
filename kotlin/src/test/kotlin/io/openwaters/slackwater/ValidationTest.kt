package io.openwaters.slackwater

import java.time.Instant
import kotlin.test.*

class ValidationTest {
    private val t = Instant.parse("2026-01-01T00:00:00Z")
    @Test fun invalidStepsAndOverflowFail() {
        val s = Station(emptyList())
        for (step in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1e-12)) {
            assertFailsWith<IllegalArgumentException> { s.heights(t, t.plusSeconds(1), step) }
        }
        assertFailsWith<IllegalArgumentException> { s.heights(Instant.MIN, Instant.MAX) }
        assertFailsWith<IllegalArgumentException> { s.heights(Instant.EPOCH, Instant.EPOCH.plusSeconds(Int.MAX_VALUE.toLong() + 1), 1.0) }
    }
    @Test fun nonfiniteConstantsFail() {
        for (v in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { HarmonicConstituent("M2", v, 0.0) }
            assertFailsWith<IllegalArgumentException> { HarmonicConstituent("M2", 1.0, v) }
            assertFailsWith<IllegalArgumentException> { Station(emptyList(), v) }
        }
    }
    @Test fun reversedAndEqualWindows() {
        val s = Station(emptyList(), 1.0)
        assertTrue(s.heights(t.plusNanos(1), t).isEmpty())
        assertTrue(s.rates(t.plusSeconds(1), t).isEmpty())
        assertEquals(1, s.heights(t, t).size)
        assertEquals(2, s.heights(t.plusSeconds(1), t.plusSeconds(1)).size)
    }
}
