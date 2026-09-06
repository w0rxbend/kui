/**
 * The rule that decides which marker a drawer row carries, and it is a rule about honesty.
 *
 * Two things want the badge: the capability fold, which knows whether the service behind the row is
 * answering, and a count, which is a number from the last snapshot that arrived. They cannot both
 * have it, and every case below is an argument about which one the reader is better served by.
 *
 * Nothing here renders. Deciding what a row says is a pure fold over capability states and figures,
 * which is why it lives in `navigation.ts` and not in `NavItem`; the drawing, and ADR-032's
 * rendering rules, are checked against a DOM in `chrome.test.tsx`.
 */
import { describe, expect, it } from "vitest";
import { Actions, ReasonCodes } from "@kui/api";
import type { FeatureRegistration, FeatureState } from "@kui/kernel";

import type { NavCount } from "../chrome/types.js";
import { ECOSYSTEM_GROUP, countBadge, destinationFor, navigationGroups } from "./navigation.js";

const topics: FeatureRegistration = {
  id: "topics",
  serviceId: "topic",
  viewAction: Actions.TopicView,
  label: "Topics",
  icon: "topics",
  group: "Cluster",
  order: 200,
  requiresCluster: true,
  sidebar: true,
  load: async () => ({}),
};

const clusters: FeatureRegistration = {
  ...topics,
  id: "clusters",
  serviceId: "cluster",
  viewAction: Actions.ClusterConfigView,
  label: "Clusters",
  icon: "brokers",
  order: 100,
  requiresCluster: false,
};

const ready: FeatureState = { kind: "ready" };
const down: FeatureState = {
  kind: "unavailable",
  code: ReasonCodes.UpstreamUnavailable,
  message: "",
  since: undefined,
};

const landing = (registration: FeatureRegistration, cluster: string | undefined) =>
  registration.requiresCluster
    ? cluster === undefined
      ? undefined
      : `/ui/clusters/${cluster}/topics`
    : "/ui/clusters";

/** One row, for a feature in a given state with a given figure beside it. */
const rowFor = (state: FeatureState, count: NavCount | undefined) =>
  destinationFor(
    { registration: topics, state },
    { landingFor: landing, cluster: "prod", countFor: () => count },
  );

