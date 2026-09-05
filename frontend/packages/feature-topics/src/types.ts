/**
 * What the topic screens are told about a topic.
 *
 * These are the feature's own view types, not the wire's. The gateway's topic list is a paged,
 * sectioned document with per-section availability; the list on screen is a table of rows. Mapping
 * between them happens where the data is fetched, so that every state a row can be in is reachable
 * from a story and from a test without a server.
 */

/** How a topic's replication is doing, as the chip beside its name says it. */
export type TopicHealth =
  /** Every partition has all of its replicas in sync. */
  | "in-sync"
  /** At least one partition is short of replicas but still has a leader. Readable, at risk. */
  | "under-replicated"
  /** At least one partition has no leader. Not readable, not writable. */
  | "offline"
  /**
   * KUI could not describe this topic — the broker did not answer for it.
   *
   * Deliberately its own state and not folded into "offline": one says the topic is broken, the
   * other says KUI does not know, and drawing the second as the first invents a fact.
   */
  | "unknown";

/** One row of the topic list. */
export interface TopicRow {
  readonly name: string;
  /** Kafka's own flag: `__consumer_offsets` and friends. Hidden by default; see `TopicListPage`. */
  readonly internal: boolean;
  readonly partitions: number;
  readonly replicationFactor: number;
  readonly health: TopicHealth;
  /**
   * How many records the topic holds, as far as the offsets say.
   *
   * `undefined` is "not known", which is a different fact from zero and is drawn differently: a
   * dash, never a `0`. A topic whose partitions could not all be described has no honest total.
   */
  readonly records?: number | undefined;
  /** Size on disk in bytes. `undefined` means the same as above. */
  readonly bytes?: number | undefined;
  /** `delete`, `compact`, or `compact,delete`, exactly as Kafka spells it. */
  readonly cleanupPolicy?: string | undefined;
  /**
   * Records produced per second, when the cluster has a metrics source that reports it.
   *
   * `undefined` is "not measured here" and draws a dash. Most Kafka deployments have no per-topic
   * rate without an external metrics store, so this is absent far more often than it is present —
   * which is why it must never render as `0`, the figure a silent topic legitimately has.
   */
  readonly messagesPerSecond?: number | undefined;
}

/** The topic-page tabs, as the page is told about them. */
export interface TopicTab {
  readonly id: string;
  readonly label: string;
  readonly href: string;
  readonly count?: number | undefined;
}
