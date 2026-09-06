/**
 * The cluster-wide statistics above the topic list (`SCREENS-V4.md` §4.6).
 *
 * ## The one fact this region exists to state
 *
 * The design's own capture is of a cluster of 128 topics with `orders.` typed into the search box
 * and three rows in the table — and `TOTAL TOPICS` still reads 128. That is called out in the
 * reading as the load-bearing fact of the screen, and it is the whole reason these figures come
 * from `GET …/topics/statistics` rather than from a fold over `props.topics`. A total computed from
 * the rows on screen would track the filter, and the number an operator opens this region for is
 * precisely the one that must not move when they type.
 *
 * So this component is given a document and never a row. It has no access to the page's rows at
 * all, which is the cheapest way to make the mistake unavailable rather than merely discouraged.
 *
 * ## A refused total is a sentence, not a zero and not a dash
 *
 * `partitionCount` and `sizeBytes` are each `Option` on the wire and each refuses on its own: a
 * topic the scrape could not describe removes both sums and leaves the count, and `describeLogDirs`
 * can fail on a cluster whose describe worked, which removes the size and leaves the partitions.
 * `StatTile`'s `not-measured` figure prints *words* — "not measured" — with the reason as its
 * title, because `0 B` under `TOTAL STORAGE` is the most reassuring possible rendering of the least
 * reassuring possible state, and a bare em dash there says "there is no value" when the fact is
 * "we could not add these up".
 *
 * `incompleteTopics` is what turns the absence into an explanation rather than a shrug, so it is
 * both the tile's chip and, when it is not zero, a sentence under the row.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { StatTile, formatCount, type TileFigure } from "@kui/kernel";
import { formatBytes } from "./TopicListPage.jsx";
import type { TopicStatistics } from "./data.js";

export interface TopicStatisticsRegionProps {
  /**
   * The document, or `undefined` when there is not one yet or the section refused.
   *
   * Deliberately not a `TopicStatistics` with zeroes in it. A caller that cannot supply the
   * document has nothing to say about the cluster, and the tiles say that rather than drawing a
   * cluster with no topics in it.
   */
  readonly statistics?: TopicStatistics | undefined;
  readonly loading?: boolean | undefined;
  /**
   * Why there is no document, in one sentence. Shown on the tiles as the reason they are absent,
   * so a reader is never left with three blanks and no account of them.
   */
  readonly unavailableReason?: string | undefined;
}

/** Why a sum is missing, given how many topics the scrape could not describe. */
export function refusalReason(incompleteTopics: number): string {
  if (incompleteTopics <= 0) {
    return "The cluster did not report this total, so KUI has nothing to add up.";
  }
  const noun = incompleteTopics === 1 ? "topic" : "topics";
  const topics = `${formatCount(incompleteTopics)} ${noun}`;
  // A sum over the topics that *did* answer is a smaller number wearing the confidence of a
  // complete one, and it is the one an operator would plan capacity against.
  return `${topics} could not be described, so this total is withheld rather than counted short.`;
}

/** A figure that is present, or the sentence saying why it is not. */
function sumFigure(
  value: number | undefined,
  format: (value: number) => string,
  incompleteTopics: number,
): TileFigure {
  if (value === undefined) return { kind: "not-measured", why: refusalReason(incompleteTopics) };
  return { kind: "value", text: format(value) };
}

export function TopicStatisticsRegion(props: TopicStatisticsRegionProps): JSX.Element {
  /* One accessor read by all four tiles, so a document that arrives replaces four figures in one
     pass rather than four times. */
  const document = (): TopicStatistics | undefined => props.statistics;

  const pending = (): boolean => props.loading === true && document() === undefined;

  /** The tile's figure when there is no document at all: pending, or the reason there is none. */
  const absent = (): TileFigure =>
    pending()
      ? { kind: "pending" }
      : {
          kind: "not-measured",
          why:
            props.unavailableReason ??
            "KUI has not been able to read this cluster's topic totals.",
        };

  return (
    <section class="kui-topic-stats" aria-label="Topics on this cluster">
      <div class="kui-topic-stats__tiles">
        <StatTile
          label="TOTAL TOPICS"
          icon="topics"
          tone="primary"
          figure={
            document() === undefined
              ? absent()
              : /* Every topic the scrape learned of, including the ones it could not describe.
                   It is the one figure here that is never withheld, because it comes from the
                   listing rather than from a description of each topic. */
                { kind: "value", text: formatCount(document()?.topics ?? 0) }
          }
          chip={
            document() === undefined || (document()?.incompleteTopics ?? 0) === 0
              ? { text: "across the whole cluster" }
              : {
                  text: `${formatCount(document()?.incompleteTopics ?? 0)} could not be described`,
                  tone: "attention",
                }
          }
          testId="topic-stat-topics"
        />

        <StatTile
          label="TOTAL PARTITIONS"
          icon="partitions"
          tone="accent"
          figure={
            document() === undefined
              ? absent()
              : sumFigure(
                  document()?.partitions,
                  formatCount,
                  document()?.incompleteTopics ?? 0,
                )
          }
          chip={{ text: "summed over every topic" }}
          testId="topic-stat-partitions"
        />

        <StatTile
          label="TOTAL STORAGE"
          icon="disk"
          tone="warning"
          figure={
            document() === undefined
              ? absent()
              : sumFigure(document()?.bytes, formatBytes, document()?.incompleteTopics ?? 0)
          }
          chip={{ text: "on disk, before replication" }}
          testId="topic-stat-storage"
        />
      </div>

      {/*
        The design draws a fourth tile — `AVG REPLICATION`, with `3 topics at RF 2` beside it — and
        it is deliberately not here. There is no cluster-wide replication figure on the wire, and
        the only way to draw one would be to average the replication factors of the rows on this
        page. That number tracks the search box, which is exactly the thing this region exists not
        to do: it would sit in a row of cluster totals, look like one, and quietly answer a
        different question every time somebody typed. When the wire carries the figure the tile can
        be built; until then the three that are true are better than four of which one is not.
      */}

      <Show when={document() !== undefined && (document()?.incompleteTopics ?? 0) > 0}>
        <p class="kui-topic-stats__incomplete" role="status">
          {formatCount(document()?.incompleteTopics ?? 0)}{" "}
          {(document()?.incompleteTopics ?? 0) === 1 ? "topic" : "topics"} on this cluster could not
          be described, so the sums above them are withheld rather than counted short.
        </p>
      </Show>
    </section>
  );
}
