/**
 * One cluster's brokers — the screen an operator opens when something is wrong.
 *
 * ## Two renderings of the same brokers, and both earn their place
 *
 * The **health panel** is the dashboard's broker-health panel (SPEC §4.20), lifted whole: a row per
 * broker with a dot, a name, `id 1 · 512 leaders` on the right, and a stadium disk track that turns
 * amber at 75% and red at 90%. It answers "is anything about to run out of disk" in one glance,
 * without reading a number.
 *
 * The **table** answers the questions the panel cannot: which broker is the controller, how the
 * partitions are spread, whether anything is out of sync. It is the consumer table's treatment,
 * because an operator who has learnt that table has already learnt this one.
 *
 * They are not a redundancy. The panel is a picture and the table is a grid; putting the disk bar in
 * a table cell would make it four pixels wide, and putting the replica counts in the panel would
 * make it a table with a bar in it.
 *
 * ## Nothing here refetches on a timer
 *
 * The reference product reloads this page every five seconds behind a full-page loader, so the table
 * you are reading disappears under a spinner while you read it. KUI reads a snapshot and says when
 * it was taken; the freshness line under the panel is what makes that acceptable, because the answer
 * to "how old is this" is always on screen.
 */

import { For, Show, createMemo } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Button, Card, EmptyState, PageHeader, StatTile, formatBytes, formatCount, type TileFigure } from "@kui/kernel";
import { BrokerCard, type BrokerConfig } from "./BrokerCard.js";
import { clusterVoice, partitionSkew, summariseDisk, voiceOf, type Broker, type ClusterDisk } from "./model.js";

export interface BrokerListProps {
  readonly clusterName: string;
  readonly brokers: readonly Broker[];
  readonly underReplicatedPartitions?: number | null | undefined;
  /** How long ago the snapshot was taken, already in words: `2s ago`, `4 minutes ago`. */
  readonly observedAgo?: string | undefined;
  readonly loading?: boolean | undefined;
  readonly failure?: { readonly message: string; readonly code: string; readonly onRetry: () => void } | undefined;
  readonly clustersHref: string;
  readonly hrefFor: (brokerId: number) => string;
  readonly onOpen?: ((brokerId: number) => void) | undefined;
  /**
   * A card was opened or closed.
   *
   * This is what makes the settings a *lazy* fetch rather than a hidden one: the screen asks the
   * gateway for a broker's two hundred settings when somebody opens that broker's card, and a page
   * nobody expands costs nothing beyond the list. The card keeps its own open/closed state, so a
   * caller that does not care may leave this out.
   */
  readonly onToggle?: ((brokerId: number, expanded: boolean) => void) | undefined;
  /**
   * The per-broker extras the expanded card shows. Functions rather than a map on the broker,
   * because they arrive from three different calls at three different times and the card has to be
   * able to draw before any of them lands.
   */
  readonly configsFor?: ((brokerId: number) => readonly BrokerConfig[] | undefined) | undefined;
  /** How many settings the card is *not* showing, so it can say so rather than look complete. */
  readonly configsMoreFor?: ((brokerId: number) => number | undefined) | undefined;
  readonly configsErrorFor?: ((brokerId: number) => string | undefined) | undefined;
  readonly versionFor?: ((brokerId: number) => string | undefined) | undefined;
  readonly uptimeFor?: ((brokerId: number) => string | undefined) | undefined;
}

