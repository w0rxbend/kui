import { For, Show, createSignal, createUniqueId } from "solid-js";
import { Icon } from "@kui/kernel";
import type { NavDestination, NavRank } from "./types.js";

/**
 * One destination in the navigation drawer: a stadium-shaped row with an icon, a label and an
 * optional trailing badge — and, when it has children, a disclosure and the rows under it.
 *
 * ## The badge says what it means, not what it counts
 *
 * A badge is a number with a sentence attached (`NavBadge.description`), and the sentence is what a
 * screen reader hears. "Brokers 3/3" read aloud is a fraction with no subject; "Brokers, 3 of 3
 * online" is the fact. The tone is chosen from the meaning too — `2/3` brokers is danger even
 * though `3/3` is success, and a count of topics is neutral however large it grows.
 *
 * When the count could not be fetched the badge is omitted entirely. Neither of the two tempting
 * alternatives is honest: `0` is a statement about the cluster, and a spinner in a 20px badge is
 * three grey pixels that look like a rendering fault.
 *
 * ## A disabled destination stays visible, and says why
 *
 * The fold produces a disabled row for exactly one state — `forbidden` — and the row is present,
 * dimmed, not a link and not focusable, and it carries its reason as a tooltip *and* in its
 * accessible name. (The design marks ksqlDB "soon" in the capture; ksqlDB ships in this wave, so
 * the fixtures that used to draw that row now draw a refused one.) Removing the row instead
 * would tell the operator the product cannot do the thing at all, which is a different and wrong
 * statement; leaving it dead with no explanation is worse still, because there is nothing to read
 * and nothing to try.
 *
 * ## The tree, and why the disclosure is a separate control
 *
 * `SCREENS-V4.md` §2.2 draws Topics expanded over its favourites and prefix groups. The row is
 * therefore two controls, not one: the label is a link to the topic list, and the chevron beside it
 * opens the rows underneath. Merging them — making the whole row toggle, or making the chevron
 * navigate — costs the other affordance, and both are wanted: an operator who knows which prefix
 * they want expands, and one who wants the list clicks the label.
 *
 * It is a plain `<button>` inside a plain `<ul>`, and deliberately not the ARIA `tree` pattern.
 * `tree` brings a roving tabindex and a keyboard contract of its own — arrow keys that move
 * *between* items rather than within them — and applying it to a list whose rows are ordinary links
 * would change how every other row in the drawer behaves. The button gets Enter and Space from the
 * browser, the links keep Tab, and the keyboard behaviour of the drawer stays the one it already
 * had. The same argument `TabStrip` makes for not claiming the `tablist` pattern.
 *
 * ## Expansion is seeded from the data and then held here
 *
 * `NavDestination.expanded` is the *initial* state. After that the reader owns it, and it is held
 * in a signal rather than pushed back into the data, because the drawer is rebuilt whenever a
 * capability frame lands — which on a struggling cluster is every few seconds. A component instance
 * survives its props changing, so the tree a reader opened stays open; a tree that read `expanded`
 * on every render would collapse itself exactly when somebody most needs it.
 *
 * ## The focus ring
 *
 * The ring is drawn in the stylesheet with `outline`, offset from the pill, and it is never removed
 * "for tidiness". This project has shipped three controls that were perfect in the accessibility
 * tree and invisible to everybody else, and a keyboard user who cannot see where they are is not
 * served by a `:focus` state that exists only in the DOM.
 */
export type NavItemProps = {
  readonly destination: NavDestination;
  /** True when this destination is the page currently being shown. */
  readonly current?: boolean | undefined;
  /** The id of the currently shown destination, so a nested row can mark itself too. */
  readonly currentId?: string | undefined;
};

