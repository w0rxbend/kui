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
import { decodeMessageRecord } from "./wire.js";

/**
 * How many records one browse keeps on screen.
 *
 * Five hundred is a long way past what anybody scrolls and a long way short of what makes a tab
 * unresponsive. It bounds a live tail, which is otherwise unbounded by definition.
 */
export const MAX_ROWS = 500;

/**
 * Total decoded payload text a session may retain. Row count alone is not a memory bound when a
 * live topic contains large records; values beyond this budget keep metadata but drop their text.
 */
export const MAX_RETAINED_PAYLOAD_BYTES = 8 * 1024 * 1024;

/**
 * Cursor pages kept for zero-network Previous navigation.
 *
 * The record cap prevents an infinite browse from retaining every payload it has ever decoded;
 * the page cap also bounds the overhead of many tiny pages. Kafka has no stable total page count,
 * so evicting the oldest cached page is more honest than pretending the browser can retain an
 * unbounded snapshot of a moving log.
 */
export const MAX_CACHED_PAGES = 10;

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
  /**
   * How many records the *service* read to produce what arrived — not how many matched.
   *
   * The field is `records` on the wire (`ConsumedDto` in
   * `services/message/contract/.../StreamEventDtos.scala`). It was read here as `messages`, which
   * the server has never sent, so the fallback applied and this figure was `0` for every browse
   * that has ever run. That is the exact failure the type's own documentation below warns about:
   * with the scanned count reading zero, a filtered scan over a million records is indistinguishable
   * from an empty topic.
   */
  readonly records: number;
  readonly bytes: number;
  readonly elapsedMs: number;
  /** Records the filter itself could not evaluate. Reported rather than silently skipped. */
  readonly filterErrors?: number | undefined;
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
  /** The server's reason for the terminal `done` frame, when the stream ended normally. */
  readonly endReason?: BrowseEndReason | undefined;
  readonly failure?: BrowseFailure | undefined;
}

/** The four terminal reasons in ADR-035's shared `done` event. */
export type BrowseEndReason = "limit" | "exhausted" | "budget" | "cancelled";

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
  /** Optional for source compatibility with transports that predate terminal-reason reporting. */
  readonly endReason?: (() => BrowseEndReason | undefined) | undefined;
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
  /** The records so far, in the offset direction the backend delivered them. */
  readonly rows: Accessor<readonly KafkaRecord[]>;
  /** The cached page currently selected by the offset paginator. */
  readonly pageRows: Accessor<readonly KafkaRecord[]>;
  /** One-based. Pages are cached client-side; Kafka does not provide a stable total page count. */
  readonly pageNumber: Accessor<number>;
  readonly progress: Accessor<BrowseProgress>;
  readonly running: Accessor<boolean>;
  readonly paused: Accessor<boolean>;
  /** How many records are waiting behind a pause. Zero unless paused. */
  readonly held: Accessor<number>;
  readonly canLoadMore: Accessor<boolean>;
  readonly canPreviousPage: Accessor<boolean>;
  readonly canNextPage: Accessor<boolean>;
  /** Which records arrived in the last tick, so the list can wash them once. */
  readonly arrived: Accessor<ReadonlySet<string>>;
  /** Starts a browse, discarding whatever the previous one delivered. */
  readonly start: (query: BrowseQuery) => void;
  /** Reads the next page and **appends** it. Does nothing without a cursor. */
  readonly loadMore: () => void;
  /** Selects a cached successor, or reads it through the terminal cursor. */
  readonly nextPage: () => void;
  /** Selects the cached predecessor without re-reading a moving Kafka log. */
  readonly previousPage: () => void;
  readonly setPaused: (on: boolean) => void;
  readonly stop: () => void;
}

