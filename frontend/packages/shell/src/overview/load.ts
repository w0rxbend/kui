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
      return value(section.data);
    case "forbidden":
      return unknown(`You do not have permission to read ${noun}.`);
    case "not_configured":
      return unknown(`${capitalise(noun)} is not configured for this cluster.`);
    case "unavailable":
    case "unreadable":
      return unknown(section.reason.message ?? `KUI could not read ${noun} (${section.reason.code}).`);
  }
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
    groups: groups.ok
      ? readSection<readonly ConsumerGroup[]>(groups.value.groups, "the consumer groups")
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

function topicCountOf(response: unknown): Reading<number> {
  const pageInfo = (response as { pageInfo?: { totalItems?: number } }).pageInfo;
  const total = pageInfo?.totalItems;
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
