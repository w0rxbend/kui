import { Icon } from "@kui/kernel";
import type { ClusterSummary } from "./types.js";

/**
 * The card pinned to the foot of the navigation drawer: which cluster you are looking at, whether
 * it is well, and what version it runs.
 *
 * ## This is the authoritative health statement
 *
 * The dot on the brand tile at the top of the drawer mirrors this, but decoratively. This card is
 * the one that says it in words — "healthy", "degraded", "unreachable" — because colour is never
 * the only signal. Around one man in twelve cannot reliably separate the amber from the green, and
 * a screen reader gets no colour at all.
 *
 * ## The three ways this goes wrong, and what each one says
 *
 * Unknown version: "healthy · version unknown". Not "healthy · —". A dash sitting next to a word
 * reads as a typo rather than as a missing value, and the operator's next question ("which version
 * is this?") deserves an answer that admits we do not know.
 *
 * Unreachable: the whole card becomes a button that retries, the tile turns danger-toned, and the
 * text says when the cluster was last seen. The most useful thing to offer somebody staring at an
 * unreachable cluster is the ability to ask again without reloading the page.
 *
 * No cluster configured: "no cluster · add one", neutral tone, and the card links to configuration.
 * A first-time visitor who has not added a cluster is not in an error state, so nothing here is red.
 */
export type ClusterStatusCardProps = {
  /** The selected cluster, or `undefined` when none is configured yet. */
  readonly cluster?: ClusterSummary | undefined;
  /** Opens the cluster menu. Not called in the unreachable or unconfigured cases. */
  readonly onOpen?: (() => void) | undefined;
  /** Called by the unreachable card's retry. */
  readonly onRetry?: (() => void) | undefined;
  /** Where "add one" goes when there is no cluster. */
  readonly configureHref?: string | undefined;
};

export function ClusterStatusCard(props: ClusterStatusCardProps) {
  const cluster = () => props.cluster;

  /** The second line, as words. Never a bare dash, and never colour on its own. */
  const detail = () => {
    const c = cluster();
    if (!c) return "no cluster · add one";
    if (c.health === "unreachable") {
      return c.lastSeen ? `unreachable · last seen ${c.lastSeen}` : "unreachable";
    }
    const version = c.version ?? "version unknown";
    return `${c.health} · ${version}`;
  };

  const tone = () => {
    const c = cluster();
    if (!c) return "neutral";
    if (c.health === "unreachable") return "danger";
    if (c.health === "degraded") return "warning";
    if (c.health === "unknown") return "neutral";
    return "success";
  };

  const name = () => cluster()?.name ?? "No cluster";

  const inside = () => (
    <>
      <span class={["kui-cluster-card__tile", `kui-cluster-card__tile--${tone()}`]} aria-hidden="true">
        <Icon name="shield" size="16px" />
      </span>
      <span class="kui-cluster-card__text">
        <span class="kui-cluster-card__name">{name()}</span>
        <span class="kui-cluster-card__detail">
          <span class={["kui-cluster-card__dot", `kui-cluster-card__dot--${tone()}`]} aria-hidden="true" />
          {detail()}
        </span>
      </span>
    </>
  );

  return (
    <div class="kui-cluster-card__slot">
      {cluster() === undefined ? (
        <a class="kui-cluster-card" href={props.configureHref ?? "#"} data-testid="cluster-status-card">
          {inside()}
        </a>
      ) : cluster()!.health === "unreachable" ? (
        <button
          type="button"
          class="kui-cluster-card kui-cluster-card--retry"
          onClick={() => props.onRetry?.()}
          /* Named for what pressing it does, not for what it shows. "prod-kyiv-01, unreachable" is
           * the state; "check again" is the action, and the action is what belongs in the name. */
          aria-label={`${name()}: ${detail()}. Check again`}
          data-testid="cluster-status-card"
        >
          {inside()}
          <Icon name="refresh" size="14px" class="kui-cluster-card__retry-icon" />
        </button>
      ) : (
        <button
          type="button"
          class="kui-cluster-card"
          onClick={() => props.onOpen?.()}
          aria-label={`${name()}: ${detail()}. Change cluster`}
          data-testid="cluster-status-card"
        >
          {inside()}
        </button>
      )}
    </div>
  );
}
