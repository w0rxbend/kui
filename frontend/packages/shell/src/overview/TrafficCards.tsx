/**
 * The Traffic tab's last row (`SCREENS-V4.md` §4.2): request handlers, top producers, and the
 * record-size card that is not a histogram.
 *
 * ## Three cards, and the design named a figure a broker does not publish in each of them
 *
 * This file is where wave 5's rule 7 lands on the screen. ADR-052 took each decision in the open;
 * `metrics.ts` carries the wire's half of it; these three components draw what was decided:
 *
 *  - **Request handlers** (§3.4) draws three rings, `71% NETWORK IDLE`, `64% IO IDLE` and
 *    `38% PURGATORY`. The first two are ratios and are rings. The third is not a percentage of
 *    anything — `DelayedOperationPurgatory` publishes a queue *length* — so it is drawn as a count
 *    with its unit, and the card says in words why one tile in a row of rings is not one. A length
 *    divided by an invented ceiling would have drawn beautifully and meant nothing.
 *  - **Top producers** (§4) is titled `Top producers · client.id` in the design, and a broker
 *    publishes no per-`client.id` byte rate unless quotas are configured. So the **title comes from
 *    the answer**: the server sends `measuredBy`, `producerBoard` reads it, and the heading says the
 *    same word. A tile labelled `client.id` over a topic name is exactly the defect the rule names.
 *    The ranking also leaves out Kafka's own `__` topics, which would otherwise take the top row on
 *    every idle cluster, and the card says how many were left out rather than showing a short list.
 *  - **Message size distribution** (§3.5) draws twelve buckets and three percentile chips. Kafka
 *    publishes a mean — bytes-in over messages-in — and no distribution at all, so this card prints
 *    the mean and says the distribution is not measured. It draws **no axis**: twelve buckets
 *    assembled from one number is a picture of an assumption.
 */

import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";

import {
  Card,
  Monogram,
  ProgressBar,
  RingGauge,
  formatBytes,
  formatCount,
  type Fetched,
} from "@kui/kernel";

import { MetricAbsence, NotMeasured } from "./NotMeasured.jsx";
import {
  handlerPanel,
  producerBoard,
  recordSizeReadout,
  type HandlerDocument,
  type HandlerGauge,
  type ProducerBoard,
  type ProducerDocument,
  type RecordSizeDocument,
} from "./metrics.js";

/* --- Request handlers --------------------------------------------------------------------------- */

export const HANDLERS_NOUN = "this cluster's request handlers";

/** A source that answered with no readings at all: real, and not the same as no source. */
export const NO_HANDLER_READINGS =
  "The metrics source answered and served no request-handler readings, so there is nothing to " +
  "draw. Widening the exporter's ruleset is what would fill this card.";

export function RequestHandlersCard(props: { readonly state: Fetched<HandlerDocument> }): JSX.Element {
  const panel = () => {
    const state = props.state;
    return state.kind === "ready" || state.kind === "stale" ? handlerPanel(state.value) : undefined;
  };

  return (
    <Card
      title="Request handlers"
      icon="stream"
      testId="panel-request-handlers"
      {...failure(props.state)}
      caption={captionOf(props.state, panel()?.caption)}
    >
      <Show
        when={panel()}
        fallback={
          <MetricAbsence state={props.state} noun={HANDLERS_NOUN} testId="handlers-not-measured" />
        }
      >
        {(built) => (
          <Show
            when={built().gauges.length > 0}
            fallback={<NotMeasured why={NO_HANDLER_READINGS} testId="handlers-empty" />}
          >
            <ul class="kui-handlers">
              <For each={built().gauges}>{(gauge) => <HandlerTile gauge={gauge} />}</For>
            </ul>
          </Show>
        )}
      </Show>
    </Card>
  );
}

