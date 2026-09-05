import type { JSX } from "@solidjs/web";
import { For, Show, Repeat } from "solid-js";
import { Checkbox } from "./Checkbox.jsx";
import { EmptyState, Skeleton } from "./EmptyState.jsx";
import { Icon } from "./Icon.jsx";

/**
 * How a column's cells line up.
 *
 * Two options, because only two are ever right. Text is read *along* a row and wants the same left
 * edge in every one of them. Numbers are compared *down* a column and only line up if their units
 * digits do. Centring is the third option and it is wrong for both, because it puts nothing in a
 * predictable place.
 */
export type ColumnAlign = "start" | "numeric";

/** One column of a table. */
export interface Column<Row> {
  /**
   * The column's stable name. It is what goes into the sort state and therefore into the URL, so
   * it must not change when the header text does.
   */
  readonly id: string;
  /** The label. Written in sentence case here and upper-cased by the stylesheet — see below. */
  readonly header: string;
  readonly render: (row: Row) => JSX.Element;
  /** Requires `onSortChange`. Without it the header is drawn as plain text, not a dead button. */
  readonly sortable?: boolean;
  /** Any CSS length, or omitted to let the browser decide. */
  readonly width?: string;
  /** Set `numeric` for offsets, counts, sizes, rates and lag. */
  readonly align?: ColumnAlign;
}

export type SortOrder = "asc" | "desc";

export interface Sort {
  readonly columnId: string;
  readonly order: SortOrder;
}

/**
 * Clicking a header cycles ascending, descending, then back to unsorted.
 *
 * The third state matters and is the one usually left out. Without it there is no way back to the
 * server's natural order, which for a list of brokers is broker id and for a list of records is
 * offset — and "the order the data actually has" is not reachable by sorting on any column.
 */
export function nextSort(current: Sort | null, columnId: string): Sort | null {
  if (current === null || current.columnId !== columnId) return { columnId, order: "asc" };
  if (current.order === "asc") return { columnId, order: "desc" };
  return null;
}

function ariaSort(current: Sort | null, columnId: string): "ascending" | "descending" | "none" {
  if (current === null || current.columnId !== columnId) return "none";
  return current.order === "asc" ? "ascending" : "descending";
}

/** Selection is controlled by the caller: the set of selected row keys, and a callback. */
export interface TableSelection {
  readonly selectedKeys: ReadonlySet<string>;
  readonly onChange: (next: ReadonlySet<string>) => void;
  /** How a row names itself in the checkbox's accessible name: "Select {label(row)}". */
  readonly rowLabel?: (key: string) => string;
}

export interface DataTableProps<Row> {
  readonly columns: readonly Column<Row>[];
  readonly rows: readonly Row[];
  /**
   * A stable identity per row. Rows are keyed by it rather than by position, so a re-sort moves
   * the existing elements instead of destroying and rebuilding them — which is what keeps a text
   * selection, an open tooltip and the keyboard focus where the user left them.
   */
  readonly rowKey: (row: Row) => string;
  /**
   * The table's accessible name. Required: a screen reader listing the landmarks on a page with
   * four tables reads out four things called "table".
   */
  readonly caption: string;

  readonly sort?: Sort | null;
  /**
   * Sorting is fully controlled. A `sortable` column with no `onSortChange` renders as an ordinary
   * header rather than as a button that does nothing, because a control that looks live and is not
   * is worse than no control.
   */
  readonly onSortChange?: (next: Sort | null) => void;

  readonly selection?: TableSelection;

  /** Makes the whole row a control. See the note on the row element below. */
  readonly onRowClick?: (row: Row) => void;

  readonly loading?: boolean;
  /** How many placeholder rows to draw while loading an empty table. Six fills a panel. */
  readonly skeletonRows?: number;

  /**
   * What to draw when there are no rows. Compose an `<EmptyState>` here: the table does not decide
   * whether "no rows" means nothing-yet, filtered-out, unavailable or forbidden, because only the
   * caller knows which, and the four are never interchangeable (§4.16).
   */
  readonly empty?: JSX.Element;

  readonly testId?: string;
}

