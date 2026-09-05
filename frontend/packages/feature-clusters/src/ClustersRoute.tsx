/**
 * The clusters feature's route entry: the screen the shell renders for every `/clusters…` address.
 *
 * ## One component for three addresses
 *
 * The shell hands a feature the same root for every route it owns, so this component decides which
 * of its screens to draw from the address bar. That is the shape ADR-012 chose deliberately — the
 * alternative, a component per route, means the shell has to know the feature's internal structure
 * before it has downloaded it.
 *
 * The addresses this owns:
 *
 *   /clusters                          the cluster list
 *   /clusters/:id/brokers              that cluster's brokers
 *   /clusters/:id/brokers/:brokerId    one broker: its disks and its settings
 *
 * ## What it does when nothing has arrived
 *
 * Never a spinner over the whole page. The screens each take a finished view model plus a state,
 * and their loading rendering is a skeleton in the shape of the answer, so the page does not jump
 * when the data lands. A failure is the screen's own failure panel with the reason and a retry that
 * works — not a thrown error, and never an empty list, which reads as "this cluster has no brokers"
 * when it means "nobody answered".
 */
import { Show, createEffect, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  Button,
  Card,
  EmptyState,
  PageHeader,
  createMutation,
  useKui,
  valueOf,
  type Fetched,
} from "@kui/kernel";
import { Actions } from "@kui/api";
import { useLocation, useNavigate, useParams } from "@solidjs/router";
import { ClusterList } from "./ClusterList.jsx";
import { BrokerList } from "./BrokerList.jsx";
import { BrokerDetail, type BrokerTabKey, type Loaded } from "./BrokerDetail.jsx";
import { ClusterAdmin, type Connectivity } from "./ClusterAdmin.jsx";
import { EMPTY_CLUSTER_FORM, formFor, toRequest, type ClusterForm } from "./clusterForm.js";
import {
  deleteCluster,
  fetchBrokerConfigs,
  fetchBrokerLogDirs,
  fetchBrokers,
  fetchClusters,
  fetchManagedClusters,
  saveCluster,
  testConnection,
  type ManagedClusterRow,
} from "./data.js";
import type { Broker, ClusterSummary, ConfigEntry, LogDir } from "./model.js";

export default function Clusters(): JSX.Element {
  const params = useParams<{ readonly clusterId?: string; readonly brokerId?: string }>();
  const location = useLocation();

  /*
   * `/clusters/manage` is a sibling of `/clusters/:clusterId`, and the router matches the parameter
   * route for it — `manage` is a perfectly good cluster id as far as the pattern is concerned. So
   * the path is checked here rather than relying on route ordering, which would make a cluster
   * genuinely named `manage` unreachable instead.
   */
  const managing = () => location.pathname.replace(/\/+$/, "").endsWith("/clusters/manage");

  return (
    <Show when={!managing()} fallback={<ManageScreen />}>
      <Show when={params.clusterId} fallback={<ClustersScreen />}>
        {(clusterId) => (
          <Show when={params.brokerId} fallback={<BrokersScreen clusterId={clusterId()} />}>
            {(brokerId) => <BrokerScreen clusterId={clusterId()} brokerId={brokerId()} />}
          </Show>
        )}
      </Show>
    </Show>
  );
}

/**
 * Adding, changing and removing the clusters KUI knows about.
 *
 * This screen is about KUI's *own* configuration rather than about a Kafka cluster's settings, which
 * is why it asks for `ApplicationConfig` rather than `ClusterConfig`: the difference is the list of
 * which clusters exist and how to reach them, versus a broker's own configuration.
 */
