/**
 * Which consumer groups read this topic, and how far behind each one is **on this topic**.
 *
 * ## The lag on this tab is not the lag on the consumer-groups screen
 *
 * A group that reads four topics has one total lag across all four, and that is the figure the
 * consumer-group list shows. It answers "is this group behind?". The figure here answers a
 * different question — "is anything behind on *this* topic?" — and on a group that reads four
 * topics the two are simply different numbers. Showing the total here would attribute another
 * topic's backlog to this one, and the operator would go looking for a problem that is not here.
 *
 * So `topicLag` is the column, `totalLag` is a note beside it on groups that read more than one
 * topic, and the two are labelled rather than left to be inferred from their size.
 *
 * ## "Dormant" is the server's word and it is not a failure
 *
 * A dormant group holds committed offsets on this topic and has no live member reading it. That is
 * exactly what a nightly batch job looks like between runs, and it is also exactly what an
 * abandoned consumer looks like — KUI cannot tell them apart and does not try. It is marked,
 * neutrally, with lag beside it, because a dormant group with large lag is the one an operator
 * wants to notice and a dormant group with none is nothing at all.
 */
import { Match, Show, Switch, createMemo } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  Button,
  DataTable,
  EmptyState,
  MISSING,
  StatusPill,
  ThresholdValue,
  formatCount,
  type Column,
  type PillTone,
  type ThresholdLevel,
} from "@kui/kernel";
import type { TopicConsumerRow } from "./data.js";

/** Why the table has no rows, when it has none. The same union `TopicPartitions` takes. */
export type ConsumersFailure =
  | {
      readonly kind: "unavailable";
      readonly message: string;
      readonly code: string;
      readonly onRetry: () => void;
    }
  | { readonly kind: "forbidden"; readonly message: string; readonly code: string };

export interface TopicConsumersProps {
  readonly rows: readonly TopicConsumerRow[];
  readonly loading?: boolean | undefined;
  readonly failure?: ConsumersFailure | undefined;
  /** Where a group's name points. A real URL, so the browser's own gestures work. */
  readonly hrefFor: (groupId: string) => string;
}

/**
 * The chip for a group's state.
 *
 * A deliberately smaller vocabulary than `feature-consumers`' `stateChip`, and duplicated rather
 * than imported. The features are separately loaded microfrontends and neither depends on the
 * other; importing across that edge would pull the whole consumer feature into this chunk to reuse
 * six lines. If a third screen needs this mapping, the answer is to move it into `@kui/kernel`
 * beside the other shared vocabularies, not to add a second cross-feature edge.
 *
 * `EMPTY` is neutral and not amber, for the reason the consumers feature gives: a group with no
 * members is the resting state of a scheduled job, and colouring it as a warning teaches operators
 * to ignore amber in the one column where amber has to mean something.
 */
export function groupStateChip(state: string): { readonly label: string; readonly tone: PillTone } {
  switch (state) {
    case "STABLE":
      return { label: "Stable", tone: "success" };
    case "EMPTY":
      return { label: "Empty", tone: "neutral" };
    case "DEAD":
      return { label: "Dead", tone: "danger" };
    case "PREPARING_REBALANCE":
    case "COMPLETING_REBALANCE":
      return { label: "Rebalancing", tone: "warning" };
    default:
      /* Kafka's own `UNKNOWN`, and anything this build has not heard of. Neutral, and named as
         what it is: a state the coordinator reported, not a judgement KUI has made about it. */
      return { label: "Unknown", tone: "neutral" };
  }
}

/** Where a lag figure stops being ordinary. The same two thresholds the consumer screens use. */
export const LAG_WARN_ABOVE = 1_000;
export const LAG_CRITICAL_ABOVE = 100_000;

/**
 * The three levels, and not five.
 *
 * An operator scanning a column sorts each cell into "fine", "look at this" and "act on this" in
 * the time it takes to scroll past. More steps are read as a gradient, which is to say as nothing.
 */
export function lagLevel(lag: number): ThresholdLevel {
  if (lag > LAG_CRITICAL_ABOVE) return "critical";
  if (lag > LAG_WARN_ABOVE) return "warning";
  return "normal";
}

