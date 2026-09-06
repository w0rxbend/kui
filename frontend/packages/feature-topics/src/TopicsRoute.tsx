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
import { Show, createEffect, createMemo, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Actions, userMessage, type ApiResult } from "@kui/api";
import { useLocation, useParams } from "@solidjs/router";
import {
  Breadcrumbs,
  ConfirmDialog,
  TabStrip,
  createMutation,
  createQueryRegistry,
  formatCount,
  notify,
  useKui,
  useQuery,
  valueOf,
  writeBlockedReason,
  type BulkAction,
  type Fetched,
  type QueryRegistry,
} from "@kui/kernel";
import { DEFAULT_TOPIC_QUERY, TopicListPage, type TopicListQuery } from "./TopicListPage.jsx";
import { TopicStatisticsRegion } from "./TopicStatisticsRegion.jsx";
import { TopicOverviewTab } from "./TopicOverviewTab.jsx";
import { topicsCsv, topicsVoice } from "./topicList.js";
import { TopicPage } from "./TopicPage.jsx";
import { CreateTopicDialog } from "./CreateTopicDialog.jsx";
import { PlannedActionDialog } from "./PlannedActionDialog.jsx";
import { TopicSettings, type ConfigChange } from "./TopicSettings.jsx";
import { TopicPartitions, type PartitionsFailure } from "./TopicPartitions.jsx";
import { TopicConsumers, type ConsumersFailure } from "./TopicConsumers.jsx";
import { AddPartitionsDialog } from "./AddPartitionsDialog.jsx";
import { fetchTopicConfig, type TopicConfig } from "./config.js";
import {
  fetchPartitions,
  fetchTopicConsumers,
  fetchTopicOverview,
  fetchTopicStatistics,
  fetchTopics,
  type PartitionRow,
  type TopicConsumerRow,
  type TopicListResult,
  type TopicOverview,
  type TopicQuery,
  type TopicStatistics,
} from "./data.js";
import type { TopicRow } from "./types.js";
import type { NewTopic } from "./write.js";
import {
  confirmPurge,
  createTopic,
  deleteTopic,
  deleteTopics,
  increasePartitions,
  planDeletion,
  planPartitionIncrease,
  planPurge,
  purgeTopics,
  updateTopicConfig,
  type BulkOutcome,
  type DeletionPlan,
  type PartitionPlan,
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

/**
 * The tabs' own answers, held apart from every other query in the product.
 *
 * A registry rather than the shared one, and `staleAfterMs: 0` rather than the default thirty
 * seconds, because these particular tabs have a rule the general cache does not: **an answer is
 * re-read every time the tab is opened**. Consumer lag and partition offsets move while somebody is
 * looking at the page, and a figure carried over from four minutes ago is wrong in the direction
 * that matters — it says a group has caught up when it has not.
 *
 * An entry that is always stale is refetched when a *new* watcher acquires it, which is exactly the
 * moment a tab opens; it is not refetched on every read, so this is one request per open and not a
 * loop. Two components on one key still share one request, which is the thing a hand-rolled
 * a hand-rolled fetch hook never gave, and the reason this file no longer has one.
 */
const TAB_QUERIES: QueryRegistry = createQueryRegistry({ staleAfterMs: 0 });

/**
 * A tab's data, fetched the first time the tab is opened and re-fetched whenever it is opened again.
 *
 * The topic page is one document with several sections, and fetching all of them on arrival would
 * ask the cluster for thirty-three configuration keys, the whole partition table and every consumer
 * group for a visitor who came to look at the overview. So each tab pays for itself: a closed tab's
 * key is `undefined`, `useQuery` binds nothing, and nothing is requested.
 */
function useTabQuery<T>(
  isOpen: () => boolean,
  key: () => string,
  load: () => Promise<Fetched<T>>,
): { readonly state: () => Fetched<T>; readonly reload: () => void } {
  const query = useQuery<T>({
    key: () => (isOpen() ? key() : undefined),
    load,
    registry: TAB_QUERIES,
  });
  return { state: query.state, reload: query.reload };
}

/**
 * A tab's fetch state, as the table's `failure` prop wants it.
 *
 * The four not-happy states of ADR-039 are never interchangeable and this is the one place they are
 * translated. `loading`, `ready` and `stale` all produce `undefined`: a stale table draws its rows,
 * because real data that is out of date is worth more than an error message, and the staleness is
 * reported beside it rather than instead of it.
 */
function tabFailure<T>(
  state: Fetched<T>,
  onRetry: () => void,
):
  | {
      readonly kind: "unavailable";
      readonly message: string;
      readonly code: string;
      readonly onRetry: () => void;
    }
  | { readonly kind: "forbidden"; readonly message: string; readonly code: string }
  | { readonly kind: "not-configured"; readonly message: string }
  | undefined {
  switch (state.kind) {
    case "failed":
      return { kind: "unavailable", message: state.message, code: state.code, onRetry };
    case "forbidden":
      return {
        kind: "forbidden",
        // No code: a refusal is not an incident and there is nothing for the operator to quote at
        // anybody except the administrator who grants the role.
        message: "Ask an administrator for a role that includes this cluster's consumer and topic read permissions.",
        code: "FORBIDDEN",
      };
    case "not-configured":
      return {
        kind: "not-configured",
        message: "This deployment has not configured the service that answers for it, so there is nothing to retry.",
      };
    default:
      return undefined;
  }
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

/**
 * The screen's query, as the topics endpoint takes it.
 *
 * `showInternal` comes out of the facet chip rather than out of a checkbox of its own, and that is
 * the whole of the mapping between the design's four-chip bar and the one parameter the wire has.
 * `Internal` asks the server for Kafka's bookkeeping topics; the other three do not, and the page
 * says which of them it applies itself — see `isServerFacet`.
 */
export function toTopicQuery(query: TopicListQuery): TopicQuery {
  const field = query.sort === null ? undefined : SORT_FIELDS[query.sort.columnId];
  return {
    showInternal: query.facet === "internal",
    ...(query.search === "" ? {} : { q: query.search }),
    ...(field === undefined ? {} : { sort: `${field}:${query.sort?.order ?? "asc"}` }),
    page: query.page,
    pageSize: query.pageSize,
  };
}

/**
 * The sentence a bulk action's outcome deserves.
 *
 * Both halves, always, because a set can fail in the middle: "3 topics deleted" over a selection of
 * five is a sentence that leaves the operator to discover the other two. `SCREENS-V4.md` §6
 * recommends the flat register here — the design's own `2 topics deleted (in spirit)` hedges about
 * whether a destructive action happened, and a confirmation is the one place this product's voice
 * is not funny.
 */
export function bulkSentence(verb: string, outcome: BulkOutcome): string {
  const done = `${formatCount(outcome.done.length)} ${outcome.done.length === 1 ? "topic" : "topics"} ${verb}`;
  if (outcome.failed.length === 0) return done;
  return `${done}. ${formatCount(outcome.failed.length)} refused: ${outcome.failed
    .map((failure) => `${failure.topic} — ${failure.reason}`)
    .join("; ")}`;
}

/**
 * Hands the browser a file.
 *
 * An object URL and a synthetic click, revoked immediately afterwards: the alternative is a data
 * URI, which several browsers cap at a couple of megabytes and which a cluster of four thousand
 * topics would exceed. The anchor is attached before it is clicked because a detached one is
 * ignored in some browsers, and removed straight after because it is not part of the page.
 */
function download(filename: string, text: string, type: string): void {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function TopicsScreen(props: { readonly clusterId: string }): JSX.Element {
  const kui = useKui();
  const [query, setQuery] = createSignal<TopicListQuery>(DEFAULT_TOPIC_QUERY);

  const list = useQuery<TopicListResult>({
    /* Every control on this page is applied by the server. It used to ask for the largest page the
       endpoint allows and then filter, search and sort what came back, which is honest for one page
       and wrong for a cluster with four thousand topics: a search that only looks at the rows it was
       handed is a search that lies, and it lies by finding nothing and saying so.

       The whole request is in the key, which is what `useQuery` means by one: two screens asking
       for the same page share one call, and a change to any control is a different key and
       therefore a different answer. */
    key: () => `topics|${props.clusterId}|${JSON.stringify(toTopicQuery(query()))}`,
    load: () => fetchTopics(kui.api, props.clusterId, toTopicQuery(query())),
  });
  const state = list.state;
  const reload = list.reload;

  /**
   * The cluster-wide statistics, on a key of their own.
   *
   * Deliberately **not** keyed by the query. This document is about the whole cluster and does not
   * move when the search box does, so typing `orders.` re-fetches the page and leaves these totals
   * exactly where they were — which is the design's load-bearing fact for this screen rather than an
   * optimisation. A key that carried the filter would issue a request per keystroke for figures that
   * cannot change because of it.
   */
  const statistics = useQuery<TopicStatistics>({
    key: () => `topic-statistics|${props.clusterId}`,
    load: () => fetchTopicStatistics(kui.api, props.clusterId),
  });

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

  /** The statistics document, or nothing. `loading`, `failed` and every refusal are all "nothing". */
  const totals = (): TopicStatistics | undefined => {
    const current = statistics.state();
    return current.kind === "ready" || current.kind === "stale" ? current.value : undefined;
  };

  const [creating, setCreating] = createSignal(false);

  /**
   * The selected topic names — one set, shared by the table and the cards (`SCREENS-V4.md` §3.7).
   *
   * Cleared whenever the query changes. A bar reading "2 topics selected" over a page holding
   * neither of them is a control whose subject the operator cannot see, and the first thing they
   * would do is click `Delete` to find out what it meant. The ticks are on rows; they go with the
   * rows.
   */
  const [selected, setSelected] = createSignal<ReadonlySet<string>>(new Set<string>());

  const changeQuery = (next: TopicListQuery): void => {
    setSelected(new Set<string>());
    setQuery(next);
  };

  /** The rows behind the ticks. A name with no row on this page contributes nothing to the file. */
  const selectedRows = createMemo(() => result().topics.filter((topic) => selected().has(topic.name)));

  const [bulk, setBulk] = createSignal<"purge" | "delete" | undefined>(undefined);

  const runBulk = createMutation(async (kind: "purge" | "delete") => {
    const names = [...selected()];
    const outcome =
      kind === "delete"
        ? await deleteTopics(kui.api, props.clusterId, names)
        : await purgeTopics(kui.api, props.clusterId, names);
    // Always `ok`: the outcome *is* the answer, failures included, because a set can fail in the
    // middle and one error envelope cannot say which half did. See `bulkSentence`.
    return { ok: true as const, value: outcome };
  });

  const create = createMutation((topic: NewTopic) => createTopic(kui.api, props.clusterId, topic));

  /*
   * Two separate reasons, and the button says which one applies. A read-only cluster is ADR-047's
   * own state rather than a permission problem, and telling an operator to ask an administrator for
   * a permission they already hold wastes their afternoon.
   */
  /**
   * Re-reads the list until the new topic is in it, or until it is time to stop asking.
   *
   * Kafka's `createTopics` returns when the *controller has accepted* the create, not when every
   * broker will list the topic — so a single re-fetch straight afterwards is a race, and losing it
   * means an operator creates a topic and does not see it. They then create it again, and the second
   * attempt fails with "topic already exists", which reads as the product being broken twice.
   *
   * The row is not spliced in locally instead, because the row this screen would invent is a guess
   * at what the broker decided about the defaults it was not given — and a guessed partition count
   * on a topic somebody is about to produce to is worse than a short wait.
   *
   * Bounded, and it stops rather than spinning: three seconds is far longer than the controller
   * takes, and a topic still absent after that is a fact about the cluster rather than a race. The
   * list then shows what the server actually returned, which is the honest answer.
   */
  const settleAfterCreate = async (name: string): Promise<void> => {
    for (let attempt = 0; attempt < 6; attempt += 1) {
      reload();
      // Long enough for the fetch the reload just started to have landed, and short enough that the
      // list is on screen well before anybody wonders.
      await new Promise((resolve) => setTimeout(resolve, 500));
      // `result()` is the screen's own view of the answer, with the same fallback the table uses,
      // so this asks exactly the question the operator is about to ask: is it on the list?
      if (result().topics.some((one) => one.name === name)) return;
    }
  };

  const createBlocked = (): string | undefined =>
    writeBlockedReason({
      permitted: kui.permits(Actions.TopicCreate),
      readOnly: false,
      action: "create a topic on this cluster",
    });

  const purgeBlocked = (): string | undefined =>
    writeBlockedReason({
      permitted: kui.permits(Actions.TopicMessagesDelete),
      readOnly: false,
      action: "empty topics on this cluster",
    });

  const deleteBlocked = (): string | undefined =>
    writeBlockedReason({
      permitted: kui.permits(Actions.TopicDelete),
      readOnly: false,
      action: "delete topics on this cluster",
    });

  /**
   * The rows on screen, as a file. Named for the cluster, because two of these in one folder would
   * otherwise be two files called `topics.csv`.
   */
  const exportRows = (rows: readonly TopicRow[]): void => {
    download(`${props.clusterId}-topics.csv`, topicsCsv(rows), "text/csv;charset=utf-8");
  };

  /**
   * What the bulk bar offers.
   *
   * An action the principal may not take is **disabled with its reason**, never hidden: if `Delete`
   * disappeared for one operator then `Purge` would move into its place, and the same gesture would
   * do two different irreversible things to two different people (`SCREENS-V4.md` §3.7).
   */
  const bulkActions = (): readonly BulkAction[] => [
    {
      id: "export",
      label: "Export",
      icon: "download",
      onSelect: () => exportRows(selectedRows()),
    },
    {
      id: "purge",
      label: "Empty",
      icon: "minus",
      destructive: true,
      ...(purgeBlocked() === undefined ? {} : { disabledReason: purgeBlocked() }),
      onSelect: () => {
        runBulk.reset();
        setBulk("purge");
      },
    },
    {
      id: "delete",
      label: "Delete",
      icon: "trash",
      destructive: true,
      ...(deleteBlocked() === undefined ? {} : { disabledReason: deleteBlocked() }),
      onSelect: () => {
        runBulk.reset();
        setBulk("delete");
      },
    },
  ];

  return (
    <>
      <TopicListPage
        topics={result().topics}
        loading={state().kind === "loading"}
        incomplete={result().incomplete}
        query={query()}
        onQueryChange={changeQuery}
        totalItems={result().page.totalItems}
        /* Two documents, one sentence: the match count is the server's answer to the *current*
           search and the two totals are the cluster's, from a document that does not move when the
           search box does. Neither component below holds both, which is why the line is composed
           here — see `topicsVoice`. */
        voice={topicsVoice(result().page.totalItems, totals()?.topics, totals()?.partitions)}
        statistics={
          <TopicStatisticsRegion
            statistics={totals()}
            loading={statistics.state().kind === "loading"}
            {...(statistics.state().kind === "failed"
              ? {
                  unavailableReason:
                    "KUI could not read this cluster's topic totals. The list below is still this cluster's.",
                }
              : {})}
          />
        }
        selected={selected()}
        onSelectionChange={setSelected}
        bulkActions={bulkActions()}
        onExport={() => exportRows(result().topics)}
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

      <ConfirmDialog
        open={bulk() !== undefined}
        onClose={() => setBulk(undefined)}
        title={
          bulk() === "delete"
            ? `Delete ${formatCount(selected().size)} ${selected().size === 1 ? "topic" : "topics"}?`
            : `Empty ${formatCount(selected().size)} ${selected().size === 1 ? "topic" : "topics"}?`
        }
        /* Named rather than counted. A confirmation that says "5 topics" is a confirmation the
           operator cannot check, and the whole reason for a plan→confirm flow is that they can. */
        consequence={
          bulk() === "delete"
            ? `Removes ${[...selected()].join(", ")}, with every record and every configuration override each of them holds. Each topic is planned and confirmed on its own, so a refusal stops that topic and not the rest.`
            : `Deletes every record currently in ${[...selected()].join(", ")}. The topics, their configuration and their partition counts are left as they are.`
        }
        confirmLabel={bulk() === "delete" ? "Delete topics" : "Empty topics"}
        confirmIcon={bulk() === "delete" ? "trash" : "minus"}
        /* Typed, because neither can be undone and both act on more than one thing at once. The
           word rather than a name: there is no single name to type. */
        typeToConfirm={bulk() === "delete" ? "delete" : "empty"}
        busy={runBulk.busy()}
        onConfirm={() => {
          const kind = bulk();
          if (kind === undefined) return;
          void runBulk.run(kind).then((outcome) => {
            if (outcome.kind !== "done") return;
            setBulk(undefined);
            setSelected(new Set<string>());
            notify(bulkSentence(kind === "delete" ? "deleted" : "emptied", outcome.value), {
              // Not `success` when part of the set refused: a green toast over three failures is
              // the reassuring rendering of the state that needs attention.
              tone: outcome.value.failed.length === 0 ? "success" : "warning",
            });
            reload();
          });
        }}
        testId="topic-bulk-confirm"
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
            notify(`${topic.name} created`, {
              message: "It may take a moment to appear in the list below.",
            });
            void settleAfterCreate(topic.name);
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
  const overviewQuery = useQuery<TopicOverview>({
    key: () => `topic-overview|${props.clusterId}|${props.topicName}`,
    load: () => fetchTopicOverview(kui.api, props.clusterId, props.topicName),
  });
  const state = overviewQuery.state;
  const reload = overviewQuery.reload;

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

  /*
   * Growing the topic is two dialogs and therefore two signals.
   *
   * `growing` is the form that asks for a number; `growTarget` is the number it produced, and its
   * presence is what opens the confirmation. They are separate rather than one three-state value
   * because the confirmation's `plan()` closes over the target, and `PlannedActionDialog` fetches
   * the plan the moment it opens — a target that arrived after the dialog did would plan for the
   * previous one.
   */
  const [growing, setGrowing] = createSignal(false);
  const [growTarget, setGrowTarget] = createSignal<number | undefined>(undefined);

  const purge = createMutation((token: string) =>
    confirmPurge(kui.api, props.clusterId, props.topicName, token),
  );
  const remove = createMutation((token: string) =>
    deleteTopic(kui.api, props.clusterId, props.topicName, token),
  );
  const grow = createMutation((token: string) =>
    increasePartitions(kui.api, props.clusterId, props.topicName, token),
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
  const routerLocation = useLocation();
  const tab = () => new URLSearchParams(routerLocation.search).get("tab") ?? "overview";

  /** What identifies the subject of every tab's request. Changing topic re-fetches whatever is open. */
  const subject = (): string => `${props.clusterId}/${props.topicName}`;

  const config = useTabQuery<TopicConfig>(
    () => tab() === "settings",
    () => `topic-config|${subject()}`,
    () => fetchTopicConfig(kui.api, props.clusterId, props.topicName),
  );

  const partitions = useTabQuery<readonly PartitionRow[]>(
    () => tab() === "partitions",
    () => `topic-partitions|${subject()}`,
    /* The uncapped endpoint, not the overview's list. The overview stops at 500 partitions, so on a
       large topic its table is short and the totals above it are not — see `fetchPartitions`. */
    () => fetchPartitions(kui.api, props.clusterId, props.topicName),
  );

  const consumers = useTabQuery<readonly TopicConsumerRow[]>(
    () => tab() === "consumers",
    () => `topic-consumers|${subject()}`,
    () => fetchTopicConsumers(kui.api, props.clusterId, props.topicName),
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

  /**
   * Growing the topic is an edit of the topic itself, so it is gated on `TopicEdit`.
   *
   * There is no separate "add partitions" action in the server's vocabulary, and inventing one here
   * — a hand-written `{resource, action}` pair — is exactly the mistake `useKui().permits` was
   * narrowed to make impossible: it would name an action the server has never heard of, answer
   * `false` for everyone for ever, and disable a control with a message blaming the operator.
   */
  const growBlocked = (): string | undefined =>
    writeBlockedReason({
      permitted: kui.permits(Actions.TopicEdit),
      readOnly: false,
      action: "add partitions to this topic",
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
        /* The trail the design draws inside the content (`SCREENS-V4.md` §4.9). The shell's own
           breadcrumb names the cluster and the section; this one names the object and the list it
           came from, which is the link an operator uses to get back to where they were. */
        breadcrumb={
          <Breadcrumbs
            crumbs={[
              { label: "Topics", href: kui.paths.topics(props.clusterId) },
              { label: props.topicName },
            ]}
          />
        }
        /* Not a produce form on this page: the browser owns producing, and a second one here would
           be a second implementation of the same drawer. The button goes where the drawer is, which
           is what the design's `➤ Produce message` does. */
        onProduce={{
          label: "Produce message",
          onClick: () => {
            window.location.assign(kui.paths.topicMessages(props.clusterId, props.topicName));
          },
        }}
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
                id: "partitions",
                label: "Partitions",
                icon: "partitions",
                /* The count comes from the overview's row rather than from the partition tab's own
                   fetch, so the strip can say how many there are before anybody opens the tab. It
                   is `undefined` — no badge at all — while the topic is undescribed, because a `0`
                   beside "Partitions" would claim something no Kafka topic is. */
                count: overview()?.topic.partitions,
                href: `${kui.paths.topic(props.clusterId, props.topicName)}?tab=partitions`,
              },
              {
                id: "messages",
                label: "Messages",
                icon: "messages",
                href: kui.paths.topicMessages(props.clusterId, props.topicName),
              },
              {
                id: "consumers",
                label: "Consumers",
                icon: "consumers",
                href: `${kui.paths.topic(props.clusterId, props.topicName)}?tab=consumers`,
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
        <Show when={tab() === "overview"}>
          <TopicOverviewTab
            {...(overview() === undefined ? {} : { overview: overview() })}
            loading={state().kind === "loading"}
            partitionsHref={`${kui.paths.topic(props.clusterId, props.topicName)}?tab=partitions`}
          />
        </Show>

        <Show when={tab() === "partitions"}>
          <TopicPartitions
            partitions={valueOf(partitions.state(), [])}
            loading={partitions.state().kind === "loading"}
            failure={
              tabFailure(partitions.state(), () => partitions.reload()) as
                | PartitionsFailure
                | undefined
            }
            onAdd={() => {
              grow.reset();
              setGrowing(true);
            }}
            addDisabledReason={growBlocked()}
            addBusy={grow.busy()}
          />
        </Show>

        <Show when={tab() === "consumers"}>
          <TopicConsumers
            rows={valueOf(consumers.state(), [])}
            loading={consumers.state().kind === "loading"}
            /* `not-configured` cannot reach here: this endpoint is not sectioned, so a consumer
               service that is not configured arrives as an error envelope. The cast is narrowing
               the shared union to the two members this table draws. */
            failure={
              tabFailure(consumers.state(), () => consumers.reload()) as
                | ConsumersFailure
                | undefined
            }
            hrefFor={(groupId) => kui.paths.consumerGroup(props.clusterId, groupId)}
          />
        </Show>

        <Show when={tab() === "settings"}>
          <TopicSettings
            config={valueOf(config.state(), { entries: [], overridden: 0 })}
            loading={config.state().kind === "loading"}
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
                      config.reload();
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
            /* The count is the server's own, from the answer rather than from the plan: a partition
               the broker refused is not a partition that was emptied, and the toast is the only
               place the operator is told the difference. */
            notify(`${props.topicName} emptied`, {
              message:
                outcome.value.refused.length === 0
                  ? `${formatCount(outcome.value.purgedPartitions)} ${outcome.value.purgedPartitions === 1 ? "partition" : "partitions"} emptied.`
                  : `${formatCount(outcome.value.purgedPartitions)} emptied; ${formatCount(outcome.value.refused.length)} refused.`,
              tone: outcome.value.refused.length === 0 ? "success" : "warning",
            });
            // The record counts and sizes on this page are now wrong by exactly what was deleted.
            reload();
          });
        }}
      />

      <AddPartitionsDialog
        open={growing()}
        onClose={() => setGrowing(false)}
        topicName={props.topicName}
        /* The count the page already read. `undefined` when the topic could not be described, which
           the form reports rather than filling in a plausible number — a pre-filled `1` on a topic
           with twelve partitions is a wrong answer wearing a right answer's shape. */
        current={overview()?.topic.partitions}
        onContinue={(target) => {
          setGrowing(false);
          setGrowTarget(target);
        }}
      />

      <PlannedActionDialog<PartitionPlan>
        /* The confirmation exists only once a target has been chosen, which is what guarantees the
           plan it fetches on open is a plan for that target. */
        open={growTarget() !== undefined}
        onClose={() => setGrowTarget(undefined)}
        title={`Add partitions to ${props.topicName}?`}
        confirmLabel="Add partitions"
        confirmIcon="plus"
        /* Not destructive — no record is deleted — and still typed, because Kafka cannot remove a
           partition afterwards. The kernel's test for asking somebody to type a name is whether the
           action can be undone, and this one cannot. */
        destructive={false}
        typeToConfirm={props.topicName}
        planningMessage="Asking the cluster what this would change…"
        plan={() =>
          planning(
            planPartitionIncrease(
              kui.api,
              props.clusterId,
              props.topicName,
              growTarget() as number,
            ),
          )
        }
        describe={describePartitionIncrease}
        state={grow.state()}
        onConfirm={(token) => {
          void grow.run(token).then((outcome) => {
            if (outcome.kind !== "done") return;
            setGrowTarget(undefined);
            notify(`${props.topicName} now has ${formatCount(outcome.value.target)} partitions`, {
              message: "Kafka cannot remove a partition, so this cannot be undone.",
            });
            /* Both the partition table and the topic's own row are now wrong: the table is short by
               the new partitions and the header's count is the old one. Re-read both rather than
               splicing empty rows in — the broker decides the new partitions' replica assignment,
               and this screen would be guessing at it. */
            partitions.reload();
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
            /* Raised before the navigation, and it survives it: the toast region lives in the shell,
               above the route, so a confirmation for a page that no longer exists is still read on
               the page the operator lands on. */
            notify(`${props.topicName} deleted`, {
              message:
                outcome.value.autoCreateEnabled === true
                  ? "This cluster creates topics automatically, so anything still producing to this name will recreate it."
                  : undefined,
            });
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

/**
 * What adding partitions would do, when the server's plan carries no warning of its own.
 *
 * The server always sends `KEY_ROUTING_CHANGES`, so in practice `consequenceOf` uses that sentence
 * and this one never appears — which is the right way round: the server knows the counts, writes
 * the better sentence, and this is here so the dialog is never blank if a future server has nothing
 * to say. It states the count change and nothing else, because the one thing worth warning about is
 * precisely the thing the server's own warning covers.
 */
export function describePartitionIncrease(plan: PartitionPlan): string {
  return `Raises this topic from ${plan.current} to ${plan.target} partitions, adding ${plan.added} ${plan.added === 1 ? "partition" : "partitions"}. Kafka cannot remove a partition, so this cannot be undone.`;
}