export function BrokerList(props: BrokerListProps): JSX.Element {
  const controller = createMemo<number | null>(() => props.brokers.find((broker) => broker.isController)?.id ?? null);
  const skew = createMemo(() => partitionSkew(props.brokers));
  /* One decision for the whole cluster: see `BrokerCard`'s `showRack`. */
  const rackAware = createMemo(() => props.brokers.some((broker) => broker.rack !== null));

  const voice = createMemo(() =>
    props.failure !== undefined
      ? "Broker data is unavailable."
      : clusterVoice(voiceOf(props.brokers, props.underReplicatedPartitions ?? null, props.observedAgo ?? null)),
  );

  const totalLeaders = createMemo<number | undefined>(() => {
    // `undefined` rather than a sum that silently treats an unreadable broker as zero: a total that
    // is quietly short is worse than one that admits it is not known.
    if (props.brokers.some((broker) => broker.leaderPartitions === null)) return undefined;
    return props.brokers.reduce((sum, broker) => sum + (broker.leaderPartitions ?? 0), 0);
  });

  /**
   * What the brokers' disks hold, what they could hold, and what Kafka's share of that is.
   *
   * Three sums with three fates, because they arrive from two endpoints that fail separately —
   * `summariseDisk` states the rule. The tile below prefers the capacity pair, because a percentage
   * is what an operator came here for, and falls back to what Kafka holds rather than refusing:
   * this once required a denominator, and the tile read "log directories could not be read" on a
   * cluster that had just reported its usage perfectly.
   */
  const disk = createMemo(() => summariseDisk(props.brokers));

  return (
    <section class="kui-brk-page" data-testid="brokers">
      <PageHeader
        title="Brokers"
        crumbs={[{ label: "Clusters", href: props.clustersHref }, { label: props.clusterName }, { label: "Brokers" }]}
        voice={voice()}
        testId="brokers-head"
      />

      <Show
        when={props.failure === undefined}
        fallback={
          <Card
            title="Brokers"
            state="unavailable"
            message={props.failure?.message}
            description="The cluster service is not responding, so KUI cannot say what these brokers are doing."
            code={props.failure?.code}
            stateAction={
              <Button variant="secondary" icon="refresh" onClick={() => props.failure?.onRetry()}>
                Retry
              </Button>
            }
            testId="brokers-card"
          />
        }
      >
        {/* The four figures the design puts across the top. The fourth is partition skew rather than
            the design's NETWORK: this product has no per-broker throughput figure on the wire, and a
            tile showing "not measured" for ever is a tile nobody reads — whereas skew is computed
            from data already on screen and answers a question the cards cannot. */}
        <div class="kui-brk-tiles">
          <StatTile
            label="ACTIVE CONTROLLER"
            icon="brokers"
            tone="primary"
            figure={
              controller() === null
                ? { kind: "unknown" }
                : { kind: "value", text: `broker ${formatCount(controller() ?? 0)}` }
            }
            chip={controller() === null ? { text: "no controller reported", tone: "attention" } : undefined}
          />
          <StatTile
            label="TOTAL LEADERS"
            icon="partitions"
            tone="accent"
            figure={
              totalLeaders() === undefined
                ? { kind: "unknown" }
                : { kind: "value", text: formatCount(totalLeaders() ?? 0) }
            }
            chip={
              totalLeaders() !== undefined
                ? undefined
                : /* Two different silences, and the old chip said the wrong one. A broker in this
                     list *did* answer `describeCluster` — that is how it got here. Kafka simply
                     does not report a per-broker leader count on every cluster, and saying "one or
                     more brokers did not answer" about a cluster that answered sends an operator
                     looking for an outage that is not happening. */
                  props.brokers.length === 0
                  ? { text: "no broker answered", tone: "attention" }
                  : { text: "this cluster does not report leader counts" }
            }
          />
          <StatTile
            label="DISK USED"
            icon="disk"
            tone="warning"
            figure={diskFigure(disk())}
            chip={diskChip(disk())}
          />
          <StatTile
            label="PARTITION SKEW"
            icon="chart-bars"
            tone="success"
            figure={skew() === undefined ? { kind: "unknown" } : { kind: "value", text: `${Math.round((skew() ?? 0) * 100)}`, unit: "%" }}
            chip={
              /* A skew needs at least two brokers to be a skew at all. Saying so is more use than
                 the general caption, which on a single-broker cluster reads as though something
                 failed. */
              skew() === undefined && props.brokers.length < 2
                ? { text: "needs at least two brokers" }
                : { text: skewCaption(skew()) }
            }
          />
        </div>

        <Show
          when={props.brokers.length > 0}
          fallback={
            <Card title="Brokers" testId="brokers-empty">
              <EmptyState
                kind="empty"
                title="No brokers."
                description="KUI reached the cluster and it reported no brokers, which should not happen while it is running."
              />
            </Card>
          }
        >
          <div class="kui-brk-cards">
            <For each={props.brokers}>
              {(broker) => (
                <BrokerCard
                  broker={broker}
                  showRack={rackAware()}
                  href={props.hrefFor(broker.id)}
                  onToggle={(expanded) => props.onToggle?.(broker.id, expanded)}
                  configs={props.configsFor?.(broker.id)}
                  configsMore={props.configsMoreFor?.(broker.id)}
                  configsError={props.configsErrorFor?.(broker.id)}
                  version={props.versionFor?.(broker.id)}
                  uptime={props.uptimeFor?.(broker.id)}
                />
              )}
            </For>
          </div>
        </Show>

        <Show when={props.observedAgo}>
          {(ago) => (
            <p class="kui-brk-freshness" data-testid="brokers-freshness">
              Read {ago()}. Nothing on this page refreshes on its own.
            </p>
          )}
        </Show>
      </Show>
    </section>
  );
}




function skewCaption(skew: number | undefined): string {
  if (skew === undefined) return "Replicas per broker, as a share of the average.";
  if (skew > 0.2) return "Above 20% the load is uneven enough to be worth rebalancing.";
  return "Under 20%. The partitions are spread evenly enough.";
}

/**
 * The disk figure itself, which is one of three different numbers.
 *
 * The capacity pair first — it is the one a percentage can be read off — then what Kafka holds, and
 * only then the refusal. The order matters: falling straight to `unknown` because no capacity was
 * reported throws away a figure the server sent, and Kafka not exposing the size of the disk under
 * a log directory is the ordinary case rather than a failure.
 */
function diskFigure(disk: ClusterDisk): TileFigure {
  if (disk.usedBytes !== null) return { kind: "value", text: formatBytes(disk.usedBytes) };
  if (disk.heldBytes !== null) return { kind: "value", text: formatBytes(disk.heldBytes) };
  return { kind: "unknown" };
}

/**
 * What to say beneath it.
 *
 * The case that matters is the third: a cluster that reported what it holds and has no capacity to
 * report it against. That is the ordinary answer from a broker whose log directories KUI cannot
 * read, and it must not be drawn as a failure — nor may the figure above be read as a percentage of
 * anything.
 */
type Chip = { readonly text: string; readonly tone?: "neutral" | "positive" | "attention" };

function diskChip(disk: ClusterDisk): Chip | undefined {
  if (disk.usedBytes !== null) {
    const total = `of ${formatBytes(disk.capacityBytes ?? 0)}`;
    return disk.measured < disk.brokers
      ? {
          text: `${total}, over the ${disk.measured} of ${disk.brokers} brokers that reported a disk`,
          tone: "attention",
        }
      : { text: total };
  }
  if (disk.heldBytes !== null) {
    return { text: "what Kafka holds; no disk capacity was reported" };
  }
  return disk.brokers === 0
    ? { text: "no broker answered", tone: "attention" }
    : { text: "log directories could not be read", tone: "attention" };
}

export { skewCaption };
