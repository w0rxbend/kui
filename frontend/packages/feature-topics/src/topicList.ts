/**
 * The topic list's vocabulary and its arithmetic: what the filters mean, which columns the cluster
 * can order by, and the two files the list is written out as.
 *
 * ## Why this is not in the component
 *
 * Everything here is a pure function, and each one has an edge that only shows up on data nobody
 * screenshots: a `cleanup.policy` of `compact,delete`, a policy containing the separator the export
 * uses, a cluster whose sweep could not describe four topics. Those are cheap to test over arrays
 * and expensive to reach through a rendered table, so they live here and the component draws what
 * it is given.
 *
 * ## What used to be here and is not
 *
 * `visibleTopics`, `totals`, `topBy`, `cleanupSplit` and `pageOf` folded, ordered, summed and
 * sliced the rows the page happened to hold. They were written for a list this screen filtered, searched,
 * sorted and paginated itself — which the server has done since wave 3, so every one of them had
 * exactly one reference in the repository, its own declaration. A cluster-wide figure computed from
 * one page is precisely the sentence this screen was rebuilt to stop writing, and keeping the
 * arithmetic that writes it around "in case" is how it gets called again. The totals come from
 * `GET …/topics/statistics`; the order and the page come from the request.
 */
import type { TopicRow } from "./types.js";

/** The chips under the controls. `all` is not special-cased; it is the filter that matches. */
export type TopicFilter = "all" | "internal" | "out-of-sync" | "compacted";

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
function isOutOfSync(topic: TopicRow): boolean {
  return topic.health === "under-replicated" || topic.health === "offline";
}

/**
 * The columns this list can be ordered by, in both vocabularies at once.
 *
 * ## One list, because two lists that must agree do not
 *
 * The `Sort ·` menu's options lived in `TopicListPage.tsx` and the request's field names in
 * `TopicsRoute.tsx`, and the pair had a hole with a direction to it: *removing* a mapping was
 * caught by a case, and *adding* an option with no mapping was not. That ships a menu item that sorts by
 * nothing — the list is redrawn in the server's own order under an arrow claiming otherwise, which
 * is the same shape of lie as a search that only looks at the page it holds. Derived from one list,
 * an option with no field cannot be written down.
 *
 * `health` and `cleanup policy` are deliberately absent: the topics endpoint orders by neither, and
 * their columns are not marked sortable in the table either, so no control anywhere offers an order
 * the cluster cannot produce.
 */
export interface SortableColumn {
  /** The table's own column id. A header click and the menu both write this. */
  readonly columnId: string;
  /** What the `Sort ·` menu calls it. */
  readonly label: string;
  /** The field name the topics endpoint takes, which is a different spelling. */
  readonly field: string;
}

export const SORTABLE_COLUMNS: readonly SortableColumn[] = [
  { columnId: "name", label: "topic", field: "name" },
  { columnId: "partitions", label: "partitions", field: "partitions" },
  { columnId: "replication", label: "replicas", field: "replicationFactor" },
  { columnId: "records", label: "records", field: "messageCount" },
  { columnId: "size", label: "size", field: "size" },
];

/**
 * The wire field for a column id, or `undefined` for a column the server cannot order by.
 *
 * `undefined` sends no `sort` at all, which is the right answer: the alternative is sending a field
 * the server does not know, having it ignore the parameter, and drawing an ascending arrow over
 * rows in the server's own order.
 */
export function sortFieldFor(columnId: string): string | undefined {
  return SORTABLE_COLUMNS.find((column) => column.columnId === columnId)?.field;
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
 * The page's voice line: `3 of 128 topics match · 1,536 partitions` (`SCREENS-V4.md` §4.6).
 *
 * ## Three figures, three documents, and every one of them can be absent
 *
 * The design's line mixes scopes on purpose: the match count tracks the filter and the two totals
 * do not. `matched` is the server's count for the current search, `clusterTopics` and
 * `clusterPartitions` come from the statistics document, and each clause is **dropped** rather than
 * filled when its figure is missing. `0 partitions` on a cluster whose sweep was incomplete would
 * be the never-zero rule broken in the most readable place on the screen; `3 of 0 topics match` is
 * worse still, because it is arithmetic nobody can do.
 *
 * The design's line ends `· 2 of them are drama queens`, and that clause is not written here. It is
 * a count of out-of-sync topics dressed as a quip, and the only figure this screen holds for it is
 * over the rows it happens to have — a cluster-wide claim made from one page, which is the sentence
 * this whole list was rebuilt to stop writing. The health chips say it per topic, accurately.
 */
export function topicsVoice(
  matched: number | undefined,
  clusterTopics: number | undefined,
  clusterPartitions: number | undefined,
): string {
  const topics = (count: number): string => `${count.toLocaleString()} ${count === 1 ? "topic" : "topics"}`;

  const head =
    matched === undefined
      ? clusterTopics === undefined
        ? undefined
        : topics(clusterTopics)
      : clusterTopics === undefined || clusterTopics === matched
        ? topics(matched)
        : `${matched.toLocaleString()} of ${clusterTopics.toLocaleString()} topics match`;

  if (head === undefined) return "";
  if (clusterPartitions === undefined) return head;
  return `${head} · ${clusterPartitions.toLocaleString()} partitions`;
}

/**
 * The rows on screen, as a CSV an operator can open.
 *
 * ## Why absent is an empty cell and not a zero
 *
 * The same rule the screen follows, applied where it is easiest to break: a spreadsheet sums a
 * column without asking, so a `0` written here for a topic whose size could not be read becomes a
 * cluster total that is quietly short — and unlike the screen, the file carries no dash and no
 * sentence to say so. An empty cell is what every spreadsheet treats as "no value", and it is the
 * only rendering of "not known" that survives the export.
 *
 * ## Quoting
 *
 * Every field is quoted and every embedded quote is doubled, which is RFC 4180 and is not
 * decoration: Kafka permits `.`, `_` and `-` in a topic name but a cleanup policy is
 * `compact,delete`, and an unquoted comma there silently shifts every column after it by one.
 * CRLF line endings for the same reason — it is what the format says, and what a spreadsheet on
 * Windows expects.
 */
export function topicsCsv(topics: readonly TopicRow[]): string {
  const cell = (value: string | number | undefined): string =>
    value === undefined ? '""' : `"${String(value).replaceAll('"', '""')}"`;

  const header = [
    "topic",
    "internal",
    "partitions",
    "replication factor",
    "health",
    "records",
    "size bytes",
    "messages per second",
    "cleanup policy",
  ]
    .map(cell)
    .join(",");

  const rows = topics.map((topic) =>
    [
      cell(topic.name),
      cell(topic.internal ? "yes" : "no"),
      cell(topic.partitions),
      cell(topic.replicationFactor),
      cell(topic.health),
      cell(topic.records),
      cell(topic.bytes),
      cell(topic.messagesPerSecond),
      cell(topic.cleanupPolicy),
    ].join(","),
  );

  return [header, ...rows].join("\r\n");
}
