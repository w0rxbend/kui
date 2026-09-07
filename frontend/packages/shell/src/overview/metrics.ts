/**
 * The Traffic tab's other four wires: latency, request handlers, top producers and record size.
 *
 * ## What this file codes against, and why it is a stated contract rather than a diff
 *
 * The four endpoints are `services/metrics`' (W5-01) and land beside this file rather than before
 * it, so this module is written against the shape both sides agreed in wave 5's plan and in
 * ADR-052: each answers a `Section`-wrapped document under one named key, `status` is one of
 * `ok | stale | unavailable | not_configured | forbidden`, and where a series is answered it
 * carries **exactly `bucketCount` entries for the range**, with a never-sampled bucket written
 * `null`. Those are the same rules the throughput card already keeps, and `throughput.ts` sets out
 * at length why the difference between a `null` and a `0` is the whole point of the screen.
 *
 * ## Three of the design's figures a broker does not publish, and what is drawn instead
 *
 * ADR-052 decides these; this module carries the browser's half of each decision, because a card
 * that quietly became a different measurement is the most expensive kind of wrong on a screen whose
 * promise is that it says what it knows.
 *
 *  - **Purgatory is a queue length, not a percentage.** `SCREENS-V4.md` §3.4 draws "38% PURGATORY".
 *    `DelayedOperationPurgatory.PurgatorySize` is a count with no ceiling, and a count divided by an
 *    invented ceiling is a fabricated percentage. So a reading arrives as *either* a `ratio` or a
 *    `count`, and {@link handlerPanel} keeps them apart: a ratio draws a ring, a count draws its
 *    figure and its unit and never a ring.
 *  - **Top producers may be topics rather than clients.** §4 draws "Top producers · client.id" and a
 *    broker publishes no per-`client.id` byte rate unless quotas are configured; it publishes a
 *    per-*topic* `BytesInPerSec`. So the entry names what it holds — `clientId` or `topic` — and
 *    {@link producerBoard} reports which, so the card's title can say the same word the server did.
 *    A field called `clientId` carrying a topic name is the defect wave 5's rule 7 exists to stop.
 *  - **There is no record-size distribution.** §3.5 draws twelve buckets and `p50 · 1.1 KB` /
 *    `p99 · 18 KB` / `max · 0.9 MB` chips. Kafka publishes a *mean* — bytes-in over messages-in —
 *    and nothing else. So {@link recordSizeReadout} carries a mean and this module builds no
 *    histogram from it: twelve buckets assembled from one number is a drawing of an assumption.
 *
 * ## Why every field is read optionally
 *
 * The same reason `throughput.ts` gives: the generated browser types stop at `unknown` inside a
 * `Section`, so these shapes are hand-transcribed and are deliberately narrow. A field this build
 * does not understand is absent, and absent is drawn as absent — never as zero.
 */

import type { ApiError, KuiApiClient } from "@kui/api";
import { decodeSection } from "@kui/api";
import { apiFailure, fromSection, type Fetched } from "@kui/kernel";

import { BUCKET_COUNT, axisTicks, bucketLabel, stepWords, type ThroughputRange } from "./throughput.js";

/**
 * The window vocabulary is the throughput card's, unchanged.
 *
 * One selector in the address drives two charts, so a reader comparing a latency spike against a
 * throughput spike is looking at the same hours. A second vocabulary would let the two cards on one
 * screen be labelled with different windows.
 */
export type MetricsWindow = ThroughputRange;

/** How many rows `…/metrics/producers?top=` is asked for. §4.1 draws a handful, not a table. */
export const TOP_PRODUCERS = 5;

/* --- Asking, once, for all four ---------------------------------------------------------------- */

/**
 * A GET for a path the generated browser types do not carry yet.
 *
 * `KuiApiClient.get` is typed from `frontend/packages/api/src/schema.d.ts`, which is regenerated
 * from `docs/api/openapi.browser.json` — and those two files are W5-09's, regenerated *after* the
 * endpoints and their gateway routes land. Until that happens the four paths below are not in the
 * type, so a checked call to them does not compile, and this shell package cannot wait for a
 * document it does not own.
 *
 * So the erasure is here, in one place, named, rather than spread across four fetchers as four
 * `as any`s. It is a real hole and it is disclosed as one: once `schema.d.ts` names these paths the
 * cast can be deleted and the four calls below type-check like `fetchThroughput`'s does.
 */
