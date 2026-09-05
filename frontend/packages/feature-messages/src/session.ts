/**
 * One running browse, owned by the screen.
 *
 * ## Why the rows are a signal and not a stream the list folds
 *
 * Because a browse is stopped, restarted with different parameters, and left running while the user
 * reads. Holding the accumulated rows in one place makes "clear and start again" a single write,
 * and makes the list's input an ordinary array — the same input every other list in KUI takes.
 *
 * ## The cap, and why there is one
 *
 * A live browse on a busy topic delivers faster than a person reads, forever. Without a bound the
 * row list grows until the tab dies, which is a failure the user cannot diagnose and cannot undo.
 * So the newest {@link MAX_ROWS} are kept and older ones are dropped, which is what "follow live"
 * means anyway: the interesting end of a tail is the new end. A bounded browse — one with a limit —
 * never reaches the cap, because the service stops first.
 *
 * ## Cancellation is real
 *
 * `stop()` aborts the request, and the abort travels: the gateway's stream is cancelled, the
 * service's fiber is cancelled and its Kafka consumer is closed (ADR-035). The screen binds it to
 * unmount as well as to the Stop control, so navigating away cannot leave a consumer running — the
 * thing the whole abortable-fetch transport exists for.
 *
 * ## Pausing is not stopping
 *
 * On a tail the difference is the whole point. A busy topic redraws the list faster than a person
 * can read one row, so the moment somebody sees something interesting the row they were looking at
 * is gone. Stopping would answer that by closing the stream — and then the records produced while
 * they read are lost, because a tail has no way back to them. Pausing keeps the stream open and the
 * consumer reading, and queues what arrives until they are ready for it.
 *
 * ## Solid 2, and the one thing that bites here
 *
 * Updates are batched to a microtask, so a read taken straight after a write returns the previous
 * value until the flush. Two records arriving in the same tick therefore **cannot** both be
 * appended with `setRows((previous) => [...previous, one])`: Solid applies each updater to the last
 * *committed* value, so both compute from the same array and one record is lost. The arrays here
 * are held in plain local variables, which are the source of truth, and the signal is written from
 * them — so a burst of twenty records in one tick is twenty appends to an array and one signal
 * write. This is the same defect the toast region hit; it is a property of the framework version
 * rather than of either component.
 */

import { createSignal, onCleanup, type Accessor } from "solid-js";
import type { ApiError } from "@kui/api";
import type { KafkaRecord } from "@kui/kernel";
import { queryString, type BrowseQuery } from "./browse.js";
import { toRecord, type MessageDto } from "./wire.js";

/**
 * How many records one browse keeps on screen.
 *
 * Five hundred is a long way past what anybody scrolls and a long way short of what makes a tab
 * unresponsive. It bounds a live tail, which is otherwise unbounded by definition.
 */
export const MAX_ROWS = 500;

/** Where a stream is in its life. Mirrors the kernel's `SseConnection`, which is what supplies it. */
export type BrowseConnection =
  | { readonly phase: "idle" }
  | { readonly phase: "connecting" }
  | { readonly phase: "open" }
  | { readonly phase: "closed"; readonly reason: string };

/** Why a browse, or one event in it, did not work out. */
export type BrowseFailure =
  | { readonly kind: "server"; readonly error: ApiError }
  | { readonly kind: "transport"; readonly cause: string }
  /** One event's payload was not what its decoder expected. The stream keeps running. */
  | { readonly kind: "decode"; readonly event: string; readonly cause: string };

/** How much Kafka was read to find what is on screen. */
export interface Consumed {
  readonly messages: number;
  readonly bytes: number;
  readonly elapsedMs: number;
}

/**
 * What the status line reports.
 *
 * `scanned` (the `consumed` figure) is separate from the record count on purpose, and it is the
 * number that makes a filtered browse interpretable: a scan over a large topic routinely reads a
 * million records and matches none of them, and without it the screen is identical to a topic that
 * is empty.
 */
