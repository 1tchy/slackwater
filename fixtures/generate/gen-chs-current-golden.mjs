// Generate the CHS current recipe's golden fit and stamp
// (fixtures/chs-current-golden.json) from the @slackwater/engine reference.
// The stamp hashes the recipe's data and projection cases with the slacks the
// recipe predicts for a fixed synthetic gate. A change to the basis, windows,
// sample step, projection, `fit` or `createCurrentPredictor` that moves one of
// those slacks by 30 seconds or more changes the stamp, and every engine's
// `ChsCurrentFit` stamp must change with it. Validation results and fitted
// models record the stamp, so a model never borrows the evidence of another
// recipe.
//
// A change that moves no golden slack by 30 seconds or more keeps the recorded
// golden fit and its stamp, so engine releases that leave the recipe alone keep
// the stamp. The generator keeps the existing fixture, recorded windows and
// stamp included, while the series and event range are unchanged, every
// window's slack count is unchanged, every recorded slack is less than
// TOLERANCE_MS from the freshly computed one, and the recorded windows still hash
// to the recorded stamp (which fails when the recipe data changed). Otherwise
// it writes the fresh windows with their new stamp. `--check` therefore reports
// drift only when a slack moves 30 seconds or more, a slack count changes, or
// the recipe data changes. Hashing minute-rounded slacks would not do: a slack
// a few milliseconds from a rounding boundary would flip the stamp on a
// harmless numeric change.
// Usage: node fixtures/generate/gen-chs-current-golden.mjs [--check]
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual } from "node:util";
import { chsCurrentFit, createCurrentPredictor, fit, projectChsCurrent } from "@slackwater/engine";
import { writeJSON } from "./write.mjs";

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const TOLERANCE_MS = 30_000;
const recipe = JSON.parse(readFileSync(new URL("../chs-current-fit.json", import.meta.url), "utf8"));

// Plain cosines in hours since `start`, so the input never depends on the
// engine's astronomy: tide-like lines, plus one at 28.006 h that the basis
// lacks, so the fit leaves a residual as it does on real data.
const series = {
  start: "2026-01-01T00:00:00Z",
  days: 210,
  floodDirection: 135,
  meanFlow: 0.1,
  components: [
    { periodHours: 12.4206012, amplitude: 2, phaseDegrees: 30 },
    { periodHours: 12, amplitude: 0.6, phaseDegrees: 75 },
    { periodHours: 12.65834751, amplitude: 0.4, phaseDegrees: 140 },
    { periodHours: 23.93446966, amplitude: 0.9, phaseDegrees: 200 },
    { periodHours: 25.81934171, amplitude: 0.7, phaseDegrees: 260 },
    { periodHours: 24.06589016, amplitude: 0.3, phaseDegrees: 310 },
    { periodHours: 6.210300601, amplitude: 0.15, phaseDegrees: 10 },
    { periodHours: 28.00621204, amplitude: 0.05, phaseDegrees: 95 },
  ],
};

const start = Date.parse(series.start);
const fitEnd = start + series.days * DAY;
const step = chsCurrentFit.sampleMinutes * 60_000;
const ebbDirection = (series.floodDirection + 180) % 360;
const speeds = [];
const directions = [];
for (let t = start; t <= fitEnd; t += step) {
  const hours = (t - start) / HOUR;
  const velocity = series.components.reduce(
    (sum, c) =>
      sum + c.amplitude * Math.cos((2 * Math.PI * hours) / c.periodHours - (c.phaseDegrees * Math.PI) / 180),
    series.meanFlow,
  );
  // IWLS reports a speed and the direction the water sets toward.
  speeds.push({ time: new Date(t), value: Math.abs(velocity) });
  directions.push({ time: new Date(t), value: velocity >= 0 ? series.floodDirection : ebbDirection });
}
const samples = projectChsCurrent(speeds, directions, series.floodDirection);
const from = new Date(fitEnd);
const to = new Date(fitEnd + 7 * DAY);
const events = { start: from.toISOString(), end: to.toISOString() };

const windows = chsCurrentFit.days.map((days) => {
  const result = fit(
    samples.filter((s) => s.time.getTime() >= fitEnd - days * DAY),
    chsCurrentFit.basis,
  );
  const predictor = createCurrentPredictor(result.constituents, {
    floodDirection: series.floodDirection,
    ebbDirection,
    meanFlow: result.offset,
  });
  const slacks = predictor
    .getEventsPrediction({ start: from, end: to })
    .filter((e) => e.kind === "slack" && e.time >= from && e.time < to)
    .map((e) => e.time.toISOString());
  assert.ok(slacks.length >= 20, `${days}-day window predicts only ${slacks.length} slacks`);
  return {
    days,
    offset: result.offset,
    constituents: result.constituents.map(({ name, amplitude, phase }) => ({ name, amplitude, phase })),
    slacks,
  };
});

// `provisionalSlackMaxMinutes` is left out on purpose: it is a publication
// threshold, not a fit input, so changing it changes no fitted model.
const stampOf = (golden) =>
  createHash("sha256")
    .update(
      JSON.stringify({
        speedSeries: chsCurrentFit.speedSeries,
        directionSeries: chsCurrentFit.directionSeries,
        basis: chsCurrentFit.basis,
        days: chsCurrentFit.days,
        sampleMinutes: chsCurrentFit.sampleMinutes,
        projections: recipe.projections.map(({ floodDirection, speeds, directions, expected }) => ({
          floodDirection,
          speeds,
          directions,
          expected,
        })),
        slacks: golden.map(({ days, slacks }) => ({ days, slacks })),
      }),
    )
    .digest("hex")
    .slice(0, 12);

const path = fileURLToPath(new URL("../chs-current-golden.json", import.meta.url));
const previous = existsSync(path) ? JSON.parse(readFileSync(path, "utf8")) : undefined;

// Whether the recorded golden fit still stands for the freshly computed one.
const keepsPrevious =
  previous !== undefined &&
  isDeepStrictEqual(previous.series, series) &&
  isDeepStrictEqual(previous.events, events) &&
  previous.windows.length === windows.length &&
  previous.windows.every(
    (recorded, i) =>
      recorded.days === windows[i].days &&
      recorded.slacks.length === windows[i].slacks.length &&
      recorded.slacks.every(
        (slack, j) => Math.abs(Date.parse(slack) - Date.parse(windows[i].slacks[j])) < TOLERANCE_MS,
      ),
  ) &&
  stampOf(previous.windows) === previous.stamp;

const golden = keepsPrevious
  ? previous
  : {
      source: "Generated by fixtures/generate/gen-chs-current-golden.mjs from @slackwater/engine",
      series,
      events,
      windows,
      stamp: stampOf(windows),
    };

writeJSON(path, golden);
console.log("chsCurrentFit.stamp", golden.stamp);
