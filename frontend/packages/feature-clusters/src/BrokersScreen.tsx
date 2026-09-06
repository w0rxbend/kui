/**
 * The brokers screen, fetching.
 *
 * ## Why this is its own file
 *
 * `BrokerList` draws finished view models and asks for nothing, which is what lets every state of
 * it live in a story. This is the other half: the component that decides *what to ask for and
 * when*, and it is the half whose rules were never observable. Wave 2 shipped a `BrokerList` with
 * an `underReplicatedPartitions` prop and a route that never passed it, so the voice line announced
 * "Zero under-replicated partitions" on every cluster in the product — and no test could tell,
 * because every test handed the prop in by hand.
 *
 * So the seam is a component rather than a lump inside the route: a test mounts *this*, gives it
 * nothing but an API client, and counts what it asks for. That is the only arrangement in which
 * "the screen reads the real count" and "expanding a card costs one request" are assertions about
 * the product rather than about the test's own composition.
 *
 * ## Three requests to draw the page, and a fourth per card that is opened
 *
 * - the brokers themselves;
 * - the cluster, for the under-replicated partition count the voice line reads, the version its
 *   cards tag, and when the scrape was taken;
 * - the log directories, once for the whole cluster, for the disk capacity that turns a bar into a
 *   percentage.
 *
 * Each fails on its own and none of them blanks the others: a cluster that refuses
 * `describeLogDirs` still draws every card, with a sentence where the percentage would be.
 *
 * The settings are the fourth, and they are **not** fetched on render. `describeConfigs` on an
 * ordinary broker is three hundred and forty rows and sixty kilobytes; a nine-broker cluster would
 * pay half a megabyte to draw a page whose collapsed cards show none of it. One request happens
 * when a card is opened, and a page nobody expands issues none.
 */