export interface BrowseProgress {
  /** How many records the *stream* delivered. Counts on regardless of the pause; see below. */
  readonly delivered: number;
  readonly consumed?: Consumed | undefined;
  /** What the service says it is doing: `seeking`, `reading`, `filtering`. */
  readonly phase?: string | undefined;
  readonly connection: BrowseConnection;
  readonly failure?: BrowseFailure | undefined;
}

const IDLE: BrowseProgress = { delivered: 0, connection: { phase: "idle" } };

/** What a browse's stream delivers, once the transport has named the event. */
export type BrowseEvent =
  | { readonly kind: "record"; readonly record: KafkaRecord }
  | { readonly kind: "phase"; readonly name: string }
  | { readonly kind: "consumed"; readonly consumed: Consumed };

/** The handle a transport hands back. The kernel's `SseHandle` satisfies it. */
export interface BrowseHandle {
  readonly close: () => void;
  /** The `id:` on the terminal `done` event: the signed continuation, when the server sent one. */
  readonly endMarker: () => string | undefined;
}

/** How the session reaches the network. Supplied by the shell; replaced wholesale by a test. */
export interface BrowseTransport {
  open(
    url: string,
    handlers: {
      readonly onEvent: (event: BrowseEvent) => void;
      readonly onFailure: (failure: BrowseFailure) => void;
      readonly onConnection: (connection: BrowseConnection) => void;
    },
  ): BrowseHandle;
}

export interface BrowseSessionOptions {
  /** `/api/v1/clusters/{id}/topics/{topic}/messages/stream`, already escaped. */
  readonly streamUrl: string;
  readonly transport: BrowseTransport;
}

export interface BrowseSession {
  /** The records so far, **newest first**. */
  readonly rows: Accessor<readonly KafkaRecord[]>;
  readonly progress: Accessor<BrowseProgress>;
  readonly running: Accessor<boolean>;
  readonly paused: Accessor<boolean>;
  /** How many records are waiting behind a pause. Zero unless paused. */
  readonly held: Accessor<number>;
  readonly canLoadMore: Accessor<boolean>;
  /** Which records arrived in the last tick, so the list can wash them once. */
  readonly arrived: Accessor<ReadonlySet<string>>;
  /** Starts a browse, discarding whatever the previous one delivered. */
  readonly start: (query: BrowseQuery) => void;
  /** Reads the next page and **appends** it. Does nothing without a cursor. */
  readonly loadMore: () => void;
  readonly setPaused: (on: boolean) => void;
  readonly stop: () => void;
}

