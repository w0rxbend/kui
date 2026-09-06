/**
 * The rows under the drawer's Topics entry: favourites by exact name, then prefix groups, then
 * `internal` (`SCREENS-V4.md` §2.2).
 *
 * ## Why this is here and not in the drawer
 *
 * `prefixes()` next door already folds a name list into groups, with the two rules that make the
 * tree usable on a real cluster — the group count is capped so four thousand singletons do not
 * become four thousand rows, and internal topics come out as one padlocked row whatever their own
 * prefixes are. What was missing was the step after it: turning those groups into the destinations
 * the drawer draws, with somewhere for the favourites to sit.
 *
 * It is a fold over plain data, so it belongs beside the other fold rather than inside a component.
 * The practical consequence is that the interesting cases — a favourite that no longer exists, a
 * cluster with no topics at all, a favourite that is an internal topic — are decided by a function
 * a test can call, instead of by a component a test has to render.
 *
 * ## The favourites are not a fourth prefix group
 *
 * A favourite is an exact topic name a person chose, and it keeps its own row *and* its membership
 * of whatever group it belongs to. That double-counting is deliberate: the design draws
 * `orders.payments.v2` starred at the top and still counts it in `orders.* 3`, because the group's
 * figure is "how many topics begin with orders" and a starred one has not stopped beginning with
 * orders. A tree whose children summed to less than its parent is a tree somebody will spend an
 * afternoon reconciling. A starred internal topic gets the same treatment for the same reason:
 * starring a topic is a statement about attention, not about what kind of topic it is, so it keeps
 * its row at the top and stays inside the padlocked count.
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
   * The topics this person starred, in the order they want them.
   *
   * Kept in the caller's order rather than sorted, because it is a list somebody arranged. Names
   * that are not on `names` are dropped: a favourite that has been deleted is a row that leads to a
   * 404, and a drawer that keeps offering it is a drawer that lies about what the cluster holds.
   */
  readonly favourites?: readonly string[] | undefined;
  /**
   * Where a row goes. Two shapes, because a favourite is one topic and a group is a filtered list.
   *
   * Handed in for the reason `NavigationInput.landingFor` is: this module concatenates no URLs, so
   * a renamed route segment is a compile error at the one place that builds addresses rather than a
   * drawer full of links that quietly 404.
   */
  readonly topicHref: (name: string) => string;
  readonly groupHref: (prefix: string) => string;
  /** How many prefix rows to keep. Defaults to `prefixes`' own cap. */
  readonly maxGroups?: number | undefined;
}

/**
 * The tree, in the order the drawer draws it.
 *
 * The order here is already the final one; `NavItem` sorts by rank as well, which is not redundant
 * — it is what makes the rule hold for a caller that assembles children from two sources in the
 * other order.
 */
export function topicTree(input: TopicTreeInput): readonly NavDestination[] {
  const known = new Set(input.names);
  const starred = (input.favourites ?? []).filter((name) => known.has(name));

  const favourites: readonly NavDestination[] = starred.map((name) => ({
    id: `topic:${name}`,
    label: name,
    /* The star is the row's own glyph rather than a trailing marker: it is what tells a reader at a
       glance which of these rows is an exact topic and which is a group of them, and the two kinds
       are otherwise identical in shape. */
    icon: "star",
    href: input.topicHref(name),
    rank: "favourite",
  }));

  const groups: readonly NavDestination[] = prefixes(input.names, input.maxGroups).map((group) => ({
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

  return [...favourites, ...groups];
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