export function createBrowseSession(options: BrowseSessionOptions): BrowseSession {
  /* Plain arrays are the source of truth; the signals mirror them. See the Solid 2 note above —
   * this is not a style choice, it is the only shape that survives two records in one tick. */
  let rowList: KafkaRecord[] = [];
  let pageList: KafkaRecord[][] = [];
  let heldList: KafkaRecord[] = [];
  let pausedNow = false;
  let liveNow = false;
  let activePageNow = 0;
  let firstPageNumberNow = 1;
  let handle: BrowseHandle | undefined;
  /* What the last browse was, so that "load more" reads the same range in the same direction with
   * the same decoding. Continuing with the parameters the *controls* currently hold would silently
   * change what the next page is a continuation of, whenever somebody edited a control without
   * pressing Read. */
  let lastQuery: BrowseQuery | undefined;
  let cursorNow: string | undefined;
  let arrivedResetQueued = false;
  /* Every transport callback belongs to the run that registered it. Closing an HTTP stream is an
   * asynchronous cancellation, so callbacks already queued by an older run can arrive after a new
   * one has started. The generation makes those callbacks inert before they touch shared state. */
  let generation = 0;
  const retainedByteSizes = new WeakMap<KafkaRecord, number>();
  const utf8 = new TextEncoder();

  function retainedBytes(record: KafkaRecord): number {
    const cached = retainedByteSizes.get(record);
    if (cached !== undefined) return cached;
    const value = record.value;
    const text =
      value.kind === "json" || value.kind === "text"
        ? value.text
        : value.kind === "large"
          ? value.text
          : undefined;
    const bytes = text === undefined ? 0 : utf8.encode(text).length;
    retainedByteSizes.set(record, bytes);
    return bytes;
  }

  function withinPayloadBudget(record: KafkaRecord): KafkaRecord {
    const incoming = retainedBytes(record);
    if (incoming === 0) return record;
    // A record is normally referenced by both `rowList` and its cached page. Count the object once,
    // but include every owner: while a successor page is in flight, `rowList` can evict records
    // that its predecessor page still retains until terminal cursor processing trims the cache.
    const retainedRecords = new Set<KafkaRecord>([...rowList, ...heldList, ...pageList.flat()]);
    const retained = [...retainedRecords].reduce(
      (total, existing) => total + retainedBytes(existing),
      0,
    );
    if (retained + incoming <= MAX_RETAINED_PAYLOAD_BYTES) return record;

    const value = record.value;
    const sourceKind =
      value.kind === "json" ? "json" : value.kind === "large" ? (value.sourceKind ?? "text") : "text";
    const bytes = value.kind === "large" ? Math.max(value.bytes, incoming) : incoming;
    return { ...record, value: { kind: "large", bytes, sourceKind } };
  }

  const [rows, setRows] = createSignal<readonly KafkaRecord[]>([], { ownedWrite: true });
  const [pages, setPages] = createSignal<readonly (readonly KafkaRecord[])[]>([], { ownedWrite: true });
  const [pageIndex, setPageIndex] = createSignal(0, { ownedWrite: true });
  const [firstPageNumber, setFirstPageNumber] = createSignal(1, { ownedWrite: true });
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
  const pageRows = (): readonly KafkaRecord[] => pages()[pageIndex()] ?? [];
  const pageNumber = (): number => firstPageNumber() + pageIndex();
  const canPreviousPage = (): boolean => pageIndex() > 0 && !running();
  const canNextPage = (): boolean =>
    !running() && (pageIndex() < pages().length - 1 || cursor() !== undefined);

  function publishRows(): void {
    setRows(rowList.slice());
    setPages(pageList.map((page) => page.slice()));
    setHeld(heldList.length);
  }

  function trimPageCache(): void {
    let cachedRecords = pageList.reduce((total, page) => total + page.length, 0);
    while (
      pageList.length > 1 &&
      (pageList.length > MAX_CACHED_PAGES || cachedRecords > MAX_ROWS)
    ) {
      const removed = pageList.shift();
      cachedRecords -= removed?.length ?? 0;
      firstPageNumberNow += 1;
      setFirstPageNumber(firstPageNumberNow);
      activePageNow = Math.max(0, activePageNow - 1);
      setPageIndex((current) => Math.max(0, current - 1));
    }
  }

  function stop(): void {
    const stopped = handle;
    handle = undefined;
    generation += 1;
    /* Invalidate before closing: a transport is allowed to report `closed` synchronously from
     * `close()`, and that callback belongs to the stream that has already been stopped. */
    stopped?.close();
    setRunning(false);
    arrivedResetQueued = false;
    setArrived(new Set<string>());
    /* Whatever was held is shown rather than discarded. Those records were delivered; throwing
     * them away because the user pressed Stop would lose evidence that arrived before the press,
     * and on a tail there is no second chance to read them. */
    release();
  }

  function release(): void {
    pausedNow = false;
    setPausedSignal(false);
    if (heldList.length > 0) {
      const page = pageList[activePageNow] ?? [];
      if (liveNow) {
        rowList = [...heldList, ...rowList].slice(0, MAX_ROWS);
        pageList[activePageNow] = [...heldList, ...page].slice(0, MAX_ROWS);
      } else {
        rowList = [...rowList, ...heldList].slice(-MAX_ROWS);
        pageList[activePageNow] = [...page, ...heldList].slice(0, MAX_ROWS);
      }
      heldList = [];
    }
    publishRows();
  }

  function run(query: BrowseQuery, keepRows: boolean, selectNewPage = false): void {
    stop();
    const runGeneration = generation;
    const isCurrentRun = (): boolean => generation === runGeneration;
    liveNow = query.live;
    if (!keepRows) {
      rowList = [];
      pageList = [[]];
      activePageNow = 0;
      firstPageNumberNow = 1;
      setFirstPageNumber(1);
      setPageIndex(0);
    } else {
      activePageNow = pageList.length;
      pageList = [...pageList, []];
    }
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
      if (!isCurrentRun() || handle !== which) return;
      cursorNow = which.endMarker();
      setCursor(cursorNow);
      setProgress((current) => ({ ...current, endReason: which.endReason?.() }));
      handle = undefined;
      setRunning(false);
      trimPageCache();
      /* Keep the completed page visible while its successor is in flight. A cursor page may take
       * seconds when a selective filter scans deeply; replacing useful records with a blank
       * loading panel for that whole interval makes paging feel broken. Switch only when the
       * successor is complete, at which point even an empty page can still expose its cursor. */
      if (selectNewPage) setPageIndex(activePageNow);
      publishRows();
    };

    opened = options.transport.open(url, {
      onEvent: (event) => {
        if (!isCurrentRun()) return;
        switch (event.kind) {
          case "record": {
            const record = withinPayloadBudget(event.record);
            /* The count moves even while paused, because it counts what the *stream* delivered. A
             * paused screen that also stopped counting would be indistinguishable from a stream
             * that had stalled, which is the one thing a pause must not be mistaken for. */
            if (pausedNow) {
              heldList = liveNow
                ? [record, ...heldList].slice(0, MAX_ROWS)
                : [...heldList, record].slice(0, MAX_ROWS);
            } else {
              const page = pageList[activePageNow] ?? [];
              if (liveNow) {
                rowList = [record, ...rowList].slice(0, MAX_ROWS);
                pageList[activePageNow] = [record, ...page].slice(0, MAX_ROWS);
              } else {
                /* The source already orders a bounded page relative to its offsets: forward
                 * browses arrive low-to-high and backward browses high-to-low. Preserve that
                 * order, including across continuation pages, instead of reversing every page as
                 * records happen to reach the browser. */
                rowList = [...rowList, record].slice(-MAX_ROWS);
                pageList[activePageNow] = [...page, record].slice(0, MAX_ROWS);
              }
            }
            setProgress((current) => ({
              ...current,
              delivered: current.delivered + 1,
              phase: undefined,
              failure: current.failure?.kind === "decode" ? undefined : current.failure,
            }));
            setArrived((current) => new Set(current).add(recordId(record)));
            if (!arrivedResetQueued) {
              arrivedResetQueued = true;
              queueMicrotask(() => {
                if (!isCurrentRun()) return;
                arrivedResetQueued = false;
                setArrived(new Set<string>());
              });
            }
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
      onFailure: (failure) => {
        if (!isCurrentRun()) return;
        setProgress((current) => ({ ...current, failure }));
      },
      onConnection: (connection) => {
        if (!isCurrentRun()) return;
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
    pageRows,
    pageNumber,
    progress,
    running,
    paused,
    held,
    canLoadMore,
    canPreviousPage,
    canNextPage,
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
    nextPage: () => {
      if (running()) return;
      if (pageIndex() < pageList.length - 1) {
        setPageIndex(pageIndex() + 1);
        return;
      }
      if (lastQuery === undefined || cursorNow === undefined) return;
      run({ ...lastQuery, cursor: cursorNow }, true, true);
    },
    previousPage: () => {
      if (!canPreviousPage()) return;
      setPageIndex(pageIndex() - 1);
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
    case "message": {
      const decoded = decodeMessageRecord(body);
      return decoded.ok
        ? { ok: true, value: { kind: "record", record: decoded.value } }
        : decoded;
    }
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
            records: numberOr(body["records"], 0),
            bytes: numberOr(body["bytes"], 0),
            elapsedMs: numberOr(body["elapsedMs"], 0),
            ...(typeof body["filterErrors"] === "number" ? { filterErrors: body["filterErrors"] } : {}),
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
