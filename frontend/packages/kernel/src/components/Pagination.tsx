/**
 * The row under a table: how many are shown, how many fit on a page, and how to reach page 47.
 *
 * ## Why "go to page" exists at all
 *
 * A cluster with 128 topics at 8 a page is 16 pages, and the design draws first/previous/next/last
 * with three numbered buttons (SCREENS.md §3.3). That is fine for 16 and useless for 400, which is
 * what an estate with a few thousand topics produces. The numbered buttons are the common case and
 * the box is the escape hatch, and it is cheap enough that leaving it out only saves the operator
 * from being able to do their job.
 *
 * ## The count is a range and a total, not a page number
 *
 * "Showing 1-8 of 24" answers the question the operator actually has, which is whether the thing
 * they are looking for is likely to be on this page. "Page 1 of 3" answers a question about the
 * interface. Both are cheap; only one is useful.
 *
 * ## What happens when the total is not known
 *
 * A server that streams or that cannot count cheaply gives no total. The component then draws the
 * range it does know (`Showing 1-8`), enables `next` on the caller's say-so, and **disables `last`
 * and hides the numbered buttons entirely**, because a last page cannot be computed and a guessed
 * one sends the operator somewhere that does not exist. This is the case that is normally
 * discovered in production, so it has a story.
 *
 * ## Accessibility
 *
 * The whole thing is a `<nav>` with a name, which is how a screen-reader user finds it and skips
 * it. The current page's button carries `aria-current="page"`. The range line is a live region:
 * changing pages otherwise moves focus to a button whose label is a bare digit, and the user is
 * told nothing about what happened.
 */
import { For, Show, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Icon } from "./Icon.jsx";

export interface PaginationProps {
  /** One-based. The first page is 1, not 0, because the buttons say so. */
  readonly page: number;
  readonly pageSize: number;
  /** `undefined` when the server did not say. See the note above; this is not the same as 0. */
  readonly total: number | undefined;
  /** How many rows this page actually holds — the last page is usually short. */
  readonly shown: number;
  readonly onPage: (page: number) => void;
  readonly onPageSize?: ((size: number) => void) | undefined;
  /** The offered page sizes. The design draws 8, 16, 32. */
  readonly pageSizes?: readonly number[] | undefined;
  /**
   * Whether a next page exists, for the case where `total` is unknown. Ignored when `total` is
   * known, because then it is arithmetic rather than a claim.
   */
  readonly hasNext?: boolean | undefined;
  /**
   * The navigation landmark's accessible name. Defaults to "Pagination", which is right when there
   * is one on the page.
   *
   * It is overridable because a table with a paginator above *and* below it puts two landmarks with
   * the same name in one document, and a screen-reader user listing the landmarks then gets two
   * indistinguishable entries. Found by running axe over the stories rather than by reasoning about
   * it: the `TheEdges` story stacks six of these and axe reported `landmark-unique`.
   */
  readonly label?: string | undefined;
  readonly testId?: string | undefined;
}

/**
 * The window of page numbers to draw: at most five, always including the current page, and never
 * running off either end.
 *
 * Exported because it is the part worth testing directly. Every off-by-one a paginator can have
 * lives in here — page 1 of 1, page 1 of 2, the current page at either edge, a current page beyond
 * the last (which happens when the page size grows while the user is on page 9) — and none of them
 * is reachable from a screenshot.
 */
export function pageWindow(page: number, pageCount: number, span = 5): readonly number[] {
  if (pageCount <= 0) return [];
  const width = Math.min(span, pageCount);
  // Centre the window on the current page, then push it back inside the range. Clamping `page`
  // first matters: a caller on page 9 of a list that just shrank to 3 pages must get [1,2,3], not
  // an empty window.
  const current = Math.min(Math.max(page, 1), pageCount);
  let start = current - Math.floor(width / 2);
  if (start < 1) start = 1;
  if (start + width - 1 > pageCount) start = pageCount - width + 1;
  return Array.from({ length: width }, (_, index) => start + index);
}

