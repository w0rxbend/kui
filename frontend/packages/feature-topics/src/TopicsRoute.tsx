/**
 * The topics feature's route entry: the screen the shell renders for every `/topics…` address.
 *
 * ## The feature had none, and the shell said nothing
 *
 * `registry.ts` loads this package with `import("@kui/feature-topics").then(featureModule)`, and
 * `featureModule` reads a `default` export. There wasn't one. The kernel tolerates that — it renders
 * a panel saying the feature arrived without a screen — which is the right behaviour for a feature
 * that is half-built and the reason nobody noticed for as long as they did: the route resolved, the
 * navigation highlighted, and the page said something reasonable.
 *
 * ## The addresses this owns
 *
 *   /clusters/:clusterId/topics              the list
 *   /clusters/:clusterId/topics/:topicName   one topic
 *
 * The message browser hangs off a topic but belongs to `feature-messages`, which is why
 * `/topics/:topicName/messages` is not here.
 */
import { Show, createEffect, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import { useParams } from "@solidjs/router";
import { TabStrip, useKui, valueOf, type Fetched } from "@kui/kernel";
import { TopicListPage } from "./TopicListPage.jsx";
import { TopicPage } from "./TopicPage.jsx";
import { fetchTopicOverview, fetchTopics, type TopicListResult, type TopicOverview } from "./data.js";

export default function Topics(): JSX.Element {
  const params = useParams<{ readonly clusterId?: string; readonly topicName?: string }>();

  return (
    <Show
      when={params.clusterId}
      fallback={<NoCluster />}
    >
      {(clusterId) => (
        <Show
          when={params.topicName}
          fallback={<TopicsScreen clusterId={clusterId()} />}
        >
          {(topicName) => <TopicScreen clusterId={clusterId()} topicName={topicName()} />}
        </Show>
      )}
    </Show>
  );
}

/**
 * Reached only by a hand-typed or stale address: the navigation cannot produce a topics link
 * without a cluster (`landingFor` returns `undefined`), so this is the bookmark somebody kept from
 * a deployment that has since been reconfigured. It says so rather than rendering an empty list,
 * which would read as a cluster with no topics.
 */
function NoCluster(): JSX.Element {
  const kui = useKui();
  return (
    <section class="kui-topic-list" aria-label="Topics">
      <p class="kui-topic-list__incomplete" role="status">
        No cluster is selected, so there are no topics to list.{" "}
        <a href={kui.paths.clusters()}>Choose a cluster</a> and try again.
      </p>
    </section>
  );
}

/** Fetch, hold, and re-fetch on demand. Mirrors `feature-clusters`; both want the kernel's cache. */
function useFetch<T>(
  load: () => Promise<Fetched<T>>,
  deps: () => unknown,
): { readonly state: () => Fetched<T>; readonly reload: () => void } {
  const [state, setState] = createSignal<Fetched<T>>({ kind: "loading" });
  const [attempt, setAttempt] = createSignal(0);

  createEffect(
    () => [deps(), attempt()] as const,
    () => {
      let cancelled = false;
      setState({ kind: "loading" });
      void load().then((next) => {
        // Switching topic while a request is out must not let the old topic's partitions land on
        // the new topic's page — real figures, for the wrong subject, is the most convincing kind
        // of wrong data there is.
        if (!cancelled) setState(() => next);
      });
      return () => {
        cancelled = true;
      };
    },
  );

  return { state, reload: () => setAttempt(attempt() + 1) };
}

function TopicsScreen(props: { readonly clusterId: string }): JSX.Element {
  const kui = useKui();
  const { state } = useFetch<TopicListResult>(
    /* `showInternal` is requested so the screen's own checkbox has something to reveal: the server
       excludes Kafka's bookkeeping topics by default, so the control was filtering data that had
       never contained one and could not do anything. Filtering them back out is the screen's job.

       `pageSize` is the server's maximum. Server-side paging, sorting and search all exist on this
       endpoint and the list page does none of them yet — it filters what it holds, which is honest
       for one page and wrong for a cluster with four thousand topics. Asking for the largest page
       makes the current behaviour correct for every cluster this product has met; wiring the
       controls through `TopicQuery` is the next step and the reason that type exists. */
    () => fetchTopics(kui.api, props.clusterId, { showInternal: true, pageSize: 500 }),
    () => props.clusterId,
  );

  createEffect(
    () => state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  const result = () =>
    valueOf(state(), { topics: [], incomplete: 0, page: { page: 1, pageSize: 0, totalItems: undefined } });

  return (
    <TopicListPage
      topics={result().topics}
      loading={state().kind === "loading"}
      incomplete={result().incomplete}
      onOpen={(topic) => {
        window.location.assign(kui.paths.topic(props.clusterId, topic.name));
      }}
      /* The create control is drawn only where the principal may use it *and* the cluster is
         writable. A read-only cluster is ADR-047's own state and is not a permission problem, so
         the two are separate reasons and the button says which one applies. */
      onCreate={() => undefined}
      createDisabledReason={
        kui.permits({ resource: "TOPIC", action: "CREATE" })
          ? undefined
          : "You do not have permission to create a topic on this cluster."
      }
    />
  );
}

function TopicScreen(props: { readonly clusterId: string; readonly topicName: string }): JSX.Element {
  const kui = useKui();
  const { state } = useFetch<TopicOverview>(
    () => fetchTopicOverview(kui.api, props.clusterId, props.topicName),
    () => `${props.clusterId}/${props.topicName}`,
  );

  createEffect(
    () => state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  const overview = () => (state().kind === "ready" || state().kind === "stale" ? valueOf(state(), undefined) : undefined);

  return (
    <TopicPage
      name={props.topicName}
      /* `unknown` until the description arrives, and `unknown` is not `offline`: one says the topic
         is broken, the other says we have not been told. */
      health={overview()?.topic.health ?? "unknown"}
      tabs={
        <TabStrip
          label="Topic sections"
          currentId="overview"
          tabs={[
            {
              id: "overview",
              label: "Overview",
              icon: "info",
              href: kui.paths.topic(props.clusterId, props.topicName),
            },
            {
              id: "messages",
              label: "Messages",
              icon: "messages",
              href: kui.paths.topicMessages(props.clusterId, props.topicName),
            },
          ]}
        />
      }
    />
  );
}
