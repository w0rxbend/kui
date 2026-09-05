/**
 * Every topic on the cluster.
 *
 * ## Why this table is windowed
 *
 * A production cluster has thousands of topics, and the design's own frame shows a count of 128 in
 * the drawer for a demonstration cluster. Putting four thousand rows in the document costs a second
 * of layout before anything appears and makes every subsequent scroll janky. `VirtualizedTable`
 * keeps the visible rows plus an overscan.
 *
 * Two rules that component already enforces and that are repeated here because both were paid for
 * with defects: the row height is *given* to it rather than measured out of the CSS, and the
 * container's size is *observed* for the component's lifetime rather than sampled once at mount —
 * measuring once drew five rows for a twelve-partition topic, because at mount the container had
 * not been laid out. The table also scrolls inside its own box, so the page never scrolls sideways.
 *
 * ## Internal topics are hidden by default and the switch says so
 *
 * `__consumer_offsets` and its friends are Kafka's own bookkeeping. They are in every cluster, they
 * are never what somebody opened this page to find, and they are not secret — so they are hidden
 * behind a switch that is visible from the start, not behind a preference somebody has to discover.
 * The count beside the switch always counts what is *shown*, so it agrees with the table under it.
 *
 * ## Filtering happens here, not on the server
 *
 * The search box narrows the rows the page already has. That is a deliberate limit and it is stated
 * on screen when it bites: with a page of topics loaded, "no match" means "no match on this page",
 * and a box that silently searched only what it could see would be a box that lies. See
 * `matchCount`.
 */

import type { JSX } from "@solidjs/web";
import { Show, createMemo, createSignal } from "solid-js";
import {
  Button,
  Checkbox,
  EmptyState,
  Icon,
  StatusPill,
  TextField,
  VirtualizedTable,
  type Column,
} from "@kui/kernel";
import { healthChip } from "./TopicPage.jsx";
import type { TopicRow } from "./types.js";

export interface TopicListPageProps {
  readonly topics: readonly TopicRow[];
  readonly loading?: boolean | undefined;
  /** Opens one topic. The whole row is the target; see `VirtualizedTable`. */
  readonly onOpen: (topic: TopicRow) => void;
  readonly onCreate?: (() => void) | undefined;
  readonly createDisabledReason?: string | undefined;
  /**
   * How many topics KUI could not describe.
   *
   * Reported rather than hidden. A list that is quietly four topics short is a list an operator
   * makes decisions from without knowing it is incomplete.
   */
  readonly incomplete?: number | undefined;
  /**
   * Overrides the table's measured viewport height, in pixels.
   *
   * Only a test or a benchmark passes it. It exists because a DOM implementation with no layout
   * engine reports every element as zero pixels tall, so a windowed table that could only measure
   * itself would draw nothing at all outside a real browser — and a suite that then asserted "no
   * rows" would be asserting jsdom's arithmetic rather than this page's.
   */
  readonly viewportHeight?: number | undefined;
}

