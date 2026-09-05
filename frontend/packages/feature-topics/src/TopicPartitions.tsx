/**
 * The whole partition table for one topic, and the control that grows it.
 *
 * ## Why this is a tab and not the overview's table
 *
 * The overview carries a partition list too, and the gateway stops it at 500 rows. On a topic with
 * a thousand partitions that produces a table missing more than half of itself, under totals
 * counted from all of them — the shape in which somebody concludes that partition 700 does not
 * exist. This tab reads `…/topics/{topicName}/partitions`, which is not capped, so the table is the
 * topic.
 *
 * ## A partition with no leader is not a partition we failed to read
 *
 * The two are one field apart on the wire and opposite facts on screen. `leader: null` is the
 * gateway saying this partition **has** no leader — it is offline, neither readable nor writable —
 * and its record count is withheld rather than guessed, because a count needs a leader to answer
 * for it. So the leader cell says "none" in danger colours, and the counts beside it say "not
 * known" as an em dash. Drawing the first as a dash would report an outage as a gap in KUI's
 * knowledge; drawing the second as `0` would report an unanswered question as an empty partition.
 *
 * ## The figure at the top is a sum, and it says when it is not one
 *
 * A total over the partitions that answered, presented as the topic's total, is a smaller number
 * wearing the confidence of a complete one. When any partition withheld its count there is no
 * honest total, and the summary says how many partitions could not be counted instead of printing
 * one.
 */
import { For, Match, Show, Switch, createMemo } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  Button,
  DataTable,
  EmptyState,
  MISSING,
  StatusPill,
  formatCount,
  type Column,
} from "@kui/kernel";
import type { PartitionRow } from "./data.js";
import { formatBytes } from "./TopicListPage.jsx";

/** Why the table has no rows, when it has none. See `GroupListProps.failure` for the same shape. */
export type PartitionsFailure =
  | {
      readonly kind: "unavailable";
      readonly message: string;
      readonly code: string;
      readonly onRetry: () => void;
    }
  | { readonly kind: "forbidden"; readonly message: string; readonly code: string }
  | { readonly kind: "not-configured"; readonly message: string };

export interface TopicPartitionsProps {
  readonly partitions: readonly PartitionRow[];
  readonly loading?: boolean | undefined;
  readonly failure?: PartitionsFailure | undefined;
  /**
   * Opens the add-partitions flow. Absent when this deployment offers no such control at all;
   * present-but-disabled, with a reason, when the principal may not use it — see
   * `addDisabledReason`. A permission the operator lacks is never hidden.
   */
  readonly onAdd?: (() => void) | undefined;
  readonly addDisabledReason?: string | undefined;
  readonly addBusy?: boolean | undefined;
}

/** How many partitions have no leader. The one figure on this screen that is an alarm. */
export function offlineCount(partitions: readonly PartitionRow[]): number {
  return partitions.filter((partition) => partition.leader === null).length;
}

/** How many partitions are short of an in-sync replica. Readable, and at risk. */
export function underReplicatedCount(partitions: readonly PartitionRow[]): number {
  return partitions.filter(
    (partition) => partition.leader !== null && partition.inSync.length < partition.replicas.length,
  ).length;
}

/**
 * The sentence above the table.
 *
 * `null` for the record total when any partition withheld its count, and the sentence then says how
 * many did rather than quoting a sum over the rest. That is the never-zero rule applied to an
 * aggregate: a total is a claim about every partition, so one silent partition invalidates it.
 */
export function partitionSummary(partitions: readonly PartitionRow[]): string {
  if (partitions.length === 0) return "";
  const count = `${formatCount(partitions.length)} ${partitions.length === 1 ? "partition" : "partitions"}`;
  const uncounted = partitions.filter((partition) => partition.messageCount === null).length;
  if (uncounted > 0) {
    return `${count}. ${formatCount(uncounted)} of them did not report a record count, so this topic has no total to show.`;
  }
  const records = partitions.reduce((total, partition) => total + (partition.messageCount ?? 0), 0);
  return `${count}, holding ${formatCount(records)} ${records === 1 ? "record" : "records"}.`;
}

