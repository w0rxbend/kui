import type { JSX } from "@solidjs/web";
import { createEffect, createMemo, createSignal, For, onSettled, Show } from "solid-js";
import type { Column, Sort } from "./DataTable.jsx";
import { nextSort } from "./DataTable.jsx";
import { EmptyState } from "./EmptyState.jsx";
import { COMPACT_ROW_SAVING_PX, createIsCompact } from "./density.js";
import { Icon } from "./Icon.jsx";
import { slice, trailingHeightPx } from "./window.js";

/**
 * A table that keeps only the visible rows in the document.
 *
 * ## Why this exists
 *
 * `DataTable` puts every row in the document, which is right for thirty brokers and wrong for ten
 * thousand topics: the browser lays out every row whether or not anyone can see it, and a list
 * that size takes long enough that a scroll visibly stutters. This component renders the rows the
 * viewport can show plus a few either side, and stands the rest in with two empty spacer rows
 * whose heights add up to the space the missing rows would have taken. The scrollbar is therefore
 * the length of the whole list while the document stays about twenty rows long.
 *
 * ## Defect one: the row height is given, not measured
 *
 * There is one number. It is used by the window arithmetic (`slice`) *and* published to the
 * stylesheet as `--kui-vtable-row-height`, so the two cannot disagree.
 *
 * If they could, the failure would be silent. Suppose the CSS drew rows at 36px while the
 * arithmetic believed 48: the component would compute that a 600px viewport holds thirteen rows,
 * render thirteen, and the viewport would have room for seventeen. The bottom of the list is blank
 * and the scrollbar does not fix it, because scrolling recomputes the same wrong number. Nothing
 * is visibly wrong on either side taken alone — the CSS is correct, the arithmetic is correct, and
 * the pair is broken. Publishing one signal into both is what makes that unrepresentable.
 *
 * ## Defect two: the container's size is observed, not sampled once
 *
 * The viewport height is watched with a `ResizeObserver` for the component's whole life.
 *
 * Measuring once at mount drew five rows for a twelve-partition topic. At the moment the component
 * mounts, the browser has often not yet given the scroller the height the stylesheet will settle
 * it at — the element is a few dozen pixels tall, the arithmetic concludes that two rows fit, and
 * the table then renders five rows for the rest of its life inside a container that has since
 * grown to hold eighteen. Nothing errors: the operator sees five of twelve partitions above a
 * large blank area, under a scrollbar that does not scroll, because the component believes the
 * whole list is already on screen.
 *
 * The observer is set up in `onSettled` — Solid 2's replacement for `onMount` — and the cleanup it
 * returns disconnects it. It is created behind a feature test because a DOM implementation with no
 * layout engine has no `ResizeObserver`, and there the `viewportHeight` override is the only
 * source, which is exactly what such a test drives.
 *
 * ### The Solid 2 hazard in this component
 *
 * Solid 2 batches updates on a microtask: reading a signal immediately after writing it returns
 * the old value until the queue flushes. So nothing here writes a measurement and then reads it
 * back in the same tick to compute a slice. The slice is a memo over the signals; the writes just
 * set them, and the memo recomputes when the flush happens. Writing `setViewport(h); const s =
 * sliceMemo();` would silently compute the previous frame's window.
 *
 * ## What this is not
 *
 * Not a grid: no column resizing, no reordering, no grouping, no pinned columns, no cell editing,
 * no expandable rows. It takes the same `Column<Row>[]` `DataTable` takes. When a screen needs a
 * grid, the answer is to decide that a grid is a product feature and build one, not to grow this
 * until it is a bad one. A record that opens in place is `RecordRow`, which is a list of cards and
 * deliberately not a table (design spec §3.5).
 *
 * It also does no fetching, no paging and no sorting. It draws the rows it is handed, in the order
 * it is handed them. The screens that use it sort on the server — the topic list sorts ten
 * thousand rows it has never seen, of which it holds five hundred — and a table that quietly
 * re-sorted its own page would show the right rows in an order no page boundary matches.
 *
 * ## Accessibility
 *
 * `aria-rowcount` is the length of the *whole* list and each row's `aria-rowindex` is its position
 * in that list, not in the window. Without absolute indices a screen reader on a list of ten
 * thousand announces "row 3 of 12", because twelve rows is all that is in the document — the
 * single most misleading thing a windowed table can say.
 *
 * The spacers are `role="presentation"` and hidden: they are layout, and announcing two empty rows
 * around every window would be worse than useless.
 *
 * Keyboard: Up and Down move one row, Page Up and Page Down move a viewport's worth, Home and End
 * go to the ends. Exactly one row is in the tab order at a time — a roving tabindex — so Tab
 * enters the table once and the arrow keys do the rest, rather than Tab stepping through ten
 * thousand rows. Because a focused row can be scrolled out of the window and removed from the
 * document, focus is remembered as an *index* and reapplied when that row comes back.
 */