interface UncheckedParams {
  readonly path: Record<string, string>;
  readonly query?: Record<string, string>;
}

type UncheckedAnswer =
  | { readonly ok: true; readonly value: unknown }
  | { readonly ok: false; readonly error: ApiError };

type UncheckedGet = (
  path: string,
  init: { readonly params: UncheckedParams },
) => Promise<UncheckedAnswer>;

/**
 * One cluster-scoped metrics read, from the request to a `Fetched`.
 *
 * Shared by all four because all four fail in the same six ways, and a per-endpoint copy of this
 * would be four chances to fold `not_configured` into `failed` — the collapse that puts a red panel
 * and a Retry button in front of every operator who never configured an exporter.
 *
 * @param key the field the section is wrapped in, as the service writes it
 * @param alias the same field under its other spelling; see the note on `startingAt` in `throughput.ts`
 */
async function readMetric<T>(
  api: KuiApiClient,
  path: string,
  clusterId: string,
  query: Record<string, string> | undefined,
  key: string,
  alias: string,
  noun: string,
): Promise<Fetched<T>> {
  const get = api.get as unknown as UncheckedGet;
  const answer = await get(path, {
    params: query === undefined ? { path: { clusterId } } : { path: { clusterId }, query },
  });
  if (!answer.ok) return apiFailure(answer.error);

  const body = answer.value as Record<string, unknown> | null;
  const section = body === null ? undefined : (body[key] ?? body[alias]);
  if (section === undefined) {
    return {
      kind: "failed",
      message: `KUI could not read ${noun}: the server sent something other than a ${noun}.`,
      code: "UNREADABLE_BODY",
    };
  }
  return fromSection(decodeSection<T>(section), (data) => data);
}

/* --- Latency ------------------------------------------------------------------------------------ */

/**
 * One step of the latency axis.
 *
 * `produceP99Millis` is the committed spelling and `produceP99` is the same field written the other
 * way in the wave's own contract table. Both are read and neither is preferred, exactly as
 * `startingAt`/`at` are on the throughput bucket — this is one name written down twice, not
 * defensive decoding in general.
 */
export interface LatencyBucket {
  readonly startingAt?: string | undefined;
  readonly at?: string | undefined;
  readonly produceP99Millis?: number | null | undefined;
  readonly produceP99?: number | null | undefined;
  readonly fetchP99Millis?: number | null | undefined;
  readonly fetchP99?: number | null | undefined;
}

export interface LatencySeries {
  readonly range?: string | undefined;
  readonly stepSeconds?: number | undefined;
  readonly buckets?: readonly LatencyBucket[] | undefined;
}

/** Everything the latency card draws, decided here so the component is arrangement. */
export interface LatencyChart {
  readonly categories: readonly string[];
  /** Produce p99, in milliseconds, per bucket. `null` is a gap and is never plotted as zero. */
  readonly produce: readonly (number | null)[];
  readonly fetch: readonly (number | null)[];
  readonly ticks: readonly number[];
  readonly absentBuckets: number;
  /** The newest bucket that measured anything, which is what §3.1 puts in the legend chips. */
  readonly latest: { readonly produce: number | null; readonly fetch: number | null } | undefined;
  readonly caption: string | undefined;
}

/** A reading the server sent, or `null` when it sent nothing. Never `0` for an absent measurement. */
function millis(...candidates: readonly (number | null | undefined)[]): number | null {
  for (const candidate of candidates) {
    if (typeof candidate === "number" && Number.isFinite(candidate)) return candidate;
  }
  return null;
}

/**
 * The sentence under the latency chart.
 *
 * The same two facts the throughput caption carries and deliberately not the same words: a step
 * nothing sampled is drawn blank rather than as *zero latency*, which is a different — and much
 * more reassuring — false claim than a rate of zero.
 */
function latencyCaption(
  range: MetricsWindow,
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
        `rather than as zero latency.`,
    );
  }
  return parts.length === 0 ? undefined : parts.join(" ");
}

/**
 * The wire's series as the picture.
 *
 * `millis()` is what keeps a gap a gap, and it is the one line in this function that matters:
 * replacing either call with `?? 0` draws a broker that stopped answering as a broker answering
 * instantly, which is the most reassuring possible rendering of "we were not looking".
 */
