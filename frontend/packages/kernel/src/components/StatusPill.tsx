/**
 * A status pill: a small stadium that says, in words, what state something is in.
 *
 * ## Colour is never the only signal
 *
 * Every pill carries text. Around one man in twelve cannot separate the red from the green, and a
 * screen reader gets no colour at all — so the tone is a second, redundant channel and the words
 * are the first one (SPEC §4.0). A pill whose text is "" is not rendered: an empty stadium is a
 * shape with no meaning, and it reads as a rendering bug.
 *
 * ## `role="status"` is opt-in, and rarely
 *
 * A pill whose text changes on its own — a consumer group leaving `Stable` — should announce
 * itself without focus moving, which is what `live` is for. A pill that simply labels something
 * must not: a page of announcing pills announces nothing useful, because a screen reader queues
 * them and the operator hears a paragraph of state every time anything changes. The default is
 * silent (SPEC §4.7).
 *
 * ## `dataState` is for a machine, and the words are for a person
 *
 * The text is the product's vocabulary and it is allowed to change — "state not reported" was
 * chosen over "unknown" because of what it says to a reader, and a future wave may say it better.
 * A browser case that asserts a pill by its words therefore has to transcribe them, and
 * `frontend/e2e/connect.spec.ts`'s `wordFor` is what that looks like: five labels copied out of a
 * component into a spec, correct on the day they were copied and gated by nothing after it.
 * `dataState` is the other channel — the machine word the drawing came *from* — so a case can
 * compare what is on the screen to what the API said without knowing a single label.
 */
import type { JSX } from "@solidjs/web";
import { Show, merge } from "solid-js";
import { Icon, type IconName } from "../icon.jsx";

export type PillTone = "success" | "warning" | "danger" | "accent" | "neutral";

export interface StatusPillProps {
  readonly children: string;
  readonly tone?: PillTone | undefined;
  /** A 6px dot before the text. Decorative; the text still says the state. */
  readonly dot?: boolean | undefined;
  /** The dot breathes — for a live tail. Suppressed under `prefers-reduced-motion`. */
  readonly pulsing?: boolean | undefined;
  readonly icon?: IconName | undefined;
  /** Announce changes to this pill's text without moving focus. Use sparingly. */
  readonly live?: boolean | undefined;
  /**
   * The machine word this pill was drawn from, published as `data-state`. See the header.
   *
   * Never rendered and never read by this component: it exists so that a caller drawing a state
   * out of a wire can publish the wire's own word beside the sentence it chose for it. Absent when
   * the pill is a label rather than a drawing of a reported state, in which case there is no such
   * word and inventing one would be a claim.
   */
  readonly dataState?: string | undefined;
  /** Renders as a `<button>`: LIVE / PAUSED is a toggle, not a label. */
  readonly onClick?: (() => void) | undefined;
  readonly disabled?: boolean | undefined;
  readonly pressed?: boolean | undefined;
  readonly title?: string | undefined;
  readonly class?: string | undefined;
}

export function StatusPill(props: StatusPillProps): JSX.Element {
  const p = merge({ tone: "neutral" } as const, props);

  const body = (): JSX.Element => (
    <>
      <Show when={props.dot === true}>
        <span
          class={["kui-pill__dot", { "kui-pill__dot--pulsing": props.pulsing === true }]}
          aria-hidden="true"
        />
      </Show>
      <Show when={props.icon}>{(name) => <Icon name={name()} />}</Show>
      <span class="kui-pill__text">{props.children}</span>
    </>
  );

  const classes = (): (string | undefined)[] => ["kui-pill", `kui-pill--${p.tone}`, props.class];

  return (
    <Show when={props.children !== ""}>
      <Show
        when={props.onClick !== undefined}
        fallback={
          <span
            class={classes()}
            role={props.live === true ? "status" : undefined}
            title={props.title}
            data-state={props.dataState}
          >
            {body()}
          </span>
        }
      >
        <button
          type="button"
          class={[...classes(), "kui-focusable"]}
          aria-pressed={props.pressed === undefined ? undefined : props.pressed ? "true" : "false"}
          disabled={props.disabled === true}
          title={props.title}
          data-state={props.dataState}
          onClick={() => props.onClick?.()}
        >
          {body()}
        </button>
      </Show>
    </Show>
  );
}
