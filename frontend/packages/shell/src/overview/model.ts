/**
 * The cluster overview's view model: what the dashboard draws, derived from what the backend serves.
 *
 * ## Why this file is pure, and has no imports from Solid
 *
 * Every judgement the dashboard makes about a cluster is made here — whether "all in sync" is a true
 * sentence, whether a broker's disk is into the amber, which figures the product simply does not
 * measure. Those are the parts that can be wrong in a way that matters, and they are all decided by
 * functions that take plain data and return plain data. The components below this are then only
 * arrangement, and the tests for the judgements need no browser.
 *
 * ## The section payload types
 *
 * `@kui/api`'s generated types stop at `unknown` for anything wrapped in a `Section` — the server
 * documents `Section[A]` with `Schema.any`, and `packages/api/src/section.ts` explains why at
 * length. The shapes below are therefore written out by hand, transcribed from the Scala case
 * classes in `libs/contracts-core` that produce them, and they are the *only* hand-written shapes in
 * this feature. When the server's schema is fixed these are deleted and the generated types take
 * over; until then they are deliberately narrow, naming only the fields the dashboard reads.
 */

import { formatCount } from "@kui/kernel";

import { type Reading, mapReading, notCollected, readingValue, unknown, value } from "./reading.js";

/* --- The backend's shapes, as far as this screen reads them ----------------------------------- */

/**
 * `ClusterSummaryDto` from `libs/contracts-core/.../ClusterDtos.scala`.
 *
 * Almost every field is optional on the wire and optional here, because they are `None` until the
 * first successful scrape and stay `None` on a broker too old to report them. Widening them to
 * required and defaulting to zero would turn "we have not looked yet" into "there are none", which
 * is the exact lie this screen is built to avoid.
 */
export interface ClusterSummary {
  readonly version?: string | undefined;
  readonly controllerId?: number | undefined;
  readonly brokerCount: number;
  readonly onlinePartitionCount?: number | undefined;
  readonly offlinePartitionCount?: number | undefined;
  readonly underReplicatedPartitionCount?: number | undefined;
  readonly scrapedAt?: string | undefined;
}

/** `BrokerDto`. `leaderCount` and the disk figures are optional for the same reason. */
export interface Broker {
  readonly id: number;
  readonly host: string;
  readonly port: number;
  readonly isController: boolean;
  readonly leaderCount?: number | undefined;
  readonly diskUsageBytes?: number | undefined;
}

/**
 * `LogDirDto`, which is where a disk *percentage* can come from and `BrokerDto` alone cannot.
 *
 * `totalBytes` is only reported by Kafka 3.3 and later, and is absent for an offline directory. A
 * broker whose directories report no total therefore has a known usage and an unknown capacity,
 * which is a disk bar with no percentage — see {@link brokerHealth}.
 */
export interface LogDir {
  readonly brokerId: number;
  readonly path: string;
  readonly error?: string | undefined;
  readonly totalBytes?: number | undefined;
  readonly usableBytes?: number | undefined;
}

/** `GroupSummaryDto`, as far as the "top consumer lag" panel reads it. */
export interface ConsumerGroup {
  readonly groupId: string;
  readonly state: string;
  readonly totalLag?: number | undefined;
}

/* --- The view model ---------------------------------------------------------------------------- */

/** The tone vocabulary shared by pills, bars and the donut. Mapped to tokens by the components. */
export type Tone = "neutral" | "success" | "warning" | "danger";

export interface BrokerBar {
  readonly id: number;
  readonly name: string;
  /** `id 1 · 512 leaders`, or `id 1` alone when the leader count was not reported. */
  readonly detail: string;
  /** The disk figure, as a percentage. Absent when no directory reported a capacity. */
  readonly diskPercent: Reading<number>;
  readonly online: boolean;
}

export interface LagEntry {
  readonly groupId: string;
  readonly lag: number;
  readonly state: string;
}

export interface PartitionHealth {
  readonly inSync: number;
  readonly underReplicated: number;
  readonly offline: number;
  /** The share of partitions that are fully replicated and online, 0..100. */
  readonly healthyPercent: number;
}

