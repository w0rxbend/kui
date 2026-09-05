/**
 * Every cluster KUI is configured to talk to.
 *
 * The design does not draw this screen. It draws the drawer's cluster status card (`01`) — a name,
 * a health dot and a version — and the consumer table. This is those two things: one row per
 * cluster, in the consumer table's ruled treatment, with the status card's dot-name-version reading
 * as the first cell.
 *
 * ## Every column here can be filled
 *
 * The screen this replaces carried columns for things KUI does not have on this endpoint, and they
 * were an em dash on every row of every deployment. They are gone. What is left — health, version,
 * brokers, topics, partitions, under-replicated — is either a number the cluster reports or an
 * explicit "we could not read it", and both are worth a reader's eye.
 *
 * Under-replicated is the exception worth naming: it is `0` on a healthy cluster, every row, all
 * day. It stays, because a zero there is the *answer to the question the operator came with*, and a
 * column of zeroes that turns amber is the cheapest possible alarm. That is the difference between
 * a column that is always empty and a column that is usually zero.
 */

import { Show, createMemo } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  Button,
  Card,
  DataTable,
  EmptyState,
  MISSING,
  PageHeader,
  StatusPill,
  ThresholdValue,
  formatCount,
  type Column,
} from "@kui/kernel";
import { healthLabel, healthTone, type ClusterSummary } from "./model.js";

export interface ClusterListProps {
  readonly clusters: readonly ClusterSummary[];
  readonly loading?: boolean | undefined;
  readonly failure?: { readonly message: string; readonly code: string; readonly onRetry: () => void } | undefined;
  readonly hrefFor: (clusterId: string) => string;
  readonly onOpen?: ((clusterId: string) => void) | undefined;
}

export function ClusterList(props: ClusterListProps): JSX.Element {
  /**
   * The line under the title. It counts what is on screen and says what is wrong with it, and the
   * aside appears only on the branch where nothing is.
   */
  const voice = createMemo(() => {
    if (props.failure !== undefined) return "The cluster list is unavailable.";
    const total = props.clusters.length;
    const unwell = props.clusters.filter((one) => one.health !== "healthy").length;
    if (total === 0) return "No clusters configured yet.";
    if (unwell === 0) {
      return `${formatCount(total)} ${total === 1 ? "cluster" : "clusters"}, all answering. Nothing to do here.`;
    }
    return `${formatCount(total)} ${total === 1 ? "cluster" : "clusters"}. ${formatCount(unwell)} ${unwell === 1 ? "is" : "are"} not healthy.`;
  });

  const columns: readonly Column<ClusterSummary>[] = [
    {
      id: "name",
      header: "Cluster",
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
            {row.name}
          </a>
          <Show when={row.readOnly}>
            <StatusPill tone="neutral" title="KUI will refuse every mutation on this cluster.">
              read-only
            </StatusPill>
          </Show>
        </span>
      ),
    },
    {
      id: "health",
      header: "Health",
      width: "9rem",
      // The dot in the first cell is decoration; this is the word, and the word is what a screen
      // reader gets and what a colour-blind reader reads.
      render: (row) => <StatusPill tone={healthTone(row.health)}>{healthLabel(row.health)}</StatusPill>,
    },
    {
      id: "version",
      header: "Version",
      width: "8rem",
      render: (row) => (row.version === null ? <Unknown what="version" /> : <span class="kui-brk-mono">{row.version}</span>),
    },
    {
      id: "brokers",
      header: "Brokers",
      align: "numeric",
      width: "8rem",
      // `2/3` rather than `2`: on a cluster with a broker down, the denominator is the whole story.
      render: (row) =>
        row.brokersOnline === null || row.brokersTotal === null ? (
          <Unknown what="broker count" />
        ) : (
          <span class={row.brokersOnline < row.brokersTotal ? "kui-brk-fraction kui-brk-fraction--short" : "kui-brk-fraction"}>
            {`${formatCount(row.brokersOnline)}/${formatCount(row.brokersTotal)}`}
          </span>
        ),
    },
    { id: "topics", header: "Topics", align: "numeric", width: "7rem", render: (row) => (row.topics === null ? <Unknown what="topic count" /> : formatCount(row.topics)) },
    {
      id: "partitions",
      header: "Partitions",
      align: "numeric",
      width: "8rem",
      render: (row) => (row.partitions === null ? <Unknown what="partition count" /> : formatCount(row.partitions)),
    },
    {
      id: "underReplicated",
      header: "Under-replicated",
      align: "numeric",
      width: "10rem",
      render: (row) =>
        row.underReplicatedPartitions === null ? (
          <Unknown what="under-replicated partition count" />
        ) : (
          <ThresholdValue
            value={formatCount(row.underReplicatedPartitions)}
            // Any under-replication at all is worth an operator's attention, so the warning
            // threshold here is one rather than a round number: this is not a quantity that has a
            // comfortable range.
            level={row.underReplicatedPartitions === 0 ? "normal" : "critical"}
            announcement={() => "partitions under-replicated"}
          />
        ),
    },
  ];

  return (
    <section class="kui-brk-page" data-testid="clusters">
      <PageHeader title="Clusters" voice={voice()} testId="clusters-head" />
      <Show
        when={props.failure === undefined}
        fallback={
          <Card
            title="Clusters"
            state="unavailable"
            message={props.failure?.message}
            description="KUI could not read its own cluster configuration."
            code={props.failure?.code}
            stateAction={
              <Button variant="secondary" icon="refresh" onClick={() => props.failure?.onRetry()}>
                Retry
              </Button>
            }
            testId="clusters-card"
          />
        }
      >
        <DataTable<ClusterSummary>
          caption="Clusters KUI is configured to talk to"
          columns={columns}
          rows={props.clusters}
          rowKey={(row) => row.id}
          loading={props.loading}
          onRowClick={props.onOpen === undefined ? undefined : (row) => props.onOpen?.(row.id)}
          testId="clusters-table"
          empty={
            <EmptyState
              kind="empty"
              title="No clusters configured."
              description="Point KUI at a Kafka cluster in its configuration file, and it appears here."
            />
          }
        />
      </Show>
    </section>
  );
}

/** An em dash with the reason. Never a blank cell, and never a zero. */
function Unknown(props: { readonly what: string }): JSX.Element {
  return (
    <span class="kui-brk-missing" title={`KUI could not read this cluster's ${props.what}.`}>
      <span aria-hidden="true">{MISSING}</span>
      <span class="kui-visually-hidden">{`${props.what} unavailable`}</span>
    </span>
  );
}
