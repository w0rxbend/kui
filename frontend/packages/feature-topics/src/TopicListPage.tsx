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
 * ## Where each control is applied, and why the screen says so
 *
 * The search, the page, the page size and the order are the **server's**. That is the whole reason
 * `TopicListQuery` exists: a search that only looks at the twenty-five rows it was handed is a
 * search that lies, and it lies in the most convincing way — by finding nothing and saying so.
 *
 * Two of the four facet chips have no counterpart on the wire. `Out of sync` and `Compacted` are
 * derived from fields the list carries but does not index, so they narrow the page and **the page
 * says they do**, in a sentence next to the count. Hiding that would reproduce the defect the
 * server-side search was built to remove, one control over. `All` and `Internal` are the server's:
 * they are the `showInternal` parameter, which is what makes those two honest at any cluster size.
 *
 * ## One selection, two treatments
 *
 * The selected set is the caller's, and the table and the cards are two renderings of it
 * (`SCREENS-V4.md` §3.7: the design's two ticks are on cards and the same set must survive the
 * switch to the table). Neither list owns it, because a list that owned it would clear it every
 * time the operator changed how the rows are drawn.
 */

import type { JSX } from "@solidjs/web";
import { Show, createEffect, createMemo, createSignal, onCleanup } from "solid-js";
import {
  BulkActionBar,
  Button,
  EmptyState,
  Icon,
  Pagination,
  SegmentedControl,
  Select,
  SingleSelectChips,
  StatusPill,
  Switch,
  Tag,
  TextField,
  VirtualizedTable,
  formatRate,
  type BulkAction,
  type Column,
  type Sort,
} from "@kui/kernel";
import { healthChip } from "./TopicPage.jsx";
import { TopicCards } from "./TopicCards.jsx";
import { isCompacted, matchesFilter, type TopicFilter } from "./topicList.js";
import type { TopicRow } from "./types.js";

/**
 * What the list is currently showing, and what the operator has asked for.
 *
 * The whole of it is the *server's* to apply except `facet`, which is half and says so — see the
 * header. This page used to filter, search and sort the rows it happened to hold, which is honest
 * for one page and wrong for a cluster with four thousand topics.
 */
export interface TopicListQuery {
  /** Substring match on the name. */
  readonly search: string;
  /**
   * Which of the four chips is lit.
   *
   * `all` and `internal` become the `showInternal` request parameter; `out-of-sync` and `compacted`
   * narrow the page the server sent. {@link isServerFacet} is the one place that distinction lives.
   */
  readonly facet: TopicFilter;
  /** `null` is the server's own order. */
  readonly sort: Sort | null;
  /** One-based, like the buttons. */
  readonly page: number;
  readonly pageSize: number;
}

/**
 * Whether a facet is one the cluster can apply.
 *
 * One function rather than two conditions, because the two things that depend on it must not be
 * able to disagree: the rows are narrowed here only when it is `false`, and the sentence saying so
 * is drawn only when it is `false`. Split into two `facet === …` tests, a chip added to `FACETS`
 * would end up narrowing the page silently or announcing a narrowing that did not happen — and a
 * filter that quietly narrows a page while looking like it narrows a cluster is the defect this
 * screen already fixed once, for the search box.
 *
 * Exported so a caller can ask the same question without re-deriving it. `toTopicQuery` deliberately
 * does not: it maps the one facet the wire has (`internal` → `showInternal`) and would answer the
 * remaining two identically whichever way this went.
 */
export function isServerFacet(facet: TopicFilter): boolean {
  return facet === "all" || facet === "internal";
}

/** Table or cards. Persisted per user, for the reason `SCREENS.md` §2.12 gives. */
export type TopicView = "table" | "cards";

/**
 * Where the chosen view is remembered.
 *
 * The design is explicit that the choice must persist *per user*, not per visit: "an operator who
 * prefers cards and gets a table on every navigation will conclude the control does not work". It
 * lives in `localStorage` rather than in the query string, because it is a preference about how this
 * person likes to read a list and not a property of the list being read — a link somebody sends
 * should show the recipient the recipient's own preferred view.
 */
const VIEW_STORAGE_KEY = "kui.topics.view";

/** The same, for the statistics switch. The design draws it on; a reader who turns it off means it. */
const STATISTICS_STORAGE_KEY = "kui.topics.statistics";

