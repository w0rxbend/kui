/**
 * "Cluster / Topics / orders" — where you are, and how to get back.
 *
 * KUI's URLs nest four or five levels deep (cluster, topic, tab, message), and without a trail the
 * only way back to the topic list is the browser's Back button, which does the wrong thing after a
 * few in-page navigations.
 *
 * ## Accessibility contract
 *
 * A `<nav aria-label="Breadcrumb">` wrapping an ordered list: the landmark says this is navigation,
 * and the list says what order the steps are in. The last crumb is not a link and carries
 * `aria-current="page"`. The separators are `aria-hidden`, because "slash" read out between every
 * step is noise.
 */
import type { JSX } from "@solidjs/web";
import { For, Show } from "solid-js";

export interface Crumb {
  readonly label: string;
  /** `undefined` for the page you are on, which has nowhere to go. */
  readonly href?: string | undefined;
}

export interface BreadcrumbsProps {
  readonly crumbs: readonly Crumb[];
  readonly class?: string | undefined;
  readonly "data-testid"?: string | undefined;
}

export function Breadcrumbs(props: BreadcrumbsProps): JSX.Element {
  return (
    <nav
      class={["kui-breadcrumbs", props.class]}
      aria-label="Breadcrumb"
      data-testid={props["data-testid"]}
    >
      <ol class="kui-breadcrumbs__list">
        <For each={props.crumbs}>
          {(crumb, index) => (
            <li class="kui-breadcrumbs__item">
              <Show when={index() > 0}>
                <span class="kui-breadcrumbs__separator" aria-hidden="true">
                  /
                </span>
              </Show>
              <Show
                when={crumb.href}
                fallback={<span aria-current="page">{crumb.label}</span>}
              >
                {(href) => <a href={href()}>{crumb.label}</a>}
              </Show>
            </li>
          )}
        </For>
      </ol>
    </nav>
  );
}
