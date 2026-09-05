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
import {
  Button,
  Card,
  DataTable,
  EmptyState,
  MISSING,
  PageHeader,
  ProgressBar,
  StatusPill,
  ThresholdValue,
  formatBytes,
  formatCount,
  type Column,
} from "@kui/kernel";
import {
  DISK_CRITICAL_PERCENT,
  DISK_THRESHOLDS,
  brokerMeta,
  brokerName,
  clusterVoice,
  controllerCaption,
  diskPercent,
  healthLabel,
  healthTone,
  partitionSkew,
  voiceOf,
  type Broker,
} from "./model.js";

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
}

export function BrokerList(props: BrokerListProps): JSX.Element {
  const controller = createMemo<number | null>(() => props.brokers.find((broker) => broker.isController)?.id ?? null);
  const skew = createMemo(() => partitionSkew(props.brokers));

  const voice = createMemo(() =>
    props.failure !== undefined
      ? "Broker data is unavailable."
      : clusterVoice(voiceOf(props.brokers, props.underReplicatedPartitions ?? null, props.observedAgo ?? null)),
  );

  const columns: readonly Column<Broker>[] = [
    {
      id: "broker",
      header: "Broker",
      render: (row) => (
        <span class="kui-brk-name">
          <span class={`kui-brk-dot kui-brk-dot--${row.health}`} aria-hidden="true" />
          <a
            class="kui-brk-name__link"
            href={props.hrefFor(row.id)}
            onClick={(event: MouseEvent) => {
              if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
              if (props.onOpen === undefined) return;
              event.preventDefault();
              event.stopPropagation();
              props.onOpen(row.id);
            }}
          >
            {brokerName(row)}
          </a>
          <Show when={row.isController}>
            <StatusPill tone="accent" title="This broker is the cluster's active controller.">
              controller
            </StatusPill>
          </Show>
        </span>
      ),
    },
    { id: "id", header: "Id", align: "numeric", width: "5rem", render: (row) => formatCount(row.id) },
    {
      id: "health",
      header: "Health",
      width: "9rem",
      render: (row) => <StatusPill tone={healthTone(row.health)}>{healthLabel(row.health)}</StatusPill>,
    },
    {
      id: "rack",
      header: "Rack",
      width: "8rem",
      // Kept only where the cluster is rack-aware; see `brokerColumns` below. On a cluster with no
      // racks this column is an em dash on every row, which is the thing that teaches people to
      // stop reading columns.
      render: (row) => (row.rack === null ? MISSING : <span class="kui-brk-mono">{row.rack}</span>),
    },
    {
      id: "leaders",
      header: "Leaders",
      align: "numeric",
      width: "7rem",
      render: (row) => (row.leaderPartitions === null ? <Unknown what="leader count" /> : formatCount(row.leaderPartitions)),
    },
    {
      id: "replicas",
      header: "Replicas",
      align: "numeric",
      width: "7rem",
      render: (row) => (row.replicaPartitions === null ? <Unknown what="replica count" /> : formatCount(row.replicaPartitions)),
    },
    {
      id: "outOfSync",
      header: "Out of sync",
      align: "numeric",
      width: "8rem",
      render: (row) =>
        row.outOfSyncReplicas === null ? (
          <Unknown what="out-of-sync replica count" />
        ) : (
          <ThresholdValue
            value={formatCount(row.outOfSyncReplicas)}
            level={row.outOfSyncReplicas === 0 ? "normal" : "critical"}
            announcement={() => "replicas out of sync"}
          />
        ),
    },
    {
      id: "disk",
      header: "Disk",
      align: "numeric",
      width: "9rem",
      render: (row) => <DiskCell broker={row} />,
    },
  ];

  const visibleColumns = createMemo<readonly Column<Broker>[]>(() =>
    props.brokers.some((broker) => broker.rack !== null) ? columns : columns.filter((column) => column.id !== "rack"),
  );

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
        <div class="kui-brk-grid">
          <Card
            title="Broker health"
            caption={controllerCaption(controller())}
            state={props.loading === true && props.brokers.length === 0 ? "loading" : "ready"}
            // Three broker rows at the real height, so the panel does not resize when data lands.
            bodyMinHeight="11rem"
            testId="broker-health"
          >
            <Show
              when={props.brokers.length > 0}
              fallback={<EmptyState kind="empty" title="No brokers." description="Nothing has answered on this cluster." />}
            >
              <ul class="kui-brk-health">
                <For each={props.brokers}>{(broker) => <BrokerHealthRow broker={broker} />}</For>
              </ul>
            </Show>
          </Card>

          <Card title="Partition spread" caption={skewCaption(skew())} testId="broker-skew">
            <SkewFigure skew={skew()} brokers={props.brokers} />
          </Card>
        </div>

        <DataTable<Broker>
          caption={`Brokers on ${props.clusterName}`}
          columns={visibleColumns()}
          rows={props.brokers}
          rowKey={(row) => String(row.id)}
          loading={props.loading}
          onRowClick={props.onOpen === undefined ? undefined : (row) => props.onOpen?.(row.id)}
          testId="brokers-table"
          empty={
            <EmptyState
              kind="empty"
              title="No brokers."
              description="KUI reached the cluster and it reported no brokers, which should not happen while it is running."
            />
          }
        />

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

/**
 * One entry of the broker-health panel: dot, name, metadata, disk track, percentage.
 *
 * The status pill appears only above the critical threshold, and it appears in *words*: around one
 * man in twelve cannot separate the amber from the red, and a bar whose only difference at 91% is
 * its hue is a bar that says nothing to them.
 */
function BrokerHealthRow(props: { readonly broker: Broker }): JSX.Element {
  const percent = createMemo(() => diskPercent(props.broker.diskUsedBytes, props.broker.diskTotalBytes));
  const critical = createMemo(() => {
    const value = percent();
    return value !== undefined && value >= DISK_CRITICAL_PERCENT;
  });

  return (
    <li class="kui-brk-health__row" data-testid={`broker-health-${props.broker.id}`}>
      <div class="kui-brk-health__head">
        <span class={`kui-brk-dot kui-brk-dot--${props.broker.health}`} aria-hidden="true" />
        <span class="kui-brk-health__name">{brokerName(props.broker)}</span>
        {/* The dot is decoration; this is the word that carries the state. Visually hidden because
            the name and the disk bar already fill the row, and a second chip per broker would make
            the panel a list of chips. */}
        <span class="kui-visually-hidden">{healthLabel(props.broker.health)}</span>
        <span class="kui-brk-health__meta">{brokerMeta(props.broker)}</span>
      </div>
      <ProgressBar
        label={`${brokerName(props.broker)} disk usage`}
        caption="disk"
        value={percent()}
        thresholds={DISK_THRESHOLDS}
        valueText={diskText(props.broker)}
        trailing={
          <Show when={critical()}>
            <StatusPill tone="danger">disk critical</StatusPill>
          </Show>
        }
      />
    </li>
  );
}

/**
 * The disk figure in words: `3.4 GB / 4.0 GB`, or an em dash.
 *
 * Both halves, because a percentage on its own does not tell an operator whether 83% is eight
 * hundred megabytes or eight terabytes, and that is the difference between "tomorrow" and "now".
 */
function diskText(broker: Broker): string {
  if (broker.diskUsedBytes === null || broker.diskTotalBytes === null) return MISSING;
  return `${formatBytes(broker.diskUsedBytes)} / ${formatBytes(broker.diskTotalBytes)}`;
}

function DiskCell(props: { readonly broker: Broker }): JSX.Element {
  const percent = createMemo(() => diskPercent(props.broker.diskUsedBytes, props.broker.diskTotalBytes));
  return (
    <Show when={percent() !== undefined} fallback={<Unknown what="disk usage" />}>
      <ThresholdValue
        value={`${Math.round(percent() ?? 0)}%`}
        level={(percent() ?? 0) >= DISK_CRITICAL_PERCENT ? "critical" : (percent() ?? 0) >= DISK_THRESHOLDS.warn ? "warning" : "normal"}
        announcement={(level) => (level === "critical" ? "disk critical" : "disk filling up")}
      />
    </Show>
  );
}

/**
 * The skew figure, and the sentence that says what it means.
 *
 * A single percentage with no explanation is a number nobody acts on, so the caption states the
 * rule: under 20% is even, and above it the busiest broker is doing measurably more work than the
 * quietest.
 */
function SkewFigure(props: { readonly skew: number | undefined; readonly brokers: readonly Broker[] }): JSX.Element {
  return (
    <Show
      when={props.skew !== undefined}
      fallback={
        <p class="kui-brk-skew__none">
          {props.brokers.length < 2
            ? "A single broker holds every partition, so there is no spread to measure."
            : "KUI could not read enough replica counts to work this out."}
        </p>
      }
    >
      <p class="kui-brk-skew" data-testid="broker-skew-figure">
        <span class={(props.skew ?? 0) > 0.2 ? "kui-brk-skew__value kui-brk-skew__value--uneven" : "kui-brk-skew__value"}>
          {`${Math.round((props.skew ?? 0) * 100)}%`}
        </span>
        <span class="kui-brk-skew__unit">between the busiest and the quietest broker</span>
      </p>
    </Show>
  );
}

function skewCaption(skew: number | undefined): string {
  if (skew === undefined) return "Replicas per broker, as a share of the average.";
  if (skew > 0.2) return "Above 20% the load is uneven enough to be worth rebalancing.";
  return "Under 20%. The partitions are spread evenly enough.";
}

function Unknown(props: { readonly what: string }): JSX.Element {
  return (
    <span class="kui-brk-missing" title={`KUI could not read this broker's ${props.what}.`}>
      <span aria-hidden="true">{MISSING}</span>
      <span class="kui-visually-hidden">{`${props.what} unavailable`}</span>
    </span>
  );
}

export { BrokerHealthRow, diskText, skewCaption };
