/**
 * The vocabulary the application chrome is built from.
 *
 * These types are deliberately small and made of plain data. The drawer, the top bar and the tab
 * strip are told what to draw; none of them fetches anything, and none of them knows what a Kafka
 * cluster is. That is what makes them testable at every state in Storybook, including the states
 * that only occur when a service is down and which are therefore the hardest to reach in a running
 * product — and those are exactly the states this project's worst defects have lived in.
 */

import type { IconName } from "@kui/kernel";

/**
 * How a cluster is doing, as four cases rather than a boolean.
 *
 * "unknown" is not the same as "unreachable": the first says we have not asked yet, the second says
 * we asked and got nothing. Collapsing them makes a page that is still loading look like a page
 * reporting an outage, which is the single most expensive kind of false alarm an operations tool
 * can raise.
 */
export type ClusterHealth = "healthy" | "degraded" | "unreachable" | "unknown";

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
 * Which of ADR-032's five states a destination is in.
 *
 * Written to `data-state` on the row, and deliberately not expressed as a class name. Class names
 * belong to the visual design and change whenever the design does; this is a statement about state,
 * and the end-to-end tests that assert the five rules against a real browser have to keep asserting
 * on something that stays true through a restyle.
 */
export type NavState = "ready" | "degraded" | "unavailable" | "forbidden" | "not_configured";

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
