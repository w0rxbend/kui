/**
 * Toasts: the short confirmation that something you asked for happened.
 *
 * ## What a toast may and may not carry
 *
 * A toast is for the *result of an action the operator just took*, and only when they do not need
 * to do anything about it: "Topic created", "Offsets reset", "Copied". It is the wrong shape for
 * anything else, because it goes away.
 *
 * Two rules fall straight out of that, and they are the ones this module enforces rather than
 * documents:
 *
 *   1. **An error toast never auto-dismisses.** A message that disappears on a timer is a message
 *      the reader may not have finished, and if the news is that something failed they need the
 *      code that is in it. `danger` toasts stay until they are dismissed.
 *   2. **The timer pauses while the pointer is on the stack or focus is inside it.** Somebody
 *      reaching for the dismiss button, or tabbing to the undo action, must not have the toast
 *      vanish out from under them mid-reach.
 *
 * ## Why the region is always in the document
 *
 * `ToastRegion` renders an empty live region at all times, even with nothing to show. A live
 * region that is *created* at the moment it receives its first message is, in several
 * screen-reader and browser combinations, not announced at all — the reader has to have been
 * watching the element before the text arrived. An always-present empty region is the difference
 * between a confirmation that is announced and one that silently is not.
 *
 * `aria-live="polite"` and not `assertive`: a confirmation waits for a pause in whatever the
 * reader is doing. The one exception is a `danger` toast, which carries `role="alert"` on the
 * toast itself.
 *
 * ## Why the store is a module-level signal
 *
 * There is one toast stack per application, so this is genuinely global state rather than
 * something scoped to a subtree — and Solid 2's guidance is explicit that a module-scope signal
 * *is* the global, and that Context exists for subtree scoping rather than for app-wide state.
 * `notify()` can therefore be called from anywhere, including from a plain async function with no
 * component around it, which is exactly where the results of mutations arrive.
 */
import { createSignal, For, onSettled, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Icon, type IconName } from "./Icon.jsx";

export type ToastTone = "success" | "info" | "warning" | "danger";

export interface Toast {
  readonly id: number;
  readonly tone: ToastTone;
  /** A short sentence in the past tense. "Topic created", not "Creating topic". */
  readonly title: string;
  /** Optional detail: what it applied to, or why it failed. */
  readonly message?: string | undefined;
  /** The stable code, for a failure. Shown in mono; never swallowed. */
  readonly code?: string | undefined;
  /**
   * How long before it goes away, in milliseconds. `null` means it stays until dismissed, which is
   * forced for `danger` regardless of what is passed.
   */
  readonly durationMs: number | null;
  /** One action, most usefully "Undo". */
  readonly action?: { readonly label: string; readonly onClick: () => void } | undefined;
}

/** How many are drawn at once. Beyond this the stack says how many more are waiting. */
export const MAX_VISIBLE_TOASTS = 3;

const DEFAULT_DURATION_MS = 6000;

/*
 * The store is a plain array plus a signal that mirrors it, and the array is the source of truth.
 *
 * This is not belt-and-braces. Solid 2 batches writes onto a microtask, and an updater passed to a
 * setter is applied to the last *committed* value — so two `notify()` calls in the same tick, which
 * is exactly what a page raising two confirmations at once does, both compute `[...previous, mine]`
 * from the same empty list and the second one wins. One toast disappears, silently, and only when
 * two things happen at once. Keeping the list in a plain variable that updates synchronously and
 * pushing the whole list into the signal each time makes every write absolute, so ordering inside a
 * tick cannot lose one.
 *
 * `ownedWrite` because `notify` is called from anywhere, including from inside an effect or an
 * action — which are owned scopes, and Solid 2 refuses signal writes from those by default. This is
 * a module-level store with exactly one writer path, which is the narrow case the option is for.
 */
let items: readonly Toast[] = [];
const [toasts, setToasts] = createSignal<readonly Toast[]>(items, { ownedWrite: true });

function commit(next: readonly Toast[]): void {
  items = next;
  setToasts(next);
}

export { toasts };

let nextId = 0;

export interface NotifyOptions {
  readonly tone?: ToastTone;
  readonly message?: string | undefined;
  readonly code?: string | undefined;
  /** Overrides the default. Ignored for `danger`, which never auto-dismisses. */
  readonly durationMs?: number | null | undefined;
  readonly action?: { readonly label: string; readonly onClick: () => void } | undefined;
}