export function TopicPartitions(props: TopicPartitionsProps): JSX.Element {
  const offline = createMemo(() => offlineCount(props.partitions));
  const underReplicated = createMemo(() => underReplicatedCount(props.partitions));

  const columns: readonly Column<PartitionRow>[] = [
    {
      id: "partition",
      header: "Partition",
      align: "numeric",
      width: "10%",
      render: (row) => <span class="kui-table__cell-number">{row.partition}</span>,
    },
    {
      id: "leader",
      header: "Leader",
      width: "12%",
      render: (row) => (
        <Show
          when={row.leader !== null}
          fallback={
            /* Not an em dash. See the header: this is the cluster telling us there is no leader,
               which is an outage on this partition, and it must not read as a missing reading. */
            <StatusPill tone="danger" dot>
              none
            </StatusPill>
          }
        >
          <span class="kui-table__cell-number">{row.leader}</span>
        </Show>
      ),
    },
    {
      id: "replicas",
      header: "Replicas",
      width: "22%",
      render: (row) => <Replicas row={row} />,
    },
    {
      id: "inSync",
      header: "In sync",
      align: "numeric",
      width: "12%",
      render: (row) => (
        <span
          class={
            row.inSync.length < row.replicas.length
              ? "kui-partitions__short"
              : "kui-table__cell-number"
          }
        >
          {row.inSync.length} of {row.replicas.length}
        </span>
      ),
    },
    {
      id: "earliest",
      header: "Earliest offset",
      align: "numeric",
      /* `0` here is a fact and prints as `0`: it means nothing has ever been deleted from this
         partition by retention. It is only `null` that becomes a dash. */
      render: (row) => <Offset value={row.earliestOffset} />,
    },
    {
      id: "latest",
      header: "Latest offset",
      align: "numeric",
      render: (row) => <Offset value={row.latestOffset} />,
    },
    {
      id: "records",
      header: "Records",
      align: "numeric",
      render: (row) => <Offset value={row.messageCount} />,
    },
    {
      id: "size",
      header: "Size",
      align: "numeric",
      /* Almost always absent: a broker reports per-partition size only where KUI has a metrics
         source for it, and a single-broker cluster reports none at all. A dash, never a zero — a
         partition holding eight records does not occupy no disk. */
      render: (row) => (
        <Show when={row.sizeBytes !== null} fallback={<NotKnown />}>
          <span class="kui-table__cell-number">{formatBytes(row.sizeBytes as number)}</span>
        </Show>
      ),
    },
  ];

  return (
    <section class="kui-partitions" aria-label="Partitions">
      <div class="kui-partitions__controls">
        <p class="kui-partitions__summary" role="status">
          {partitionSummary(props.partitions)}
        </p>

        <Show when={props.onAdd}>
          {(add) => (
            <Button
              variant="secondary"
              icon="plus"
              busy={props.addBusy === true}
              {...(props.addDisabledReason === undefined
                ? {}
                : { disabled: true as const, disabledReason: props.addDisabledReason })}
              onClick={() => add()()}
            >
              Add partitions
            </Button>
          )}
        </Show>
      </div>

      {/* Two separate sentences, because they are two different situations and the second is not a
          milder version of the first. An offline partition is not being written to at all; an
          under-replicated one is, and would lose data only if the leader went as well. */}
      <Show when={offline() > 0}>
        <p class="kui-partitions__alarm" role="status">
          {formatCount(offline())} {offline() === 1 ? "partition has" : "partitions have"} no
          leader. {offline() === 1 ? "It is" : "They are"} neither readable nor writable until a
          broker takes {offline() === 1 ? "it" : "them"} over.
        </p>
      </Show>
      <Show when={offline() === 0 && underReplicated() > 0}>
        <p class="kui-partitions__warning" role="status">
          {formatCount(underReplicated())}{" "}
          {underReplicated() === 1 ? "partition is" : "partitions are"} short of an in-sync replica.{" "}
          {underReplicated() === 1 ? "It is" : "They are"} still readable and writable.
        </p>
      </Show>

      <DataTable<PartitionRow>
        columns={columns}
        rows={props.partitions}
        rowKey={(row) => String(row.partition)}
        caption="Partitions of this topic"
        loading={props.loading === true}
        testId="topic-partitions-table"
        empty={<PartitionsEmpty failure={props.failure} />}
      />
    </section>
  );
}

