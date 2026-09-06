/**
 * The rows under the drawer's Topics entry: prefix groups, then `internal`
 * (`SCREENS-V4.md` §2.2, less its favourites).
 *
 * ## Why this is here and not in the drawer
 *
 * `prefixes()` next door already folds a name list into groups, with the two rules that make the
 * tree usable on a real cluster — the group count is capped so four thousand singletons do not
 * become four thousand rows, and internal topics come out as one padlocked row whatever their own
 * prefixes are. What was missing was the step after it: turning those groups into the destinations
 * the drawer draws.
 *
 * It is a fold over plain data, so it belongs beside the other fold rather than inside a component.
 * The practical consequence is that the interesting cases — a cluster with no topics at all, a
 * cluster whose names are all internal, a group cap that leaves a remainder — are decided by a
 * function a test can call, instead of by a component a test has to render.
 *
 * ## The favourites the design draws are not built, and the branch that drew them is gone
 *
 * `SCREENS-V4.md` §2.2 puts two starred topics above the prefix groups. This fold used to produce
 * them from a `favourites` list, `NavItem` sorted them to the top through a `rank` of its own, and
 * the whole path was reachable only from this file's test, a fixture and a story: nothing in the
 * product records a favourite, so the one production call site in `App.tsx` passed none and the
 * branch had survived two waves as an orphan. It is removed rather than left, because a branch that
 * only its own test can reach is a branch nobody can trust when the store finally arrives.
 *
 * Building it back is one input away — `favourites: readonly string[]`, filtered against `names` so
 * a deleted topic does not become a row leading to a 404, emitted before the groups and *also*
 * counted inside them, because the group's figure is "how many topics begin with orders" and
 * starring one has not stopped it beginning with orders. What has to exist first is the thing that
 * records the star: a control on a screen and somewhere to keep it.
 */

import type { NavDestination } from "../chrome/types.js";
import { INTERNAL_GROUP, OTHER_GROUP, prefixes } from "./prefixes.js";

export interface TopicTreeInput {
  /**
   * Every topic name on the cluster, internal ones included.
   *
   * The whole list, which is what the names-only index M5 adds exists to make cheap: the fold needs
   * every name to get the group counts right, and paging the full topic list to learn four thousand
   * names is what this endpoint was added to avoid.
   */
  readonly names: readonly string[];
  /**
   * Where a group row goes: the topic list, asked for the topics that row stands for.
   *
   * Handed in for the reason `NavigationInput.landingFor` is: this module concatenates no URLs, so
   * a renamed route segment is a compile error at the one place that builds addresses rather than a
   * drawer full of links that quietly 404.
   */
  readonly groupHref: (prefix: string) => string;
  /** How many prefix rows to keep. Defaults to `prefixes`' own cap. */
  readonly maxGroups?: number | undefined;
}

/**
 * The tree, in the order the drawer draws it.
 *
 * The order here is already the final one — `prefixes` orders the groups and `internal` comes last
 * — and `NavItem` sorts by rank as well, which is not redundant: it is what makes the rule hold for
 * a caller that assembles children from two sources in the other order.
 */
export function topicTree(input: TopicTreeInput): readonly NavDestination[] {
  return prefixes(input.names, input.maxGroups).map((group) => ({
    id: `prefix:${group.prefix}`,
    label: group.prefix,
    /* One neutral glyph for every prefix row, and a padlock for `internal`. `SCREENS-V4.md` §0.2
       and open finding 2 are explicit that the per-prefix glyphs the design draws — a cart for
       `orders`, a chart for `analytics` — are not derivable from a topic name and must not be
       guessed at. Inferring a shopping cart from three letters is exactly the invention this
       codebase spends its comments arguing against. */
    icon: group.prefix === INTERNAL_GROUP ? "lock" : "topics",
    href: input.groupHref(group.prefix),
    badge: {
      text: String(group.count),
      /* Neutral however large, the same rule `countBadge` applies to the Topics row above: a
         cluster with a lot of topics in one prefix is a busy cluster, not a broken one. */
      tone: "neutral",
      description: `${group.count} ${group.count === 1 ? "topic" : "topics"}`,
    },
    rank: group.prefix === INTERNAL_GROUP ? "internal" : "prefix",
  }));
}

/**
 * Where a prefix row goes: the topic list, asked for the topics that row stands for.
 *
 * ## Why the address is assembled here and the route is not
 *
 * The *route* comes from the caller — `KuiPaths.topics(cluster)`, built through the router's typed
 * proxy, so a renamed segment is a compile error rather than a drawer full of links that quietly
 * 404. What this adds is the query, which is not a route: `q` and `showInternal` are the topic
 * list endpoint's own parameters, declared in `TopicQueryCodecs` and documented in the generated
 * schema, and the list screen reads them off its own address.
 *
 * ## The three kinds of row, which are not three kinds of filter
 *
 * - A **prefix group** is `q=<segment>`. The row's label is written `orders.*` because that is what
 *   a person reads; the `.*` is presentation and is stripped, because `q` is a name search and not
 *   a glob — asking the server for `orders.*` literally would match nothing at all.
 * - **`internal`** is not a prefix. It is every topic whose name begins with an underscore, so the
 *   filter is the list's own `showInternal` switch rather than a search for a name.
 * - **`other`** is the residue the group cap left over ({@link prefixes}), which is by construction
 *   not describable as a search. It goes to the unfiltered list: a row that leads somewhere honest
 *   and wider than itself is better than a row that leads to a query matching nothing.
 */
export function topicGroupHref(listHref: string, group: string): string {
  if (group === INTERNAL_GROUP) return `${listHref}?showInternal=true`;
  if (group === OTHER_GROUP) return listHref;
  return `${listHref}?q=${encodeURIComponent(group.replace(/\.\*$/, ""))}`;
}
