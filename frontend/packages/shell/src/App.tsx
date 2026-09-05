import { lazy, Loading } from "solid-js";
import { createRouter, defineRoutes } from "@solidjs/router";
import { readBootstrap } from "./bootstrap.js";

/**
 * The scaffold's proof page: the smallest thing that exercises the whole chain end to end.
 *
 * It is not a screen and no screen is ported here (DEVPLAN §4 forbids it). It exists to make four
 * claims observable in a browser rather than asserted in a document:
 *
 *  1. the gateway's bootstrap block reaches the browser and is read (`basePath`, `apiBase`,
 *     `buildVersion` are rendered);
 *  2. the router is mounted under the deployment's base path, so a deep link works behind a
 *     reverse proxy as well as at the root;
 *  3. a feature package is reached only through `lazy(() => import(...))`, so it arrives as its
 *     own chunk after the shell has already rendered;
 *  4. the stylesheet is part of the build and is served from the same origin.
 *
 * Lane C replaces this file with the real shell (SOL-024 … SOL-026).
 */

const bootstrap = readBootstrap();

/**
 * Where the router thinks it is mounted.
 *
 * The gateway serves the application under `<basePath>/ui/`, so a URL the browser sees is
 * `/kui/ui/clusters` while the route pattern is `/clusters`. Handing the router that prefix is
 * what keeps the patterns free of the mount point — the same reason `<base href>` exists for the
 * assets.
 */
const routerBase = `${bootstrap.basePath}/ui`;

/**
 * The clusters feature, reached the only way a feature may ever be reached.
 *
 * `lazy` + `import()` is the loading seam itself: Vite gives this specifier its own chunk, the
 * build manifest records it under the entry's `dynamicImports`, and nothing of the feature is in
 * the entry chunk. A static `import Clusters from "@kui/feature-clusters"` here would be a build
 * failure once SOL-010 lands, and that is deliberate.
 */
const Clusters = lazy(() => import("@kui/feature-clusters"));

const routes = defineRoutes([
  { path: "/", component: Home },
  { path: "/clusters", component: ClustersRoute },
]);

const Router = createRouter({ routes, base: routerBase });

export function App() {
  return (
    <Router>
      {(props) => (
        <main class="kui-proof">
          <h1 class="kui-proof__title">KUI</h1>
          <p class="kui-proof__lede">
            The TypeScript and SolidJS build is being served by the gateway, from the gateway's own
            resources, on one origin.
          </p>

          <nav class="kui-proof__nav" aria-label="Proof pages">
            {/* Built through the router's typed path proxy rather than written by hand: a typo or
                a missing parameter is a compile error, which is the UI-side half of this
                repository's "never hand-write a path" rule. */}
            <a href={Router.paths()}>Bootstrap</a>
            <a href={Router.paths.clusters()}>Load a feature on demand</a>
          </nav>

          <section class="kui-proof__panel">{props.children}</section>
        </main>
      )}
    </Router>
  );
}

function Home() {
  return (
    <dl class="kui-proof__facts">
      <dt>Base path</dt>
      <dd data-testid="base-path">{bootstrap.basePath === "" ? "(mounted at the root)" : bootstrap.basePath}</dd>
      <dt>API base</dt>
      <dd data-testid="api-base">{bootstrap.apiBase}</dd>
      <dt>Build version</dt>
      <dd data-testid="build-version">{bootstrap.buildVersion}</dd>
    </dl>
  );
}

function ClustersRoute() {
  return (
    <Loading fallback={<p class="kui-proof__pending">Loading the clusters feature…</p>}>
      <Clusters />
    </Loading>
  );
}
