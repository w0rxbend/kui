import { Show } from "solid-js";
import { Icon } from "@kui/kernel";
import { healthWord, type ClusterSummary } from "./types.js";

/**
 * The head of the navigation drawer: which cluster you are on, how it is doing, and the one route
 * to registering another.
 *
 * ## The product wordmark is not here any more
 *
 * It used to be: a gradient tile, "Kafka UI", and a tagline. In all twenty-three captures of
 * `SCREENS-V4.md` §2.1 the head is the *cluster* — a status dot, the name at 15px/600, a caption,
 * and a `+` at the right edge — and the product marks itself once, on the rail's glyph above.
 * That is not a branding decision, it is a use of the most valuable 40px in the frame: an operator
 * knows which product they opened, and the question they ask every few minutes is which cluster
 * they are about to break.
 *
 * ## The caption drops parts, it does not fill them
 *
 * `1 URP · v3.7.0 · 3 brokers` is three independent figures, and **each is omitted when it is not
 * known** rather than printed as a dash beside a word. A cluster whose brokers have not been
 * counted shows the name and the dot and nothing else — never `— brokers`, which reads as a
 * missing dash rather than as a missing figure, and never `0 brokers`, which is an assertion about
 * the cluster that nobody made.
 *
 * The first token is variable *in kind*, and that is the whole reason the caption exists. A green
 * dot beside the word "healthy" says one thing twice; `1 URP` says the thing the dot cannot. So a
 * clean cluster is captioned with the health word and a cluster with under-replicated partitions is
 * captioned with the count — and a count of zero is not a defect, so it takes the word.
 *
 * ## The dot is decoration, and it can be
 *
 * It is `aria-hidden` because the caption below says the same thing in words: its first token is
 * the health when there is no defect to report, and a defect count when there is — and a defect
 * count is a stronger statement than any dot. A coloured circle that were the only signal would be
 * unreadable to roughly one man in twelve and to everybody on a screen reader. The one case with no
 * word is a health nobody has established yet, where the honest rendering is to say nothing at all
 * rather than to guess.
 */
export type BrandBlockProps = {
  /**
   * The cluster at the head, when one has been chosen and read.
   *
   * Absent covers both of the ways there can be none — a deployment with nothing configured, and
   * the moment before the first answer arrives. Neither is an error, and the head says "no cluster"
   * rather than drawing an empty row, because a head that changed height when the first response
   * landed would move the whole drawer under the reader's cursor.
   */
  readonly cluster?: ClusterSummary | undefined;
  /**
   * Where the `+` goes: `/clusters/manage`, built by the caller through `KuiPaths`.
   *
   * A prop and never a literal, for the reason `routing/paths.ts` exists — a hand-written address
   * goes on compiling after a segment is renamed and produces a link that 404s for whoever clicks
   * it. Absent means no `+` at all: this is the only route to cluster registration in the whole
   * product, and a control that goes nowhere is worse than an absent one.
   */
  readonly manageHref?: string | undefined;
};

export function BrandBlock(props: BrandBlockProps) {
  const health = () => props.cluster?.health ?? "unknown";
  const name = () => props.cluster?.name ?? "no cluster";

  /**
   * The three parts, with the ones we do not have left out.
   *
   * Built as a list and joined, rather than as a string with conditionals in it: joining is what
   * guarantees exactly one separator between whatever survives, and a template with three optional
   * pieces is how a caption ends up reading `· v3.7.0 ·` on the day two of them are missing.
   */
  const caption = () => {
    const cluster = props.cluster;
    if (cluster === undefined) return undefined;
    const parts = [firstToken(cluster), cluster.version, brokerToken(cluster)].filter(
      (part): part is string => part !== undefined,
    );
    return parts.length === 0 ? undefined : parts.join(" · ");
  };

  return (
    <div class="kui-brand" data-testid="brand-block">
      {/* Two rows, as §2.1 draws them: the dot, the name and the `+` on one line, and the caption
          on its own beneath. The caption gets the drawer's full width that way, which matters — it
          is three figures joined by interpuncts and there is no useful place to break it. */}
      <div class="kui-brand__head">
        <span
          class={["kui-brand__dot", `kui-brand__dot--${health()}`]}
          /* Decoration. The word for the same fact is the caption's first token, below. */
          aria-hidden="true"
        />
        <span class="kui-brand__name">{name()}</span>
        <Show when={props.manageHref}>
          {(href) => (
            <a class="kui-brand__add kui-focusable" href={href()} data-testid="brand-add-cluster">
              <Icon name="plus" size="16px" />
              {/* The glyph is a plus and the destination is a registration form; "Add a cluster" is
                  the same phrase the environment rail and the cluster card use, because three
                  different words for one destination is three destinations to a reader. */}
              <span class="kui-visually-hidden">Add a cluster</span>
            </a>
          )}
        </Show>
      </div>
      {/* The caption carries the health in words, which is what makes the dot above safe to hide.
          No `aria-label` on either span: `aria-label` on a generic element is prohibited by ARIA and
          axe reports it, and the words are in the reading order here anyway. */}
      <Show when={caption()}>
        {(line) => <span class="kui-brand__caption">{line()}</span>}
      </Show>
    </div>
  );
}

/**
 * The caption's first token: the defect count when there is one, the health word otherwise.
 *
 * `undefined` only when the health itself is not known, which is the state before the first scrape
 * answers. "health not known yet" is the right sentence for a tooltip and the wrong one for a
 * 11px caption that is read at a glance, so at this size the honest rendering is to say nothing.
 */
function firstToken(cluster: ClusterSummary): string | undefined {
  const under = cluster.defects?.underReplicatedPartitions;
  /* URP, the abbreviation the design draws, and only when the figure is both known and non-zero. A
     `0 URP` is not a defect, so the token falls back to the health word — and an absent count is
     not a zero, which is why the test is on `undefined` and not on falsiness. */
  if (under !== undefined && under > 0) return `${under} URP`;
  return cluster.health === "unknown" ? undefined : healthWord(cluster.health);
}

/** `3 brokers`, and nothing at all when nobody has counted them. */
function brokerToken(cluster: ClusterSummary): string | undefined {
  const count = cluster.brokerCount;
  if (count === undefined) return undefined;
  return count === 1 ? "1 broker" : `${count} brokers`;
}
