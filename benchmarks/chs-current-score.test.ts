import { describe, expect, test } from "vitest";
import {
  pairSlacks,
  parseEvents,
  rounded,
  scoreWeek,
  summarize,
  type ScoreEvent,
  type WeekScore,
} from "./chs-current-score.ts";

const start = Date.UTC(2026, 10, 5);
const end = start + 7 * 86_400_000;
const at = (minutes: number) => start + minutes * 60_000;
const slack = (minutes: number): ScoreEvent => ({ time: at(minutes), kind: "slack", speed: 0 });
const flood = (minutes: number, speed: number): ScoreEvent => ({
  time: at(minutes),
  kind: "maxFlood",
  speed,
});
const ebb = (minutes: number, speed: number): ScoreEvent => ({
  time: at(minutes),
  kind: "maxEbb",
  speed,
});
const weak = (minutes: number): ScoreEvent => ({ time: at(minutes), kind: "weak", speed: 0 });
const week = (observed: ScoreEvent[], predicted: ScoreEvent[], velocityAt = () => 1) =>
  scoreWeek({ start, end, observed, predicted, velocityAt });

describe("pairSlacks", () => {
  test("prefers the closer pair when only one can be made", () => {
    expect(pairSlacks([at(60), at(160)], [at(150), at(400)])).toEqual(new Map([[1, 0]]));
  });

  test("keeps pairs in time order at a double slack", () => {
    expect(pairSlacks([at(100), at(130)], [at(125), at(155)])).toEqual(
      new Map([
        [0, 0],
        [1, 1],
      ]),
    );
  });

  test("never pairs slacks farther apart than the window", () => {
    expect(pairSlacks([at(0)], [at(181)])).toEqual(new Map());
  });
});

