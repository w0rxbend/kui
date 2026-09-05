/**
 * The block at the top of a content region: where you are, what this is, and what you can do to it.
 *
 * SPEC §4.12, and §5.2's note, which is the part that is easy to get wrong: **the dashboard and the
 * consumer list carry a voice line, and an object page does not.** The topic page in screenshot `02`
 * has a breadcrumb, a title, a status chip and two actions, and no sentence under the title — not
 * because one was forgotten, but because a page about one named object has nothing general to say
 * about it. That is why `voice` is optional and why nothing here supplies a default: a component
 * that invented a sentence would put one on every object page in the product.
 *
 * ## The destructive action is not the loudest thing on the page
 *
 * `actions` is a slot rather than a list of descriptors, because the ordering and emphasis are the
 * caller's: screenshot `02` puts the ordinary action (`Produce message`) in the filled treatment
 * and the destructive one (`Purge`) in the outlined danger treatment beside it. A component that
 * accepted `{label, destructive}` pairs would have to decide that, and it would decide it the same
 * way on every page — which is how "delete, empty and add-partitions look identical to one another
 * and to ordinary actions" happened in the first place.
 */

import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Breadcrumbs, type Crumb } from "./Breadcrumbs.jsx";

export interface PageHeaderProps {
  /** The page's name. Rendered as the region's one `<h1>`. */
  readonly title: string;
  /** The trail above the title. Omitted on a top-level destination, which has nothing to trail. */
  readonly crumbs?: readonly Crumb[] | undefined;
  /**
   * The sentence under the title. Present on a list, absent on an object page — see above.
   *
   * Pass the whole sentence, already chosen for the current health. Never pass a template with the
   * aside bolted on: SPEC §6.3 rule 3 requires the aside to disappear when the state is not
   * healthy, and a component that concatenated one could not honour that.
   */
  readonly voice?: string | undefined;
  /** Sits beside the title: the topic page's `in sync` chip, a group's state. */
  readonly chip?: JSX.Element | undefined;
  /** The right-hand end. Order and emphasis are the caller's. */
  readonly actions?: JSX.Element | undefined;
  readonly testId?: string | undefined;
}

export function PageHeader(props: PageHeaderProps): JSX.Element {
  return (
    <header class="kui-page-head" data-testid={props.testId}>
      <Show when={props.crumbs !== undefined && props.crumbs.length > 0}>
        <Breadcrumbs crumbs={props.crumbs ?? []} />
      </Show>
      <div class="kui-page-head__row">
        <div class="kui-page-head__identity">
          <h1 class="kui-page-head__title">{props.title}</h1>
          <Show when={props.chip}>{(chip) => <span class="kui-page-head__chip">{chip()}</span>}</Show>
        </div>
        <Show when={props.actions}>
          {(actions) => <div class="kui-page-head__actions">{actions()}</div>}
        </Show>
      </div>
      <Show when={props.voice}>{(voice) => <p class="kui-page-head__voice">{voice()}</p>}</Show>
    </header>
  );
}
