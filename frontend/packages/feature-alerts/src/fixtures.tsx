/**
 * One document in, one screen state out — through the store the shell actually drives.
 *
 * ## Why this file replaced `wire.ts`
 *
 * `wire.ts` held a `feedSection` that pulled the `events` section off the envelope, decoded its
 * payload with the kernel's `decodeAlertFeed`, and answered a `Section<AlertFeed>`. Every line of
 * that already existed in `@kui/kernel`'s `createAlerts`, which is what the shell calls and what
 * therefore decides what this screen is handed in production — and **nothing in the product ever
 * called `feedSection`**. It was a second reader of one wire, exercised only by the cases and
 * stories that were supposed to be checking the first one. Wave 5's producers wire is what two
 * readers of one document cost: both sides passed their own cases and the card drew nothing.
 *
 * So there is now one reader, it is the kernel's, and this module is the harness that drives it.
 * The cases and the stories below get their state the same way the running product does: a `load`
 * that answers a document, `createAlerts`, `start()`, and whatever `feed()` then holds.
 *
 * ## The one thing that is more than mechanical, said out loud
 *
 * The collapse is not free. `feedSection` answered synchronously, and `createAlerts` cannot: it is
 * a store with a read in flight, so every caller here is either asynchronous (the cases, which
 * `await`) or reactive (the stories, which render {@link FromDocument} and redraw when the read
 * lands). A story therefore paints its loading state for one microtask before it paints the card,
 * which is exactly what a reader sees in a browser and is the reason it is acceptable. The
 * alternative — keeping a synchronous copy of the read path for stories only — is the second
 * reader again, wearing a different name.
 *
 * This module is imported by `*.test.tsx` and `*.stories.tsx` and by nothing that `index.tsx`
 * exports, so it is not reachable from the feature's entry point and never reaches the bundle.
 */
import { createRoot, flush, onCleanup } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  createAlerts,
  type AlertFeed,
  type Alerts,
  type Fetched,
  type SseHandle,
} from "@kui/kernel";

/**
 * A stream that is already closed.
 *
 * Every case and story here is about a *read*. An open stream would add a second writer to the
 * signal under assertion and make the state depend on when a frame happened to arrive; the store's
 * own suite in `@kui/kernel` is where the stream's behaviour is pinned.
 */
function quietStream(): SseHandle {
  return {
    connection: () => ({ phase: "closed", reason: "no stream in this case" }),
    close: () => {},
    endMarker: () => undefined,
  } as SseHandle;
}

/**
 * The store, reading one document. The caller owns disposal.
 *
 * Module-private: `feedState` and `FromDocument` below are its only callers and always were, and an
 * export nothing outside this file names is a promise this module is not being asked for.
 */
function storeFor(document: unknown): Alerts {
  return createAlerts({
    load: async () => ({ ok: true, value: document }),
    openStream: quietStream,
  });
}

/**
 * The state the screen is handed for this document.
 *
 * Eight flushes rather than one: the read resolves on a microtask and Solid settles its writes on
 * the next, so a single turn reads `loading` and the case fails as though the decoder were broken.
 * The same number, for the same reason, as `alertsRoute.test.tsx`'s `settle`.
 */
export async function feedState(document: unknown): Promise<Fetched<AlertFeed>> {
  const [alerts, dispose] = createRoot((disposeRoot) => {
    const store = storeFor(document);
    store.start();
    return [store, disposeRoot] as const;
  });
  for (let turn = 0; turn < 8; turn += 1) await flush();
  const state = alerts.feed();
  alerts.stop();
  dispose();
  return state;
}

/**
 * The page inside a document that decodes, or a failure naming what the screen was handed instead.
 *
 * The message carries the store's own sentence for a refusal, because that is the sentence a reader
 * would have seen: a case that failed with "expected ready" and dropped it would hide the very
 * thing — a renamed field, a row with no date — that made the document unreadable.
 */
export async function pageOf(document: unknown): Promise<AlertFeed> {
  const state = await feedState(document);
  if (state.kind === "ready" || state.kind === "stale") return state.value;
  const because = state.kind === "failed" ? `: ${state.message}` : "";
  throw new Error(`expected a readable feed, the store answered ${state.kind}${because}`);
}

export interface FromDocumentProps {
  readonly document: unknown;
  /** What to draw with whatever the store holds, redrawn when the read lands. */
  readonly children: (state: Fetched<AlertFeed>) => JSX.Element;
}

/**
 * A story's document, read the way the shell reads one.
 *
 * The store is created inside the component so that it is owned and disposed with the story rather
 * than living for the life of the Storybook tab; an abandoned store is the leak
 * `@kui/kernel`'s own header warns about.
 */
export function FromDocument(props: FromDocumentProps): JSX.Element {
  const alerts = storeFor(props.document);
  alerts.start();
  onCleanup(() => alerts.stop());
  return <>{props.children(alerts.feed())}</>;
}