function ManageScreen(): JSX.Element {
  const kui = useKui();
  const { state, reload } = useFetch<readonly ManagedClusterRow[]>(
    () => fetchManagedClusters(kui.api),
    () => "managed",
  );

  const [editing, setEditing] = createSignal<
    { readonly id: string | undefined; readonly form: ClusterForm } | undefined
  >(undefined);
  const [connectivity, setConnectivity] = createSignal<Connectivity | undefined>(undefined);

  /** The version of the record being replaced, or `undefined` for a create. */
  const version = (): number | undefined => rows().find((row) => row.id === editing()?.id)?.version;

  const rows = () => valueOf(state(), []);

  /*
   * Both mutations take the built request rather than reading the form themselves.
   *
   * The alternative needed a failure value for "there is no valid form", and the only ones available
   * describe the *network* — so a form with an empty name would have been reported as the gateway
   * being unreachable. Building at the call site, where the answer is already known, means the
   * unrepresentable state stays unrepresentable.
   */
  const save = createMutation(
    (clusterId: string, request: Record<string, unknown>, at: number | undefined) =>
      saveCluster(kui.api, clusterId, request, at),
  );

  const test = createMutation((request: Record<string, unknown>) =>
    testConnection(kui.api, request),
  );

  const remove = createMutation((cluster: ManagedClusterRow) =>
    deleteCluster(kui.api, cluster.id, cluster.version),
  );

  const mayEdit = () => kui.permits(Actions.ApplicationConfigEdit);

  /**
   * Clears everything the last form left behind.
   *
   * The connectivity result especially: a green "KUI reached this cluster" left over from the
   * cluster somebody was looking at a moment ago, sitting under a different cluster's settings, is
   * the most misleading thing this screen could show.
   */
  const forget = (): void => {
    setConnectivity(undefined);
    save.reset();
    test.reset();
  };

  return (
    <ClusterAdmin
      clusters={rows()}
      loading={state().kind === "loading"}
      editing={editing()}
      onAdd={() => {
        forget();
        setEditing({ id: undefined, form: EMPTY_CLUSTER_FORM });
      }}
      onEdit={(cluster) => {
        forget();
        setEditing({ id: cluster.id, form: formFor(cluster) });
      }}
      onCancel={() => {
        forget();
        setEditing(undefined);
      }}
      onFormChange={(form) => {
        const current = editing();
        if (current !== undefined) setEditing({ id: current.id, form });
        // The result described the settings as they were, not as they now are.
        setConnectivity(undefined);
      }}
      onSave={() => {
        const current = editing();
        if (current === undefined) return;
        const built = toRequest(current.form);
        // The button is disabled while this is false; the check is here as well because a form can
        // also be submitted with Enter.
        if (!built.ok) return;
        void save
          .run(current.id ?? current.form.id.trim(), built.request, version())
          .then((outcome) => {
            if (outcome.kind !== "done") return;
            setEditing(undefined);
            reload();
          });
      }}
      onTest={() => {
        const current = editing();
        if (current === undefined) return;
        const built = toRequest(current.form);
        if (!built.ok) return;
        void test.run(built.request).then((outcome) => {
          if (outcome.kind !== "done") return;
          setConnectivity(outcome.value as Connectivity);
        });
      }}
      onDelete={(cluster) => {
        void remove.run(cluster).then((outcome) => {
          if (outcome.kind === "done") reload();
        });
      }}
      connectivity={connectivity()}
      saveState={save.state()}
      testState={test.state()}
      deleteState={remove.state()}
      disabledReason={
        mayEdit()
          ? undefined
          : "You do not have permission to change which clusters KUI knows about."
      }
    />
  );
}

/**
 * A small fetch-and-hold.
 *
 * Deliberately not `createResource`: this returns the feature's own `Fetched` union, which
 * distinguishes `forbidden` and `not-configured` from `failed` — three states a resource's
 * `error` slot cannot tell apart, and which need three different renderings because only one of
 * them has a retry that would do anything.
 *
 * `reload` is returned rather than exposed as a signal write, so a screen's retry button cannot
 * accidentally be wired to something that sets state without asking again.
 */
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
        // A second cluster chosen while the first is in flight must not land on the new screen.
        // This is the defect that produces the most convincing wrong data there is: real figures,
        // for a cluster the user is no longer looking at.
        if (!cancelled) setState(() => next);
      });
      return () => {
        cancelled = true;
      };
    },
  );

  return { state, reload: () => setAttempt(attempt() + 1) };
}

