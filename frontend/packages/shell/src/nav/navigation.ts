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
import { formatCount, isHidden, type FeatureRegistration, type FeatureState } from "@kui/kernel";
import { explanation } from "../messages.js";
import type { NavBadge, NavCount, NavDestination, NavGroup } from "../chrome/types.js";

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
  /**
   * The figure each entry carries, when there is one.
   *
   * A function of the registration rather than a table of counts, for the reason `landingFor` is
   * one: the numbers come from a store that fetches, and this module must stay a pure fold over
   * capability states. Handing it a lookup keeps the fetching on the other side of the seam, so the
   * interesting cases below — a count beside a dead service, a count nobody could fetch — are still
   * decided by a function that takes plain data.
   *
   * `undefined` for a feature means *the count is not known*, which is a row with no badge. It is
   * never a zero; see {@link countBadge}.
   */
  readonly countFor?: ((feature: FeatureRegistration) => NavCount | undefined) | undefined;
  /**
   * The rows nested under an entry, when it has any — the drawer's topic tree.
   *
   * A lookup for the same reason {@link countFor} is one: the names the tree is folded from come
   * from a store that fetches, and this module has to stay a pure fold over capability states. It
   * is also the seam that keeps the fold out of the components — `nav/topicTree.ts` turns a name
   * list into these rows and `NavItem` draws them, and neither of them re-folds what the other did.
   *
   * `undefined` means "this row is a leaf", which is what every row that is not Topics is, and
   * what Topics itself is until the names have arrived. It is deliberately different from an empty
   * array: an empty array is a branch that currently holds nothing, and `NavDestination.children`
   * says at length why that distinction is worth keeping.
   */
  readonly childrenFor?:
    | ((feature: FeatureRegistration) => readonly NavDestination[] | undefined)
    | undefined;
  /** The shell's own destinations, which have no service behind them and are always reachable. */
  readonly shellDestinations?: readonly NavDestination[] | undefined;
};

/** The heading over the entries that are about one Kafka cluster: brokers, topics, consumers. */
export const CLUSTER_GROUP = "CLUSTER";

/**
 * The second heading `SCREENS-V4.md` §2.2 draws, and it now has all three of the rows it is drawn
 * with: Schema Registry, Kafka Connect and ksqlDB.
 *
 * It was declared and emitted **empty** for four waves, before any of the three had a service — and
 * that is still a state a real deployment has, which is why nothing here treats an empty
 * `ECOSYSTEM` as a defect. ADR-032's rule for a feature whose upstream is not configured is that it
 * is *hidden*, not drawn as a failure, so a deployment running none of the three registers none of
 * the rows and the heading has nothing under it; a fabricated row would be the exact misreading the
 * rule prevents. `NavDrawer` draws nothing at all for a group with no destinations, which is what
 * makes emitting it unconditionally safe; see the comment there.
 */
export const ECOSYSTEM_GROUP = "ECOSYSTEM";

/**
 * The order the headings appear in, whatever order the features declaring them are registered in.
 *
 * The same argument the entries themselves make one paragraph up, one level higher: a heading that
 * moved because a feature happened to be registered earlier would move every row under it. Both
 * groups on this list are emitted whether or not anything is registered into them, which is how
 * `ECOSYSTEM` can be empty and still be a state something draws; a heading a registration invents
 * that is not on the list keeps its place at the end, in the order it first appeared.
 */
export const NAV_GROUP_ORDER: readonly string[] = [CLUSTER_GROUP, ECOSYSTEM_GROUP];

/**
 * Every link in the drawer, grouped and ordered.
 *
 * The heading order is the shell's own OVERVIEW when it has destinations, then `NAV_GROUP_ORDER`,
 * then any heading a registration declared that is not on it, in the order it first appeared.
 * Within a group the entries keep their declared order, which is the correctness property the
 * header argues for at length.
 *
 * Headings are uppercased here rather than at each registration. `SCREENS-V4.md` draws CLUSTER and
 * ECOSYSTEM in capitals, and `NavGroup.heading` says the capitals belong in the markup rather than
 * in a `text-transform` — so the one place that assembles the groups is the one place that spells
 * them, and a registration that writes "Cluster" and one that writes "cluster" cannot become two
 * headings over two halves of one list.
 */
export function navigationGroups(input: NavigationInput): readonly NavGroup[] {
  const shell = input.shellDestinations ?? [];
  const ordered = [...input.features].sort((a, b) => a.registration.order - b.registration.order);

  const groups = new Map<string, NavDestination[]>();
  if (shell.length > 0) groups.set("OVERVIEW", [...shell]);
  /* Seeded empty so that a declared group keeps its place in the order even before anything is
     registered into it, and so that `ECOSYSTEM` exists as the empty group the drawer must draw
     nothing for. */
  for (const heading of NAV_GROUP_ORDER) if (!groups.has(heading)) groups.set(heading, []);

  for (const feature of ordered) {
    const heading = headingOf(feature.registration.group);
    const destination = destinationFor(feature, input);
    if (destination === undefined) continue;
    const existing = groups.get(heading);
    if (existing === undefined) groups.set(heading, [destination]);
    else existing.push(destination);
  }

  return [...groups].map(([heading, destinations]) => ({ heading, destinations }));
}

