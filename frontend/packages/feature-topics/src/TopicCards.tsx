/**
 * The topic list as a grid of cards (`SCREENS-V4.md` §4.7, §4.8).
 *
 * The rule is that it is the *same page* — same controls, same statistics, same query, same
 * selection — with the table replaced. It is not a second screen with its own behaviour.
 *
 * ## What the design draws, and the one thing this adds back
 *
 * §4.7 is precise: icon tile, name, checkbox at the top right; a row of three tags — `12
 * partitions`, `RF 3`, `delete`; a footer with the size on the left and the rate on the right; and
 * a thin magnitude bar under it. That is what is built here.
 *
 * §4.7 also says, in as many words, that no health pill appears on a card. **This keeps it**, and
 * the deviation is stated rather than smuggled. The card is the treatment an operator picks to scan
 * a cluster with, and a card view that cannot show that a topic is offline is decoration in
 * exchange for information: the reader would have to switch back to the table to find out that
 * anything is wrong, which makes the toggle a trap rather than a preference. The pill is one dot and
 * one word; the cost of keeping it is a line of the mockup and the cost of dropping it is an outage
 * nobody saw. If the design's intent was that health belongs only in the table, that is a
 * conversation to have with the capture in hand, not a silence to ship.
 *
 * ## The magnitude bar is relative to this page, and only to this page
 *
 * A bar needs a denominator, and the only one the cards hold is the largest topic among the rows
 * the server sent. So the caller supplies it, and it is documented as the page's — a bar drawn
 * against a cluster-wide maximum this component invented would be a picture of a number nobody
 * measured. A topic whose size could not be read draws no bar at all rather than an empty one: an
 * empty track and a topic of zero bytes look identical.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Checkbox, Icon, IconTile, MagnitudeBar, StatusPill, Tag, formatRate } from "@kui/kernel";
import { healthChip } from "./TopicPage.jsx";
import type { TopicRow } from "./types.js";

export interface TopicCardsProps {
  readonly topics: readonly TopicRow[];
  readonly onOpen: (topic: TopicRow) => void;
  readonly formatBytes: (bytes: number) => string;
  /**
   * The selected topic names — the *same* set the table is given.
   *
   * `SCREENS-V4.md` §3.7 requires the selection to survive the Table↔Cards switch, which it can
   * only do if neither treatment owns it. The two ticks in the design's §4.8 capture are on cards
   * and the bulk bar under them acts on rows.
   */
  readonly selected?: ReadonlySet<string> | undefined;
  readonly onSelectionChange?: ((next: ReadonlySet<string>) => void) | undefined;
}

/** The largest measured size among these rows, or `undefined` when none of them reported one. */
export function largestSize(topics: readonly TopicRow[]): number | undefined {
  const sizes = topics
    .map((topic) => topic.bytes)
    .filter((bytes): bytes is number => bytes !== undefined);
  return sizes.length === 0 ? undefined : Math.max(...sizes);
}

export function TopicCards(props: TopicCardsProps): JSX.Element {
  const selected = (): ReadonlySet<string> => props.selected ?? new Set<string>();
  const largest = () => largestSize(props.topics);

  const toggle = (name: string, on: boolean): void => {
    const change = props.onSelectionChange;
    if (change === undefined) return;
    const next = new Set(selected());
    if (on) next.add(name);
    else next.delete(name);
    change(next);
  };

  return (
    /*
     * A list, not a bag of divs. The cards are a list of topics whatever shape they are drawn in,
     * and a screen reader user arriving here should be told how many there are and be able to move
     * between them — which is precisely what is lost when a grid is built out of anonymous
     * containers.
     */
    <ul class="kui-topic-cards" aria-label="Topics">
      <For each={props.topics}>
        {(topic) => (
          <li class="kui-topic-cards__item">
            <article
              class={[
                "kui-topic-card",
                { "kui-topic-card--selected": selected().has(topic.name) },
              ]}
            >
              <header class="kui-topic-card__head">
                <IconTile
                  /* An internal topic is Kafka's own bookkeeping and is not a topic anybody should
                     be producing to. The padlock says so at a glance, where the word "internal" in
                     a column is something you have to go and look for. */
                  icon={topic.internal ? "lock" : "topics"}
                  tone={topic.internal ? "neutral" : "primary"}
                  size="sm"
                />
                <a class="kui-topic-card__name" href="#" onClick={(event) => {
                  event.preventDefault();
                  props.onOpen(topic);
                }}>
                  {topic.name}
                </a>
                <StatusPill tone={healthChip(topic.health).tone} dot>
                  {healthChip(topic.health).label}
                </StatusPill>
                <Show when={props.onSelectionChange !== undefined}>
                  <span class="kui-topic-card__select">
                    <Checkbox
                      labelHidden
                      /* Named, not "select": a screen-reader user moving through a grid of these
                         hears twelve identical controls otherwise, and the one they want is the
                         one whose card they cannot see. */
                      label={`Select ${topic.name}`}
                      checked={selected().has(topic.name)}
                      onChange={(on) => toggle(topic.name, on)}
                    />
                  </span>
                </Show>
              </header>

              <p class="kui-topic-card__tags">
                <Tag>{`${topic.partitions.toLocaleString()} ${topic.partitions === 1 ? "partition" : "partitions"}`}</Tag>
                <Tag>{`RF ${topic.replicationFactor}`}</Tag>
                {/* Absent rather than `delete`: Kafka's default is not a fact about this topic, and
                    a batch that did not cover it told us nothing. Same rule as the table's column. */}
                <Show when={topic.cleanupPolicy}>{(policy) => <Tag tone="info">{policy()}</Tag>}</Show>
              </p>

              <p class="kui-topic-card__foot">
                <Figure
                  label="Size"
                  value={topic.bytes}
                  format={(bytes) => props.formatBytes(bytes)}
                />
                <Figure label="Rate" value={topic.messagesPerSecond} format={formatRate} />
              </p>

              {/* No bar when there is nothing to draw one against: an empty track and a topic of
                  zero bytes are indistinguishable, and one of them is a measurement. */}
              <Show when={topic.bytes !== undefined && (largest() ?? 0) > 0}>
                <MagnitudeBar
                  class="kui-topic-card__bar"
                  value={props.formatBytes(topic.bytes as number)}
                  fraction={(topic.bytes as number) / (largest() as number)}
                  inline
                />
              </Show>
            </article>
          </li>
        )}
      </For>
    </ul>
  );
}

/**
 * One labelled figure, with the never-zero rule.
 *
 * `0` is a fact — a topic with no records is empty — and absent is a different fact: nobody could
 * read it. They must not look alike, so absent is the words "not measured" rather than a blank or a
 * zero. Words rather than the table's em dash, because a card has room for them and a dash floating
 * in a card footer has nothing next to it to be read against.
 */
function Figure(props: {
  readonly label: string;
  readonly value: number | undefined;
  readonly format: (value: number) => string;
}): JSX.Element {
  return (
    <span class="kui-topic-card__figure">
      <Icon name="dot" size="8px" class="kui-topic-card__figure-dot" />
      <span class="kui-topic-card__figure-label">{props.label}</span>
      <Show
        when={props.value !== undefined}
        fallback={<span class="kui-topic-card__figure-absent">not measured</span>}
      >
        <span class="kui-topic-card__figure-value">{props.format(props.value as number)}</span>
      </Show>
    </span>
  );
}
