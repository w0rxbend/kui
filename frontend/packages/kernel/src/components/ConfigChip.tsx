/**
 * One configuration setting, drawn as a chip: the key on the left, the value on the right.
 *
 * ## Why configuration is chips rather than a table
 *
 * A broker has around two hundred settings and an operator wants eight of them. A table of two
 * hundred rows makes the eight unfindable; a wrapping row of chips lets the important ones sit at
 * the front, lets the eye skip, and reflows to whatever width the card has (SCREENS.md §2.15).
 *
 * The trade is real and worth naming: chips are worse than a table for comparing the *same*
 * setting across brokers, because the chips are not in columns. That comparison is the broker
 * detail screen's job, not this one's.
 *
 * ## The value is monospaced and the key is not
 *
 * The key is prose — `log.retention.hours` reads as words. The value is a quantity that is
 * compared by eye against other values, and `1 GB` under `168` under `producer` only lines up if
 * the digits are the same width. So the key takes the body face and the value takes the mono face,
 * which is the same rule offsets and partition ids follow everywhere else in the product.
 *
 * ## An absent value is a dash, never an empty right side
 *
 * A chip with nothing after its key looks like a chip that failed to finish drawing. `—` says the
 * setting exists and has no value, which is a real state — an unset `compression.type` is
 * different from a `compression.type` we could not read, and different again from one that is
 * `none`.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";

export interface ConfigChipProps {
  readonly name: string;
  /**
   * The already-formatted value: `1 GB`, `168`, `producer`. Formatting belongs to the caller,
   * which knows the setting's unit; this component knows only that a value is a value.
   *
   * `undefined` means "genuinely has no value" and draws an em dash. It does not mean "not loaded"
   * — a chip that has not loaded should not be drawn at all, because the card it is in carries the
   * loading state for the whole set.
   */
  readonly value: string | undefined;
  /**
   * Marks the setting as differing from the cluster default. Drawn as a dot before the key and
   * stated in the title, because an override is the single thing an operator scans this list for
   * and finding it by reading two hundred chips is not scanning.
   */
  readonly overridden?: boolean | undefined;
  /**
   * A description, shown as the chip's title. Kafka's setting names are terse to the point of
   * being cryptic, and the documentation for them already exists in this product.
   */
  readonly description?: string | undefined;
  readonly testId?: string | undefined;
}

export function ConfigChip(props: ConfigChipProps): JSX.Element {
  const title = () => {
    const parts = [props.name];
    if (props.description !== undefined) parts.push(props.description);
    if (props.overridden === true) parts.push("Overridden from the cluster default");
    return parts.join(" — ");
  };

  return (
    <div
      class={["kui-config-chip", { "kui-config-chip--overridden": props.overridden === true }]}
      title={title()}
      data-testid={props.testId}
    >
      <Show when={props.overridden === true}>
        {/* Decoration: the title above says it in words, which is what a screen reader gets. */}
        <span class="kui-config-chip__mark" aria-hidden="true" />
      </Show>
      <span class="kui-config-chip__name">{props.name}</span>
      <Show
        when={props.value}
        fallback={
          <span class="kui-config-chip__value kui-config-chip__value--none" title="No value is set">
            —
          </span>
        }
      >
        {(value) => <span class="kui-config-chip__value">{value()}</span>}
      </Show>
    </div>
  );
}

export interface ConfigChipsProps {
  /** The accessible name of the set, e.g. "broker-1.kyiv configuration". */
  readonly label: string;
  readonly children: JSX.Element;
  readonly testId?: string | undefined;
}

/**
 * The wrapping container. A `<dl>` would be the semantically ideal element for key/value pairs, but
 * a definition list whose terms and definitions must wrap *together* as units needs each pair in a
 * `<div>`, and the resulting markup is a list in name only. A labelled group of chips reads
 * correctly and is what it is.
 */
export function ConfigChips(props: ConfigChipsProps): JSX.Element {
  return (
    <div class="kui-config-chips" role="group" aria-label={props.label} data-testid={props.testId}>
      {props.children}
    </div>
  );
}
