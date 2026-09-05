/**
 * The message browser: the screen the product is used on more than any other.
 *
 * ## Nothing is read until somebody asks
 *
 * Opening this tab does not start a browse. Every other screen in KUI fetches on mount because its
 * answer is a fixed, small document; a browse is a Kafka consumer reading an unbounded log, and
 * starting one for anybody who so much as clicked the wrong tab is how a cluster ends up with
 * consumers nobody asked for. So the controls are drawn, the empty state says what to do, and Read
 * starts it.
 *
 * ## Where it starts is a choice, and the choice is in the URL
 *
 * Beginning, end, an offset, or a time — the four ways anybody actually describes where to look.
 * All of them live in the query string under the *service's own* parameter names, which means a
 * browse is a link: an operator who has found the record that matters sends the URL and their
 * colleague sees the same records. It also means the Back button undoes a filter, which is what a
 * browser's Back button is for.
 *
 * The per-partition seek the contract also supports has no control of its own — it would be a table
 * of sixty inputs — but it survives a URL round trip, so a link carrying one keeps working.
 *
 * ## Following live
 *
 * LIVE starts at the end and keeps the stream open. It is exclusive with a start position and the
 * server refuses the combination, so turning it on *sets* the seek to `latest` rather than sending
 * both. The pause is separate and is not a stop: it holds new records back while the stream keeps
 * reading, so the row somebody is looking at stays where it is and nothing is lost. See
 * `session.ts`.
 *
 * ## Stopping is real
 *
 * Stop aborts the request, and that abort travels: the gateway's stream is cancelled, the service's
 * fiber is cancelled and its Kafka consumer is closed (ADR-035). Unmounting does the same thing, so
 * navigating away cannot leave a consumer running.
 *
 * ## The bar is outside everything that re-renders
 *
 * The filter bar is a sibling of the list, not a child of anything whose condition flips when
 * records land. That is the whole fix for "the drawer rebuilt while I was typing"; see the header of
 * `MessageFilterBar.tsx`.
 */

import type { JSX } from "@solidjs/web";
import { For, Show, createMemo, createSignal, onCleanup } from "solid-js";
import {
  Button,
  EmptyState,
  Spinner,
  RecordList,
  RecordRow,
  recordKey,
  type KafkaRecord,
} from "@kui/kernel";
import { userMessage } from "@kui/api";
import { MessageFilterBar, type LiveAvailability } from "./MessageFilterBar.jsx";
import type { BrowseQuery, SeekMode } from "./browse.js";
import type { BrowseSession } from "./session.js";

export interface MessagesTabProps {
  readonly topic: string;
  /** How many partitions the topic has, for the selector and its summary. */
  readonly partitionCount: number;
  /** The browse the URL describes. This component never writes it; it asks. */
  readonly query: BrowseQuery;
  /**
   * Asks for the URL to change. The screen above owns the address bar, because a browse is a link
   * and only one thing may write it.
   */
  readonly onQueryChange: (query: BrowseQuery) => void;
  readonly session: BrowseSession;
  /** Whether this principal may publish into this topic. */
  readonly mayProduce?: boolean | undefined;
  /** Why not, when they may not. A disabled control without a reason is a control that looks broken. */
  readonly produceDisabledReason?: string | undefined;
  readonly onProduce?: (() => void) | undefined;
  readonly liveAvailability?: LiveAvailability | undefined;
  /** The clock, passed in so relative times are testable. */
  readonly now?: number | undefined;
}

