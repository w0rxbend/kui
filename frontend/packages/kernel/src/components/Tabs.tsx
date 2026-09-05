/**
 * A tab strip with exactly one panel in the document at a time.
 *
 * ## The keyboard contract (the WAI-ARIA "tabs" pattern)
 *
 * A tab strip is a single stop in the browser's Tab order, not one stop per tab. Once focus is
 * inside it the arrow keys move between tabs and `Home`/`End` jump to the ends; pressing Tab leaves
 * the strip entirely and lands in the panel. That is done with a *roving tabindex*: the selected
 * tab carries `tabindex="0"` and every other tab carries `tabindex="-1"`, so the browser's own Tab
 * order contains exactly one of them.
 *
 * Moving with an arrow key changes the selection as well as the focus — the "automatic activation"
 * variant of the pattern. It is the right one here because switching a KUI tab is cheap and
 * reversible; the manual variant (arrow to move, Enter to activate) exists for tabs whose panels
 * are expensive to open.
 *
 * ## Lazy panels
 *
 * Only the selected panel exists in the DOM. That is why `Tab.body` is a function rather than an
 * element: a topic page's "Consumers" tab issues requests when it is created, and building all five
 * panels up front would fire five screens' worth of traffic for an operator who looks at one.
 * Rendering them all and hiding the inactive ones with CSS has the same fault plus a worse one —
 * their subscriptions stay live, and the whole set appears at once if the stylesheet fails to load.
 *
 * ## Degraded input
 *
 * An empty tab list renders an empty strip and no panel. A `selected` id matching no tab renders no
 * panel rather than silently falling back to the first one: quietly changing the caller's state to
 * make a render succeed hides the bug that produced the bad id.
 */
import type { JSX } from "@solidjs/web";
import { For, Show, createMemo, createUniqueId } from "solid-js";

export interface Tab {
  /** Stable across renders. It is the key, and it is half of the two element ids below. */
  readonly id: string;
  readonly label: string;
  /** Built only when the tab is selected. See "Lazy panels" above. */
  readonly body: () => JSX.Element;
}

export interface TabsProps {
  readonly tabs: readonly Tab[];
  readonly selected: string;
  readonly onSelect: (id: string) => void;
  /** Names the strip for a screen reader. Two strips on one page need telling apart. */
  readonly label?: string | undefined;
  readonly class?: string | undefined;
  readonly "data-testid"?: string | undefined;
}

/**
 * The tab a keystroke should move to, given where we are now. `undefined` leaves things alone.
 *
 * Exported because every off-by-one this component can have lives here, and a wrap-around is
 * exactly the kind of arithmetic that is cheap to test and easy to get wrong by one.
 */
export function tabTarget(
  key: string,
  current: string,
  available: readonly Tab[],
): string | undefined {
  const position = available.findIndex((tab) => tab.id === current);
  if (position < 0 || available.length === 0) return undefined;
  const size = available.length;
  switch (key) {
    // Wrapping is part of the pattern: Right on the last tab goes back to the first.
    case "ArrowRight":
    case "ArrowDown":
      return available[(position + 1) % size]?.id;
    case "ArrowLeft":
    case "ArrowUp":
      return available[(position - 1 + size) % size]?.id;
    case "Home":
      return available[0]?.id;
    case "End":
      return available[size - 1]?.id;
    default:
      return undefined;
  }
}

export function Tabs(props: TabsProps): JSX.Element {
  const instance = createUniqueId();
  const tabId = (id: string): string => `${instance}-tab-${id}`;
  const panelId = (id: string): string => `${instance}-panel-${id}`;

  /* The tab elements, so a keystroke can move DOM focus without waiting for a flush. Focus is
   * moved *only* from the key handler: stealing focus because a server response switched tabs is
   * deeply unpleasant, and that is the reason focus is not simply derived from the selection. */
  const buttons = new Map<string, HTMLButtonElement>();

  /* Keyed on the id, so that any other change to the tab list — a label arriving, a tab being
   * added — leaves the open panel alone. Rebuilding it would lose its scroll position and re-issue
   * its requests. */
  const active = createMemo(
    () => props.tabs.find((tab) => tab.id === props.selected),
    { equals: (a, b) => a?.id === b?.id },
  );

  const onKeyDown = (event: KeyboardEvent): void => {
    const next = tabTarget(event.key, props.selected, props.tabs);
    if (next === undefined) return;
    // Without this the browser scrolls the page on an arrow key, which inside a tab strip is never
    // what the operator meant.
    event.preventDefault();
    props.onSelect(next);
    buttons.get(next)?.focus();
  };

  return (
    <div class={["kui-tabs", props.class]} data-testid={props["data-testid"]}>
      <div class="kui-tabs__list" role="tablist" aria-label={props.label} onKeyDown={onKeyDown}>
        <For each={props.tabs} keyed={(tab) => tab.id}>
          {(tab) => (
            <button
              type="button"
              id={tabId(tab().id)}
              class={[
                "kui-tabs__tab",
                { "kui-tabs__tab--selected": props.selected === tab().id },
              ]}
              role="tab"
              aria-controls={panelId(tab().id)}
              aria-selected={props.selected === tab().id ? "true" : "false"}
              tabindex={props.selected === tab().id ? 0 : -1}
              data-tab-id={tab().id}
              ref={(element: HTMLButtonElement) => {
                buttons.set(tab().id, element);
              }}
              onClick={() => props.onSelect(tab().id)}
            >
              {tab().label}
            </button>
          )}
        </For>
      </div>
      <Show when={active()}>
        {(tab) => (
          <div
            id={panelId(tab().id)}
            class="kui-tabs__panel"
            role="tabpanel"
            aria-labelledby={tabId(tab().id)}
            /* A Tab stop, so a keyboard user can reach a panel holding no focusable element of its
             * own — a table of read-only figures, which is most of this product. */
            tabindex={0}
          >
            {tab().body()}
          </div>
        )}
      </Show>
    </div>
  );
}