export function latencyChart(series: LatencySeries, range: MetricsWindow): LatencyChart {
  const buckets = series.buckets ?? [];
  const categories = buckets.map((bucket) => bucketLabel(bucket.startingAt ?? bucket.at, range));
  const produce = buckets.map((bucket) => millis(bucket.produceP99Millis, bucket.produceP99));
  const fetch = buckets.map((bucket) => millis(bucket.fetchP99Millis, bucket.fetchP99));

  let absentBuckets = 0;
  for (let index = 0; index < buckets.length; index += 1) {
    if (produce[index] === null && fetch[index] === null) absentBuckets += 1;
  }

  /* Newest measured rather than last, for the reason the throughput legend gives: the final bucket
     of a live series is very often the one still being filled, and reading it blindly prints the
     current latency as absent on a cluster that is answering perfectly well. */
  let latest: LatencyChart["latest"] = undefined;
  for (let index = buckets.length - 1; index >= 0; index -= 1) {
    const p = produce[index] ?? null;
    const f = fetch[index] ?? null;
    if (p !== null || f !== null) {
      latest = { produce: p, fetch: f };
      break;
    }
  }

  return {
    categories,
    produce,
    fetch,
    ticks: axisTicks(categories.length),
    absentBuckets,
    latest,
    caption: latencyCaption(range, buckets.length, absentBuckets, series.stepSeconds),
  };
}

/** Whether this window measured a latency at all. Cheap: it stops at the first reading. */
export function hasLatencyReading(series: LatencySeries): boolean {
  return (series.buckets ?? []).some(
    (bucket) =>
      millis(bucket.produceP99Millis, bucket.produceP99) !== null ||
      millis(bucket.fetchP99Millis, bucket.fetchP99) !== null,
  );
}

export function latencyKey(clusterId: string, range: MetricsWindow): string {
  return `metrics-latency:${clusterId}:${range}`;
}

/**
 * Asks for one cluster's p99 latency over one window.
 *
 * The wire's parameter is `window=` and the address's is `?range=`; they carry the same three
 * spellings and the browser resolves the address before it asks, so an unrecognised `?range=`
 * cannot reach the server.
 */
export async function fetchLatency(
  api: KuiApiClient,
  clusterId: string,
  range: MetricsWindow,
): Promise<Fetched<LatencySeries>> {
  return readMetric<LatencySeries>(
    api,
    "/api/v1/clusters/{clusterId}/metrics/latency",
    clusterId,
    { window: range },
    "latency",
    "latency",
    "the latency series",
  );
}

/* --- Request handlers --------------------------------------------------------------------------- */

/**
 * One reading of the **Request handlers** card (§3.4), and the shape carries this wave's rule 7.
 *
 * A reading is *either* a `ratio` — a fraction in 0..1, which is what
 * `RequestHandlerAvgIdlePercent` and `NetworkProcessorAvgIdlePercent` publish — *or* a `count`,
 * which is what `PurgatorySize` publishes. It is never both, and a `count` never becomes a
 * percentage: there is no ceiling to divide it by, and inventing one is a fabricated figure.
 */
export interface HandlerReading {
  readonly id?: string | undefined;
  readonly label?: string | undefined;
  /** A fraction in 0..1. Not a pre-formatted percentage: the card decides how to print it. */
  readonly ratio?: number | null | undefined;
  readonly count?: number | null | undefined;
  /** What a `count` is counted in — `requests`, `operations`. Printed beside the figure. */
  readonly unit?: string | undefined;
  /** Which end of the domain is the good end. See {@link handlerPanel} for the default and why. */
  readonly goodDirection?: "high" | "low" | undefined;
}

export interface HandlerDocument {
  readonly readings?: readonly HandlerReading[] | undefined;
}

/** One reading, decided: a ring with a percentage, or a figure with a unit and no ring. */
export interface HandlerGauge {
  readonly id: string;
  readonly caption: string;
  /** `"ratio"` draws a ring; `"count"` draws a figure and its unit, and never a ring. */
  readonly kind: "ratio" | "count";
  /** The percentage the ring reads, 0..100. `undefined` for a count and for an unmeasured ratio. */
  readonly percent: number | undefined;
  /** The figure a count prints. `undefined` when the count was not measured. */
  readonly count: number | undefined;
  readonly unit: string | undefined;
  readonly goodDirection: "high" | "low";
}

