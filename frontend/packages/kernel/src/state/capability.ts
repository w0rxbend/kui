/**
 * The browser's picture of what every service can currently do (ADR-032).
 *
 * ## Why the frames are decoded by hand
 *
 * Everything else the browser reads is typed from `docs/api/openapi.browser.json`, and nothing may
 * hand-write a mirror of a server shape. A capability frame is the one documented exception, and the
 * reason is a measurable gap rather than a preference.
 *
 * `kui.contracts.capability.CapabilityState` has a hand-written Circe codec that writes a `status`
 * discriminator — `"available"`, `"degraded"`, `"unavailable"`, `"not_configured"` — while its
 * derived Tapir schema is an untagged `oneOf` that mentions no such field. So the generated
 * TypeScript describes four shapes, none carrying `status`, two of which (`Available` and
 * `NotConfigured`) are the same empty object. Switching on the generated union is therefore
 * impossible, and "available" and "not configured" are the two states ADR-032 renders most
 * differently: one is a working feature and the other is a feature that must vanish from the
 * navigation. Recorded as B-006 in `BLOCKERS.md` with the proposed server-side fix.
 *
 * Until that is fixed the decoder below reads `status` — the string the wire actually carries —
 * from the generated `CapabilityStatuses` constants, which are emitted from the same Scala enum the
 * codec writes. The vocabulary is still generated; only the *shape* is read defensively. That is the
 * same arrangement `@kui/api`'s `decodeEnvelope` uses, and for the same reason: this is a payload the
 * generated types cannot vouch for, so every field is checked and anything unrecognisable becomes a
 * value rather than a thrown error.
 *
 * ## What the store guarantees
 *
 * - **A frame it cannot read is skipped and the picture is left exactly as it was.** Wiping the
 *   navigation because of one bad frame would be the worst possible failure mode: every feature
 *   would go from "working" to "unknown" because of a typo in one delta.
 * - **A lost stream never marks anything unavailable.** Nothing has been observed to break; only the
 *   updates have stopped. The last known states stand, the connection state says "closed", and the
 *   shell renders a banner saying the picture may be out of date. Taking a working product off the
 *   air because one connection failed is a far worse outcome than showing slightly old information.
 * - **A name is never unlearned.** A display name is identity and a state is health: they change on
 *   completely different occasions, and a frame that carries no name must leave the last one
 *   standing rather than blank the label.
 */
import { createSignal, type Accessor } from "solid-js";
import { CapabilityStatuses, ReasonCodes, type CapabilityStatus } from "@kui/api";

/** How KUI is pacing itself against a service that still answers, but not well. */
export type DegradedReason = {
  readonly code: string;
  readonly message: string;
  readonly suggestedPollIntervalMs?: number | undefined;
  readonly p95Ms?: number | undefined;
};

/** One capability's state, as the wire carries it. */
export type CapabilityStateValue =
  | { readonly status: "available" }
  | { readonly status: "degraded"; readonly reason: DegradedReason }
  | {
      readonly status: "unavailable";
      readonly reason: string;
      readonly message: string;
      readonly since?: string | undefined;
    }
  | { readonly status: "not_configured" };

/** What a capability is about: a service, optionally scoped to one cluster. */
export type CapabilityKey = {
  readonly service: string;
  readonly cluster?: string | undefined;
};

export type CapabilityEntry = {
  readonly key: CapabilityKey;
  readonly state: CapabilityStateValue;
  /** What a person calls the thing this entry is about — a cluster's display name. */
  readonly name?: string | undefined;
};

/**
 * A key flattened to a string, so it can index a plain record.
 *
 * `-` stands for "no cluster", which cannot collide with a cluster id: the contract restricts those
 * to `^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$`, which cannot be a single hyphen.
 */
export function capabilityKeyOf(key: CapabilityKey): string {
  return `${key.service}/${key.cluster ?? "-"}`;
}

/** What the store's connection to the gateway is doing, for the staleness banner. */
export type StreamState =
  | { readonly kind: "connecting" }
  | { readonly kind: "open" }
  | { readonly kind: "closed"; readonly reason: string };

const KNOWN_STATUSES: readonly string[] = Object.values(CapabilityStatuses);

/**
 * Reads one capability state, or says it could not.
 *
 * Total: every branch returns, and a status this build has never heard of is `undefined` rather than
 * a guess. `deriveFeatureState` turns `undefined` into "we have not been told", which is honest,
 * where guessing "available" would claim health nobody reported.
 */
