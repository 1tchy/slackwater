// Fits CHS tide ports with `chsTideFit` from CHS's own `wlp` predictions and
// scores held-out weeks against them, using the bar in
// docs/validation/chs-tides.md. The fit ends at the start of the current UTC
// month, so each monthly run measures a different season.
import { expect } from "vitest";
import { mkdir, readFile, writeFile } from "fs/promises";
import { createWriteStream } from "fs";
import { join } from "path";
import { chsTideFit, createTidePredictor, fit } from "@slackwater/engine";

const __dirname = new URL(".", import.meta.url).pathname;
const IWLS = "https://api-iwls.dfo-mpo.gc.ca/api/v1";
const DAY = 86_400_000;
const STEP = chsTideFit.sampleMinutes * 60_000;

const FAST = !!process.env.FAST;
// A model never expires on device, and its error follows the seasonal mean
// level, so one held-out week understates it.
const HOLDOUT_DAYS = FAST ? [28] : [28, 90, 180, 270];
const SAMPLE_SIZE = FAST ? 3 : 64;

// The bar, set before scoring. Height clauses take the looser of an absolute
// and a relative limit, because 10 cm is a fifth of a small port's range and a
// rounding error at Saint John.
const HEIGHT_ABS_CM = 10;
const HEIGHT_REL = 0.02;
const TIMING_MEDIAN_MAX_MIN = 20;
const TIMING_WORST_MAX_MIN = 40;
const MATCH_WINDOW_MIN = 180;

await mkdir(".test-cache", { recursive: true });

const now = new Date();
const fitEnd = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1);
const fitStart = fitEnd - chsTideFit.days * DAY;
const stamp = new Date(fitEnd).toISOString().slice(0, 10);

interface IwlsStation {
  id: string;
  code: string;
  officialName: string;
  timeSeries: { code: string }[];
}

// Every Nth station serving both series, in code order, so the sample spreads
// across the coast and stays the same from run to run.
const all: IwlsStation[] = await fetchIwls("stations", "stations");
const eligible = all
  .filter((s) => ["wlp", "wlp-hilo"].every((c) => s.timeSeries.some((t) => t.code === c)))
  .sort((a, b) => a.code.localeCompare(b.code));
const stride = Math.ceil(eligible.length / SAMPLE_SIZE);
const sample = eligible.filter((_, i) => i % stride === 0);

console.log(
  `Fitting ${sample.length} of ${eligible.length} CHS tide ports: ` +
    `${chsTideFit.days} d ending ${stamp}, held out at +${HOLDOUT_DAYS.join(", +")} d`,
);

interface Stat {
  station: string;
  name: string;
  holdout_days: number;
  fit_rms_cm: number;
  range_cm: number;
  bar_cm: number;
  rmse_cm: number;
  bias_cm: number;
  max_abs_cm: number;
  timing_median_min: number;
  timing_worst_min: number;
  height_median_cm: number;
  matched: number;
  events: number;
  pass: boolean;
}

const stats: Stat[] = [];
const skipped: string[] = [];

for (const station of sample) {
  const samples = await series(station, "wlp", fitStart, fitEnd);
  const grid = samples.filter((s) => s.t % STEP === 0);
  if (grid.length < 1 + 2 * chsTideFit.basis.length) {
    skipped.push(station.code);
    continue;
  }
  const result = fit(
    grid.map((s) => ({ time: new Date(s.t), value: s.v })),
    chsTideFit.basis,
  );
  const predictor = createTidePredictor(result.constituents, { offset: result.offset });

  for (const holdout of HOLDOUT_DAYS) {
    const start = fitEnd + holdout * DAY;
    const end = start + 7 * DAY;
    const truth = (await series(station, "wlp", start, end)).filter((s) => s.t % STEP === 0);
    const hilo = await series(station, "wlp-hilo", start, end);
    if (truth.length === 0 || hilo.length < 2) {
      skipped.push(`${station.code}@+${holdout}`);
      continue;
    }

    const errors = truth.map(
      (s) => (predictor.getWaterLevelAtTime({ time: new Date(s.t) }).level - s.v) * 100,
    );
    const rmse = Math.sqrt(mean(errors.map((e) => e * e)));
    const levels = truth.map((s) => s.v);
    const range = (Math.max(...levels) - Math.min(...levels)) * 100;
    const bar = Math.max(HEIGHT_ABS_CM, range * HEIGHT_REL);

    // wlp-hilo has no high/low qualifier, so each event is classified against
    // a neighbour; extremes alternate, so either neighbour will do.
    const extremes = predictor.getExtremesPrediction({
      start: new Date(start - DAY),
      end: new Date(end + DAY),
    });
    const timings: number[] = [];
    const heights: number[] = [];
    hilo.forEach((event, i) => {
      const high = i > 0 ? event.v > hilo[i - 1].v : event.v > hilo[i + 1].v;
      let best: { dt: number; level: number } | undefined;
      for (const e of extremes) {
        if (e.high !== high) continue;
        const dt = Math.abs(e.time.getTime() - event.t) / 60_000;
        if (!best || dt < best.dt) best = { dt, level: e.level };
      }
      if (best && best.dt < MATCH_WINDOW_MIN) {
        timings.push(best.dt);
        heights.push(Math.abs(best.level - event.v) * 100);
      }
    });

    const timingMedian = median(timings);
    const timingWorst = Math.max(...timings);
    const heightMedian = median(heights);
    stats.push({
      station: station.code,
      name: station.officialName,
      holdout_days: holdout,
      fit_rms_cm: result.rms * 100,
      range_cm: range,
      bar_cm: bar,
      rmse_cm: rmse,
      bias_cm: mean(errors),
      max_abs_cm: Math.max(...errors.map(Math.abs)),
      timing_median_min: timingMedian,
      timing_worst_min: timingWorst,
      height_median_cm: heightMedian,
      matched: timings.length,
      events: hilo.length,
      pass:
        timings.length === hilo.length &&
        rmse <= bar &&
        timingMedian <= TIMING_MEDIAN_MAX_MIN &&
        timingWorst <= TIMING_WORST_MAX_MIN &&
        heightMedian <= bar,
    });
  }
  process.stdout.write(".");
}