export function createBrowseSession(options: BrowseSessionOptions): BrowseSession {
  /* Plain arrays are the source of truth; the signals mirror them. See the Solid 2 note above —
   * this is not a style choice, it is the only shape that survives two records in one tick. */
  let rowList: KafkaRecord[] = [];
  let heldList: KafkaRecord[] = [];
  let pausedNow = false;
  let handle: BrowseHandle | undefined;
  /* What the last browse was, so that "load more" reads the same range in the same direction with
   * the same decoding. Continuing with the parameters the *controls* currently hold would silently
   * change what the next page is a continuation of, whenever somebody edited a control without
   * pressing Read. */
  let lastQuery: BrowseQuery | undefined;
  let cursorNow: string | undefined;

  const [rows, setRows] = createSignal<readonly KafkaRecord[]>([], { ownedWrite: true });
  const [progress, setProgress] = createSignal<BrowseProgress>(IDLE, { ownedWrite: true });
  const [running, setRunning] = createSignal(false, { ownedWrite: true });
  const [paused, setPausedSignal] = createSignal(false, { ownedWrite: true });
  const [held, setHeld] = createSignal(0, { ownedWrite: true });
  const [cursor, setCursor] = createSignal<string | undefined>(undefined, { ownedWrite: true });
  const [arrived, setArrived] = createSignal<ReadonlySet<string>>(new Set(), { ownedWrite: true });

  /* True only when a browse has finished *and* the server chose to send a cursor with it. The
   * server omits one whenever asking again would be pointless, so this is the server's own answer
   * to "is there more" rather than the browser guessing from a full page — which is the guess that
   * puts a "Load more" button under the last page of every topic. */
  const canLoadMore = (): boolean => cursor() !== undefined && !running();

  function publishRows(): void {
    setRows(rowList.slice());
    setHeld(heldList.length);
  }

  function stop(): void {
    handle?.close();
    handle = undefined;
    setRunning(false);
    /* Whatever was held is shown rather than discarded. Those records were delivered; throwing
     * them away because the user pressed Stop would lose evidence that arrived before the press,
     * and on a tail there is no second chance to read them. */
    release();
  }

  function release(): void {
    pausedNow = false;
    setPausedSignal(false);
    if (heldList.length > 0) {
      rowList = [...heldList, ...rowList].slice(0, MAX_ROWS);
      heldList = [];
    }
    publishRows();
  }

  function run(query: BrowseQuery, keepRows: boolean): void {
    stop();
    if (!keepRows) rowList = [];
    lastQuery = { ...query, cursor: undefined };
    /* The cursor from the *previous* page is spent the moment this one starts. Leaving it in place
     * would leave "Load more" offering the page that is already being read. */
    cursorNow = undefined;
    setCursor(undefined);
    setArrived(new Set<string>());
    /* The delivered count restarts with each request, because it is what the status line reports
     * about the request in flight; the list's own length is what says how much is on screen. */
    setProgress({ delivered: 0, connection: { phase: "connecting" } });
    publishRows();

    const url = buildUrl(options.streamUrl, query);

    /* Declared *before* the call and assigned after it, and both of the next two lines exist
     * because of a defect this file shipped for about an hour.
     *
     * `open` may report a terminal state **synchronously, from inside the call** — a transport that
     * refuses the request before it sends it, or a fake one in a story that plays a whole scripted
     * stream at once. The first version read the handle out of the `const` that `open` was being
     * assigned to, so that path threw `Cannot access 'opened' before initialization` and the whole
     * screen failed to render. Nothing in the suite caught it, because every test in it drives the
     * stream after `open` has returned; the story that plays a finished browse found it in the
     * first second of looking at it.
     *
     * So the close is *recorded* if it arrives early and applied once the handle exists. */
    let opened: BrowseHandle | undefined;
    let closedBeforeOpenReturned = false;

    const finish = (which: BrowseHandle): void => {
      cursorNow = which.endMarker();
      setCursor(cursorNow);
      if (handle === which) {
        handle = undefined;
        setRunning(false);
      }
    };

    opened = options.transport.open(url, {
      onEvent: (event) => {
        switch (event.kind) {
          case "record": {
            /* The count moves even while paused, because it counts what the *stream* delivered. A
             * paused screen that also stopped counting would be indistinguishable from a stream
             * that had stalled, which is the one thing a pause must not be mistaken for. */
            if (pausedNow) heldList = [event.record, ...heldList].slice(0, MAX_ROWS);
            else rowList = [event.record, ...rowList].slice(0, MAX_ROWS);
            setProgress((current) => ({ ...current, delivered: current.delivered + 1, phase: undefined }));
            setArrived((current) => new Set(current).add(recordId(event.record)));
            publishRows();
            return;
          }
          case "phase":
            setProgress((current) => ({ ...current, phase: event.name }));
            return;
          case "consumed":
            setProgress((current) => ({ ...current, consumed: event.consumed }));
            return;
        }
      },
      /* A failure is held beside the rows rather than replacing them: the records that did arrive
       * are still what the user asked for, and throwing them away to show an error would lose the
       * evidence. */
      onFailure: (failure) => setProgress((current) => ({ ...current, failure })),
      onConnection: (connection) => {
        setProgress((current) => ({ ...current, connection }));
        if (connection.phase !== "closed") return;
        /* A closed stream releases the handle. `running` is "is there a handle", and the control
         * reads Stop while it is true — so without this a browse that ended by itself, which is
         * what every bounded browse does the moment it has read its limit, left the button saying
         * Stop for ever, beside a status line reading "finished", with no way back to Read short of
         * reloading the page. The handle is already closed by then; dropping the reference is all
         * that is left to do. */
        if (opened === undefined) {
          closedBeforeOpenReturned = true;
          return;
        }
        finish(opened);
      },
    });

    handle = opened;
    setRunning(true);
    if (closedBeforeOpenReturned) finish(opened);
    /* A new browse is a new question, so it starts unpaused and holding nothing. Carrying a pause
     * across a Read would leave the user pressing a button that appears to do nothing at all. */
    pausedNow = false;
    setPausedSignal(false);
    heldList = [];
    publishRows();
  }

  onCleanup(() => stop());

  return {
    rows,
    progress,
    running,
    paused,
    held,
    canLoadMore,
    arrived,
    start: (query) => run(query, false),
    loadMore: () => {
      /* With no cursor it does nothing rather than starting a fresh browse. A "load more" that
       * quietly re-read the first page would look like a button that scrolled the user back to
       * where they began. */
      if (lastQuery === undefined || cursorNow === undefined) return;
      /* Appending is the whole difference from `start`, and it is safe here in a way it would not
       * be for a changed query: the cursor names the exact continuation of the range already on
       * screen, in the same direction, so the rows join onto the ones below them rather than being
       * a second range mixed into the first. */
      run({ ...lastQuery, cursor: cursorNow }, true);
    },
    setPaused: (on) => {
      if (on) {
        pausedNow = true;
        setPausedSignal(true);
        return;
      }
      /* Releasing prepends what was held, newest first, which puts the list back exactly where it
       * would have been had the pause never happened — a pause changes when rows appear, never
       * which ones or in what order. */
      release();
    },
    stop,
  };
}

