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
private struct GoldenOffAxis: Decodable { let amplitudeDegrees: Double; let periodHours: Double }
private struct GoldenSeries: Decodable {
    let start: String; let days: Int; let floodDirection: Double; let meanFlow: Double
    let offAxis: GoldenOffAxis
    let components: [GoldenComponent]
}
private struct GoldenSpan: Decodable { let start: String; let end: String }
private struct GoldenExtremum: Decodable { let time: String; let kind: String; let speed: Double }
private struct GoldenWindow: Decodable {
    let days: Int; let velocities: [Double]; let slacks: [String]; let extrema: [GoldenExtremum]
}
private struct Golden: Decodable {
    let series: GoldenSeries; let events: GoldenSpan; let windows: [GoldenWindow]; let stamp: String
}

@Test func chsCurrentFitCarriesTheGoldenStamp() throws {
    #expect(ChsCurrentFit.stamp == (try loadFixture("chs-current-golden", as: Golden.self)).stamp)
}

/// The same synthetic gate the TypeScript generator fits, fitted and predicted
/// here at the tolerances the generator keeps its stamp under: SlackwaterKit
/// must reproduce the velocities, slacks and peaks the stamp was derived from.
@Test func chsCurrentFitReproducesTheGoldenWindows() throws {
    let g = try loadFixture("chs-current-golden", as: Golden.self)
    let start = parseISO(g.series.start)
    let step = Double(ChsCurrentFit.sampleMinutes * 60)
    let flood = g.series.floodDirection
    let ebb = (flood + 180).truncatingRemainder(dividingBy: 360)
    var speeds: [HarmonicSample] = [], directions: [HarmonicSample] = []
    for k in 0...(g.series.days * 86_400 / Int(step)) {
        let time = start.addingTimeInterval(Double(k) * step)
        let hours = Double(k) * step / 3600
        let velocity = g.series.components.reduce(g.series.meanFlow) { sum, c in
            sum + c.amplitude * cos(2 * .pi * hours / c.periodHours - c.phaseDegrees * .pi / 180)
        }
        // Off the flood axis by theta, with the speed the projection needs to recover the velocity.
        let theta = g.series.offAxis.amplitudeDegrees * sin(2 * .pi * hours / g.series.offAxis.periodHours)
        let direction = (velocity >= 0 ? flood : flood + 180) + theta
        speeds.append(HarmonicSample(time: time, value: abs(velocity) / cos(theta * .pi / 180)))
        directions.append(HarmonicSample(
            time: time,
            value: (direction.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)))
    }
    let samples = ChsCurrentFit.project(speeds: speeds, directions: directions, floodDirection: flood)
    let fitEnd = start.addingTimeInterval(Double(g.series.days) * 86_400)
    let from = parseISO(g.events.start), to = parseISO(g.events.end)
    let inSpan = { (t: Date) in t >= from && t < to }
    for w in g.windows {
        let label = "\(w.days) d"
        let cut = fitEnd.addingTimeInterval(-Double(w.days) * 86_400)
        let result = try fit(samples: samples.filter { $0.time >= cut }, constituents: ChsCurrentFit.basis)
        let station = CurrentStation(constituents: result.constituents, floodDirection: flood,
                                     ebbDirection: ebb, offset: result.offset)

        let velocities = station.speeds(from: from, to: to, step: 600).map(\.speed)
        #expect(velocities.count == w.velocities.count, "\(label) velocities")
        for (a, e) in zip(velocities, w.velocities) {
            #expect(abs(a - e) < 1e-4, "\(label) velocity \(a) against \(e)")
        }

        let slacks = station.slacks(from: from, to: to).map(\.time).filter(inSpan)
        #expect(slacks.count == w.slacks.count, "\(label) slacks")
        for (a, e) in zip(slacks, w.slacks.map(parseISO)) {
            #expect(abs(a.timeIntervalSince(e)) < 30, "\(label) slack near \(e)")
        }

        let extrema = station.maxima(from: from, to: to).filter { inSpan($0.time) }
        let kinds = w.extrema.map { $0.kind == "maxFlood" ? CurrentEventKind.maxFlood : .maxEbb }
        #expect(extrema.map(\.kind) == kinds, "\(label) extremum kinds")
        for (a, e) in zip(extrema, w.extrema) {
            #expect(abs(a.time.timeIntervalSince(parseISO(e.time))) < 30, "\(label) extremum near \(e.time)")
            #expect(abs(a.speed - e.speed) < 1e-3, "\(label) extremum speed near \(e.time)")
        }
    }
}
