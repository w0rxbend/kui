/**
 * A feature's **static** registration: everything the shell has to know before the feature has been
 * downloaded (ADR-012 amendment 2).
 *
 * ## Why a feature is registered in two halves
 *
 * A feature is downloaded only when it is needed, which is the whole point of ADR-012. But three
 * things about a feature have to be known *before* it is downloaded, or the product misbehaves in
 * ways the user cannot work around:
 *
 * - **Its URLs.** A bookmarked link to `/ui/clusters` must resolve on the first load, before any
 *   chunk has been fetched. If the router only learned the pattern once the feature had been
 *   imported, the first address it saw would be one it could not match, and the user would get a 404
 *   for a page that exists.
 * - **Its navigation entry and where that entry points.** The navigation is drawn on first paint,
 *   from capability state alone; nothing may be downloaded in order to draw a link.
 * - **Which service backs it**, so a capability frame naming a service can be matched to the feature
 *   it affects.
 *
 * All three are *data*: path shapes, a label, an icon name, a service id. Linking against them costs
 * a few bytes in the entry chunk and pulls no feature code with them. What must never appear on this
 * side of the line is the feature's component: that is the dynamic half, reached only through
 * {@link FeatureRegistration.load}'s `import()`, and `frontend.checkBundleShape` fails the build if
 * it leaks into the entry chunk's static imports.
 */
import type { Component } from "solid-js";
import type { IconName } from "../icon.jsx";

/**
 * The features this build can contain.
 *
 * A string union rather than an enum so that a registration table is checked exhaustively and a
 * bookmark naming a feature this build does not have simply fails to match.
 */
export type FeatureId = "clusters" | "topics" | "messages" | "consumers";

/**
 * The service that backs a feature.
 *
 * Carried on the registration rather than guessed from the feature id, because the two are not
 * always the same word: `topics` is a feature and `topic` is the service behind it. Guessing one
 * from the other is exactly the kind of near-miss that makes a feature silently stop reacting to its
 * service going down.
 */
export type ServiceId = string;

/** What a feature's chunk default-exports: the component the shell renders for its routes. */
export type FeatureComponent = Component;

export type FeatureRegistration = {
  readonly id: FeatureId;
  readonly serviceId: ServiceId;
  /** The words on the navigation entry, and the subject of every sentence written about it. */
  readonly label: string;
  readonly icon: IconName;
  /** The lettered heading the entry sits under in the drawer. */
  readonly group: string;
  /**
   * Where it sits.
   *
   * Explicit rather than "the order they were registered in", because registration order is a fact
   * about the code and navigation order is a product decision. It is also what keeps an entry from
   * jumping when its state changes: a navigation whose entries reshuffle when a service goes down is
   * one where the user clicks the wrong thing, because they aim at the position their muscle memory
   * learned and something else has moved into it.
   */
  readonly order: number;
  /**
   * Whether the entry means anything before a cluster is chosen.
   *
   * A cluster-scoped feature's URL has a cluster id in it, and until one is chosen the entry has no
   * destination to point at. It is left out of the navigation rather than pointed at a placeholder:
   * an empty path segment collapses, so `/ui/clusters//topics` is `/ui/clusters/topics`, which
   * matches no route. Every cluster-scoped entry in this product was once a dead link for exactly
   * that reason.
   */
  readonly requiresCluster: boolean;
  /**
   * Whether the navigation shows this entry at all.
   *
   * Almost always yes. The message browser is the exception: its URL names a topic as well as a
   * cluster, the navigation has no topic to name, and an entry that cannot build its own destination
   * is a link that goes nowhere. Its way in is the topic page. The feature still declares a label and
   * an order, because the shell names it wherever it is mentioned outside the navigation — the
   * fallback panel's "what still works" list, for one.
   */
  readonly sidebar: boolean;
  /**
   * Every URL this feature owns, as router patterns relative to the mount point.
   *
   * Registered with the router at start-up, before a byte of the feature has been downloaded, which
   * is what makes a deep link resolve on the first pass.
   */
  readonly routes: readonly string[];
  /**
   * Where the navigation entry goes.
   *
   * Built from the chosen cluster rather than being a constant, so that a cluster-scoped entry points
   * at the cluster the user is actually on. `undefined` means "there is nowhere to point right now",
   * which is what keeps the entry out of the navigation instead of pointing it at a broken address.
   */
  readonly landing: (cluster: string | undefined) => string | undefined;
  /**
   * The dynamic half: the `import()` that fetches the feature's chunk.
   *
   * The body of this thunk must be a bare dynamic `import()` of the feature package and nothing else.
   * Anything that also *statically* names the feature — a type annotation naming its component, a
   * re-export, a value pulled out for convenience — makes it reachable from the entry chunk, and the
   * bundler then ships it to every user on first paint, including users whose deployment has no such
   * service. Nothing about the source looks different when that happens, which is why the check reads
   * the build manifest's module graph rather than trusting a review to spot it.
   */
  readonly load: () => Promise<{ readonly default: FeatureComponent }>;
};
