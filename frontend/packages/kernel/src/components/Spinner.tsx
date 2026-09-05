/**
 * The busy indicator.
 *
 * It is not in the icon set, and that is deliberate: every icon in `icon.tsx` is a static,
 * decorative shape, and this one is the single glyph whose job is to change over time. Keeping it
 * out means the icon set has exactly one rule about motion — there isn't any — and the
 * reduced-motion decision lives beside the animation it suppresses.
 *
 * Under `prefers-reduced-motion` the spin is **suppressed, not slowed**. A slow spin is still a
 * spin, and the people who set that preference did not ask for a gentler version of the thing that
 * makes them ill. The arc keeps its shape, so "this is busy" is still drawn; what goes away is the
 * movement. That is also why the shape is a three-quarter arc rather than a full circle: a full
 * circle looks identical at every angle, so a motionless one would look like nothing at all.
 */
import type { JSX } from "@solidjs/web";

export interface SpinnerProps {
  readonly size?: string | undefined;
  /**
   * An accessible name. Omit it inside a control that is already marked `aria-busy` — the control
   * has said it, and saying it twice is noise.
   */
  readonly label?: string | undefined;
  readonly class?: string | undefined;
}

export function Spinner(props: SpinnerProps): JSX.Element {
  return (
    <svg
      class={["kui-icon", "kui-spinner2", props.class]}
      viewBox="0 0 24 24"
      width={props.size ?? "1em"}
      height={props.size ?? "1em"}
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      role={props.label === undefined ? undefined : "img"}
      aria-hidden={props.label === undefined ? "true" : undefined}
      aria-label={props.label}
    >
      <path d="M12 3a9 9 0 1 0 9 9" />
    </svg>
  );
}