/**
 * A plain table: every row is in the document.
 *
 * ## What this is for
 *
 * Lists of tens or a few hundred rows — brokers, consumer groups, schema versions, connectors,
 * partitions. When a list can exceed a few hundred, use `VirtualizedTable`, which has the same
 * columns and the same look and differs only in how the rows reach the document.
 *
 * ## The scroll box is not optional
 *
 * A table cannot be laid out narrower than its own widest content. The clusters list has nine
 * columns with headers like "UNDER-REPLICATED"; on a 1565px window its intrinsic minimum came to
 * 1456px inside a 1197px column, so `width: 100%` lost, the table stuck out of its column, and the
 * *document* grew a horizontal scrollbar. Every screen then scrolled sideways together — the
 * drawer slid off the left while the reader chased a column off the right — and one column was
 * still squeezed hard enough to break a word across two lines.
 *
 * So the table is wrapped in `.kui-table-scroll`, which is `overflow-x: auto`, and that wrapper is
 * what this component returns. Wide content scrolls inside its own box; the page body never
 * scrolls horizontally. The wrapper repeats the panel's radius because it clips the table, and a
 * square clip over a rounded panel shows as four sharp corners.
 *
 * ## Loading does not resize the table
 *
 * Two different loadings, because they are two different situations:
 *
 *   - Loading with rows already on screen — a refresh, a re-sort, a page change — dims the rows
 *     that are there. Replacing them would collapse the table to nothing, jump the page, and jump
 *     it back a moment later.
 *   - Loading with nothing on screen — the first fetch — draws skeleton rows at the real row
 *     height, so the panel is already the size it will be when the data lands.
 *
 * Either way `aria-busy` is on the table, which is what actually tells a screen reader that
 * something is coming; the visual treatment says it to everyone else.
 *
 * ## Accessibility contract
 *
 * A real `<table>` with `<th scope="col">`, so a screen reader can say which column a cell belongs
 * to. A sortable header is a `<button>` *inside* the `<th>` — the `<th>` itself is not clickable,
 * because a clickable `<th>` is invisible to the keyboard — and the `<th>` carries `aria-sort`.
 * The `<caption>` is visually hidden and names the table.
 */