export function TopicListPage(props: TopicListPageProps): JSX.Element {
  const [search, setSearch] = createSignal("");
  const [showInternal, setShowInternal] = createSignal(false);

  const visible = createMemo(() => {
    const needle = search().trim().toLowerCase();
    return props.topics.filter(
      (topic) =>
        (showInternal() || !topic.internal) &&
        (needle === "" || topic.name.toLowerCase().includes(needle)),
    );
  });

  const columns: readonly Column<TopicRow>[] = [
    {
      id: "name",
      header: "Topic",
      sortable: true,
      render: (topic) => (
        <span class="kui-topic-list__name">
          <Icon name="topics" size="14px" class="kui-topic-list__glyph" />
          {/* Truncated with a visible ellipsis *and* the whole name on the element, because a
              name clipped mid-character with nothing to say so reads as a different topic. The
              page the row opens shows it in full and never truncates it. */}
          <span class="kui-table__cell-strong kui-topic-list__label" title={topic.name}>
            {topic.name}
          </span>
          {/* A marker, not a colour: "internal" is a fact about the topic, and an operator
              scanning for their own topics needs to skip these at a glance. */}
          <Show when={topic.internal}>
            <span class="kui-topic-list__internal">internal</span>
          </Show>
        </span>
      ),
    },
    {
      id: "health",
      header: "Health",
      render: (topic) => {
        const chip = healthChip(topic.health);
        return (
          <StatusPill tone={chip.tone} dot>
            {chip.label}
          </StatusPill>
        );
      },
    },
    {
      id: "partitions",
      header: "Partitions",
      align: "numeric",
      sortable: true,
      render: (topic) => <span class="kui-table__cell-number">{topic.partitions}</span>,
    },
    {
      id: "replication",
      header: "Replicas",
      align: "numeric",
      render: (topic) => <span class="kui-table__cell-number">{topic.replicationFactor}</span>,
    },
    {
      id: "records",
      header: "Records",
      align: "numeric",
      sortable: true,
      /* A dash means "no value". It is deliberately not a zero: a topic whose partitions could not
         all be described has no honest total, and printing `0` for it is inventing one. */
      render: (topic) => <Quantity value={topic.records} format={(n) => n.toLocaleString()} />,
    },
    {
      id: "size",
      header: "Size",
      align: "numeric",
      render: (topic) => <Quantity value={topic.bytes} format={formatBytes} />,
    },
    {
      id: "policy",
      header: "Cleanup",
      render: (topic) => (
        <span class="kui-table__cell-muted">{topic.cleanupPolicy ?? "—"}</span>
      ),
    },
  ];

  return (
    <section class="kui-topic-list" aria-label="Topics">
      {/* The controls are outside everything that re-renders when rows arrive. The search box must
          not be rebuilt while somebody is typing in it; keeping it out of the boundary that the
          table lives in is the whole fix. */}
      <div class="kui-topic-list__controls">
        <TextField
          label="Search topics"
          labelHidden
          type="search"
          icon="search"
          placeholder="Search topics…"
          value={search()}
          onInput={setSearch}
        />
        <Checkbox
          label="Show internal topics"
          checked={showInternal()}
          onChange={setShowInternal}
        />
        <span class="kui-topic-list__count">{matchCount(visible().length, props.topics.length)}</span>
        <Show when={props.onCreate}>
          {(create) => (
            <Button
              variant="primary"
              icon="plus"
              {...(props.createDisabledReason === undefined
                ? {}
                : { disabled: true as const, disabledReason: props.createDisabledReason })}
              onClick={() => create()()}
            >
              Create topic
            </Button>
          )}
        </Show>
      </div>

      <Show when={props.incomplete !== undefined && props.incomplete > 0}>
        <p class="kui-topic-list__incomplete" role="status">
          {/* Named rather than swallowed. The alternative — a list four topics short with nothing
              on screen — is the shape in which an operator makes a decision from data they do not
              know is incomplete. */}
          {props.incomplete} {props.incomplete === 1 ? "topic" : "topics"} could not be described,
          and {props.incomplete === 1 ? "is" : "are"} missing from this list.
        </p>
      </Show>

      <VirtualizedTable<TopicRow>
        columns={columns}
        rows={visible()}
        rowKey={(topic) => topic.name}
        caption="Topics on this cluster"
        onRowClick={props.onOpen}
        {...(props.viewportHeight === undefined ? {} : { viewportHeight: props.viewportHeight })}
        {...(props.viewportHeight === undefined ? {} : { viewportHeight: props.viewportHeight })}
        empty={
          <Show
            when={search().trim() !== ""}
            fallback={
              <EmptyState
                kind="empty"
                title="No topics yet."
                description="A topic appears here as soon as one is created, by this page or by anything else that talks to the cluster."
              />
            }
          >
            <EmptyState
              kind="filtered"
              title="No topic matches that text."
              description="The search narrows the topics already loaded. Clearing it shows them all."
            />
          </Show>
        }
      />
    </section>
  );
}

/**
 * The count beside the switch.
 *
 * It always names both numbers when they differ, because "12 topics" over a table of twelve rows
 * that is really a cluster of four thousand is the most confidently wrong sentence this page could
 * write.
 */
export function matchCount(shown: number, total: number): string {
  if (shown === total) return `${total.toLocaleString()} ${total === 1 ? "topic" : "topics"}`;
  return `${shown.toLocaleString()} of ${total.toLocaleString()} topics`;
}

/**
 * A number, or a dash when there is not one.
 *
 * The dash is `—` with a screen-reader word beside it: a bare dash is announced as "dash" or as
 * nothing at all depending on the reader, and "not known" is the fact.
 */
function Quantity(props: {
  readonly value: number | undefined;
  readonly format: (value: number) => string;
}): JSX.Element {
  return (
    <Show
      when={props.value !== undefined}
      fallback={
        <span class="kui-table__cell-muted">
          <span aria-hidden="true">—</span>
          <span class="kui-visually-hidden">not known</span>
        </span>
      }
    >
      <span class="kui-table__cell-number">{props.format(props.value as number)}</span>
    </Show>
  );
}

/** Bytes at one decimal place. Decimal units, because that is what a broker's own metrics use. */
export function formatBytes(bytes: number): string {
  const units = ["B", "kB", "MB", "GB", "TB", "PB"] as const;
  let value = Math.max(0, bytes);
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${unit === 0 ? String(value) : value.toFixed(1)} ${units[unit] ?? "B"}`;
}
