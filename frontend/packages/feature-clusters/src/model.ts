/**
 * What the cluster and broker screens are about.
 *
 * The design draws none of these screens. What it does draw — the dashboard's broker-health panel
 * (`01`) and the consumer table (`04`) — is the whole vocabulary they are built from: a health dot,
 * a name, a right-aligned line of metadata, a stadium progress track that turns amber past a
 * threshold, and a ruled table with chips and threshold-coloured numbers. Nothing new is invented.
 *
 * As in the consumers feature, everything here is plain data and plain functions, so the rules that
 * decide a colour or a sentence can be tested without a DOM.
 */

import type { PillTone } from "@kui/kernel";

/* ------------------------------------------------------------------------------------------ */
/* Health                                                                                       */
/* ------------------------------------------------------------------------------------------ */

/**
 * How a cluster or a broker is doing.
 *
 * `unknown` is separate from `offline` for the reason that runs through this whole product: a
 * broker that is down and a broker we could not ask look identical if they share a colour, and they
 * mean opposite things. `unknown` is grey; `offline` is red.
 */
export type Health = "healthy" | "degraded" | "offline" | "unknown";

export function healthTone(health: Health): PillTone {
  switch (health) {
    case "healthy":
      return "success";
    case "degraded":
      return "warning";
    case "offline":
      return "danger";
    case "unknown":
      return "neutral";
  }
}

/** The word beside the dot. Always present: colour is never the only signal (SPEC §7.9). */
export function healthLabel(health: Health): string {
  switch (health) {
    case "healthy":
      return "healthy";
    case "degraded":
      return "degraded";
    case "offline":
      return "offline";
    case "unknown":
      return "unreachable";
  }
}

/* ------------------------------------------------------------------------------------------ */
/* Disk                                                                                         */
/* ------------------------------------------------------------------------------------------ */

/**
 * Where a disk stops being ordinary. Fixed here, once, so that the dashboard panel, the broker list
 * and the broker detail page cannot disagree about when a disk is worrying (SPEC §4.20).
 */
export const DISK_WARN_PERCENT = 75;
export const DISK_CRITICAL_PERCENT = 90;

export const DISK_THRESHOLDS = { warn: DISK_WARN_PERCENT, critical: DISK_CRITICAL_PERCENT } as const;

/**
 * A disk's usage as a percentage, or `undefined` when it cannot be worked out.
 *
 * `undefined` and not `0`. A 0%-full disk and an unmeasurable disk look identical if both draw an
 * empty bar, and they mean opposite things — which is why `ProgressBar` prints an em dash where the
 * percentage goes for the second and a `0%` for the first.
 */
export function diskPercent(usedBytes: number | null, totalBytes: number | null): number | undefined {
  if (usedBytes === null || totalBytes === null) return undefined;
  if (!Number.isFinite(usedBytes) || !Number.isFinite(totalBytes) || totalBytes <= 0) return undefined;
  return Math.min(100, (usedBytes / totalBytes) * 100);
}

/* ------------------------------------------------------------------------------------------ */
/* Brokers                                                                                      */
/* ------------------------------------------------------------------------------------------ */

export interface Broker {
  readonly id: number;
  /** `broker-1.kyiv:9092`, as the cluster advertises it. */
  readonly host: string;
  readonly port: number;
  /** The rack the broker declares, or `null` where the cluster is not rack-aware. */
  readonly rack: string | null;
  readonly isController: boolean;
  readonly health: Health;
  /** Partitions this broker leads. `null` when the metadata could not be read. */
  readonly leaderPartitions: number | null;
  /** Replicas it holds, whether it leads them or not. */
  readonly replicaPartitions: number | null;
  /** Replicas it holds that are not in sync. Zero is the healthy answer and is worth printing. */
  readonly outOfSyncReplicas: number | null;
  /**
   * How much of the filesystem beneath this broker's log directories is in use, and how large that
   * filesystem is. **Not** what Kafka holds — see {@link Broker.heldBytes}.
   *
   * Both come from `describeLogDirs`, which reports `totalBytes` and `usableBytes` per directory,
   * and both are `null` until that endpoint has answered. A percentage can only be measured against
   * a capacity, and this pair is the only capacity Kafka's admin protocol exposes anywhere.
   */
  readonly diskUsedBytes: number | null;
  readonly diskTotalBytes: number | null;
  /**
   * What Kafka's own data occupies on this broker: the sum of its replicas, which the brokers
   * endpoint sends as `diskUsageBytes`.
   *
   * Kept apart from the pair above because the two answer different questions and are wrong as
   * each other. A broker holding 95 kB of Kafka data on a disk that is 60% full is a normal
   * machine; printing either figure under the other's label describes a cluster that does not
   * exist.
   */
  readonly heldBytes: number | null;
  /**
   * How far this broker's share of the *leaderships* is from an even one, as a signed percentage.
   *
   * `+50` means it leads half again as many partitions as an even share would give it and `-100`
   * means it leads none; `0` is an even share and is a measurement worth printing. `null` is the
   * absence of one — the figure is a sum over the topic sweep, so a cluster with one topic KUI
   * could not describe reports no skew at all rather than a skew computed over the rest.
   *
   * It is **not** the PARTITION SKEW tile's figure. That one is the spread of *replica* counts
   * across the cluster, from the log directories, and answers "are the partitions spread evenly";
   * this one is one broker's deviation in leadership, from the partition census, and answers "is
   * this the broker carrying it". The two have different sources and refuse independently, which
   * is why they are two fields and not one.
   */
  readonly leaderSkewPercent: number | null;
}

