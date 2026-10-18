# SlackwaterIWLS

An MIT Swift client for the [IWLS fetching contract](../../../docs/iwls-fetch.md). Add the `SlackwaterIWLS` product from the root Swift package. It is independent of the `SlackwaterKit` prediction engine and supports the package's macOS 13, iOS 16, and watchOS 9 floors, plus FoundationNetworking on Linux.

```swift
import Foundation
import SlackwaterIWLS

let client = IwlsClient()
let stations = try await client.stationList()
let station = try IwlsFetch.resolve(
    name: "Requested place", latitude: 48.42, longitude: -123.37,
    series: "wlp", in: stations)
let end = IwlsFetch.localMidnight(
    now: Date(), timeZone: TimeZone(identifier: "America/Vancouver")!)
var samples: [ChsSample] = []
for chunk in IwlsFetch.chunkPlan(days: 60, end: end) {
    samples += try await client.series("wlp", stationID: station.id, chunk: chunk)
}
samples.sort { $0.t < $1.t }
```

The caller owns cache storage, queueing, and fitting. Use `ChsChunk.isCacheable` for full-week chunks and `IwlsFetch.onGrid` when loading cached samples. Preserve repeated timestamps when concatenating responses. `IwlsClient.metadata(stationID:)` retrieves current flood/ebb axes. The optional request observer receives elapsed seconds for each attempt, including pacing, so an application can track download estimates. A supplied `URLSession` owns its connectivity and timeout settings.

This client is for direct fetching on a user's device. It grants no right to redistribute CHS predictions through an API or CLI. The fixtures use synthetic values. See [LICENSE](LICENSE) for the module's MIT license and [the contract](../../../docs/iwls-fetch.md) for decoding, retries, and compatibility tests.