describe("the figure a navigation row carries", () => {
  it("draws a plain quantity neutrally, however large it gets", () => {
    /* A cluster with 128 topics is a big cluster, not a broken one. An amber count would teach the
     * reader to ignore amber, which costs them the one badge that will matter later. */
    const badge = rowFor(ready, { kind: "total", value: 128 })?.badge;
    expect(badge?.text).toBe("128");
    expect(badge?.tone).toBe("neutral");
  });

  /* The rule the whole fold exists for. `128` beside a dead topic service is a reassuring picture
   * of an outage: the reader sees a figure, concludes the topics are fine, and the one marker that
   * would have told them otherwise is the one that was dropped to make room for it. */
  it("keeps the capability badge when a count would cover it up", () => {
    const badge = rowFor(down, { kind: "total", value: 128 })?.badge;
    expect(badge?.text).toBe("down");
    expect(badge?.tone).toBe("danger");
  });

  it("draws no badge at all for a count nobody could fetch", () => {
    /* Not a `0`. A zero is a statement about the cluster, and this is a statement about KUI. */
    expect(rowFor(ready, undefined)?.badge).toBeUndefined();
  });

  it("says nothing about objects a forbidden row's reader may not see", () => {
    const entry = destinationFor(
      { registration: topics, state: { kind: "forbidden" } },
      { landingFor: landing, cluster: "prod", countFor: () => ({ kind: "total", value: 128 }) },
    );
    expect(entry?.disabled).toBe(true);
    expect(entry?.badge).toBeUndefined();
  });

  it("takes its tone from what the figure means and not from its size", () => {
    /* `3/3` is success and `2/3` is danger with no amber step between them, because a broker that
     * is gone is gone; `128` is neutral because a lot of topics is not a problem. */
    expect(countBadge({ kind: "online", online: 3, total: 3 })?.tone).toBe("success");
    expect(countBadge({ kind: "online", online: 2, total: 3 })?.tone).toBe("danger");
    expect(countBadge({ kind: "online", online: 2, total: 3 })?.text).toBe("2/3");
    expect(countBadge({ kind: "total", value: 4000 })?.tone).toBe("neutral");
    expect(countBadge({ kind: "total", value: 4000 })?.text).toBe("4,000");
  });

  it("puts the whole figure in the description, because that is the accessible name", () => {
    /* "Brokers, 3 of 3 brokers online" is what a screen reader says; "Brokers 3/3" is not a
     * sentence, and a badge whose meaning is carried by its position is carried by nothing. */
    expect(countBadge({ kind: "online", online: 3, total: 3, noun: "brokers" })?.description).toBe(
      "3 of 3 brokers online",
    );
  });

  it("writes a defect as a phrase and takes its severity from the caller", () => {
    /* A rebalancing group settles by itself and is a warning; a failed task does not and is not. */
    const rebalancing = countBadge({
      kind: "defect",
      value: 1,
      noun: "rebalancing",
      severity: "warning",
    });
    expect(rebalancing?.text).toBe("1 rebalancing");
    expect(rebalancing?.tone).toBe("warning");
    expect(
      countBadge({ kind: "defect", value: 1, noun: "failed", severity: "danger" })?.tone,
    ).toBe("danger");
  });

  it("draws no badge for a defect of zero, or for a fraction out of nothing", () => {
    /* "0 rebalancing" is a permanently present marker, and a permanently present marker is one
     * nobody looks at. `0/0` is worse: it reads as a cluster with no brokers. */
    const none = countBadge({ kind: "defect", value: 0, noun: "rebalancing", severity: "warning" });
    expect(none).toBeUndefined();
    expect(countBadge({ kind: "online", online: 0, total: 0 })).toBeUndefined();
  });
});

describe("counts across the whole drawer", () => {
  const groups = (countFor: (feature: FeatureRegistration) => NavCount | undefined) =>
    navigationGroups({
      features: [
        { registration: clusters, state: ready },
        { registration: topics, state: ready },
      ],
      landingFor: landing,
      cluster: "prod",
      countFor,
    });

  it("folds a count into each destination it has one for, and only those", () => {
    const destinations = groups((feature) =>
      feature.id === "topics" ? { kind: "total", value: 128 } : undefined,
    ).flatMap((group) => group.destinations);

    expect(destinations.map((destination) => destination.badge?.text)).toEqual([undefined, "128"]);
    expect(destinations[1]?.badge).toMatchObject({ text: "128", tone: "neutral" });
  });

  it("still shows the dead service's badge when the count outlives the service", () => {
    /* The same rule as `destinationFor`'s, asserted through the whole fold, because this is the
     * one that reaches the drawer: a stale `128` beside a topic service that is not answering is a
     * reassuring picture of an outage. */
    const drawn = navigationGroups({
      features: [{ registration: topics, state: down }],
      landingFor: landing,
      cluster: "prod",
      countFor: () => ({ kind: "total", value: 128 }),
    }).flatMap((group) => group.destinations);

    expect(drawn[0]?.badge).toMatchObject({ text: "down", tone: "danger" });
  });

  it("does not move an entry when its figure appears", () => {
    /* The same property the five states already have: a drawer whose rows reshuffle when a number
     * arrives is one where the user clicks the thing that moved into the position they aimed at. */
    const order = (countFor: (feature: FeatureRegistration) => NavCount | undefined) =>
      groups(countFor)
        .flatMap((group) => group.destinations)
        .map((destination) => destination.id);

    expect(order(() => undefined)).toEqual(order(() => ({ kind: "total", value: 128 })));
  });
});