export function brokerName(broker: Broker): string {
  return `${broker.host}:${broker.port}`;
}

/**
 * The right-aligned metadata line the dashboard panel draws: `id 1 · 512 leaders`.
 *
 * Built here rather than in the component because it is a sentence with a rule in it — a leader
 * count that could not be read is dropped from the line rather than printed as `0 leaders`, which
 * would be a claim about the cluster's partition distribution that nobody made.
 */
export function brokerMeta(broker: Broker): string {
  const parts = [`id ${broker.id}`];
  if (broker.leaderPartitions !== null) parts.push(`${broker.leaderPartitions.toLocaleString("en-US")} leaders`);
  if (broker.rack !== null) parts.push(`rack ${broker.rack}`);
  return parts.join(" · ");
}

/**
 * What a broker's leader skew says, in words, or the sentence saying it was not measured.
 *
 * A signed percentage is not a figure anybody reads correctly at a glance: `-25%` beside the word
 * LEADERS invites the reading "this broker leads −25 partitions". So the number is put in a
 * sentence that says which direction it goes and what it is a share *of*, which is the only form in
 * which it is worth drawing at all.
 *
 * Zero is a measurement and gets its own sentence rather than being folded into "more than": an
 * evenly balanced broker is the answer somebody came to this card for.
 */
export function leaderSkewSentence(percent: number | null): string {
  if (percent === null) {
    return (
      "The cluster did not report this broker's share of the leaderships, so KUI cannot say " +
      "whether it leads more than its share."
    );
  }
  const rounded = Math.round(percent);
  if (rounded === 0) return "Leads an even share of this cluster's partitions.";
  const size = Math.abs(rounded).toLocaleString("en-US");
  return rounded > 0
    ? `Leads ${size}% more partitions than an even share of this cluster's.`
    : `Leads ${size}% fewer partitions than an even share of this cluster's.`;
}

/**
 * How unevenly the partitions are spread across the brokers that are answering, as a fraction of
 * the mean, or `undefined` when it cannot be computed.
 *
 * This is the figure that makes a broker list worth building rather than reading out of a shell. A
 * cluster whose brokers hold 400, 410 and 405 partitions is balanced; one holding 900, 200 and 115
 * is one machine away from an incident, and no single row says so.
 *
 * Expressed as (max − min) / mean rather than as a standard deviation, because the operator's
 * question is "how far apart are the busiest and the quietest", and a σ needs explaining.
 *
 * ## Brokers that are not answering are excluded, and that is not a detail
 *
 * A broker that is down holds no replicas, so counting it puts a zero in the set and the figure
 * goes to 200% on a cluster whose *live* brokers are perfectly even. That was the first thing this
 * function said when it was put on a screen with a dead broker on it, and it read as "the
 * partitions are catastrophically skewed" when what had actually happened was already stated, in
 * words, four inches higher up. A number that restates an outage as a second, different problem
 * costs an operator a minute they do not have.
 *
 * So the spread is over the brokers that answered. On a cluster where every broker is down there
 * is nothing to compare and the answer is `undefined`, which the panel prints as a sentence.
 */