export function decodeCapabilityState(raw: unknown): CapabilityStateValue | undefined {
  if (typeof raw !== "object" || raw === null) return undefined;
  const value = raw as Record<string, unknown>;
  const status = value["status"];
  if (typeof status !== "string" || !KNOWN_STATUSES.includes(status)) return undefined;

  switch (status as CapabilityStatus) {
    case "available":
      return { status: "available" };
    case "not_configured":
      return { status: "not_configured" };
    case "degraded":
      return { status: "degraded", reason: decodeDegradedReason(value["reason"]) };
    case "unavailable":
      return {
        status: "unavailable",
        // A missing reason is a degraded frame, not an undecodable one: the user still has to be
        // told the feature is down, and `UNKNOWN` renders as "KUI could not refresh this".
        reason: typeof value["reason"] === "string" ? value["reason"] : ReasonCodes.Unknown,
        message: typeof value["message"] === "string" ? value["message"] : "",
        ...(typeof value["since"] === "string" ? { since: value["since"] } : {}),
      };
  }
}

function decodeDegradedReason(raw: unknown): DegradedReason {
  if (typeof raw !== "object" || raw === null) {
    return { code: ReasonCodes.Unknown, message: "" };
  }
  const value = raw as Record<string, unknown>;
  return {
    code: typeof value["code"] === "string" ? value["code"] : ReasonCodes.Unknown,
    message: typeof value["message"] === "string" ? value["message"] : "",
    ...(typeof value["suggestedPollIntervalMs"] === "number"
      ? { suggestedPollIntervalMs: value["suggestedPollIntervalMs"] }
      : {}),
    ...(typeof value["p95Ms"] === "number" ? { p95Ms: value["p95Ms"] } : {}),
  };
}

/** One entry, or `undefined` when it is not one. */
export function decodeCapabilityEntry(raw: unknown): CapabilityEntry | undefined {
  if (typeof raw !== "object" || raw === null) return undefined;
  const value = raw as Record<string, unknown>;

  const key = value["key"];
  if (typeof key !== "object" || key === null) return undefined;
  const keyFields = key as Record<string, unknown>;
  const service = keyFields["service"];
  if (typeof service !== "string" || service.length === 0) return undefined;

  const state = decodeCapabilityState(value["state"]);
  if (state === undefined) return undefined;

  return {
    key: {
      service,
      ...(typeof keyFields["cluster"] === "string" ? { cluster: keyFields["cluster"] } : {}),
    },
    state,
    ...(typeof value["name"] === "string" && value["name"].length > 0
      ? { name: value["name"] }
      : {}),
  };
}

/**
 * One frame of the capability stream: the whole picture, or a change to it.
 *
 * The two shapes are told apart by which field they carry — a snapshot has `entries`, a delta has
 * `entry` — rather than by a discriminator, because the wire format is the contract as it stands and
 * adding a discriminator would be a server change made for the client's convenience.
 */
export type CapabilityFrame =
  | { readonly kind: "snapshot"; readonly entries: readonly CapabilityEntry[] }
  | { readonly kind: "delta"; readonly entry: CapabilityEntry };

/** Reads one frame's JSON text. Never throws: an unreadable frame is `undefined`. */
export function decodeCapabilityFrame(data: string): CapabilityFrame | undefined {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch {
    return undefined;
  }
  if (typeof parsed !== "object" || parsed === null) return undefined;
  const value = parsed as Record<string, unknown>;

  if (Array.isArray(value["entries"])) {
    // Entries that do not decode are dropped and the rest of the snapshot is kept. A snapshot is the
    // whole picture, and discarding all of it because one service reported something unreadable
    // would blank the navigation over one bad entry.
    const entries = value["entries"].flatMap((entry: unknown) => {
      const decoded = decodeCapabilityEntry(entry);
      return decoded === undefined ? [] : [decoded];
    });
    return { kind: "snapshot", entries };
  }

  const entry = decodeCapabilityEntry(value["entry"]);
  return entry === undefined ? undefined : { kind: "delta", entry };
}

/** What the store needs from the outside world. */
export type CapabilityStoreOptions = {
  /**
   * Subscribes to the capability stream and returns the unsubscribe.
   *
   * A function rather than a URL so that the store owns none of the transport: the shell supplies
   * something built on `EventSource`, and a test supplies one it drives by hand. It is called again
   * after a stream is closed for good, which is why it is a factory and not a handle.
   */
  readonly openStream: (handlers: CapabilityStreamHandlers) => () => void;
  /**
   * `GET /api/v1/capabilities`, the fallback for when the stream cannot be established.
   *
   * Answers with the decoded entries, or `undefined` when the request failed. A failed poll changes
   * nothing: both the stream and the poller being down means the picture is stale, which the
   * connection state already says. It does not mean every feature is broken.
   */
  readonly poll: () => Promise<readonly CapabilityEntry[] | undefined>;
  /** Runs a thunk after a delay. The browser's `setTimeout`, or a test's queue. */
  readonly schedule: (delayMs: number, run: () => void) => void;
  /** How often the fallback asks for the whole picture. */
  readonly pollIntervalMs?: number | undefined;
};

