/**
 * The Traffic tab's other four wires: latency, request handlers, top producers and record size.
 *
 * ## What this file codes against, and why it is a document rather than a prose contract
 *
 * The four endpoints are `services/metrics`', and this module was originally written against a
 * shape described in prose in a wave plan. Two of the four descriptions were wrong, both sides
 * shipped green, and two cards drew a confident false sentence about the source for a whole
 * milestone. So the shapes below are transcribed from `services/metrics/contract`'s DTOs and are
 * held to them by `wire.golden.test.ts`, which decodes the documents the server's own encoder
 * renders.
 *
 * The rules that are the same for all four: each answers a `Section`-wrapped document under one
 * named key, `status` is one of `ok | stale | unavailable | not_configured | forbidden`, and where
 * a series is answered it carries **exactly `bucketCount` entries for the range**, with a
 * never-sampled bucket written `null`. `stale` is reachable and is not dead render code: a gauge
 * whose newest scrape is older than one `kui.metrics.scrapeInterval` answers `stale` carrying the
 * figure and the instant it was taken (ADR-052), so a card can show a true number without claiming
 * it is current. `throughput.ts` sets out at length why the difference between a `null` and a `0`
 * is the whole point of the screen.
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
 *  - **Top producers are topics rather than clients.** §4 draws "Top producers · client.id" and a
 *    broker publishes no per-`client.id` byte rate unless quotas are configured; it publishes a
 *    per-*topic* `BytesInPerSec`. So the server sends `measuredBy` beside the rows and
 *    {@link producerBoard} reads it, so the card's title says the same word the server did. A field
 *    called `clientId` carrying a topic name is the defect wave 5's rule 7 exists to stop.
 *  - **There is no record-size distribution.** §3.5 draws twelve buckets and `p50 · 1.1 KB` /
 *    `p99 · 18 KB` / `max · 0.9 MB` chips. Kafka publishes a *mean* — bytes-in over messages-in —
 *    and nothing else. So {@link recordSizeReadout} carries a mean and this module builds no
 *    histogram from it: twelve buckets assembled from one number is a drawing of an assumption.
 *
 * ## Why every field is read optionally, and what stops the shapes drifting again
 *
 * The generated browser types stop at `unknown` inside a `Section` — `Section`'s Tapir schema is
 * `Schema.any`, so `schema.d.ts` types all five payloads opaque — and these shapes are therefore
 * hand-transcribed and deliberately narrow. A field this build does not understand is absent, and
 * absent is drawn as absent, never as zero.
 *
 * Hand-transcribing is exactly how two of the four came to be wrong, so it is no longer the only
 * thing holding the wire together: `services/metrics/contract/test/resources/golden/*.json` are
 * documents rendered by the **server's own encoder**, and `wire.golden.test.ts` runs them through
 * the fetchers below. A shape that drifts from the service now fails a browser case rather than
 * quietly answering an empty array.
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
 * One delayed-operation purgatory, exactly as `RequestHandlerDtos.scala` writes it.
 *
 * A **count** of parked requests, not a percentage. `SCREENS-V4.md` §3.4 draws "38% PURGATORY";
 * `DelayedOperationPurgatory.PurgatorySize` is a queue length with no ceiling to divide it by, and a
 * count over an invented denominator is a fabricated figure. ADR-052 decides it; this is the shape
 * that decision has on the wire.
 */
export interface PurgatoryQueue {
  readonly operation?: string | undefined;
  readonly delayedRequests?: number | null | undefined;
}

/**
 * The request-handlers document, and the shape this module used to get wrong.
 *
 * Until wave 6 this file read `data.readings[]` of `{id, label, ratio, count, unit}` — a shape no
 * service has ever sent. The top-level `Section` key matched, so `readMetric` unwrapped happily,
 * `readings` was `undefined`, `handlerPanel` answered zero gauges and the card drew *"The metrics
 * source answered and served no request-handler readings"* over a source that served three. Both
 * sides had unit cases against their own literal; neither had a document they were both asserted
 * against. The fields below are the server's own, and `wire.golden.test.ts` decodes the encoder's
 * output rather than a literal written here.
 *
 * Both ratios are fractions in `0..1` — `RequestHandlerAvgIdlePercent` and
 * `NetworkProcessorAvgIdlePercent` as the broker publishes them, not pre-formatted percentages —
 * and `null` means the exporter named the reading and did not measure it. `null` is not `0`, which
 * would say the pool was saturated.
 */
