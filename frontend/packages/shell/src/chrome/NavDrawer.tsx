import { For } from "solid-js";
import { BrandBlock } from "./BrandBlock.js";
import { ClusterStatusCard } from "./ClusterStatusCard.js";
import { NavItem } from "./NavItem.js";
import type { ClusterSummary, NavGroup } from "./types.js";

/**
 * The left-hand navigation drawer: brand block, lettered groups of destinations, cluster card at
 * the foot.
 *
 * ## Why the destination list scrolls and the two ends do not
 *
 * The brand block and the cluster card are pinned; only the middle scrolls. A drawer that scrolls as
 * one piece takes the cluster card off screen the moment a deployment has enough destinations to
 * overflow, and the cluster card is the answer to "which cluster am I about to break?" — which is
 * the question an operator asks most often and most urgently.
 *
 * ## The headings are real headings
 *
 * CLUSTER and ECOSYSTEM are written uppercase in the source, not lowercase with `text-transform`.
 * A screen reader given "Cluster" styled as capitals reads a word; given the same word transformed
 * it may still read a word, but given an acronym-shaped string it sometimes spells it out. Writing
 * what we mean removes the guess. Each heading labels its own list through `aria-labelledby`, so the
 * groups are navigable as groups rather than as one flat run of links.
 *
 * ## Degraded behaviour
 *
 * The drawer always renders. If the capability registry cannot be reached, the caller passes the
 * destinations it knows about with no badges, and every page behind them takes its own unavailable
 * rendering. The shell never becomes a blank page because a service is down — the frame is the one
 * thing that has to survive everything else failing.
 */
export type NavDrawerProps = {
  readonly groups: readonly NavGroup[];
  /** The id of the destination currently being shown, if any. */
  readonly currentId?: string | undefined;
  readonly cluster?: ClusterSummary | undefined;
  readonly onOpenClusters?: (() => void) | undefined;
  readonly onRetryCluster?: (() => void) | undefined;
  readonly configureHref?: string | undefined;
};

export function NavDrawer(props: NavDrawerProps) {
  const health = () => props.cluster?.health ?? "unknown";

  return (
    <div class="kui-nav-drawer" data-testid="nav-drawer">
      <BrandBlock health={health()} />

      <nav class="kui-nav-drawer__nav" aria-label="Main">
        <For each={props.groups}>
          {(group) => {
            const headingId = `kui-nav-group-${group.heading.toLowerCase()}`;
            return (
              <div class="kui-nav-group">
                <h2 class="kui-nav-group__heading" id={headingId}>
                  {group.heading}
                </h2>
                <ul class="kui-nav-group__list" aria-labelledby={headingId}>
                  <For each={group.destinations}>
                    {(destination) => <NavItem destination={destination} current={destination.id === props.currentId} />}
                  </For>
                </ul>
              </div>
            );
          }}
        </For>
      </nav>

      <ClusterStatusCard
        cluster={props.cluster}
        onOpen={props.onOpenClusters}
        onRetry={props.onRetryCluster}
        configureHref={props.configureHref}
      />
    </div>
  );
}
