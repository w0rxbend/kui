/**
 * The round initials in the top bar.
 *
 * ## Never invent somebody's initials
 *
 * If the identity service is unavailable the avatar draws a neutral person glyph, not a guess
 * (SPEC §4.11). Guessing is worse than admitting you do not know: an operator who sees the wrong
 * initials concludes they are signed in as somebody else, and the next thing they do is sign out
 * of a working session.
 *
 * That is why `name` is `string | undefined` and the undefined case is a rendering rather than a
 * default. A default would be a lie with a fallback's face on it.
 *
 * ## Initials from a name that might be anything
 *
 * A Kafka operator's display name can be an email address, a service account id, one word, or
 * five. The rule is the same one everywhere: the first letter of the first word and the first
 * letter of the last, uppercased, capped at two. Nothing clever — a clever rule is a rule that
 * produces something surprising for the one name nobody tested.
 */
import type { JSX } from "@solidjs/web";
import { Show, merge } from "solid-js";
import { Icon } from "../icon.jsx";

export interface AvatarProps {
  /** The person's display name, or undefined when it could not be read. */
  readonly name?: string | undefined;
  /** Renders as a `<button>` that opens the account menu. */
  readonly onClick?: (() => void) | undefined;
  readonly class?: string | undefined;
  /** What to call it when the name is unknown. */
  readonly unknownLabel?: string | undefined;
}

export function initialsOf(name: string): string {
  const words = name.trim().split(/[\s._@-]+/u).filter((w) => w.length > 0);
  const first = words[0];
  if (first === undefined) return "";
  const last = words.length > 1 ? words[words.length - 1] : undefined;
  const letters = last === undefined ? first.slice(0, 2) : `${first[0] ?? ""}${last[0] ?? ""}`;
  return letters.toUpperCase();
}

export function Avatar(props: AvatarProps): JSX.Element {
  const p = merge({ unknownLabel: "Account (name unavailable)" } as const, props);
  const known = (): boolean => props.name !== undefined && props.name.trim() !== "";
  const label = (): string => (known() ? `Account: ${props.name ?? ""}` : p.unknownLabel);

  const body = (): JSX.Element => (
    <Show when={known()} fallback={<Icon name="person" />}>
      {/* The letters are decoration: the accessible name is the whole name, not two letters of
          it. "O P" read aloud is not a person. */}
      <span aria-hidden="true">{initialsOf(props.name ?? "")}</span>
    </Show>
  );

  const classes = (): (string | undefined)[] => [
    "kui-avatar",
    known() ? undefined : "kui-avatar--unknown",
    props.class,
  ];

  return (
    <Show
      when={props.onClick !== undefined}
      fallback={
        <span class={classes()} role="img" aria-label={label()} title={label()}>
          {body()}
        </span>
      }
    >
      <button
        type="button"
        class={[...classes(), "kui-focusable"]}
        aria-label={label()}
        title={label()}
        onClick={() => props.onClick?.()}
      >
        {body()}
      </button>
    </Show>
  );
}
