/**
 * The alert feed the bell and the card both read (M8, `SCREENS-V4.md` §3.8 and §3.9).
 *
 * ## Why there is one store and why it is here
 *
 * The bell is in the shell's chrome and the card is in `@kui/feature-alerts`. A feature may not
 * import the shell, nothing may import a feature statically, and the two must never be able to
 * disagree about how many alerts are open — a bell reading `2` beside a card reading `3` is worse
 * than either number alone, because the operator then has to decide which of KUI's own screens to
 * believe. So the feed is fetched once, held once, and read twice, and the kernel is the only
 * package both consumers can see. It is the capability store's argument exactly.
 *
 * ## How the two halves fit together, which is the service's design and not this file's
 *
 * `AlertChangeDto`'s scaladoc
 * (`services/alerts/contract/src/kui/alerts/contract/dto/AlertDtos.scala:313-321`) settles it: the
 * stream frame carries **the open count and no events**, so that one principal's unread count never
 * reaches another subscriber's socket and a dropped frame costs a fetch rather than a stale screen.
 * A subscriber that sees a count it does not hold re-reads the feed it is already authorized for.
 * So this store does exactly that, in that order: take the count off the frame *first* — it is the
 * number the bell and the danger pill both draw, and it is the same for every principal — and then
 * re-read for the rows.
 *
 * ## The rules, each of which is a defect this product has already paid for
 *
 * - **Every count is the server's own figure.** `openCount` and `unreadCount` are counted over the
 *   whole store and `items` is one page, so a fold over the rows this browser holds is a different
 *   number by construction. There is no such fold in this file. A document that carries no count
 *   answers `null` — "KUI does not know" — and never `0`.
 * - **A document this build cannot read is a refusal, not an empty feed.** Wave 5's producers wire
 *   decoded successfully into nothing and the card announced it. {@link Alerts.feed} goes to
 *   `failed` with the cause in it instead.
 * - **Nothing about a severity, a tone or a glyph is decided here.** The server sends both halves of
 *   each pair for that reason; see `./events.ts`.
 * - **The bell goes quiet because the server said so.** `markAllRead()` re-reads with the
 *   endpoint's own `markRead` query — `AlertsEndpoints.MarkReadParam` — and takes the
 *   `unreadCount` that comes back. Zeroing a local number and hoping is how a bell ends up
 *   disagreeing with the panel it opens.
 * - **An answer that arrives after the store moved on is dropped.** Reads cannot be recalled, and
 *   applying a stale one repaints a card the shell has torn down or overwrites a newer feed with an
 *   older one. Every read carries the episode that asked for it.
 * - **The stream is closed before another is opened, and `stop()` really stops.** The same rule, and
 *   the same shipped defect, as `../capabilities/store.ts`: an abandoned `EventSource` is
 *   unreachable through the store and retries for the life of the tab.
 *
 * ## Why there is no poller behind the stream
 *
 * The capability store has one because the navigation is drawn from it and a frozen navigation is
 * unusable. The feed is not that: when its stream drops, what is held is real and simply not
 * current, which is what `stale` says. A second fetch path would be a second decoder against the
 * same wire, which is the pair of half-contracts this file exists to avoid. The connection is
 * exposed so a screen can say so.
 */
import type { ApiResult } from "@kui/api";
import { decodeSection, userMessage } from "@kui/api";
import { createEffect, createRoot, createSignal, type Accessor } from "solid-js";

import { apiFailure, type Fetched } from "../fetched.js";
import type { SseConnection, SseHandle, SseSubscriber } from "../sse/stream.js";
import {
  decodeAlertChange,
  decodeAlertFeed,
  type AlertChange,
  type AlertEvent,
  type AlertFeed,
} from "./events.js";

/**
 * The event name the change stream publishes under.
 *
 * `AlertChangeDto.EventName` on the server. A literal rather than a member of `@kui/api`'s
 * `SseEventNames`, because that file is generated from the Scala vocabulary and compared byte for
 * byte by `./mill frontend.apiConstants --check`, so it will carry this name when the generator is
 * next run and not before. It is an option as well, so the shell can override it without this file
 * moving.
 */
export const ALERTS_EVENT_NAME = "alerts";

/**
 * The key the feed's section hangs off in the read's body.
 *
 * `AlertFeedResponse` is `events: Section[AlertFeedDto]`, and M8's exit criterion greps
 * `.events.status` (`docs/plan/ROADMAP.md:470-474`).
 */
export const ALERTS_SECTION_KEY = "events";

