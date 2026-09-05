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
import { Actions, userMessage, type ApiResult } from "@kui/api";
import { useParams } from "@solidjs/router";
import {
  TabStrip,
  createMutation,
  useKui,
  valueOf,
  writeBlockedReason,
  type Fetched,
} from "@kui/kernel";
import { DEFAULT_TOPIC_QUERY, TopicListPage, type TopicListQuery } from "./TopicListPage.jsx";
import { TopicPage } from "./TopicPage.jsx";
import { CreateTopicDialog } from "./CreateTopicDialog.jsx";
import { PlannedActionDialog } from "./PlannedActionDialog.jsx";
import { TopicSettings, type ConfigChange } from "./TopicSettings.jsx";
import { fetchTopicConfig, type TopicConfig } from "./config.js";
import {
  fetchTopicOverview,
  fetchTopics,
  type TopicListResult,
  type TopicOverview,
  type TopicQuery,
} from "./data.js";
import type { NewTopic } from "./write.js";
import {
  confirmPurge,
  createTopic,
  deleteTopic,
  planDeletion,
  planPurge,
  updateTopicConfig,
  type DeletionPlan,
  type PurgePlan,
} from "./write.js";

export default function Topics(): JSX.Element {
  const params = useParams<{
    readonly clusterId?: string;
    readonly topicName?: string;
  }>();

  return (
    <Show when={params.clusterId} fallback={<NoCluster />}>
      {(clusterId) => (
        <Show when={params.topicName} fallback={<TopicsScreen clusterId={clusterId()} />}>
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

/**
 * The table's column ids, in the server's spelling.
 *
 * A map rather than sending the column id straight through, because the two vocabularies differ and
 * only some columns can be sorted at all. An unmapped column sorts by nothing, which is the right
 * answer: the alternative is sending a field the server does not know, having it ignore the
 * parameter, and drawing an ascending arrow over rows in the server's own order.
 */
const SORT_FIELDS: Readonly<Record<string, string>> = {
  name: "name",
  partitions: "partitions",
  replication: "replicationFactor",
  records: "messageCount",
  size: "size",
  // `health` and `policy` are absent on purpose: the server sorts by none of them, and this map is
  // what stops a column offering an order the cluster cannot produce. The two columns are therefore
  // not marked sortable in the table either, so the header does not invite the click.
};

/** The screen's query, as the topics endpoint takes it. */
export function toTopicQuery(query: TopicListQuery): TopicQuery {
  const field = query.sort === null ? undefined : SORT_FIELDS[query.sort.columnId];
  return {
    showInternal: query.showInternal,
    ...(query.search === "" ? {} : { q: query.search }),
    ...(field === undefined ? {} : { sort: `${field}:${query.sort?.order ?? "asc"}` }),
    page: query.page,
    pageSize: query.pageSize,
  };
}

function TopicsScreen(props: { readonly clusterId: string }): JSX.Element {
  const kui = useKui();
  const [query, setQuery] = createSignal<TopicListQuery>(DEFAULT_TOPIC_QUERY);
  const { state, reload } = useFetch<TopicListResult>(
    /* Every control on this page is applied by the server. It used to ask for the largest page the
       endpoint allows and then filter, search and sort what came back, which is honest for one page
       and wrong for a cluster with four thousand topics: a search that only looks at the rows it was
       handed is a search that lies, and it lies by finding nothing and saying so. */
    () => fetchTopics(kui.api, props.clusterId, toTopicQuery(query())),
    // Re-fetched whenever any part of it changes, which is what makes the controls mean anything.
    () => `${props.clusterId}|${JSON.stringify(query())}`,
  );

  createEffect(
    () => state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  const result = () =>
    valueOf(state(), {
      topics: [],
      incomplete: 0,
      page: { page: 1, pageSize: 0, totalItems: undefined },
    });

  const [creating, setCreating] = createSignal(false);

  const create = createMutation((topic: NewTopic) => createTopic(kui.api, props.clusterId, topic));

  /*
   * Two separate reasons, and the button says which one applies. A read-only cluster is ADR-047's
   * own state rather than a permission problem, and telling an operator to ask an administrator for
   * a permission they already hold wastes their afternoon.
   */
  const createBlocked = (): string | undefined =>
    writeBlockedReason({
      permitted: kui.permits(Actions.TopicCreate),
      readOnly: false,
      action: "create a topic on this cluster",
    });

  return (
    <>
      <TopicListPage
        topics={result().topics}
        loading={state().kind === "loading"}
        incomplete={result().incomplete}
        query={query()}
        onQueryChange={setQuery}
        totalItems={result().page.totalItems}
        onOpen={(topic) => {
          window.location.assign(kui.paths.topic(props.clusterId, topic.name));
        }}
        onCreate={() => {
          // Any failure from a previous attempt goes with the dialog that showed it. Reopening to
          // find last time's error still on screen reads as this attempt having already failed.
          create.reset();
          setCreating(true);
        }}
        createDisabledReason={createBlocked()}
      />
      <CreateTopicDialog
        open={creating()}
        onClose={() => setCreating(false)}
        state={create.state()}
        /* The names the browser holds, so an obvious clash is caught without a round trip. It is
           not the authority — the server's rejection is — and on a cluster whose list was truncated
           or is stale this misses some. Missing one costs a round trip and an accurate error;
           inventing one would refuse a name that is genuinely free. */
        existingNames={result().topics.map((topic) => topic.name)}
        onCreate={(topic) => {
          void create.run(topic).then((outcome) => {
            if (outcome.kind !== "done") return;
            setCreating(false);
            // Kafka's create is accepted by the controller before the topic is listable, so the
            // list is re-fetched rather than having the new row spliced in locally — the row this
            // screen would invent is a guess at what the broker decided about the defaults.
            reload();
          });
        }}
      />
    </>
  );
}

function TopicScreen(props: {
  readonly clusterId: string;
  readonly topicName: string;
}): JSX.Element {
  const kui = useKui();
  const { state, reload } = useFetch<TopicOverview>(
    () => fetchTopicOverview(kui.api, props.clusterId, props.topicName),
    () => `${props.clusterId}/${props.topicName}`,
  );

  createEffect(
    () => state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  const overview = () =>
    state().kind === "ready" || state().kind === "stale" ? valueOf(state(), undefined) : undefined;

  const [purging, setPurging] = createSignal(false);
  const [deleting, setDeleting] = createSignal(false);

  const purge = createMutation((token: string) =>
    confirmPurge(kui.api, props.clusterId, props.topicName, token),
  );
  const remove = createMutation((token: string) =>
    deleteTopic(kui.api, props.clusterId, props.topicName, token),
  );

  /** A plan request, as the dialog wants it: the plan, or one sentence saying why there is none. */
  const planning = async <P,>(
    request: Promise<ApiResult<P>>,
  ): Promise<P | { readonly failure: string }> => {
    const answer = await request;
    return answer.ok ? answer.value : { failure: userMessage(answer.error) };
  };

  /**
   * Which section of the topic page is on screen.
   *
   * Read from the address rather than held in a signal, so that a link to a topic's settings is a
   * link somebody can send. `?tab=settings` rather than a path segment because the tabs are one
   * page's sections, not separate resources — the overview and the settings describe the same topic.
   */
  const tab = () => new URLSearchParams(location.search).get("tab") ?? "overview";

  const [configAttempt, setConfigAttempt] = createSignal(0);
  const [config, setConfig] = createSignal<Fetched<TopicConfig>>({ kind: "loading" });

  createEffect(
    () => [props.clusterId, props.topicName, tab(), configAttempt()] as const,
    () => {
      // Fetched when the tab is opened, not with the page. Thirty-three keys per topic is a request
      // nobody asked for on a page most visitors come to for the partition table.
      if (tab() !== "settings") return undefined;
      let cancelled = false;
      setConfig({ kind: "loading" });
      void fetchTopicConfig(kui.api, props.clusterId, props.topicName).then((next) => {
        if (!cancelled) setConfig(() => next);
      });
      return () => {
        cancelled = true;
      };
    },
  );

  const editConfig = createMutation((change: ConfigChange) =>
    updateTopicConfig(kui.api, props.clusterId, props.topicName, change),
  );

  const purgeBlocked = (): string | undefined =>
    writeBlockedReason({
      permitted: kui.permits(Actions.TopicMessagesDelete),
      readOnly: false,
      action: "empty this topic",
    });

  const deleteBlocked = (): string | undefined =>
    writeBlockedReason({
      permitted: kui.permits(Actions.TopicDelete),
      readOnly: false,
      action: "delete this topic",
    });

  const editBlocked = (): string | undefined =>
    writeBlockedReason({
      permitted: kui.permits(Actions.TopicEdit),
      readOnly: false,
      action: "change this topic's settings",
    });

  return (
    <>
      <TopicPage
        name={props.topicName}
        /* `unknown` until the description arrives, and `unknown` is not `offline`: one says the topic
         is broken, the other says we have not been told. */
        health={overview()?.topic.health ?? "unknown"}
        onPurge={{
          label: "Empty topic",
          onClick: () => {
            purge.reset();
            setPurging(true);
          },
          disabledReason: purgeBlocked(),
          busy: purge.busy(),
        }}
        onDelete={{
          label: "Delete topic",
          onClick: () => {
            remove.reset();
            setDeleting(true);
          },
          disabledReason: deleteBlocked(),
          busy: remove.busy(),
        }}
        tabs={
          <TabStrip
            label="Topic sections"
            currentId={tab()}
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
              {
                id: "settings",
                label: "Settings",
                icon: "sliders",
                href: `${kui.paths.topic(props.clusterId, props.topicName)}?tab=settings`,
              },
            ]}
          />
        }
      >
        <Show when={tab() === "settings"}>
          <TopicSettings
            config={valueOf(config(), { entries: [], overridden: 0 })}
            loading={config().kind === "loading"}
            state={editConfig.state()}
            onChange={
              editBlocked() === undefined
                ? (change) => {
                    void editConfig.run(change).then((outcome) => {
                      if (outcome.kind !== "done") return;
                      /* Re-read rather than patching the row locally. The response is the
                         configuration as the broker holds it *afterwards*, so a value the broker
                         normalised — "3600000" for "1h" — is the value the operator sees, and the
                         `source` of the key they just changed flips to "set on this topic" without
                         this screen having to work out that it would. */
                      setConfigAttempt(configAttempt() + 1);
                    });
                  }
                : undefined
            }
            changeDisabledReason={editBlocked()}
          />
        </Show>
      </TopicPage>

      <PlannedActionDialog<PurgePlan>
        open={purging()}
        onClose={() => setPurging(false)}
        title={`Empty ${props.topicName}?`}
        confirmLabel="Empty topic"
        confirmIcon="minus"
        /* Typing the name is asked for here because the action destroys data and cannot be undone.
         It is deliberately *not* asked for on things that can be undone — a product that demands it
         everywhere teaches operators to type names without reading. */
        typeToConfirm={props.topicName}
        plan={() => planning(planPurge(kui.api, props.clusterId, props.topicName))}
        describe={describePurge}
        state={purge.state()}
        onConfirm={(token) => {
          void purge.run(token).then((outcome) => {
            if (outcome.kind !== "done") return;
            setPurging(false);
            // The record counts and sizes on this page are now wrong by exactly what was deleted.
            reload();
          });
        }}
      />

      <PlannedActionDialog<DeletionPlan>
        open={deleting()}
        onClose={() => setDeleting(false)}
        title={`Delete ${props.topicName}?`}
        confirmLabel="Delete topic"
        confirmIcon="trash"
        typeToConfirm={props.topicName}
        plan={() => planning(planDeletion(kui.api, props.clusterId, props.topicName))}
        describe={describeDeletion}
        state={remove.state()}
        onConfirm={(token) => {
          void remove.run(token).then((outcome) => {
            if (outcome.kind !== "done") return;
            setDeleting(false);
            /* Back to the list, because this page is now about a topic that does not exist. Kafka's
             delete is asynchronous — the controller accepts it and the topic can still appear in a
             listing for a moment — so the list may still show it. That is not a failure and the
             screen says nothing about it; complaining would be this product reporting Kafka's
             ordinary behaviour as a fault. */
            window.location.assign(kui.paths.topics(props.clusterId));
          });
        }}
      />
    </>
  );
}

/**
 * What emptying this topic destroys, in figures.
 *
 * The count is `null` when any partition could not be read, and the sentence says so rather than
 * quoting a total: a sum over the partitions that *could* be read is a smaller number wearing the
 * confidence of a complete one, and the operator would agree to lose more than they were shown.
 */
export function describePurge(plan: PurgePlan): string {
  const where = `${plan.partitions} ${plan.partitions === 1 ? "partition" : "partitions"}`;
  if (plan.records === null) {
    return `Deletes every record currently in ${where}. At least one partition could not be counted, so the number of records is not known.`;
  }
  return `Deletes ${plan.records.toLocaleString()} ${plan.records === 1 ? "record" : "records"} across ${where}. The topic, its configuration and its partition count are left as they are.`;
}

/** What deleting this topic destroys. */
export function describeDeletion(plan: DeletionPlan): string {
  const where = `${plan.partitions} ${plan.partitions === 1 ? "partition" : "partitions"}`;
  const records =
    plan.records === null
      ? "At least one partition could not be counted, so the number of records is not known."
      : `That is ${plan.records.toLocaleString()} ${plan.records === 1 ? "record" : "records"}.`;
  /*
   * The most useful sentence on this dialog, and the one an operator is least likely to have
   * thought of. With `auto.create.topics.enable` on, deleting a topic something is still producing
   * to does not remove it — it removes the configuration and the data and leaves a fresh topic with
   * the broker's defaults, which is usually the opposite of the intent.
   */
  const recreated =
    plan.autoCreateEnabled === true
      ? " This cluster creates topics automatically, so anything still producing to this name will recreate it immediately — with the broker's default settings, not these."
      : "";
  return `Removes the topic and its ${where}, along with its configuration. ${records}${recreated}`;
}