export function storedView(): TopicView {
  try {
    return window.localStorage.getItem(VIEW_STORAGE_KEY) === "cards" ? "cards" : "table";
  } catch {
    // A private window, or a browser configured to block site data. A preference that cannot be
    // read is not an error; it is the default.
    return "table";
  }
}

export function rememberView(view: TopicView): void {
  try {
    window.localStorage.setItem(VIEW_STORAGE_KEY, view);
  } catch {
    /* Nothing to do: the view still changes, it just will not be there next time. */
  }
}

/** The statistics switch is **on** unless this reader has turned it off. `SCREENS-V4.md` §4.6. */
export function storedStatistics(): boolean {
  try {
    return window.localStorage.getItem(STATISTICS_STORAGE_KEY) !== "off";
  } catch {
    return true;
  }
}

export function rememberStatistics(on: boolean): void {
  try {
    window.localStorage.setItem(STATISTICS_STORAGE_KEY, on ? "on" : "off");
  } catch {
    /* As above: the region still opens and closes, it just will not be remembered. */
  }
}

export const DEFAULT_TOPIC_QUERY: TopicListQuery = {
  search: "",
  facet: "all",
  sort: null,
  page: 1,
  pageSize: 32,
};

/**
 * The sort control's options, in the column ids the table already sorts by.
 *
 * One vocabulary rather than two, so the `Sort ·` menu and a click on a column heading write the
 * same `Sort` and cannot disagree about which order the list is in. The ids not offered here —
 * health and cleanup policy — are the ones the server sorts by nothing, and they are not marked
 * sortable in the table either, so no control anywhere offers an order the cluster cannot produce.
 */
const SORT_OPTIONS: readonly { readonly value: string; readonly label: string }[] = [
  { value: "", label: "the server's order" },
  { value: "name", label: "topic" },
  { value: "partitions", label: "partitions" },
  { value: "replication", label: "replicas" },
  { value: "records", label: "records" },
  { value: "size", label: "size" },
];

const FACETS: readonly { readonly value: TopicFilter; readonly label: string }[] = [
  { value: "all", label: "All" },
  { value: "internal", label: "Internal" },
  { value: "out-of-sync", label: "Out of sync" },
  { value: "compacted", label: "Compacted" },
];

export interface TopicListPageProps {
  /** This page of rows, exactly as the server sent them. Narrowed only by a page-scoped facet. */
  readonly topics: readonly TopicRow[];
  readonly loading?: boolean | undefined;
  readonly query: TopicListQuery;
  /**
   * Asks for a different view of the list. The screen owning the fetch decides what to do with it;
   * this component never mutates the query it was given.
   */
  readonly onQueryChange: (query: TopicListQuery) => void;
  /**
   * How many topics match, across every page. `undefined` when the server did not count — which is
   * not zero, and the paginator draws numbered buttons only where there is a known last page.
   */
  readonly totalItems?: number | undefined;
  /** Opens one topic. The whole row is the target; see `VirtualizedTable`. */
  readonly onOpen: (topic: TopicRow) => void;
  readonly onCreate?: (() => void) | undefined;
  readonly createDisabledReason?: string | undefined;
  /**
   * The cluster-wide statistics region, built by the caller.
   *
   * A slot rather than a document, because this component must not be able to compute those totals:
   * it holds the page's rows and the region's whole point is that its figures are not about them.
   * See `TopicStatisticsRegion`.
   */
  readonly statistics?: JSX.Element | undefined;
  /**
   * The line above the controls, already composed — see `topicsVoice`.
   *
   * A string rather than the figures, because it is written from *two* documents: the match count
   * is this page's and the partition total is the cluster's, and only the caller holds both. A
   * component handed the page's rows and asked to write the sentence would have to guess at the
   * half it cannot see, which is how the count in it would come to track the search box.
   */
  readonly voice?: string | undefined;
  /** The selected topic names. One set, shared by the table and the cards — see the header. */
  readonly selected?: ReadonlySet<string> | undefined;
  readonly onSelectionChange?: ((next: ReadonlySet<string>) => void) | undefined;
  /** What the bulk bar offers. Empty or absent draws no bar, whatever is selected. */
  readonly bulkActions?: readonly BulkAction[] | undefined;
  /** Downloads the rows on screen. Absent hides the control entirely. */
  readonly onExport?: (() => void) | undefined;
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
  /**
   * What is in the search box right now, which is not the same as what has been asked for.
   *
   * The box has to keep up with typing, and the server must not be asked once per keystroke. So the
   * text is local and immediate, and the *query* follows it after a pause. Without the local copy
   * the caret would jump about as answers arrived; without the pause, typing "payments" is eight
   * requests, of which seven are already stale when they are sent.
   */
  const [typed, setTyped] = createSignal(props.query.search);
  const [view, setView] = createSignal<TopicView>(storedView());
  const [statisticsOpen, setStatisticsOpen] = createSignal(storedStatistics());
  let searchTimer: ReturnType<typeof setTimeout> | undefined;