export interface AlertsOptions {
  /** Subscribes to the change stream. A function, because a new one is opened after a close. */
  readonly openStream: (subscriber: SseSubscriber<AlertChange>) => SseHandle;
  /**
   * `GET …/alerts/events`, with the endpoint's `markRead` query.
   *
   * A parameter rather than two functions so that "read the feed" and "read the feed and mark it
   * read" cannot end up spelling the address differently — it is one endpoint and one caller.
   */
  readonly load: (markRead: boolean) => Promise<ApiResult<unknown>>;
  /**
   * Which cluster this store is for, when the caller knows.
   *
   * The stream is opened per cluster, so every frame on it should be this one's — but the frame
   * names its cluster, and a frame for another one moving this bell's count would be a number from
   * somewhere the operator is not looking. Checked when it is offered.
   */
  readonly cluster?: (() => string | undefined) | undefined;
  readonly eventName?: string | undefined;
  readonly sectionKey?: string | undefined;
  /** Where an unreadable frame is reported. Defaults to the console. */
  readonly warn?: ((message: string) => void) | undefined;
}

export interface Alerts {
  /** The feed and an honest account of its state, for whatever is drawing it. */
  readonly feed: Accessor<Fetched<AlertFeed>>;
  /** The rows of the page the server sent, in its own order. Empty while loading and after a refusal. */
  readonly events: Accessor<readonly AlertEvent[]>;
  /** The server's cluster-wide open count, or `null` when nothing has said. Never a fold over rows. */
  readonly openCount: Accessor<number | null>;
  /** The server's per-principal unread count, or `null`. Never a fold over rows. */
  readonly unreadCount: Accessor<number | null>;
  /** Whether this principal has anything unread. `null` — nobody has said — is not "unread". */
  readonly unread: Accessor<boolean>;
  /** How far this principal has read, as the server recorded it. */
  readonly lastReadAt: Accessor<string | undefined>;
  /** What the stream is doing, for the connection indicator. */
  readonly connection: Accessor<SseConnection>;
  /** Re-reads the feed and asks the server to mark it read for this principal. */
  markAllRead(): void;
  /** Re-reads the feed without marking anything. */
  refresh(): void;
  /** Reads the feed and opens the stream. Called once, by the shell. */
  start(): void;
  /** Closes the stream. Idempotent. */
  stop(): void;
}

