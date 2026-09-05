/**
 * The page-wide banner: one sentence about something wrong with the whole cluster.
 *
 * ## When a banner is the right shape
 *
 * Almost never. A banner takes a strip off the top of every page and pushes the content down, so
 * it has to be about something that makes the content underneath *misleading* — the cluster is
 * unreachable, KUI is in read-only mode, this cluster's connection is misconfigured. A panel that
 * failed is not a banner; it says so in its own frame (see `Card`), because that is where the
 * reader is looking when they want to know about it.
 *
 * ## One banner, maximum
 *
 * A stack of banners is a wall, and a wall is scrolled past. If two conditions are true at once,
 * the more severe one is shown and the other is said in the panel it belongs to. This is a
 * decision the caller has to make, so the component takes a single banner rather than a list —
 * there is no way to hand it two.
 *
 * ## The voice, here specifically
 *
 * Nothing playful. A banner only ever appears when something is wrong, and SPEC §6 rule 4 puts
 * error messages outside the voice entirely. *"The cluster is not answering. Last successful check
 * was 4 minutes ago."* — a fact, and a second fact that tells the operator how stale everything
 * below it is. No aside.
 *
 * ## Announcing it
 *
 * A `danger` banner is `role="alert"`, which interrupts a screen reader. That is right for it and
 * wrong for the others: a `warning` or `info` banner uses `role="status"`, which waits for a pause.
 * Making everything an alert means the first one is the only one anybody hears.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Icon, type IconName } from "./Icon.jsx";

export type BannerTone = "danger" | "warning" | "info";

export interface BannerProps {
  readonly tone: BannerTone;
  /** One sentence. Two facts at most. If it needs a paragraph, it is not a banner. */
  readonly message: string;
  /** The stable code, for whoever the operator escalates to. Never swallowed. */
  readonly code?: string | undefined;
  /**
   * One action, and it must be the thing that fixes or investigates this — Retry, Reconnect,
   * View brokers. A banner with two actions is a dialog that forgot to open.
   */
  readonly action?: JSX.Element | undefined;
  /**
   * Lets the operator put it away. Offer it only where the condition is one they have chosen to
   * live with — read-only mode, a deprecation notice. A cluster that is not answering must not be
   * dismissible, because dismissing it makes every stale number on the page look current.
   */
  readonly onDismiss?: (() => void) | undefined;
  readonly testId?: string | undefined;
}

const GLYPH: Record<BannerTone, IconName> = {
  danger: "error",
  warning: "warning",
  info: "info",
};

export function Banner(props: BannerProps): JSX.Element {
  return (
    <div
      class={["kui-banner", `kui-banner--${props.tone}`]}
      data-testid={props.testId}
      role={props.tone === "danger" ? "alert" : "status"}
    >
      {/* Decoration. The tone is already carried by the words, because around one man in twelve
          cannot separate the red from the amber and a screen reader gets no colour at all. */}
      <Icon name={GLYPH[props.tone]} class="kui-banner__glyph" />

      <p class="kui-banner__message">
        {props.message}
        <Show when={props.code}>{(code) => <code class="kui-banner__code">{code()}</code>}</Show>
      </p>

      <Show when={props.action}>{(action) => <div class="kui-banner__action">{action()}</div>}</Show>

      <Show when={props.onDismiss}>
        {(onDismiss) => (
          <button
            type="button"
            class="kui-banner__dismiss kui-focusable"
            // Names the action, not the picture: "Close" would leave a screen-reader user guessing
            // what closes.
            aria-label="Dismiss this notice"
            onClick={() => onDismiss()()}
          >
            <Icon name="close" />
          </button>
        )}
      </Show>
    </div>
  );
}
