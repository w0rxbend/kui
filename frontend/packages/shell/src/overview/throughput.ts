/**
 * The Throughput card's data: the wire, the range, and the fold that turns buckets into a chart.
 *
 * ## The wire this file codes against
 *
 * `GET /api/v1/clusters/{clusterId}/metrics/throughput?range=24h|7d|30d` answers a `Section`-wrapped
 * `{throughput: {status, data}}`. When the status carries data, `data` is
 * `{range, from, to, stepSeconds, buckets}` and each bucket is
 * `{startingAt, bytesInPerSecond, bytesOutPerSecond, recordsPerSecond}` with every rate nullable.
 *
 * **A null rate means the step was never sampled.** It is not a zero, and the whole of this module
 * exists to keep those two apart from the moment the JSON is decoded to the moment a bar is drawn.
 * A zero is a measured fact about a quiet cluster; a null is KUI admitting it was not looking. An
 * operator shown a flat line for an hour KUI spent restarting goes and asks their producers why
 * they stopped, which is the most expensive wrong answer this screen can give.
 *
 * ## Why the bucket key is read under two names
 *
 * The committed contract (`services/metrics/contract/.../ThroughputDtos.scala`) calls it
 * `startingAt`; the wave's own contract table writes the same field `at`. They are the same field
 * and only one of them can be on the wire, so both are read and neither is preferred in code that
 * has to keep working across the change. This is not defensive decoding in general — the rest of
 * the payload is read exactly as the Scala writes it — it is one name that is written down twice.
 *
 * ## Why the buckets are drawn one for one and never re-bucketed here
 *
 * `SCREENS-V4.md` §4.1 draws twenty-four paired columns and a 24h range answers 288 of them, so the
 * obvious move is to average twelve buckets into each drawn column. It is the wrong move: a column
 * folded from twelve steps of which one was sampled draws as *measured*, and the eleven gaps inside
 * it disappear. Re-bucketing in the browser would hide exactly the thing this card is for. So every
 * bucket the server sent gets a column, `BarChart` narrows the bars to fit, and the gaps stay where
 * they happened.
 */

import type { KuiApiClient } from "@kui/api";
import { decodeSection } from "@kui/api";
import { apiFailure, fromSection, type Fetched } from "@kui/kernel";

/** The three windows the endpoint offers, in the order the selector draws them. */
export const THROUGHPUT_RANGES = ["24h", "7d", "30d"] as const;

export type ThroughputRange = (typeof THROUGHPUT_RANGES)[number];

/**
 * The range a request with no `?range=` gets, and the one the card opens on.
 *
 * The same value `ThroughputRange.Default` holds on the server. If the two ever disagree the card
 * asks for one window and labels it with another, so the agreement is asserted rather than assumed:
 * see `throughput.test.ts`.
 */
export const DEFAULT_THROUGHPUT_RANGE: ThroughputRange = "24h";

/** The search parameter the selector writes. In the address so a colleague can be sent one. */
export const RANGE_PARAM = "range";

/**
 * How many buckets a full series of each range holds — 24h/5min, 7d/1h, 30d/6h.
 *
 * A mirror of `ThroughputRange.bucketCount`, and it is used **only to check the answer**, never to
 * build the axis. The axis is always the buckets the server actually sent; this table is what lets
 * the card notice that it was sent fewer and say so, which is the browser's half of the rule that a
 * quiet window must draw the same axis as a busy one.
 */
export const BUCKET_COUNT: Readonly<Record<ThroughputRange, number>> = {
  "24h": 288,
  "7d": 168,
  "30d": 120,
};

/** What the segment or query parameter means. An unknown spelling is the default, not an error. */
export function throughputRange(raw: string | undefined): ThroughputRange {
  return THROUGHPUT_RANGES.find((range) => range === raw) ?? DEFAULT_THROUGHPUT_RANGE;
}

/* --- The wire, as far as this card reads it ---------------------------------------------------- */

/**
 * One step of the axis.
 *
 * Every rate is `number | null | undefined` and all three of those mean the same thing here: not
 * measured. `null` is what the server writes, `undefined` is what an older build that omitted the
 * field would leave, and neither may become a zero.
 */
export interface ThroughputBucket {
  readonly startingAt?: string | undefined;
  /** The other spelling of `startingAt`. See the header. */
  readonly at?: string | undefined;
  readonly bytesInPerSecond?: number | null | undefined;
  readonly bytesOutPerSecond?: number | null | undefined;
}

export interface ThroughputSeries {
  readonly range?: string | undefined;
  readonly from?: string | undefined;
  readonly to?: string | undefined;
  /** The width of one bucket. Used for the sentence that counts the gaps, and for nothing else. */
  readonly stepSeconds?: number | undefined;
  readonly buckets?: readonly ThroughputBucket[] | undefined;
}