export interface HandlerPanel {
  readonly gauges: readonly HandlerGauge[];
  /** Said in words when a reading is a count, because a count in a row of rings needs explaining. */
  readonly caption: string | undefined;
}

/** A label for a reading that arrived without one. Never blank: a gauge with no caption is a number. */
function handlerCaption(reading: HandlerReading, index: number): string {
  return reading.label ?? reading.id ?? `Reading ${index + 1}`;
}

/**
 * Turns the endpoint's readings into gauges.
 *
 * ## The two decisions in here
 *
 * **A ratio is a fraction and the card multiplies it.** W5-01's endpoint answers ratios rather than
 * pre-formatted percentages precisely so that this decision is made once, here, where it is
 * testable — a server that sent `64` and a server that sent `0.64` would otherwise both draw
 * something plausible.
 *
 * **`goodDirection` defaults to `"high"`, and only because of what this endpoint publishes.**
 * `RingGauge` refuses a default of its own, for the good reason its header gives: a wrong guess
 * paints an incident green. The default here is not a guess about gauges in general — it is a fact
 * about *this* document, every reading of which is an **idle** ratio, where more idle is more
 * headroom. A reading whose good end is the low end says so on the wire, and the wire wins.
 */
export function handlerPanel(document: HandlerDocument): HandlerPanel {
  const gauges = (document.readings ?? []).map((reading, index): HandlerGauge => {
    const finite = (value: number | null | undefined): number | undefined =>
      typeof value === "number" && Number.isFinite(value) ? value : undefined;
    const ratio = finite(reading.ratio);
    const count = finite(reading.count);
    const isCount =
      reading.ratio === undefined && (reading.count !== undefined || reading.unit !== undefined);
    return {
      id: reading.id ?? `reading-${index}`,
      caption: handlerCaption(reading, index),
      kind: isCount ? "count" : "ratio",
      percent: isCount || ratio === undefined ? undefined : ratio * 100,
      count: isCount ? count : undefined,
      unit: isCount ? (reading.unit ?? "") : undefined,
      goodDirection: reading.goodDirection ?? "high",
    };
  });

  /* The sentence the design does not have, and the one §3.4's "38% PURGATORY" made necessary. A
     queue length sitting in a row of rings looks like a ring that failed to draw unless somebody
     says why it is not one. */
  const counts = gauges.filter((gauge) => gauge.kind === "count");
  const caption =
    counts.length === 0
      ? undefined
      : `${counts.map((gauge) => gauge.caption).join(" and ")} ` +
        `${counts.length === 1 ? "is a" : "are"} ` +
        `queue ${counts.length === 1 ? "length" : "lengths"} and not a share of anything, so ${
          counts.length === 1 ? "it is" : "they are"
        } drawn as a count rather than as a ring.`;

  return { gauges, caption };
}

export function requestHandlersKey(clusterId: string): string {
  return `metrics-request-handlers:${clusterId}`;
}

export async function fetchRequestHandlers(
  api: KuiApiClient,
  clusterId: string,
): Promise<Fetched<HandlerDocument>> {
  return readMetric<HandlerDocument>(
    api,
    "/api/v1/clusters/{clusterId}/metrics/request-handlers",
    clusterId,
    undefined,
    "requestHandlers",
    "request-handlers",
    "the request-handler readings",
  );
}

/* --- Top producers ------------------------------------------------------------------------------ */

/**
 * One row of the **Top producers** card, named for what it holds.
 *
 * Exactly one of `clientId` and `topic` is set, and which one it is decides the card's title. This
 * is the browser's half of wave 5's rule 7: the design asked for `client.id`, a broker publishes a
 * per-topic byte rate unless quotas are configured, and a tile labelled `client.id` over a topic
 * name is the drift the rule exists to stop.
 */
export interface ProducerEntry {
  readonly clientId?: string | undefined;
  readonly topic?: string | undefined;
  readonly bytesPerSecond?: number | null | undefined;
}

export interface ProducerDocument {
  readonly entries?: readonly ProducerEntry[] | undefined;
}

