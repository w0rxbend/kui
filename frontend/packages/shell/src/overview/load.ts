/**
 * Fetching the cluster overview, and turning four independent answers into one view model.
 *
 * ## Four requests, not one
 *
 * The dashboard's panels come from four endpoints across three services: the cluster summary, the
 * broker list, the log directories and the consumer groups. They are asked for separately and each
 * one's answer lands in its own `Reading`, so a service that is down blanks its own panels and
 * nothing else. Combining them into one request would make the whole screen as available as its
 * least available part, which is the opposite of what ADR-039 asks for — and on an operations
 * dashboard it is also the worst possible failure mode, because the panel most likely to be
 * unavailable is the one describing the thing that has gone wrong.
 *
 * ## Why the failure paths are so explicit
 *
 * Each of the four steps below can fail in three distinguishable ways — the transport failed, the
 * section came back not-`ok`, or the payload was there but empty — and each maps to a different
 * `Reading`. Writing them out is verbose and the verbosity is the point: the alternative is a
 * `catch` that turns all three into one "unavailable", and the whole argument of `reading.ts` is
 * that those are not the same thing to show somebody.
 */

import type { KuiApiClient } from "@kui/api";
import { decodeSection, userMessage, type Section } from "@kui/api";

import {
  type Broker,
  type ClusterSummary,
  type ConsumerGroup,
  type LogDir,
  brokerCount,
  brokerHealth,
  controllerNote,
  lagPill,
  latencyPercentiles,
  overviewLede,
  partitionHealth,
  partitionTotal,
  productionRate,
  replicationPill,
  throughputSeries,
  topLag,
  totalLag,
} from "./model.js";
import type { OverviewModel } from "./Overview.jsx";
import { type Reading, pending, unknown, value } from "./reading.js";

/** The four answers, before they are assembled. Exported so a test can build one directly. */
export interface OverviewData {
  readonly summary: Reading<ClusterSummary>;
  readonly brokers: Reading<readonly Broker[]>;
  readonly logDirs: Reading<readonly LogDir[]>;
  readonly groups: Reading<readonly ConsumerGroup[]>;
  readonly topicCount: Reading<number>;
}

/** What the screen shows before anything has come back: four skeletons, not four dashes. */
export function loadingData(): OverviewData {
  return {
    summary: pending(),
    brokers: pending(),
    logDirs: pending(),
    groups: pending(),
    topicCount: pending(),
  };
}

/**
 * Turns a section into a reading.
 *
 * The five section statuses collapse to two readings, and which way each one goes is a judgement:
 *
 * - `ok` and `stale` both carry data, and both become a value. A stale section is last-known-good
 *   data, which is worth far more on an operations screen than a blank panel — the staleness is
 *   surfaced separately, by the card's own stale badge, rather than by throwing the data away.
 * - `unavailable`, `forbidden`, `not_configured` and `unreadable` become `unknown`, each with the
 *   sentence that says which of the four it was. They are deliberately *not* `notCollected`:
 *   `notCollected` means the product has no such measurement, and every one of these means the
 *   measurement exists and this request did not get it.
 */
export function readSection<T>(raw: unknown, noun: string): Reading<T> {
  const section: Section<T> = decodeSection<T>(raw);
  switch (section.status) {
    case "ok":
    case "stale":
      return value(withoutNulls(section.data));
    case "forbidden":
      return unknown(`You do not have permission to read ${noun}.`);
    case "not_configured":
      return unknown(`${capitalise(noun)} is not configured for this cluster.`);
    case "unavailable":
    case "unreadable":
      return unknown(section.reason.message ?? `KUI could not read ${noun} (${section.reason.code}).`);
  }
}

