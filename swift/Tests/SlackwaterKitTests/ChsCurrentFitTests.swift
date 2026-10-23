import Foundation
import Testing
import SlackwaterKit

private struct Sample: Decodable { let time: String; let value: Double }
private struct Projection: Decodable {
    let note: String
    let floodDirection: Double
    let speeds: [Sample]
    let directions: [Sample]
    let expected: [Sample]
}
private struct Recipe: Decodable {
    let speedSeries: String
    let directionSeries: String
    let basis: [String]
    let days: [Int]
    let sampleMinutes: Int
    let provisionalSlackMaxMinutes: Int
    let projections: [Projection]
}

private func samples(_ rows: [Sample]) -> [HarmonicSample] {
    rows.map { HarmonicSample(time: parseISO($0.time), value: $0.value) }
}

@Test func chsCurrentFitMatchesTheSharedRecipe() throws {
    let recipe = try loadFixture("chs-current-fit", as: Recipe.self)
    #expect(ChsCurrentFit.speedSeries == recipe.speedSeries)
    #expect(ChsCurrentFit.directionSeries == recipe.directionSeries)
    #expect(ChsCurrentFit.basis == recipe.basis)
    #expect(ChsCurrentFit.days == recipe.days)
    #expect(ChsCurrentFit.sampleMinutes == recipe.sampleMinutes)
    #expect(ChsCurrentFit.provisionalSlackMaxMinutes == recipe.provisionalSlackMaxMinutes)
}

@Test func chsCurrentFitDropsNonFiniteSamples() {
    let t = { (i: Int) in Date(timeIntervalSince1970: 1_767_225_600 + Double(i) * 900) }
    let speeds = [Double.nan, 2, 1].enumerated().map { HarmonicSample(time: t($0.offset), value: $0.element) }
    let directions = [135, Double.nan, 135].enumerated().map { HarmonicSample(time: t($0.offset), value: $0.element) }
    let projected = ChsCurrentFit.project(speeds: speeds, directions: directions, floodDirection: 135)
    #expect(projected.map(\.time) == [t(2)])
    #expect(projected.map(\.value) == [1])
}

@Test func chsCurrentFitProjectsOntoTheFloodAxis() throws {
    for c in try loadFixture("chs-current-fit", as: Recipe.self).projections {
        let projected = ChsCurrentFit.project(speeds: samples(c.speeds), directions: samples(c.directions),
                                              floodDirection: c.floodDirection)
        let expected = samples(c.expected)
        #expect(projected.map(\.time) == expected.map(\.time), "\(c.note)")
        for (p, e) in zip(projected, expected) { #expect(abs(p.value - e.value) < 1e-12, "\(c.note)") }
    }
}

private struct GoldenComponent: Decodable { let periodHours: Double; let amplitude: Double; let phaseDegrees: Double }
private struct GoldenSeries: Decodable {
    let start: String; let days: Int; let floodDirection: Double; let meanFlow: Double
    let components: [GoldenComponent]
}
private struct GoldenSpan: Decodable { let start: String; let end: String }
private struct GoldenWindow: Decodable { let days: Int; let slacks: [String] }
private struct Golden: Decodable {
    let series: GoldenSeries; let events: GoldenSpan; let windows: [GoldenWindow]; let stamp: String
}

@Test func chsCurrentFitCarriesTheGoldenStamp() throws {
    #expect(ChsCurrentFit.stamp == (try loadFixture("chs-current-golden", as: Golden.self)).stamp)
}

/// The same synthetic gate the TypeScript generator fits, fitted and predicted
/// here: SlackwaterKit must reproduce the slacks the stamp was derived from.
@Test func chsCurrentFitReproducesTheGoldenSlacks() throws {
    let g = try loadFixture("chs-current-golden", as: Golden.self)
    let start = parseISO(g.series.start)
    let step = Double(ChsCurrentFit.sampleMinutes * 60)
    let ebb = (g.series.floodDirection + 180).truncatingRemainder(dividingBy: 360)
    var speeds: [HarmonicSample] = [], directions: [HarmonicSample] = []
    for k in 0...(g.series.days * 86_400 / Int(step)) {
        let time = start.addingTimeInterval(Double(k) * step)
        let hours = Double(k) * step / 3600
        let velocity = g.series.components.reduce(g.series.meanFlow) { sum, c in
            sum + c.amplitude * cos(2 * .pi * hours / c.periodHours - c.phaseDegrees * .pi / 180)
        }
        speeds.append(HarmonicSample(time: time, value: abs(velocity)))
        directions.append(HarmonicSample(time: time, value: velocity >= 0 ? g.series.floodDirection : ebb))
    }
    let samples = ChsCurrentFit.project(speeds: speeds, directions: directions,
                                        floodDirection: g.series.floodDirection)
    let fitEnd = start.addingTimeInterval(Double(g.series.days) * 86_400)
    let from = parseISO(g.events.start), to = parseISO(g.events.end)
    for w in g.windows {
        let cut = fitEnd.addingTimeInterval(-Double(w.days) * 86_400)
        let result = try fit(samples: samples.filter { $0.time >= cut }, constituents: ChsCurrentFit.basis)
        let station = CurrentStation(constituents: result.constituents, floodDirection: g.series.floodDirection,
                                     ebbDirection: ebb, offset: result.offset)
        let slacks = station.slacks(from: from, to: to).map(\.time).filter { $0 >= from && $0 < to }
        let expected = w.slacks.map(parseISO)
        #expect(slacks.count == expected.count, "\(w.days) d")
        for (a, e) in zip(slacks, expected) {
            #expect(abs(a.timeIntervalSince(e)) < 60, "\(w.days) d slack near \(e)")
        }
    }
}
