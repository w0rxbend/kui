/**
 * The browser's picture of what every service can currently do (ADR-032).
 *
 * ## What it is for
 *
 * Every navigation entry, every fallback panel and the cluster switcher are drawn from this one
 * store. It is fed by `/api/v1/capabilities/stream`, and when that stream cannot be held open it
 * falls back to asking `/api/v1/capabilities` on a timer — slower and noisier, and still far better
 * than a navigation frozen at whatever it happened to know when the connection dropped.
 *
 * ## The rules, each of which is a defect this product shipped
 *
 * - **The old stream is closed before a new one is opened.** Leaving it open left an `EventSource`
 *   retrying on its own for the life of the tab: unreachable through the store, so `stop()` could
 *   never close it, yet still a gateway subscriber delivering every delta a second time. The
 *   fallback re-opened one every thirty seconds.
 * - **A failed poll changes nothing.** Both the stream and the poller being down means the picture
 *   is stale, which {@link Capabilities.stale} says. It does not mean every feature is broken, and
 *   marking them so would take a working product off the air because one endpoint is down.
 * - **A business error never dims a capability.** Nothing outside this file writes into it: a
 *   feature whose own request failed renders its own failure, and the health of a service is only
 *   ever what the gateway said about it. There is no method here for a feature to call.
 * - **"Trying" must not overwrite "stale".** A replacement handle starts in `connecting`, and
 *   letting that through took the staleness banner down every time the fallback had another go at
 *   the stream — the user was shown thirty-second-old data as if it were live.
 * - **Timestamps are sticky.** `since` and `updatedAt` are the gateway's and are kept exactly as
 *   they arrived, and a name once learned is never unlearned: a frame that carries no name leaves
 *   the last one standing rather than blanking the label.
 * - **A transition is announced once.** The same service flapping produces one notification, not
 *   one every few seconds — see {@link CapabilitiesOptions.dedupWindowMs}.
 *
 * ## Why the timer, the clock and the stream are parameters
 *
 * Everything interesting here is about *time*, and a test that waits thirty real seconds for any of
 * it is a test nobody runs. The application passes the browser's; a suite passes its own and steps
 * time by hand.
 */
import type { ApiResult } from "@kui/api";
import { SseEventNames } from "@kui/api";
import { createEffect, createRoot, createSignal, type Accessor } from "solid-js";

import type { SseConnection, SseHandle, SseSubscriber } from "../sse/stream.js";
import {
  capabilityKeyOf,
  decodeCapabilityFrame,
  describeCapability,
  isUnavailable,
  stateMessage,
  type CapabilityEntry,
  type CapabilityFrame,
  type CapabilityKey,
  type CapabilityState,
} from "./frames.js";
import { deriveFeatureState, type FeatureState } from "./featureState.js";

/** How often the fallback asks for the whole picture. */
export const POLL_INTERVAL_MS = 30_000;

/** Repeats of the same notification inside this window collapse into one. */
export const DEDUP_WINDOW_MS = 30_000;

/** Something the user is told out of band, when a capability crosses into or out of unavailable. */
export interface CapabilityNotice {
  readonly tone: "danger" | "success";
  readonly title: string;
  readonly message: string | undefined;
  /** Two notices with the same key inside the window collapse into one. */
  readonly dedupKey: string;
}

export interface CapabilitiesOptions {
  /**
   * Subscribes to the capability stream. A function, because the store opens a fresh one after the
   * old one has been closed for good.
   */
  readonly openStream: (subscriber: SseSubscriber<CapabilityFrame>) => SseHandle;
  /** `GET /api/v1/capabilities`, the fallback for when the stream cannot be held. */
  readonly poll: () => Promise<ApiResult<unknown>>;
  /** Where the transition notices go. */
  readonly notify: (notice: CapabilityNotice) => void;
  /** Runs a thunk after a delay, and answers with a handle that cancels it. */
  readonly schedule: (delayMs: number, action: () => void) => void;
  /** Milliseconds since the epoch. Only ever used for notification de-duplication. */
  readonly now?: () => number;
  readonly pollIntervalMs?: number;
  readonly dedupWindowMs?: number;
  /** Where an unreadable frame is reported. Defaults to the console. */
  readonly warn?: (message: string) => void;
}