console.log(`\nSkipped: ${skipped.length ? skipped.join(", ") : "none"}`);

const columns = Object.keys(stats[0] ?? {}) as (keyof Stat)[];
const csv = createWriteStream(join(__dirname, "chs-fit.csv"));
csv.write(columns.join(",") + "\n");
for (const s of stats) {
  csv.write(
    columns
      .map((c) => {
        const v = s[c];
        if (typeof v !== "number") return JSON.stringify(v);
        return Number.isInteger(v) ? String(v) : v.toFixed(2);
      })
      .join(",") + "\n",
  );
}
csv.end();

for (const holdout of HOLDOUT_DAYS) {
  const rows = stats.filter((s) => s.holdout_days === holdout);
  const rmse = rows.map((s) => s.rmse_cm).sort((a, b) => a - b);
  const passing = rows.filter((s) => s.pass).length;
  const p50 = ntile(rmse, 0.5);
  const p90 = ntile(rmse, 0.9);
  console.log(`+${holdout} d:`, { count: rows.length, passing, p50RmseCm: p50, p90RmseCm: p90 });

  expect(rows.length, `+${holdout} d stations scored`).toBeGreaterThan(0);
  // Baseline expectations from the first run (fit ending 2026-10-01) in
  // docs/validation/chs-tides.md, with margin for other seasons. Most misses
  // are the seasonal mean level; move these toward the bar as the recipe
  // improves.
  expect(passing / rows.length, `+${holdout} d share passing`).toBeGreaterThanOrEqual(0.3);
  expect(p50, `+${holdout} d RMSE p50`).toBeLessThan(12);
  expect(p90, `+${holdout} d RMSE p90`).toBeLessThan(25);
}

interface Sample {
  t: number;
  v: number;
}

async function series(station: IwlsStation, code: string, from: number, to: number) {
  const out: Sample[] = [];
  for (let t = from; t < to; t += 7 * DAY) {
    const chunkEnd = Math.min(t + 7 * DAY, to);
    const query = new URLSearchParams({
      "time-series-code": code,
      from: iso(t),
      to: iso(chunkEnd),
      ...(code === "wlp" ? { resolution: "FIFTEEN_MINUTES" } : {}),
    });
    const rows: { eventDate: string; value: number }[] = await fetchIwls(
      `stations/${station.id}/data?${query}`,
      `${station.code}-${code}-${iso(t).slice(0, 10)}`,
    );
    for (const row of rows) {
      const time = new Date(row.eventDate).getTime();
      if (out.at(-1)?.t !== time) out.push({ t: time, v: row.value });
    }
  }
  return out;
}

async function fetchIwls(path: string, cacheKey: string) {
  const filePath = `./.test-cache/chs-fit-${cacheKey}.json`;
  try {
    return await readFile(filePath, "utf-8").then((data) => JSON.parse(data));
  } catch {
    const res = await fetchWithBackoff(`${IWLS}/${path}`);
    const data = await res.json();
    await writeFile(filePath, JSON.stringify(data));
    return data;
  }
}

// IWLS rate-limits long runs partway through; back off and carry on rather
// than lose the run. Requests are spaced like the apps space theirs.
async function fetchWithBackoff(url: string) {
  for (let attempt = 0; attempt < 6; attempt++) {
    const res = await fetch(url);
    await sleep(1000);
    if (res.ok) return res;
    if (res.status !== 429 && res.status < 500) throw new Error(`IWLS ${res.status} for ${url}`);
    await sleep(5000 * 2 ** attempt);
  }
  throw new Error(`IWLS still failing after 6 attempts for ${url}`);
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function iso(ms: number) {
  return new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");
}

function mean(data: number[]) {
  return data.reduce((a, b) => a + b, 0) / data.length;
}

function median(data: number[]) {
  const s = [...data].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

function ntile(data: number[], percent: number) {
  return data[Math.floor(data.length * percent)] ?? NaN;
}