import { For, createEffect, createMemo, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import { relativeAge, useQuery, useKui, valueOf, type Fetched } from "@kui/kernel";

import { BrokerList } from "./BrokerList.jsx";
import type { BrokerConfig } from "./BrokerCard.js";
import {
  fetchBrokerConfigs,
  fetchBrokers,
  fetchCluster,
  fetchClusterDisks,
  withDisks,
  type BrokerDisk,
} from "./data.js";
import {
  sortConfigs,
  versionTag,
  type Broker,
  type ClusterSummary,
  type ConfigEntry,
} from "./model.js";

/** How many settings a card shows before it defers to the broker's own page. */
const CHIPS_PER_CARD = 8;

export interface BrokersScreenProps {
  readonly clusterId: string;
  /** The address of one broker's own page, and of the cluster list above this one. */
  readonly hrefFor: (brokerId: number) => string;
  readonly clustersHref: string;
  /** The clock, for the one test that would otherwise assert against the machine's own. */
  readonly now?: () => Date;
}

export function BrokersScreen(props: BrokersScreenProps): JSX.Element {
  const kui = useKui();

  const brokers = useQuery<readonly Broker[]>({
    key: () => `clusters/${props.clusterId}/brokers`,
    load: () => fetchBrokers(kui.api, props.clusterId),
  });

  const cluster = useQuery<ClusterSummary>({
    key: () => `clusters/${props.clusterId}`,
    load: () => fetchCluster(kui.api, props.clusterId),
  });

  const disks = useQuery<readonly BrokerDisk[]>({
    key: () => `clusters/${props.clusterId}/log-dirs`,
    load: () => fetchClusterDisks(kui.api, props.clusterId),
  });

  createEffect(
    () => brokers.state(),
    (current) => {
      // The shell decides what a failure means for connectivity; the feature only says whether the
      // call came back. The other two are deliberately not reported: a cluster that refuses its log
      // directories is not a gateway that is down, and dimming the whole product over it would be
      // this screen answering a question that is not its to answer.
      if (current.kind !== "loading") kui.report("feature", current.kind === "failed");
    },
  );

  /** Which cards are open, and therefore which settings have been asked for. */
  const [expanded, setExpanded] = createSignal<readonly number[]>([]);

  /*
   * The settings that have arrived, keyed by broker.
   *
   * A plain map behind the signal, written whole, for the reason `Toast.tsx` documents: Solid 2
   * applies an updater to the last *committed* value, so two answers landing in one tick would both
   * compute their new map from the same old one and one broker's settings would vanish.
   * `ownedWrite` because the writer is an effect, which is an owned scope.
   */
  let answers = new Map<number, Fetched<readonly ConfigEntry[]>>();
  const [configs, setConfigs] = createSignal<ReadonlyMap<number, Fetched<readonly ConfigEntry[]>>>(
    answers,
    { ownedWrite: true },
  );

  const record = (brokerId: number, state: Fetched<readonly ConfigEntry[]>): void => {
    answers = new Map(answers).set(brokerId, state);
    setConfigs(answers);
  };

  const rows = createMemo(() =>
    withDisks(valueOf(brokers.state(), []), valueOf(disks.state(), [])),
  );

  const summary = (): ClusterSummary | undefined => {
    const state = cluster.state();
    return state.kind === "ready" || state.kind === "stale" ? state.value : undefined;
  };

  /**
   * How old the picture is, in words.
   *
   * Absent rather than "unknown" when the scrape did not say: the freshness line under the cards
   * exists to make a page that does not refresh acceptable, and a line reading "Read unknown ago"
   * does the opposite of that.
   */
  const observedAgo = (): string | undefined => {
    const at = summary()?.observedAt;
    if (at === null || at === undefined) return undefined;
    return relativeAge(at, props.now?.() ?? new Date());
  };

  const settingsFor = (brokerId: number): Fetched<readonly ConfigEntry[]> | undefined =>
    configs().get(brokerId);

  const chipsFor = (brokerId: number): readonly BrokerConfig[] | undefined => {
    const state = settingsFor(brokerId);
    if (state === undefined) return undefined;
    if (state.kind !== "ready" && state.kind !== "stale") return undefined;
    return sortConfigs(state.value)
      .slice(0, CHIPS_PER_CARD)
      .map((entry) => ({
        name: entry.name,
        /* A sensitive setting has no value to show and is not missing one. `ConfigChip` draws
           `undefined` as an em dash, which is the wrong word for "Kafka refuses to disclose it", so
           the word is supplied here instead. */
        value: entry.sensitive ? "hidden" : (entry.value ?? undefined),
        overridden: entry.overridden,
      }));
  };

  const moreFor = (brokerId: number): number | undefined => {
    const state = settingsFor(brokerId);
    if (state === undefined || (state.kind !== "ready" && state.kind !== "stale")) return undefined;
    const rest = state.value.length - CHIPS_PER_CARD;
    return rest > 0 ? rest : undefined;
  };

  const settingsErrorFor = (brokerId: number): string | undefined => {
    const state = settingsFor(brokerId);
    switch (state?.kind) {
      case "failed":
        return state.message;
      case "forbidden":
        return "You do not have permission to read this broker's settings.";
      case "not-configured":
        return "This deployment has not configured broker settings.";
      default:
        return undefined;
    }
  };

  return (
    <>
      <BrokerList
        clusterName={props.clusterId}
        brokers={rows()}
        /*
         * The real count, from the cluster's own summary — `null` when the sweep has not produced
         * one, which a fresh stack answers for the first minute of its life. `clusterVoice` has
         * three sentences for this and only one of them mentions zero.
         */
        underReplicatedPartitions={summary()?.underReplicatedPartitions ?? null}
        observedAgo={observedAgo()}
        loading={brokers.state().kind === "loading"}
        failure={failureOf(brokers.state(), brokers.reload)}
        clustersHref={props.clustersHref}
        hrefFor={props.hrefFor}
        onToggle={(brokerId, open) =>
          setExpanded((current) =>
            open ? [...current, brokerId] : current.filter((id) => id !== brokerId),
          )
        }
        configsFor={chipsFor}
        configsMoreFor={moreFor}
        configsErrorFor={settingsErrorFor}
        /* One version for the cluster, drawn on each card: `describeCluster` reports the
           cluster's version and no endpoint carries one per broker. `versionTag` returns nothing
           where the scrape did not say, so an unread version is a missing tag, not an invented
           one. */
        versionFor={() => versionTag(summary()?.version ?? null, summary()?.controllerKind ?? null)}
      />

      {/* One subscription per open card, and the fetch is the subscription. `For` keys on the
          broker id, so a card that stays open across another card opening keeps its query — and
          therefore does not ask again. */}
      <For each={expanded()}>
        {(brokerId) => (
          <BrokerSettings
            clusterId={props.clusterId}
            brokerId={brokerId}
            onState={(state) => record(brokerId, state)}
          />
        )}
      </For>
    </>
  );
}

/**
 * One open card's settings.
 *
 * It draws nothing. It exists so that the request's lifetime is exactly the card's: `useQuery`
 * binds when its state is first read in a reactive scope and unbinds when its owner goes away, so
 * mounting one of these per open broker is what makes "one request per expansion" a structural
 * property rather than a rule somebody has to remember at each call site.
 */
function BrokerSettings(props: {
  readonly clusterId: string;
  readonly brokerId: number;
  readonly onState: (state: Fetched<readonly ConfigEntry[]>) => void;
}): JSX.Element {
  const kui = useKui();

  const settings = useQuery<readonly ConfigEntry[]>({
    key: () => `clusters/${props.clusterId}/brokers/${props.brokerId}/configs`,
    load: () => fetchBrokerConfigs(kui.api, props.clusterId, props.brokerId),
  });

  createEffect(
    () => settings.state(),
    (state) => props.onState(state),
  );

  return null;
}

/**
 * The failure panel's props, or `undefined` when there is nothing to report.
 *
 * The same three refusals the rest of this feature distinguishes: a failure has a retry that might
 * work, and neither of the other two does — the sentence tells the operator not to press it.
 */
export function failureOf<T>(
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