/* --- The chart ---------------------------------------------------------------------------------- */

/** A run of consecutive buckets that were all measured, or all not. Drawn as the coverage strip. */
export interface CoverageRun {
  readonly measured: boolean;
  readonly length: number;
}

/** Everything the card draws, decided here so the component is arrangement. */
export interface ThroughputChart {
  /** One label per bucket. Its length is the axis, and it is the server's count, never a target. */
  readonly categories: readonly string[];
  /** Bytes in per second, per bucket. `null` is a gap. */
  readonly produce: readonly (number | null)[];
  /** Bytes out per second, per bucket. `null` is a gap. */
  readonly consume: readonly (number | null)[];
  /** Which categories get a printed tick: five, as `SCREENS-V4.md` §4.1 draws them. */
  readonly ticks: readonly number[];
  /** The gaps, in drawing order, so a reader can see *where* KUI was not looking. */
  readonly coverage: readonly CoverageRun[];
  /** How many buckets carried neither rate. */
  readonly absentBuckets: number;
  /** The newest bucket that carried a rate, for the legend chips. `undefined` when none did. */
  readonly latest: { readonly produce: number | null; readonly consume: number | null } | undefined;
  /** The sentence under the chart, or `undefined` when there is nothing to say. */
  readonly caption: string | undefined;
}