/**
 * Rewrites every `null` in a decoded payload to `undefined`.
 *
 * ## Why this is not pedantry
 *
 * `model.ts` distinguishes "we know this is zero" from "nobody told us" everywhere, and it spells
 * the second one `undefined` — the interfaces say `readonly offlinePartitionCount?: number |
 * undefined`, and the checks are written `=== undefined`. JSON has no `undefined`: the gateway
 * sends `null`, and `null === undefined` is `false`.
 *
 * So every one of those careful guards was passed straight through by the value it was written to
 * catch, and the consequences were not cosmetic. On a cluster reporting `null` for all three
 * partition counts, `replicationPill` fell past its "not knowing is not the same as being fine"
 * guard into the final arm and printed **"all in sync"**; `overviewLede` announced **"Zero
 * under-replicated partitions. You may sip your coffee."**; and `partitionTotal` computed
 * `null + null` as `0` and captioned the card **"0 partitions"**. A monitoring screen invented
 * three separate reassurances about data it had never received.
 *
 * The fix belongs here, at the one boundary where wire data becomes model data, rather than as
 * twenty `== null` checks scattered through the judgements — those would work, and the
 * twenty-first would be forgotten. Converting once means `model.ts` can keep saying `undefined`
 * and mean it. `null` and `undefined` both mean "absent" to every consumer downstream, so nothing
 * is lost by collapsing them.
 */
export function withoutNulls<T>(input: T): T {
  if (input === null) return undefined as T;
  if (Array.isArray(input)) return input.map((item: unknown) => withoutNulls(item)) as T;
  // Only plain objects are walked. A `Date`, a `Map` or anything else with a prototype of its own
  // is left exactly as it is rather than being flattened into a bare object.
  if (typeof input === "object" && Object.getPrototypeOf(input) === Object.prototype) {
    const out: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(input as Record<string, unknown>)) {
      const converted = withoutNulls(item);
      // Dropped rather than set to `undefined`, so `"key" in object` and `Object.keys` agree with
      // the reading that the field is absent.
      if (converted !== undefined) out[key] = converted;
    }
    return out as T;
  }
  return input;
}

/**
 * Reads a section whose payload is a *page* rather than a bare list.
 *
 * ## Why this is separate from `readSection`
 *
 * Three of the overview's four sections carry their data directly — the cluster summary is an
 * object, the broker list and the log directories are arrays. The consumer groups do not: that
 * endpoint pages, so its section's `data` is `{ items, pageInfo }`, exactly as the endpoint's own
 * description says ("the page is wrapped in a freshness section").
 *
 * Reading it with `readSection<readonly ConsumerGroup[]>` produced a `Reading` holding the page
 * object while claiming to hold an array. Nothing complained: `decodeSection<T>` takes `unknown`
 * and hands back `T`, so the type argument is an assertion the compiler cannot check, and the
 * generated `schema.d.ts` types this response body as `unknown` and so could not contradict it
 * either. The mistake surfaced only when `totalLag` ran `for (const group of list)` over the page
 * and threw `TypeError: ... is not iterable` — *inside a computation*, which Solid 2 answers by
 * logging `REACTIVITY_HALTED` and stopping the graph. The dashboard's four skeletons then never
 * resolved and three panels stayed empty for ever, with every request having returned 200.
 *
 * So the unwrapping is done here, once, and the shape is *checked* rather than asserted: a payload
 * that is not a page becomes an `unknown` reading with a sentence, which is a panel that says it
 * could not read the data — not an exception that takes the screen with it.
 */
export function readPagedSection<T>(raw: unknown, noun: string): Reading<readonly T[]> {
  const section = readSection<unknown>(raw, noun);
  if (section.kind !== "value") return section;

  const items = (section.value as { items?: unknown } | null)?.items;
  if (!Array.isArray(items)) {
    return unknown(`KUI could not read ${noun}: the server sent something other than a page.`);
  }
  return value(items as readonly T[]);
}

function capitalise(text: string): string {
  return text.length === 0 ? text : `${text[0]?.toUpperCase() ?? ""}${text.slice(1)}`;
}