export function Pagination(props: PaginationProps): JSX.Element {
  const [goto, setGoto] = createSignal("");

  const pageCount = () =>
    props.total === undefined ? undefined : Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize)));

  const firstRow = () => (props.shown === 0 ? 0 : (props.page - 1) * props.pageSize + 1);
  const lastRow = () => (props.page - 1) * props.pageSize + props.shown;

  const atFirst = () => props.page <= 1;
  const atLast = () => {
    const count = pageCount();
    return count === undefined ? props.hasNext !== true : props.page >= count;
  };

  const go = (page: number): void => {
    const count = pageCount();
    const clamped = count === undefined ? Math.max(1, page) : Math.min(Math.max(1, page), count);
    if (clamped !== props.page) props.onPage(clamped);
  };

  const submitGoto = (event: Event): void => {
    event.preventDefault();
    const parsed = Number.parseInt(goto(), 10);
    // Silently ignoring rubbish is right here: the box is a shortcut, and an error message for
    // "q" would be more interruption than the mistake is worth. The box keeps its text so the
    // user can see nothing happened.
    if (Number.isFinite(parsed)) {
      go(parsed);
      setGoto("");
    }
  };

  return (
    <nav class="kui-pagination" aria-label={props.label ?? "Pagination"} data-testid={props.testId}>
      <p class="kui-pagination__range" aria-live="polite">
        <Show when={props.shown > 0} fallback={<>Nothing to show</>}>
          Showing {firstRow().toLocaleString()}–{lastRow().toLocaleString()}
          <Show when={props.total !== undefined}> of {props.total?.toLocaleString()}</Show>
        </Show>
      </p>

      <Show when={props.onPageSize !== undefined}>
        <div class="kui-pagination__sizes" role="group" aria-label="Rows per page">
          <For each={props.pageSizes ?? [8, 16, 32]}>
            {(size) => (
              <button
                type="button"
                class={[
                  "kui-pagination__size",
                  "kui-focusable",
                  { "kui-pagination__size--active": size === props.pageSize },
                ]}
                aria-pressed={size === props.pageSize ? "true" : "false"}
                aria-label={`${size} rows per page`}
                onClick={() => props.onPageSize?.(size)}
              >
                {size}
              </button>
            )}
          </For>
        </div>
      </Show>

      <div class="kui-pagination__pages">
        <button
          type="button"
          class="kui-pagination__step kui-focusable"
          aria-label="First page"
          disabled={atFirst()}
          onClick={() => go(1)}
        >
          <Icon name="chevrons-left" />
        </button>
        <button
          type="button"
          class="kui-pagination__step kui-focusable"
          aria-label="Previous page"
          disabled={atFirst()}
          onClick={() => go(props.page - 1)}
        >
          <Icon name="chevron-left" />
        </button>

        {/* Numbered buttons only when there is a known last page. Without a total they would be a
            guess, and a guess that sends the operator to a page that does not exist. */}
        <Show when={pageCount()}>
          {(count) => (
            <For each={pageWindow(props.page, count())}>
              {(page) => (
                <button
                  type="button"
                  class={[
                    "kui-pagination__page",
                    "kui-focusable",
                    { "kui-pagination__page--current": page === props.page },
                  ]}
                  aria-current={page === props.page ? "page" : undefined}
                  aria-label={`Page ${page}`}
                  onClick={() => go(page)}
                >
                  {page}
                </button>
              )}
            </For>
          )}
        </Show>

        <button
          type="button"
          class="kui-pagination__step kui-focusable"
          aria-label="Next page"
          disabled={atLast()}
          onClick={() => go(props.page + 1)}
        >
          <Icon name="chevron-right" />
        </button>
        <button
          type="button"
          class="kui-pagination__step kui-focusable"
          aria-label="Last page"
          disabled={atLast() || pageCount() === undefined}
          onClick={() => {
            const count = pageCount();
            if (count !== undefined) go(count);
          }}
        >
          <Icon name="chevrons-right" />
        </button>
      </div>

      <Show when={pageCount() !== undefined && (pageCount() ?? 0) > 5}>
        <form class="kui-pagination__goto" onSubmit={submitGoto}>
          <label class="kui-pagination__goto-label" for="kui-pagination-goto">
            Go to
          </label>
          <input
            id="kui-pagination-goto"
            class="kui-pagination__goto-input kui-focusable"
            type="text"
            inputmode="numeric"
            placeholder="#"
            value={goto()}
            onInput={(event) => setGoto(event.currentTarget.value)}
          />
          <button type="submit" class="kui-pagination__goto-go kui-focusable">
            Go
          </button>
        </form>
      </Show>
    </nav>
  );
}
