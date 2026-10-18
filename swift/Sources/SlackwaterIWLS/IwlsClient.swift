// SPDX-License-Identifier: MIT
// Adapted from https://github.com/openwatersio/slackwater-ios/blob/f6750e9/Slackwater/IwlsClient.swift; relicensed by the copyright holder.
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public actor IwlsPacer {
    private let interval: TimeInterval
    private var last = Date.distantPast

    public init(interval: TimeInterval = 2.5) { self.interval = interval }

    public func wait() async throws {
        try Task.checkCancellation()
        let now = Date.now
        let slot = max(now, last.addingTimeInterval(interval))
        last = slot
        let gap = slot.timeIntervalSince(now)
        if gap > 0 { try await Task.sleep(for: .seconds(gap)) }
    }
}

public struct IwlsClient: Sendable {
    public static let base = "https://api-iwls.dfo-mpo.gc.ca/api/v1"
    public static let pacer = IwlsPacer()
    private let transport: @Sendable (URL) async throws -> (Data, URLResponse)
    private let pause: @Sendable (Double) async throws -> Void
    private let requestPacer: IwlsPacer
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
            let began = Date.now
            let result: Result<(Data, URLResponse), any Error>
            do {
                try await requestPacer.wait()
                result = .success(try await transport(url))
            } catch {
                result = .failure(error)
            }
            await observeRequest(Date.now.timeIntervalSince(began))
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
