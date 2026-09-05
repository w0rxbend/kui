/**
 * The card: the box almost every region of this product is drawn in, and the six renderings it has
 * that are not the happy one.
 *
 * ## The rule this component exists to enforce
 *
 * **The frame never disappears.** A gateway aggregation is partial by design — every section
 * carries its own status — so a page has to be able to show three healthy panels and one that
 * failed, at the same time, without either of them lying. That is only possible if "this failed"
 * is a state *inside* the card. A panel that unmounts itself takes its own title with it, and
 * leaves a gap the operator has to notice and then work out the meaning of.
 *
 * So the title, the header actions and the card's own outline are rendered in **every** state,
 * including `unavailable` and `forbidden`. Only the body changes.
 *
 * ## The six states, and why they are six and not two
 *
 * An empty box is ambiguous in four different directions at once. These are the four, plus the two
 * that are not empty:
 *
 * | State         | The body holds                              | What the operator does next     |
 * | ------------- | ------------------------------------------- | ------------------------------- |
 * | `ready`       | the content                                 | reads it                        |
 * | `loading`     | skeletons at the content's real size        | waits                           |
 * | `empty`       | "nothing here yet", warmly                  | creates something               |
 * | `filtered`    | "nothing matched X" **and a clear button**  | clears the filter               |
 * | `unavailable` | a sentence, the error code, and **Retry**   | retries, or escalates the code  |
 * | `forbidden`   | a sentence with a lock                      | asks an administrator           |
 *
 * `empty` and `filtered` are the pair most often collapsed into one, and they must never be: one
 * says the cluster has no topics, the other says the operator's own filter is hiding them, and
 * substituting the first for the second sends somebody looking for a problem that is not there.
 *
 * ## Stale is not a state, it is a layer
 *
 * A stale card is `ready` — it has content, and the content is the last known value. So `stale` is
 * a separate prop that dims the body and puts a badge above it, and it composes with `ready`
 * rather than replacing it. Blanking a panel because its data went out of date throws away the
 * only information anybody has (see `StaleBadge`).
 *
 * ## Naming
 *
 * The class is `.kui-panel`, not `.kui-card`, because `.kui-card` belongs to the Laminar
 * frontend's stylesheet, which is still what the shipping application is styled by. When that
 * frontend is retired, `20-kernel-controls.css` goes with it and these names lose their prefix in
 * one rename. This is the same convention `27-primitives.css` uses for `.kui-btn`.
 */
import { Show, type ParentComponent } from "solid-js";
import type { JSX } from "@solidjs/web";
import { EmptyState, Skeleton } from "./EmptyState.jsx";
import { Icon, type IconName } from "./Icon.jsx";
import { StaleBadge, type StaleBadgeProps } from "./StaleBadge.jsx";

export type CardState = "ready" | "loading" | "empty" | "filtered" | "unavailable" | "forbidden";

export interface CardProps {
  /**
   * The card's name. Rendered in every state, including the failing ones.
   *
   * Omit it only for a card that is a single figure with its own label inside — a `StatCard` —
   * where a second heading above the label would be read twice by a screen reader.
   */
  readonly title?: string;
  /** A decorative glyph before the title. */
  readonly icon?: IconName;
  /**
   * The right-hand end of the header: a legend, a range selector, an action.
   *
   * It stays mounted in every state on purpose. When a metrics panel is unavailable, changing the
   * range is a legitimate retry, and removing the control removes the way out.
   */
  readonly headerEnd?: JSX.Element;
  /** A caption under the body, at the subtle text colour. */
  readonly caption?: JSX.Element;
  readonly footer?: JSX.Element;

  readonly state?: CardState;

  /** The sentence for `empty`, `filtered`, `unavailable` and `forbidden`. */
  readonly message?: string;
  /** The second line: what it means, or what to do. */
  readonly description?: string;
  /** The stable failure code. Shown verbatim for `unavailable` and `forbidden`. */
  readonly code?: string;
  /** The single action offered by the state's body — Retry, Clear filter, Create topic. */
  readonly stateAction?: JSX.Element;

  /** Present means "the content below is the last known value". Composes with `ready`. */
  readonly stale?: StaleBadgeProps;

  /**
   * A minimum body height. Set it wherever the content's height is known in advance, so the card
   * does not change size when the data lands and shove the rest of the page down.
   */
  readonly bodyMinHeight?: string;

  /** What `loading` draws. Defaults to three skeleton lines; pass the real shape where it is known. */
  readonly loadingBody?: JSX.Element;

  readonly class?: string;
  /** For tests to find this card. Never used for styling. */
  readonly testId?: string;
}

/** The three skeleton lines a card falls back to when the caller has not described its content. */
function DefaultLoadingBody(): JSX.Element {
  return (
    <div class="kui-panel__loading">
      <Skeleton width="60%" />
      <Skeleton width="85%" />
      <Skeleton width="45%" />
    </div>
  );
}

export const Card: ParentComponent<CardProps> = (props) => {
  const state = (): CardState => props.state ?? "ready";
  const showsContent = () => state() === "ready";
  const isStale = () => props.stale !== undefined && showsContent();

  return (
    <section
      class={["kui-panel", `kui-panel--${state()}`, props.class]}
      data-testid={props.testId}
      // `aria-busy` is what tells a screen reader that the skeletons are a placeholder and not the
      // content. The skeletons themselves are `aria-hidden`, so without this the region is silent.
      aria-busy={state() === "loading" ? "true" : undefined}
    >
      <Show when={props.title !== undefined || props.headerEnd !== undefined}>
        <header class="kui-panel__header">
          <Show when={props.title}>
            {(title) => (
              <h2 class="kui-panel__title">
                <Show when={props.icon}>
                  {(icon) => <Icon name={icon()} class="kui-panel__icon" />}
                </Show>
                {title()}
              </h2>
            )}
          </Show>
          <Show when={props.headerEnd}>
            {(headerEnd) => <div class="kui-panel__header-end">{headerEnd()}</div>}
          </Show>
        </header>
      </Show>

      <Show when={isStale()}>
        {/* Above the content, not below it: an operator who reads the figure first and the caveat
            second has already believed the figure. */}
        <div class="kui-panel__stale">
          <StaleBadge {...(props.stale as StaleBadgeProps)} />
        </div>
      </Show>

      <div
        class={["kui-panel__body", { "kui-panel__body--stale": isStale() }]}
        style={props.bodyMinHeight === undefined ? undefined : { "min-height": props.bodyMinHeight }}
      >
        <Show when={state() === "loading"}>{props.loadingBody ?? <DefaultLoadingBody />}</Show>
        <Show when={showsContent()}>{props.children}</Show>
        <Show when={state() !== "ready" && state() !== "loading"}>
          <EmptyState
            kind={state() === "filtered" ? "filtered" : state() === "empty" ? "empty" : state() === "forbidden" ? "forbidden" : "unavailable"}
            title={props.message ?? "There is nothing to show."}
            description={props.description}
            code={props.code}
            action={props.stateAction}
          />
        </Show>
      </div>

      <Show when={props.caption}>{(caption) => <p class="kui-panel__caption">{caption()}</p>}</Show>
      <Show when={props.footer}>{(footer) => <footer class="kui-panel__footer">{footer()}</footer>}</Show>
    </section>
  );
};