/**
 * Asks the four endpoints and returns whatever came back.
 *
 * Every request is issued at once rather than in sequence. They are independent, and four
 * round-trips one after another on a link to a remote gateway is the difference between a dashboard
 * that appears and one that assembles itself in front of you.
 *
 * Nothing here rejects: `@kui/api` answers failures as values, so `Promise.all` cannot short-circuit
 * and one dead service cannot take the other three answers down with it. That property is the whole
 * reason the client is shaped that way, and it is worth not undoing with a stray `throw`.
 */
export async function fetchOverview(api: KuiApiClient, clusterId: string): Promise<OverviewData> {
  const [detail, brokers, logDirs, groups, topics] = await Promise.all([
    api.get("/api/v1/clusters/{clusterId}", { params: { path: { clusterId } } }),
    api.get("/api/v1/clusters/{clusterId}/brokers", { params: { path: { clusterId } } }),
    api.get("/api/v1/clusters/{clusterId}/log-dirs", { params: { path: { clusterId } } }),
    api.get("/api/v1/clusters/{clusterId}/consumer-groups", { params: { path: { clusterId } } }),
    api.get("/api/v1/clusters/{clusterId}/topics", { params: { path: { clusterId } } }),
  ]);

  return {
    summary: detail.ok
      ? readSection<ClusterSummary>(detail.value.cluster.summary, "the cluster summary")
      : unknown(userMessage(detail.error)),
    brokers: brokers.ok
      ? readSection<readonly Broker[]>(brokers.value.brokers, "the broker list")
      : unknown(userMessage(brokers.error)),
    logDirs: logDirs.ok
      ? readSection<readonly LogDir[]>(logDirs.value.logDirs, "the log directories")
      : unknown(userMessage(logDirs.error)),
    /* A page, not a list — see `readPagedSection`. */
    groups: groups.ok
      ? readPagedSection<ConsumerGroup>(groups.value.groups, "the consumer groups")
      : unknown(userMessage(groups.error)),
    /* The topic *count*, not the topic list. The list endpoint pages, and its page info carries the
     * total — so the number on the card is the cluster's topic count and not "how many topics fit
     * on the first page", which is the bug this line exists to not have. When the server sends no
     * total, the card says unknown rather than showing a page size as though it were a total. */
    topicCount: topics.ok
      ? topicCountOf(topics.value)
      : unknown(userMessage(topics.error)),
  };
}

/**
 * The cluster's topic count, taken from the page's own total.
 *
 * Two things about this were wrong, and both were invisible because the response is typed
 * `unknown` in the generated schema. The total was looked for at the top of the response rather
 * than inside the freshness section's `data`, and it was looked for under the name `pageInfo`
 * while the gateway calls it `page`. Missing on both counts, the function took its honest-looking
 * fallback branch and the card showed an em dash — "KUI did not report a topic count" — for a
 * cluster that had reported one perfectly well. A fallback that is reached by accident is worse
 * than no fallback, because it looks like the considered answer it was written to be.
 */
function topicCountOf(response: unknown): Reading<number> {
  const section = readSection<{ page?: { totalItems?: number } }>(
    (response as { topics?: unknown }).topics,
    "the topic list",
  );
  if (section.kind !== "value") return section;

  const total = section.value.page?.totalItems;
  return typeof total === "number"
    ? value(total)
    : unknown("this KUI build did not report a total topic count");
}

/** Assembles the view model. Pure, and therefore the thing the tests drive. */
export function toOverviewModel(data: OverviewData): OverviewModel {
  const lag = totalLag(data.groups);
  return {
    lede: overviewLede(data.summary),
    brokerCount: brokerCount(data.summary),
    brokerPill: replicationPill(data.summary),
    topicCount: data.topicCount,
    partitionTotal: partitionTotal(data.summary),
    productionRate: productionRate(),
    throughput: throughputSeries(),
    latency: latencyPercentiles(),
    lag,
    lagPill: lagPill(lag),
    brokers: brokerHealth(data.brokers, data.logDirs),
    controllerNote: controllerNote(data.brokers),
    partitions: partitionHealth(data.summary),
    topLag: topLag(data.groups),
  };
}
