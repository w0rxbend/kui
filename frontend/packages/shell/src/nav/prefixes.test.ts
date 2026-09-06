/**
 * The fold from a topic name list to the drawer's tree.
 *
 * The interesting cases are all about size. The captures were taken against a cluster with 128
 * topics; the clusters this product is installed on have thousands, and every rule here exists to
 * keep the drawer the same shape on both.
 */
import { describe, expect, it } from "vitest";

import { MAX_PREFIX_GROUPS, isInternalTopic, prefixes } from "./prefixes.js";

describe("the topic tree's prefix groups", () => {
  it("groups by the first segment and files the bookkeeping topics apart", () => {
    expect(prefixes(["orders.a", "orders.b", "__consumer_offsets"])).toEqual([
      { prefix: "orders.*", count: 2 },
      { prefix: "internal", count: 1 },
    ]);
  });

  it("puts every internal topic under one row, whatever its own prefix is", () => {
    /* Otherwise `__consumer_offsets` and `__transaction_state` each claim a row above `orders.*`,
     * which is two rows spent on the two topics an operator is least likely to want. */
    expect(prefixes(["__consumer_offsets", "__transaction_state", "_schemas"])).toEqual([
      { prefix: "internal", count: 3 },
    ]);
    expect(isInternalTopic("_schemas")).toBe(true);
    expect(isInternalTopic("orders.payments")).toBe(false);
  });

  it("writes a topic with no prefix as itself rather than claiming children", () => {
    /* `heartbeats.*` over a single topic called `heartbeats` sends whoever expands it looking for
     * children that do not exist. */
    expect(prefixes(["heartbeats"])).toEqual([{ prefix: "heartbeats", count: 1 }]);
  });

  it("splits on the dot alone", () => {
    /* `orders-eu.payments` and `orders-us.payments` share three letters and nothing else, and a
     * hyphen-splitting fold would file them together and claim a relationship between them. */
    expect(prefixes(["orders-eu.payments", "orders-us.payments"])).toEqual([
      { prefix: "orders-eu.*", count: 1 },
      { prefix: "orders-us.*", count: 1 },
    ]);
  });

  it("caps the rows and still counts everything that did not fit", () => {
    /* The failure this module exists to prevent: four thousand topics that share no prefix must not
     * become four thousand rows. They become the cap plus one `other` row, and the figures still
     * add up — a tree whose children sum to less than the number on its parent is one somebody
     * spends an afternoon reconciling. */
    const names = Array.from({ length: 4000 }, (_, index) => `topic-${index}`);
    const rows = prefixes(names);

    expect(rows.length).toBe(MAX_PREFIX_GROUPS + 1);
    expect(rows.at(-1)?.prefix).toBe("other");
    expect(rows.reduce((sum, row) => sum + row.count, 0)).toBe(4000);
  });

  it("keeps the largest groups, and orders ties the same way every time", () => {
    /* A refetch that returns the names in another order must not reshuffle the rows under the
     * reader's cursor — the same argument `navigation.ts` makes about entries that move. */
    const names = ["b.1", "b.2", "b.3", "a.1", "a.2", "c.1"];
    expect(prefixes(names, 2)).toEqual([
      { prefix: "b.*", count: 3 },
      { prefix: "a.*", count: 2 },
      { prefix: "other", count: 1 },
    ]);
    expect(prefixes([...names].reverse(), 2)).toEqual(prefixes(names, 2));
  });

  it("has nothing to say about a cluster with no topics", () => {
    /* No rows, and in particular no `other 0`: a group of nothing is not a summary of anything. */
    expect(prefixes([])).toEqual([]);
  });
});