/** A rate the server sent, or `null` when it sent nothing. Never `0` for an absent measurement. */
function rate(value: number | null | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/**
 * The category label for one bucket, in the browser's own zone.
 *
 * The zone is the browser's because nothing in KUI stores a timezone preference — `SettingsPage`'s
 * header says why there is no control for one — so a label in any other zone would be a claim the
 * rest of the product does not make. Each range gets the coarsest label that still separates two
 * adjacent buckets: minutes over a day, the weekday over a week, the date over a month.
 */
function labelFor(instant: string | undefined, range: ThroughputRange): string {
  if (instant === undefined) return "";
  const at = new Date(instant);
  if (Number.isNaN(at.getTime())) return "";
  switch (range) {
    case "24h":
      return at.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
    case "7d":
      return at.toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" });
    case "30d":
      return at.toLocaleDateString(undefined, { day: "numeric", month: "short" });
  }
}

/**
 * Five ticks rather than `defaultTicks`'s three.
 *
 * §4.1 labels the throughput axis `00:00 · 06:00 · 12:00 · 18:00 · now`, which is four quarters and
 * an end. The kernel's default is first, middle and last, which is the right default for a card
 * narrow enough that five labels would collide and the wrong one for the widest card on the page.
 */
export function axisTicks(count: number): readonly number[] {
  if (count <= 0) return [];
  if (count <= 5) return Array.from({ length: count }, (_unused, index) => index);
  const last = count - 1;
  return [0, Math.round(last * 0.25), Math.round(last * 0.5), Math.round(last * 0.75), last];
}

/**
 * The gaps, as runs.
 *
 * A run rather than a cell per bucket, because a 30-day series is 120 cells and a 24-hour one is
 * 288, and the strip has to align with a chart that divides its width equally between buckets — so
 * a run of `n` buckets is a box with `flex-grow: n` and the alignment is arithmetic rather than a
 * measurement. A bucket is measured when *either* rate was measured: a source that publishes bytes
 * in and not bytes out is an ordinary exporter configuration, and calling that step unmeasured
 * would report a gap that is not there.
 */
export function coverageRuns(
  produce: readonly (number | null)[],
  consume: readonly (number | null)[],
): readonly CoverageRun[] {
  const runs: CoverageRun[] = [];
  for (let index = 0; index < produce.length; index += 1) {
    const measured = produce[index] !== null || (consume[index] ?? null) !== null;
    const last = runs[runs.length - 1];
    if (last !== undefined && last.measured === measured) {
      runs[runs.length - 1] = { measured, length: last.length + 1 };
    } else {
      runs.push({ measured, length: 1 });
    }
  }
  return runs;
}

/** How long one step is, in words, from the width the server sent. `undefined` when it sent none. */
function stepWords(stepSeconds: number | undefined): string | undefined {
  if (stepSeconds === undefined || !Number.isFinite(stepSeconds) || stepSeconds <= 0) return undefined;
  if (stepSeconds % 3600 === 0) {
    const hours = stepSeconds / 3600;
    return hours === 1 ? "one-hour" : `${hours}-hour`;
  }
  if (stepSeconds % 60 === 0) {
    const minutes = stepSeconds / 60;
    return minutes === 1 ? "one-minute" : `${minutes}-minute`;
  }
  return `${stepSeconds}-second`;
}

/**
 * The sentence under the chart.
 *
 * Two facts can need saying and they are said in one line rather than two, because a card with two
 * captions reads as a card that has gone wrong. Neither is decoration: the gap count is what makes
 * a blank column legible as "not measured" rather than as "nothing happened", and the short-axis
 * clause is the browser noticing that the server broke the one rule this chart's shape rests on —
 * that a range's bucket count is constant whatever was sampled.
 */
function captionFor(
  range: ThroughputRange,
  drawn: number,
  absent: number,
  stepSeconds: number | undefined,
): string | undefined {
  const parts: string[] = [];
  const expected = BUCKET_COUNT[range];
  if (drawn !== expected) {
    parts.push(
      `This build answered ${drawn} steps where a ${range} range holds ${expected}, so the axis is ` +
        `shorter than the window it is labelled with.`,
    );
  }
  if (absent > 0) {
    const step = stepWords(stepSeconds);
    const steps = step === undefined ? "steps" : `${step} steps`;
    parts.push(
      `${absent} of the ${drawn} ${steps} in this window were never sampled, and are drawn blank ` +
        `rather than as a rate of zero.`,
    );
  }
  return parts.length === 0 ? undefined : parts.join(" ");
}

/**
 * The wire's series as the picture, and the one line in this file that matters.
 *
 * `rate()` is what keeps a gap a gap. Replacing either of its two calls with `?? 0` turns every
 * unsampled step into a measured quiet one, and the chart, the coverage strip and the hidden data
 * table all stop being able to tell the difference — which is the mutation this card is gated on.
 */
export function throughputChart(series: ThroughputSeries, range: ThroughputRange): ThroughputChart {
  const buckets = series.buckets ?? [];
  const categories = buckets.map((bucket) => labelFor(bucket.startingAt ?? bucket.at, range));
  const produce = buckets.map((bucket) => rate(bucket.bytesInPerSecond));
  const consume = buckets.map((bucket) => rate(bucket.bytesOutPerSecond));
  const coverage = coverageRuns(produce, consume);
  const absentBuckets = coverage.reduce((sum, run) => (run.measured ? sum : sum + run.length), 0);

  /* The newest bucket that measured anything, which is what the legend chips print. Newest rather
     than an average over the window: §3.1 puts the *current* value in the chip, and an average
     across a day is a number nobody can act on. */
  let latest: ThroughputChart["latest"] = undefined;
  for (let index = buckets.length - 1; index >= 0; index -= 1) {
    const producedAt = produce[index] ?? null;
    const consumedAt = consume[index] ?? null;
    if (producedAt !== null || consumedAt !== null) {
      latest = { produce: producedAt, consume: consumedAt };
      break;
    }
  }

  return {
    categories,
    produce,
    consume,
    ticks: axisTicks(categories.length),
    coverage,
    absentBuckets,
    latest,
    caption: captionFor(range, buckets.length, absentBuckets, series.stepSeconds),
  };
}

/**
 * Whether this series measured anything at all.
 *
 * Cheap on purpose: the voice line above the tab asks this on every render, and building the whole
 * chart to answer it would fold 288 buckets into three arrays to find out whether one of them held
 * a number. It stops at the first measured rate.
 */
export function hasMeasuredBucket(series: ThroughputSeries): boolean {
  return (series.buckets ?? []).some(
    (bucket) => rate(bucket.bytesInPerSecond) !== null || rate(bucket.bytesOutPerSecond) !== null,
  );
}

/* --- Asking for it ------------------------------------------------------------------------------ */

/**
 * The key one request is cached under.
 *
 * Both the cluster and the range are in it, which is `useQuery`'s whole contract: a range left out
 * would mean the selector changed the address, changed the label and drew the previous window's
 * bars out of the cache.
 */
export function throughputKey(clusterId: string, range: ThroughputRange): string {
  return `metrics-throughput:${clusterId}:${range}`;
}

/**
 * Asks for one cluster's throughput.
 *
 * Never rejects, by the same rule the rest of `@kui/api` follows. The four section statuses that do
 * not carry data become the four `Fetched` cases that say why, and `not_configured` in particular
 * stays its own case all the way to the card: it is the common, fully supported answer for a
 * deployment that has configured no exporter, and turning it into a failure would put a red panel
 * and a retry button in front of every operator who never asked for metrics.
 */
export async function fetchThroughput(
  api: KuiApiClient,
  clusterId: string,
  range: ThroughputRange,
): Promise<Fetched<ThroughputSeries>> {
  const answer = await api.get("/api/v1/clusters/{clusterId}/metrics/throughput", {
    params: { path: { clusterId }, query: { range } },
  });
  if (!answer.ok) return apiFailure(answer.error);

  const body = answer.value as { throughput?: unknown } | null;
  if (body === null || body.throughput === undefined) {
    return {
      kind: "failed",
      message: "KUI could not read the throughput: the server sent something other than a series.",
      code: "UNREADABLE_BODY",
    };
  }
  return fromSection(decodeSection<ThroughputSeries>(body.throughput), (data) => data);
}