/**
 * One sub-tile, and the two shapes it has.
 *
 * A ratio is a ring, drawn with the direction the wire stated — `RingGauge` refuses to guess which
 * end of a scale is good, and the design's own card puts a green 64% beside an amber 38%. A count
 * is a figure and its unit and **no ring at all**: a ring needs a domain, this quantity has no
 * ceiling, and a ring drawn over an invented one is a fabricated percentage.
 *
 * An unmeasured ratio still draws its tile — `RingGauge` paints the plain track and an em dash for
 * an `undefined` value, which is §3.4's own absent rule — so a reader can see *which* of the
 * readings is missing rather than counting the tiles that are there.
 */
function HandlerTile(props: { readonly gauge: HandlerGauge }): JSX.Element {
  return (
    <li class="kui-handlers__tile" data-testid={`handler-${props.gauge.id}`}>
      <Show
        when={props.gauge.kind === "ratio"}
        fallback={
          <p class="kui-handlers__count" role="img" aria-label={countLabel(props.gauge)}>
            <span class="kui-handlers__count-value">
              {props.gauge.count === undefined ? "—" : formatCount(props.gauge.count)}
            </span>
            <span class="kui-handlers__count-unit">{props.gauge.unit}</span>
            <span class="kui-handlers__count-caption">{props.gauge.caption}</span>
          </p>
        }
      >
        <RingGauge
          value={props.gauge.percent}
          goodDirection={props.gauge.goodDirection}
          caption={props.gauge.caption}
          diameter={72}
          strokeWidth={7}
        />
      </Show>
    </li>
  );
}

/**
 * The accessible name for a count tile.
 *
 * Composed rather than left to the flow for the reason `RingGauge`'s header gives about its own:
 * three tiles side by side leave a screen reader with loose numbers and loose captions and no way
 * to tell which belongs to which. The em dash never reaches it — `—` is announced as "dash" or as
 * nothing depending on the reader, which is a rendering of "not measured" that means neither.
 */
function countLabel(gauge: HandlerGauge): string {
  const unit = gauge.unit === undefined || gauge.unit.length === 0 ? "" : ` ${gauge.unit}`;
  return gauge.count === undefined
    ? `${gauge.caption}: not measured`
    : `${gauge.caption}: ${formatCount(gauge.count)}${unit}`;
}

/* --- Top producers ------------------------------------------------------------------------------ */

export const PRODUCERS_NOUN = "which producers are writing to this cluster";

/**
 * A threshold no share can reach, which is how this card says "there is no threshold here".
 *
 * `levelFor` is `percent >= warn`, so a limit above 100 is never met by a bar whose maximum is the
 * largest entry. Spelled as a constant rather than as `Infinity` so the intent survives a reader
 * who is checking why the busiest producer is not amber.
 */
const PRODUCER_NO_RAMP = 101;

/** A source that answered and named nobody. Real, and not the same as no source. */
export const NO_PRODUCERS =
  "The metrics source answered and named no producers, so there is nothing to rank. A cluster " +
  "nothing is writing to reads exactly like this.";

/**
 * The card's title, from the answer.
 *
 * §4 draws `Top producers · client.id`, and this heading says whatever the server actually named
 * its rows. That is the whole of wave 5's rule 7 in one function: the design's word may not be
 * printed over a different measurement, so the word is read from the data rather than written here.
 */
export function producersTitle(board: ProducerBoard | undefined): string {
  return board === undefined ? "Top producers" : `Top producers · ${board.subject}`;
}