export function createAlerts(options: AlertsOptions): Alerts {
  const eventName = options.eventName ?? ALERTS_EVENT_NAME;
  const sectionKey = options.sectionKey ?? ALERTS_SECTION_KEY;
  const warn =
    options.warn ??
    ((message: string) => {
      console.warn(message);
    });

  // Written from network callbacks, which can be reached while an owned scope is current — the
  // same one-writer-many-readers shape the capability store has.
  const [feed, setFeed] = createSignal<Fetched<AlertFeed>>({ kind: "loading" }, { ownedWrite: true });
  const [connection, setConnection] = createSignal<SseConnection>(
    { phase: "connecting" },
    { ownedWrite: true },
  );
  /**
   * The count the newest frame carried, held beside the feed rather than folded into it.
   *
   * The frame arrives before the re-read it triggers, and the whole reason the service puts the
   * count on the frame is so that the bell and the pill move together at that moment rather than a
   * round trip later. It is dropped the instant a read lands, because the read's count is the same
   * number from the same source and one of them has to win.
   */
  const [streamed, setStreamed] = createSignal<number | null>(null, { ownedWrite: true });

  let handle: SseHandle | undefined;
  /** Disposes the computation following the current handle's connection. */
  let disposeWatch: (() => void) | undefined;
  let stopped = false;

  /**
   * Which life of this store the read in flight belongs to.
   *
   * A read cannot be recalled once it is out, and its answer arrives whenever it arrives — after a
   * `stop()`, after a second `start()`, or after a newer read has already landed. Applying it then
   * paints a feed nobody is waiting for over the one that is current, and on a stopped store it
   * repopulates a card the shell has torn down. Raised by `start()`, by `stop()` and by every read,
   * and the answer is dropped when it no longer matches. The capability store's episode, one file
   * over, is the same rule for the same reason.
   */
  let episode = 0;

  const subscriber: SseSubscriber<AlertChange> = {
    events: [eventName],
    decode: (_event, data) => decodeAlertChange(data),
    onEvent: (change) => {
      const mine = options.cluster?.();
      if (mine !== undefined && change.cluster !== mine) {
        // Not this store's cluster. Taking the count would put a number on this bell from a
        // cluster the operator is not looking at.
        return;
      }
      // The count first — see the note on `streamed` — and the rows after.
      setStreamed(change.openCount);
      read(false);
    },
    onError: (error) => {
      if (error.kind === "decode") {
        // A frame we cannot read is reported and skipped; what is on screen is still what the
        // server last said, which is more use than a blank card.
        warn(`kui: ignoring an unreadable alert frame: ${JSON.stringify(error)}`);
        return;
      }
      // `server` and `transport` are terminal. What is held is now as fresh as the last read and
      // no fresher, which is what `stale` means — it is not a claim that anything is broken.
      markStale(error.kind === "server" ? userMessage(error.error) : error.cause);
    },
  };

  function releaseHandle(): void {
    const open = handle;
    handle = undefined;
    // The watcher goes before the close, so that the `closed by the client` the close produces is
    // not read back as "the server went away" and shown to the user as an outage.
    disposeWatch?.();
    disposeWatch = undefined;
    open?.close();
  }

  /**
   * Opens a stream and follows *its* connection rather than keeping a second opinion here.
   *
   * The obvious alternative is to set the phase from the events this store already sees —
   * `connecting` when the stream is opened, `open` when a frame arrives — and it is wrong twice.
   * A healthy stream with nothing happening on it then reads as "connecting" for as long as the
   * cluster stays quiet, which on a well-run cluster is for ever; and `reconnecting`, the one phase
   * an operator actually needs to see, cannot be reported at all, because the retry happens inside
   * the transport and produces no frame. The transport already knows all three. This says what it
   * knows.
   *
   * A detached root, because the store has no component around it and Solid needs an owner for a
   * computation to be disposable — the capability store, one directory over, carries the same note
   * for the same reason.
   */
  function connect(): void {
    releaseHandle();
    if (stopped) return;
    const opened = options.openStream(subscriber);
    handle = opened;
    disposeWatch = createRoot((dispose) => {
      createEffect(
        () => opened.connection(),
        (current) => {
          if (handle === opened) setConnection(current);
        },
      );
      return dispose;
    });
  }

  /** What is held right now, whether it arrived fresh or is being shown under a staleness badge. */
  function current(): AlertFeed | undefined {
    const state = feed();
    return state.kind === "ready" || state.kind === "stale" ? state.value : undefined;
  }

  /** The one open-count derivation used by every consumer of this store. */
  function knownOpenCount(): number | null {
    const held = current();
    // A zero before the first evaluation is no measurement at all. The service has not looked yet.
    if (held?.evaluatedAt === undefined) return null;
    return streamed() ?? held.openCount;
  }

  function markStale(reason: string): void {
    const held = current();
    if (held === undefined) {
      setFeed({ kind: "failed", message: reason, code: "STREAM_CLOSED" });
      return;
    }
    setFeed({ kind: "stale", value: held, reason });
  }

  function read(markRead: boolean): void {
    episode += 1;
    const asked = episode;
    void options.load(markRead).then((answer) => {
      if (asked !== episode || stopped) return;
      applyRead(answer);
    });
  }

  function applyRead(result: ApiResult<unknown>): void {
    // A completed read supersedes the stream hint on every outcome. Keeping the hint beside a
    // refusal or failure would let the bell display a live count while the feed says it cannot be
    // read, which is two answers from one store.
    setStreamed(null);
    if (!result.ok) {
      setFeed(apiFailure(result.error));
      return;
    }
    const body = result.value;
    const envelope =
      typeof body === "object" && body !== null ? (body as Record<string, unknown>) : undefined;
    const section = decodeSection<unknown>(envelope?.[sectionKey]);
    switch (section.status) {
      case "forbidden":
        setFeed({ kind: "forbidden" });
        return;
      case "not_configured":
        setFeed({ kind: "not-configured" });
        return;
      case "unavailable":
      case "unreadable":
        setFeed({
          kind: "failed",
          message: section.reason.message ?? "The alerts service did not answer.",
          code: section.reason.code,
        });
        return;
      case "ok":
      case "stale": {
        const decoded = decodeAlertFeed(section.data);
        if (!decoded.ok) {
          // The refusal, spelled out. This is the sentence that would have caught wave 5's
          // producers wire on the day it shipped rather than two waves later.
          setFeed({
            kind: "failed",
            message: `KUI could not read the alert feed: ${decoded.cause}`,
            code: "UNREADABLE_FEED",
          });
          return;
        }
        if (section.status === "stale") {
          setFeed({
            kind: "stale",
            value: decoded.value,
            reason: section.reason.message ?? "This is the last answer KUI received.",
          });
          return;
        }
        setFeed({ kind: "ready", value: decoded.value });
        return;
      }
    }
  }

  return {
    feed,
    events: () => current()?.items ?? [],
    connection,
    lastReadAt: () => current()?.lastReadAt,

    openCount: knownOpenCount,

    unreadCount: () => current()?.unreadCount ?? null,

    // `null` is not "unread". Nobody has said how many are unread, and lighting the bell on that
    // would be a mark meaning "KUI does not know", which is not what a reader takes it for.
    unread: () => (current()?.unreadCount ?? 0) > 0,

    markAllRead(): void {
      read(true);
    },

    refresh(): void {
      read(false);
    },

    start(): void {
      stopped = false;
      read(false);
      connect();
    },

    stop(): void {
      stopped = true;
      episode += 1;
      setStreamed(null);
      releaseHandle();
      setConnection({ phase: "closed", reason: "closed by the client" });
    },
  };
}