export interface VirtualizedTableProps<Row> {
  readonly columns: readonly Column<Row>[];
  readonly rows: readonly Row[];
  readonly rowKey: (row: Row) => string;
  /** The table's accessible name. See `DataTable` on why it is required. */
  readonly caption: string;

  /**
   * The comfortable row height in pixels. 48 is the design's comfortable row spelled out: a cell's
   * line box is 14px at the tight line height, so 18px, and the design puts 15px above and below
   * it. 15 + 18 + 15 = 48. Compact keeps the same line box at 9px padding, so 36 — which is where
   * `COMPACT_ROW_SAVING_PX` comes from, and why this prop is the *comfortable* height rather than
   * the current one.
   */
  readonly rowHeight?: number;

  /**
   * Rows rendered above and below the viewport. Three is enough to hide a fast scroll's repaint
   * and small enough that the document stays short.
   */
  readonly overscan?: number;

  /**
   * Forces the density instead of following `data-density` on `<html>`. The switch in Settings is
   * a statement about every table in the product, so following it is the default; a caller passes
   * this only where a table is deliberately exempt, and a test passes it to pin the height.
   */
  readonly compact?: boolean;

  /**
   * Overrides the measured viewport height, in pixels.
   *
   * This exists because a DOM implementation with no layout engine reports every element as zero
   * pixels tall, and a component that could only measure itself would be untestable outside a real
   * browser. It is also what a benchmark harness drives the component through.
   */
  readonly viewportHeight?: number;

  readonly sort?: Sort | null;
  readonly onSortChange?: (next: Sort | null) => void;

  readonly onRowClick?: (row: Row) => void;

  readonly empty?: JSX.Element;
  readonly testId?: string;
}

export const DEFAULT_ROW_HEIGHT = 48;

/** The custom property the row height is published through, so the arithmetic and the CSS share
 * one number. Deliberately not a token: a token would be a second place the number lives. */
export const ROW_HEIGHT_PROPERTY = "--kui-vtable-row-height";

