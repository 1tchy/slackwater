package io.openwaters.slackwater

import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.min
import kotlinx.serialization.json.*

internal fun fixture(name: String): JsonObject = Json.parseToJsonElement(
    File(System.getProperty("fixtures"), "$name.json").readText()
).jsonObject
internal fun JsonElement.obj() = jsonObject
internal fun JsonObject.array(key: String) = getValue(key).jsonArray
internal fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.instant(key: String) = Instant.parse(string(key))
internal fun angularDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return min(d, 360 - d)
}