  // A query the operator abandoned by navigating away must not arrive afterwards and re-fetch.
  onCleanup(() => clearTimeout(searchTimer));

  /**
   * Keeps the box in step when the query changes from somewhere else — a cleared filter, a restored
   * address. It deliberately does not fire while the operator is mid-word: this only runs when the
   * *query* changed, and the query changes from typing only after the pause has already elapsed.
   */
  createEffect(
    () => props.query.search,
    (search: string) => {
      if (search !== typed().trim()) setTyped(search);
    },
  );

  /** Any change to the view resets to the first page. Page 7 of a different filter is not a page. */
  const ask = (change: Partial<TopicListQuery>): void => {
    props.onQueryChange({ ...props.query, page: 1, ...change });
  };

  const facet = () => props.query.facet;

  /*
   * The rows the server sent, narrowed only where the server could not narrow them itself.
   *
   * `matchesFilter` is `topicList.ts`'s, not a second copy: `compact,delete` is a real and common
   * value of `cleanup.policy`, and a membership test rather than an equality one is the difference
   * between "compacted" meaning what it says and quietly excluding every topic that also deletes.
   */
  const visible = createMemo(() =>
    isServerFacet(facet())
      ? props.topics
      : props.topics.filter((topic) => matchesFilter(topic, facet())),
  );

  const selected = (): ReadonlySet<string> => props.selected ?? new Set<string>();

