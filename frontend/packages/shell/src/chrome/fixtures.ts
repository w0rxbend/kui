/**
 * The sample data the stories and the tests are built from.
 *
 * Two kinds of fixture live here, and the second kind is the point.
 *
 * The first is the design: the destinations, badges, cluster and tabs that appear in the five
 * screenshots, so that a story can be held up against the image it is meant to reproduce.
 *
 * The second is everything the screenshots do not show. Every screenshot in this project is of a
 * healthy cluster with short names and small numbers, and every serious defect this frontend has
 * shipped has been somewhere else: the longest string, the largest number, the state that only
 * occurs when a service is down. Those fixtures are here on purpose and they are not decoration.
 * `LONG_TOPIC` is a real shape of Kafka topic name, not an invented worst case.
 */

import type { ClusterSummary, NavDestination, NavGroup, Tab } from "./types.js";

/** A topic name of the length this product actually meets. */
export const LONG_TOPIC = "orders.payments.v2.dead-letter.retry-5m.eu-central-1.reprocessing";

/** The navigation exactly as the design draws it: healthy cluster, one group needing attention. */
export const NAV_GROUPS: readonly NavGroup[] = [
  {
    heading: "CLUSTER",
    destinations: [
      { id: "dashboard", label: "Dashboard", icon: "dashboard", href: "/dashboard" },
      {
        id: "brokers",
        label: "Brokers",
        icon: "brokers",
        href: "/brokers",
        badge: { text: "3/3", tone: "success", description: "3 of 3 online" },
      },
      {
        id: "topics",
        label: "Topics",
        icon: "topics",
        href: "/topics",
        badge: { text: "128", tone: "neutral", description: "128 topics" },
      },
      {
        id: "consumers",
        label: "Consumers",
        icon: "consumers",
        href: "/consumers",
        /* One, not fourteen. The badge counts the groups needing attention, not the groups. */
        badge: { text: "1", tone: "warning", description: "1 group needs attention" },
      },
    ],
  },
  {
    heading: "ECOSYSTEM",
    destinations: [
      { id: "schema", label: "Schema Registry", icon: "schema", href: "/schema" },
      { id: "connect", label: "Kafka Connect", icon: "connect", href: "/connect" },
      /* The one state that draws a *disabled* row, and the only one the fold produces: ADR-032
         renders `forbidden` as present, dimmed, not a link, with the reason in its accessible name.
         It used to read "Not built yet" with a `soon` badge, which was true while ksqlDB was M9's
         and is a sentence this wave made false — and a fixture claiming a shipped feature does not
         exist is the kind of stale citation four retrospectives have asked to stop finding. */
      {
        id: "ksql",
        label: "ksqlDB",
        icon: "ksql",
        href: "/ksql",
        disabled: true,
        disabledReason: "You do not have permission to run ksqlDB statements on this cluster",
      },
    ],
  },
];

/** The same navigation with a broker down and the counts unavailable. */
export const NAV_GROUPS_DEGRADED: readonly NavGroup[] = [
  {
    heading: "CLUSTER",
    destinations: [
      { id: "dashboard", label: "Dashboard", icon: "dashboard", href: "/dashboard" },
      {
        id: "brokers",
        label: "Brokers",
        icon: "brokers",
        href: "/brokers",
        /* Danger, not success, and not neutral: two of three brokers is an outage in progress. The
         * tone follows the meaning of the fraction, never the fact that there is a fraction. */
        badge: { text: "2/3", tone: "danger", description: "2 of 3 online, 1 offline" },
      },
      /* No badge at all: the topic count could not be fetched. `0` would be a lie and a spinner
       * would not fit. */
      { id: "topics", label: "Topics", icon: "topics", href: "/topics" },
      {
        id: "consumers",
        label: "Consumers",
        icon: "consumers",
        href: "/consumers",
        badge: { text: "6", tone: "warning", description: "6 groups need attention" },
      },
    ],
  },
  {
    heading: "ECOSYSTEM",
    destinations: [
      { id: "schema", label: "Schema Registry", icon: "schema", href: "/schema" },
      {
        id: "connect",
        label: "Kafka Connect",
        icon: "connect",
        href: "/connect",
        disabled: true,
        disabledReason: "The connect service is not answering",
      },
      {
        id: "ksql",
        label: "ksqlDB",
        icon: "ksql",
        href: "/ksql",
        disabled: true,
        disabledReason: "You do not have permission to run ksqlDB statements on this cluster",
      },
    ],
  },
];

export const HEALTHY_CLUSTER: ClusterSummary = {
  id: "prod-kyiv-01",
  name: "prod-kyiv-01",
  health: "healthy",
  version: "v3.7.0",
  brokerCount: 3,
};

/**
 * `M09`'s cluster: one under-replicated partition, which is what the head's first token becomes.
 *
 * The caption's first token is variable *in kind* — the health word when the cluster is clean, the
 * defect count when it is not — and this is the fixture that exercises the second half of that.
 */
