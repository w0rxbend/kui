/**
 * A quantity against a limit — the broker-health disk bar of SPEC §4.20.
 *
 * Three things this component exists to get right, each of them a rule from the spec rather than
 * a preference:
 *
 * 1. **An unknown value is not a zero.** A 0%-full disk and a disk we could not measure look
 *    identical if both draw an empty track, and they mean opposite things. An unknown value draws
 *    the track with no fill at all *and* prints the em dash where the percentage goes, so the two
 *    pictures differ in a place the reader is already looking.
 * 2. **The thresholds are fixed here, once.** Warn at 75%, critical at 90% (SPEC §4.20), so that
 *    three panels cannot disagree about when a disk is worrying.
 * 3. **The colour is never the only signal.** The percentage text is always printed, and above
 *    critical the caller is expected to add a status pill in words. Around one man in twelve
 *    cannot separate the amber from the green.
 */
import { Show, type Component } from "solid-js";
import type { JSX } from "@solidjs/web";
import { DEFAULT_THRESHOLDS, fraction, formatPercent, levelFor, type MaybeNumber, type Thresholds } from "./format.js";

/* Every optional prop is written `?: T | undefined` rather than `?: T`. Under this workspace's
 * `exactOptionalPropertyTypes`, `?: T` means "absent, or a T" and specifically *not* `undefined`,
 * so a caller holding a `T | undefined` cannot forward it — which is every caller that computes a
 * value that might be missing, i.e. the reason this component takes a `MaybeNumber` at all. */
export interface ProgressBarProps {
  /** Names the quantity for a screen reader: `broker-3.kyiv disk usage`, not `progress`. */
  readonly label: string;
  /** Shown to the left of the track. Short: `disk`. Omit for a bare bar. */
  readonly caption?: string | undefined;
  /** `undefined` means "we could not measure it" — see rule 1 above. */
  readonly value: MaybeNumber;
  readonly max?: number | undefined;
  readonly thresholds?: Thresholds | undefined;
  /** Overrides the printed figure when the caller formats it differently (`3.4 / 4 GB`). */
  readonly valueText?: string | undefined;
  /** Rendered after the bar; the broker panel puts its status pill here. */
  readonly trailing?: JSX.Element | undefined;
}

export const ProgressBar: Component<ProgressBarProps> = props => {
  const max = (): number => props.max ?? 100;
  const known = (): boolean => props.value !== undefined && Number.isFinite(props.value);
  // `fraction` guards the denominator, which is what stops a max of 0 producing a full bar.
  const percent = (): number => fraction(props.value, max()) * 100;
  const level = (): string => (known() ? levelFor(percent(), props.thresholds ?? DEFAULT_THRESHOLDS) : "unknown");
  const text = (): string => props.valueText ?? formatPercent(known() ? percent() : undefined);

  return (
    <div class="kui-progress">
      <Show when={props.caption}>
        <span class="kui-progress__caption">{props.caption}</span>
      </Show>

      {/*
        `role="progressbar"` with the value on it, rather than an `aria-hidden` bar next to text:
        the bar *is* the value here, unlike the magnitude bar in a list where the number is printed
        beside it. An unknown value omits `aria-valuenow`, which is how ARIA spells "indeterminate";
        setting it to 0 would tell a screen reader the disk is empty.
      */}
      <div
        class={["kui-progress__track", `kui-progress__track--${level()}`]}
        role="progressbar"
        aria-label={props.label}
        aria-valuemin={0}
        aria-valuemax={max()}
        aria-valuenow={known() ? props.value : undefined}
        aria-valuetext={text()}
      >
        <Show when={known()}>
          <div class="kui-progress__fill" style={{ width: `${percent()}%` }} />
        </Show>
      </div>

      <span class={["kui-progress__value", `kui-progress__value--${level()}`]}>{text()}</span>
      <Show when={props.trailing}>{props.trailing}</Show>
    </div>
  );
};
