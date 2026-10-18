// XTide's slack times, as published on dairiki.org, against CHS's slack events.
// Outside `npm run benchmarks` so CI doesn't scrape a volunteer-run site on every push.
//
//   node benchmarks/xtide-chs-slack.ts [YYYY-MM]
//
// The default month is the one slackwater.xyz/accuracy reports for Sechelt Rapids.
import { mkdir, readFile, writeFile } from "fs/promises";
import { join } from "path";

const __dirname = new URL(".", import.meta.url).pathname;
const IWLS_API = "https://api-iwls.dfo-mpo.gc.ca/api/v1";
const DAIRIKI = "https://www.dairiki.org/tides/monthly.php";

interface Station {
  name: string;
  dairiki: string;
  chs: string;
  timezone: string;
}

const stations: Station[] = [
  { name: "Sechelt Rapids", dairiki: "scl", chs: "07845", timezone: "America/Vancouver" },
];

const month = process.argv[2] ?? "2026-10";
if (!/^\d{4}-\d{2}$/.test(month)) throw new Error(`month must be YYYY-MM, got ${month}`);

await mkdir(".test-cache", { recursive: true });

const rows: string[] = [];
for (const station of stations) {
  const turns = parseTurns(
    await cached(`xtide-${station.dairiki}-${month}.html`, () =>
      fetchText(`${DAIRIKI}/${station.dairiki}/${month}`),
    ),
    station.timezone,
  );
  const events = await chsEvents(station, month);
  const slacks = events.filter((e) => e.qualifier === "SLACK");
  if (turns.length === 0 || slacks.length === 0) {
    throw new Error(`${station.name}: ${turns.length} XTide turns, ${slacks.length} CHS slacks`);
  }

  const matches = slacks.map((slack) => {
    const turn = turns.reduce((a, b) =>
      Math.abs(b - slack.time) < Math.abs(a - slack.time) ? b : a,
    );
    return {
      slack,
      diffMin: (turn - slack.time) / 60_000,
      weakerKn: weakerNeighbour(events, slack),
    };
  });
  const abs = matches.map((m) => Math.abs(m.diffMin)).sort((a, b) => a - b);
  const strong = matches.filter((m) => m.weakerKn >= 4).map((m) => Math.abs(m.diffMin));
  const nearBig = matches.filter((m) => Math.abs(m.diffMin) >= 30).map((m) => m.weakerKn);

  console.log(`\n${station.name}, ${month}`);
  console.log({
    xtideTurns: turns.length,
    chsSlacks: slacks.length,
    within10: abs.filter((x) => x <= 10).length,
    over15: abs.filter((x) => x > 15).length,
    atLeast30: abs.filter((x) => x >= 30).length,
    medianMin: abs[Math.floor(abs.length / 2)],
    maxMin: abs.at(-1),
    bigMissesWeakerSideMaxKn: nearBig.length ? Math.max(...nearBig) : null,
    strongSlacks: strong.length,
    strongOver15: strong.filter((x) => x > 15).length,
    strongMaxMin: strong.length ? Math.max(...strong) : null,
  });

  for (const m of matches) {
    rows.push(
      [
        station.name,
        new Date(m.slack.time).toISOString(),
        m.diffMin.toFixed(0),
        m.weakerKn.toFixed(1),
      ].join(","),
    );
  }
}

await writeFile(
  join(__dirname, "xtide-chs-slack.csv"),
  ["station,chs_slack_utc,xtide_minus_chs_min,weaker_adjacent_max_kn", ...rows].join("\n") + "\n",
);

interface ChsEvent {
  time: number;
  qualifier: string;
  value: number;
}

async function chsEvents(station: Station, month: string): Promise<ChsEvent[]> {
  const list = JSON.parse(
    await cached("iwls-stations.json", () => fetchText(`${IWLS_API}/stations`)),
  );
  const id = list.find((s: { code: string }) => s.code === station.chs)?.id;
  if (!id) throw new Error(`no IWLS station ${station.chs}`);

  // A day either side so the maxima around the month's first and last slacks are present.
  const [y, m] = month.split("-").map(Number);
  const from = new Date(Date.UTC(y, m - 1, 0)).toISOString();
  const to = new Date(Date.UTC(y, m, 2)).toISOString();
  const data = JSON.parse(
    await cached(`iwls-${station.chs}-events-${month}.json`, () =>
      fetchText(
        `${IWLS_API}/stations/${id}/data?time-series-code=wcp1-events&from=${from}&to=${to}`,
      ),
    ),
  );
  const events: ChsEvent[] = data
    .map((e: { eventDate: string; qualifier: string; value: number }) => ({
      time: Date.parse(e.eventDate),
      qualifier: e.qualifier,
      value: e.value,
    }))
    .sort((a: ChsEvent, b: ChsEvent) => a.time - b.time);
  // Keep the padding maxima for weakerNeighbour, but only the month's own slacks.
  return events.filter(
    (e) => e.qualifier !== "SLACK" || localMonth(e.time, station.timezone) === month,
  );
}

// The speed of the weaker of the two runs a slack separates; XTide and CHS disagree most beside weak runs.
function weakerNeighbour(events: ChsEvent[], slack: ChsEvent): number {
  const i = events.indexOf(slack);
  const sides = [events[i - 1], events[i + 1]].filter((e) => e && e.qualifier !== "SLACK");
  return Math.min(...sides.map((e) => Math.abs(e.value)));
}

// A turn is a time cell not followed by a speed cell; maxima carry their speed in a `right` cell.
function parseTurns(html: string, timezone: string): number[] {
  const body = html.slice(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
  const turns: number[] = [];
  let date = "";
  for (const [, row] of body.matchAll(/<tr[^>]*>(.*?)<\/tr>/gs)) {
    date = row.match(/daily\.php\/[^/]+\/(\d{4}-\d{2}-\d{2})/)?.[1] ?? date;
    const cells = [...row.matchAll(/<td([^>]*)>(.*?)<\/td>/gs)];
    cells.forEach(([, , text], i) => {
      if (!/^\d\d:\d\d$/.test(text.trim())) return;
      if (cells[i + 1]?.[1].includes("right")) return;
      turns.push(localToUtc(date, text.trim(), timezone));
    });
  }
  return turns;
}

function localToUtc(date: string, time: string, timezone: string): number {
  const naive = Date.parse(`${date}T${time}:00Z`);
  // Two passes so a guess on the far side of a DST change settles on the right offset.
  const first = naive - offsetMs(naive, timezone);
  return naive - offsetMs(first, timezone);
}

function offsetMs(utc: number, timezone: string): number {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      timeZone: timezone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    })
      .formatToParts(utc)
      .map((p) => [p.type, p.value]),
  );
  return (
    Date.parse(`${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:00Z`) - utc
  );
}

function localMonth(utc: number, timezone: string): string {
  return new Date(utc + offsetMs(utc, timezone)).toISOString().slice(0, 7);
}

async function fetchText(url: string): Promise<string> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} ${url}`);
  return res.text();
}

async function cached(key: string, load: () => Promise<string>): Promise<string> {
  const filePath = `./.test-cache/${key}`;
  try {
    return await readFile(filePath, "utf-8");
  } catch {
    const text = await load();
    await writeFile(filePath, text);
    return text;
  }
}