export const DEFECTIVE_CLUSTER: ClusterSummary = {
  id: "staging-eu-01",
  name: "staging-eu-01",
  health: "degraded",
  version: "v3.7.0",
  brokerCount: 3,
  defects: { underReplicatedPartitions: 1 },
};

/**
 * A cluster nobody has counted the brokers of.
 *
 * Every screenshot in this project is of a cluster whose every figure arrived. This is the one that
 * did not, and the rule it pins is that the head drops the part it does not have rather than
 * writing `— brokers`, which reads as a missing dash and not as a missing figure.
 */
export const UNCOUNTED_CLUSTER: ClusterSummary = {
  id: "prod-kyiv-01",
  name: "prod-kyiv-01",
  health: "healthy",
};

export const DEGRADED_CLUSTER: ClusterSummary = {
  id: "prod-kyiv-01",
  name: "prod-kyiv-01",
  health: "degraded",
  version: "v3.7.0",
  brokerCount: 3,
};

export const UNREACHABLE_CLUSTER: ClusterSummary = {
  id: "prod-kyiv-01",
  name: "prod-kyiv-01",
  health: "unreachable",
  lastSeen: "4m ago",
};

/** A cluster whose version we could not read. Says so in words rather than with a dash. */
export const VERSIONLESS_CLUSTER: ClusterSummary = {
  id: "prod-kyiv-01",
  name: "prod-kyiv-01",
  health: "healthy",
};

/** The name a cluster gets when somebody names it after its purpose and its region and its owner. */
export const LONG_NAME_CLUSTER: ClusterSummary = {
  id: "long",
  name: "prod-eu-central-1-payments-platform-primary-01",
  health: "healthy",
  version: "v3.7.0-confluent-7.6.1",
};

export const CLUSTERS: readonly ClusterSummary[] = [
  HEALTHY_CLUSTER,
  { id: "staging-fra", name: "staging-fra", health: "degraded", version: "v3.6.1" },
  { id: "dev-local", name: "dev-local", health: "unreachable", lastSeen: "2h ago" },
  { id: "analytics", name: "analytics-eu", health: "healthy" },
];

/**
 * The topic tree of `SCREENS-V4.md` §2.2, in the order the *drawer* is expected to fix rather than
 * the order it is given.
 *
 * Deliberately jumbled: `internal` is written first and the largest prefix group last, so a
 * renderer that merely preserved the caller's order would draw the padlocked row at the top and
 * fail the case. The fold in `nav/topicTree.ts` emits them already sorted; this fixture is what
 * proves the drawer does not depend on that.
 */
export const TOPIC_TREE: readonly NavDestination[] = [
  {
    id: "prefix:internal",
    label: "internal",
    icon: "lock",
    href: "/topics?prefix=internal",
    badge: { text: "4", tone: "neutral", description: "4 topics" },
    rank: "internal",
  },
  {
    id: "prefix:orders.*",
    label: "orders.*",
    icon: "topics",
    href: "/topics?prefix=orders",
    badge: { text: "3", tone: "neutral", description: "3 topics" },
    rank: "prefix",
  },
  {
    id: "prefix:analytics.*",
    label: "analytics.*",
    icon: "topics",
    href: "/topics?prefix=analytics",
    badge: { text: "3", tone: "neutral", description: "3 topics" },
    rank: "prefix",
  },
];

/** The same navigation, with the Topics row expanded over the tree the design draws under it. */
export const NAV_GROUPS_WITH_TREE: readonly NavGroup[] = NAV_GROUPS.map((group) => ({
  ...group,
  destinations: group.destinations.map((destination) =>
    destination.id === "topics"
      ? { ...destination, children: TOPIC_TREE, expanded: true }
      : destination,
  ),
}));

/**
 * `ECOSYSTEM` with nothing registered into it — a deployment configured with no registry, no
 * Connect worker and no ksqlDB server.
 *
 * It was the group's *only* state until M9; it is now one state of two, and still the one to test,
 * because a heading over an empty list reads as a list that failed to load and the drawer must draw
 * nothing at all for it. ADR-032 hides an unconfigured feature rather than drawing it as a failure,
 * so this shape is reachable in production and not merely a fixture.
 */
export const NAV_GROUPS_EMPTY_ECOSYSTEM: readonly NavGroup[] = [
  NAV_GROUPS[0]!,
  { heading: "ECOSYSTEM", destinations: [] },
];

export const TOPIC_TABS: readonly Tab[] = [
  { id: "overview", label: "Overview", icon: "info", href: "#overview" },
  { id: "messages", label: "Messages", icon: "messages", href: "#messages" },
  { id: "consumers", label: "Consumers", icon: "consumers", href: "#consumers", count: 14 },
  { id: "settings", label: "Settings", icon: "settings", href: "#settings" },
];