describe("scoreWeek", () => {
  test("pairs each CHS slack with the predicted slack beside it", () => {
    const score = week([slack(60), slack(432), slack(804)], [slack(65), slack(430), slack(810)]);
    expect(score.slack_errors).toEqual([5, 2, 6]);
    expect(score.invented).toBe(0);
  });

  test("lets one predicted slack pair with one CHS slack only", () => {
    expect(week([slack(100), slack(160)], [slack(130)]).slack_errors).toEqual([30, null]);
  });

  test("scores a late double slack in order", () => {
    expect(week([slack(100), slack(130)], [slack(125), slack(155)]).slack_errors).toEqual([25, 25]);
  });

  test("pairs a late model's slacks in order rather than inventing one", () => {
    const score = week([slack(1000), slack(1240)], [slack(1130), slack(1380)]);
    expect(score.slack_errors).toEqual([130, 140]);
    expect(score.invented).toBe(0);
  });

  test("counts a predicted slack no CHS slack pairs with as invented", () => {
    const score = week([slack(60)], [slack(62), slack(400)]);
    expect(score.slack_errors).toEqual([2]);
    expect(score.invented).toBe(1);
  });

  test("pairs across the week's edge without missing or inventing a slack", () => {
    const score = week([slack(-10)], [slack(5)]);
    expect(score.slack_errors).toEqual([]);
    expect(score.invented).toBe(0);
  });

  test("lets a CHS slack before the week pair with its own neighbour, not one inside the week", () => {
    const score = week([slack(-170), slack(170)], [slack(-200), slack(5), slack(175)]);
    expect(score.slack_errors).toEqual([5]);
    expect(score.invented).toBe(1);
  });

  test("lets a CHS slack after the week pair with its own neighbour, not one inside the week", () => {
    const w = 7 * 1440;
    const score = week(
      [slack(w - 170), slack(w + 170)],
      [slack(w - 175), slack(w - 5), slack(w + 200)],
    );
    expect(score.slack_errors).toEqual([5]);
    expect(score.invented).toBe(1);
  });

  test("ignores predicted slacks in the padding", () => {
    expect(week([], [slack(7 * 1440 + 60)]).invented).toBe(0);
  });

  test("misses every CHS slack when the model never reverses", () => {
    const score = week([slack(60), slack(432)], [flood(200, 1.5)]);
    expect(score.slack_errors).toEqual([null, null]);
    expect(score.invented).toBe(0);
  });

  test("scores a week with peak flows but no slack", () => {
    const score = week([flood(100, 1.2)], [slack(50), slack(300), flood(110, 1)]);
    expect(score.slack_errors).toEqual([]);
    expect(score.invented).toBe(2);
    expect(score.extremum_errors).toEqual([10]);
    expect(score.speed_errors[0]).toBeCloseTo(0.2, 12);
  });

  test("matches extrema by kind", () => {
    const score = week([flood(100, 2)], [ebb(101, -2), flood(130, 1.8)]);
    expect(score.extremum_errors).toEqual([30]);
    expect(score.speed_errors[0]).toBeCloseTo(0.2, 12);
  });

  test("drops an extremum with no same-kind prediction within the window", () => {
    const score = week([flood(100, 2)], [flood(400, 2)]);
    expect(score.extrema).toBe(1);
    expect(score.extremum_errors).toEqual([]);
  });

  test("skips timing and speed for weak extrema but still checks their sign", () => {
    const score = week([ebb(200, -0.4)], [ebb(205, -0.5)], () => 0.2);
    expect(score).toMatchObject({
      extrema: 1,
      wrong_sign: 1,
      extremum_errors: [],
      speed_errors: [],
    });
  });

  test("does not count a predicted slack pair around a CHS weak-and-variable event as invented", () => {
    // Active Pass at neaps: CHS publishes ebb, weak and variable, ebb where the model reverses for an hour.
    const score = week(
      [slack(526), ebb(698, -1.6), weak(1000), ebb(1361, -2.9), slack(1575)],
      [
        slack(530),
        ebb(700, -1.5),
        slack(940),
        flood(1003, 0.2),
        slack(1060),
        ebb(1358, -2.8),
        slack(1572),
      ],
      () => -1,
    );
    expect(score).toMatchObject({ slack_errors: [4, 3], invented: 0, extrema: 2, wrong_sign: 0 });
  });

  test("still counts a predicted slack pair with no weak-and-variable event between them", () => {
    expect(week([weak(900)], [slack(940), slack(1060)]).invented).toBe(2);
  });

  test("excuses nothing when only one unpaired predicted slack lies beside a weak-and-variable event", () => {
    expect(week([weak(1000)], [slack(1060)]).invented).toBe(1);
    expect(week([weak(1000)], [slack(940)]).invented).toBe(1);
    expect(week([slack(940), weak(1000)], [slack(940), slack(1060)]).invented).toBe(1);
  });

  test("still counts a predicted slack pair farther than the window from a weak-and-variable event", () => {
    expect(week([weak(1000)], [slack(819), slack(1060)]).invented).toBe(2);
    expect(week([weak(1000)], [slack(940), slack(1181)]).invented).toBe(2);
  });

  test("lets a weak-and-variable event excuse one pair, and a predicted slack be excused once", () => {
    expect(week([weak(1000)], [slack(880), slack(940), slack(1060), slack(1120)]).invented).toBe(2);
    expect(week([weak(1000), weak(1120)], [slack(940), slack(1060), slack(1180)]).invented).toBe(1);
  });

  test("excuses a pair straddling the week's edge from either week", () => {
    const observed = [weak(-10)];
    const predicted = [slack(-70), slack(50)];
    expect(week(observed, predicted).invented).toBe(0);
    const before = scoreWeek({
      start: start - 7 * 86_400_000,
      end: start,
      observed,
      predicted,
      velocityAt: () => 1,
    });
    expect(before.invented).toBe(0);
  });

  test("never counts a weak-and-variable event as an extremum or a wrong sign", () => {
    for (const v of [-1, 1]) {
      expect(week([weak(1000)], [], () => v)).toMatchObject({
        extrema: 0,
        wrong_sign: 0,
        extremum_errors: [],
        speed_errors: [],
      });
    }
  });
});

describe("parseEvents", () => {
  test("reads slacks, floods and ebbs, with ebbs negative, in time order", () => {
    const events = parseEvents([
      { eventDate: "2026-11-05T06:00:00Z", qualifier: "EXTREMA_EBB", value: 2.5 },
      { eventDate: "2026-11-05T03:00:00Z", qualifier: "SLACK", value: 0 },
      { eventDate: "2026-11-05T00:00:00Z", qualifier: "EXTREMA_FLOOD", value: 3 },
    ]);
    expect(events).toEqual([
      { time: Date.UTC(2026, 10, 5, 0), kind: "maxFlood", speed: 3 },
      { time: Date.UTC(2026, 10, 5, 3), kind: "slack", speed: 0 },
      { time: Date.UTC(2026, 10, 5, 6), kind: "maxEbb", speed: -2.5 },
    ]);
  });

  test("reads a weak-and-variable row as a weak event at speed 0, with or without a value", () => {
    const events = parseEvents([
      { eventDate: "2026-11-05T06:00:00Z", qualifier: "WEAK_AND_VARIABLE" },
      { eventDate: "2026-11-05T03:00:00Z", qualifier: "OTHER", value: 0 },
      { eventDate: "2026-11-05T00:00:00Z", qualifier: "WEAK_AND_VARIABLE", value: 0 },
    ]);
    expect(events).toEqual([
      { time: Date.UTC(2026, 10, 5, 0), kind: "weak", speed: 0 },
      { time: Date.UTC(2026, 10, 5, 6), kind: "weak", speed: 0 },
    ]);
  });

  test("drops rows with another qualifier", () => {
    expect(
      parseEvents([{ eventDate: "2026-11-05T00:00:00Z", qualifier: "OTHER", value: null }]),
    ).toEqual([]);
    expect(
      parseEvents([{ eventDate: "2026-11-05T00:00:00Z", qualifier: "toString", value: 1 }]),
    ).toEqual([]);
  });

  test("stops on an unreadable date, which would otherwise score as a missed slack", () => {
    expect(() => parseEvents([{ eventDate: "5 Nov 2026", qualifier: "SLACK", value: 0 }])).toThrow(
      /unreadable/,
    );
  });

  test("stops on an extremum with no speed", () => {
    expect(() =>
      parseEvents([{ eventDate: "2026-11-05T00:00:00Z", qualifier: "EXTREMA_FLOOD", value: null }]),
    ).toThrow(/speed/);
  });

  test("stops on a negative extremum speed, since CHS publishes magnitudes", () => {
    for (const qualifier of ["EXTREMA_FLOOD", "EXTREMA_EBB"]) {
      expect(() =>
        parseEvents([{ eventDate: "2026-11-05T00:00:00Z", qualifier, value: -1.2 }]),
      ).toThrow(/negative/);
    }
  });

  test("refuses anything but an array", () => {
    expect(() => parseEvents({})).toThrow(TypeError);
  });
});