/* --- Stat cards -------------------------------------------------------------------------------- */

/**
 * How many brokers are up — and deliberately *not* "3 / 3".
 *
 * The screenshot draws `3/3`, and the backend cannot honestly produce the denominator. A Kafka
 * cluster has no notion of an expected broker count: `describeCluster` reports the brokers that
 * answered, and nothing anywhere says how many there should have been. The denominator in the
 * design could only be computed as "the number that answered", which makes it `n/n` always — a
 * fraction that is 100% by construction and therefore tells an operator nothing, while looking
 * exactly like a fraction that would drop to 2/3 in an outage. A reassuring number that cannot
 * fall is worse than no number.
 *
 * So the card shows the count, and the *pill* carries the health, which is a thing the cluster
 * really does report. This is a deliberate departure from the screenshot, recorded in the report.
 */
export function brokerCount(summary: Reading<ClusterSummary>): Reading<number> {
  return mapReading(summary, (s) => s.brokerCount);
}

/**
 * The sentence under the broker count, which is only allowed to be cheerful when it is true.
 *
 * SPEC §6 puts this rule in words: the voice is a property of a healthy cluster, not of the
 * product. "All in sync" over a cluster with 12 under-replicated partitions is not a joke that
 * landed badly, it is a false statement on a monitoring screen.
 */
export function replicationPill(summary: Reading<ClusterSummary>): { text: string; tone: Tone } | undefined {
  const s = readingValue(summary);
  if (s === undefined) return undefined;

  const under = s.underReplicatedPartitionCount;
  const offline = s.offlinePartitionCount;

  // Not knowing is not the same as being fine, and this is the branch where the difference is
  // cheapest to get wrong: `undefined > 0` is `false` in JavaScript, so a missing count sails
  // straight into the "all in sync" arm unless it is checked first.
  if (under === undefined || offline === undefined) return undefined;

  // Both of these say "partitions" out loud. The pill sits under a figure labelled BROKERS ONLINE,
  // and a bare "46 offline" there reads as forty-six offline brokers — which on a three-broker
  // cluster is not merely wrong but impossible, and is the kind of number somebody acts on before
  // they notice it cannot be true.
  if (offline > 0) {
    return { text: `${formatCount(offline)} partitions offline`, tone: "danger" };
  }
  if (under > 0) {
    return { text: `${formatCount(under)} partitions under-replicated`, tone: "warning" };
  }
  return { text: "all in sync", tone: "success" };
}

/**
 * Total partitions: the two counts added, and unknown unless *both* are known.
 *
 * Adding an absent count as zero would understate the total, and a partition count that is quietly
 * too low is indistinguishable from a cluster that has lost partitions.
 */
export function partitionTotal(summary: Reading<ClusterSummary>): Reading<number> {
  if (summary.kind !== "value") return summary;
  const { onlinePartitionCount: online, offlinePartitionCount: offline } = summary.value;
  if (online === undefined || offline === undefined) {
    return unknown("this broker does not report partition counts");
  }
  return value(online + offline);
}

/**
 * Total consumer lag across every group.
 *
 * A group whose lag could not be computed contributes nothing to the sum *and* makes the sum
 * approximate, which is why the count of such groups comes back with it: a total that silently
 * omits three groups is a number an operator will act on and should not.
 */
export function totalLag(groups: Reading<readonly ConsumerGroup[]>): Reading<{ total: number; incomplete: number }> {
  return mapReading(groups, (list) => {
    let total = 0;
    let incomplete = 0;
    for (const group of list) {
      if (group.totalLag === undefined) incomplete += 1;
      else total += group.totalLag;
    }
    return { total, incomplete };
  });
}

/**
 * The lag pill's words, which are a joke only when the lag is small enough for one.
 *
 * "Fashionably late" is the design's copy and it is good copy — for a cluster that is a little
 * behind. Over a million-record backlog it reads as the product not understanding what it is
 * looking at. The threshold is what turns the voice on and off.
 */
