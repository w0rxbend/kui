import { describe, expect, it } from "vitest";
import { createShellRouter, landingFor } from "./routes.jsx";
import { shellPaths } from "./paths.js";

/**
 * The route table, matched rather than rendered.
 *
 * `paths.test.ts` asserts that a link comes out spelled the way the design writes it. That is only
 * half of a working address: a link is correct when the router *resolves* it, and the two halves
 * are written in different files, so nothing in the type system connects them. The shipped symptom
 * of a disagreement is the 404 page on a URL the product itself just produced — which is precisely
 * what `/clusters/<id>` did before this table had a dashboard node.
 *
 * So these tests take the string `shellPaths` builds, hand it to the router, and insist that
 * something other than the wildcard matched. `match()` is pure — no rendering, no feature chunk
 * downloaded — so a deep link is checked here exactly as the first load would see it.
 */
const noop = () => null;
const views = {
  home: noop,
  settings: noop,
  forbidden: noop,
  notFound: noop,
  feature: () => noop,
};

/** The wildcard is last in the table, so falling through to it is the 404 page. */
const NotFoundPattern = "/ui/*attempted";

describe("the cluster-scoped dashboard address", () => {
  const router = createShellRouter("", views);
  const paths = shellPaths(router);

  it("resolves the address the tab strip builds", () => {
    const matched = router.match(paths.dashboard("prod-kyiv-01", "storage"));
    const leaf = matched.at(-1);

    expect(leaf?.pattern).toBe("/ui/clusters/:clusterId/dashboard/:tab");
    /* Both parameters, because wave 2's Overview reads its cluster and its tab from here rather
       than from the stored selection — a pasted link has to carry both. */
    expect(leaf?.params).toMatchObject({ clusterId: "prod-kyiv-01", tab: "storage" });
  });

  it("resolves the tabless address to the same node, not to the 404 page", () => {
    // `/dashboard` and `/dashboard/overview` are one page under two spellings, and only the longer
    // one is ever built. The shorter one is what somebody types, and it has to land somewhere.
    const matched = router.match("/ui/clusters/prod-kyiv-01/dashboard");

    expect(matched.length).toBeGreaterThan(0);
    expect(matched.at(-1)?.pattern).not.toBe(NotFoundPattern);
    expect(matched.at(-1)?.params).toMatchObject({ clusterId: "prod-kyiv-01" });
  });

  it("resolves the cluster's own address to the dashboard, not to the 404 page", () => {
    /* `/clusters/<id>` with no page named is the shortest thing anybody types and the shape a
       colleague pastes, and it used to fall through to the wildcard — so the product drew "that
       page does not exist" over a drawer that was, at the same moment, correctly listing that
       cluster's topics. Three signals agreed and the page disagreed with all of them. */
    const matched = router.match("/ui/clusters/prod-kyiv-01");
    const leaf = matched.at(-1);

    expect(leaf?.pattern).not.toBe(NotFoundPattern);
    expect(leaf?.pattern).toBe("/ui/clusters/:clusterId");
    expect(leaf?.params).toMatchObject({ clusterId: "prod-kyiv-01" });
  });

  it("resolves behind a reverse proxy, where the prefix is not /ui", () => {
    const behindProxy = createShellRouter("/kui", views);
    const link = shellPaths(behindProxy).dashboard("prod-kyiv-01");

    expect(link).toBe("/kui/ui/clusters/prod-kyiv-01/dashboard/overview");
    expect(behindProxy.match(link).at(-1)?.pattern).toBe(
      "/kui/ui/clusters/:clusterId/dashboard/:tab",
    );
  });

  it("leaves the addresses that already worked alone", () => {
    /* A new sibling under `/:clusterId` is the kind of change that shadows its neighbours: the
       cluster id is itself a parameter, so a table that grew `/dashboard` in the wrong place would
       start swallowing `/clusters/dashboard/...` or, worse, match `topics` as a tab. */
    expect(router.match(paths.home()).at(-1)?.pattern).not.toBe(NotFoundPattern);
    const topics = router.match(paths.topics("prod")).at(-1);
    expect(topics?.pattern).toBe("/ui/clusters/:clusterId/topics");
    expect(router.match(paths.topicMessages("prod", "orders")).at(-1)?.pattern).toBe(
      "/ui/clusters/:clusterId/topics/:topicName/messages",
    );
    expect(router.match(paths.manageClusters()).at(-1)?.pattern).toBe("/ui/clusters/manage");
    /* A tab is one segment. Anything deeper is genuinely not a page. */
    expect(router.match("/ui/clusters/prod/dashboard/overview/extra").at(-1)?.pattern).toBe(
      NotFoundPattern,
    );
  });
});

/**
 * Alerts is a route, not a dashboard tab, and the address the drawer links to is the one the
 * router resolves.
 *
 * The two halves are still written in different files — `landingFor` builds the link and the table
 * matches it — so nothing in the type system connects them and a 404 on an address the product
 * itself produced is the shipped symptom. `/clusters/<id>` had exactly that defect until wave 5.
 *
 * The tab alternative is ruled out here rather than left to be discovered: a tab would live in the
 * shell's `overview/`, and `@kui/feature-alerts` reaching into it would invert the dependency the
 * feature split exists to keep. So `/dashboard/alerts` is deliberately *not* a page, and this case
 * says so out loud, because nothing else would notice if somebody added it.
 */
describe("the alerts address", () => {
  const router = createShellRouter("", views);

  it("resolves the address the drawer's Alerts row links to", () => {
    const link = landingFor(router, "alerts", "prod-kyiv-01");
    expect(link).toBe("/ui/clusters/prod-kyiv-01/alerts");
    const leaf = router.match(link!).at(-1);
    expect(leaf?.pattern).toBe("/ui/clusters/:clusterId/alerts");
    expect(leaf?.params).toMatchObject({ clusterId: "prod-kyiv-01" });
  });

  it("has nowhere to point until a cluster is chosen", () => {
    /* The rule every cluster-scoped entry keeps: an empty segment collapses, so
       `/ui/clusters//alerts` is `/ui/clusters/alerts`, which matches the cluster list's own
       `/manage` neighbourhood rather than an alerts screen. `undefined` keeps the row out of the
       drawer instead. */
    expect(landingFor(router, "alerts", undefined)).toBeUndefined();
  });

  it("leaves the dashboard's tabs alone: alerts is not one of them", () => {
    const tab = router.match("/ui/clusters/prod-kyiv-01/dashboard/alerts").at(-1);
    // It *matches* — every one-segment tab does — and it matches the dashboard, which is the point:
    // the alerts screen is somewhere else entirely, and the two must not be one address.
    expect(tab?.pattern).toBe("/ui/clusters/:clusterId/dashboard/:tab");
    expect(tab?.pattern).not.toBe("/ui/clusters/:clusterId/alerts");
  });
});