export interface ProducerRow {
  readonly id: string;
  /** Bytes in per second. `undefined` where the exporter served the name and not the rate. */
  readonly bytesPerSecond: number | undefined;
}

export interface ProducerBoard {
  readonly rows: readonly ProducerRow[];
  /** What the rows are: the word the *server* used, which is the word the card's title says. */
  readonly subject: "client.id" | "topic" | "producer";
}

/**
 * Reads the rows and reports what they are.
 *
 * `subject` is derived from the field the server actually filled rather than from a constant here,
 * so a deployment that does configure client quotas draws `client.id` and one that does not draws
 * `topic`, with no second place for the two to disagree. A document whose entries name neither is
 * `producer` — a heading that claims nothing, which is the honest answer to a row this build does
 * not recognise.
 */
export function producerBoard(document: ProducerDocument): ProducerBoard {
  const entries = document.entries ?? [];
  const rows = entries.flatMap((entry): readonly ProducerRow[] => {
    const id = entry.clientId ?? entry.topic;
    if (id === undefined || id.length === 0) return [];
    const rate = entry.bytesPerSecond;
    return [
      {
        id,
        bytesPerSecond: typeof rate === "number" && Number.isFinite(rate) ? rate : undefined,
      },
    ];
  });

  const named = entries.find((entry) => entry.clientId !== undefined || entry.topic !== undefined);
  const subject: ProducerBoard["subject"] =
    named === undefined ? "producer" : named.clientId !== undefined ? "client.id" : "topic";

  return { rows, subject };
}

export function producersKey(clusterId: string, top: number): string {
  return `metrics-producers:${clusterId}:${top}`;
}

export async function fetchTopProducers(
  api: KuiApiClient,
  clusterId: string,
  top: number = TOP_PRODUCERS,
): Promise<Fetched<ProducerDocument>> {
  return readMetric<ProducerDocument>(
    api,
    "/api/v1/clusters/{clusterId}/metrics/producers",
    clusterId,
    { top: String(top) },
    "producers",
    "topProducers",
    "the top producers",
  );
}

/* --- Record size -------------------------------------------------------------------------------- */

/**
 * What a broker publishes about record size, which is one number.
 *
 * `meanBytes` is bytes-in over messages-in. There is no `buckets` field and there is not going to
 * be one: §3.5's twelve-bucket distribution has no source in Kafka's JMX surface at all, and this
 * card draws the mean it has beside a sentence naming the distribution it does not.
 */
export interface RecordSizeDocument {
  readonly meanBytes?: number | null | undefined;
  /** Over what window the mean was taken, in words, when the service says. */
  readonly window?: string | undefined;
}

export interface RecordSizeReadout {
  readonly meanBytes: number | undefined;
  readonly window: string | undefined;
}

export function recordSizeReadout(document: RecordSizeDocument): RecordSizeReadout {
  const mean = document.meanBytes;
  return {
    meanBytes: typeof mean === "number" && Number.isFinite(mean) ? mean : undefined,
    window: document.window,
  };
}

export function recordSizeKey(clusterId: string): string {
  return `metrics-record-size:${clusterId}`;
}

export async function fetchRecordSize(
  api: KuiApiClient,
  clusterId: string,
): Promise<Fetched<RecordSizeDocument>> {
  return readMetric<RecordSizeDocument>(
    api,
    "/api/v1/clusters/{clusterId}/metrics/record-size",
    clusterId,
    undefined,
    "recordSize",
    "record-size",
    "the record size",
  );
}

/* --- Printing a duration ------------------------------------------------------------------------ */

/**
 * A latency in words.
 *
 * Local rather than in `@kui/kernel` because this is the first duration the product prints and one
 * caller is not a shared concern; the kernel's `numbers.ts` is where it moves when a second screen
 * needs it. The precision drops as the magnitude rises for the reason `formatBytes` does the same:
 * `0.42 ms` and `1.2 s` are both readable, and `1234.5678 ms` is a number nobody reads.
 */
export function formatMillis(value: number): string {
  if (!Number.isFinite(value)) return "—";
  if (value >= 1000) return `${(value / 1000).toFixed(2)} s`;
  if (value >= 100) return `${Math.round(value)} ms`;
  if (value >= 1) return `${value.toFixed(1)} ms`;
  return `${value.toFixed(2)} ms`;
}
