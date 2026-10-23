package io.openwaters.slackwater

import kotlin.test.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

class ChsCurrentFitTest {
    private fun samples(rows: JsonArray) = rows.map { HarmonicSample(it.obj().instant("time"), it.obj().number("value")) }

    @Test fun matchesTheSharedRecipe() {
        val recipe = fixture("chs-current-fit")
        assertEquals(recipe.string("speedSeries"), ChsCurrentFit.SPEED_SERIES)
        assertEquals(recipe.string("directionSeries"), ChsCurrentFit.DIRECTION_SERIES)
        assertEquals(recipe.array("basis").map { it.jsonPrimitive.content }, ChsCurrentFit.basis)
        assertEquals(recipe.array("days").map { it.jsonPrimitive.content.toInt() }, ChsCurrentFit.days)
        assertEquals(recipe.number("sampleMinutes").toInt(), ChsCurrentFit.SAMPLE_MINUTES)
        assertEquals(recipe.number("provisionalSlackMaxMinutes").toInt(), ChsCurrentFit.PROVISIONAL_SLACK_MAX_MINUTES)
    }

    @Test fun dropsNonFiniteSamples() {
        val t = { i: Int -> java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(900L * i) }
        val speeds = listOf(Double.NaN, 2.0, 1.0).mapIndexed { i, v -> HarmonicSample(t(i), v) }
        val directions = listOf(135.0, Double.NaN, 135.0).mapIndexed { i, v -> HarmonicSample(t(i), v) }
        assertEquals(listOf(HarmonicSample(t(2), 1.0)), ChsCurrentFit.project(speeds, directions, 135.0))
    }

    @Test fun projectsOntoTheFloodAxis() {
        for (item in fixture("chs-current-fit").array("projections")) {
            val c = item.obj()
            val projected = ChsCurrentFit.project(samples(c.array("speeds")), samples(c.array("directions")), c.number("floodDirection"))
            val expected = samples(c.array("expected"))
            assertEquals(expected.map { it.time }, projected.map { it.time }, c.string("note"))
            projected.zip(expected).forEach { (p, e) -> assertEquals(e.value, p.value, 1e-12, c.string("note")) }
        }
    }

    @Test fun carriesTheGoldenStamp() {
        assertEquals(fixture("chs-current-golden").string("stamp"), ChsCurrentFit.STAMP)
    }

    @Test fun predictsTheGoldenSlacksFromTheReferenceFit() {
        val golden = fixture("chs-current-golden")
        val events = golden.getValue("events").obj()
        val from = events.instant("start")
        val to = events.instant("end")
        val flood = golden.getValue("series").obj().number("floodDirection")
        for (item in golden.array("windows")) {
            val window = item.obj()
            val station = CurrentStation(window.constituents(), flood, (flood + 180) % 360, window.number("offset"))
            val slacks = station.slacks(from, to).map { it.time }.filter { it >= from && it < to }
            val expected = window.array("slacks").map { java.time.Instant.parse(it.jsonPrimitive.content) }
            val label = "${window.number("days").toInt()} d"
            assertEquals(expected.size, slacks.size, label)
            slacks.zip(expected).forEach { (a, e) -> assertTrue(kotlin.math.abs(secondsBetween(a, e)) < 1.0, "$label slack near $e") }
        }
    }
}
