/**
 * The Overview tab's body (`SCREENS-V4.md` §4.9).
 *
 * ## What was here before
 *
 * Nothing. `TopicsRoute` declared a tab with `id: "overview"`, the strip opened it by default, and
 * the page had no `<Show when={tab() === "overview"}>` at all — so the first thing anybody saw
 * after clicking a topic was a header, a tab strip and an empty box. Every other tab drew.
 *
 * ## Four figures, and each of them can be absent
 *
 * The design's four tiles are `PARTITIONS`, `SIZE ON DISK`, `PRODUCE RATE` and `CONSUMER GROUPS`,
 * and three of the four are `Option` on the wire. `sizeBytes` is `null` on a cluster whose
 * `describeLogDirs` did not answer — it is `null` on the quickstart's single broker today —
 * `produceRate` is `null` until two scrapes exist, and the group count is absent when the
 * consumer service could not be asked. Each says so in words rather than showing `0`: a topic with
 * no bytes on disk, a topic nobody is producing to and a topic nothing reads are all real states,
 * and none of them may be drawn the way "we could not find out" is drawn.
 *
 * The design's captions — `min.isr 2`, `retention 7 days`, `avg message 1.1 KB` — are not written
 * here. Every one of them comes from the topic's configuration, which is the Settings tab's own
 * request, and fetching thirty-three configuration keys so that a caption under a tile can be
 * fuller is the shape of request this page was built to avoid. The captions that are here are the
 * facts this document already carries.
 *
 * ## The partition table is the overview's, not the tab's
 *
 * `fetchTopicOverview` already carries the partitions, so this draws them rather than issuing a
 * second request for the same rows. The gateway caps that list at 500, which is why the Partitions
 * tab exists and reads the uncapped endpoint instead — and why the note under this table points at
 * it rather than pretending the table is the topic.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { StatTile, formatCount, formatRate, type TileFigure } from "@kui/kernel";
import { TopicPartitions } from "./TopicPartitions.jsx";
import { formatBytes } from "./TopicListPage.jsx";
import { healthChip } from "./TopicPage.jsx";
import type { TopicOverview } from "./data.js";

/** How many partitions the overview's list is capped at by the gateway. See the header. */
export const OVERVIEW_PARTITION_CAP = 500;

export interface TopicOverviewTabProps {
  /** `undefined` while the overview is still coming, or when it did not come at all. */
  readonly overview?: TopicOverview | undefined;
  readonly loading?: boolean | undefined;
  /** Where the Partitions tab lives, for the note under a capped table. */
  readonly partitionsHref: string;
}

/** A figure, or the sentence saying why there is not one. Never a zero standing in for absence. */
function measured(
  value: number | undefined,
  format: (value: number) => string,
  why: string,
): TileFigure {
  if (value === undefined) return { kind: "not-measured", why };
  return { kind: "value", text: format(value) };
}

export function TopicOverviewTab(props: TopicOverviewTabProps): JSX.Element {
  const topic = () => props.overview?.topic;
  const partitions = () => props.overview?.partitions ?? [];

  /** Pending only while there is genuinely nothing yet; an arrived overview is never "pending". */
  const pending = (): boolean => props.loading === true && props.overview === undefined;

  const figureOf = (
    value: number | undefined,
    format: (value: number) => string,
    why: string,
  ): TileFigure => (pending() ? { kind: "pending" } : measured(value, format, why));

  return (
    <section class="kui-topic-overview" aria-label="Overview">
      <div class="kui-topic-overview__tiles">
        <StatTile
          label="PARTITIONS"
          icon="partitions"
          tone="primary"
          figure={figureOf(
            topic()?.partitions,
            formatCount,
            "KUI could not describe this topic, so it does not know how many partitions it has.",
          )}
          chip={
            topic() === undefined
              ? undefined
              : { text: `replication factor ${topic()?.replicationFactor}` }
          }
          testId="topic-overview-partitions"
        />

        <StatTile
          label="SIZE ON DISK"
          icon="disk"
          tone="warning"
          figure={figureOf(
            topic()?.bytes,
            formatBytes,
            "This cluster did not report a size for this topic. Kafka answers per log " +
              "directory, and a broker that did not describe its own leaves the topic with no " +
              "honest total.",
          )}
          chip={{ text: "before replication" }}
          testId="topic-overview-size"
        />

        <StatTile
          label="PRODUCE RATE"
          icon="stream"
          tone="accent"
          figure={figureOf(
            topic()?.messagesPerSecond,
            formatRate,
            "The rate is the difference between two scrapes. A topic KUI has read once has no " +
              "rate yet, and neither has one whose partitions changed since the last pass.",
          )}
          chip={{ text: "records per second" }}
          testId="topic-overview-rate"
        />

        <StatTile
          label="CONSUMER GROUPS"
          icon="consumers"
          tone="success"
          figure={figureOf(
            props.overview?.consumerGroups,
            formatCount,
            "The consumer service did not answer for this topic, so KUI does not know what " +
              "reads it. That is not the same as nothing reading it.",
          )}
          chip={
            topic() === undefined
              ? undefined
              : { text: healthChip(topic()?.health ?? "unknown").label }
          }
          testId="topic-overview-groups"
        />
      </div>

      {/* The same component the Partitions tab draws, without its `Add partitions` control: growing
          a topic is a decision, and offering it twice on one page is how it gets taken twice. */}
      <TopicPartitions partitions={partitions()} loading={props.loading === true} />

      <Show when={partitions().length >= OVERVIEW_PARTITION_CAP}>
        <p class="kui-topic-overview__capped" role="status">
          {/* The one thing this table can be wrong about, said out loud. The overview's partition
              list stops at {OVERVIEW_PARTITION_CAP}, and a reader who concluded that partition 700
              does not exist would have concluded it from a table that never said it was short. */}
          This table stops at {formatCount(OVERVIEW_PARTITION_CAP)} partitions.{" "}
          <a href={props.partitionsHref}>The Partitions tab</a> reads them all.
        </p>
      </Show>
    </section>
  );
}
