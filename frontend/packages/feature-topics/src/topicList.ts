/**
 * The topic list's arithmetic: what the filters mean, how the rows are ordered, what the summary
 * figures are, and which slice of them a page shows.
 *
 * ## Why this is not in the component
 *
 * Everything here is a pure function of a list of topics, and every one of them has an edge that
 * only shows up on data nobody screenshots: a cluster where no topic reports a throughput, a filter
 * that matches nothing, a page the user is on after the list shrank underneath them, a total that
 * would be quietly short because four topics could not be described.
 *
 * Those are cheap to test over arrays and expensive to reach through a rendered table, so they live
 * here and the component draws what it is given.
 */
import type { TopicRow } from "./types.js";

/** The chips under the controls. `all` is not special-cased; it is the filter that matches. */
export type TopicFilter = "all" | "internal" | "out-of-sync" | "compacted";

export type TopicSort = "topic" | "partitions" | "size" | "throughput" | "replication";

export type SortDirection = "asc" | "desc";

/** Which of the two list treatments is showing. Persisted per user by the caller. */
export type TopicView = "table" | "cards";

/**
 * Whether a topic is compacted.
 *
 * Kafka's `cleanup.policy` is a comma-separated set, and `compact,delete` is a real and common
 * value, so this is a membership test rather than an equality one. A topic that both compacts and
 * deletes is compacted; treating it as `delete` because the string is not exactly `compact` is the
 * bug this function exists to prevent.
 */
export function isCompacted(topic: TopicRow): boolean {
  return (topic.cleanupPolicy ?? "")
    .split(",")
    .map((part) => part.trim())
    .includes("compact");
}

/**
 * Whether a topic's replication is short.
 *
 * `unknown` is deliberately *not* out of sync. It means KUI could not describe the topic, and
 * putting it in a filter called "out of sync" would state a fact about the cluster that nobody
 * established — the same distinction the health chip makes.
 */
export function isOutOfSync(topic: TopicRow): boolean {
  return topic.health === "under-replicated" || topic.health === "offline";
}

export function matchesFilter(topic: TopicRow, filter: TopicFilter): boolean {
  switch (filter) {
    case "internal":
      return topic.internal;
    case "out-of-sync":
      return isOutOfSync(topic);
    case "compacted":
      return isCompacted(topic);
    case "all":
    default:
      // Internal topics are Kafka's own bookkeeping. They are in every cluster and are never what
      // somebody opened this page to find, so "All" means all of the user's topics — and the
      // `Internal` chip is how they are reached, which is why it is always drawn.
      return !topic.internal;
  }
}

/**
 * The rows to show, filtered and ordered.
 *
 * The name search narrows what is already loaded, which is a real limit and is stated on screen
 * when it bites: "no match" means "no match among the topics this page holds".
 */
export function visibleTopics(
  topics: readonly TopicRow[],
  filter: TopicFilter,
  search: string,
  sort: TopicSort,
  direction: SortDirection,
): readonly TopicRow[] {
  const needle = search.trim().toLowerCase();
  const rows = topics.filter(
    (topic) => matchesFilter(topic, filter) && (needle === "" || topic.name.toLowerCase().includes(needle)),
  );
  return [...rows].sort((a, b) => compare(a, b, sort, direction));
}

/**
 * Orders two rows, direction included.
 *
 * The direction is applied *inside* here rather than by multiplying the result, and that is the
 * whole reason this function takes it. A topic whose size could not be read must sort last in both
 * directions — it is not the smallest topic, and it is not the largest either — so the
 * absent-value rule has to survive the flip. Multiplying the comparator's result by -1, which is
 * the obvious way to reverse a sort, flips that rule too and fills the top of a descending "largest
 * topics" list with every topic nobody could measure.
 */
function compare(a: TopicRow, b: TopicRow, sort: TopicSort, direction: SortDirection): number {
  const flip = direction === "asc" ? 1 : -1;
  const byName = a.name.localeCompare(b.name) * flip;

  switch (sort) {
    case "partitions":
      return (a.partitions - b.partitions) * flip || byName;
    case "replication":
      return (a.replicationFactor - b.replicationFactor) * flip || byName;
    case "size":
      return numeric(a.bytes, b.bytes, flip) || byName;
    case "throughput":
      return numeric(a.messagesPerSecond, b.messagesPerSecond, flip) || byName;
    case "topic":
    default:
      return byName;
  }
}