describe("summarize", () => {
  const score = (s: Partial<WeekScore>): WeekScore => ({
    slack_errors: [],
    invented: 0,
    extremum_errors: [],
    speed_errors: [],
    extrema: 0,
    wrong_sign: 0,
    ...s,
  });

  test("takes medians and the worst over matched slacks and counts the missed ones", () => {
    expect(summarize(score({ slack_errors: [5, null, 10, 20] }))).toMatchObject({
      slacks: 4,
      missed: 1,
      slack_median_min: 10,
      slack_worst_min: 20,
      pass: false,
    });
  });

  test("passes a week inside the one-week bar", () => {
    const s = score({
      slack_errors: [5, 10],
      extremum_errors: [10],
      speed_errors: [0.1],
      extrema: 2,
    });
    expect(summarize(s).pass).toBe(true);
  });

  test("fails a week with an invented slack", () => {
    const s = score({
      slack_errors: [5],
      invented: 1,
      extremum_errors: [10],
      speed_errors: [0.1],
      extrema: 1,
    });
    expect(summarize(s).pass).toBe(false);
  });

  test("fails a week with nothing to score", () => {
    expect(summarize(score({})).pass).toBe(false);
  });

  test("flags a reversed axis at 60 % wrong signs", () => {
    expect(summarize(score({ extrema: 5, wrong_sign: 3 })).reversed).toBe(true);
    expect(summarize(score({ extrema: 5, wrong_sign: 2 })).reversed).toBe(false);
  });
});

describe("rounded", () => {
  const errors = (s: Partial<WeekScore>): WeekScore => ({
    slack_errors: [],
    invented: 0,
    extremum_errors: [],
    speed_errors: [],
    extrema: 0,
    wrong_sign: 0,
    ...s,
  });

  test("rounds minutes up to a tenth and knots up to a hundredth, keeping missed slacks", () => {
    const r = rounded(
      errors({
        slack_errors: [1.234, null],
        extremum_errors: [9.876],
        speed_errors: [0.12345],
        extrema: 1,
      }),
    );
    expect(r.slack_errors).toEqual([1.3, null]);
    expect(r.extremum_errors).toEqual([9.9]);
    expect(r.speed_errors).toEqual([0.13]);
  });

  test("never rounds an error down past a bar", () => {
    const r = rounded(errors({ slack_errors: [30.04, 45.04], speed_errors: [0.504] }));
    expect(r.slack_errors).toEqual([30.1, 45.1]);
    expect(r.speed_errors).toEqual([0.51]);
  });

  test("keeps an error already on the grid, float noise included", () => {
    const r = rounded(errors({ slack_errors: [30.0, 15.000000000000002, 0], speed_errors: [0] }));
    expect(r.slack_errors).toEqual([30.0, 15.0, 0]);
    expect(r.speed_errors).toEqual([0]);
  });

  test("fails a week whose raw worst slack is just past the bar", () => {
    const week = errors({
      slack_errors: [5, 5, 30.04],
      extremum_errors: [10],
      speed_errors: [0.1],
      extrema: 1,
    });
    expect(summarize(rounded(week))).toMatchObject({
      slack_median_min: 5,
      slack_worst_min: 30.1,
      extremum_median_min: 10,
      speed_median_kn: 0.1,
      missed: 0,
      invented: 0,
      reversed: false,
      pass: false,
    });
  });
});
