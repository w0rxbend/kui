/**
 * The message feature's route entry: the browser for one topic's records.
 *
 *   /clusters/:clusterId/topics/:topicName/messages
 *
 * ## What it joins up
 *
 * Every piece of this existed and nothing connected them. `MessagesTab` is the screen,
 * `createBrowseSession` is the state machine, `browse.ts` is the URL grammar, `transport.ts` is the
 * network — and the package exported no `default`, so the shell rendered the kernel's "this feature
 * arrived without a screen" panel for every `/messages` address. This is the wiring.
 *
 * ## The browse is the address bar
 *
 * A browse *is* a link: the seek position, the partitions, the filter and the live flag all live in
 * the query string, so an operator can send a colleague exactly what they are looking at. That is
 * why `MessagesTab` never writes the query itself — it asks, through `onQueryChange`, and this
 * component is the one thing that writes the address. Two writers to one URL is how a screen ends
 * up fighting the Back button.
 *
 * ## Stopping actually stops
 *
 * The session is disposed when the route unmounts, which closes the stream, which aborts the
 * request, which cancels the gateway's stream, which releases the Kafka consumer. Without that last
 * link every abandoned browse leaves a consumer assigned on the message service until its budget
 * expires — see `transport.ts` for why `openEventSource` could not do this.
 */
import { Show, createMemo, createSignal, onCleanup } from "solid-js";
import type { JSX } from "@solidjs/web";
import { useLocation, useParams } from "@solidjs/router";
import { createMutation, useKui, type KafkaRecord } from "@kui/kernel";
import { Actions } from "@kui/api";
import { MessagesTab } from "./MessagesTab.jsx";
import { ProduceDrawer } from "./ProduceDrawer.jsx";
import { SmartFilterDialog } from "./SmartFilterDialog.jsx";
import { ResendDialog } from "./ResendDialog.jsx";
import { registerFilter, testFilter, type RegisteredFilter } from "./filters.js";
import { resend, type ResendDraft } from "./resend.js";
import { toDto } from "./wire.js";
import { TrackPage } from "./TrackPage.jsx";
import { emptyQuery, track, type TrackQuery } from "./track.js";
import { produce, type RecordDraft } from "./produce.js";
import { createBrowseSession } from "./session.js";
import { createBrowseTransport } from "./transport.js";
import { fromParams, queryString, type BrowseQuery } from "./browse.js";

export default function Messages(): JSX.Element {
  const params = useParams<{
    readonly clusterId?: string;
    readonly topicName?: string;
  }>();
  const location = useLocation();

  /*
   * `/messages/track` is the one address this feature serves that names no topic: a track reads
   * *across* topics, which is the whole point of it. It is matched on the path rather than by a
   * route parameter because it sits outside the `/topics/:topicName` subtree the browser lives in.
   */
  const tracking = () => location.pathname.replace(/\/+$/, "").endsWith("/messages/track");

  return (
    <Show when={params.clusterId} fallback={<NoSubject what="cluster" />}>
      {(clusterId) => (
        <Show when={!tracking()} fallback={<TrackScreen clusterId={clusterId()} />}>
          <Show when={params.topicName} fallback={<NoSubject what="topic" />}>
            {(topicName) => <BrowserScreen clusterId={clusterId()} topicName={topicName()} />}
          </Show>
        </Show>
      )}
    </Show>
  );
}

/**
 * A message browser needs both a cluster and a topic, and the navigation cannot produce a link to
 * this route without them — the drawer has no topic to name, which is why the feature has no
 * navigation entry at all. So this is a hand-typed or stale address, and it says so.
 */
function NoSubject(props: { readonly what: string }): JSX.Element {
  const kui = useKui();
  return (
    <section aria-label="Messages">
      <p role="status">
        A message browser needs a {props.what} in its address, and this one has none.{" "}
        <a href={kui.paths.clusters()}>Start from the cluster list</a>.
      </p>
    </section>
  );
}