export function NavItem(props: NavItemProps) {
  const badge = () => props.destination.badge;
  const children = () => ordered(props.destination.children);
  const branch = () => children().length > 0;

  /* Read once, on purpose: this is the seed and not a binding. See the header. */
  const [expanded, setExpanded] = createSignal(props.destination.expanded === true);
  const subtreeId = createUniqueId();

  /* The whole row's accessible name: the label, then what any badge means, then — if the row is
   * dead — why. Assembled here rather than left to the browser because the visible badge text is a
   * fragment ("3/3", "soon") that means nothing read on its own. */
  const accessibleName = () => {
    const parts = [props.destination.label];
    const b = badge();
    if (b) parts.push(b.description);
    if (props.destination.disabled && props.destination.disabledReason) {
      parts.push(props.destination.disabledReason);
    }
    return parts.join(", ");
  };

  const body = () => (
    <>
      <Icon name={props.destination.icon} size="20px" class="kui-nav-item__icon" />
      <span class="kui-nav-item__label">{props.destination.label}</span>
      {badge() ? (
        <span
          class={["kui-nav-item__badge", `kui-nav-item__badge--${badge()!.tone}`]}
          /* Hidden from assistive technology because the fragment it shows is already spelled out
           * in the row's accessible name above; announcing both says the number twice. */
          aria-hidden="true"
        >
          {badge()!.text}
        </span>
      ) : null}
    </>
  );

  return (
    <li class="kui-nav-item__slot">
      <div class="kui-nav-item__row">
        {props.destination.disabled ? (
          <span
            class="kui-nav-item kui-nav-item--disabled"
            /* `role="link"` with `aria-disabled` rather than a real `<a>`: it is announced as the
             * destination it is, it is in the reading order, and it is not in the tab order, because
             * tabbing to something that cannot be activated is a dead end. */
            role="link"
            aria-disabled="true"
            aria-label={accessibleName()}
            data-state={props.destination.state}
            title={props.destination.disabledReason ?? undefined}
            data-testid={`nav-${props.destination.id}`}
          >
            {body()}
          </span>
        ) : (
          <a
            class={[
              "kui-nav-item",
              {
                "kui-nav-item--current": props.current === true,
                /* Dimmed, and still a link. See `NavDestination.state`. */
                "kui-nav-item--dimmed": props.destination.state === "unavailable",
              },
            ]}
            href={props.destination.href}
            data-state={props.destination.state}
            aria-current={props.current === true ? "page" : undefined}
            aria-label={accessibleName()}
            data-testid={`nav-${props.destination.id}`}
          >
            {body()}
          </a>
        )}

        <Show when={branch()}>
          <button
            type="button"
            class="kui-nav-item__disclosure kui-focusable"
            /* The label names the *destination*, not the direction, because "Collapse" alone in a
             * list of ten disclosures tells a screen-reader user nothing about which one they are
             * on. `aria-expanded` already carries the direction. */
            aria-label={`${expanded() ? "Collapse" : "Expand"} ${props.destination.label}`}
            aria-expanded={expanded() ? "true" : "false"}
            aria-controls={subtreeId}
            data-testid={`nav-${props.destination.id}-disclosure`}
            onClick={() => setExpanded(!expanded())}
          >
            <Icon name={expanded() ? "chevron-down" : "chevron-right"} size="14px" />
          </button>
        </Show>
      </div>

      {/* Removed from the document rather than hidden with CSS: a collapsed subtree that is still in
          the tree is still in the tab order and still read aloud, which is the whole failure a
          disclosure exists to prevent. `aria-controls` points at an element that only exists while
          it is open, which is what `aria-expanded="false"` already tells a reader to expect. */}
      <Show when={branch() && expanded()}>
        <ul
          class="kui-nav-subtree"
          id={subtreeId}
          data-testid={`nav-${props.destination.id}-subtree`}
        >
          <For each={children()}>
            {(child) => (
              <NavItem
                destination={child}
                current={child.id === props.currentId}
                currentId={props.currentId}
              />
            )}
          </For>
        </ul>
      </Show>
    </li>
  );
}

/**
 * The children in the order the drawer draws them: prefix groups, then `internal`.
 *
 * Sorted here as well as in `nav/topicTree.ts` — which already emits them this way — because the
 * rule belongs to the *rendering* and must hold for any caller. A drawer assembled from more than
 * one source has no natural order of its own, and `internal` arriving before `orders.*` because two
 * lists were concatenated the other way round would be a bug nobody could see in either list.
 *
 * Stable within a rank: `prefixes()` has already ordered the groups largest-first with ties broken
 * alphabetically, and re-sorting them by anything else here would throw that away.
 */
function ordered(children: readonly NavDestination[] | undefined): readonly NavDestination[] {
  if (children === undefined || children.length === 0) return [];
  return [...children].sort((a, b) => rankOf(a.rank) - rankOf(b.rank));
}

/**
 * `internal` last, everything else before it.
 *
 * `internal` sits at the foot because an operator looking for `__consumer_offsets` knows where it
 * is and one looking for `orders.*` does not: the padlocked row is the one that never needs to be
 * found by scanning, so it is the one that can afford the worst position.
 *
 * The numbers are spaced rather than consecutive because a rank that sorts *above* the groups is
 * the one this drawer will want next — the design's starred favourites — and it should be able to
 * arrive without renumbering the two that already exist.
 */
function rankOf(rank: NavRank | undefined): number {
  switch (rank) {
    case "internal":
      return 20;
    default:
      return 10;
  }
}