export interface Capabilities {
  /** Everything the gateway has told us, by {@link capabilityKeyOf}. Never emptied by a failure. */
  readonly states: Accessor<ReadonlyMap<string, CapabilityEntry>>;
  /** What the stream is doing, for the connection indicator. */
  readonly connection: Accessor<SseConnection>;
  /**
   * Whether the picture may be out of date: the stream is not open, so what is on screen is as fresh
   * as the last poll and no fresher. It is *not* a claim that anything is broken.
   */
  readonly stale: Accessor<boolean>;
  /** One capability's state, or `undefined` when the gateway has not reported it. */
  stateOf(service: string, cluster?: string): CapabilityState | undefined;
  /** What the shell should render for one feature: the capability folded together with permission. */
  featureState(service: string, cluster: string | undefined, permitted: boolean): FeatureState;
  /** The display name the gateway last reported for a capability, when it reported one. */
  nameOf(service: string, cluster?: string): string | undefined;
  /** Opens the stream and starts keeping the picture up to date. Called once, by the shell. */
  start(): void;
  /** Closes the stream and stops the poller. For tests and for a shell that is shutting down. */
  stop(): void;
}

export function createCapabilities(options: CapabilitiesOptions): Capabilities {
  const pollIntervalMs = options.pollIntervalMs ?? POLL_INTERVAL_MS;
  const dedupWindowMs = options.dedupWindowMs ?? DEDUP_WINDOW_MS;
  const now = options.now ?? (() => Date.now());
  const warn =
    options.warn ??
    ((message: string) => {
      console.warn(message);
    });

  // Written from network callbacks and from timers, both of which can be reached while an owned
  // scope is current. This is one store with one writer path, which is what `ownedWrite` is for.
  const [states, setStates] = createSignal<ReadonlyMap<string, CapabilityEntry>>(new Map(), {
    ownedWrite: true,
  });
  const [connection, setConnection] = createSignal<SseConnection>(
    { phase: "connecting" },
    { ownedWrite: true },
  );

  /**
   * The display name last reported for each key, kept beside the entries rather than only inside
   * them: a name is identity and a state is health, they change on completely different occasions,
   * and a name is never unlearned.
   */
  const names = new Map<string, string>();

  /** When each dedup key was last announced. */
  let announced = new Map<string, number>();

  let handle: SseHandle | undefined;
  /** Disposes the computation following the current handle's connection. */
  let disposeWatch: (() => void) | undefined;
  let polling = false;
  let stopped = false;

  /**
   * Which polling episode is current.
   *
   * A scheduled callback cannot be unscheduled through the `schedule` function this store is given,
   * so every callback carries the number of the episode that scheduled it and does nothing when that
   * number is no longer current. Without it, a stream that flaps twice inside one poll interval
   * leaves the first episode's pending callback alive alongside the second, and from then on two
   * independent chains poll and re-open the stream on their own timers.
   */
  let episode = 0;

  const subscriber: SseSubscriber<CapabilityFrame> = {
    events: [SseEventNames.Capabilities],
    decode: (_event, data) => decodeCapabilityFrame(data),
    onEvent: (frame) => {
      if (frame.kind === "snapshot") applySnapshot(frame.entries);
      else applyDelta(frame.entry, frame.previous);
    },
    onError: (error) => {
      // A frame we cannot read is reported and skipped, and the picture is left exactly as it was.
      warn(`kui: ignoring an unreadable capability frame: ${JSON.stringify(error)}`);
    },
  };

  function releaseHandle(): void {
    const open = handle;
    handle = undefined;
    // The watcher is disposed *before* the close, so that the `closed by the client` the close
    // produces is not read back as "the server went away" and used to start the poller again.
    disposeWatch?.();
    disposeWatch = undefined;
    open?.close();
  }

  function connect(): void {
    // The previous stream is closed first. See the note at the top of this file.
    releaseHandle();
    if (stopped) return;

    const opened = options.openStream(subscriber);
    handle = opened;
    watchConnection(opened);
  }

  /**
   * Follows one handle's connection state.
   *
   * A detached root, because this store has no component around it: it is created once by the shell
   * and lives for the life of the page, and Solid needs an owner for a computation to be disposable
   * at all. Disposing this root is what detaches the store from a handle it has finished with —
   * under Laminar the same job needed a `ManualOwner`, and the version before that owned the
   * subscriptions from the window, which never dies, so an abandoned stream kept writing into this
   * store for the life of the tab.
   */
  function watchConnection(opened: SseHandle): void {
    disposeWatch = createRoot((dispose) => {
      createEffect(
        () => opened.connection(),
        (current) => {
          if (handle === opened) applyConnection(current);
        },
      );
      return dispose;
    });
  }

  function applyConnection(current: SseConnection): void {
    switch (current.phase) {
      case "open":
        // The stream is working again, so the poller stands down.
        polling = false;
        episode += 1;
        setConnection(current);
        return;
      case "closed":
        setConnection(current);
        beginPollingFallback();
        return;
      default: {
        const held = connection();
        if (held.phase !== "closed") setConnection(current);
      }
    }
  }

  /**
   * Starts asking for the whole picture on a timer, and keeps trying to get the stream back.
   *
   * Polling is strictly worse than the stream — it is a poll interval behind, and it is a request
   * per interval per open tab — so it is a fallback and never the normal path.
   */
  function beginPollingFallback(): void {
    if (polling || stopped) return;
    polling = true;
    episode += 1;
    tick(episode);
  }

  function tick(current: number): void {
    if (!polling || current !== episode) return;

    void options.poll().then((outcome) => {
      if (!polling || current !== episode) return;
      if (outcome.ok) {
        const frame = decodeCapabilityFrame(JSON.stringify(outcome.value));
        if (frame.ok && frame.value.kind === "snapshot") applySnapshot(frame.value.entries);
        else warn("kui: the capability poll answered with something that is not a snapshot");
      } else {
        // Nothing changes. See the note at the top of this file.
        warn(`kui: capability poll failed: ${JSON.stringify(outcome.error)}`);
      }
    });

    options.schedule(pollIntervalMs, () => {
      // Each tick is also another go at the stream: recovering onto it is what stops the polling.
      if (!polling || current !== episode) return;
      connect();
      tick(current);
    });
  }

  function applySnapshot(entries: readonly CapabilityEntry[]): void {
    const previous = states();
    const replacement = new Map<string, CapabilityEntry>();
    for (const entry of entries) replacement.set(capabilityKeyOf(entry.key), rememberName(entry));
    setStates(replacement);
    for (const [key, entry] of replacement) {
      report(entry.key, key, previous.get(key)?.state, entry.state);
    }
  }

  function applyDelta(entry: CapabilityEntry, previous: CapabilityState | undefined): void {
    const key = capabilityKeyOf(entry.key);
    const before = previous ?? states().get(key)?.state;
    const replacement = new Map(states());
    replacement.set(key, rememberName(entry));
    setStates(replacement);
    report(entry.key, key, before, entry.state);
  }

  /** Records the name an entry carried, and gives the entry the last known one when it carried none. */
  function rememberName(entry: CapabilityEntry): CapabilityEntry {
    const key = capabilityKeyOf(entry.key);
    if (entry.name !== undefined) {
      names.set(key, entry.name);
      return entry;
    }
    const known = names.get(key);
    return known === undefined ? entry : { ...entry, name: known };
  }

  /**
   * Announces a capability crossing into or out of unavailable.
   *
   * Only the crossing is announced. "It is still down" is not news and, on a service that flaps,
   * would be a notice every few seconds — which is why the dedup key names the capability rather
   * than the moment.
   *
   * The rule is "was not unavailable, now is" rather than strictly "was available": from the user's
   * point of view a degraded feature going down is the same event, and reporting one and not the
   * other would make the notices depend on an intermediate state they never saw.
   *
   * The two directions are deliberately not symmetric. A loss is announced only when something was
   * known before — the first frame after a page load is the picture, not a transition — while a
   * recovery is announced whenever the previous state was unavailable, because the user was told
   * about the loss and is owed the ending.
   */
  function report(
    key: CapabilityKey,
    keyString: string,
    before: CapabilityState | undefined,
    after: CapabilityState,
  ): void {
    const wasUnavailable = before !== undefined && isUnavailable(before);
    const nowUnavailable = isUnavailable(after);
    const label = names.get(keyString) ?? describeCapability(key);

    if (!wasUnavailable && nowUnavailable && before !== undefined) {
      raise({
        tone: "danger",
        title: `${label} is unavailable`,
        message: stateMessage(after),
        dedupKey: `capability-lost:${keyString}`,
      });
    } else if (wasUnavailable && !nowUnavailable) {
      raise({
        tone: "success",
        title: `${label} is back`,
        message: undefined,
        dedupKey: `capability-back:${keyString}`,
      });
    }
  }

  function raise(notice: CapabilityNotice): void {
    const at = now();
    // Forget keys older than the window, so the map cannot grow for the lifetime of the page.
    announced = new Map([...announced].filter(([, when]) => at - when < dedupWindowMs));
    if (announced.has(notice.dedupKey)) return;
    announced.set(notice.dedupKey, at);
    options.notify(notice);
  }

  return {
    states,
    connection,
    stale: () => connection().phase !== "open",

    stateOf(service: string, cluster?: string): CapabilityState | undefined {
      return states().get(capabilityKeyOf({ service, cluster }))?.state;
    },

    nameOf(service: string, cluster?: string): string | undefined {
      return names.get(capabilityKeyOf({ service, cluster }));
    },

    featureState(service: string, cluster: string | undefined, permitted: boolean): FeatureState {
      return deriveFeatureState(this.stateOf(service, cluster), permitted);
    },

    start(): void {
      stopped = false;
      connect();
    },

    stop(): void {
      stopped = true;
      polling = false;
      // Invalidates any callback already scheduled, so the store really does go quiet rather than
      // keep polling and re-opening the stream against a gateway the user may no longer be
      // authenticated to.
      episode += 1;
      releaseHandle();
      setConnection({ phase: "closed", reason: "closed by the client" });
    },
  };
}
