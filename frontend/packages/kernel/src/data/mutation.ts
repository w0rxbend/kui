/**
 * Running a mutation, and the four states a control can be in while it happens.
 *
 * ## Why every write path shares one shape
 *
 * There are eight of them — create a topic, delete a topic, purge it, edit a setting, produce a
 * record, reset offsets, delete offsets, delete a group — and each has the same four moments and the
 * same three ways to go wrong. Written eight times, three of them would forget to disable the button
 * while the request is out, and two would report a 403 as though retrying might help.
 *
 * ## The rules this encodes
 *
 * **A running mutation cannot be started again.** Not a nicety: these are `POST`s and `DELETE`s
 * against a cluster. Two clicks on "Create topic" is two creates, and the second one's error
 * ("topic already exists") is the *first* one's success reported as a failure. `run` refuses while
 * `running`, so the guard cannot be forgotten at a call site.
 *
 * **A failure keeps the form.** `failed` carries the message and leaves whatever the user typed
 * where it is. A dialog that closes on failure loses the work and gives the operator nothing to
 * correct.
 *
 * **Forbidden is not failure.** A 403 means this principal may not do this, and a retry button is a
 * button that cannot work. It is a separate case so the control can say so instead.
 *
 * ## What it deliberately does not do
 *
 * It does not invalidate caches, close dialogs, or show toasts. Those are decisions about *this*
 * screen — whether a create should navigate to the new topic, whether a purge should refresh the
 * page behind it — and burying them here would make every call site's behaviour invisible at the
 * call site.
 */
import { createSignal, type Accessor } from "solid-js";
import {
  isForbidden,
  userMessage,
  type ApiError,
  type ApiResult,
} from "@kui/api";

export type Mutation<T> =
  | { readonly kind: "idle" }
  | { readonly kind: "running" }
  | { readonly kind: "done"; readonly value: T }
  | { readonly kind: "failed"; readonly message: string; readonly code: string }
  /** This principal may not do it. Distinct from failed: there is nothing to retry. */
  | { readonly kind: "forbidden"; readonly message: string };

export interface MutationHandle<A extends readonly unknown[], T> {
  readonly state: Accessor<Mutation<T>>;
  /** Runs it, unless one is already running. Resolves to the resulting state. */
  readonly run: (...args: A) => Promise<Mutation<T>>;
  /** Back to `idle` — for a dialog that reopens, or a form the user has started editing again. */
  readonly reset: () => void;
  /** True while a request is out. What a submit button's `disabled` and `busy` read. */
  readonly busy: Accessor<boolean>;
}

/**
 * Wraps a call that returns an {@link ApiResult} into a mutation with state.
 *
 * @param call the request. It receives whatever `run` was given.
 */
export function createMutation<A extends readonly unknown[], T>(
  call: (...args: A) => Promise<ApiResult<T>>,
): MutationHandle<A, T> {
  const [state, setState] = createSignal<Mutation<T>>(
    { kind: "idle" },
    { ownedWrite: true },
  );

  const run = async (...args: A): Promise<Mutation<T>> => {
    // The guard that cannot be forgotten. Two clicks is two creates, and the second failure is the
    // first success wearing a red panel.
    if (state().kind === "running") return state();

    setState({ kind: "running" });
    const answer = await call(...args);
    const next: Mutation<T> = answer.ok
      ? { kind: "done", value: answer.value }
      : failureOf(answer.error);
    // `setState(() => next)` rather than `setState(next)`: a signal setter given a function calls
    // it, and a `done` value that happens to be a function would be invoked instead of stored.
    setState(() => next);
    return next;
  };

  return {
    state,
    run,
    reset: () => setState({ kind: "idle" }),
    busy: () => state().kind === "running",
  };
}

function failureOf(error: ApiError): Mutation<never> {
  if (isForbidden(error)) {
    return {
      kind: "forbidden",
      message: userMessage(error),
    };
  }
  return {
    kind: "failed",
    // `userMessage` rather than `error.message`: only the envelope case has one, and a screen that
    // reaches for `.message` on an unreachable gateway prints the word "undefined" at exactly the
    // moment something is wrong.
    message: userMessage(error),
    code: error.kind === "envelope" ? error.code : error.kind.toUpperCase(),
  };
}

/**
 * The sentence a control shows when it is disabled, or `undefined` when it is not.
 *
 * Two reasons, and they are genuinely different: this principal may not, or this *cluster* may not.
 * ADR-047's read-only flag is a property of the deployment, not of the person, and telling somebody
 * to ask an administrator for a permission they already hold wastes their afternoon.
 */
export function writeBlockedReason(options: {
  readonly permitted: boolean;
  readonly readOnly: boolean;
  /** "create a topic", "publish into this topic" — completes "You do not have permission to …". */
  readonly action: string;
}): string | undefined {
  if (options.readOnly) {
    return `This cluster is configured read-only in KUI, so nothing here can ${options.action}.`;
  }
  if (!options.permitted) {
    return `You do not have permission to ${options.action}.`;
  }
  return undefined;
}