/** Raise a toast. Returns its id, so a caller that knows the work finished can dismiss it early. */
export function notify(title: string, options: NotifyOptions = {}): number {
  const tone = options.tone ?? "success";
  nextId += 1;
  const toast: Toast = {
    id: nextId,
    tone,
    title,
    message: options.message,
    code: options.code,
    // The forcing happens here rather than in the component, so that every reader of a `Toast` —
    // including a test — sees the same truth about whether it will disappear.
    durationMs: tone === "danger" ? null : (options.durationMs ?? DEFAULT_DURATION_MS),
    action: options.action,
  };
  commit([...items, toast]);
  return toast.id;
}

export function dismissToast(id: number): void {
  commit(items.filter((toast) => toast.id !== id));
}

/** For tests and for a route change that should not carry the previous page's confirmations. */
export function clearToasts(): void {
  commit([]);
}

const GLYPH: Record<ToastTone, IconName> = {
  success: "check",
  info: "info",
  warning: "warning",
  danger: "error",
};

/**
 * The stack. Mount it once, at the application root.
 *
 * Not in a `Portal`: the region has to be a stable element that exists from the first paint, and
 * a portal that is created and destroyed with its content is exactly the thing the note at the
 * top of this file warns about. It is positioned by the stylesheet instead.
 */
export function ToastRegion(): JSX.Element {
  const [paused, setPaused] = createSignal(false, { ownedWrite: true });

  const visible = () => toasts().slice(0, MAX_VISIBLE_TOASTS);
  const queued = () => Math.max(0, toasts().length - MAX_VISIBLE_TOASTS);

  return (
    <div
      class="kui-notice-stack"
      // The region itself, always present. See the note at the top of this file.
      aria-live="polite"
      aria-relevant="additions text"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocusIn={() => setPaused(true)}
      onFocusOut={() => setPaused(false)}
    >
      <For each={visible()}>{(toast) => <ToastItem toast={toast} paused={paused} />}</For>

      <Show when={queued() > 0}>
        <p class="kui-notice-stack__queued">{queued()} more</p>
      </Show>
    </div>
  );
}

function ToastItem(props: { readonly toast: Toast; readonly paused: () => boolean }): JSX.Element {
  onSettled(() => {
    const duration = props.toast.durationMs;
    if (duration === null) return;

    // A repeating tick rather than a single `setTimeout`, because the timer has to be pausable and
    // resumable. A `setTimeout` that is cleared and restarted on every hover gives the toast a
    // fresh full lifetime each time the pointer crosses it, which means a stack somebody is
    // reading never clears.
    const step = 100;
    let elapsed = 0;
    const id = setInterval(() => {
      if (props.paused()) return;
      elapsed += step;
      if (elapsed >= duration) {
        clearInterval(id);
        dismissToast(props.toast.id);
      }
    }, step);

    return () => clearInterval(id);
  });

  return (
    <div
      class={["kui-notice", `kui-notice--${props.toast.tone}`]}
      // A failure interrupts; a confirmation waits its turn inside the polite region above.
      role={props.toast.tone === "danger" ? "alert" : undefined}
      data-testid={`toast-${props.toast.id}`}
    >
      <Icon name={GLYPH[props.toast.tone]} class="kui-notice__glyph" />

      <div class="kui-notice__content">
        <p class="kui-notice__title">{props.toast.title}</p>
        <Show when={props.toast.message}>
          {(message) => <p class="kui-notice__message">{message()}</p>}
        </Show>
        <Show when={props.toast.code}>
          {(code) => <code class="kui-notice__code">{code()}</code>}
        </Show>
        <Show when={props.toast.action}>
          {(action) => (
            <button
              type="button"
              class="kui-notice__action kui-focusable"
              onClick={() => {
                action().onClick();
                dismissToast(props.toast.id);
              }}
            >
              {action().label}
            </button>
          )}
        </Show>
      </div>

      <button
        type="button"
        class="kui-notice__dismiss kui-focusable"
        // Names what it dismisses, so a stack of three does not read as three identical buttons.
        aria-label={`Dismiss: ${props.toast.title}`}
        onClick={() => dismissToast(props.toast.id)}
      >
        <Icon name="close" />
      </button>
    </div>
  );
}