/** Partition and offset. Offsets restart at zero in every partition; see `recordKey` in the kernel. */
function recordId(record: KafkaRecord): string {
  return `${String(record.partition)}:${record.offset}`;
}

function buildUrl(base: string, query: BrowseQuery): string {
  const search = queryString(query);
  return search === "" ? base : `${base}?${search}`;
}

/**
 * Turns the message stream's named events into {@link BrowseEvent}s.
 *
 * Exported and separate from the transport because it is the part worth testing: a `message` whose
 * payload is not a record, or a `consumed` whose figures are missing, must not end a stream that is
 * otherwise delivering good records. Each returns a decode failure that the transport reports and
 * keeps going, which is the same rule ADR-035 gives the server.
 */
export function decodeBrowseEvent(
  event: string,
  data: string,
): { ok: true; value: BrowseEvent } | { ok: false; cause: string } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch {
    return { ok: false, cause: "not JSON" };
  }
  if (parsed === null || typeof parsed !== "object") return { ok: false, cause: "not an object" };
  const body = parsed as Record<string, unknown>;

  switch (event) {
    case "message":
      /* No structural validation beyond "it is an object". The DTO has eleven fields and this
       * would be a second, weaker copy of the server's decoder — and a record that is missing one
       * of them still tells the operator more than a stream that stopped. The mapping in `wire.ts`
       * is total: every field it reads has a defined reading for a missing value. */
      return { ok: true, value: { kind: "record", record: toRecord(body as unknown as MessageDto) } };
    case "phase": {
      const name = body["phase"] ?? body["name"];
      return typeof name === "string"
        ? { ok: true, value: { kind: "phase", name } }
        : { ok: false, cause: "no phase name" };
    }
    case "consumed":
      return {
        ok: true,
        value: {
          kind: "consumed",
          consumed: {
            messages: numberOr(body["messages"], 0),
            bytes: numberOr(body["bytes"], 0),
            elapsedMs: numberOr(body["elapsedMs"], 0),
          },
        },
      };
    default:
      return { ok: false, cause: `unknown event ${event}` };
  }
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}