function BrowserScreen(props: {
  readonly clusterId: string;
  readonly topicName: string;
}): JSX.Element {
  const kui = useKui();
  const location = useLocation();

  /**
   * The browse the address describes.
   *
   * Read from the URL rather than held in a signal, so that the Back button, a pasted link and a
   * reload all produce exactly the same browse. `fromParams` is total: a query string somebody has
   * edited by hand yields a valid browse rather than an exception.
   */
  const query = createMemo<BrowseQuery>(() => fromParams(new URLSearchParams(location.search)));

  const streamUrl = () =>
    `/api/v1/clusters/${encodeURIComponent(props.clusterId)}` +
    `/topics/${encodeURIComponent(props.topicName)}/messages/stream`;

  // One session for the life of this screen. Changing the *query* restarts the stream inside it;
  // changing the topic unmounts the route, which disposes it.
  const session = createBrowseSession({
    streamUrl: streamUrl(),
    transport: createBrowseTransport(),
  });

  onCleanup(() => {
    // Closes the stream, which aborts the request, which releases the consumer. The single most
    // important line in this file.
    session.stop();
  });

  const [partitionCount] = createSignal(0);
  const [producing, setProducing] = createSignal(false);
  const [editingFilter, setEditingFilter] = createSignal(false);
  const [resending, setResending] = createSignal(false);

  const write = createMutation((draft: RecordDraft) =>
    produce(kui.api, props.clusterId, props.topicName, draft),
  );

  /* Registering and previewing are two mutations rather than one, because their states are shown in
   * two different places in the dialog and a shared one would make a failed preview blank out the
   * apply button's error, or the other way round. */
  const compile = createMutation((source: string) =>
    registerFilter(kui.api, props.clusterId, source),
  );
  const preview = createMutation((source: string, sample: KafkaRecord) =>
    testFilter(kui.api, props.clusterId, source, toDto(sample)),
  );
  const copy = createMutation((draft: ResendDraft) =>
    resend(kui.api, props.clusterId, props.topicName, draft),
  );

  /**
   * The one writer of the address.
   *
   * `replaceState` rather than `pushState`: adjusting a filter is refining one view, not visiting a
   * new page, and pushing every keystroke would make the Back button walk backwards through a
   * sentence somebody typed.
   */
  function writeQuery(next: BrowseQuery): void {
    const search = queryString(next);
    const url = `${location.pathname}${search === "" ? "" : `?${search}`}`;
    window.history.replaceState(null, "", url);
  }

  /**
   * Put a compiled filter on the browse, or take it off.
   *
   * The id and the source move **together, always** — both set or both cleared. That pairing is the
   * whole reason the browse takes two parameters instead of one: a replica which has never seen this
   * id compiles the source beside it rather than refusing a filter that was registered a second ago
   * on a sibling. An address carrying only the id would work until it was opened on the wrong
   * replica, which is the worst possible time to find out.
   */
  function applyFilter(filter: RegisteredFilter | undefined): void {
    const next = query();
    writeQuery(
      filter === undefined
        ? { ...next, filterId: undefined, filterSource: undefined }
        : { ...next, filterId: filter.id, filterSource: filter.source },
    );
  }

  const mayProduce = () => kui.permits(Actions.TopicMessagesProduce);
  /* A resend reads this topic and writes another. The gateway checks both, and the second is a
   * permission on a topic that has not been named yet — so this only gates on the half that can be
   * checked here, and the server refuses the other half with the destination in the message. */
  const mayResend = () =>
    kui.permits(Actions.TopicMessagesRead) && kui.permits(Actions.TopicMessagesProduce);

  return (
    <>
      <MessagesTab
        topic={props.topicName}
        /* Not known here: the partition count comes from the topic overview, which this route does
         not fetch. `0` makes the selector offer "all partitions" and nothing else, which is honest
         — it cannot offer a list of partitions it has not been told about. Fetching the overview
         alongside the stream is the next step. */
        partitionCount={partitionCount()}
        query={query()}
        onQueryChange={writeQuery}
        session={session}
        mayProduce={mayProduce()}
        produceDisabledReason={
          mayProduce() ? undefined : "You do not have permission to publish into this topic."
        }
        onProduce={() => {
          // The last attempt's receipt or error belongs to the drawer that showed it. Reopening to
          // find "written to partition 3" from ten minutes ago reads as this record having been sent.
          write.reset();
          setProducing(true);
        }}
        mayResend={mayResend()}
        resendDisabledReason={
          mayResend()
            ? undefined
            : "You do not have permission to read this topic and publish into another one."
        }
        onResend={() => {
          // Same rule as produce: a tally from a previous copy reappearing over a fresh form would
          // read as this copy's receipt, and the figures are the whole content of that panel.
          copy.reset();
          setResending(true);
        }}
        smartFilter={{
          ...(query().filterSource === undefined ? {} : { source: query().filterSource }),
          onOpen: () => {
            compile.reset();
            preview.reset();
            setEditingFilter(true);
          },
          ...(query().filterId === undefined
            ? {}
            : {
                onClear: () => {
                  session.stop();
                  applyFilter(undefined);
                },
              }),
        }}
      />

      <SmartFilterDialog
        open={editingFilter()}
        onClose={() => setEditingFilter(false)}
        topic={props.topicName}
        {...(query().filterSource === undefined ? {} : { source: query().filterSource })}
        /* The records on screen are what a preview may be tried against. A filter is written about
           a shape of document, and a synthetic record would answer a question about a document this
           topic does not contain. */
        samples={session.rows()}
        testState={preview.state()}
        onTest={(source, sample) => void preview.run(source, sample)}
        applyState={compile.state()}
        onApply={(source) => {
          void compile.run(source).then((state) => {
            /* Only a filter the server compiled reaches the browse — and the id travels with the
               source it was minted from, because a replica that has never seen this id compiles the
               source rather than refusing a filter registered a second ago on its neighbour. */
            if (state.kind !== "done") return;
            session.stop();
            applyFilter(state.value);
            setEditingFilter(false);
          });
        }}
        {...(query().filterId === undefined
          ? {}
          : {
              onClear: () => {
                session.stop();
                applyFilter(undefined);
                setEditingFilter(false);
              },
            })}
      />

      <ResendDialog
        open={resending()}
        onClose={() => setResending(false)}
        topic={props.topicName}
        partitionCount={partitionCount()}
        state={copy.state()}
        /* Stays open on success, like the produce drawer and for a stronger reason: the answer is
           two figures, and a copy that read and wrote nothing is a 200 whose whole meaning is in
           them. Closing on success would show the operator nothing at all. */
        onSend={(draft) => void copy.run(draft)}
      />

      <ProduceDrawer
        open={producing()}
        onClose={() => setProducing(false)}
        topic={props.topicName}
        partitionCount={partitionCount()}
        state={write.state()}
        onSend={(draft) => {
          /* The drawer deliberately stays open on success: it shows the partition and offset the
           broker assigned. "Sent" is not something an operator can go and check; a position is. */
          void write.run(draft);
        }}
      />
    </>
  );
}

/** Tracking one value across several topics. */
function TrackScreen(props: { readonly clusterId: string }): JSX.Element {
  const kui = useKui();
  const [query, setQuery] = createSignal<TrackQuery>(emptyQuery());
  const run = createMutation((q: TrackQuery) => track(kui.api, props.clusterId, q));

  const mayRead = () => kui.permits(Actions.TopicMessagesRead);

  return (
    <TrackPage
      query={query()}
      onQueryChange={(next) => {
        setQuery(next);
        /* The last answer described the last query. Leaving it on screen under a changed form is
           how somebody concludes a value is absent from a window they never searched. */
        run.reset();
      }}
      onSearch={() => void run.run(query())}
      state={run.state()}
      disabledReason={
        mayRead() ? undefined : "You do not have permission to read messages on this cluster."
      }
    />
  );
}
