/**
 * The single place in the whole frontend where a feature package is named (ADR-012).
 *
 * ## Read this before adding an entry
 *
 * The body of a `load` thunk must be a bare `import("@kui/feature-…")` and **nothing else**. That
 * expression is the split point the bundler works from: everything reachable only through it goes
 * into a chunk of its own, which the browser fetches the first time the thunk is called.
 *
 * The split is easy to destroy by accident, and destroying it looks like nothing. A static
 * `import` of the package at the top of this file, a type annotation naming the feature's component,
 * a value pulled out "for convenience" — any of them makes the feature reachable from the entry
 * chunk, and the bundler then ships it to every user on first paint, including users whose
 * deployment has no such service at all. Nothing about the source looks different when that
 * happens, which is why `frontend.checkBundleShape` asserts the shape of the build manifest's
 * module graph rather than trusting a reviewer to spot it.
 *
 * Everything *else* here is ordinary static data — a label, an icon name, a service id, a sort
 * order — and the shell links against it normally, because all of it has to be known before anything
 * is downloaded (ADR-012 amendment 2): the navigation is drawn on first paint. The URL patterns are
 * not here: they live in `routing/routes.tsx` as literals, so that every link is built through the
 * router's typed path proxy rather than assembled out of string fragments.
 */
import { featureModule, type FeatureRegistration } from "@kui/kernel";

/**
 * The four features this build contains, in navigation order.
 *
 * The orders leave wide gaps: they are a product decision about where an operator's eye goes, and
 * the sequence — the cluster, then its topics, then the records in them, then who is reading them —
 * is the one the reference products use and the one operators already have in their fingers.
 */
export const featureRegistry: readonly FeatureRegistration[] = [
  {
    id: "clusters",
    // The feature is `clusters` and the service behind it is `cluster`, singular. The two are not
    // the same word, which is exactly why neither is guessed from the other.
    serviceId: "cluster",
    label: "Clusters",
    icon: "brokers",
    group: "Cluster",
    order: 100,
    requiresCluster: false,
    sidebar: true,
    load: () => import("@kui/feature-clusters").then(featureModule),
  },
  {
    id: "topics",
    serviceId: "topic",
    label: "Topics",
    icon: "topics",
    group: "Cluster",
    order: 200,
    requiresCluster: true,
    sidebar: true,
    load: () => import("@kui/feature-topics").then(featureModule),
  },
  {
    id: "messages",
    serviceId: "message",
    label: "Messages",
    icon: "messages",
    group: "Cluster",
    order: 250,
    requiresCluster: true,
    // The one feature that is deliberately absent from the drawer. Its URL names a topic as well as
    // a cluster, and the drawer has no topic to name; an entry that cannot build its own destination
    // is a link that goes nowhere. Its way in is the "Browse messages" action on a topic, which does
    // know the topic. It still declares a label, because the shell names it in the fallback panel's
    // "what still works" list.
    sidebar: false,
    load: () => import("@kui/feature-messages").then(featureModule),
  },
  {
    id: "consumers",
    serviceId: "consumer",
    label: "Consumers",
    icon: "consumers",
    group: "Cluster",
    order: 300,
    requiresCluster: true,
    sidebar: true,
    load: () => import("@kui/feature-consumers").then(featureModule),
  },
];

/** The registration for one id, or `undefined` when this build has no such feature. */
export function registrationOf(id: string): FeatureRegistration | undefined {
  return featureRegistry.find((registration) => registration.id === id);
}
