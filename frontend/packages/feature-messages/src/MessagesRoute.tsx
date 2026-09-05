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
import { createMutation, useKui } from "@kui/kernel";
import { Actions } from "@kui/api";
import { MessagesTab } from "./MessagesTab.jsx";
import { ProduceDrawer } from "./ProduceDrawer.jsx";
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

  const write = createMutation((draft: RecordDraft) =>
    produce(kui.api, props.clusterId, props.topicName, draft),
  );

  const mayProduce = () => kui.permits(Actions.TopicMessagesProduce);

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
        onQueryChange={(next) => {
          // The one writer of the address. `replaceState` rather than `pushState`: adjusting a filter
          // is refining one view, not visiting a new page, and pushing every keystroke would make the
          // Back button walk backwards through a sentence somebody typed.
          const search = queryString(next);
          const url = `${location.pathname}${search === "" ? "" : `?${search}`}`;
          window.history.replaceState(null, "", url);
        }}
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
