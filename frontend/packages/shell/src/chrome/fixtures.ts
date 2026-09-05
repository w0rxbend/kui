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

import type { ClusterSummary, NavGroup, Tab } from "./types.js";

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
      {
        id: "ksql",
        label: "KSQL DB",
        icon: "ksql",
        href: "/ksql",
        disabled: true,
        disabledReason: "Not built yet",
        badge: { text: "soon", tone: "neutral", description: "not built yet" },
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
        label: "KSQL DB",
        icon: "ksql",
        href: "/ksql",
        disabled: true,
        disabledReason: "Not built yet",
        badge: { text: "soon", tone: "neutral", description: "not built yet" },
      },
    ],
  },
];

export const HEALTHY_CLUSTER: ClusterSummary = {
  id: "prod-kyiv-01",
  name: "prod-kyiv-01",
  health: "healthy",
  version: "v3.7.0",
};

export const DEGRADED_CLUSTER: ClusterSummary = {
  id: "prod-kyiv-01",
  name: "prod-kyiv-01",
  health: "degraded",
  version: "v3.7.0",
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

export const TOPIC_TABS: readonly Tab[] = [
  { id: "overview", label: "Overview", icon: "info", href: "#overview" },
  { id: "messages", label: "Messages", icon: "messages", href: "#messages" },
  { id: "consumers", label: "Consumers", icon: "consumers", href: "#consumers", count: 14 },
  { id: "settings", label: "Settings", icon: "settings", href: "#settings" },
];
