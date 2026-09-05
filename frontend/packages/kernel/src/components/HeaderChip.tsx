import type { JSX } from "@solidjs/web";
import { createSignal, Show } from "solid-js";

/**
 * One Kafka record header, drawn as a chip.
 *
 * ## Three different absences, three different renderings
 *
 * A header can be absent, present-and-empty, or present-and-not-text, and collapsing any two of
 * them loses something the operator needs:
 *
 *   - **Absent** — the chip simply is not there. Nothing to draw.
 *   - **Present with an empty value** — `name: (empty)` at the subtle text colour. A producer that
 *     sets `correlation-id` to the empty string has a bug; a producer that does not set it at all
 *     has a different bug. Drawing the first as the second hides one of them.
 *   - **Not valid UTF-8** — the bytes as `0x…` with a `binary` marker. Never a replacement
 *     character: `�` is what a decoder produces when it gives up, and rendering it as though
 *     it were the data tells the operator their header contains a question mark in a box, which
 *     it does not.
 *
 * ## Why the chip is a button
 *
 * A correlation id exists to be pasted into a log search. The chip copies its value on click,
 * announces that it did in a live region, and says so visibly for the people who do not have one.
 * It is a real `<button>`, so it is in the tab order and answers to Enter and Space without any of
 * that being written here.
 */
export interface HeaderChipProps {
  readonly name: string;
  /**
   * The value. `null` means the header was present with an empty value — not that it was absent;
   * an absent header is not rendered at all, by the caller.
   */
  readonly value: string | null;
  /** The value was not valid UTF-8 and `value` is its hex rendering. */
  readonly binary?: boolean;
  readonly testId?: string;
}

/** How much of a value fits on a chip before it is truncated. Past this the chip dominates the row
 * it is in, and the whole value is one click away in the clipboard and one hover away in the
 * tooltip. */
const MAX_VALUE_CHARS = 48;

export function HeaderChip(props: HeaderChipProps): JSX.Element {
  const [copied, setCopied] = createSignal(false);

  const displayValue = () => {
    if (props.value === null) return "(empty)";
    if (props.value.length <= MAX_VALUE_CHARS) return props.value;
    return `${props.value.slice(0, MAX_VALUE_CHARS - 1)}…`;
  };

  async function copy(): Promise<void> {
    // A clipboard write can be refused — an insecure origin, a browser policy, a user who denied
    // permission. Refusing quietly would leave the chip claiming it copied something it did not,
    // so failure leaves the label alone.
    try {
      await navigator.clipboard.writeText(props.value ?? "");
      setCopied(true);
      globalThis.setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  }

  return (
    <button
      type="button"
      class={[
        "kui-header-chip",
        {
          "kui-header-chip--empty": props.value === null,
          "kui-header-chip--binary": props.binary === true,
        },
      ]}
      data-testid={props.testId}
      // The visible text is truncated; the accessible name and the tooltip are not. A screen
      // reader and a hover both get the whole value.
      title={props.value ?? "(empty)"}
      aria-label={`Copy header ${props.name}: ${props.value ?? "(empty)"}`}
      onClick={() => void copy()}
    >
      <span class="kui-header-chip__name">{props.name}:</span>
      <span class="kui-header-chip__value">{displayValue()}</span>
      <Show when={props.binary === true}>
        <span class="kui-header-chip__marker">binary</span>
      </Show>
      {/* The confirmation. `aria-live="polite"` so it is announced without interrupting, and it is
          text rather than a colour change so it reaches everybody. */}
      <span class="kui-header-chip__copied" aria-live="polite">
        <Show when={copied()}>Copied</Show>
      </span>
    </button>
  );
}
