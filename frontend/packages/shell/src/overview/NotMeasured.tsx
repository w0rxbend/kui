/**
 * What a panel draws when KUI does not measure the thing the design drew there.
 *
 * ## Why this is not `Card state="unavailable"`
 *
 * `Card` already has six states, and "unavailable" is the tempting one. It is the wrong one. Every
 * failing state in that component means *we asked and it did not work*: it carries a failure code,
 * it offers a retry, and it is styled to be noticed. All three are wrong here. Nothing asked,
 * nothing failed, there is no code to quote and a retry can never succeed, because the product has
 * no such measurement to retry. A throughput panel drawn as "unavailable" sends an operator to go
 * and find a broken metrics exporter that has never existed — a false alarm that costs somebody an
 * afternoon, on a screen whose entire job is to tell them where to look.
 *
 * So this is a `ready` card whose content is a plain sentence. It is quiet on purpose: it should
 * read as "this part of the product is not built yet", which is what is true, and it should not
 * compete for attention with the panels next to it that are reporting on a live cluster.
 *
 * ## Why it draws no axes
 *
 * The obvious alternative — an empty plot with its time axis still labelled — is worse than useless.
 * An axis is a claim that there is a measurable quantity here and it merely has no values right now.
 * The chart components already handle a genuinely empty series that way, correctly, for the case
 * where a cluster has been quiet. This is a different case and must not borrow that picture.
 */

import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Icon, type Fetched } from "@kui/kernel";

export interface NotMeasuredProps {
  /**
   * The sentence, from `model.ts`. It names what is not collected and why, in terms of the thing
   * that would have to exist for it to be collected — so a reader learns whether this is a bug, a
   * configuration gap, or an unbuilt feature. It is always the third.
   */
  readonly why: string;
  /**
   * What the reader can do instead, when there is something. Omitted rather than filled with
   * encouragement: a panel that says "coming soon!" and nothing else has wasted the space twice.
   */
  readonly instead?: string | undefined;
  readonly testId?: string | undefined;
}

export function NotMeasured(props: NotMeasuredProps): JSX.Element {
  return (
    /* `role="note"` rather than `status` or `alert`. This is a standing fact about the product, not
     * an event: an assertive live region would announce "KUI does not record throughput" to a
     * screen-reader user every time the dashboard re-rendered, which is both noise and alarming. */
    <div class="kui-not-measured" role="note" data-testid={props.testId ?? "not-measured"}>
      <Icon name="info" size="18px" class="kui-not-measured__icon" />
      <p class="kui-not-measured__why">{props.why}</p>
      {/* `Show` is not needed for a single optional string in a leaf position: an `undefined` child
          renders nothing. A conditional wrapper here would only add a component to the tree. */}
      {props.instead === undefined ? undefined : <p class="kui-not-measured__instead">{props.instead}</p>}
    </div>
  );
}

/* --- The two sentences every metrics card shares ------------------------------------------------ */

/**
 * A cluster with no metrics source, in words, for whichever card is asking.
 *
 * Five cards on this tab reach this state and they must reach it in one voice: the operator is
 * looking at a screen where four panels are saying the same thing, and four spellings of it read as
 * four different problems. It names the *deployment choice* rather than a fault, which is the
 * difference between "this is broken" and "this is not switched on" — and it is the sentence M7's
 * old exit criterion could be satisfied by, so the cards that draw it also assert an axis and a
 * table are absent beside it.
 *
 * @param noun what is not being measured, in the possessive: `this cluster's throughput`
 */
export function notConfiguredSentence(noun: string): string {
  return (
    `KUI is not measuring ${noun}. No metrics source is configured for it, so there is nothing ` +
    `to draw — a deployment choice rather than a fault.`
  );
}

/** The one a card shows when the principal may not read the cluster's metrics. */
export function forbiddenSentence(noun: string): string {
  return `You do not have permission to read ${noun}.`;
}

export interface MetricAbsenceProps {
  readonly state: Fetched<unknown>;
  /** What is missing, in the possessive, for both sentences above. */
  readonly noun: string;
  readonly testId?: string | undefined;
}

/**
 * What a metrics card draws when it has no reading, and why `failed` is not in it.
 *
 * `Card` already draws that state's own body — the message, the stable code and the Retry button —
 * so a second sentence underneath would say the same thing twice. The three that reach here are the
 * three a card has to distinguish, and each is a different next action: **wait**, **configure
 * something**, **ask for a permission**. Collapsing any pair produces a screen that says "try
 * again" when trying again is either pointless or the wrong action entirely.
 *
 * One component rather than five, because five cards on one tab reaching the same three states is
 * five chances for one of them to draw an empty axis where a sentence belongs.
 */
export function MetricAbsence(props: MetricAbsenceProps): JSX.Element {
  return (
    <>
      <Show when={props.state.kind === "loading"}>
        {/* The same reserved box the rest of the dashboard draws: a figure that has not arrived
            must not look like one that is missing. */}
        <div
          class="kui-overview__waiting"
          role="status"
          aria-busy="true"
          aria-label={`Reading ${props.noun}`}
        />
      </Show>
      <Show when={props.state.kind === "not-configured"}>
        {/* No axis and no ring. Both are claims that the quantity is measured and merely absent
            right now, and this cluster has nothing measuring it. */}
        <NotMeasured why={notConfiguredSentence(props.noun)} testId={props.testId} />
      </Show>
      <Show when={props.state.kind === "forbidden"}>
        <p class="kui-overview__blank" role="note">
          {forbiddenSentence(props.noun)}
        </p>
      </Show>
    </>
  );
}
