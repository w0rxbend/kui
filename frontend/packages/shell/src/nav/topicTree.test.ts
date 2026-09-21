/**
 * The rows under the drawer's Topics entry, as a fold over a name list.
 *
 * Everything interesting about the tree is decided here rather than in the component: which rows
 * exist, what they are called, what they count and where they sort. `chrome.test.tsx` asserts the
 * one thing that is genuinely about drawing — that the renderer imposes the order itself rather
 * than trusting whatever it was handed.
 */
import { describe, expect, it } from "vitest";

import { topicGroupHref, topicSubtree, topicTree } from "./topicTree.js";

const NAMES = [
  "orders.payments.v2",
  "orders.payments.v3",
  "orders.refunds",
  "analytics.clickstream",
  "analytics.sessions",
  "__consumer_offsets",
  "__transaction_state",
  "heartbeats",
];

const tree = (names: readonly string[] = NAMES, maxGroups?: number) =>
  topicTree({
    names,
    groupHref: (prefix) => `/ui/clusters/prod/topics?prefix=${prefix}`,
    ...(maxGroups === undefined ? {} : { maxGroups }),
  });

describe("the drawer's topic tree", () => {
  it("puts the padlocked row last, whatever the fold produced", () => {
    const rows = tree();
    expect(rows.at(-1)?.label).toBe("internal");
    expect(rows.at(-1)?.rank).toBe("internal");
    /* And every underscored name is inside it rather than a row of its own, whatever prefix it
       would otherwise have fallen under. */
    expect(rows.at(-1)?.badge?.text).toBe("2");
    expect(rows.map((row) => row.label)).not.toContain("__consumer_offsets");
  });

  it("emits no row for a topic, only for the groups a name list can be folded into", () => {
    /* The favourites branch is gone: it produced `topic:<name>` rows with a `favourite` rank from a
       list nothing in the product records, so a fold over names now produces group rows only. What
       it would take to bring it back is in the module's own header. */
    const rows = tree();
    expect(rows.every((row) => row.id.startsWith("prefix:"))).toBe(true);
    expect(rows.map((row) => row.rank)).not.toContain("favourite");
  });

  it("gives the internal row a padlock and every other row one neutral glyph", () => {
    /* `SCREENS-V4.md` §0.2 and open finding 2: the per-prefix glyphs the design draws — a cart for
     * `orders`, a chart for `analytics` — are not derivable from a topic name, and inferring a
     * shopping cart from three letters is precisely the invention this codebase argues against. */
    const rows = tree();
    expect(rows.find((row) => row.label === "internal")?.icon).toBe("lock");
    expect(new Set(rows.filter((row) => row.rank === "prefix").map((row) => row.icon))).toEqual(
      new Set(["topics"]),
    );
  });

  it("makes every count neutral, however large the group", () => {
    // The same rule `countBadge` applies to the Topics row above it: a busy prefix is a busy
    // prefix, and an amber count would teach the reader to ignore amber.
    const many = Array.from({ length: 4000 }, (_, index) => `orders.item-${index}`);
    const rows = tree(many);
    expect(rows[0]?.badge).toMatchObject({ text: "4000", tone: "neutral" });
  });

  it("says 'topic' rather than 'topics' for a group of one", () => {
    // The badge's description is the accessible name, so it is a sentence and not a fragment.
    const rows = tree(["heartbeats"]);
    expect(rows[0]?.badge?.description).toBe("1 topic");
  });

  it("builds every address through the caller rather than concatenating one", () => {
    const rows = tree();
    expect(rows[0]?.href).toBe("/ui/clusters/prod/topics?prefix=orders.*");
    expect(rows.find((row) => row.label === "internal")?.href).toBe(
      "/ui/clusters/prod/topics?prefix=internal",
    );
  });

  it("is empty for a cluster with no topics, rather than a tree of nothing", () => {
    expect(tree([])).toEqual([]);
  });

  it("honours the fold's cap so four thousand singletons are not four thousand rows", () => {
    const singletons = Array.from({ length: 40 }, (_, index) => `topic-${index}`);
    const rows = tree(singletons, 3);
    expect(rows.length).toBe(4);
    expect(rows.at(-1)?.label).toBe("other");
  });
});

