/**
 * The vocabulary the application chrome is built from.
 *
 * These types are deliberately small and made of plain data. The drawer, the top bar and the tab
 * strip are told what to draw; none of them fetches anything, and none of them knows what a Kafka
 * cluster is. That is what makes them testable at every state in Storybook, including the states
 * that only occur when a service is down and which are therefore the hardest to reach in a running
 * product — and those are exactly the states this project's worst defects have lived in.
 */

import type { FeatureId, IconName } from "@kui/kernel";

/**
 * How a cluster is doing, as four cases rather than a boolean.
 *
 * "unknown" is not the same as "unreachable": the first says we have not asked yet, the second says
 * we asked and got nothing. Collapsing them makes a page that is still loading look like a page
 * reporting an outage, which is the single most expensive kind of false alarm an operations tool
 * can raise.
 */
export type ClusterHealth = "healthy" | "degraded" | "unreachable" | "unknown";

/**
 * The four health cases in words, which is where the fact actually lives.
 *
 * Every place this product reports a cluster's health draws a coloured dot, and in every one of
 * them the dot is marked decorative — so the word beside it is not a caption, it is the statement.
 * One table rather than one per component: the drawer's head and the environment rail's tooltips
 * name the same four states, and two tables would let a rename change one screen's vocabulary and
 * not the other's, which is the kind of drift nobody reports because each screen reads correctly on
 * its own.
 */
export function healthWord(health: ClusterHealth): string {
  return HEALTH_WORDS[health];
}

const HEALTH_WORDS: Record<ClusterHealth, string> = {
  healthy: "healthy",
  degraded: "degraded",
  /* Not "unreachable": the sentence is about what we observed, and "not answering" says that
     without asserting the cluster is down — a severed route from KUI is not a dead broker. */
  unreachable: "not answering",
  unknown: "health not known yet",
};

/** A cluster as the chrome needs to know it. */
export type ClusterSummary = {
  readonly id: string;
  readonly name: string;
  readonly health: ClusterHealth;
  /**
   * The broker version, when it is known. `undefined` means "not known", and the components turn
   * that into the words "version unknown" rather than into a dash — a dash beside a word reads as a
   * missing dash, not as a missing version.
   */
  readonly version?: string | undefined;
  /** Only meaningful when `health` is "unreachable": how long ago the last successful check was. */
  readonly lastSeen?: string | undefined;
  /**
   * How many brokers answered the last successful scrape, when one has succeeded.
   *
   * Optional for the same reason `version` is, and the drawer head treats it the same way. The head
   * writes a three-part interpunct list — `1 URP · v3.7.0 · 3 brokers` (`SCREENS-V4.md` §2.1) — and
   * **each part is dropped when its figure is unknown** rather than printed as a dash beside a
   * word, which reads as a missing dash and not as a missing figure. That is `BrandBlock`'s own
   * stated rule, applied to a caption instead of to a version.
   */
  readonly brokerCount?: number | undefined;
  /**
   * What is currently wrong with the cluster, in the counts the caption's first token comes from.
   *
   * The first token of that list is variable *in kind*: the health word when the cluster is clean,
   * a defect count with an abbreviation when it is not. That is the whole reason the caption
   * exists — a green dot and the word "healthy" say the same thing twice, whereas "1 URP" says
   * something the dot cannot.
   *
   * An absent count means nobody has told us, and it is emphatically not a zero: a cluster whose
   * partition counts came back `null` would otherwise be captioned "0 URP", which is an invented
   * reassurance about data that never arrived — the exact defect `overview/load.ts` documents at
   * length in `withoutNulls`.
   */
  readonly defects?:
    | {
        readonly underReplicatedPartitions?: number | undefined;
      }
    | undefined;
};

/**
 * The tone of a navigation badge.
 *
 * The tone follows the *meaning* of the number, never the number itself. Brokers `2/3` is danger
 * even though `3/3` is success; a count of topics is neutral however large it gets, because a lot of
 * topics is not a problem.
 */
export type BadgeTone = "neutral" | "success" | "warning" | "danger";

export type NavBadge = {
  readonly text: string;
  readonly tone: BadgeTone;
  /**
   * What the badge means, in a sentence. It becomes part of the destination's accessible name, so
   * that "Brokers, 3 of 3 online" is what a screen reader says rather than "Brokers 3/3".
   */
  readonly description: string;
};

/**
 * The figure a navigation row carries, in the three shapes the drawer actually draws.
 *
 * A shape rather than a formatted string, and that is the whole point. The tone follows what the
 * figure *means*, and only the figure knows: `2/3` is danger, `128` is neutral, and a caller that
 * had already flattened both to text could tell them apart only by looking at the digits — which is
 * how a badge ends up amber because a number happened to be large. Keeping the meaning until the
 * last moment lets the rule be applied once, in `nav/navigation.ts`, instead of at each row.
 */
export type NavCount =
  /** A plain quantity: `128` topics, `6` subjects. Never a problem, however large it gets. */
  | {
      readonly kind: "total";
      readonly value: number;
      /** What is counted, for the accessible name. The label beside it already says "Topics". */
      readonly noun?: string | undefined;
    }
  /**
   * `3/3`: how many of a set are up, out of how many there are.
   *
   * The one figure on this list whose tone changes without the row changing, which is exactly why
   * it is worth a variant of its own. A denominator has to be a number somebody really reported —
   * see `brokerCount` in `overview/model.ts` for why "the number that answered" is not one.
   */
  | {
      readonly kind: "online";
      readonly online: number;
      readonly total: number;
      readonly noun?: string | undefined;
    }
  /**
   * `1 rebalancing`, `1 failed`: a count of things that are not right, and the word for them.
   *
   * The severity is the caller's to state because only the caller knows what the thing counted
   * does next: a rebalancing consumer group settles by itself and is a warning, a failed connector
   * task does not and is not.
   */
  | {
      readonly kind: "defect";
      readonly value: number;
      readonly noun: string;
      readonly severity: "warning" | "danger";
    };

