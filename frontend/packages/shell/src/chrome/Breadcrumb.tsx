import { For, Show, createSignal } from "solid-js";
import type { Crumb } from "./types.js";

/**
 * The trail above a page title: `Topics › orders.payments.v2`.
 *
 * ## The last crumb is not a link
 *
 * It is where you already are. Rendering it as a link offers a journey to the current page, which
 * costs a tab stop and answers nothing; it carries `aria-current="page"` instead, which is how a
 * screen reader user is told the same thing.
 *
 * ## A long trail collapses, it never wraps
 *
 * Kafka names are long — `orders.payments.v2.dead-letter.retry-5m` is not an unusual topic — and a
 * trail that wraps to two lines pushes the page title down, while a trail that overflows makes the
 * whole page scroll sideways. So the middle collapses to a button reading `…` which expands the
 * hidden segments in place. The button is a real button with a real accessible name ("Show 2 hidden
 * levels"), not a decorative ellipsis: an ellipsis nobody can press is just a lie about there being
 * more.
 */
export type BreadcrumbProps = {
  readonly trail: readonly Crumb[];
  /**
   * How many crumbs may be shown before the middle collapses. Four is the point at which the trail
   * stops being read and starts being scanned.
   */
  readonly maxVisible?: number | undefined;
};

export function Breadcrumb(props: BreadcrumbProps) {
  const [expanded, setExpanded] = createSignal(false);
  const maxVisible = () => props.maxVisible ?? 4;
  const collapsed = () => !expanded() && props.trail.length > maxVisible();

  /** The first crumb, then the hole, then the last two. */
  const head = () => (collapsed() ? props.trail.slice(0, 1) : props.trail);
  const tail = () => (collapsed() ? props.trail.slice(-2) : []);
  const hiddenCount = () => Math.max(0, props.trail.length - 3);

  /* `isLast` is passed as a function rather than as a value on purpose. A `<For>` callback body is
   * an owner, not a tracking scope: anything read at the top of it — an index accessor, a signal —
   * is frozen at its first value, and Solid says so with a STRICT_READ_UNTRACKED warning. Taking a
   * getter and calling it inside the JSX below moves the read into a scope that tracks. */
  const crumb = (item: Crumb, isLast: () => boolean) => (
    <li class="kui-breadcrumb__item">
      {item.href !== undefined && !isLast() ? (
        <a class="kui-breadcrumb__link" href={item.href}>
          {item.label}
        </a>
      ) : (
        <span class="kui-breadcrumb__current" aria-current={isLast() ? "page" : undefined}>
          {item.label}
        </span>
      )}
      <Show when={!isLast()}>
        <span class="kui-breadcrumb__separator" aria-hidden="true">
          ›
        </span>
      </Show>
    </li>
  );

  return (
    <nav class="kui-breadcrumb" aria-label="Breadcrumb" data-testid="breadcrumb">
      <ol class="kui-breadcrumb__list">
        <For each={head()}>{(item, index) => crumb(item, () => !collapsed() && index() === props.trail.length - 1)}</For>
        <Show when={collapsed()}>
          <li class="kui-breadcrumb__item">
            <button
              type="button"
              class="kui-breadcrumb__more"
              aria-label={`Show ${hiddenCount()} hidden ${hiddenCount() === 1 ? "level" : "levels"}`}
              onClick={() => setExpanded(true)}
              data-testid="breadcrumb-expand"
            >
              …
            </button>
            <span class="kui-breadcrumb__separator" aria-hidden="true">
              ›
            </span>
          </li>
        </Show>
        <For each={tail()}>{(item, index) => crumb(item, () => index() === tail().length - 1)}</For>
      </ol>
    </nav>
  );
}