/**
 * The seam the frame hands the fold through.
 *
 * `NavigationInput.childrenFor` treats `undefined` and `[]` as different answers — a leaf and a
 * branch holding nothing — and `destinationFor` omits the `children` key entirely for the first.
 * The rule that a cluster with no topics is a *leaf* lived inside `App.tsx`'s memo for two waves,
 * as a `names.length === 0` clause defended by a comment that named `NavItem` as the real guard;
 * `NavItem`'s guard was deletable at the same time with every case
 * `pnpm -C frontend test packages/shell` runs still green, so the rule was claimed twice and
 * asserted nowhere. It lives here now, where a case can call it.
 */
describe("the subtree the drawer's Topics row is given", () => {
  const subtree = (names: readonly string[]) =>
    topicSubtree({ names, groupHref: (prefix) => `/ui/clusters/prod/topics?prefix=${prefix}` });

  it("is no subtree at all for a cluster with no topics, rather than an empty one", () => {
    expect(subtree([])).toBeUndefined();
  });

  it("is no subtree when every name folds away, not a branch holding nothing", () => {
    /* The wider rule, and the reason the emptiness tested is the fold's output rather than its
       input: `prefixes` drops a zero-length name, so a list that is not empty can still produce no
       rows, and a `names.length` check at the call site would hand the drawer a branch with an
       empty list under it. */
    expect(subtree(["", ""])).toBeUndefined();
  });

  /**
   * The rows themselves, written out rather than compared against the fold beside them.
   *
   * This case used to read `expect(subtree(NAMES)).toEqual(tree())`, which is two calls to the same
   * function through two names: a mutation inside `topicTree` moves both sides at once and the
   * comparison goes on holding. The packet that wrote it disclosed exactly that. So the expectation
   * is a literal list — the three prefix groups `NAMES` folds into, in the order `prefixes` puts
   * them, with `internal` last — and the identity with `topicTree` is asserted separately, as the
   * narrower claim it actually is.
   */
  it("is the fold's own rows, in the fold's own order, when there are any", () => {
    const rows = subtree(NAMES);
    expect(rows?.map((row) => row.label)).toEqual([
      "orders.*",
      "analytics.*",
      "heartbeats",
      "internal",
    ]);
    /* The counts too, because a subtree that dropped a name would keep the labels and lose the
       figures — and the figure is what the row is for. */
    expect(rows?.map((row) => row.badge?.text)).toEqual(["3", "2", "1", "2"]);
    // And it is the same fold and not a second one: `topicSubtree` adds the empty case and nothing
    // else, which is the only thing it is allowed to add.
    expect(rows).toEqual(tree());
  });
});

/**
 * Where a prefix row goes.
 *
 * Three cases, and the reason they are three is that only one of them is a search. Getting this
 * wrong is silent: a link that lands on the unfiltered list looks exactly like a link that worked,
 * because the list it lands on does contain the topics the row named.
 */
describe("the address a prefix row leads to", () => {
  const list = "/ui/clusters/prod/topics";

  it("asks the list for the prefix, without the star the row is written with", () => {
    /* `q` is a name search, not a glob: `orders.*` sent literally matches nothing at all, and a
       search that matches nothing renders as "no topics" over a cluster that has three. */
    expect(topicGroupHref(list, "orders.*")).toBe(`${list}?q=orders`);
  });

  it("uses the list's own switch for internal topics rather than searching for a name", () => {
    // `internal` is every topic beginning with an underscore, whatever its own prefix is. There is
    // no name to search for; there is a switch, and this is what it is for.
    expect(topicGroupHref(list, "internal")).toBe(`${list}?showInternal=true`);
  });

  it("sends the capped remainder to the whole list, which no query can express", () => {
    // `other` is what the group cap left over — several prefixes at once — so no `q` can express
    // it. The unfiltered list is wider than the row and is at least true.
    expect(topicGroupHref(list, "other")).toBe(list);
  });

  it("encodes a prefix that is not URL-safe", () => {
    // A topic segment may hold anything Kafka allows, and a hand-built query string is how a search
    // for `a b` becomes a request for two parameters.
    expect(topicGroupHref(list, "a b.*")).toBe(`${list}?q=a%20b`);
  });
});
