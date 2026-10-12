# Slackwater for Kotlin

An offline tide and current engine for Kotlin/JVM and Android. Callers supply station constants; the engine does no file or network I/O. Runtime dependencies are Kotlin's standard library and its transitive annotations artifact. The constituent catalog is compiled into the library from the same source as the TypeScript and Swift catalogs.

## Use from source

Builds use JDK 17, Kotlin 2.2.0 and the checked-in Gradle 8.14.3 wrapper. The library targets JVM 8 bytecode and uses `java.time.Instant`; Android consumers require `minSdk = 26` because [Instant was added in API 26](https://developer.android.com/reference/java/time/Instant). Android device execution is pending consumer integration testing.

This is a source distribution. The dependency below resolves through a composite build; it is not published to Maven Central. Check out the repository at a reviewed full commit SHA and keep that SHA in your application's dependency setup:

```sh
git clone https://github.com/openwatersio/slackwater.git vendor/slackwater
git -C vendor/slackwater checkout --detach <full-reviewed-commit-sha>
```

In your application's `settings.gradle.kts`:

```kotlin
includeBuild("vendor/slackwater/kotlin")
```

In its `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.openwaters:slackwater-engine:0.1.0")
}
```

See [smoke](smoke) for a complete separate consumer. Prediction needs no station database, JSON parser, Android framework or bundled asset.

## Predict

```kotlin
import io.openwaters.slackwater.*
import java.time.Instant

val from = Instant.parse("2026-06-01T00:00:00Z")
val to = from.plusSeconds(2 * 86400)
val constants = listOf(HarmonicConstituent("M2", 1.0, 0.0))
val tide = Station(constants, offsetMetres = 0.5)
val heights: List<TidePoint> = tide.heights(from, to)
val rates: List<TideRatePoint> = tide.rates(from, to)
val highLow: List<TideExtreme> = tide.extremes(from, to)

val current = CurrentStation(constants, floodDirectionDegrees = 90.0, ebbDirectionDegrees = 270.0)
val speeds: List<CurrentPoint> = current.speeds(from, to)
val events: List<CurrentEvent> = current.events(from, to)
val slacks = current.slacks(from, to)
val maxima = current.maxima(from, to)
val astronomy: ConstituentAstronomy? = constituentAstronomy("M2", from)
```

The constants here illustrate the API, not a real station. Tide amplitudes, `offsetMetres` and heights are metres; rates are metres per hour. Current amplitudes, `offsetKnots` and speeds are signed knots, positive flood and negative ebb. Phases are GMT degrees. Directions are degrees true, normalized to `[0, 360)`; null means unpublished, and slacks have no direction.

All numeric inputs must be finite. Steps must be positive, representable at the requested epoch and at least one nanosecond. Timeline and event searches exceeding the indexed work/result range are rejected. Invalid inputs throw `IllegalArgumentException`. Reversed windows return empty lists. Equal timeline bounds produce the samples from outward snapping; event roots on an exact boundary are not guaranteed. Steps default to 600 seconds; the start is floored and the end ceiled to the step on the UTC epoch grid. No default time zone enters prediction. Station constructors copy caller collections, and requests have independent calculation state.

Unknown names and zero amplitudes contribute nothing. Astronomy returns null for an unknown name or a catalog entry without a complete equilibrium definition. IHO node corrections are supported. Schureman, fitting, station lookup, datum resolution, ranking, derived slack and publication tooling are outside this port.

## Subordinates and datums

```kotlin
val subordinateTide = SubordinateTideStation(
    tide, highTimeOffsetSeconds = 120.0, lowTimeOffsetSeconds = -60.0,
    height = HeightOffset.Ratio(high = 1.1, low = 0.9)
)
// Or HeightOffset.Fixed(high = 0.2, low = -0.1), in metres.
val subordinateCurrent = SubordinateCurrentStation(
    current, slackBeforeFloodOffsetSeconds = 60.0, slackBeforeEbbOffsetSeconds = -60.0,
    floodTimeOffsetSeconds = 120.0, ebbTimeOffsetSeconds = -120.0,
    floodSpeedRatio = 1.1, ebbSpeedRatio = 0.9
)
```

The harmonic sum is relative to mean sea level; `Station.offsetMetres` adds a caller-resolved datum offset. Ratio tide reductions require the reference in chart datum. Apply any final chart-to-requested-datum difference after reduction. Ratios must be nonnegative; fixed corrections can be negative. All subordinate time offsets are seconds (convert database/NOAA minutes before construction).

Subordinate curves interpolate corrected events with a half cosine. Short requests expand their event context until corrected knots bracket both timeline boundaries, with the longest active constituent period bounding that expansion. An oscillating reference that still cannot supply surrounding knots causes an explicit error. Empty event tables from non-oscillating references retain the zero curve. These curves approximate the official event table rather than reconstruct local hydraulics. Corrected events are sorted; coincident knots use the last value. Current slack offsets follow the next maximum's phase, using an adaptive search up to the longest active constituent period when a short request lacks that maximum. A required phase that still cannot be resolved causes an explicit error; standalone table reduction retains the flood fallback. Current slacks are exactly zero after reduction.

Current events use independent whole-UTC-day searches, then crop to the exact requested bounds. TypeScript returns the covering UTC days instead, so its parity fixture can extend beyond its nominal end. Tide harmonic extrema use an outward 600-second search grid, matching Swift; subordinate tide events crop after corrections.

## Develop and verify

From `kotlin/` with JDK 17:

```sh
./gradlew test jacocoTestReport jacocoTestCoverageVerification
./gradlew -p smoke run
```

JaCoCo enforces 100% handwritten line coverage, excluding only generated `CatalogDataKt`. Reports are in `build/reports/`. Tests read shared checked-in fixtures without network access: timelines within `1e-6` metres/knots, event count/kind exact and event differences below 60 seconds and 0.02 metres/knots. NOAA accuracy gates are in [the engine contract](../docs/CONTRACT.md).

From the repository root, `npm run fixtures:check` checks both generated catalogs and the shared oracle vectors. To regenerate the catalogs intentionally, run `node fixtures/generate/gen-catalog.mjs`. Keep generated Swift and Kotlin outputs together with their source changes. Existing fixture values must never be loosened to fit a port.

The engine is [MIT licensed](../LICENSE). Read the repository's [navigation warning](../README.md) before using predictions.