export function TopProducersCard(props: { readonly state: Fetched<ProducerDocument> }): JSX.Element {
  const board = (): ProducerBoard | undefined => {
    const state = props.state;
    return state.kind === "ready" || state.kind === "stale" ? producerBoard(state.value) : undefined;
  };

  /**
   * The denominator every bar on this card is drawn against: the **largest** rate on it.
   *
   * A magnitude list compares its rows to each other, so the busiest producer fills its track and
   * everything else is drawn as a fraction of it — which is the comparison the card exists to make.
   * `Math.min` here would peg every row but the quietest at a full bar and destroy it, and nothing
   * asserts a bar's width unless a case reads one, which is why `producers-empty` is not the only
   * rendering case this card has.
   */
  const ceiling = (): number | undefined => {
    const rates = (board()?.rows ?? [])
      .map((row) => row.bytesPerSecond)
      .filter((rate): rate is number => rate !== undefined);
    return rates.length === 0 ? undefined : Math.max(...rates);
  };

  return (
    <Card
      title={producersTitle(board())}
      icon="person"
      testId="panel-top-producers"
      {...failure(props.state)}
      caption={captionOf(props.state, undefined)}
    >
      <Show
        when={board()}
        fallback={
          <MetricAbsence state={props.state} noun={PRODUCERS_NOUN} testId="producers-not-measured" />
        }
      >
        {(built) => (
          <Show
            when={built().rows.length > 0}
            fallback={<NotMeasured why={NO_PRODUCERS} testId="producers-empty" />}
          >
            {/*
             * This card's rows lead with a `Monogram`, which is why they are written here rather
             * than handed to `MagnitudeBarList`: that component's `label` is a string, and §4's row
             * is a tile, an identifier and a figure. The bar underneath is the kernel's, so the
             * guarded denominator and the "an unknown value never draws as a full track" rule are
             * still the shared ones rather than arithmetic repeated in the shell.
             */}
            <ul class="kui-producers">
              <For each={built().rows}>
                {(row) => (
                  <li class="kui-producers__row">
                    <span class="kui-producers__head">
                      <Monogram id={row.id} size="sm" />
                      <span class="kui-producers__name">{row.id}</span>
                    </span>
                    <ProgressBar
                      label={`${row.id} write rate`}
                      value={row.bytesPerSecond}
                      max={ceiling()}
                      /* No ramp. `ProgressBar` defaults to the product's disk thresholds — 75 and
                         90 per cent, where *high* is bad — and a producer's share of the busiest
                         producer is not that kind of quantity: nobody is in trouble for writing the
                         most. Left at the default, the top row of every healthy cluster drew red. */
                      thresholds={{ warn: PRODUCER_NO_RAMP, critical: PRODUCER_NO_RAMP }}
                      valueText={
                        row.bytesPerSecond === undefined
                          ? "not measured"
                          : `${formatBytes(row.bytesPerSecond)}/s`
                      }
                    />
                  </li>
                )}
              </For>
            </ul>
          </Show>
        )}
      </Show>
      {/* Said in the card rather than left out of it. The ranking is not the exporter's whole list,
          and a reader comparing this card against the exporter has to be able to see the difference
          was made on purpose. The sentence appears only when there is a figure behind it — the
          count is the server's own, never a length this browser subtracted. */}
      <Show when={excludedSentence(board())}>
        {(sentence) => (
          <p class="kui-producers__excluded" role="note" data-testid="producers-excluded">
            {sentence()}
          </p>
        )}
      </Show>
    </Card>
  );
}

/**
 * What the card says about the rows it did not rank, or nothing at all.
 *
 * `undefined` at zero rather than "0 internal topics were excluded", which is the never-a-zero rule
 * the whole dashboard keeps: a sentence about an omission that did not happen is noise on a card
 * with four rows in it.
 */
export function excludedSentence(board: ProducerBoard | undefined): string | undefined {
  const excluded = board?.internalTopicsExcluded ?? 0;
  if (excluded === 0) return undefined;
  return (
    `${excluded} of Kafka's own internal ${excluded === 1 ? "topic is" : "topics are"} not ranked ` +
    `here: ${excluded === 1 ? "it carries" : "they carry"} the cluster's own bookkeeping rather ` +
    `than anybody's traffic.`
  );
}

/* --- Message size ------------------------------------------------------------------------------- */

