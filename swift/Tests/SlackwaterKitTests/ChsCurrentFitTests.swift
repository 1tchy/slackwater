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

@Test func chsCurrentFitProjectsOntoTheFloodAxis() throws {
    for c in try loadFixture("chs-current-fit", as: Recipe.self).projections {
        let projected = ChsCurrentFit.project(speeds: samples(c.speeds), directions: samples(c.directions),
                                              floodDirection: c.floodDirection)
        let expected = samples(c.expected)
        #expect(projected.map(\.time) == expected.map(\.time), "\(c.note)")
        for (p, e) in zip(projected, expected) { #expect(abs(p.value - e.value) < 1e-12, "\(c.note)") }
    }
}
