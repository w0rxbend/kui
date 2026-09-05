/**
 * The one router, over every page in the application.
 *
 * ## Route patterns are static; only rendering is dynamically imported
 *
 * Every *feature's* patterns are in the table below, and they are registered before any feature has
 * been downloaded. The reason is concrete: a bookmarked link to a topic must resolve on the first
 * load, before the topics chunk exists in the browser. If the router only learned about a URL once
 * its feature had been imported, the very first thing it saw would be an address it could not match,
 * and the user would get a 404 for a page that exists.
 *
 * A route *pattern* is data — a path shape — so it costs a few bytes in the entry chunk and pulls
 * nothing else in. A route's *rendering* is behind `FeatureGate`, which does the `import()`.
 *
 * ## Every path is built, never written
 *
 * The table is a literal, so the router's `paths` proxy is typed from it: `paths.clusters[":clusterId"]`
 * does not exist and `paths.clusters("prod").topics()` does, and a renamed segment or a forgotten
 * parameter is a compile error. This is the TypeScript replacement for the rule that no HTTP path is
 * hand-written, applied to the browser's own addresses — and it is why nothing in this package
 * concatenates a URL out of string fragments.
 *
 * ## The base path
 *
 * The gateway serves the frontend under `<basePath>/ui/`, so the browser sees `/kui/ui/clusters`
 * while the pattern is `/clusters`. `RouterConfig.base` carries that prefix, which keeps the patterns
 * free of the mount point — the same reason `<base href>` exists for the assets. A hard-coded root
 * link broke this product behind a reverse proxy once already, so there is exactly one place the
 * prefix is applied and every link goes through it.
 */
import { createRouter, defineRoutes, type RouterInstance } from "@solidjs/router";
import type { JSX } from "@solidjs/web";
import type { FeatureId } from "@kui/kernel";

/** Where the frontend is served from, beneath whatever prefix the deployment uses. */
export const UiPath = "/ui";

/**
 * What renders inside each route.
 *
 * Passed in rather than imported so that this module has no opinion about capability state, sessions
 * or downloads, and so a test can mount the whole route table with four stubs and no stores.
 */
export type RouteViews = {
  readonly home: () => JSX.Element;
  readonly settings: () => JSX.Element;
  readonly notFound: () => JSX.Element;
  readonly forbidden: () => JSX.Element;
  /** One gate per feature. Which feature a route belongs to is decided here, in the table. */
  readonly feature: (id: FeatureId) => () => JSX.Element;
};

export function shellRoutes(views: RouteViews) {
  const gate = views.feature;

  return defineRoutes([
    { path: "/", component: views.home },
    { path: "/settings", component: views.settings },
    { path: "/forbidden", component: views.forbidden },

    // Nested rather than listed flat, and that is not a formatting choice: the typed path proxy is
    // built from the tree, and two sibling entries that repeat `/clusters/:clusterId` in their own
    // `path` produce two separate branches of which only the first is reachable through `paths`. The
    // nesting is what makes `paths.clusters("prod").topics()` and `.brokers()` both exist.
    {
      // No `component` on this node, and that is deliberate. A parent route's component is a
      // *layout*: the router renders it and passes the matched child as `props.children`, so a
      // layout that does not render its children replaces them. `gate("clusters")` renders one
      // feature and has no children slot, so with it here every address below `/clusters` drew the
      // cluster list — `/clusters/development/topics` and `/clusters/development/consumer-groups`
      // both showed it, while the drawer correctly highlighted Topics and Consumers and the address
      // bar said what the user had actually asked for. Three separate signals agreed and the page
      // disagreed with all of them.
      //
      // The index child below is what renders the cluster list at `/clusters` itself; this node
      // exists only to group the tree, which is what makes `paths.clusters("prod").topics()`
      // resolve.
      path: "/clusters",
      children: [
        { path: "/", component: gate("clusters") },
        { path: "/manage", component: gate("clusters") },
        {
          path: "/:clusterId",
          children: [
            { path: "/brokers", component: gate("clusters") },
            { path: "/brokers/:brokerId", component: gate("clusters") },

            { path: "/topics", component: gate("topics") },
            { path: "/topics/:topicName", component: gate("topics") },

            // The message browser hangs off a topic, which is why it has no navigation entry: the
            // drawer has no topic to name. Its route is registered all the same, so a link from a
            // topic page — or a bookmark somebody kept — resolves like any other.
            { path: "/topics/:topicName/messages", component: gate("messages") },
            { path: "/messages/track", component: gate("messages") },

            { path: "/consumer-groups", component: gate("consumers") },
            { path: "/consumer-groups/:groupId", component: gate("consumers") },
          ],
        },
      ],
    },

    // Anything else. Last, so a feature cannot accidentally shadow one of the shell's own addresses,
    // and present at all so that a mistyped URL gets the 404 page with working navigation rather than
    // an empty content area.
    { path: "*attempted", component: views.notFound },
  ]);
}

export type ShellRouter = RouterInstance<ReturnType<typeof shellRoutes>>;

/**
 * Builds the router for a deployment mounted at `basePath`.
 *
 * @param basePath `""` at the root, or e.g. `"/kui"`. The `/ui` segment is added here so that no
 *   caller has to remember it and no caller can spell it differently.
 */
export function createShellRouter(basePath: string, views: RouteViews): ShellRouter {
  return createRouter({
    routes: shellRoutes(views),
    base: `${basePath.replace(/\/$/, "")}${UiPath}`,
  });
}

/**
 * Where a feature's navigation entry goes, given the chosen cluster.
 *
 * `undefined` means "there is nowhere to point right now", which is what keeps a cluster-scoped entry
 * out of the navigation until a cluster is chosen, instead of pointing it at an address that matches
 * no route.
 *
 * Every branch goes through the typed proxy, so a change to the table above breaks this function
 * rather than producing links that quietly 404.
 */
export function landingFor(
  router: ShellRouter,
  feature: FeatureId,
  cluster: string | undefined,
): string | undefined {
  const paths = router.paths;
  switch (feature) {
    case "clusters":
      return paths.clusters();
    case "topics":
      return cluster === undefined ? undefined : paths.clusters(cluster).topics();
    case "consumers":
      return cluster === undefined ? undefined : paths.clusters(cluster)["consumer-groups"]();
    case "messages":
      // Its URL names a topic as well as a cluster, and the navigation has no topic to name.
      return undefined;
  }
}

/**
 * The cluster a URL names, if it names one.
 *
 * A URL naming a cluster wins over the stored selection on load: a link is usually pasted by a
 * colleague, and the recipient has to see what the sender saw. It is read from the path rather than
 * from a matched route because it has to be known before the router renders anything.
 *
 * The shape `<prefix>/clusters/<id>/…` is one the shell already routes through, so recognising it
 * here is not the shell knowing what a cluster is; it is the shell knowing where its own prefix ends.
 */
export function clusterInUrl(url: string, uiPrefix: string): string | undefined {
  const path = url.split(/[?#]/)[0] ?? "";
  const withoutOrigin = path.replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]*/i, "");
  const relative = withoutOrigin.startsWith(uiPrefix) ? withoutOrigin.slice(uiPrefix.length) : withoutOrigin;
  const segments = relative.split("/").filter((segment) => segment.length > 0);
  return segments[0] === "clusters" && segments[1] !== undefined && segments[1] !== "manage"
    ? segments[1]
    : undefined;
}