export const RECORD_SIZE_NOUN = "this cluster's record sizes";

/**
 * The sentence beside the mean, and it is the point of the card.
 *
 * §3.5 draws twelve buckets and `p50 · 1.1 KB` / `p99 · 18 KB` / `max · 0.9 MB`. A broker publishes
 * none of them: `BytesInPerSec` over `MessagesInPerSec` is a **mean**, and a mean cannot be spread
 * into a distribution without inventing its shape. So the card prints what exists and names what
 * does not, which is the whole reason it is not drawn as an empty `Histogram`.
 */
export const NO_DISTRIBUTION =
  "A broker publishes no record-size distribution — only the mean above, as bytes in over messages " +
  "in — so the percentile chips and the twelve buckets the design draws are not measured here and " +
  "are not drawn from the mean.";

export function RecordSizeCard(props: { readonly state: Fetched<RecordSizeDocument> }): JSX.Element {
  const readout = () => {
    const state = props.state;
    return state.kind === "ready" || state.kind === "stale"
      ? recordSizeReadout(state.value)
      : undefined;
  };

  return (
    <Card
      title="Message size distribution"
      icon="chart-bars"
      testId="panel-message-sizes"
      {...failure(props.state)}
      caption={captionOf(props.state, readout()?.window)}
    >
      <Show
        when={readout()}
        fallback={
          <MetricAbsence state={props.state} noun={RECORD_SIZE_NOUN} testId="record-size-not-measured" />
        }
      >
        {(built) => (
          <div class="kui-record-size">
            {/* The figure, or words. Never an em dash: a mean the exporter did not serve is a
                sentence, and this is the one card on the tab where a dash beside "MEAN RECORD"
                would read as a distribution that failed to draw. */}
            <p class="kui-record-size__figure" data-testid="record-size-mean">
              <Show
                when={built().meanBytes !== undefined}
                fallback={<span class="kui-record-size__absent">not measured</span>}
              >
                <span class="kui-record-size__value">{formatBytes(built().meanBytes as number)}</span>
              </Show>
              <span class="kui-record-size__label">MEAN RECORD</span>
            </p>
            <NotMeasured why={NO_DISTRIBUTION} testId="record-size-no-distribution" />
          </div>
        )}
      </Show>
    </Card>
  );
}

/* --- Shared ------------------------------------------------------------------------------------- */

/**
 * The three props a failed read puts on a card, decided once for all three of them.
 *
 * They are one decision and not three, because they only work together: `state="unavailable"` with
 * no `message` draws a card with an empty body, and a `message` with no `code` leaves an operator a
 * sentence they cannot quote in a support conversation. Written out per card, the three could be —
 * and were — replaced by a bare `state="ready"` with the whole suite green, after which a gateway
 * error drew a healthy-looking card with no message, no `KUI-` code and no Retry.
 *
 * Returned as an object rather than as three accessors so that a card cannot spread two of them.
 */
function failure(state: Fetched<unknown>): {
  readonly state: "unavailable" | "ready";
  readonly message: string | undefined;
  readonly code: string | undefined;
} {
  return state.kind === "failed"
    ? { state: "unavailable", message: state.message, code: state.code }
    : { state: "ready", message: undefined, code: undefined };
}

/**
 * A card's caption, with the stale reason in front of whatever else it had to say.
 *
 * The same decision the throughput card records: `Card`'s stale badge takes an `asOf` date and
 * `Fetched.stale` carries none, so the badge would mean inventing a timestamp. Last-known-good data
 * drawn as though it were current is the defect the brokers screen was repaired for, and this is
 * the sentence that stops it happening on four more cards.
 */
function captionOf(state: Fetched<unknown>, own: string | undefined): string | undefined {
  if (state.kind !== "stale") return own;
  const stale = `This is the last answer KUI received: ${state.reason}`;
  return own === undefined ? stale : `${stale} ${own}`;
}
