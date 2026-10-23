// Scores one held-out week of a fitted CHS current gate against CHS's own
// published events, for benchmarks/chs-current-fit.ts and the results
// repository that will pool its rows. Nothing here does I/O, so the rules are
// tested on synthetic events in chs-current-score.test.ts.
import { CONTEXT_MIN, MATCH_WINDOW_MIN } from "./chs-current-grid.ts";
import { parseEventDate } from "./iwls.ts";

/** Bumped whenever a rule changes how this benchmark fits or scores (the fetch plan, the fit end, padding, pairing), so rows from different rules never pool. */
export const SCORER_VERSION = 2;
/** CHS extrema weaker than this get no timing or speed score: the peak of a weak drift is noise. */
export const SIGNIFICANT_KN = 0.75;
/** A model with the wrong sign at this share of a week's CHS extrema has its flood axis reversed. */
export const REVERSED_SHARE = 0.6;

export type EventKind = "slack" | "maxFlood" | "maxEbb";

export interface ScoreEvent {
  /** Epoch milliseconds. */
  time: number;
  kind: EventKind;
  /** Signed knots, positive on the flood. */
  speed: number;
}

export interface WeekScore {
  /** One entry per CHS slack in the week, in time order: minutes to its paired predicted slack, or null when none pairs with it. */
  slack_errors: (number | null)[];
  /** Predicted slacks in the week that no CHS slack pairs with. */
  invented: number;
  /** Minutes from each CHS extremum of at least SIGNIFICANT_KN to the nearest predicted extremum of its kind, when one lies within the match window. */
  extremum_errors: number[];
  /** Knots between the same extrema's speeds, in the same order. */
  speed_errors: number[];
  /** CHS extrema in the week. */
  extrema: number;
  /** CHS extrema at which the model's velocity has the other sign. */
  wrong_sign: number;
}

const QUALIFIERS = new Map<unknown, EventKind>([
  ["SLACK", "slack"],
  ["EXTREMA_FLOOD", "maxFlood"],
  ["EXTREMA_EBB", "maxEbb"],
]);

/**
 * CHS `wcp1-events` rows as events, in time order. An unreadable date stops
 * the run, because the lost event would score as a missed slack; a row with
 * another qualifier is not an event this benchmark scores.
 */
export function parseEvents(raw: unknown): ScoreEvent[] {
  if (!Array.isArray(raw)) throw new TypeError("IWLS events must be an array");
  return raw
    .flatMap((row): ScoreEvent[] => {
      const kind = QUALIFIERS.get(row?.qualifier);
      if (!kind) return [];
      const time = parseEventDate(String(row.eventDate));
      if (time === undefined) throw new Error(`unreadable wcp1-events date: ${row.eventDate}`);
      if (kind === "slack") return [{ time, kind, speed: 0 }];
      if (typeof row.value !== "number") {
        throw new Error(`wcp1-events ${row.qualifier} at ${row.eventDate} has no speed`);
      }
      // CHS publishes magnitudes, so a negative one would flip the sign the qualifier gives.
      if (row.value < 0) {
        throw new Error(`wcp1-events ${row.qualifier} at ${row.eventDate} has a negative speed`);
      }
      return [{ time, kind, speed: kind === "maxEbb" ? -row.value : row.value }];
    })
    .sort((a, b) => a.time - b.time);
}

interface Matching {
  gapMs: number;
  observedSum: number;
  links: [number, number][];
}

/** More pairs first, then less total gap, then the earlier CHS slacks paired. */
function better(a: Matching, b: Matching): boolean {
  if (a.links.length !== b.links.length) return a.links.length > b.links.length;
  if (a.gapMs !== b.gapMs) return a.gapMs < b.gapMs;
  return a.observedSum < b.observedSum;
}

/**
 * Pairs CHS slacks with predicted slacks one to one, never farther apart than
 * `windowMin`. Pairs keep their time order: if one CHS slack pairs with a
 * predicted slack, a later CHS slack pairs with a later predicted slack. Both
 * inputs must be sorted ascending. Among the matchings that keep order, the
 * most pairs win, then the least total gap, then the earliest CHS slacks.
 * Maps each paired observed index to its predicted index, so one predicted
 * slack can never stand in for two CHS slacks.
 *
 * Order matters because a crossed pair scores each slack against its
 * neighbour's prediction. CHS slacks at 100 and 130 minutes against a model at
 * 125 and 155 would read as 55 and 5 instead of 25 and 25, and a late model's
 * slacks would read as a missed reversal plus an invented slack it does not
 * have.
 */
