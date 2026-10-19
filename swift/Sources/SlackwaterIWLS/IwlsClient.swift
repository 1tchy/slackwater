// SPDX-License-Identifier: MIT
// Adapted from https://github.com/openwatersio/slackwater-ios/blob/f6750e9/Slackwater/IwlsClient.swift; relicensed by the copyright holder.
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public actor IwlsPacer {
    private let interval: Duration
    // Monotonic, so a wall-clock step back cannot stall every client in the process.
    private var last: ContinuousClock.Instant?

    public init(interval: TimeInterval = 2.5) { self.interval = .seconds(interval) }

    public func wait() async throws {
        try Task.checkCancellation()
        let now = ContinuousClock.now
        let slot = last.map { max(now, $0 + interval) } ?? now
        last = slot
        if slot > now { try await Task.sleep(until: slot, clock: .continuous) }
    }
}

public struct IwlsClient: Sendable {
    public static let base = "https://api-iwls.dfo-mpo.gc.ca/api/v1"
    public static let pacer = IwlsPacer()
    private let transport: @Sendable (URL) async throws -> (Data, URLResponse)
    private let pause: @Sendable (Double) async throws -> Void
    let requestPacer: IwlsPacer
    private let observeRequest: @Sendable (TimeInterval) async -> Void

    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
#if !canImport(FoundationNetworking)
        config.waitsForConnectivity = true
#endif
        // An unavailable path must eventually release the application's download queue.
        config.timeoutIntervalForResource = 300
        return URLSession(configuration: config)
    }()

    public init(session: URLSession? = nil,
                observeRequest: @escaping @Sendable (TimeInterval) async -> Void = { _ in }) {
        let session = session ?? Self.session
        self.init(transport: { try await session.data(from: $0) },
                  pause: { try await Task.sleep(for: .seconds($0)) },
                  pacer: Self.pacer, observeRequest: observeRequest)
    }

    init(transport: @escaping @Sendable (URL) async throws -> (Data, URLResponse),
         pause: @escaping @Sendable (Double) async throws -> Void,
         pacer: IwlsPacer, observeRequest: @escaping @Sendable (TimeInterval) async -> Void) {
        self.transport = transport
        self.pause = pause
        self.requestPacer = pacer
        self.observeRequest = observeRequest
    }

    public func stationList() async throws -> [IwlsStation] {
        try JSONDecoder().decode([IwlsStation].self, from: await get(URL(string: Self.base + "/stations")!))
    }

    public struct Metadata: Decodable, Sendable {
        public let floodDirection: Double?
        public let ebbDirection: Double?
        public init(floodDirection: Double?, ebbDirection: Double?) {
            self.floodDirection = floodDirection; self.ebbDirection = ebbDirection
        }
    }

    public func metadata(stationID: String) async throws -> Metadata {
        // Reuse the series URL's path-component escaping for resolved station IDs.
        let chunk = ChsChunk(start: Date(timeIntervalSince1970: 0), end: Date(timeIntervalSince1970: 0))
        let dataURL = try IwlsFetch.dataURL("wlp", stationID: stationID, chunk: chunk)
        var url = URLComponents(url: dataURL, resolvingAgainstBaseURL: false)!
        url.percentEncodedPath = String(url.percentEncodedPath.dropLast(4)) + "metadata"
        url.queryItems = nil
        return try JSONDecoder().decode(Metadata.self, from: await get(url.url!))
    }

    public func series(_ code: String, stationID: String, chunk: ChsChunk) async throws -> [ChsSample] {
        let url = try IwlsFetch.dataURL(code, stationID: stationID, chunk: chunk)
        return try IwlsFetch.onGrid(IwlsFetch.decode(await get(url)))
    }

    private func get(_ url: URL) async throws -> Data {
        var attempt = 0
        while true {
            try Task.checkCancellation()
            attempt += 1
            let began = ContinuousClock.now
            let result: Result<(Data, URLResponse), any Error>
            do {
                try await requestPacer.wait()
                result = .success(try await transport(url))
            } catch {
                result = .failure(error)
            }
            let elapsed = (ContinuousClock.now - began).components
            await observeRequest(Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18)
            let outcome: Result<Int, any Error>
            let retryAfter: Double?
            switch result {
            case .success(let (data, response)):
                let http = response as? HTTPURLResponse
                let status = http?.statusCode ?? 0
                if status == 200 { return data }
                outcome = .success(status)
                retryAfter = http?.value(forHTTPHeaderField: "Retry-After").flatMap(Double.init)
            case .failure(let error):
                outcome = .failure(error)
                retryAfter = nil
            }
            guard let delay = IwlsFetch.retryDelay(after: outcome, attempt: attempt, retryAfter: retryAfter) else {
                switch outcome {
                case .success(let status): throw IwlsFetch.terminalError(status: status)
                case .failure(let error): throw error
                }
            }
            try await pause(delay)
        }
    }
}