export function MessagesTab(props: MessagesTabProps): JSX.Element {
  const session = props.session;

  /* The filter box's own text, which is not the same thing as the browse's `contains`. The box
   * holds what has been typed; the query holds what has been asked for. Conflating them is what
   * makes a filter field jump back to the last committed value mid-word. */
  const [filterText, setFilterText] = createSignal(props.query.contains ?? "");

  /* The clock, ticking, so "2s ago" becomes "3s ago" without every record row owning a timer. One
   * interval for the screen; five hundred rows read it. */
  const [now, setNow] = createSignal(props.now ?? Date.now());
  const ticking = setInterval(() => setNow(props.now ?? Date.now()), 1000);
  onCleanup(() => clearInterval(ticking));

  const rows = (): readonly KafkaRecord[] => session.rows();
  const failure = createMemo(() => session.progress().failure);

  function read(): void {
    session.start(props.query);
  }

  /**
   * Changing anything about *where* to read stops whatever is running.
   *
   * A browse in flight is reading a different range from the one the controls now describe, and
   * letting it keep delivering into the list would mix two ranges with nothing on screen to say
   * why.
   */
  function change(next: BrowseQuery): void {
    session.stop();
    props.onQueryChange(next);
  }

  function setSeek(seek: SeekMode): void {
    /* Following and a start position are exclusive and the server refuses the pair. Choosing a
       start therefore turns following off, rather than sending a request that will be refused. */
    change({ ...props.query, seek, live: seek.kind === "latest" ? props.query.live : false });
  }

  function setLive(live: boolean): void {
    // Following *is* "start at the end", so turning it on says so rather than sending both.
    change({ ...props.query, live, seek: live ? { kind: "latest" } : props.query.seek });
  }

  return (
    <section class="kui-browse" aria-label={`Messages in ${props.topic}`}>
      <MessageFilterBar
        seek={props.query.seek}
        onSeekChange={setSeek}
        partitionCount={props.partitionCount}
        partitions={props.query.partitions}
        onPartitionsChange={(partitions) => change({ ...props.query, partitions })}
        filter={filterText()}
        onFilterChange={setFilterText}
        onFilterCommit={(text) =>
          change({ ...props.query, ...(text === "" ? { contains: undefined } : { contains: text }) })
        }
        live={props.query.live}
        onLiveChange={setLive}
        {...(props.liveAvailability === undefined ? {} : { liveAvailability: props.liveAvailability })}
      >
        <Show
          when={session.running()}
          fallback={
            <Button variant="primary" size="sm" icon="refresh" onClick={read}>
              Read
            </Button>
          }
        >
          {/* Two controls while a browse runs, and they are different verbs. Pause holds records
              back with the consumer still reading; Stop closes it. Offering only one of them is
              what made people stop a tail to read one row and lose everything after it. */}
          <Button
            variant="secondary"
            size="sm"
            icon={session.paused() ? "chevron-down" : "minus"}
            onClick={() => session.setPaused(!session.paused())}
          >
            {/* The count appears only when there is one. `Resume (0)` was on screen for a second
                in the story that pauses a stream nothing has arrived on since — a zero drawn as a
                quantity, which is the rule this product has broken before with a bar that drew a
                full-width track for it. */}
            {pauseLabel(session.paused(), session.held())}
          </Button>
          <Button variant="secondary" size="sm" icon="close" onClick={() => session.stop()}>
            Stop
          </Button>
        </Show>
      </MessageFilterBar>

      <BrowseStatus session={session} />

      {/* The failure sits beside the records rather than replacing them. The records that did
          arrive are still what the user asked for, and clearing the list to show an error would
          throw away the evidence they were reading. */}
      <Show when={failure()}>
        {(problem) => (
          <p class="kui-browse__failure" role="alert">
            <span class="kui-browse__failure-text">{describe(problem())}</span>
          </p>
        )}
      </Show>

      <Show
        when={rows().length > 0}
        fallback={
          <BrowseEmpty
            running={session.running()}
            everRan={session.progress().connection.phase !== "idle"}
            filtered={props.query.contains !== undefined}
            onRead={read}
          />
        }
      >
        <RecordList label={`Records in ${props.topic}`}>
          <For each={rows()} keyed={(record) => recordKey(record)}>
            {(record) => (
              <RecordRow
                record={record()}
                now={now()}
                arrived={session.arrived().has(recordKey(record()))}
              />
            )}
          </For>
        </RecordList>
      </Show>

      <Show when={session.canLoadMore()}>
        <div class="kui-browse__more">
          <Button variant="secondary" size="sm" onClick={() => session.loadMore()}>
            Load more
          </Button>
        </div>
      </Show>

      <Show when={props.onProduce !== undefined}>
        <div class="kui-browse__produce">
          <Button
            variant="secondary"
            icon="send"
            {...(props.mayProduce === false
              ? {
                  disabled: true as const,
                  disabledReason:
                    props.produceDisabledReason ??
                    "You do not hold a role that permits producing to this topic.",
                }
              : {})}
            onClick={() => props.onProduce?.()}
          >
            Produce message
          </Button>
        </div>
      </Show>
    </section>
  );
}