export function partitionSkew(brokers: readonly Broker[]): number | undefined {
  const counts = brokers
    .filter((broker) => broker.health !== "offline" && broker.health !== "unknown")
    .map((broker) => broker.replicaPartitions)
    .filter((count): count is number => count !== null);
  if (counts.length < 2) return undefined;
  const total = counts.reduce((sum, count) => sum + count, 0);
  const mean = total / counts.length;
  if (mean <= 0) return undefined;
  return (Math.max(...counts) - Math.min(...counts)) / mean;
}

/**
 * The sentence under a broker's disk bar, or `undefined` where the bar speaks for itself.
 *
 * This is the packet's own instance of the product's central promise, at the one place it is
 * easiest to break: a bar with no fill and a dash beside it is indistinguishable from an empty
 * disk, and the three states behind it are not remotely the same.
 *
 *   - a capacity was reported — the percentage *is* the sentence, so there is none;
 *   - no capacity, but Kafka's own usage is known — say which figure is on screen and why there is
 *     no percentage, because Kafka's admin protocol does not expose the size of the disk under a
 *     log directory and an operator should not be left wondering whether KUI simply failed;
 *   - neither — say that nothing was measured, rather than drawing an empty track.
 */
export function diskNote(broker: Broker): string | undefined {
  if (diskPercent(broker.diskUsedBytes, broker.diskTotalBytes) !== undefined) return undefined;
  if (broker.heldBytes !== null) {
    return "No disk capacity was reported for this broker, so this is what Kafka holds here rather than how full the disk is.";
  }
  return "This broker's disks were not measured, so KUI cannot say how full they are.";
}

/**
 * The three disk figures for a whole cluster, each refusing on its own.
 *
 * Separate sums rather than one, because they come from two endpoints with two different fates: the
 * capacity pair from `describeLogDirs` and the held bytes from the brokers list. A cluster whose
 * log directories were refused still knows what its brokers hold, and a tile that answered
 * "unknown" for the pair would be throwing away a figure the server had just sent.
 *
 * A broker is counted in the capacity sums only when *both* halves are known, which is the same
 * rule the shell's storage meter applies for the same reason: half a broker's capacity produces a
 * plausible-looking percentage that is simply wrong.
 */
export interface ClusterDisk {
  readonly usedBytes: number | null;
  readonly capacityBytes: number | null;
  readonly heldBytes: number | null;
  /** How many brokers contributed a capacity, out of how many are on screen. */
  readonly measured: number;
  readonly brokers: number;
}

export function summariseDisk(brokers: readonly Broker[]): ClusterDisk {
  const withCapacity = brokers.filter(
    (broker) => broker.diskUsedBytes !== null && (broker.diskTotalBytes ?? 0) > 0,
  );
  const held = brokers.filter((broker) => broker.heldBytes !== null);
  return {
    usedBytes: withCapacity.length === 0 ? null : withCapacity.reduce((sum, b) => sum + (b.diskUsedBytes ?? 0), 0),
    capacityBytes: withCapacity.length === 0 ? null : withCapacity.reduce((sum, b) => sum + (b.diskTotalBytes ?? 0), 0),
    heldBytes: held.length === 0 ? null : held.reduce((sum, b) => sum + (b.heldBytes ?? 0), 0),
    measured: withCapacity.length,
    brokers: brokers.length,
  };
}

/* ------------------------------------------------------------------------------------------ */
/* Clusters                                                                                     */
/* ------------------------------------------------------------------------------------------ */

export interface ClusterSummary {
  readonly id: string;
  readonly name: string;
  readonly health: Health;
  /** The Kafka version the brokers report, or `null` when nothing answered. */
  readonly version: string | null;
  /**
   * `kraft` or `zookeeper`, as the scrape reports the cluster running. `null` when it did not say.
   *
   * On the wire beside the version, and read here because the broker card's version tag is the two
   * of them together (`v4.3 · KRaft`) and a tag that named only half of it would be answering the
   * less interesting half of the question.
   */
  readonly controllerKind: string | null;
  readonly brokersOnline: number | null;
  readonly brokersTotal: number | null;
  readonly topics: number | null;
  readonly partitions: number | null;
  readonly underReplicatedPartitions: number | null;
  /** `true` when KUI is configured to refuse every mutation on this cluster (ADR-047). */
  readonly readOnly: boolean;
  /** The last time KUI successfully read anything from it. */
  readonly observedAt: Date | null;
}

/**
 * The version tag on an expanded broker card: `v4.3 · KRaft`, or nothing.
 *
 * The version is the *cluster's* — `describeCluster` reports one version for the cluster and the
 * brokers endpoint carries none per broker — so this is a fact about the deployment drawn on each
 * card, not a claim that KUI asked this machine what it is running. Where the scrape did not report
 * one there is no tag at all: a tag reading "unknown version" is a row of noise on every card, and
 * the card already says what it does not know about the figures that are its own.
 */
