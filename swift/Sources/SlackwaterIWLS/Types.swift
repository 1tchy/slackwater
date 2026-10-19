// SPDX-License-Identifier: MIT
import Foundation

public struct IwlsStation: Codable, Sendable {
    public struct Series: Codable, Sendable {
        public let code: String
        public init(code: String) { self.code = code }
    }
    public let id: String
    public let officialName: String
    public let latitude: Double
    public let longitude: Double
    public let timeSeries: [Series]

    public init(id: String, officialName: String, latitude: Double, longitude: Double, timeSeries: [Series]) {
        self.id = id
        self.officialName = officialName
        self.latitude = latitude
        self.longitude = longitude
        self.timeSeries = timeSeries
    }
}

public struct IwlsSample: Codable, Sendable {
    public let eventDate: String
    public let value: Double
}

public struct ChsSample: Codable, Equatable, Sendable {
    public let t: Double
    public let v: Double
    public init(t: Double, v: Double) { self.t = t; self.v = v }
}

public struct ChsChunk: Equatable, Hashable, Sendable {
    public let start: Date
    public let end: Date
    public init(start: Date, end: Date) { self.start = start; self.end = end }
    public var isCacheable: Bool { end.timeIntervalSince(start) == 7 * 86400 }
    /// Whether these on-grid samples may be kept for this chunk. A full chunk's identity never
    /// changes, so caching an empty response would keep a missing week for good.
    public func canCache(_ samples: [ChsSample]) -> Bool { isCacheable && !samples.isEmpty }
}

public enum IwlsError: Error, Equatable, LocalizedError {
    case permanent(String)
    case transient(String)

    public var errorDescription: String? {
        switch self {
        case .permanent(let reason), .transient(let reason): reason
        }
    }
}