function ClustersScreen(): JSX.Element {
  const kui = useKui();
  const { state, reload } = useFetch<readonly ClusterSummary[]>(
    () => fetchClusters(kui.api),
    () => "clusters",
  );

  createEffect(
    () => state(),
    (current) => {
      // The shell decides what a failure means for connectivity; the feature only says whether the
      // call came back.
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  return (
    <ClusterList
      clusters={valueOf(state(), [])}
      loading={state().kind === "loading"}
      failure={failureOf(state(), reload)}
      hrefFor={(id) => kui.paths.brokers(id)}
    />
  );
}

function BrokersScreen(props: { readonly clusterId: string }): JSX.Element {
  const kui = useKui();
  const { state, reload } = useFetch<readonly Broker[]>(
    () => fetchBrokers(kui.api, props.clusterId),
    () => props.clusterId,
  );

  createEffect(
    () => state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  return (
    <BrokerList
      clusterName={props.clusterId}
      brokers={valueOf(state(), [])}
      loading={state().kind === "loading"}
      failure={failureOf(state(), reload)}
      clustersHref={kui.paths.clusters()}
      hrefFor={(brokerId) => kui.paths.broker(props.clusterId, brokerId)}
    />
  );
}

/**
 * One broker: the page the broker list has been linking to since it was written.
 *
 * ## Three requests, three fates
 *
 * The broker's identity comes from the cluster's broker *list* — there is no per-broker endpoint,
 * and the row in that list is where the host, the port, the rack and the controller flag live. Its
 * disks and its settings come from two more endpoints, each of which fails on its own and neither of
 * which may blank the other; `BrokerDetail` already draws that, so all this does is keep the three
 * states apart on the way in.
 *
 * ## The settings are not fetched until the tab is opened
 *
 * `describeConfigs` on an ordinary broker is three hundred and forty rows and sixty kilobytes.
 * Somebody who came to see which disk is filling up should not pay for it, so the request is made
 * when the configuration tab is selected and not before. Coming back to it later fetches again
 * rather than holding the last answer, which is the right way round for a page whose whole purpose
 * is to show what a broker is configured with *now*.
 *
 * ## The tab is in the address, not in a signal
 *
 * `?tab=configuration`, so that the tab somebody is looking at is the tab in the link they send, and
 * so that Back leaves the tab where Back should leave it. The same shape the topic page uses.
 */
function BrokerScreen(props: {
  readonly clusterId: string;
  readonly brokerId: string;
}): JSX.Element {
  const kui = useKui();
  const navigate = useNavigate();
  const location = useLocation();

  /*
   * Kafka node ids are integers and the endpoints take them as integers. A path segment that is not
   * one — a typed URL, an old bookmark — becomes `NaN`, which matches no broker in the list, and the
   * page below says so instead of asking the gateway about a broker that cannot exist.
   */
  const brokerId = (): number => Number(props.brokerId);

  const tab = (): BrokerTabKey =>
    new URLSearchParams(location.search).get("tab") === "configuration"
      ? "configuration"
      : "logdirs";

  const brokers = useFetch<readonly Broker[]>(
    () => fetchBrokers(kui.api, props.clusterId),
    () => props.clusterId,
  );

  const logDirs = useFetch<readonly LogDir[]>(
    () => fetchBrokerLogDirs(kui.api, props.clusterId, brokerId()),
    () => `${props.clusterId}/${props.brokerId}`,
  );

  const configs = useFetch<readonly ConfigEntry[]>(
    () =>
      tab() === "configuration"
        ? fetchBrokerConfigs(kui.api, props.clusterId, brokerId())
        : // Not "there is nothing to show": nothing has been asked for yet, and the tab is not on
          // screen to show it. The panel this feeds is only built once the tab is selected.
          Promise.resolve<Fetched<readonly ConfigEntry[]>>({ kind: "loading" }),
    () => `${props.clusterId}/${props.brokerId}/${tab()}`,
  );

  createEffect(
    () => brokers.state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  const broker = () => valueOf(brokers.state(), []).find((row) => row.id === brokerId());

  return (
    <Show
      when={broker()}
      fallback={
        <BrokerNotShown
          clusterId={props.clusterId}
          brokerId={props.brokerId}
          state={brokers.state()}
          onRetry={brokers.reload}
          brokersHref={kui.paths.brokers(props.clusterId)}
        />
      }
    >
      {(found) => (
        <BrokerDetail
          broker={found()}
          clusterName={props.clusterId}
          clustersHref={kui.paths.clusters()}
          brokersHref={kui.paths.brokers(props.clusterId)}
          logDirs={loadedOf(logDirs.state(), logDirs.reload)}
          configuration={loadedOf(configs.state(), configs.reload)}
          tab={tab()}
          onTabChange={(next) => {
            const here = kui.paths.broker(props.clusterId, found().id);
            // The default tab carries no query at all, so the canonical address of this page is the
            // bare one and two links to the same view cannot be spelled two ways.
            navigate(next === "logdirs" ? here : `${here}?tab=${next}`);
          }}
        />
      )}
    </Show>
  );
}

/**
 * What stands in for the page when there is no broker to draw.
 *
 * Three different situations, and they must not look alike: the list has not answered yet, the list
 * could not be read, and the list was read and this broker is not in it. The third is the one worth
 * the care — a broker id that no longer exists is what a bookmark from before a decommission looks
 * like, and "the cluster service is not answering" would send somebody to investigate an outage that
 * is not happening.
 */
function BrokerNotShown(props: {
  readonly clusterId: string;
  readonly brokerId: string;
  readonly state: Fetched<readonly Broker[]>;
  readonly onRetry: () => void;
  readonly brokersHref: string;
}): JSX.Element {
  const failure = () => failureOf(props.state, props.onRetry);

  return (
    <section class="kui-brk-page" data-testid="broker-not-shown">
      <PageHeader
        title={`Broker ${props.brokerId}`}
        crumbs={[
          { label: props.clusterId },
          { label: "Brokers", href: props.brokersHref },
          { label: `Broker ${props.brokerId}` },
        ]}
        testId="broker-not-shown-head"
      />
      <Card
        title="Broker"
        state={props.state.kind === "loading" ? "loading" : failure() === undefined ? "ready" : "unavailable"}
        message={failure()?.message}
        description={
          failure() === undefined
            ? undefined
            : `KUI reads a broker's identity from ${props.clusterId}'s broker list, and that list could not be read.`
        }
        code={failure()?.code}
        stateAction={
          failure() === undefined ? undefined : (
            <Button variant="secondary" icon="refresh" onClick={props.onRetry}>
              Retry
            </Button>
          )
        }
        bodyMinHeight="12rem"
        testId="broker-not-shown-card"
      >
        <EmptyState
          kind="empty"
          title={`${props.clusterId} has no broker ${props.brokerId}.`}
          description="The cluster answered, and no broker in it reports that node id. It may have been decommissioned since this link was made."
          /* An anchor rather than a `Button`, because it goes somewhere: a real link is what makes
             middle-click, "open in new tab" and the status bar's preview work, and `Button` takes no
             href precisely so that this decision has to be made deliberately. */
          action={
            <a class="kui-btn kui-btn--secondary kui-btn--md" href={props.brokersHref}>
              <span class="kui-btn__label">All brokers</span>
            </a>
          }
        />
      </Card>
    </section>
  );
}

/**
 * A screen state as one tab of {@link BrokerDetail} takes it.
 *
 * `stale` keeps its value and is shown: data that is real and out of date is still the best answer
 * anybody has, and hiding it would replace a figure that was true a minute ago with a dash meaning
 * "not known". `not-configured` is drawn as unavailable with its own sentence rather than being
 * merged into a failure, for the same reason the cluster screens keep them apart.
 */
function loadedOf<T>(state: Fetched<T>, onRetry: () => void): Loaded<T> {
  switch (state.kind) {
    case "ready":
    case "stale":
      return { kind: "ready", value: state.value };
    case "loading":
      return { kind: "loading" };
    case "forbidden":
      return { kind: "forbidden", message: "You may not read this.", code: "FORBIDDEN" };
    case "not-configured":
      return {
        kind: "unavailable",
        message: "This deployment has not configured it.",
        code: "NOT_CONFIGURED",
        onRetry,
      };
    case "failed":
      return { kind: "unavailable", message: state.message, code: state.code, onRetry };
  }
}

/**
 * The failure panel's props, or `undefined` when there is nothing to report.
 *
 * `forbidden` and `not-configured` produce a panel too, and each says its own thing — a retry on
 * either would be a button that cannot work, so neither gets one that pretends otherwise. They are
 * given `reload` all the same because the screen's type requires a handler; the sentence is what
 * tells the operator not to press it.
 */
function failureOf<T>(
  state: Fetched<T>,
  reload: () => void,
): { readonly message: string; readonly code: string; readonly onRetry: () => void } | undefined {
  switch (state.kind) {
    case "failed":
      return { message: state.message, code: state.code, onRetry: reload };
    case "forbidden":
      return {
        message:
          "You do not have permission to see this. Ask an administrator for the cluster view grant.",
        code: "FORBIDDEN",
        onRetry: reload,
      };
    case "not-configured":
      return {
        message: "This deployment has no cluster configured yet.",
        code: "NOT_CONFIGURED",
        onRetry: reload,
      };
    default:
      return undefined;
  }
}