export function TopicConsumers(props: TopicConsumersProps): JSX.Element {
  const behind = createMemo(
    () => props.rows.filter((row) => (row.topicLag ?? 0) > LAG_WARN_ABOVE).length,
  );

  const columns: readonly Column<TopicConsumerRow>[] = [
    {
      id: "group",
      header: "Consumer group",
      width: "36%",
      render: (row) => (
        <Show
          when={row.groupId !== ""}
          fallback={
            /* The server sent a row whose group has no id. It is kept rather than dropped —
               dropping it would quietly shorten a list somebody counts — and it says what it is
               instead of rendering a link to nowhere. */
            <span class="kui-table__cell-muted">a group KUI could not name</span>
          }
        >
          <a class="kui-table__cell-strong" href={props.hrefFor(row.groupId)} title={row.groupId}>
            {row.groupId}
          </a>
        </Show>
      ),
    },
    {
      id: "state",
      header: "State",
      width: "14%",
      render: (row) => {
        const chip = groupStateChip(row.state);
        return (
          <StatusPill tone={chip.tone} dot>
            {chip.label}
          </StatusPill>
        );
      },
    },
    {
      id: "members",
      header: "Members",
      align: "numeric",
      width: "10%",
      /* `0` is a fact here and prints as `0`: a group can hold offsets with nobody consuming, and
         that is the whole meaning of the dormant mark in the next column. */
      render: (row) => <span class="kui-table__cell-number">{formatCount(row.members)}</span>,
    },
    {
      id: "partitions",
      header: "Partitions here",
      align: "numeric",
      width: "12%",
      render: (row) => <span class="kui-table__cell-number">{formatCount(row.partitions)}</span>,
    },
    {
      id: "topicLag",
      header: "Lag on this topic",
      align: "numeric",
      render: (row) => (
        <Show
          when={row.topicLag !== null}
          fallback={
            <span class="kui-table__cell-muted">
              <span aria-hidden="true">{MISSING}</span>
              {/* A lag that could not be computed is not a group that has caught up. Saying "not
                  known" out loud is what keeps those two apart for a reader who cannot see the
                  dash. */}
              <span class="kui-visually-hidden">not known</span>
            </span>
          }
        >
          <ThresholdValue
            class="kui-table__cell-number"
            value={formatCount(row.topicLag as number)}
            level={lagLevel(row.topicLag as number)}
            announcement={(level) => (level === "critical" ? "critical lag" : "high lag")}
          />
        </Show>
      ),
    },
    {
      id: "reach",
      header: "Elsewhere",
      /* The column that stops this tab's lag being misread. A group that reads only this topic has
         nothing to say here; one that reads five says so, with its lag across all of them, so the
         figure to its left is unambiguously about this topic alone. */
      render: (row) => (
        <Show
          when={row.topics > 1}
          fallback={<span class="kui-table__cell-muted">this topic only</span>}
        >
          <span class="kui-table__cell-muted">
            reads {formatCount(row.topics)} topics
            <Show when={row.totalLag !== null}>
              {" "}
              · {formatCount(row.totalLag as number)} lag in total
            </Show>
          </span>
        </Show>
      ),
    },
    {
      id: "dormant",
      header: "",
      width: "10%",
      render: (row) => (
        <Show when={row.dormant}>
          <StatusPill
            tone="neutral"
            title="This group has committed offsets on this topic and no live member reading it."
          >
            dormant
          </StatusPill>
        </Show>
      ),
    },
  ];

  return (
    <section class="kui-topic-consumers" aria-label="Consumer groups">
      <Show when={behind() > 0}>
        <p class="kui-topic-consumers__warning" role="status">
          {formatCount(behind())} {behind() === 1 ? "group is" : "groups are"} more than{" "}
          {formatCount(LAG_WARN_ABOVE)} records behind on this topic.
        </p>
      </Show>

      <DataTable<TopicConsumerRow>
        columns={columns}
        rows={props.rows}
        rowKey={(row) => row.groupId}
        caption="Consumer groups reading this topic"
        loading={props.loading === true}
        testId="topic-consumers-table"
        empty={<ConsumersEmpty failure={props.failure} />}
      />
    </section>
  );
}

/**
 * The kinds of nothing, told apart.
 *
 * A topic nothing consumes is completely ordinary — a topic written to by one service and read by a
 * connector that is not a consumer group, or one that nothing has got round to reading yet — so the
 * empty rendering is a statement of fact and not a problem to solve. It is the *other* three that
 * must never be drawn this way: "nobody reads this topic" and "the consumer service is down" would
 * send an operator in opposite directions.
 */
function ConsumersEmpty(props: { readonly failure?: ConsumersFailure | undefined }): JSX.Element {
  const failure = (): ConsumersFailure | undefined => props.failure;
  return (
    <Switch
      fallback={
        <EmptyState
          kind="empty"
          title="No consumer group reads this topic."
          description="A group appears here as soon as something consumes from this topic and commits an offset. Anything reading without a group — a console consumer, some connectors — never appears."
        />
      }
    >
      <Match when={failure()?.kind === "unavailable" ? failure() : undefined}>
        {(reason) => (
          <EmptyState
            kind="unavailable"
            title="The consumer groups did not come back."
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
            title="You may see this topic but not who reads it."
            description={reason().message}
            code={(reason() as { readonly code: string }).code}
          />
        )}
      </Match>
    </Switch>
  );
}