export function DataTable<Row>(props: DataTableProps<Row>): JSX.Element {
  const columnCount = () => props.columns.length + (props.selection === undefined ? 0 : 1);
  const isEmpty = () => props.rows.length === 0;
  const showSkeleton = () => props.loading === true && isEmpty();

  const allKeys = () => props.rows.map(props.rowKey);
  const selectedCount = () => {
    const selection = props.selection;
    if (selection === undefined) return 0;
    return allKeys().filter((key) => selection.selectedKeys.has(key)).length;
  };

  function toggleAll(checked: boolean): void {
    const selection = props.selection;
    if (selection === undefined) return;
    const next = new Set(selection.selectedKeys);
    for (const key of allKeys()) {
      if (checked) next.add(key);
      else next.delete(key);
    }
    selection.onChange(next);
  }

  function toggleOne(key: string, checked: boolean): void {
    const selection = props.selection;
    if (selection === undefined) return;
    const next = new Set(selection.selectedKeys);
    if (checked) next.add(key);
    else next.delete(key);
    selection.onChange(next);
  }

  return (
    <div class="kui-table-scroll" data-testid={props.testId}>
      <table
        class={["kui-table", { "kui-table--loading": props.loading === true && !isEmpty() }]}
        // ARIA booleans are the strings "true" and "false", not JS booleans: in HTML a boolean
        // attribute means "present or absent", and an absent `aria-busy` says nothing, while
        // `aria-busy="false"` says "not busy". Solid 2's JSX types enforce the distinction.
        aria-busy={props.loading === true ? "true" : "false"}
      >
        {/* Visually hidden, because the panel already has a heading on the page; present, because
            the accessibility tree does not have that heading's context. */}
        <caption class="kui-visually-hidden">{props.caption}</caption>

        <thead>
          <tr>
            <Show when={props.selection !== undefined}>
              <th scope="col" class="kui-table__header-cell kui-table__header-cell--select">
                <Checkbox
                  labelHidden
                  label={`Select all ${props.rows.length} rows`}
                  checked={selectedCount() > 0 && selectedCount() === props.rows.length}
                  // Mixed, not unchecked. A header checkbox that showed "unchecked" while three of
                  // twenty rows were selected would be lying about the state below it, and clicking
                  // it would then select everything rather than clear the three.
                  indeterminate={selectedCount() > 0 && selectedCount() < props.rows.length}
                  disabled={isEmpty()}
                  onChange={toggleAll}
                  testId="select-all"
                />
              </th>
            </Show>

            <For each={props.columns}>
              {(column) => (
                <th
                  scope="col"
                  class={[
                    "kui-table__header-cell",
                    { "kui-table__header-cell--numeric": column.align === "numeric" },
                  ]}
                  style={column.width === undefined ? undefined : { width: column.width }}
                  aria-sort={ariaSort(props.sort ?? null, column.id)}
                >
                  <Show
                    when={column.sortable === true && props.onSortChange !== undefined}
                    fallback={column.header}
                  >
                    <button
                      type="button"
                      class="kui-table__sort"
                      onClick={() => props.onSortChange?.(nextSort(props.sort ?? null, column.id))}
                    >
                      {column.header}
                      <SortMark sort={props.sort ?? null} columnId={column.id} />
                    </button>
                  </Show>
                </th>
              )}
            </For>
          </tr>
        </thead>

        <tbody class="kui-table__body">
          <Show when={showSkeleton()}>
            {/* `Repeat` draws a fixed count with no diffing, which is what a placeholder wants:
                these rows have no identity and nothing about them changes. */}
            <Repeat count={props.skeletonRows ?? 6}>
              {(index) => (
                <tr class="kui-table__row kui-table__row--skeleton" aria-hidden="true">
                  <Repeat count={columnCount()}>
                    {() => (
                      <td class="kui-table__cell">
                        {/* Varied widths, so a loading table reads as text arriving rather than as
                            a barcode. The pattern is deterministic, not random: a placeholder that
                            reshuffles on every render is a flicker. */}
                        <Skeleton width={`${[70, 45, 60, 35, 80][index % 5] ?? 60}%`} />
                      </td>
                    )}
                  </Repeat>
                </tr>
              )}
            </Repeat>
          </Show>

          <For each={props.rows} keyed={props.rowKey}>
            {(row) => {
              const key = () => props.rowKey(row());
              const clickable = () => props.onRowClick !== undefined;
              return (
                <tr
                  class={[
                    "kui-table__row",
                    {
                      "kui-table__row--clickable": clickable(),
                      "kui-table__row--selected":
                        props.selection?.selectedKeys.has(key()) === true,
                    },
                  ]}
                  aria-selected={
                    props.selection === undefined
                      ? undefined
                      : props.selection.selectedKeys.has(key())
                        ? "true"
                        : "false"
                  }
                  // A clickable row is a control, so it is in the tab order and answers to Enter
                  // and Space. A row that only answers to a mouse is a row half the operators
                  // cannot use, and this is the failure that passes every visual review.
                  tabindex={clickable() ? 0 : undefined}
                  onClick={clickable() ? () => props.onRowClick?.(row()) : undefined}
                  onKeyDown={
                    clickable()
                      ? (event: KeyboardEvent) => {
                          if (event.key !== "Enter" && event.key !== " ") return;
                          // Space scrolls the page by default, and a table that jumped a screenful
                          // every time a row was activated would be unusable from the keyboard.
                          event.preventDefault();
                          props.onRowClick?.(row());
                        }
                      : undefined
                  }
                >
                  <Show when={props.selection}>
                    {(selection) => (
                      <td class="kui-table__cell kui-table__cell--select">
                        <Checkbox
                          labelHidden
                          label={`Select ${selection().rowLabel?.(key()) ?? key()}`}
                          checked={selection().selectedKeys.has(key())}
                          onChange={(checked) => toggleOne(key(), checked)}
                        />
                      </td>
                    )}
                  </Show>
                  <For each={props.columns}>
                    {(column) => (
                      <td
                        class={[
                          "kui-table__cell",
                          { "kui-table__cell--numeric": column.align === "numeric" },
                        ]}
                      >
                        {column.render(row())}
                      </td>
                    )}
                  </For>
                </tr>
              );
            }}
          </For>

          {/* The empty state sits *inside* the table, so the header row stays above it and the
              columns still say what the table would have held. A header with nothing under it and
              no sentence is exactly the rendering that leaves a reader unable to tell "no topics"
              from "the request failed". */}
          <Show when={isEmpty() && props.loading !== true}>
            <tr>
              <td colspan={columnCount()} class="kui-table__empty">
                {props.empty ?? (
                  <EmptyState
                    kind="empty"
                    title="Nothing to show."
                    description="There is no data here yet."
                  />
                )}
              </td>
            </tr>
          </Show>
        </tbody>
      </table>
    </div>
  );
}

/**
 * The direction arrow in a sortable header.
 *
 * The unsorted case draws a placeholder of the same width rather than nothing, so a column does
 * not shift sideways the moment it becomes sorted — which, on a nine-column table, moves every
 * column to its right and makes the click look like it did something else.
 */
function SortMark(props: { readonly sort: Sort | null; readonly columnId: string }): JSX.Element {
  return (
    <Show
      when={props.sort !== null && props.sort.columnId === props.columnId}
      fallback={<span class="kui-table__sort-placeholder" aria-hidden="true" />}
    >
      <Show when={props.sort?.order === "asc"} fallback={<Icon name="chevron-down" />}>
        <Icon name="chevron-up" />
      </Show>
    </Show>
  );
}