export function versionTag(version: string | null, controllerKind: string | null): string | undefined {
  if (version === null) return undefined;
  const prefixed = version.startsWith("v") ? version : `v${version}`;
  if (controllerKind === null) return prefixed;
  // `kraft` on the wire; `KRaft` is how the project spells it, and the design's tag reads that way.
  const kind = controllerKind.toLowerCase() === "kraft" ? "KRaft" : controllerKind;
  return `${prefixed} · ${kind}`;
}

/**
 * The sentence under "Clusters", and under "Brokers".
 *
 * A discriminated union of whole sentences, per SPEC §6.3 rule 3, so the cheerful branch is
 * unreachable from an unhealthy state. The failing branches carry no aside at all: a cheerful line
 * over a broken cluster tells the operator the product has not noticed, and after seeing it once
 * they stop reading the line.
 */
export type ClusterVoice =
  | { readonly kind: "healthy"; readonly brokers: number; readonly underReplicated: number | null }
  | { readonly kind: "degraded"; readonly online: number; readonly total: number; readonly underReplicated: number | null }
  | { readonly kind: "failing"; readonly offline: readonly number[]; readonly underReplicated: number | null }
  | { readonly kind: "unreachable"; readonly lastSeen: string | null };

/**
 * What the line says about under-replication, which is **three** sentences and not two.
 *
 * `0` is a measurement: the partition sweep ran, and it found nothing under-replicated. Saying so
 * is the whole reassurance this line exists to give. `null` is the absence of a measurement, and
 * the two must not share a sentence — a cluster nobody has managed to scrape reading "Zero
 * under-replicated partitions" is the product telling an operator that everything is fine on
 * evidence it does not have, which is the one failure this design treats as unrecoverable.
 *
 * The count is on the wire as `underReplicatedPartitionCount` and is genuinely `0` on a healthy
 * cluster, so this is not a hypothetical distinction: both branches are reached in production.
 */
function underReplication(count: number | null): string {
  if (count === null) {
    return "KUI has not read an under-replicated partition count, so it is not claiming there are none.";
  }
  if (count === 0) return "Zero under-replicated partitions.";
  return `${count} ${count === 1 ? "partition is" : "partitions are"} under-replicated.`;
}

/** The same figure where a broker is already down, so the stakes are the sentence. */
function atRisk(count: number | null): string {
  if (count === null) {
    return "KUI has not read an under-replicated partition count, so how much is at risk is not known.";
  }
  if (count === 0) return "No partition has lost its last in-sync replica.";
  return `${count} ${count === 1 ? "partition has" : "partitions have"} no in-sync replica and ${count === 1 ? "is" : "are"} not accepting writes.`;
}

export function clusterVoice(voice: ClusterVoice): string {
  switch (voice.kind) {
    case "healthy":
      // The aside is attached to the measured zero and to nothing else: it is the reward for a
      // cluster KUI has actually looked at and found nothing wrong with.
      return `${voice.brokers} ${voice.brokers === 1 ? "broker" : "brokers"} online. ${underReplication(voice.underReplicated)}${voice.underReplicated === 0 ? " You may sip your coffee." : ""}`;
    case "degraded":
      return `${voice.online} of ${voice.total} brokers online. ${underReplication(voice.underReplicated)}`;
    case "failing":
      return `${voice.offline.length === 1 ? `Broker ${voice.offline[0]} is` : `Brokers ${voice.offline.join(", ")} are`} down. ${atRisk(voice.underReplicated)}`;
    case "unreachable":
      return voice.lastSeen === null
        ? "The cluster is not answering, and KUI has never reached it."
        : `The cluster is not answering. Last successful check was ${voice.lastSeen}.`;
  }
}

/**
 * Reads the voice off a set of brokers.
 *
 * Order is the rule: anything offline is a failure, anything under-replicated is a degradation, and
 * the healthy branch is reachable only when neither is true. That ordering is why the joke cannot
 * leak onto a broken cluster.
 */
