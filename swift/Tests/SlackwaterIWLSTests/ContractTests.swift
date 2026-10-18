import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif
import Testing
@testable import SlackwaterIWLS

private struct Fixture: Decodable {
    struct Resolution: Decodable {
        let name: String
        let latitude: Double
        let longitude: Double
        let series: String
        let stations: [IwlsStation]
        let expectedID: String?
    }
    struct Midnight: Decodable { let now: String; let timeZone: String; let expected: String }
    struct Plan: Decodable { let days: Double; let endSeconds: Double; let expected: [[Double]] }
    struct Decoding: Decodable { let raw: [IwlsSample]; let expected: [ChsSample]; let grid: [ChsSample] }
    struct Request: Decodable {
        let stationID: String; let series: String; let startSeconds: Double; let endSeconds: Double
        let path: String; let query: [String: String]
    }
    struct Retry: Decodable { let status: Int; let attempt: Int; let retryAfter: Double?; let delay: Double? }
    let version: Int
    let resolution: [Resolution]
    let midnights: [Midnight]
    let plans: [Plan]
    let decoding: [Decoding]
    let request: Request
    let retries: [Retry]
    let boundaryResponses: [[IwlsSample]]
    let boundaryExpected: [ChsSample]
}

private func fixture() throws -> Fixture {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("fixtures/iwls-fetch.json")
    return try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
}

@Test func resolvesByPositionAndSeries() throws {
    let f = try fixture()
    #expect(f.version == 1)
    for row in f.resolution {
        if let expected = row.expectedID {
            let station = try IwlsFetch.resolve(name: row.name, latitude: row.latitude,
                longitude: row.longitude, series: row.series, in: row.stations)
            #expect(station.id == expected)
        } else {
            #expect(throws: IwlsError.self) {
                try IwlsFetch.resolve(name: row.name, latitude: row.latitude,
                    longitude: row.longitude, series: row.series, in: row.stations)
            }
        }
    }
}

@Test func anchorsAtStationMidnightAcrossDST() throws {
    let iso = ISO8601DateFormatter()
    for row in try fixture().midnights {
        let now = try #require(iso.date(from: row.now))
        let tz = try #require(TimeZone(identifier: row.timeZone))
        #expect(IwlsFetch.localMidnight(now: now, timeZone: tz) == iso.date(from: row.expected))
    }
}

@Test func epochPlansHaveStableCacheIdentities() throws {
    for row in try fixture().plans {
        let plan = IwlsFetch.chunkPlan(days: row.days, end: Date(timeIntervalSince1970: row.endSeconds))
        #expect(plan.map { [$0.start.timeIntervalSince1970, $0.end.timeIntervalSince1970] } == row.expected)
    }
    let end = Date(timeIntervalSince1970: 1_785_000_000)
    let fast = IwlsFetch.chunkPlan(days: 60, end: end)
    let full = IwlsFetch.chunkPlan(days: 210, end: end)
    #expect(Array(full.prefix(fast.count)) == fast)
    let tomorrow = IwlsFetch.chunkPlan(days: 210, end: end.addingTimeInterval(86400))
    #expect(Set(full.filter(\.isCacheable)).intersection(tomorrow.filter(\.isCacheable)).count >= 28)
}

@Test func decodingAndCachedSamplesKeepTheFitGrid() throws {
    for row in try fixture().decoding {
        let decoded = try IwlsFetch.decode(JSONEncoder().encode(row.raw))
        #expect(decoded == row.expected)
        #expect(IwlsFetch.onGrid(decoded) == row.grid)
        #expect(IwlsFetch.onGrid(row.expected) == row.grid)
    }
    #expect(throws: (any Error).self) { try IwlsFetch.decode(Data("{}".utf8)) }
    #expect(throws: (any Error).self) { try IwlsFetch.decode(Data("[{\"eventDate\":\"1970-01-01T00:00:00Z\"}]".utf8)) }
    let f = try fixture()
    let combined = try f.boundaryResponses.flatMap { try IwlsFetch.decode(JSONEncoder().encode($0)) }
    #expect(combined == f.boundaryExpected)
}

@Test func requestUsesResolvedIDAndFifteenMinuteResolution() throws {
    let row = try fixture().request
    let url = try IwlsFetch.dataURL(row.series, stationID: row.stationID,
        chunk: ChsChunk(start: Date(timeIntervalSince1970: row.startSeconds), end: Date(timeIntervalSince1970: row.endSeconds)))
    let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    #expect(components.path == row.path)
    #expect(Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") }) == row.query)
}

@Test func retriesObeyTheSharedContract() throws {
    for row in try fixture().retries {
        #expect(IwlsFetch.retryDelay(after: .success(row.status), attempt: row.attempt, retryAfter: row.retryAfter) == row.delay)
    }
    for code in [URLError.timedOut, .networkConnectionLost, .cannotConnectToHost, .cannotFindHost,
                 .dnsLookupFailed, .notConnectedToInternet, .resourceUnavailable, .badServerResponse, .zeroByteResource] {
        #expect(IwlsFetch.retryDelay(after: .failure(URLError(code)), attempt: 1) == 2)
    }
    for code in [Int(ECONNABORTED), Int(ECONNRESET), Int(ETIMEDOUT)] {
        #expect(IwlsFetch.retryDelay(after: .failure(NSError(domain: NSPOSIXErrorDomain, code: code)), attempt: 1) == 2)
    }
    for error: any Error in [URLError(.cancelled), URLError(.secureConnectionFailed), URLError(.dataNotAllowed), CancellationError(), NSError(domain: "other", code: 53)] {
        #expect(IwlsFetch.retryDelay(after: .failure(error), attempt: 1) == nil)
    }
    #expect(IwlsFetch.terminalError(status: 404) == .permanent("HTTP 404"))
    #expect(IwlsFetch.terminalError(status: 429) == .transient("HTTP 429"))
    #expect(IwlsFetch.terminalError(status: 503) == .transient("HTTP 503"))
}
