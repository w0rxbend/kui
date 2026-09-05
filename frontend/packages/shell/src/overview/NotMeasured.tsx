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

import type { JSX } from "@solidjs/web";
import { Icon } from "@kui/kernel";

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