/**
 * A registration's declared group, as the heading the drawer draws.
 *
 * `SCREENS-V4.md` §2.2 draws CLUSTER and ECOSYSTEM in capitals, and `NavGroup.heading` says the
 * capitals belong in the markup rather than in a `text-transform` — a screen reader handed an
 * acronym-shaped string that CSS made uppercase sometimes spells it out letter by letter, and one
 * that is uppercase in the markup is a word.
 *
 * A named function rather than a `.toUpperCase()` inlined at the one call site, because it is the
 * second half of the rule that matters and is easy to lose: a registration that declares "Cluster"
 * and one that declares "cluster" must land in **one** group. Without the fold they become two
 * headings over two halves of one list, and each half reads correctly on its own.
 */
function headingOf(group: string): string {
  return group.toUpperCase();
}

/** One feature's entry, or `undefined` when it has none right now. */
export function destinationFor(
  feature: FeatureStatus,
  input: Pick<
    NavigationInput,
    "landingFor" | "cluster" | "hideForbidden" | "countFor" | "childrenFor"
  >,
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
  const badge = badgeOf(state, reason, input.countFor?.(registration));
  /* Nested rows are drawn for a `ready` feature and for nothing else, which is the rule the badge
     above already obeys one line up and for the same reason. A tree of topic names under a topic
     service that is not answering is last-known-good data presented as a navigable structure: every
     row is a link to a page that will not load, and the `down` badge that would have said so is on
     the parent the reader has already scrolled past. A forbidden row's tree would additionally name
     objects this principal may not see, which is the leak the disabled row exists to prevent. */
  const children = state.kind === "ready" ? input.childrenFor?.(registration) : undefined;

  return {
    id: registration.id,
    label: registration.label,
    icon: registration.icon,
    href,
    state: state.kind,
    ...(forbidden ? { disabled: true, disabledReason: reason ?? "" } : {}),
    ...(badge === undefined ? {} : { badge }),
    ...(children === undefined ? {} : { children }),
  };
}

/**
 * The one badge a row gets, out of the two things that want to put one there.
 *
 * **The capability badge wins**, and this is the rule the whole fold exists to enforce. A count is
 * a number from the last snapshot that arrived; a `down` badge says the service that produces those
 * numbers is not answering. Drawing `128` beside a dead topic service is a reassuring picture of an
 * outage — the reader sees a figure, concludes the topics are fine, and the one marker that would
 * have told them otherwise is the one that was dropped to make room.
 *
 * The two states that carry no capability badge are also the two that must carry no count.
 * `forbidden` would print a figure counting objects this principal is not allowed to see, which is
 * the leak the disabled row exists to prevent; `not_configured` counts objects that do not exist.
 * So a count is drawn beside a `ready` feature and nowhere else.
 */
export function badgeOf(
  state: FeatureState,
  reason: string | undefined,
  count: NavCount | undefined,
): NavBadge | undefined {
  const capability = badgeFor(state, reason);
  if (capability !== undefined) return capability;
  if (state.kind !== "ready" || count === undefined) return undefined;
  return countBadge(count);
}

/**
 * A figure as a badge, with the tone the figure's *meaning* asks for.
 *
 * Three rules, each of which is a decision rather than a formatting choice:
 *
 * - **A quantity is neutral however large.** A cluster with 4,000 topics is a big cluster, not a
 *   broken one, and an amber `4,000` would train the reader to ignore amber.
 * - **A fraction is success only while it is whole.** `2/3` is danger the instant it appears; there
 *   is no amber step, because a broker that is gone is gone.
 * - **A defect of zero is no badge at all.** "0 rebalancing" is a permanently present marker, and a
 *   permanently present marker is one nobody looks at — the same argument {@link badgeFor} makes
 *   for saying nothing about a healthy feature.
 *
 * Returning `undefined` for a nonsensical figure — a fraction out of nothing, a negative count — is
 * the other half of "an unknown count produces no badge". A figure that cannot be true is not
 * shown, rather than shown as `0/0` and read as a cluster with no brokers.
 */
export function countBadge(count: NavCount): NavBadge | undefined {
  switch (count.kind) {
    case "total": {
      if (!Number.isFinite(count.value) || count.value < 0) return undefined;
      const text = formatCount(count.value);
      return { text, tone: "neutral", description: withNoun(text, count.noun) };
    }
    case "online": {
      if (!Number.isFinite(count.online) || !Number.isFinite(count.total) || count.total <= 0) {
        return undefined;
      }
      const text = `${formatCount(count.online)}/${formatCount(count.total)}`;
      const whole = `${formatCount(count.online)} of ${formatCount(count.total)}`;
      return {
        text,
        tone: count.online >= count.total ? "success" : "danger",
        description: `${withNoun(whole, count.noun)} online`,
      };
    }
    case "defect": {
      if (!Number.isFinite(count.value) || count.value <= 0) return undefined;
      const text = `${formatCount(count.value)} ${count.noun}`;
      /* The text is already a phrase rather than a bare number, so the description repeats it: it
         is the shortest sentence that is still true, and inventing a longer one here would put
         words in front of a screen-reader user that no sighted user is shown. */
      return { text, tone: count.severity, description: text };
    }
  }
}

/** `3 brokers`, or just `3` when the row's own label already names what is counted. */
function withNoun(text: string, noun: string | undefined): string {
  return noun === undefined || noun.length === 0 ? text : `${text} ${noun}`;
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
