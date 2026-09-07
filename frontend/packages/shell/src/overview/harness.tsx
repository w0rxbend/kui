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
 * ## Why the context is real for `paths`, and what the client now answers
 *
 * The tab strip's hrefs come from `KuiPaths`, and the point of those is that they are built from the
 * route table rather than written — so a stub returning plausible strings would test the stub. They
 * are therefore the real `shellPaths` over the same router.
 *
 * The client used to be an empty object with a comment saying it was never called, because the
 * screen took a finished model and fetched nothing. It fetches five things now — the throughput
 * series, the latency series, the request-handler readings, the top producers and the mean record
 * size — and the first two depend on `?range=` and therefore on the address this harness exists to
 * set. So the stub answers **by path** and refuses anything it was not given: an unstubbed call is
 * an `unreachable` failure naming itself, which shows up in an assertion rather than as an empty
 * card.
 *
 * The default answers all five `not_configured`, which is the honest answer for a fixture cluster
 * with no exporter and is what every case that is not about metrics should see. It is deliberately
 * not "refuse everything": a case about the storage card would otherwise draw four red failure
 * panels beside it and its own axe sweep would be asserting somebody else's broken card.
 */

import { createRouter, memoryHistory, useLocation, type RouteSectionProps } from "@solidjs/router";
import type { JSX } from "@solidjs/web";

import type { ApiError, KuiApiClient } from "@kui/api";
import { KuiProvider, type KuiContextValue } from "@kui/kernel";

import { shellPaths } from "../routing/paths.js";
import { shellRoutes, type RouteViews } from "../routing/routes.jsx";
import {
  HANDLERS_NOT_CONFIGURED,
  LATENCY_NOT_CONFIGURED,
  PRODUCERS_NOT_CONFIGURED,
  RECORD_SIZE_NOT_CONFIGURED,
  THROUGHPUT_NOT_CONFIGURED,
} from "./fixtures.js";

const nothing = () => null;

/** The five metrics endpoints, as the client names them. One string each, so a stub cannot misspell one. */
export const THROUGHPUT_PATH = "/api/v1/clusters/{clusterId}/metrics/throughput";
export const LATENCY_PATH = "/api/v1/clusters/{clusterId}/metrics/latency";
export const HANDLERS_PATH = "/api/v1/clusters/{clusterId}/metrics/request-handlers";
export const PRODUCERS_PATH = "/api/v1/clusters/{clusterId}/metrics/producers";
export const RECORD_SIZE_PATH = "/api/v1/clusters/{clusterId}/metrics/record-size";

export interface StubApi {
  readonly api: KuiApiClient;
  /** Every request that went out, as `path?query`. What a "the range reached the request" case reads. */
  readonly calls: readonly string[];
}

/**
 * A gateway that answers what it was given and refuses the rest.
 *
 * The query is recorded with the path because the one thing worth asserting about this screen's
 * request is the `range` in it: a selector wired to the address and not to the request would move
 * the label and leave the window alone, and nothing that only looked at the answer could see it.
 */
export function stubApi(answers: Readonly<Record<string, unknown>>): StubApi {
  const calls: string[] = [];
  const answer = async (path: string, init?: { params?: { query?: Record<string, unknown> } }) => {
    const query = init?.params?.query;
    const suffix =
      query === undefined
        ? ""
        : `?${Object.entries(query)
            .map(([key, value]) => `${key}=${String(value)}`)
            .join("&")}`;
    calls.push(`${path}${suffix}`);
    if (Object.hasOwn(answers, path)) return { ok: true, value: answers[path] };
    const error: ApiError = { kind: "unreachable", cause: `this test stubbed no answer for ${path}` };
    return { ok: false, error };
  };
  const api = {
    get: answer,
    post: answer,
    put: answer,
    delete: answer,
    patch: answer,
    raw: {},
  } as unknown as KuiApiClient;
  return { api, calls };
}

/**
 * A deployment that has configured no exporter, which is the ordinary case rather than the failure
 * case. Exported so a story or a case can extend it with the one endpoint it is about.
 */
export const UNCONFIGURED_METRICS: Readonly<Record<string, unknown>> = {
  [THROUGHPUT_PATH]: THROUGHPUT_NOT_CONFIGURED,
  [LATENCY_PATH]: LATENCY_NOT_CONFIGURED,
  [HANDLERS_PATH]: HANDLERS_NOT_CONFIGURED,
  [PRODUCERS_PATH]: PRODUCERS_NOT_CONFIGURED,
  [RECORD_SIZE_PATH]: RECORD_SIZE_NOT_CONFIGURED,
};

export interface DashboardHostOptions {
  /**
   * What `useKui().cluster()` answers — the stored selection. Absent by default, because the
   * address is the source of truth and a default here would let a case pass on the fallback while
   * appearing to test the route.
   */
  readonly selected?: string | undefined;
  /** The gateway. Defaults to one that answers the throughput endpoint `not_configured`. */
  readonly api?: KuiApiClient | undefined;
}

/**
 * Mounts `view` as the dashboard at `at`.
 *
 * @param at a full browser address, `/ui`-prefixed as the router sees it
 * @param view what the dashboard route renders
 * @param options the stored cluster selection and the gateway; see {@link DashboardHostOptions}
 */
/**
 * Prints the address, from inside the router, so a case can read what a control wrote to it.
 *
 * `memoryHistory` does not touch `window.location` — deliberately, so that two mounted dashboards
 * in one jsdom document do not fight over one URL — which leaves a case with no way to see the
 * address except from a component under the same router. This is that component. It is mounted
 * beside the screen rather than inside it, so nothing about the screen has to change to be
 * observable.
 */
export function AddressProbe(): JSX.Element {
  const location = useLocation();
  return (
    <p data-testid="address" class="kui-visually-hidden">
      {`${location.pathname}${location.search}`}
    </p>
  );
}

export function dashboardHost(
  at: string,
  view: () => JSX.Element,
  options: DashboardHostOptions = {},
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
    api: options.api ?? stubApi(UNCONFIGURED_METRICS).api,
    cluster: () => options.selected,
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