/**
 * The figure beside each of the drawer's rows, one per feature that has one.
 *
 * Keyed by `FeatureId` rather than by a loose string, so a feature that is renamed breaks this
 * table instead of quietly losing its badge — a missing badge looks exactly like a count that could
 * not be fetched, and the two would never be told apart by anybody reading the screen.
 *
 * Every member is optional and an absent one means *not known*. It never means zero: `Topics 0` on
 * a cluster whose topic service did not answer is a statement about the cluster, and a false one.
 */
export type NavCounts = {
  readonly [K in FeatureId]?: NavCount | undefined;
};

/**
 * Which of ADR-032's five states a destination is in.
 *
 * Written to `data-state` on the row, and deliberately not expressed as a class name. Class names
 * belong to the visual design and change whenever the design does; this is a statement about state,
 * and the end-to-end tests that assert the five rules against a real browser have to keep asserting
 * on something that stays true through a restyle.
 */
export type NavState = "ready" | "degraded" | "unavailable" | "forbidden" | "not_configured";

/**
 * Where a nested row sorts among its siblings, in the two kinds the topic tree has.
 *
 * A field on the row rather than a rule read off its label. `internal` is every topic whose name
 * begins with an underscore, folded into one padlocked row by `nav/prefixes.ts`, and a renderer
 * that recovered that by looking at the label would file a genuine prefix called `internal.*` as
 * the padlocked row. The fold knows which is which; the label does not.
 *
 * There was a third kind, `favourite`, sorting above both. It went with the fold's favourites
 * branch — nothing in the product records a favourite, so no row ever carried it outside a fixture
 * — and it comes back with whatever finally records one. See `nav/topicTree.ts`.
 *
 * Absent means "an ordinary row", which sorts with the prefix groups. That is the right default for
 * a caller that has only one kind of child.
 */
export type NavRank = "prefix" | "internal";

export type NavDestination = {
  readonly id: string;
  readonly label: string;
  readonly icon: IconName;
  readonly href: string;
  /**
   * Omit the badge entirely when its number could not be fetched. A `0` is a statement about the
   * cluster and must never be printed for an unknown, and a spinner does not fit in a 20px badge.
   */
  readonly badge?: NavBadge | undefined;
  /**
   * What the shell knows about the service behind this destination, when it knows anything.
   *
   * `unavailable` is the interesting one: the row is drawn dimmed and stays a real link, because the
   * page behind it is the feature's fallback panel and that is the only place the reason, the time
   * it went away, a working retry and "what still works" exist. A dead row would take away the one
   * route to the explanation.
   */
  readonly state?: NavState | undefined;
  /** A destination that exists but is not built yet, or that this principal may not open. */
  readonly disabled?: boolean | undefined;
  /**
   * Why it is disabled, in a sentence, shown as a tooltip and read as part of the accessible name.
   * A dead row with no explanation is worse than no row, so a disabled destination without a reason
   * is a mistake this type cannot prevent but every call site should avoid.
   */
  readonly disabledReason?: string | undefined;
  /**
   * The rows nested under this one: the topic tree of `SCREENS-V4.md` §2.2.
   *
   * Recursive rather than a flat list with a depth field, because the depth of a row is not a fact
   * about the row — it is a fact about where it sits, and the two go out of step the moment a
   * branch is moved. A nested shape cannot express a child at the wrong depth at all.
   *
   * Absent and empty draw the same thing, and that is `NavItem`'s decision rather than an accident:
   * a row is a branch when it has children, so an empty array gets no disclosure. A chevron that
   * opens onto nothing is a control that appears broken, and a cluster with no topics is better
   * described by the row's own badge than by a disclosure holding an empty list. Callers may still
   * distinguish the two in their own data; the drawer does not.
   */
  readonly children?: readonly NavDestination[] | undefined;
  /**
   * Whether the children are on screen.
   *
   * Carried on the data rather than held inside the row component, because it has to survive the
   * row being re-rendered — the drawer is rebuilt whenever a capability frame lands, which on a
   * struggling cluster is every few seconds, and a tree that collapsed itself each time would be
   * unusable exactly when somebody most needs it.
   */
  readonly expanded?: boolean | undefined;
  /**
   * Where this row sorts among its siblings. See {@link NavRank}.
   *
   * Only meaningful on a child row; a top-level destination's position is its feature's declared
   * order, which is fixed for the reason `nav/navigation.ts` gives at length — an entry that moves
   * when a service goes down is an entry the user clicks by mistake.
   */
  readonly rank?: NavRank | undefined;
};

export type NavGroup = {
  /** The lettered heading: CLUSTER, ECOSYSTEM. Written uppercase at the call site — see below. */
  readonly heading: string;
  readonly destinations: readonly NavDestination[];
};

/** One step of a breadcrumb trail. The last step is the current page and is never a link. */
export type Crumb = {
  readonly label: string;
  /** Absent on the final crumb, which is where you already are. */
  readonly href?: string | undefined;
};

export type Tab = {
  readonly id: string;
  readonly label: string;
  readonly icon: IconName;
  readonly href: string;
  /** An optional count beside the label, e.g. the number of consumers on a topic. */
  readonly count?: number | undefined;
};
