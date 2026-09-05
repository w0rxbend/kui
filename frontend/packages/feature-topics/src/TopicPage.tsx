/**
 * The frame every topic tab sits inside: the trail, the name, how the topic is doing, what can be
 * done to it, and the strip of sections.
 *
 * ## Why the breadcrumb and the tab strip arrive as slots
 *
 * They are *chrome* — the parts of a screen that look the same whatever object is on it — and they
 * live in `@kui/shell`, which owns the navigation model and the URLs. A feature must not import the
 * shell: the shell loads features lazily, so the edge would be a cycle, and it would pull the whole
 * application frame into every feature's chunk. So the shell hands them down, already built, and
 * this component composes them with the parts that are genuinely about a *topic* — the name, the
 * health chip, and the two actions.
 *
 * That is not indirection for its own sake. It is what makes "Purge" and "in sync" testable and
 * reviewable here, in the package that knows what they mean, without a router.
 *
 * ## There is no voice line on this page
 *
 * The dashboard and the consumer list carry one; an object page does not (design spec §5.2, §6).
 * That is a rule rather than an oversight: the cheerful sentence is a summary of a *situation*, and
 * a page about one named thing has no situation to summarise — the chip beside the title already
 * says how it is doing, in a word.
 *
 * ## Destructive actions do not share a shape with constructive ones
 *
 * Produce is a filled secondary button; Purge is outlined danger with a glyph. This project shipped
 * a topic page where delete, empty and add-partitions were visually identical to each other and to
 * "Refresh". The outline is not decoration — it is the silhouette that stops an operator's hand.
 * The glyph is there as well, because an outline alone is a colour-only distinction.
 */

import type { JSX } from "@solidjs/web";
import { Show } from "solid-js";
import { Button, StatusPill, type PillTone } from "@kui/kernel";
import type { TopicHealth } from "./types.js";

/** One action offered in the page header. */
export interface TopicAction {
  readonly label: string;
  readonly onClick: () => void;
  /** Absent means "this principal may do it". Present means it is disabled and this is why. */
  readonly disabledReason?: string | undefined;
  readonly busy?: boolean | undefined;
}

export interface TopicPageProps {
  readonly name: string;
  readonly health: TopicHealth;
  /**
   * The trail, built by the shell. `undefined` renders no trail rather than an empty one — a
   * breadcrumb with a single item is a line that tells nobody anything.
   */
  readonly breadcrumb?: JSX.Element | undefined;
  /** The section strip, built by the shell. See the header. */
  readonly tabs?: JSX.Element | undefined;
  readonly onProduce?: TopicAction | undefined;
  readonly onPurge?: TopicAction | undefined;
  /**
   * Deleting the topic itself, as opposed to emptying it.
   *
   * Last in the row and last in this list, because it is the only action here that removes something
   * KUI cannot help the operator get back. Purge leaves the topic, its configuration and its
   * partition count exactly as they were; delete leaves nothing.
   */
  readonly onDelete?: TopicAction | undefined;
  readonly children?: JSX.Element;
}

/**
 * How the chip beside the title reads.
 *
 * Four states and four sentences, because they are four different facts. `unknown` is the one that
 * is easy to get wrong: it is not a failure of the topic, it is a failure to describe it, and
 * drawing it in danger colours tells an operator their topic is broken when what is broken is the
 * connection to the broker that would have said.
 */
export function healthChip(health: TopicHealth): {
  readonly tone: PillTone;
  readonly label: string;
} {
  switch (health) {
    case "in-sync":
      return { tone: "success", label: "in sync" };
    case "under-replicated":
      return { tone: "warning", label: "under-replicated" };
    case "offline":
      return { tone: "danger", label: "offline" };
    case "unknown":
      return { tone: "neutral", label: "not described" };
  }
}

export function TopicPage(props: TopicPageProps): JSX.Element {
  const chip = (): ReturnType<typeof healthChip> => healthChip(props.health);

  return (
    <div class="kui-topic-page">
      <Show when={props.breadcrumb}>{(trail) => <>{trail()}</>}</Show>

      {/* A `div`, not a `header`. A `<header>` that is not inside a section or an article is
          exposed as a `banner` landmark, and the application already has one — the top bar. Two
          banners give a screen-reader user two indistinguishable entries in the landmark list. */}
      <div class="kui-topic-page__header">
        <div class="kui-topic-page__identity">
          {/* The name is the heading, and it is the topic's *real* name — never shortened, never
              pretty-printed. An operator copies it out of here into a command line. */}
          <h1 class="kui-topic-page__title">{props.name}</h1>
          <StatusPill tone={chip().tone} dot>
            {chip().label}
          </StatusPill>
        </div>

        <div class="kui-topic-page__actions">
          <Show when={props.onProduce}>
            {(action) => (
              <Button
                variant="secondary"
                icon="send"
                busy={action().busy === true}
                {...disabledProps(action().disabledReason)}
                onClick={() => action().onClick()}
              >
                {action().label}
              </Button>
            )}
          </Show>
          <Show when={props.onPurge}>
            {(action) => (
              <Button
                variant="danger"
                /* `danger` requires an icon in the button's own type — the outline alone is a
                   colour-only distinction, and the type makes forgetting it impossible rather
                   than merely discouraged.

                   `minus` rather than `trash`, now that delete sits beside it. Purge and delete are
                   two different amounts of destruction — one empties the log and leaves the topic,
                   the other leaves nothing — and two danger buttons wearing the same glyph is the
                   sort of adjacency that gets the wrong one clicked. */
                icon="minus"
                busy={action().busy === true}
                {...disabledProps(action().disabledReason)}
                onClick={() => action().onClick()}
              >
                {action().label}
              </Button>
            )}
          </Show>
          <Show when={props.onDelete}>
            {(action) => (
              <Button
                variant="danger"
                /* The bin is the stronger glyph, and delete is the stronger action. */
                icon="trash"
                busy={action().busy === true}
                {...disabledProps(action().disabledReason)}
                onClick={() => action().onClick()}
              >
                {action().label}
              </Button>
            )}
          </Show>
        </div>
      </div>

      <Show when={props.tabs}>{(strip) => <>{strip()}</>}</Show>

      <div class="kui-topic-page__body">{props.children}</div>
    </div>
  );
}

/**
 * A forbidden action is disabled *with the reason*, never hidden.
 *
 * A hidden button makes an operator think the product cannot do the thing at all, and they go
 * looking for a command line. A disabled one with "You do not hold a role that permits purging this
 * topic" tells them precisely who to ask.
 */
function disabledProps(
  reason: string | undefined,
): { readonly disabled: true; readonly disabledReason: string } | Record<string, never> {
  return reason === undefined ? {} : { disabled: true, disabledReason: reason };
}