  /** The selection, in `DataTable`'s vocabulary. Absent when the caller does not want selection. */
  const selection = () => {
    const onChange = props.onSelectionChange;
    if (onChange === undefined) return undefined;
    return {
      selectedKeys: selected(),
      onChange,
      rowLabel: (key: string) => key,
    };
  };

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
      sortable: true,
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
      sortable: true,
      header: "Size",
      align: "numeric",
      render: (topic) => <Quantity value={topic.bytes} format={formatBytes} />,
    },
    {
      id: "rate",
      header: "Msg/s",
      align: "numeric",
      /* Absent far more often than present: the rate is differenced from two snapshots, so a topic
         KUI has scraped once has no rate yet. `0` is the figure a silent topic legitimately has, so
         the two must never render alike — hence the dash with its word rather than a zero. */
      render: (topic) => <Quantity value={topic.messagesPerSecond} format={formatRate} />,
    },
    {
      id: "policy",
      header: "Cleanup",
      /*
       * Nothing at all when the batch that reads `cleanup.policy` did not cover this topic.
       *
       * Not `delete`, which is Kafka's default and would be this screen guessing at a setting it
       * was not told; and not an em dash either, which in every other column on this page means "a
       * figure nobody could measure". A cleanup policy is not a figure — it is a word the topic
       * either has or has not been described with — so the honest rendering of "not described" is
       * an empty cell beside a health chip that already says the topic was not described.
       */
      render: (topic) => (
        <Show when={topic.cleanupPolicy}>
          {(policy) => (
            <Tag tone={isCompacted(topic) ? "info" : "neutral"} class="kui-topic-list__policy">
              {policy()}
            </Tag>
          )}
        </Show>
      ),
    },
  ];

  return (
    <section class="kui-topic-list" aria-label="Topics">
      <Show when={props.voice}>
        {(line) => (
          /* A list carries a voice line and an object page does not (`SCREENS.md` §5.2). `role` is
             deliberately absent: the figures are repeated in the statistics tiles and in the count
             beside the controls, and announcing this line on every keystroke would talk over the
             search box. */
          <p class="kui-topic-list__voice">{line()}</p>
        )}
      </Show>

      {/* The controls are outside everything that re-renders when rows arrive. The search box must
          not be rebuilt while somebody is typing in it; keeping it out of the boundary that the
          table lives in is the whole fix. */}
      <div class="kui-topic-list__controls">
        <SegmentedControl<TopicView>
          label="View"
          size="sm"
          value={view()}
          segments={[
            { value: "table", label: "Table", icon: "table" },
            { value: "cards", label: "Cards", icon: "cards" },
          ]}
          onChange={(next: TopicView) => {
            setView(next);
            rememberView(next);
          }}
        />

        <Select<string>
          label="Sort topics by"
          labelHidden
          prefix="Sort ·"
          size="sm"
          value={props.query.sort?.columnId ?? ""}
          options={SORT_OPTIONS}
          onChange={(columnId) =>
            ask({
              // Keeping the direction across a change of field: an operator who asked for
              // descending and then changed the column meant descending by the new one.
              sort:
                columnId === ""
                  ? null
                  : { columnId, order: props.query.sort?.order ?? "asc" },
            })
          }
        />

        <Button
          variant="ghost"
          size="sm"
          icon="sort"
          {...(props.query.sort === null
            ? {
                disabled: true as const,
                disabledReason:
                  "There is no direction to reverse while the list is in the server's own order.",
              }
            : {})}
          onClick={() =>
            ask({
              sort:
                props.query.sort === null
                  ? null
                  : {
                      columnId: props.query.sort.columnId,
                      order: props.query.sort.order === "asc" ? "desc" : "asc",
                    },
            })
          }
        >
          {props.query.sort?.order === "desc" ? "Descending" : "Ascending"}
        </Button>

        <Switch
          label="Show statistics"
          checked={statisticsOpen()}
          onChange={(on) => {
            setStatisticsOpen(on);
            rememberStatistics(on);
          }}
          testId="topic-statistics-switch"
        />

        <TextField
          label="Search topics"
          labelHidden
          type="search"
          icon="search"
          placeholder="Search topics…"
          value={typed()}
          onInput={(text) => {
            setTyped(text);
            clearTimeout(searchTimer);
            // Long enough that a typed word is one request, short enough that the list feels like it
            // is answering rather than thinking.
            searchTimer = setTimeout(() => ask({ search: text.trim() }), 300);
          }}
        />

        <span class="kui-topic-list__count">
          {matchCount(
            visible().length,
            props.totalItems,
            isServerFacet(facet()) ? undefined : props.topics.length,
          )}
        </span>

        <Show when={props.onExport}>
          {(exportRows) => (
            <Button variant="secondary" icon="download" onClick={() => exportRows()()}>
              Export
            </Button>
          )}
        </Show>

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

      {/* The region is the caller's; the switch is the page's. Behind `Show` rather than hidden
          with CSS, so a reader who turned it off is not paying for a fetch's worth of DOM they
          cannot see — and so the tab order matches what is on screen. */}
      <Show when={statisticsOpen()}>{props.statistics}</Show>

      <SingleSelectChips<TopicFilter>
        label="Filter topics"
        options={FACETS}
        value={facet()}
        onChange={(next) => ask({ facet: next })}
        testId="topic-facets"
      />

      <Show when={!isServerFacet(facet())}>
        <p class="kui-topic-list__scope" role="status">
          {/* The sentence the server-side search exists to make unnecessary, printed exactly where
              it is still true. KUI's topic index has no column for either of these, so the chip
              narrows the page it was handed — and a filter that narrows a page while looking like
              it narrows a cluster is the defect this page already fixed once, for the search box. */}
          “{FACETS.find((one) => one.value === facet())?.label}” narrows the{" "}
          {props.topics.length.toLocaleString()} topics on this page. The cluster is not searched for
          it, so a matching topic on another page is not shown.
        </p>
      </Show>

      <Show when={props.incomplete !== undefined && props.incomplete > 0}>
        <p class="kui-topic-list__incomplete" role="status">
          {/* Named rather than swallowed. The alternative — a list four topics short with nothing
              on screen — is the shape in which an operator makes a decision from data they do not
              know is incomplete. */}
          {props.incomplete} {props.incomplete === 1 ? "topic" : "topics"} could not be described,
          and {props.incomplete === 1 ? "is" : "are"} missing from this list.
        </p>
      </Show>

      {/* The query, the filtering and the sorting belong to the *view* rather than to the table, so
          switching between the two shows the same topics in a different shape — it does not reset
          anything. That is `SCREENS.md` §3.3's rule and it is the difference between a view toggle
          and a second screen. */}
      <Show when={view() === "cards"} fallback={
      <VirtualizedTable<TopicRow>
        columns={columns}
        rows={visible()}
        rowKey={(topic) => topic.name}
        caption="Topics on this cluster"
        onRowClick={props.onOpen}
        selection={selection()}
        /* Sorted by the server, for the same reason it searches: re-sorting one page of a list that
           the server paginated shows the right rows in an order no page boundary matches. */
        sort={props.query.sort}
        onSortChange={(sort) => ask({ sort })}
        {...(props.viewportHeight === undefined ? {} : { viewportHeight: props.viewportHeight })}
        empty={<ListEmpty query={props.query} />}
      />
      }>
        <Show when={visible().length > 0} fallback={<ListEmpty query={props.query} />}>
          <TopicCards
            topics={visible()}
            onOpen={props.onOpen}
            formatBytes={formatBytes}
            selected={selected()}
            {...(props.onSelectionChange === undefined
              ? {}
              : { onSelectionChange: props.onSelectionChange })}
          />
        </Show>
      </Show>

      <Pagination
        page={props.query.page}
        pageSize={props.query.pageSize}
        total={props.totalItems}
        shown={visible().length}
        onPage={(page: number) => props.onQueryChange({ ...props.query, page })}
        onPageSize={(pageSize: number) => ask({ pageSize })}
        pageSizes={[8, 16, 32]}
        /* When the server did not count, a full page is the only evidence that another exists. It
           can be wrong by one — a cluster with exactly two pages' worth offers a third that turns
           out to be empty — which is a smaller lie than hiding a page that is there. */
        hasNext={props.totalItems === undefined && props.topics.length === props.query.pageSize}
        label="Topic list pages"
      />

      {/* Floating, so it does not move the list underneath it, and absent at zero selection: a bar
          that is always in the document is a strip of the window nobody can use. */}
      <Show when={props.bulkActions !== undefined && props.bulkActions.length > 0}>
        <BulkActionBar
          count={selected().size}
          actions={props.bulkActions ?? []}
          noun="topic"
          onDismiss={() => props.onSelectionChange?.(new Set<string>())}
          testId="topic-bulk-bar"
        />
      </Show>
    </section>
  );
}

