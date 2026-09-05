/**
 * The badge that says the content under it is the last known value rather than the current one.
 *
 * ## Why stale content is shown at all
 *
 * The alternative — blanking a panel the moment its data goes out of date — throws away the only
 * information anybody has. A lag figure from four minutes ago is enormously more useful than an
 * empty box, *provided* the interface is honest that it is four minutes old. So the content stays,
 * dimmed to `--kui-opacity-stale`, and this badge sits above it saying how old it is and why.
 *
 * ## The three parts, and why the code is not the message
 *
 * SPEC §4.25 splits the badge into a state, a sentence and a code, and the split is the point.
 *
 *   - The **state** is the capability registry's own word: `Degraded`, `Unavailable`.
 *   - The **sentence** is what the operator can act on: *the metrics service is not answering*.
 *   - The **code** is what they quote to whoever they ask for help: `UPSTREAM_UNAVAILABLE`.
 *
 * `Stale: UPSTREAM_UNAVAILABLE` on its own gives the person looking at the panel nothing they can
 * do, and a reassuring sentence with the code thrown away gives the person they escalate to
 * nothing to search for. Both survive: the sentence is on screen, the code is on screen in mono at
 * the subtle text colour, and neither is allowed to replace the other.
 *
 * ## Relative time, and why the absolute time is in a `title`
 *
 * "4m ago" is what a human reads; the instant is what a human needs the moment they start
 * correlating with a log. The `<time>` element carries the machine-readable value in `datetime`
 * and the absolute rendering in `title`, so hovering gives the timestamp without a second control.
 */
import { Show } from "solid-js";
import { Icon } from "./Icon.jsx";

export interface StaleBadgeProps {
  /** The registry's own word for the state. Rendered verbatim. */
  readonly state?: string | undefined;
  /** When the value on screen was last known good. */
  readonly asOf: Date;
  /** One sentence saying why it is not current. Plain voice — this is a failure. */
  readonly detail: string;
  /** The machine-readable reason. Shown, never swallowed. */
  readonly code?: string | undefined;
  /** Overrides `Date.now()`. Tests need a clock they own; nothing else should pass this. */
  readonly now?: Date | undefined;
}

/**
 * "4m ago". Named `relativeAge` rather than `relativeTime` because `record.ts` already exports a
 * `relativeTime` for record timestamps, and two functions with one name in one barrel is a rename
 * waiting to pick the wrong one. Whole units only, largest that fits, because "4 minutes and 12 seconds ago" is a
 * precision nobody asked for and it changes every second, which makes a live region shout.
 */
export function relativeAge(asOf: Date, now: Date): string {
  const seconds = Math.max(0, Math.round((now.getTime() - asOf.getTime()) / 1000));
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export function StaleBadge(props: StaleBadgeProps) {
  const now = () => props.now ?? new Date();

  return (
    <p
      class="kui-stale-badge"
      // `status` and not `alert`: the content is still readable and nothing is being lost, so this
      // is worth telling a screen-reader user about without interrupting what they were reading.
      role="status"
    >
      <Icon name="warning" class="kui-stale-badge__glyph" />
      <span class="kui-stale-badge__state">{props.state ?? "Stale"}</span>
      <span class="kui-stale-badge__separator" aria-hidden="true">
        ·
      </span>
      <time class="kui-stale-badge__age" datetime={props.asOf.toISOString()} title={props.asOf.toLocaleString()}>
        {relativeAge(props.asOf, now())}
      </time>
      <span class="kui-stale-badge__separator" aria-hidden="true">
        ·
      </span>
      <span class="kui-stale-badge__detail">{props.detail}</span>
      <Show when={props.code}>
        {(code) => <code class="kui-stale-badge__code">{code()}</code>}
      </Show>
    </p>
  );
}
