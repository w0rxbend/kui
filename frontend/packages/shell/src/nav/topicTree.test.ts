/**
 * The rows under the drawer's Topics entry, as a fold over a name list.
 *
 * Everything interesting about the tree is decided here rather than in the component: which rows
 * exist, what they are called, what they count and where they sort. `chrome.test.tsx` asserts the
 * one thing that is genuinely about drawing — that the renderer imposes the order itself rather
 * than trusting whatever it was handed.
 */
import { describe, expect, it } from "vitest";

import { topicTree } from "./topicTree.js";

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

const tree = (
  names: readonly string[] = NAMES,
  favourites: readonly string[] = [],
  maxGroups?: number,
) =>
  topicTree({
    names,
    favourites,
    topicHref: (name) => `/ui/clusters/prod/topics/${name}`,
    groupHref: (prefix) => `/ui/clusters/prod/topics?prefix=${prefix}`,
    ...(maxGroups === undefined ? {} : { maxGroups }),
  });

describe("the drawer's topic tree", () => {
  it("puts the favourites first and internal last, whatever the fold produced", () => {
    const rows = tree(NAMES, ["analytics.clickstream"]);
    expect(rows[0]?.label).toBe("analytics.clickstream");
    expect(rows[0]?.rank).toBe("favourite");
    expect(rows.at(-1)?.label).toBe("internal");
    expect(rows.at(-1)?.rank).toBe("internal");
  });

  it("counts a favourite in its group as well as on its own row", () => {
    /* The design draws `orders.payments.v2` starred at the top and still counts it in `orders.* 3`,
     * because the group's figure is "how many topics begin with orders" and starring one has not
     * changed that. A tree whose children summed to less than its parent is a tree somebody will
     * spend an afternoon reconciling. */
    const rows = tree(NAMES, ["orders.payments.v2"]);
    const orders = rows.find((row) => row.label === "orders.*");
    expect(orders?.badge?.text).toBe("3");
  });

  it("drops a favourite the cluster no longer has", () => {
    // A row that leads to a 404 is worse than a missing row: the drawer would be asserting the
    // topic exists, which is the one thing it is in a position to know.
    const rows = tree(NAMES, ["orders.deleted", "orders.refunds"]);
    expect(rows.filter((row) => row.rank === "favourite").map((row) => row.label)).toEqual([
      "orders.refunds",
    ]);
  });

  it("keeps a starred internal topic at the top and inside the padlocked count", () => {
    /* Starring is a statement about attention, not about what kind of topic it is. */
    const rows = tree(NAMES, ["__consumer_offsets"]);
    expect(rows[0]?.label).toBe("__consumer_offsets");
    expect(rows.find((row) => row.label === "internal")?.badge?.text).toBe("2");
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
    const rows = tree(NAMES, ["orders.refunds"]);
    expect(rows[0]?.href).toBe("/ui/clusters/prod/topics/orders.refunds");
    expect(rows.find((row) => row.label === "internal")?.href).toBe(
      "/ui/clusters/prod/topics?prefix=internal",
    );
  });

  it("is empty for a cluster with no topics, rather than a tree of nothing", () => {
    expect(tree([], ["orders.refunds"])).toEqual([]);
  });

  it("honours the fold's cap so four thousand singletons are not four thousand rows", () => {
    const singletons = Array.from({ length: 40 }, (_, index) => `topic-${index}`);
    const rows = tree(singletons, [], 3);
    expect(rows.length).toBe(4);
    expect(rows.at(-1)?.label).toBe("other");
  });
});
