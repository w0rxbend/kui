import { For, Show } from "solid-js";
import { Icon } from "@kui/kernel";
import type { Tab } from "./types.js";

/**
 * The strip of tabs under a page header: Overview, Messages, Consumers, Settings.
 *
 * ## These are links, and they must not claim the tab pattern
 *
 * Each tab is a route: opening one changes the URL, and the panel below is a different page rather
 * than a hidden sibling in the same document. The ARIA tab pattern describes something else —
 * panels that are all present and swapped without navigating — and adopting it here would promise a
 * screen reader user that Left and Right move between panels that are already loaded, which is
 * false. So this is a `<nav>` full of links with `aria-current="page"` on the active one, which is
 * exactly what it is. The visual treatment is the same either way; the promise is not.
 *
 * ## The underline is not the only marker
 *
 * The active tab is brighter *and* underlined *and* carries `aria-current`. Any one of those alone
 * fails somebody: the colour step fails a colour-blind operator, the underline fails a screen
 * reader, and `aria-current` fails everybody who can see the screen.
 *
 * ## Availability
 *
 * A tab whose service is down stays in the strip and stays selectable; the page it opens explains
 * the outage. A tab that does not apply to this object — retention settings on a compacted topic —
 * is omitted rather than disabled, because "not applicable" and "temporarily broken" are different
 * statements and a dimmed row says the second one.
 */
export type TabStripProps = {
  readonly tabs: readonly Tab[];
  readonly currentId: string;
  /** Names the strip for assistive technology: "Topic sections", "Cluster sections". */
  readonly label: string;
};

export function TabStrip(props: TabStripProps) {
  return (
    <nav class="kui-page-tabs" aria-label={props.label} data-testid="tab-strip">
      <ul class="kui-page-tabs__list">
        <For each={props.tabs}>
          {(tab) => (
            <li>
              <a
                class={["kui-page-tabs__tab", { "kui-page-tabs__tab--current": tab.id === props.currentId }]}
                href={tab.href}
                aria-current={tab.id === props.currentId ? "page" : undefined}
                data-testid={`tab-${tab.id}`}
              >
                <Icon name={tab.icon} size="16px" />
                <span class="kui-page-tabs__label">{tab.label}</span>
                {/* The count is inside the link so it is read with the label — "Consumers, 14" —
                    rather than as a stray number after it. */}
                <Show when={tab.count !== undefined}>
                  <span class="kui-page-tabs__count">{tab.count}</span>
                </Show>
              </a>
            </li>
          )}
        </For>
      </ul>
    </nav>
  );
}
