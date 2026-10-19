# IWLS fetching contract

Version 1. Implementations conform to [`fixtures/iwls-fetch.json`](../fixtures/iwls-fetch.json), which contains synthetic stations and samples. `SlackwaterIWLS` is the Swift reference client. This contract covers fetching inputs for local CHS fitting; the prediction engines remain independent of runtime I/O.

## Resolve a station

Fetch `https://api-iwls.dfo-mpo.gc.ca/api/v1/stations`. Retain stations whose `timeSeries` includes the requested series (`wlp` for tides, `wcsp1` for current speed). Choose the nearest by spherical haversine distance with radius 6371.0088 km, retaining list order on ties. Accept a distance of at most 3 km; fail permanently when there is no candidate or the nearest is farther away. Names and station codes are display metadata. Every subsequent request uses the resolved `id`, encoded as one URL path component.

The application supplies valid geographic coordinates. Resolving a current station by `wcsp1` does not guarantee `wcdp1` or flood/ebb metadata exists; the consumer validates those inputs before fitting.

## Plan the window

The caller supplies the current instant and the station's IANA time zone. Find the start of that local calendar day. The device's time zone does not determine the fit end. Use an up-to-date time-zone database; DST days can contain 23 or 25 elapsed hours.

For a nominal positive, finite duration `days`, an end instant `end` in epoch seconds, and `week = 604800`:

1. Set `t = floor((end - days * 86400) / week) * week`.
2. While `t < end`, emit `[t, min(t + week, end)]` and advance `t` by `week`.
3. Return the chunks in reverse order, newest first.

Here `days` denotes an elapsed fit duration. Calendar arithmetic determines the midnight anchor; fixed seconds determine the absolute cache grid. Floor rounds toward negative infinity, including before 1970. A plan spans at least N and less than N + 7 elapsed days. It has no zero-length chunk when the end falls on the grid. The newest portion of a 210-day plan equals the 60-day plan at the same end. Consumers concatenate the complete chunks, including the oldest chunk's extra samples.

Each request spans at most seven elapsed days. Full chunks have stable identities `(resolvedID, series, startEpochSeconds)`. Only chunks with a full 604800-second duration are cacheable, and only when they decoded at least one on-grid sample, so an empty or wholly skipped response is fetched again rather than kept as a missing week. A truncated but non-empty response is not detected. Partial chunks must be fetched again when their end changes. Storage, eviction, backup exclusion, and resume UI belong to the application. The iOS adapter caches the station list for seven elapsed days and falls back to a stale decoded list after a fetch failure.

## Request and decode samples

Use `GET /stations/{resolvedID}/data` with query parameters:

- `time-series-code`: one of the fit series `wlp`, `wcsp1`, or `wcdp1`. Reject any other code: the grid filter below would empty event series such as `wlp-hilo`.
- `from`, `to`: the chunk's UTC ISO 8601 endpoints, with second precision and `Z` suffix.
- `resolution=FIFTEEN_MINUTES`.

Both endpoints are inclusive. Decode the response array's `eventDate` and numeric `value` fields, keeping response order. Reject malformed JSON or missing required fields. Accept ISO 8601 date-times with whole seconds and a UTC designator or numeric offset; skip unparseable dates and fractional-second strings, including `.000Z`. Remove a timestamp only when it equals the last successfully appended timestamp in that response; retain its first value. This is adjacent suppression, not a global deduplication or a sort.

Represent a decoded sample as `{t, v}`, where `t` is Unix epoch milliseconds and `v` is the provider value without unit conversion. Tide heights are in meters, current speeds in knots, and directions in degrees. The consumer projects speed and direction before fitting.

Keep only samples with `t % 900000 == 0`, even when the provider was asked for 15-minute resolution. Apply the same filter to cache hits, including caches written at native one-minute resolution. The raw decoder is available separately for recording validation.

Concatenate responses without deduplicating their shared endpoints. Sort the accumulated tide samples by timestamp before fitting. Equal timestamps from separate chunks retain their weight in the fitter.

For current axes, use `GET /stations/{resolvedID}/metadata` and decode optional `floodDirection` and `ebbDirection`. The fitting consumer decides whether missing axes make the job unusable.

## Pace, retry, and cancel

All client instances in a process share a pacer. Reserve request starts at least 2.5 seconds apart on a monotonic clock, including station-list, metadata, and retry attempts, so a wall-clock change cannot stretch the gap. A serial download queue is application policy; pacing alone does not limit in-flight requests. The default Swift URLSession waits for connectivity on Apple platforms and has a 300-second resource timeout. While it waits, an offline device or one denied cellular data fails each attempt as a timeout after 300 seconds rather than at once, and timeouts are retried, so a request can hold the queue for about 20 minutes before it fails. The resource timeout covers the whole transfer, so a link too slow to finish a response in 300 seconds fails the same way. Callers that supply a session own its configuration.

Try each request at most four times, including its first attempt. `attempt` in the fixture counts the attempt that just failed, starting at one. On a retry:

- HTTP 429: wait `max(60, Retry-After)` seconds.
- HTTP 500–599 except 501, 505, and 511: wait `max(2^attempt, Retry-After)` seconds.
- Transient transport failures: wait `2^attempt` seconds, giving 2, 4, and 8 seconds.
- Other outcomes: do not retry within the client.

`Retry-After` is interpreted as numeric seconds; absent, nonnumeric, negative, and non-finite values use the default delay. A value over 600 seconds ends the request without a retry, as a transient failure, so the client neither retries inside the window the server named nor holds the queue for it. Transient failures are timeout, connection failure, host/DNS lookup failure, lost connection, offline, unavailable resource, invalid server response, and empty response. The Swift mapping is `URLError.timedOut`, `cannotConnectToHost`, `cannotFindHost`, `dnsLookupFailed`, `networkConnectionLost`, `notConnectedToInternet`, `resourceUnavailable`, `badServerResponse`, and `zeroByteResource`, plus native POSIX `ECONNABORTED`, `ECONNRESET`, and `ETIMEDOUT`. Use each platform's errno constants; Darwin and Linux assign different numbers.

Only HTTP 200 supplies a successful body. Do not retry body-decoding errors. Cancellation, TLS failures, and policy denials such as disallowed cellular data propagate immediately when the session reports them; the default session's wait above reports them as timeouts. Pacing and backoff waits are cancellable. After the last attempt, HTTP 400–499 except 429 is permanent; other HTTP failures, including an exhausted rate limit, remain transient for the application's queue. The queue owns any later retry schedule.

## Scope and licensing

`SlackwaterIWLS`, this spec, and the synthetic fixtures are MIT-licensed. The Swift client is extracted from the copyright holder's iOS implementation and relicensed with their authorization. The GPL iOS application keeps its own license; the MIT module has no dependency on it or on `SlackwaterKit`.

The client fetches directly for the user's own device. It does not authorize redistribution of CHS predictions. Keep it out of `@slackwater/api` and the CLI's prediction-serving paths. The contract fixtures contain no fetched CHS observations or predictions. The Kotlin engine and TypeScript prediction engine have no dependency on this networking module; their client adapters can use the same fixture corpus.

## Verification

Run `swift test -c release --filter SlackwaterIWLSTests` from the repository root. The tests consume the fixture directly and check the HTTP loop with synthetic responses, without contacting IWLS. Contract changes update the spec, fixture, and every implementing client's tests together. A fixture version change denotes a change to the fetching contract, independently of the harmonic fit recipe.