export const LAG_JOKE_CEILING = 10_000;

export function lagPill(lag: Reading<{ total: number; incomplete: number }>): { text: string; tone: Tone } | undefined {
  const v = readingValue(lag);
  if (v === undefined) return undefined;
  if (v.incomplete > 0) {
    return { text: `${formatCount(v.incomplete)} groups not counted`, tone: "warning" };
  }
  if (v.total === 0) return { text: "fully caught up", tone: "success" };
  if (v.total < LAG_JOKE_CEILING) return { text: "fashionably late", tone: "warning" };
  return { text: "seriously behind", tone: "danger" };
}

/* --- Broker health ------------------------------------------------------------------------------ */

/** Above this share of the disk, the bar turns amber. Below it, it is the ordinary accent. */
export const DISK_WARN_PERCENT = 75;
export const DISK_CRITICAL_PERCENT = 90;

/**
 * One bar per broker, with the disk percentage computed from the broker's log directories.
 *
 * The percentage is `used / total` summed across a broker's directories, where `used` is
 * `total - usable`. A directory that reported no total is left out of *both* sums rather than
 * counted as zero: a broker with one 3.3 disk and one older disk would otherwise show a percentage
 * computed over half its capacity, which is a plausible-looking number that is simply wrong. When
 * no directory reported a total, the broker's percentage is `unknown` and the bar draws no fill —
 * which is the "a quantity bar must not draw zero as a full-width track" rule from the brief,
 * approached from the other side: it must not draw *unknown* as zero either.
 */
export function brokerHealth(
  brokers: Reading<readonly Broker[]>,
  logDirs: Reading<readonly LogDir[]>,
): Reading<readonly BrokerBar[]> {
  return mapReading(brokers, (list) =>
    list.map((broker) => {
      const dirs = readingValue(logDirs)?.filter((d) => d.brokerId === broker.id) ?? [];
      return {
        id: broker.id,
        name: broker.host,
        detail:
          broker.leaderCount === undefined
            ? `id ${broker.id}`
            : `id ${broker.id} · ${formatCount(broker.leaderCount)} leaders`,
        diskPercent: diskPercentOf(dirs, logDirs),
        online: true,
      };
    }),
  );
}

function diskPercentOf(dirs: readonly LogDir[], logDirs: Reading<readonly LogDir[]>): Reading<number> {
  if (logDirs.kind !== "value") {
    // The broker list arrived and the log directories did not. That is not "0% used"; it is a bar
    // with nothing in it and a reason attached.
    return logDirs.kind === "pending" ? { kind: "pending" } : logDirs;
  }

  let total = 0;
  let used = 0;
  let measured = 0;
  for (const dir of dirs) {
    if (dir.totalBytes === undefined || dir.usableBytes === undefined) continue;
    total += dir.totalBytes;
    used += dir.totalBytes - dir.usableBytes;
    measured += 1;
  }

  if (measured === 0) {
    return unknown("this broker's log directories do not report a disk size (Kafka 3.3 and later do)");
  }
  if (total <= 0) return unknown("this broker reported a zero-byte disk");
  return value((used / total) * 100);
}

/**
 * The line under the broker bars naming the controller.
 *
 * Returns `undefined` rather than a placeholder when no broker claims to be the controller. An
 * election in progress is a real and brief state, and printing "Controller: —" during it invites
 * somebody to go and investigate a cluster that is behaving exactly as Kafka is supposed to.
 */
export function controllerNote(brokers: Reading<readonly Broker[]>): string | undefined {
  const list = readingValue(brokers);
  if (list === undefined) return undefined;
  const controller = list.find((b) => b.isController);
  if (controller === undefined) return undefined;
  return `Controller: broker ${controller.id}. It won the election fair and square.`;
}

/* --- Partition health ---------------------------------------------------------------------------- */