export function VirtualizedTable<Row>(props: VirtualizedTableProps<Row>): JSX.Element {
  const followsDocument = createIsCompact();
  const isCompact = () => props.compact ?? followsDocument();

  const rowHeight = createMemo(() => {
    const base = Math.max(1, Math.trunc(props.rowHeight ?? DEFAULT_ROW_HEIGHT));
    return isCompact() ? Math.max(1, base - COMPACT_ROW_SAVING_PX) : base;
  });

  const [scrollTop, setScrollTop] = createSignal(0);
  const [measuredHeight, setMeasuredHeight] = createSignal(0);
  const [focusedIndex, setFocusedIndex] = createSignal(0);

  /**
   * Whether the operator is driving the table from the keyboard.
   *
   * Focus is only ever *taken* while this is true. Without it, a table that reapplies focus
   * whenever its focused row re-enters the window would snatch the caret away from whatever
   * somebody was typing the moment a background refresh moved a row.
   */
  const [keyboardEngaged, setKeyboardEngaged] = createSignal(false);

  const viewportHeight = () => props.viewportHeight ?? measuredHeight();
  const total = () => props.rows.length;

  const window_ = createMemo(() =>
    slice(scrollTop(), viewportHeight(), rowHeight(), props.overscan ?? 3, total()),
  );

  /** The rows in the window, each carrying the index it has in the *whole* list. */
  const windowed = createMemo(() => {
    const cut = window_();
    return props.rows.slice(cut.firstIndex, cut.firstIndex + cut.count).map((row, offset) => ({
      index: cut.firstIndex + offset,
      key: props.rowKey(row),
      row,
    }));
  });

  let scroller: HTMLDivElement | undefined;

  onSettled(() => {
    const element = scroller;
    if (element === undefined) return undefined;

    // Measure once immediately as well as observing, so a browser that fires its first observation
    // asynchronously still draws a full window on the first frame rather than an empty one.
    if (element.clientHeight > 0) setMeasuredHeight(element.clientHeight);

    if (typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(() => {
      const height = element.clientHeight;
      if (height > 0) setMeasuredHeight(height);
    });
    observer.observe(element);
    return () => observer.disconnect();
  });

  function scrollToPx(px: number): void {
    const clamped = Math.max(0, px);
    // Setting `scrollTop` on the element does not raise a `scroll` event in every environment, so
    // the signal is written alongside it. These two are only ever set together, here.
    if (scroller !== undefined) scroller.scrollTop = clamped;
    setScrollTop(clamped);
  }

  /**
   * Moves the focused row and brings it into view.
   *
   * The row being moved *towards* is scrolled fully into view rather than just to the edge, so the
   * next Down does not appear to do nothing.
   */
  function moveFocus(to: number): void {
    const count = total();
    if (count === 0) return;
    const height = viewportHeight();
    const px = rowHeight();
    const target = Math.min(Math.max(0, to), count - 1);

    setKeyboardEngaged(true);
    setFocusedIndex(target);

    const rowTop = target * px;
    const current = scrollTop();
    if (rowTop < current) scrollToPx(rowTop);
    else if (rowTop + px > current + height) scrollToPx(rowTop + px - height);
  }

  function onKeyDown(event: KeyboardEvent): void {
    // A page is a viewport's worth of rows and at least one, so a table in a very short container
    // still moves when Page Down is pressed rather than appearing to ignore the key.
    const pageStep = Math.max(1, Math.floor(viewportHeight() / rowHeight()));
    const current = focusedIndex();
    let target: number | undefined;
    switch (event.key) {
      case "ArrowDown":
        target = current + 1;
        break;
      case "ArrowUp":
        target = current - 1;
        break;
      case "PageDown":
        target = current + pageStep;
        break;
      case "PageUp":
        target = current - pageStep;
        break;
      case "Home":
        target = 0;
        break;
      case "End":
        target = total() - 1;
        break;
      default:
        target = undefined;
    }
    if (target === undefined) return;
    // Otherwise the browser scrolls the page as well and the table jumps twice for one key.
    event.preventDefault();
    moveFocus(target);
  }

  const columnCount = () => Math.max(1, props.columns.length);

  return (
    <div
      class={["kui-vtable", { "kui-vtable--compact": isCompact() }]}
      data-testid={props.testId}
      // The one number, published to the stylesheet. See the note at the top of this file.
      style={{ [ROW_HEIGHT_PROPERTY]: `${rowHeight()}px` }}
    >
      <div
        class="kui-vtable__scroller"
        ref={(el: HTMLDivElement) => (scroller = el)}
        onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
        onKeyDown={onKeyDown}
      >
        <table class="kui-table" aria-rowcount={total()}>
          <caption class="kui-visually-hidden">{props.caption}</caption>
          <thead>
            <tr>
              <For each={props.columns}>
                {(column) => (
                  <th
                    scope="col"
                    class={[
                      "kui-table__header-cell",
                      { "kui-table__header-cell--numeric": column.align === "numeric" },
                    ]}
                    style={column.width === undefined ? undefined : { width: column.width }}
                    aria-sort={
                      props.sort !== null &&
                      props.sort !== undefined &&
                      props.sort.columnId === column.id
                        ? props.sort.order === "asc"
                          ? "ascending"
                          : "descending"
                        : "none"
                    }
                  >
                    <Show
                      when={column.sortable === true && props.onSortChange !== undefined}
                      fallback={column.header}
                    >
                      <button
                        type="button"
                        class="kui-table__sort"
                        onClick={() =>
                          props.onSortChange?.(nextSort(props.sort ?? null, column.id))
                        }
                      >
                        {column.header}
                        <Show
                          when={props.sort?.columnId === column.id}
                          fallback={
                            <span class="kui-table__sort-placeholder" aria-hidden="true" />
                          }
                        >
                          <Show when={props.sort?.order === "asc"} fallback={<Icon name="chevron-down" />}>
                            <Icon name="chevron-up" />
                          </Show>
                        </Show>
                      </button>
                    </Show>
                  </th>
                )}
              </For>
            </tr>
          </thead>

          <tbody class="kui-table__body">
            <Spacer height={window_().offsetPx} columns={columnCount()} />

            <For each={windowed()} keyed={(item) => item.key}>
              {(item) => (
                <VirtualRow
                  index={item().index}
                  columns={props.columns}
                  row={item().row}
                  focused={focusedIndex() === item().index}
                  keyboardEngaged={keyboardEngaged()}
                  onFocused={setFocusedIndex}
                  // Spread rather than passed, because `exactOptionalPropertyTypes` treats an
                  // explicit `undefined` as different from an absent property.
                  {...(props.onRowClick === undefined ? {} : { onActivate: props.onRowClick })}
                />
              )}
            </For>

            <Spacer height={trailingHeightPx(window_(), rowHeight())} columns={columnCount()} />

            {/* Inside the table, so the header stays above it and the columns still say what the
                table would have held. */}
            <Show when={total() === 0}>
              <tr>
                <td colspan={columnCount()} class="kui-vtable__empty">
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
    </div>
  );
}

/** One of the two elements that stand in for every row outside the window. */
function Spacer(props: { readonly height: number; readonly columns: number }): JSX.Element {
  return (
    <tr class="kui-vtable__spacer" role="presentation" aria-hidden="true">
      <td colspan={props.columns} style={{ height: `${props.height}px`, padding: "0" }} />
    </tr>
  );
}

interface VirtualRowProps<Row> {
  readonly index: number;
  readonly columns: readonly Column<Row>[];
  readonly row: Row;
  readonly focused: boolean;
  readonly keyboardEngaged: boolean;
  readonly onFocused: (index: number) => void;
  readonly onActivate?: (row: Row) => void;
}

function VirtualRow<Row>(props: VirtualRowProps<Row>): JSX.Element {
  let element: HTMLTableRowElement | undefined;

  // Reapplying focus when a recycled row comes back into the window — and only when the operator
  // is actually driving from the keyboard, so a background refresh never steals the caret.
  createFocusRestore(
    () => props.focused && props.keyboardEngaged,
    () => element,
  );

  return (
    <tr
      class={["kui-table__row", "kui-vtable__row"]}
      ref={(el: HTMLTableRowElement) => (element = el)}
      // Absolute, not relative to the window. `aria-rowindex` is 1-based.
      aria-rowindex={props.index + 1}
      // A roving tabindex: exactly one row is tabbable, so Tab enters the table once and the arrow
      // keys do the rest. Every other row is reachable but not in the tab order.
      tabindex={props.focused ? 0 : -1}
      onFocus={() => props.onFocused(props.index)}
      onClick={props.onActivate === undefined ? undefined : () => props.onActivate?.(props.row)}
    >
      <For each={props.columns}>
        {(column) => (
          <td
            class={[
              "kui-table__cell",
              { "kui-table__cell--numeric": column.align === "numeric" },
            ]}
          >
            {column.render(props.row)}
          </td>
        )}
      </For>
    </tr>
  );
}

/**
 * Gives a row the browser's focus when it becomes the focused row.
 *
 * Split out because the guard is the whole point and it would be easy to lose inside the row's
 * markup: focus is taken only when the row does not already contain the active element. Calling
 * `focus()` on a row that already holds focus somewhere inside it — on a copy button, say — moves
 * focus off that control and back onto the row, which to a keyboard user reads as the page
 * fighting them.
 *
 * The two-phase form is Solid 2's only form: the first function tracks and the second one acts.
 * The tracked half is "should this row hold focus"; the acting half touches the DOM and is not
 * re-read by the graph.
 */
function createFocusRestore(
  shouldHold: () => boolean,
  element: () => HTMLElement | undefined,
): void {
  createEffect(shouldHold, (hold) => {
    if (!hold) return;
    const el = element();
    if (el === undefined) return;
    if (el.contains(document.activeElement)) return;
    el.focus();
  });
}