export function pairSlacks(
  observed: readonly number[],
  predicted: readonly number[],
  windowMin = MATCH_WINDOW_MIN,
): Map<number, number> {
  // best[i][j] is the best matching of observed[0..i) with predicted[0..j).
  const best: Matching[][] = Array.from({ length: observed.length + 1 }, () => []);
  best[0][0] = { gapMs: 0, observedSum: 0, links: [] };
  for (let i = 0; i <= observed.length; i++) {
    for (let j = 0; j <= predicted.length; j++) {
      if (i === 0 && j === 0) continue;
      const options: Matching[] = [];
      if (i > 0) options.push(best[i - 1][j]);
      if (j > 0) options.push(best[i][j - 1]);
      if (i > 0 && j > 0) {
        const gap = Math.abs(observed[i - 1] - predicted[j - 1]);
        if (gap / 60_000 <= windowMin) {
          const prev = best[i - 1][j - 1];
          options.push({
            gapMs: prev.gapMs + gap,
            observedSum: prev.observedSum + (i - 1),
            links: [...prev.links, [i - 1, j - 1]],
          });
        }
      }
      best[i][j] = options.reduce((a, b) => (better(b, a) ? b : a));
    }
  }
  return new Map(best[observed.length][predicted.length].links);
}

/**
 * Scores the week [start, end). Slacks within CONTEXT_MIN of the week take
 * part in pairing, so a slack just outside the week pairs with its own
 * neighbour, also outside, rather than taking one inside the week. Pairs are
 * at most MATCH_WINDOW_MIN apart, and only slacks inside the week are scored.
 */
export function scoreWeek({
  start,
  end,
  observed,
  predicted,
  velocityAt,
}: {
  start: number;
  end: number;
  /** CHS events over at least the week and CONTEXT_MIN on each side. */
  observed: readonly ScoreEvent[];
  /** The model's events over at least the same span. */
  predicted: readonly ScoreEvent[];
  /** The model's signed velocity at an instant. */
  velocityAt: (time: number) => number;
}): WeekScore {
  const context = CONTEXT_MIN * 60_000;
  const inContext = (e: ScoreEvent) =>
    e.kind === "slack" && e.time >= start - context && e.time <= end + context;
  const inWeek = (t: number) => t >= start && t < end;
  const obs = observed
    .filter(inContext)
    .map((e) => e.time)
    .sort((a, b) => a - b);
  const pred = predicted
    .filter(inContext)
    .map((e) => e.time)
    .sort((a, b) => a - b);
  const pairs = pairSlacks(obs, pred);
  const paired = new Set(pairs.values());

  const slack_errors = obs.flatMap((t, o) => {
    if (!inWeek(t)) return [];
    const p = pairs.get(o);
    return [p === undefined ? null : Math.abs(t - pred[p]) / 60_000];
  });
  const invented = pred.filter((t, p) => inWeek(t) && !paired.has(p)).length;

  const extremum_errors: number[] = [];
  const speed_errors: number[] = [];
  let extrema = 0;
  let wrong_sign = 0;
  for (const e of observed) {
    if (e.kind === "slack" || !inWeek(e.time)) continue;
    extrema++;
    // The sign of the model's velocity at CHS's own extremum tests for a
    // reversed axis; a matched event's sign would only say which event matched.
    const v = velocityAt(e.time);
    if ((e.kind === "maxFlood" && v < 0) || (e.kind === "maxEbb" && v > 0)) wrong_sign++;
    if (Math.abs(e.speed) < SIGNIFICANT_KN) continue;
    let best: ScoreEvent | undefined;
    for (const p of predicted) {
      if (p.kind !== e.kind) continue;
      if (!best || Math.abs(p.time - e.time) < Math.abs(best.time - e.time)) best = p;
    }
    if (best && Math.abs(best.time - e.time) / 60_000 <= MATCH_WINDOW_MIN) {
      extremum_errors.push(Math.abs(best.time - e.time) / 60_000);
      speed_errors.push(Math.abs(best.speed - e.speed));
    }
  }
  return { slack_errors, invented, extremum_errors, speed_errors, extrema, wrong_sign };
}

