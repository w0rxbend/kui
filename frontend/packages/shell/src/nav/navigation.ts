/**
 * Which entries the navigation holds, given what every feature's service is currently doing
 * (ADR-032).
 *
 * ## Why this is a list and not a component
 *
 * Deciding *which* entries exist and drawing them are two different jobs with two different failure
 * modes. Deciding is a pure function of capability states, and the interesting cases — a hidden
 * entry, a forbidden one, one that must not move when its state changes — are all decided here and
 * can be checked without a DOM. Drawing is `NavDrawer`'s and `NavItem`'s, and ADR-032's rendering
 * rules live there next to the elements they apply to. Merging the two would give one component with
 * both sets of edge cases and no way to test either without the other.
 *
 * ## The five rules
 *
 * - **`not-configured` → hidden.** This deployment has no such upstream. That is not a failure, and
 *   rendering it as one sends every operator hunting for an outage that does not exist.
 * - **`forbidden` → shown, disabled, with the reason in its accessible name.** Not a link: a
 *   disabled link is still followable by keyboard in some browsers, and following it would produce a
 *   page the user may not see. Hidden instead when the deployment sets `hideForbidden`.
 * - **`unavailable` → dimmed and still clickable.** This is the amendment ADR-032 made to the
 *   original plan, and it is the whole reason the ADR exists. A disabled entry has nowhere to put
 *   the reason, the "since", the retry or the "what still works" — the user is left with a grey
 *   word. Clicking a dimmed entry goes to the feature's fallback panel, which has all four.
 * - **`degraded` → normal, with a warning badge carrying the reason.** The page works; the badge is
 *   a warning, not a barrier.
 * - **`ready` → normal.**
 *
 * ## Order is fixed, and that is a correctness property rather than a nicety
 *
 * Entries are sorted by their declared order and never by anything that changes at run time. A
 * navigation whose entries reshuffle when a service goes down is one where the user clicks the wrong
 * thing: they aim at the position their muscle memory learned, and something else has moved into it.
 * So a feature going unavailable changes how its entry *looks* and never where it *is*.
 */
import { isHidden, type FeatureRegistration, type FeatureState } from "@kui/kernel";
import { explanation } from "../messages.js";
import type { NavDestination, NavGroup } from "../chrome/types.js";

/** One feature's registration paired with what the shell currently knows about it. */
export type FeatureStatus = {
  readonly registration: FeatureRegistration;
  readonly state: FeatureState;
};

export type NavigationInput = {
  readonly features: readonly FeatureStatus[];
  /**
   * Where a feature's entry goes, given the chosen cluster.
   *
   * Handed in rather than built here, because it is the router's typed path proxy that builds it: a
   * renamed segment is then a compile error, and every link already carries the deployment's mount
   * prefix. This module concatenates no URLs at all, which is the point — a hard-coded root link
   * broke this product behind a reverse proxy once already.
   *
   * `undefined` means "there is nowhere to point right now", which is what keeps a cluster-scoped
   * entry out of the navigation until a cluster is chosen.
   */
  readonly landingFor: (feature: FeatureRegistration, cluster: string | undefined) => string | undefined;
  /** The chosen cluster, or `undefined`. Cluster-scoped entries are left out until there is one. */
  readonly cluster: string | undefined;
  /**
   * The `kui.ui.hideForbidden` switch of ADR-032.
   *
   * Some organisations consider the existence of a feature sensitive; most find a
   * visible-but-disabled entry more helpful than a menu that changes shape per user, so it is off by
   * default.
   */
  readonly hideForbidden?: boolean | undefined;
  /** The shell's own destinations, which have no service behind them and are always reachable. */
  readonly shellDestinations?: readonly NavDestination[] | undefined;
};

/**
 * Every link in the drawer, grouped and ordered.
 *
 * Groups come out in the order their first entry declares, so the heading order is a consequence of
 * the same product decision that fixes the entries — there is no second list to keep in step.
 */
export function navigationGroups(input: NavigationInput): readonly NavGroup[] {
  const shell = input.shellDestinations ?? [];
  const ordered = [...input.features].sort((a, b) => a.registration.order - b.registration.order);

  const groups = new Map<string, NavDestination[]>();
  if (shell.length > 0) groups.set("Overview", [...shell]);

  for (const feature of ordered) {
    const destination = destinationFor(feature, input);
    if (destination === undefined) continue;
    const existing = groups.get(feature.registration.group);
    if (existing === undefined) groups.set(feature.registration.group, [destination]);
    else existing.push(destination);
  }

  return [...groups].map(([heading, destinations]) => ({ heading, destinations }));
}

/** One feature's entry, or `undefined` when it has none right now. */
export function destinationFor(
  feature: FeatureStatus,
  input: Pick<NavigationInput, "landingFor" | "cluster" | "hideForbidden">,
): NavDestination | undefined {
  const { registration, state } = feature;
  if (!registration.sidebar) return undefined;
  if (isHidden(state, input.hideForbidden ?? false)) return undefined;

  // A cluster-scoped entry has no destination until a cluster is chosen, and pointing it at a
  // placeholder is worse than leaving it out: an empty path segment collapses, so
  // `/ui/clusters//topics` is `/ui/clusters/topics`, which matches no route. Every cluster-scoped
  // entry in this product was once a dead link for exactly that reason.
  const href = input.landingFor(registration, input.cluster);
  if (href === undefined) return undefined;

  const reason = explanation(state, registration.label);
  const forbidden = state.kind === "forbidden";

  return {
    id: registration.id,
    label: registration.label,
    icon: registration.icon,
    href,
    state: state.kind,
    ...(forbidden ? { disabled: true, disabledReason: reason ?? "" } : {}),
    ...(badgeFor(state, reason) === undefined ? {} : { badge: badgeFor(state, reason)! }),
  };
}

/**
 * The marker beside a struggling feature's label.
 *
 * The words are short because a drawer entry is one line, and the *sentence* — which is what an
 * operator has to act on — goes in the badge's description, which becomes part of the entry's
 * accessible name and its tooltip. So the reason reaches a screen-reader user and a sighted user by
 * different routes and neither is carried by colour alone.
 *
 * Nothing at all for a working feature: a permanently present marker is a marker nobody looks at.
 */
function badgeFor(
  state: FeatureState,
  reason: string | undefined,
): NavDestination["badge"] | undefined {
  switch (state.kind) {
    case "ready":
    case "not_configured":
    case "forbidden":
      return undefined;
    case "degraded":
      return { text: "degraded", tone: "warning", description: reason ?? "" };
    case "unavailable":
      return { text: "down", tone: "danger", description: reason ?? "" };
  }
}

/**
 * The other features that are currently working, by label — the fallback panel's "what still works".
 *
 * The single most useful sentence on that panel, and the one only the shell can write, because it is
 * about the *other* features: a user who came to look at topics and finds the cluster service down
 * needs to know whether the trip was wasted.
 */
export function stillWorking(
  features: readonly FeatureStatus[],
  except: string,
): readonly string[] {
  return features
    .filter((feature) => feature.registration.id !== except && feature.state.kind === "ready")
    .sort((a, b) => a.registration.order - b.registration.order)
    .map((feature) => feature.registration.label);
}

/** The features that are working but not well, for the banner above the content. */
export function degradedLabels(features: readonly FeatureStatus[]): readonly string[] {
  return features
    .filter((feature) => feature.state.kind === "degraded")
    .sort((a, b) => a.registration.order - b.registration.order)
    .map((feature) => feature.registration.label);
}