/**
 * What the stream is doing, how many records arrived, and how much Kafka was read to find them.
 *
 * The scanned figure is the one that makes a filtered browse interpretable: a scan over a large
 * topic routinely reads a million records and matches none, and without this line that screen is
 * identical to a topic that is empty.
 */
function BrowseStatus(props: { readonly session: BrowseSession }): JSX.Element {
  const progress = (): ReturnType<BrowseSession["progress"]> => props.session.progress();
  return (
    <p class="kui-browse__status" aria-live="polite">
      <span class="kui-browse__phase">{phaseSentence(props.session)}</span>
      <Show when={progress().delivered > 0}>
        <span class="kui-browse__figure">{plural(progress().delivered, "record")}</span>
      </Show>
      <Show when={progress().consumed}>
        {(consumed) => (
          <span class="kui-browse__figure">
            {/* "Scanned", not "read": the number is how much of the log was examined, which on a
                filtered browse is a much larger number than the one beside it, and the two being
                different is the point. */}
            scanned {plural(consumed().messages, "record")}
          </span>
        )}
      </Show>
      <Show when={props.session.paused() && props.session.held() > 0}>
        <span class="kui-browse__figure kui-browse__figure--held">
          {plural(props.session.held(), "record")} held
        </span>
      </Show>
    </p>
  );
}

function phaseSentence(session: BrowseSession): string {
  const progress = session.progress();
  if (session.paused()) return "Paused — the stream is still reading.";
  switch (progress.connection.phase) {
    case "idle":
      return "Not started.";
    case "connecting":
      return "Connecting…";
    case "open":
      return progress.phase === undefined ? "Reading…" : `${capitalise(progress.phase)}…`;
    case "closed":
      return progress.delivered === 0 ? "Finished — nothing matched." : "Finished.";
  }
}

/**
 * The four things "no records" can mean, drawn as four different screens.
 *
 * They are genuinely different facts and this project has drawn all four as the same blank space:
 * a browse nobody has started yet, one that is running and has not delivered, one that finished
 * with nothing, and one whose filter excluded everything. Only the last two are about the topic.
 */
function BrowseEmpty(props: {
  readonly running: boolean;
  readonly everRan: boolean;
  readonly filtered: boolean;
  readonly onRead: () => void;
}): JSX.Element {
  return (
    <Show
      when={props.everRan}
      fallback={
        <EmptyState
          kind="empty"
          title="Nothing has been read yet."
          description="Opening a topic does not start reading it — a browse is a real Kafka consumer. Choose where to start and press Read."
          action={
            <Button variant="primary" icon="refresh" onClick={props.onRead}>
              Read
            </Button>
          }
        />
      }
    >
      <Show
        when={!props.running}
        fallback={
          <p class="kui-browse__reading">
            <Spinner size="16px" />
            {/* Not an EmptyState: this is not an empty screen, it is a screen that is about to
                have records on it, and drawing the same panel for both makes a browse that is
                working look like one that found nothing. */}
            Reading… records appear as they arrive.
          </p>
        }
      >
        <Show
          when={props.filtered}
          fallback={
            <EmptyState
              kind="empty"
              title="No records in that range."
              description="The partitions you chose hold nothing between where you started and where the read stopped."
            />
          }
        >
          <EmptyState
            kind="filtered"
            title="No record matched that filter."
            description="Every record in the range was read; none contained that text. Clearing the filter shows them."
          />
        </Show>
      </Show>
    </Show>
  );
}

function describe(failure: NonNullable<ReturnType<BrowseSession["progress"]>["failure"]>): string {
  switch (failure.kind) {
    case "server":
      return userMessage(failure.error);
    case "transport":
      return `The stream stopped: ${failure.cause}. The records above are what arrived before it did.`;
    case "decode":
      // Informational: the stream is still running, and saying so is the difference between "one
      // record was odd" and "the browse died".
      return `One ${failure.event} event could not be read (${failure.cause}). The browse is still running.`;
  }
}

/** What the pause control says. Exported-shaped as a function so the zero case is testable. */
export function pauseLabel(paused: boolean, held: number): string {
  if (!paused) return "Pause";
  return held === 0 ? "Resume" : `Resume (${held.toLocaleString()})`;
}

function plural(count: number, noun: string): string {
  return `${count.toLocaleString()} ${noun}${count === 1 ? "" : "s"}`;
}

function capitalise(text: string): string {
  return text.length === 0 ? text : text[0]!.toUpperCase() + text.slice(1);
}