/**
 * Rounds a week's errors up for publication: minutes to the next tenth, knots
 * to the next hundredth. Every bar (15, 20, 30 and 45 minutes, 0.5 kn) sits on
 * that grid, so a rounded error meets a bar exactly when the raw error does,
 * and no bound computed from rows comes out tighter than the truth. A median
 * of an even count averages two rounded errors, so it can come out above the
 * raw median, never below. The guard keeps float noise such as
 * 15.000000000000002 on its grid point, and errors are never negative, so a
 * zero stays 0 rather than -0.
 */
export function rounded(score: WeekScore): WeekScore {
  const up = (digits: number) => (x: number) =>
    Math.max(0, Math.ceil(x * 10 ** digits - 1e-9)) / 10 ** digits;
  return {
    ...score,
    slack_errors: score.slack_errors.map((e) => (e === null ? null : up(1)(e))),
    extremum_errors: score.extremum_errors.map(up(1)),
    speed_errors: score.speed_errors.map(up(2)),
  };
}

/** The one-week bar in docs/validation/chs-currents.md, for the share floors and the CSV summary. Year-round verdicts will pool many weeks in the results repository. */
export const WEEK_BAR = {
  slackMedianMin: 15,
  slackWorstMin: 30,
  extremumMedianMin: 20,
  speedMedianKn: 0.5,
};

export interface WeekSummary {
  slacks: number;
  missed: number;
  invented: number;
  slack_median_min: number;
  slack_worst_min: number;
  extrema: number;
  extrema_scored: number;
  extremum_median_min: number;
  speed_median_kn: number;
  wrong_sign: number;
  reversed: boolean;
  pass: boolean;
}

export function summarize(score: WeekScore): WeekSummary {
  const matched = score.slack_errors.filter((e): e is number => e !== null);
  const missed = score.slack_errors.length - matched.length;
  const slack_median_min = median(matched);
  const slack_worst_min = matched.length ? Math.max(...matched) : NaN;
  const extremum_median_min = median(score.extremum_errors);
  const speed_median_kn = median(score.speed_errors);
  const reversed = score.extrema > 0 && score.wrong_sign / score.extrema >= REVERSED_SHARE;
  return {
    slacks: score.slack_errors.length,
    missed,
    invented: score.invented,
    slack_median_min,
    slack_worst_min,
    extrema: score.extrema,
    extrema_scored: score.extremum_errors.length,
    extremum_median_min,
    speed_median_kn,
    wrong_sign: score.wrong_sign,
    reversed,
    // NaN fails every comparison, so a week with nothing to score fails.
    pass:
      missed === 0 &&
      score.invented === 0 &&
      !reversed &&
      slack_median_min <= WEEK_BAR.slackMedianMin &&
      slack_worst_min <= WEEK_BAR.slackWorstMin &&
      extremum_median_min <= WEEK_BAR.extremumMedianMin &&
      speed_median_kn <= WEEK_BAR.speedMedianKn,
  };
}

export function median(data: readonly number[]): number {
  if (data.length === 0) return NaN;
  const s = [...data].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

export type RowStatus = "scored" | "no_events" | "beyond_horizon";

/**
 * One gate, grid point, window and held-out week. A scored row adds a
 * rounded WeekScore. The results repository will find a gate's worst slacks from
 * `slack_errors` positions, with `grid` and `offset_days`, so rows never need
 * CHS's own timestamps.
 */
export interface ResultRow extends Partial<WeekScore> {
  gate: string;
  iwls: string;
  /** The flood axis the fit projected onto, degrees true. */
  flood: number;
  stamp: string;
  scorer: number;
  grid: number;
  /** The gate's local midnight on the grid point's date, ISO 8601. */
  fit_end: string;
  fit_days: number;
  /** The oldest fetched chunk's start: the app's chunk plan fits 0 to 7 extra days. */
  fit_start: string;
  fit_samples: number;
  fit_rms_kn: number;
  offset_days: number;
  week_start: string;
  status: RowStatus;
}

/** What one run learned about a gate, scored or not. A gate with no rows carries a `reason`. */
export interface GateRecord {
  gate: string;
  iwls?: string;
  flood?: number;
  ebb?: number;
  time_zone?: string;
  /** The end of CHS's published events for this station, ISO 8601. */
  horizon?: string;
  /** A hash of one fixed past week of `wcsp1`; a change means CHS revised its predictions. Absent when IWLS refused that week. */
  canary?: string;
  reason?: string;
}
