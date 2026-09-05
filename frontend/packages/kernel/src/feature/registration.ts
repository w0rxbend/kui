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
 *   for a page that exists. Those patterns live in the shell's route table rather than here, so that
 *   they stay literal and every link is built through the router's typed path proxy — a typo or a
 *   missing parameter is then a compile error rather than a dead link.
 * - **Its navigation entry.** The navigation is drawn on first paint, from capability state alone;
 *   nothing may be downloaded in order to draw a link.
 * - **Which service backs it**, so a capability frame naming a service can be matched to the feature
 *   it affects.
 *
 * All of that is *data*: a label, an icon name, a service id, a sort order. Linking against it costs
 * a few bytes in the entry chunk and pulls no feature code with it. What must never appear on this
 * side of the line is the feature's component: that is the dynamic half, reached only through
 * {@link FeatureRegistration.load}'s `import()`, and `frontend.checkBundleShape` fails the build if
 * it leaks into the entry chunk's static imports.
 */
import type { Component } from "solid-js";
import type { PermissionAction } from "@kui/api";
import type { IconName } from "../icon.jsx";

/**
 * The features this build can contain.
 *
 * A string union rather than an enum so that a registration table is checked exhaustively and a
 * bookmark naming a feature this build does not have simply fails to match.
 */
export type FeatureId = "clusters" | "topics" | "messages" | "consumers" | "schemas";

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

/**
 * What a feature's chunk looks like from the shell.
 *
 * The `default` export is the contract: it is the feature's root, and the shell renders it for every
 * one of that feature's routes. It is optional in the *type* only because a feature package can exist
 * before its root does — lane D builds the screens first — and a shell that failed to compile against
 * a half-built feature would stop the whole frontend rather than one screen. A module that arrives
 * without one renders a panel saying exactly that, which is a legible state; silently rendering
 * nothing is not.
 */
export type FeatureModule = {
  readonly default?: FeatureComponent | undefined;
};

/**
 * Reads a feature's root component out of whatever its chunk turned out to export.
 *
 * Every `load` thunk ends in this, and it is what keeps the shell compiling against feature packages
 * that are still being built. TypeScript synthesises a `default` for a module that has none — the
 * namespace object itself — so "does it have a default" cannot be asked of the type, only of the
 * value: a root component is a function, and a namespace object is not. Anything else is reported as
 * a feature with no screen rather than rendered as nothing.
 */
export function featureModule(chunk: unknown): FeatureModule {
  const candidate = (chunk as { readonly default?: unknown } | null)?.default;
  return typeof candidate === "function" ? { default: candidate as FeatureComponent } : {};
}

export type FeatureRegistration = {
  readonly id: FeatureId;
  readonly serviceId: ServiceId;
  /**
   * The permission a user needs to see this feature at all, from the generated RBAC vocabulary.
   *
   * It is stated rather than derived from {@link serviceId}, and that is the entire point. The
   * shell used to ask `permits(serviceId, "view", …)` — passing `"topic"` and `"view"` where the
   * server's vocabulary spells them `TOPIC` and `VIEW`, and where the cluster feature's resource is
   * not `"cluster"` at all but `CLUSTERCONFIG`. Matching is by exact string, so every question
   * answered "no" the moment `/auth/me` replied, and every destination in the drawer went dim with
   * "You do not have permission to view Topics." on a deployment with authentication *disabled* and
   * a principal holding a grant on every resource and every cluster.
   *
   * Typing it as `PermissionAction` is what stops that returning: the values come from `Actions`,
   * which the build emits from the server's own enums, so a rename on the server fails this file's
   * compilation instead of silently locking every operator out of the product.
   */
  readonly viewAction: PermissionAction;
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
   * The dynamic half: the `import()` that fetches the feature's chunk.
   *
   * The body of this thunk must be a dynamic `import()` of the feature package with a literal
   * specifier, and nothing that *statically* names the feature — no top-level `import` of it, no type
   * annotation naming its component, no value pulled out for convenience. Any of those makes the
   * feature reachable from the entry chunk, and the bundler then ships it to every user on first
   * paint, including users whose deployment has no such service. Nothing about the source looks
   * different when that happens, which is why the check reads the build manifest's module graph
   * rather than trusting a review to spot it. Chaining {@link featureModule} onto the import is safe:
   * it names no feature and the specifier stays literal.
   */
  readonly load: () => Promise<FeatureModule>;
};