/** Two possibly-absent numbers, ordered by `flip`, with absent always last whichever way it points. */
function numeric(a: number | undefined, b: number | undefined, flip: number): number {
  if (a === undefined && b === undefined) return 0;
  if (a === undefined) return 1;
  if (b === undefined) return -1;
  return (a - b) * flip;
}

/** The four figures above the list. Every one can be absent, and absent is never zero. */
export interface TopicTotals {
  readonly topics: number;
  readonly partitions: number;
  /** Absent when any shown topic's size could not be read: a short total is worse than none. */
  readonly bytes: number | undefined;
  /** The mean replication factor, to one decimal place. */
  readonly replication: number | undefined;
  /** How many topics are below the cluster's usual replication factor. */
  readonly belowUsualReplication: number;
}

export function totals(topics: readonly TopicRow[]): TopicTotals {
  const partitions = topics.reduce((sum, topic) => sum + topic.partitions, 0);

  // A total that silently omits the topics it could not measure is the shape in which somebody
  // plans capacity against a number that is quietly short.
  const anyUnsized = topics.some((topic) => topic.bytes === undefined);
  const bytes = anyUnsized ? undefined : topics.reduce((sum, topic) => sum + (topic.bytes ?? 0), 0);

  const replication =
    topics.length === 0
      ? undefined
      : topics.reduce((sum, topic) => sum + topic.replicationFactor, 0) / topics.length;

  // "Usual" is the mode, not the mean: a cluster of RF 3 with two RF 1 topics has a mean of 2.9 and
  // a usual of 3, and it is the two that want attention.
  const counts = new Map<number, number>();
  for (const topic of topics) counts.set(topic.replicationFactor, (counts.get(topic.replicationFactor) ?? 0) + 1);
  const usual = [...counts.entries()].sort((a, b) => b[1] - a[1] || b[0] - a[0])[0]?.[0];
  const belowUsualReplication =
    usual === undefined ? 0 : topics.filter((topic) => topic.replicationFactor < usual).length;

  return { topics: topics.length, partitions, bytes, replication, belowUsualReplication };
}

/** How the cleanup-policy donut is divided. A topic with no policy is its own slice, not a guess. */
export function cleanupSplit(topics: readonly TopicRow[]): readonly { readonly label: string; readonly value: number }[] {
  let del = 0;
  let compact = 0;
  let both = 0;
  let unknown = 0;
  for (const topic of topics) {
    const policy = topic.cleanupPolicy;
    if (policy === undefined || policy.trim() === "") unknown += 1;
    else if (isCompacted(topic) && policy.includes("delete")) both += 1;
    else if (isCompacted(topic)) compact += 1;
    else del += 1;
  }
  return [
    { label: "Delete", value: del },
    { label: "Compact", value: compact },
    { label: "Compact + delete", value: both },
    { label: "Not known", value: unknown },
  ].filter((slice) => slice.value > 0);
}

/**
 * The top `n` topics by some measure, for the two magnitude panels.
 *
 * Topics with no value are dropped rather than listed at zero — a panel called "Largest topics"
 * whose bottom entries are all topics nobody could measure is a panel that says the opposite of
 * what it means.
 */
export function topBy(
  topics: readonly TopicRow[],
  of: (topic: TopicRow) => number | undefined,
  count = 5,
): readonly { readonly topic: TopicRow; readonly value: number }[] {
  return topics
    .map((topic) => ({ topic, value: of(topic) }))
    .filter((entry): entry is { topic: TopicRow; value: number } => entry.value !== undefined)
    .sort((a, b) => b.value - a.value)
    .slice(0, count);
}

/** The rows one page shows. Clamps the page, so a list that shrank does not leave a blank table. */
export function pageOf<T>(rows: readonly T[], page: number, size: number): readonly T[] {
  const pages = Math.max(1, Math.ceil(rows.length / Math.max(1, size)));
  const clamped = Math.min(Math.max(1, page), pages);
  const start = (clamped - 1) * size;
  return rows.slice(start, start + size);
}

/**
 * The page's voice line.
 *
 * Figures first, joke second — and the joke is dropped entirely when anything is wrong, because an
 * operator whose topics are out of sync does not want to be told about it wittily.
 */
export function topicsVoice(shown: number, total: number, partitions: number, outOfSync: number): string {
  const match = shown === total ? `${total.toLocaleString()} topics` : `${shown.toLocaleString()} of ${total.toLocaleString()} topics match`;
  const base = `${match} · ${partitions.toLocaleString()} partitions`;
  if (outOfSync > 0) {
    return `${base} · ${outOfSync.toLocaleString()} out of sync`;
  }
  return `${base}`;
}