export function voiceOf(brokers: readonly Broker[], underReplicated: number | null, lastSeen: string | null): ClusterVoice {
  if (brokers.length === 0) return { kind: "unreachable", lastSeen };
  const offline = brokers.filter((broker) => broker.health === "offline").map((broker) => broker.id);
  const unreadable = brokers.filter((broker) => broker.health === "unknown").length;
  // `underReplicated` is carried through every branch exactly as it arrived, `null` included. It
  // used to be coalesced to `0` here, which put "0 partitions have no in-sync replica" under a
  // broker that was down — a reassurance about the one number nobody had managed to read.
  if (offline.length > 0) return { kind: "failing", offline, underReplicated };
  if (underReplicated !== null && underReplicated > 0) {
    return { kind: "degraded", online: brokers.length - unreadable, total: brokers.length, underReplicated };
  }
  if (unreadable > 0) return { kind: "degraded", online: brokers.length - unreadable, total: brokers.length, underReplicated };
  return { kind: "healthy", brokers: brokers.length, underReplicated };
}

/**
 * The caption under the broker-health panel (SPEC §4.20).
 *
 * The healthy line keeps its aside — *"It won the election fair and square"* — because "broker 1 is
 * the controller" is a fact that stands on its own and the aside adds nothing to it. The failing
 * line has none, because a cluster with no controller is not accepting metadata changes and there
 * is nothing funny about that.
 */
export function controllerCaption(controllerId: number | null): string {
  if (controllerId === null) return "No controller. The cluster has not elected one.";
  return `Controller: broker ${controllerId}. It won the election fair and square.`;
}

/* ------------------------------------------------------------------------------------------ */
/* Broker configuration and log directories                                                     */
/* ------------------------------------------------------------------------------------------ */

/**
 * Where a setting's value came from. The order is the order the table sorts in, and that order *is*
 * the feature: an operator opening this tab is looking for something somebody changed, so what was
 * changed at runtime comes first and the untouched defaults come last.
 */
export type ConfigSource = "DYNAMIC_BROKER" | "DYNAMIC_DEFAULT" | "STATIC" | "DEFAULT" | "UNKNOWN";

export const CONFIG_SOURCE_ORDER: readonly ConfigSource[] = ["DYNAMIC_BROKER", "DYNAMIC_DEFAULT", "STATIC", "DEFAULT", "UNKNOWN"];

export function configSourceLabel(source: ConfigSource): string {
  switch (source) {
    case "DYNAMIC_BROKER":
      return "Set on this broker";
    case "DYNAMIC_DEFAULT":
      return "Set cluster-wide";
    case "STATIC":
      return "From the broker's file";
    case "DEFAULT":
      return "Kafka's default";
    case "UNKNOWN":
      return "Unknown";
  }
}

export interface ConfigEntry {
  readonly name: string;
  /**
   * `null` for a setting the broker marks sensitive. It is not a value KUI failed to read and it is
   * not an empty string: Kafka refuses to disclose it, and the screen has to say which of the three
   * it is.
   */
  readonly value: string | null;
  readonly sensitive: boolean;
  readonly source: ConfigSource;
  /** `true` where the value differs from Kafka's default — the rows an operator came to find. */
  readonly overridden: boolean;
}

/** Sorted by source, then by name. See `CONFIG_SOURCE_ORDER`. */
export function sortConfigs(entries: readonly ConfigEntry[]): readonly ConfigEntry[] {
  return [...entries].sort((a, b) => {
    const bySource = CONFIG_SOURCE_ORDER.indexOf(a.source) - CONFIG_SOURCE_ORDER.indexOf(b.source);
    return bySource !== 0 ? bySource : a.name.localeCompare(b.name);
  });
}

/** Case-insensitive match on the name and, where there is one, on the value. */
export function configMatches(entry: ConfigEntry, term: string): boolean {
  const needle = term.trim().toLowerCase();
  if (needle === "") return true;
  if (entry.name.toLowerCase().includes(needle)) return true;
  return entry.value !== null && entry.value.toLowerCase().includes(needle);
}

export interface LogDir {
  readonly path: string;
  /** `null` when this directory could not be read; the row then carries the error instead. */
  readonly sizeBytes: number | null;
  readonly partitions: number | null;
  /**
   * Why this directory could not be read. Kafka answers per directory, so one offline disk must not
   * blank the rows for the disks that answered.
   */
  readonly error: string | null;
}

/** The sum of the directories that answered. `null` when none did. */
export function totalLogDirBytes(dirs: readonly LogDir[]): number | null {
  const known = dirs.map((dir) => dir.sizeBytes).filter((size): size is number => size !== null);
  return known.length === 0 ? null : known.reduce((sum, size) => sum + size, 0);
}