export interface HandlerDocument {
  readonly requestHandlerIdleRatio?: number | null | undefined;
  readonly networkProcessorIdleRatio?: number | null | undefined;
  readonly purgatory?: readonly PurgatoryQueue[] | undefined;
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

/** What a count is counted in, printed beside the figure. The broker parks *requests*. */
const PURGATORY_UNIT = "requests";

/** A finite number, or nothing. A `null`, a `NaN` and an absent field are all "not measured". */
function finite(value: number | null | undefined): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/**
 * Turns the endpoint's document into the tiles §3.4 draws.
 *
 * ## The three decisions in here
 *
 * **A ratio is a fraction and the card multiplies it.** The endpoint answers ratios rather than
 * pre-formatted percentages precisely so that this decision is made once, here, where it is
 * testable — a server that sent `64` and a server that sent `0.64` would otherwise both draw
 * something plausible.
 *
 * **A ratio the document names and does not measure still gets its tile.** `RingGauge` paints the
 * plain track and an em dash for an `undefined` value, which is §3.4's own absent rule, so a reader
 * can see *which* reading is missing rather than counting the tiles that are there. A ratio the
 * document does not mention at all — an older or a different server — gets no tile, which is what
 * keeps {@link HandlerPanel.gauges} able to be empty and the card's sentence reachable.
 *
 * **`goodDirection` is `"high"` for both ratios, and only because of what they are.** `RingGauge`
 * refuses a default of its own, for the good reason its header gives: a wrong guess paints an
 * incident green. This is not a guess about gauges in general — both readings on this document are
 * **idle** ratios, where more idle is more headroom.
 *
 * The order is the design's: NETWORK IDLE, IO IDLE, then the purgatories.
 */
export function handlerPanel(document: HandlerDocument): HandlerPanel {
  const gauges: HandlerGauge[] = [];

  const ratio = (
    id: string,
    caption: string,
    value: number | null | undefined,
    present: boolean,
  ): void => {
    if (!present) return;
    const measured = finite(value);
    gauges.push({
      id,
      caption,
      kind: "ratio",
      percent: measured === undefined ? undefined : measured * 100,
      count: undefined,
      unit: undefined,
      goodDirection: "high",
    });
  };

  ratio(
    "network-idle",
    "NETWORK IDLE",
    document.networkProcessorIdleRatio,
    "networkProcessorIdleRatio" in document,
  );
  ratio("io-idle", "IO IDLE", document.requestHandlerIdleRatio, "requestHandlerIdleRatio" in document);

  for (const queue of document.purgatory ?? []) {
    const operation = queue.operation ?? "";
    if (operation.length === 0) continue;
    gauges.push({
      id: `purgatory-${operation.toLowerCase()}`,
      caption: `${operation.toUpperCase()} PURGATORY`,
      kind: "count",
      percent: undefined,
      count: finite(queue.delayedRequests),
      unit: PURGATORY_UNIT,
      goodDirection: "low",
    });
  }

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
 * One row of the **Top producers** card, exactly as `ProducerDtos.scala` writes it.
 *
 * A `topic` and a `bytesInPerSecond`, and the second wire this module used to get wrong: it read
 * `data.entries[]` of `{clientId, topic, bytesPerSecond}` and the server has always sent
 * `data.topics[]`. The card drew *"The metrics source answered and named no producers"* over a
 * source that named five, and the browser case written to catch exactly that read the same wrong
 * names and iterated an empty array.
 */
export interface TopicProducerEntry {
  readonly topic?: string | undefined;
  readonly bytesInPerSecond?: number | null | undefined;
}

/**
 * The top-producers document.
 *
 * `measuredBy` is the field whose whole job is to say what the rows are *of*, and it is what the
 * card's title says. §4 draws `Top producers · client.id`; a broker publishes no per-`client.id`
 * byte rate unless quotas are configured and does publish a per-*topic* one, so the server sends
 * `"topic"` and the heading says `topic`. Reading the subject from the data rather than writing it
 * here is the whole of ADR-052's second refusal on the screen.
 *
 * `internalTopicsExcluded` counts the topic lines the exporter served that the ranking left out:
 * Kafka's own `__`-prefixed topics, which are not anybody's application traffic and which outrun
 * every real topic on an idle cluster. The card prints it, because a shortened list with nothing
 * saying so is a list nobody can check against the exporter.
 */
export interface ProducerDocument {
  readonly measuredBy?: string | undefined;
  readonly topics?: readonly TopicProducerEntry[] | undefined;
  readonly internalTopicsExcluded?: number | undefined;
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
  /** How many of the exporter's topic lines the ranking left out. Never a guess: the server's own. */
  readonly internalTopicsExcluded: number;
}

/**
 * The two words this build knows how to print, and what it does with a third.
 *
 * `measuredBy` is a fixed vocabulary rather than free text so that a browser can branch on it. A
 * value this build does not recognise draws the heading that claims nothing — `producer` — rather
 * than being printed raw, because an unrecognised word in a card's title is a claim nobody checked.
 */
const SUBJECTS: Readonly<Record<string, ProducerBoard["subject"]>> = {
  topic: "topic",
  "client.id": "client.id",
};

/**
 * Reads the rows and reports what they are.
 *
 * A row whose name is missing or blank is dropped: a bar with no label is a rate attributed to
 * nobody. A row whose *rate* is missing is kept and says so in words — dropping it would shorten a
 * top-five without saying so, and a zero would rank a real topic last on a number nobody measured.
 */
export function producerBoard(document: ProducerDocument): ProducerBoard {
  const rows = (document.topics ?? []).flatMap((entry): readonly ProducerRow[] => {
    const id = entry.topic;
    if (id === undefined || id.length === 0) return [];
    return [{ id, bytesPerSecond: finite(entry.bytesInPerSecond) }];
  });

  const measuredBy = document.measuredBy;
  const subject: ProducerBoard["subject"] =
    measuredBy === undefined ? "producer" : (SUBJECTS[measuredBy] ?? "producer");

  const excluded = document.internalTopicsExcluded;
  return {
    rows,
    subject,
    internalTopicsExcluded: typeof excluded === "number" && excluded > 0 ? excluded : 0,
  };
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
