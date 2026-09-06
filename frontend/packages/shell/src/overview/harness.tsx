/**
 * A dashboard mounted at an address, for the tests and the stories.
 *
 * ## Why the real router and not a stub
 *
 * `Overview` reads its cluster and its tab from `useParams()`, and the whole argument for doing so
 * is that the address is the single source of truth. A fake params provider would let a test assert
 * that the component reads *something* while leaving the interesting question — does
 * `/clusters/prod/dashboard/storage` actually resolve to this component with `tab` set — answered by
 * nobody. So this builds the product's own route table over a memory history and lets the router
 * match, which is the same code path a pasted link takes on a cold load.
 *
 * `memoryHistory` rather than the browser's, because a story runs inside Storybook's own iframe and
 * a test inside jsdom's one URL: both would otherwise report an address that has nothing to do with
 * the case being drawn, and every story would render the same tab.
 *
 * ## Why the context is real for `paths` and empty for everything else
 *
 * The tab strip's hrefs come from `KuiPaths`, and the point of those is that they are built from the
 * route table rather than written — so a stub returning plausible strings would test the stub. They
 * are therefore the real `shellPaths` over the same router. Nothing else on this screen is reached:
 * the model arrives as a prop and the component fetches nothing, so the client is a placeholder, and
 * a placeholder that is *used* would throw rather than answer something misleading.
 */

import { createRouter, memoryHistory, type RouteSectionProps } from "@solidjs/router";
import type { JSX } from "@solidjs/web";

import type { KuiApiClient } from "@kui/api";
import { KuiProvider, type KuiContextValue } from "@kui/kernel";

import { shellPaths } from "../routing/paths.js";
import { shellRoutes, type RouteViews } from "../routing/routes.jsx";

const nothing = () => null;

/**
 * Mounts `view` as the dashboard at `at`.
 *
 * @param at a full browser address, `/ui`-prefixed as the router sees it
 * @param selected what `useKui().cluster()` answers — the stored selection. It defaults to *none*,
 *   because the address is the source of truth and a default here would let a case pass on the
 *   fallback while appearing to test the route
 */
export function dashboardHost(
  at: string,
  view: () => JSX.Element,
  selected?: string,
): () => JSX.Element {
  const views: RouteViews = {
    home: view,
    settings: nothing,
    forbidden: nothing,
    notFound: () => <p data-testid="not-found">no route matched</p>,
    feature: () => nothing,
  };

  const Router = createRouter({
    routes: shellRoutes(views),
    base: "/ui",
    history: memoryHistory(at),
  });

  const context: KuiContextValue = {
    // Never called: this screen takes a finished view model and asks for nothing. A stub that
    // answered would invite a future version of the screen to fetch here and look fine in a story.
    api: {} as KuiApiClient,
    cluster: () => selected,
    permits: () => true,
    paths: shellPaths(Router),
    report: () => {},
  };

  return () => (
    <KuiProvider value={context}>
      <Router>{(route: RouteSectionProps) => route.children}</Router>
    </KuiProvider>
  );
}