/**
 * The four kinds of nothing this list can show, told apart.
 *
 * Written once and used by both treatments, because the table's empty slot and the cards' fallback
 * had drifted into two copies of the same three sentences — and the copy in the cards branch was
 * the one that never learned about the facets.
 */
function ListEmpty(props: { readonly query: TopicListQuery }): JSX.Element {
  const searched = () => props.query.search.trim() !== "";
  const faceted = () => props.query.facet !== "all";
  return (
    <Show
      when={searched() || faceted()}
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
        title={searched() ? "No topic matches that text." : "No topic matches that filter."}
        description={
          searched()
            ? /* It searches the whole cluster now, not the rows on screen, so the sentence that
                 said otherwise would have been telling the operator to distrust a true answer. */
              "No topic on this cluster has that in its name. Clearing the search shows them all."
            : "No topic on this page matches. Choosing “All” shows every topic the cluster listed."
        }
      />
    </Show>
  );
}

/**
 * The count beside the controls.
 *
 * It always names both numbers when they differ, because "12 topics" over a table of twelve rows
 * that is really a cluster of four thousand is the most confidently wrong sentence this page could
 * write.
 *
 * @param onPage present only while a chip the cluster cannot apply is narrowing what was sent. The
 *   comparison then has to be against the page rather than against the cluster: "2 of 4,000" would
 *   claim the server found two, when what happened is that this page kept two of thirty-two.
 */
export function matchCount(
  shown: number,
  total: number | undefined,
  onPage?: number | undefined,
): string {
  if (onPage !== undefined) {
    return `${shown.toLocaleString()} of ${onPage.toLocaleString()} on this page`;
  }
  /*
   * The server did not count. Saying "25 topics" here would be a claim about the cluster made from
   * the size of one page — the exact sentence this function exists to avoid — so it says what is
   * actually known: how many are on screen.
   */
  if (total === undefined) {
    return `${shown.toLocaleString()} ${shown === 1 ? "topic" : "topics"} shown`;
  }
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
