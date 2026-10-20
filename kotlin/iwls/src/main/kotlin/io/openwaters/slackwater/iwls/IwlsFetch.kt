package io.openwaters.slackwater.iwls

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.*

/** The rules of docs/iwls-fetch.md that need no network. */
object IwlsFetch {
    const val BASE = "https://api-iwls.dfo-mpo.gc.ca/api/v1"

    /** The start of `now`'s calendar day in `zone`: the first midnight of a repeated one, or the first instant of a day that skips it. */
    fun localMidnight(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    /** Newest first, on the absolute 7-day epoch grid, spanning at least `days` and less than `days + 7`. */
    fun chunkPlan(days: Double, end: Instant): List<ChsChunk> {
        require(days.isFinite() && days > 0) { "chunkPlan needs a positive, finite duration" }
        val endSeconds = end.epochSecond + end.nano / 1e9
        val chunks = mutableListOf<ChsChunk>()
        var t = floor((endSeconds - days * 86_400) / WEEK_SECONDS).toLong() * WEEK_SECONDS
        while (t < endSeconds) {
            val next = t + WEEK_SECONDS
            chunks += ChsChunk(Instant.ofEpochSecond(t), if (next < endSeconds) Instant.ofEpochSecond(next) else end)
            t = next
        }
        return chunks.reversed()
    }

    const val RESOLVE_TOLERANCE_KM = 3.0

    /** The nearest station serving `series`, by position; names and codes are display metadata. */
    fun resolve(name: String, latitude: Double, longitude: Double, series: String, stations: List<IwlsStation>): IwlsStation {
        var best: IwlsStation? = null
        var bestKm = Double.POSITIVE_INFINITY
        for (station in stations) {
            if (series !in station.timeSeries) continue
            val km = distanceKm(latitude, longitude, station.latitude, station.longitude)
            // Strictly nearer, so ties keep list order.
            if (km < bestKm) {
                best = station
                bestKm = km
            }
        }
        val found = best ?: throw IwlsException.Permanent("no IWLS station serves $series")
        if (bestKm > RESOLVE_TOLERANCE_KM) {
            throw IwlsException.Permanent(
                "$name: nearest $series station ${found.officialName} is ${String.format(Locale.ROOT, "%.1f", bestKm)} km away"
            )
        }
        return found
    }

    fun decodeStations(json: String): List<IwlsStation> = Json.parseToJsonElement(json).jsonArray.map { element ->
        val station = element.jsonObject
        IwlsStation(
            station.requireString("id"),
            station.requireString("officialName"),
            station.requireNumber("latitude"),
            station.requireNumber("longitude"),
            station.getValue("timeSeries").jsonArray.map { it.jsonObject.requireString("code") },
        )
    }

    private const val GRID_MILLIS = 900_000L

    // Whole seconds with a UTC designator or numeric offset, with or without its colon, as Swift's
    // ISO8601DateFormatter reads them. Fractional seconds are skipped, as the contract says.
    private val EVENT_DATE = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:?\d{2})""")

    internal fun parseEventDate(value: String): Long? {
        if (!EVENT_DATE.matches(value)) return null
        val withColon = if (value.endsWith("Z") || value[value.length - 3] == ':') value
            else value.dropLast(2) + ":" + value.takeLast(2)
        return try {
            OffsetDateTime.parse(withColon).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Samples in response order, dropping a timestamp only when it repeats the one just kept. */
    fun decode(json: String): List<ChsSample> {
        val samples = ArrayList<ChsSample>()
        for (element in Json.parseToJsonElement(json).jsonArray) {
            val row = element.jsonObject
            // Both fields are required even on a row whose date is skipped, as Swift's Codable decode requires them.
            val value = row.requireNumber("value")
            val t = parseEventDate(row.requireString("eventDate")) ?: continue
            if (samples.lastOrNull()?.tMillis != t) samples += ChsSample(t, value)
        }
        return samples
    }

    fun onGrid(samples: List<ChsSample>): List<ChsSample> = samples.filter { it.tMillis % GRID_MILLIS == 0L }

    fun decodeMetadata(json: String): IwlsMetadata {
        val metadata = Json.parseToJsonElement(json).jsonObject
        return IwlsMetadata(metadata.optionalNumber("floodDirection"), metadata.optionalNumber("ebbDirection"))
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6371.0088 * asin(min(1.0, sqrt(h)))
    }
}
