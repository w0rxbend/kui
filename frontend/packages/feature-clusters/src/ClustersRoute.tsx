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
 *   /clusters/:id/brokers/:brokerId    one broker  (not yet built here — see below)
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
import { createMutation, useKui, valueOf, type Fetched } from "@kui/kernel";
import { Actions } from "@kui/api";
import { useLocation, useParams } from "@solidjs/router";
import { ClusterList } from "./ClusterList.jsx";
import { BrokerList } from "./BrokerList.jsx";
import { ClusterAdmin, type Connectivity } from "./ClusterAdmin.jsx";
import { EMPTY_CLUSTER_FORM, formFor, toRequest, type ClusterForm } from "./clusterForm.js";
import {
  deleteCluster,
  fetchBrokers,
  fetchClusters,
  fetchManagedClusters,
  saveCluster,
  testConnection,
  type ManagedClusterRow,
} from "./data.js";
import type { Broker, ClusterSummary } from "./model.js";

export default function Clusters(): JSX.Element {
  const params = useParams<{ readonly clusterId?: string }>();
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
        {(clusterId) => <BrokersScreen clusterId={clusterId()} />}
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
