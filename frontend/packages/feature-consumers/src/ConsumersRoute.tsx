/**
 * The consumer feature's route entry: the screen the shell renders for every `/consumer-groups…`
 * address.
 *
 *   /clusters/:clusterId/consumer-groups            the list
 *   /clusters/:clusterId/consumer-groups/:groupId   one group  (not built here yet)
 *
 * It used to render `SAMPLE_GROUPS` — a screen that looked like it worked and showed invented
 * groups with invented lag. That is the most dangerous state this product can be in: an operator
 * checking whether a consumer is behind would have got a confident answer about a group that does
 * not exist.
 */
import { Show, createEffect, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import { useParams } from "@solidjs/router";
import { useKui, valueOf, type Fetched } from "@kui/kernel";
import { GroupList } from "./GroupList.jsx";
import { GroupRoute } from "./GroupRoute.jsx";
import { fetchGroups, type GroupListResult } from "./data.js";
import { pollLag } from "./lag.js";
import type { GroupSummary } from "./model.js";

export default function Consumers(): JSX.Element {
  const params = useParams<{ readonly clusterId?: string; readonly groupId?: string }>();
  return (
    <Show when={params.clusterId} fallback={<NoCluster />}>
      {(clusterId) => (
        <Show when={params.groupId} fallback={<GroupsScreen clusterId={clusterId()} />}>
          {/* Every row in the list links here, and until now every one of those links landed back
              on the list: the detail page and the offset-reset wizard were both built and neither
              had a route to reach them from. */}
          <GroupRoute />
        </Show>
      )}
    </Show>
  );
}

function NoCluster(): JSX.Element {
  const kui = useKui();
  return (
    <section aria-label="Consumer groups">
      <p role="status">
        No cluster is selected, so there are no consumer groups to list.{" "}
        <a href={kui.paths.clusters()}>Choose a cluster</a> and try again.
      </p>
    </section>
  );
}

/** Fetch, hold, re-fetch. The third copy; all three want the kernel's `QueryCache`. */
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
        if (!cancelled) setState(() => next);
      });
      return () => {
        cancelled = true;
      };
    },
  );

  return { state, reload: () => setAttempt(attempt() + 1) };
}

function GroupsScreen(props: { readonly clusterId: string }): JSX.Element {
  const kui = useKui();
  const { state, reload } = useFetch<GroupListResult>(
    () => fetchGroups(kui.api, props.clusterId),
    () => props.clusterId,
  );

  /*
   * The rows the screen draws, held apart from the request that first produced them.
   *
   * They have to be a signal of their own rather than a derivation of `state()`, because after the
   * first answer they are edited in place by the lag poll — and `state()` must stay what the list
   * request said, so that `failure()` below still describes the right thing.
   */
  const [rows, setRows] = createSignal<readonly GroupSummary[]>([]);
  const [coordinatorsMissing, setCoordinatorsMissing] = createSignal(0);

  createEffect(
    () => state(),
    (current) => {
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
      const listing = valueOf(current, { groups: [], coordinatorsMissing: 0 });
      setRows(() => listing.groups);
      setCoordinatorsMissing(listing.coordinatorsMissing);
    },
  );

  // Polling starts only once there is a list to merge into, and stops when this screen goes away —
  // a timer left running after unmount holds the whole component graph alive and keeps asking a
  // cluster the operator has navigated away from.
  createEffect(
    () => [props.clusterId, state().kind === "ready"] as const,
    ([clusterId, ready]) => {
      if (!ready) return undefined;
      // The returned function is the effect's cleanup, the same shape `useFetch` above uses: Solid
      // runs it before the next run and again when the screen is disposed.
      return pollLag(kui.api, clusterId, rows, (next, missing) => {
        setRows(() => next);
        if (missing !== null) setCoordinatorsMissing(missing);
      });
    },
  );

  /**
   * The failure, in the screen's own vocabulary.
   *
   * `GroupList` distinguishes `unavailable` from `forbidden` because only one of them has a retry
   * that could work — and neither is the same as "your filter matched nothing", which is the third
   * case its type carries and which the screen raises itself.
   */
  const failure = () => {
    const current = state();
    switch (current.kind) {
      case "failed":
        return {
          kind: "unavailable" as const,
          message: current.message,
          code: current.code,
          onRetry: reload,
        };
      case "forbidden":
        return {
          kind: "forbidden" as const,
          message: "You do not have permission to see this cluster's consumer groups.",
          code: "FORBIDDEN",
        };
      case "not-configured":
        return {
          kind: "unavailable" as const,
          message: "This deployment has no consumer group service configured.",
          code: "NOT_CONFIGURED",
          onRetry: reload,
        };
      default:
        return undefined;
    }
  };

  return (
    <GroupList
      rows={rows()}
      coordinatorsMissing={coordinatorsMissing()}
      loading={state().kind === "loading"}
      failure={failure()}
      hrefFor={(groupId) => kui.paths.consumerGroup(props.clusterId, groupId)}
    />
  );
}