/**
 * The replica list, with the leader named and the lagging ones marked.
 *
 * Broker ids rather than a count, because "3 replicas" does not answer the question an operator has
 * at this point, which is *which broker* is behind — that is the one they go and look at.
 */
function Replicas(props: { readonly row: PartitionRow }): JSX.Element {
  const inSync = createMemo(() => new Set(props.row.inSync));
  return (
    <Show when={props.row.replicas.length > 0} fallback={<NotKnown />}>
      <span class="kui-partitions__replicas">
        <For each={props.row.replicas}>
          {(broker) => (
            <span
              class={
                inSync().has(broker)
                  ? "kui-partitions__replica"
                  : "kui-partitions__replica kui-partitions__replica--behind"
              }
              title={
                inSync().has(broker)
                  ? `Broker ${broker} is in sync${broker === props.row.leader ? " and is the leader" : ""}.`
                  : `Broker ${broker} is not in sync with the leader.`
              }
            >
              {broker}
              <Show when={broker === props.row.leader}>
                <span class="kui-partitions__leader-mark" aria-hidden="true">
                  ★
                </span>
                <span class="kui-visually-hidden"> (leader)</span>
              </Show>
              <Show when={!inSync().has(broker)}>
                <span class="kui-visually-hidden"> (not in sync)</span>
              </Show>
            </span>
          )}
        </For>
      </span>
    </Show>
  );
}

/** An offset or a count. `null` is a reading nobody could take, and never a zero. */
function Offset(props: { readonly value: number | null }): JSX.Element {
  return (
    <Show when={props.value !== null} fallback={<NotKnown />}>
      <span class="kui-table__cell-number">{formatCount(props.value as number)}</span>
    </Show>
  );
}

/**
 * The em dash, with the words a screen reader needs.
 *
 * The dash alone is silent to a reader who cannot see it, which would make "not known" and "zero"
 * indistinguishable in exactly the audience least able to check.
 */
function NotKnown(): JSX.Element {
  return (
    <span class="kui-table__cell-muted">
      <span aria-hidden="true">{MISSING}</span>
      <span class="kui-visually-hidden">not known</span>
    </span>
  );
}

/**
 * The four kinds of nothing, told apart.
 *
 * "This topic has no partitions" is the one sentence that is never true of a Kafka topic, so the
 * `empty` rendering says the cluster answered with an empty list rather than pretending that is a
 * normal state a topic can be in.
 */
function PartitionsEmpty(props: { readonly failure?: PartitionsFailure | undefined }): JSX.Element {
  const failure = (): PartitionsFailure | undefined => props.failure;
  return (
    <Switch
      fallback={
        <EmptyState
          kind="empty"
          title="No partitions came back."
          description="Every Kafka topic has at least one partition, so this is the cluster answering with an empty list rather than a topic that has none."
        />
      }
    >
      <Match when={failure()?.kind === "unavailable" ? failure() : undefined}>
        {(reason) => (
          <EmptyState
            kind="unavailable"
            title="The partition table did not come back."
            description={reason().message}
            code={(reason() as { readonly code: string }).code}
            action={
              <Button
                variant="secondary"
                icon="refresh"
                onClick={() => (reason() as { readonly onRetry: () => void }).onRetry()}
              >
                Try again
              </Button>
            }
          />
        )}
      </Match>

      <Match when={failure()?.kind === "forbidden" ? failure() : undefined}>
        {(reason) => (
          <EmptyState
            kind="forbidden"
            title="You may see this topic but not its partitions."
            description={reason().message}
            code={(reason() as { readonly code: string }).code}
          />
        )}
      </Match>

      {/* Not an error and not a permission problem: the deployment has not configured whatever
          answers this, so there is nothing to retry and nobody to ask for access. */}
      <Match when={failure()?.kind === "not-configured" ? failure() : undefined}>
        {(reason) => (
          <EmptyState
            kind="empty"
            title="Partitions are not reported on this deployment."
            description={reason().message}
          />
        )}
      </Match>
    </Switch>
  );
}
