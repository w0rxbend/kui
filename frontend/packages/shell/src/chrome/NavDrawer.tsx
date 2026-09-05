import { For, Show } from "solid-js";
import { BrandBlock } from "./BrandBlock.js";
import { ClusterStatusCard } from "./ClusterStatusCard.js";
import { NavItem } from "./NavItem.js";
import { StorageMeter, type BrokerStorage } from "./StorageMeter.js";
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
 * ## What sits in the foot, and why it is two different cards
 *
 * The design replaced the cluster status card with a storage meter (`SCREENS.md` §2.7), and taken
 * literally that would drop two affordances that live nowhere else: "retry this cluster" and
 * "configure a cluster" — the latter being the only route out of a fresh install with nothing set
 * up.
 *
 * So the foot shows the storage meter when there is a cluster to report on, and the status card
 * when there is not: no cluster selected, or one that is not answering. That is not a compromise
 * between two designs, it is the same rule the rest of the product follows — a panel shows its
 * subject when it has one and says why it has not when it does not. A storage meter for a cluster
 * that is not answering would be an em dash where the answer is "you cannot reach this cluster,
 * here is a retry".
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
  /**
   * Per-broker disk, for the storage meter. Empty or absent means "not known", which the meter
   * draws as a neutral track and a sentence rather than as a zero.
   */
  readonly storage?: readonly BrokerStorage[] | undefined;
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
                {/* The count sits *beside* the heading rather than inside it. The list is named by
                    `aria-labelledby` pointing at the heading, so a count inside it would make the
                    list announce itself as "CLUSTER 4" — and the count is of menu entries, nothing
                    in Kafka, which beside "Brokers 3/3" is an invitation to misread. */}
                <div class="kui-nav-group__head">
                  <h2 class="kui-nav-group__heading" id={headingId}>
                    {group.heading}
                  </h2>
                  <span class="kui-nav-group__count" aria-label={`${group.destinations.length} destinations`}>
                    {group.destinations.length}
                  </span>
                </div>
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

      <Show
        when={props.cluster !== undefined && props.cluster.health !== "unreachable"}
        fallback={
          <ClusterStatusCard
            cluster={props.cluster}
            onOpen={props.onOpenClusters}
            onRetry={props.onRetryCluster}
            configureHref={props.configureHref}
          />
        }
      >
        <StorageMeter brokers={props.storage ?? []} />
      </Show>
    </div>
  );
}