/**
 * The donut's three segments.
 *
 * `inSync` is derived by subtraction — online partitions minus the under-replicated ones — because
 * the backend reports "online" and "under-replicated" as overlapping counts, not as a partition of
 * the whole. Treating them as three disjoint numbers and adding them would double-count every
 * under-replicated partition and produce a total larger than the cluster has.
 *
 * The subtraction is floored at zero. It should never go negative, but the two counts come from two
 * different `AdminClient` calls a moment apart, and a donut with a negative segment is a rendering
 * bug reported to the user as a cluster problem.
 */
export function partitionHealth(summary: Reading<ClusterSummary>): Reading<PartitionHealth> {
  if (summary.kind !== "value") return summary;

  const {
    onlinePartitionCount: online,
    offlinePartitionCount: offline,
    underReplicatedPartitionCount: under,
  } = summary.value;
  if (online === undefined || offline === undefined || under === undefined) {
    return unknown("this cluster does not report partition health counts");
  }

  const inSync = Math.max(0, online - under);
  const total = inSync + under + offline;
  return value({
    inSync,
    underReplicated: under,
    offline,
    healthyPercent: total === 0 ? 100 : (inSync / total) * 100,
  });
}

/* --- Top consumer lag ---------------------------------------------------------------------------- */

/**
 * The groups with the most lag, largest first.
 *
 * Groups whose lag is unknown are dropped rather than sorted as zero. A group with an uncomputable
 * lag sorted to the bottom of a "top lag" list is being reported as the healthiest thing on the
 * cluster on the strength of a number nobody has.
 */
export function topLag(groups: Reading<readonly ConsumerGroup[]>, limit = 4): Reading<readonly LagEntry[]> {
  return mapReading(groups, (list) =>
    list
      .filter((g): g is ConsumerGroup & { totalLag: number } => g.totalLag !== undefined)
      .map((g) => ({ groupId: g.groupId, lag: g.totalLag, state: g.state }))
      .sort((a, b) => b.lag - a.lag)
      .slice(0, limit),
  );
}

/* --- The things KUI does not measure ------------------------------------------------------------- */

/**
 * The three figures the design draws that this backend does not collect.
 *
 * These are not failures and they are not empty states. There is no metrics service, no timeseries
 * store and no scrape loop anywhere in the seven services — the gateway's OpenAPI documents publish
 * no endpoint that could answer any of them. The dashboard says so in a sentence and draws no axes,
 * because an empty chart with a labelled time axis is a claim that the data is merely missing right
 * now, and somebody will go looking for the broken exporter.
 */
export const throughputSeries = (): Reading<never> =>
  notCollected(
    "KUI does not record throughput over time. Nothing samples broker byte rates, so there is no history to draw.",
  );

export const productionRate = (): Reading<never> =>
  notCollected("KUI does not sample the current produce rate.");

export const latencyPercentiles = (): Reading<never> =>
  notCollected(
    "KUI does not record request latency. Produce and fetch percentiles come from broker JMX, which nothing here scrapes.",
  );

/* --- The page's own sentence ---------------------------------------------------------------------- */

/**
 * The line under "Cluster overview", which is the design's voice and is therefore conditional.
 *
 * The screenshot's "All brokers vibing. Zero under-replicated partitions. You may sip your coffee."
 * is kept verbatim for the state it describes, and only for that state. The rule from SPEC §6 and
 * from the brief is the same: a cheerful sentence over a broken cluster is worse than a plain one.
 */
export function overviewLede(summary: Reading<ClusterSummary>): string {
  if (summary.kind === "pending") return "Asking the cluster how it is.";
  if (summary.kind !== "value") return "The cluster has not answered yet, so the figures below are incomplete.";

  const { offlinePartitionCount: offline, underReplicatedPartitionCount: under } = summary.value;

  if (offline === undefined || under === undefined) {
    return "This broker does not report partition health, so some figures below are blank.";
  }
  if (offline > 0) {
    return `${formatCount(offline)} partitions are offline. That is the thing to look at first.`;
  }
  if (under > 0) {
    return `${formatCount(under)} partitions are under-replicated. Not an emergency, but not nothing.`;
  }
  return "All brokers vibing. Zero under-replicated partitions. You may sip your coffee.";
}