describe("the headings the drawer is given", () => {
  const drawn = () =>
    navigationGroups({
      features: [
        { registration: clusters, state: ready },
        { registration: topics, state: ready },
      ],
      landingFor: landing,
      cluster: "prod",
    });

  it("writes the headings uppercase, once, where the groups are assembled", () => {
    /* `NavGroup.heading` says the capitals belong in the markup rather than in a `text-transform`:
     * a screen reader given a transformed acronym-shaped string sometimes spells it out. Doing it
     * here rather than at each registration also means "Cluster" and "cluster" cannot become two
     * headings over two halves of one list. */
    expect(drawn().map((group) => group.heading)).toContain("CLUSTER");
  });

  /**
   * The fold, over registrations that do not already agree.
   *
   * The case above cannot fail on its own: both its registrations declare `group: "Cluster"`, so a
   * fold that did nothing at all would still produce one group — and the heading would read
   * "Cluster", which nothing here was looking at. Removing `.toUpperCase()` from `navigationGroups`
   * left the whole suite green, which is how a rule ships that no test can break.
   *
   * So this fixture disagrees with itself the way a real registration table eventually will: one
   * feature writes `"cluster"`, the other `"Cluster"`. Both belong in **one** group with **one**
   * heading, and the heading is the capitals the design draws.
   */
  it("folds registrations that spell their group differently into one heading", () => {
    const groups = navigationGroups({
      features: [
        { registration: { ...clusters, group: "cluster" }, state: ready },
        { registration: { ...topics, group: "Cluster" }, state: ready },
      ],
      landingFor: landing,
      cluster: "prod",
    });

    const cluster = groups.filter((group) => group.heading.toUpperCase() === "CLUSTER");
    expect(cluster).toHaveLength(1);
    expect(cluster[0]?.heading).toBe("CLUSTER");
    /* Both rows under it, and not one under each of two headings that read like two sections. */
    expect(cluster[0]?.destinations.map((destination) => destination.id)).toEqual([
      "clusters",
      "topics",
    ]);
  });

  /**
   * The tree, at the seam where a destination is built.
   *
   * `childrenFor` is what the frame hands `nav/topicTree.ts`'s fold through, and the rule that is
   * worth pinning is the one that is invisible on a healthy cluster: a row whose service is not
   * answering carries no tree. Every child would be a link to a page that will not load, and the
   * `down` badge that says so is on the parent the reader has already scrolled past.
   */
  it("nests children under a ready row and under no other", () => {
    const child = {
      id: "prefix:orders.*",
      label: "orders.*",
      icon: "topics" as const,
      href: "/ui/clusters/prod/topics?q=orders",
    };
    const nested = (state: FeatureState) =>
      destinationFor(
        { registration: topics, state },
        { landingFor: landing, cluster: "prod", childrenFor: () => [child] },
      );

    expect(nested(ready)?.children).toEqual([child]);
    expect(nested(down)?.children).toBeUndefined();
  });

  it("declares ECOSYSTEM and emits it empty until M9 registers something into it", () => {
    /* Its only state today. It is emitted rather than skipped so that adding Kafka Connect and
     * ksqlDB registers two features and changes nothing else — and so that the empty case is a
     * state something produces, which is what makes `NavDrawer`'s "draw nothing at all" rule
     * testable against real output rather than against a hand-written fixture. */
    const ecosystem = drawn().find((group) => group.heading === ECOSYSTEM_GROUP);
    expect(ecosystem).toBeDefined();
    expect(ecosystem?.destinations).toEqual([]);
  });

  it("puts CLUSTER before ECOSYSTEM whatever order the features arrive in", () => {
    const headings = drawn().map((group) => group.heading);
    expect(headings.indexOf("CLUSTER")).toBeLessThan(headings.indexOf(ECOSYSTEM_GROUP));
  });
});
