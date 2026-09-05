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
import { useKui } from "@kui/kernel";
import { useParams } from "@solidjs/router";
import { ClusterList } from "./ClusterList.jsx";
import { BrokerList } from "./BrokerList.jsx";
import { fetchBrokers, fetchClusters, type Fetched } from "./data.js";
import type { Broker, ClusterSummary } from "./model.js";

export default function Clusters(): JSX.Element {
  const params = useParams<{ readonly clusterId?: string }>();

  return (
    <Show when={params.clusterId} fallback={<ClustersScreen />}>
      {(clusterId) => <BrokersScreen clusterId={clusterId()} />}
    </Show>
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
 * The data, or the fallback.
 *
 * `stale` yields its value: out-of-date data with the reason beside it is more use than nothing,
 * and hiding it is how an operator ends up with a blank screen at the moment something is wrong.
 */
function valueOf<T>(state: Fetched<T>, fallback: T): T {
  return state.kind === "ready" || state.kind === "stale" ? state.value : fallback;
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
        message: "You do not have permission to see this. Ask an administrator for the cluster view grant.",
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