export type CapabilityStreamHandlers = {
  /** One `capabilities` frame's data, undecoded. */
  readonly onFrame: (data: string) => void;
  readonly onOpen: () => void;
  readonly onClosed: (reason: string) => void;
};

export type CapabilityStore = {
  /** Every capability the gateway has told us about, keyed by {@link capabilityKeyOf}. */
  readonly states: Accessor<ReadonlyMap<string, CapabilityStateValue>>;
  /** What each capability is called, for the screens that show a person a name. */
  readonly names: Accessor<ReadonlyMap<string, string>>;
  readonly connection: Accessor<StreamState>;
  /** One capability's state, or `undefined` when the gateway has not reported it. */
  readonly stateOf: (service: string, cluster?: string | undefined) => CapabilityStateValue | undefined;
  readonly start: () => void;
  readonly stop: () => void;
};

/** Polling is strictly worse than the stream, so it is a fallback and never the normal path. */
export const DefaultPollIntervalMs = 30_000;

export function createCapabilityStore(options: CapabilityStoreOptions): CapabilityStore {
  const [states, setStates] = createSignal<ReadonlyMap<string, CapabilityStateValue>>(new Map());
  const [names, setNames] = createSignal<ReadonlyMap<string, string>>(new Map());
  const [connection, setConnection] = createSignal<StreamState>({ kind: "connecting" });

  const pollIntervalMs = options.pollIntervalMs ?? DefaultPollIntervalMs;

  let unsubscribe: (() => void) | undefined;
  let polling = false;
  let stopped = false;
  /**
   * Which polling episode is current.
   *
   * A scheduled callback cannot be unscheduled through the `schedule` function the store is given,
   * so instead every callback carries the number of the episode that scheduled it and does nothing
   * when that number is no longer current. Without it, a stream that flaps twice inside one poll
   * interval leaves the first episode's pending callback alive alongside the second, and from then
   * on two independent chains poll and re-open the stream on their own timers.
   */
  let episode = 0;

  const applyEntries = (entries: readonly CapabilityEntry[], replace: boolean): void => {
    const nextStates = new Map(replace ? [] : states());
    for (const entry of entries) nextStates.set(capabilityKeyOf(entry.key), entry.state);
    setStates(nextStates);

    const named = entries.filter((entry) => entry.name !== undefined);
    if (named.length > 0) {
      const nextNames = new Map(names());
      for (const entry of named) nextNames.set(capabilityKeyOf(entry.key), entry.name!);
      setNames(nextNames);
    }
  };

  const handlers: CapabilityStreamHandlers = {
    onFrame: (data) => {
      const frame = decodeCapabilityFrame(data);
      if (frame === undefined) {
        // Logged and skipped. See the module comment: one unreadable frame must not blank the
        // navigation, and it is not evidence about any service's health.
        console.warn("kui: ignoring an unreadable capability frame");
        return;
      }
      if (frame.kind === "snapshot") applyEntries(frame.entries, true);
      else applyEntries([frame.entry], false);
    },
    onOpen: () => {
      // The stream is working again, so the poller stands down.
      polling = false;
      setConnection({ kind: "open" });
    },
    onClosed: (reason) => {
      if (stopped) return;
      setConnection({ kind: "closed", reason });
      beginPolling();
    },
  };

  const connect = (): void => {
    // The previous stream is closed first. Leaving it open left a subscriber retrying on its own for
    // the life of the tab, delivering every delta into this store a second time.
    unsubscribe?.();
    unsubscribe = options.openStream(handlers);
  };

  const beginPolling = (): void => {
    if (polling || stopped) return;
    polling = true;
    episode += 1;
    tick(episode);
  };

  const tick = (thisEpisode: number): void => {
    if (!polling || thisEpisode !== episode) return;

    void options.poll().then((entries) => {
      if (entries !== undefined && polling && thisEpisode === episode) applyEntries(entries, true);
    });

    options.schedule(pollIntervalMs, () => {
      if (!polling || thisEpisode !== episode) return;
      // Each tick is also another go at the stream: recovering onto it is what stops the polling.
      connect();
      tick(thisEpisode);
    });
  };

  return {
    states,
    names,
    connection,
    stateOf: (service, cluster) => states().get(capabilityKeyOf({ service, cluster })),
    start: () => {
      stopped = false;
      connect();
    },
    stop: () => {
      stopped = true;
      polling = false;
      // Invalidates any callback already scheduled, so the store really does go quiet rather than
      // keep polling against a gateway the user may no longer be authenticated to.
      episode += 1;
      unsubscribe?.();
      unsubscribe = undefined;
      setConnection({ kind: "closed", reason: "closed by the client" });
    },
  };
}
