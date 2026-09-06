/**
 * The floating pill that appears when rows are selected: a count, the actions that can be taken on
 * the selection, and a way out (`SCREENS-V4.md` §3.7).
 *
 * ## Absent at zero, and absent means absent
 *
 * With nothing selected the component renders nothing at all — not an empty bar, not a hidden one.
 * A bar that is always in the document is a strip of the window the operator can never use, and
 * one that is merely `visibility: hidden` still swallows the clicks meant for the row underneath.
 *
 * ## An action nobody may take is disabled, not hidden
 *
 * This is the rule the design states and the one worth being blunt about. If `Delete` disappeared
 * for a principal without `TOPIC:DELETE`, then `Purge` would move into its place, and the same
 * gesture that purges for one operator deletes for another. Positions have to be stable between
 * users, so a forbidden action stays where it is, disabled, carrying the reason it is disabled —
 * which `Button` already renders as a tooltip on a control that is still focusable, because a
 * `disabled` element cannot be reached by keyboard or by hover and its explanation is therefore
 * unreadable by exactly the people who need it.
 *
 * The reason is a sentence, not a code: "You do not have permission to delete topics on this
 * cluster", never "forbidden".
 *
 * ## Every action carries a glyph
 *
 * `icon` is required rather than optional. The bar is a short row of short words read in a hurry,
 * and `Purge` and `Delete` are two words of the same length that do different irreversible things;
 * the glyph is what separates them at a glance. It also makes `destructive` expressible at all —
 * `Button`'s danger silhouette requires a glyph, because an outline on its own is a colour-only
 * distinction and around one man in twelve cannot read it.
 *
 * ## What it does not own
 *
 * It does not hold the selection. The set lives with the screen, which is what lets the same
 * selection survive the Table↔Cards switch the design shows (§3.7): the two list treatments are
 * two renderings of one set, and a bar that owned the set would reset it every time the operator
 * changed how the rows are drawn.
 */
import type { JSX } from "@solidjs/web";
import { For, Show, merge } from "solid-js";
import { Button, type ButtonProps } from "./Button.jsx";
import type { IconName } from "./Icon.jsx";

export interface BulkAction {
  /** Stable across renders; the list is keyed by it. */
  readonly id: string;
  readonly label: string;
  /** Required. See the note above on why the bar has no wordless action. */
  readonly icon: IconName;
  /** Draws the danger silhouette. Delete and Purge; not Edit config. */
  readonly destructive?: boolean | undefined;
  /**
   * Present iff the action is unavailable, and it is the sentence the operator reads. Carrying the
   * reason *in the same property* that disables the action is what stops a disabled control from
   * ever shipping without one.
   */
  readonly disabledReason?: string | undefined;
  readonly onSelect: () => void;
}

export interface BulkActionBarProps {
  /** How many things are selected. At zero the component renders nothing. */
  readonly count: number;
  readonly actions: readonly BulkAction[];
  /** Clears the selection. The bar never clears it itself; it does not own it. */
  readonly onDismiss: () => void;
  /**
   * What the count is counting, singular: "topic", "consumer group". The bar prints "2 topics
   * selected" so that a screen reader reaching it out of context is told what was selected rather
   * than being told "2 selected" and left to guess.
   */
  readonly noun?: string | undefined;
  readonly plural?: string | undefined;
  readonly dismissLabel?: string | undefined;
  readonly testId?: string | undefined;
}

/**
 * The button props for one action.
 *
 * Written as four returns rather than as one object with two conditional properties, because
 * `ButtonProps` is a pair of discriminated unions — a `danger` button must have a glyph, a
 * disabled one must have a reason — and those constraints only hold on a branch where both
 * discriminants are literals. Assembling the object first and asserting the type afterwards would
 * be discarding the exact check the union exists to make.
 */
function buttonPropsOf(action: BulkAction): ButtonProps {
  const shared = {
    size: "sm",
    icon: action.icon,
    onClick: () => action.onSelect(),
    children: action.label,
  } as const;

  if (action.disabledReason !== undefined) {
    const blocked = { ...shared, disabled: true, disabledReason: action.disabledReason } as const;
    return action.destructive === true
      ? { ...blocked, variant: "danger" }
      : { ...blocked, variant: "ghost" };
  }
  return action.destructive === true
    ? { ...shared, variant: "danger" }
    : { ...shared, variant: "ghost" };
}

export function BulkActionBar(props: BulkActionBarProps): JSX.Element {
  const p = merge({ noun: "item", dismissLabel: "Clear selection" } as const, props);
  const noun = (): string =>
    props.count === 1 ? p.noun : (props.plural ?? `${p.noun}s`);

  return (
    <Show when={props.count > 0}>
      <div class="kui-bulkbar" role="region" aria-label="Selection" data-testid={props.testId}>
        {/* `role="status"` rather than a plain paragraph: the count changes under the operator's
            hands as they tick rows, and the change is the only feedback that a click landed on a
            row they could not see the tick on. */}
        <p class="kui-bulkbar__count" role="status">
          {props.count} {noun()} selected
        </p>

        <span class="kui-bulkbar__rule" aria-hidden="true" />

        <For each={props.actions} keyed={(action) => action.id}>
          {(action) => <Button {...buttonPropsOf(action())} />}
        </For>

        <span class="kui-bulkbar__rule" aria-hidden="true" />

        <Button iconOnly icon="close" variant="ghost" size="sm" onClick={() => props.onDismiss()}>
          {p.dismissLabel}
        </Button>
      </div>
    </Show>
  );
}
