/**
 * The topic list as a grid of cards.
 *
 * Not shown in any screenshot; its composition is fixed in `research/design/SCREENS.md` §3.3 rather
 * than invented here. The rule is that it is the *same page* — same controls, same statistics, same
 * query — with the table replaced. It is not a second screen with its own behaviour.
 *
 * ## Every card carries what a column carries
 *
 * That is the whole constraint, and it is what stops the cards view becoming a prettier, less useful
 * version of the list. An operator who switches to cards and can no longer see out-of-sync replicas
 * has been given decoration in exchange for information. So each figure the table shows appears
 * here, labelled, and each one keeps the never-zero rule: a figure KUI could not read is a dash with
 * the words "not known" beside it, never a `0`.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Icon, StatusPill, Tag } from "@kui/kernel";
import { healthChip } from "./TopicPage.jsx";
import type { TopicRow } from "./types.js";

export interface TopicCardsProps {
  readonly topics: readonly TopicRow[];
  readonly onOpen: (topic: TopicRow) => void;
  readonly formatBytes: (bytes: number) => string;
}

export function TopicCards(props: TopicCardsProps): JSX.Element {
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
            <article class="kui-topic-card">
              <header class="kui-topic-card__head">
                <Icon
                  /* An internal topic is Kafka's own bookkeeping and is not a topic anybody should
                     be producing to. The padlock says so at a glance, where the word "internal" in
                     a column is something you have to go and look for. */
                  name={topic.internal ? "lock" : "topics"}
                  size="14px"
                  class="kui-topic-card__glyph"
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
              </header>

              <dl class="kui-topic-card__figures">
                <Figure label="Partitions" value={topic.partitions} />
                <Figure label="Replicas" value={topic.replicationFactor} />
                <Figure label="Records" value={topic.records} />
                <Figure
                  label="Size"
                  value={topic.bytes}
                  format={(bytes) => props.formatBytes(bytes)}
                />
              </dl>

              <Show when={topic.cleanupPolicy}>
                {(policy) => (
                  <footer class="kui-topic-card__foot">
                    <Tag>{policy()}</Tag>
                  </footer>
                )}
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
 * read it. They must not look alike, so absent is a dash with the words beside it rather than a
 * blank or a zero.
 */
function Figure(props: {
  readonly label: string;
  readonly value: number | undefined;
  readonly format?: ((value: number) => string) | undefined;
}): JSX.Element {
  return (
    <div class="kui-topic-card__figure">
      <dt>{props.label}</dt>
      <dd>
        <Show
          when={props.value !== undefined}
          fallback={
            <span class="kui-table__cell-muted">
              <span aria-hidden="true">—</span>
              <span class="kui-visually-hidden">not known</span>
            </span>
          }
        >
          {props.format === undefined
            ? (props.value as number).toLocaleString()
            : props.format(props.value as number)}
        </Show>
      </dd>
    </div>
  );
}
