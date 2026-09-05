import { Icon } from "@kui/kernel";
import type { NavDestination } from "./types.js";

/**
 * One destination in the navigation drawer: a stadium-shaped row with an icon, a label and an
 * optional trailing badge.
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
 * KSQL DB is in the design marked "soon". The row is present, dimmed, not a link and not focusable,
 * and it carries its reason as a tooltip *and* in its accessible name. Removing the row instead
 * would tell the operator the product cannot do the thing at all, which is a different and wrong
 * statement; leaving it dead with no explanation is worse still, because there is nothing to read
 * and nothing to try.
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
};

export function NavItem(props: NavItemProps) {
  const badge = () => props.destination.badge;

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
      {props.destination.disabled ? (
        <span
          class="kui-nav-item kui-nav-item--disabled"
          /* `role="link"` with `aria-disabled` rather than a real `<a>`: it is announced as the
           * destination it is, it is in the reading order, and it is not in the tab order, because
           * tabbing to something that cannot be activated is a dead end. */
          role="link"
          aria-disabled="true"
          aria-label={accessibleName()}
          title={props.destination.disabledReason ?? undefined}
          data-testid={`nav-${props.destination.id}`}
        >
          {body()}
        </span>
      ) : (
        <a
          class={["kui-nav-item", { "kui-nav-item--current": props.current === true }]}
          href={props.destination.href}
          aria-current={props.current === true ? "page" : undefined}
          aria-label={accessibleName()}
          data-testid={`nav-${props.destination.id}`}
        >
          {body()}
        </a>
      )}
    </li>
  );
}
