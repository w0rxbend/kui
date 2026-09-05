/**
 * The shell's implementation of {@link KuiPaths} — every link a feature builds, built through the
 * router's typed proxy.
 *
 * ## Why the features do not build these themselves
 *
 * A feature that wrote `` `/clusters/${cluster}/topics/${name}` `` would go on compiling, and go on
 * producing links, after somebody renamed a segment in the route table. The links would simply stop
 * matching, and the symptom — a 404 for a page that plainly exists — appears only when a human
 * clicks that one link. The router's `paths` proxy is built *from* the route table, so the same
 * rename is a type error here instead, in one file, before anything ships.
 *
 * That is the whole reason `KuiPaths` is an interface in the kernel and its implementation lives
 * here: the features get link-building without getting the route table, and the route table stays
 * the only place a URL shape is written down.
 *
 * ## Encoding
 *
 * Nothing here encodes anything, and that is not an oversight. The proxy's parameter substitution
 * does it: a topic called `orders/payments` or a group id with a space reaches the address bar
 * correctly encoded because the router encodes it, once, for every caller. A feature that
 * pre-encoded would produce a double-encoded link — `%252F` — which resolves to a topic that does
 * not exist, and which is remarkably hard to spot by reading either side.
 */
import type { KuiPaths } from "@kui/kernel";
import type { ShellRouter } from "./routes.jsx";

export function shellPaths(router: ShellRouter): KuiPaths {
  const paths = router.paths;

  return {
    home: () => paths(),
    settings: () => paths.settings(),
    clusters: () => paths.clusters(),
    manageClusters: () => paths.clusters.manage(),
    brokers: (cluster) => paths.clusters(cluster).brokers(),
    /* The extra `()` is the proxy's terminating call: a parameterised node returns another node,
       and calling it with no arguments is what turns it into a string. */
    broker: (cluster, brokerId) => paths.clusters(cluster).brokers(String(brokerId))(),
    topics: (cluster) => paths.clusters(cluster).topics(),
    topic: (cluster, name) => paths.clusters(cluster).topics(name)(),
    topicMessages: (cluster, name) => paths.clusters(cluster).topics(name).messages(),
    trackMessages: (cluster) => paths.clusters(cluster).messages.track(),
    consumerGroups: (cluster) => paths.clusters(cluster)["consumer-groups"](),
    consumerGroup: (cluster, groupId) =>
      `${paths.clusters(cluster)["consumer-groups"]()}/${encodeURIComponent(groupId)}`,
  };
}
