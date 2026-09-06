/**
 * The topics feature mounted at an address, over a stubbed gateway.
 *
 * ## Why the tests go through this and not through the components
 *
 * Every interesting rule on these screens lives in the *wiring*, not in a component's props: which
 * document the statistics tiles are given, whether the Overview tab has a body at all, whether the
 * table and the cards are handed the same selection. A case that composes `TopicListPage` by hand
 * and passes it a `statistics` element has asserted the arrangement the test itself made — it
 * cannot fail when `TopicsRoute` starts computing those totals from the rows on screen, which is
 * precisely the defect worth a test.
 *
 * So this builds the real route over a real router and a real `KuiProvider`, and the only thing
 * that is not real is the gateway. `useParams`, `useLocation`, `useQuery`, the tab strip's hrefs
 * and every mapping in `data.ts` are the product's own.
 *
 * `memoryHistory` rather than jsdom's single URL: `?tab=consumers` and
 * `/topics/orders.v1` are the inputs to half of these cases, and a test that could not set them
 * would be a test of the default tab.
 *
 * ## The stub answers by path, and refuses what it was not given
 *
 * A stub that answered `{}` to an unknown path would let a screen appear to work while asking for
 * something nobody wrote down. An unstubbed path is an `unreachable` failure naming itself, which
 * shows up in the assertion rather than as an empty table.
 */
import { createRouter, memoryHistory, type RouteSectionProps } from "@solidjs/router";
import { flush } from "solid-js";
import type { JSX } from "@solidjs/web";
import type { ApiError, KuiApiClient } from "@kui/api";
import { KuiProvider, sharedQueries, type KuiContextValue, type KuiPaths } from "@kui/kernel";
import Topics from "./TopicsRoute.jsx";

/** What a stub gateway was told to say, keyed by the templated path the client is called with. */
export type StubbedAnswers = Readonly<Record<string, unknown>>;

export interface StubApi {
  readonly api: KuiApiClient;
  /** Every path asked for, in order. What a "one request per open" assertion counts. */
  readonly calls: readonly string[];
}

export function stubApi(answers: StubbedAnswers): StubApi {
  const calls: string[] = [];
  const answer = async (path: string): Promise<unknown> => {
    calls.push(path);
    if (Object.hasOwn(answers, path)) return { ok: true, value: answers[path] };
    const error: ApiError = {
      kind: "unreachable",
      cause: `this test stubbed no answer for ${path}`,
    };
    return { ok: false, error };
  };
  const client = {
    get: answer,
    post: answer,
    put: answer,
    delete: answer,
    patch: answer,
    raw: {},
  } as unknown as KuiApiClient;
  return { api: client, calls };
}

/**
 * The addresses this feature reaches.
 *
 * Written out rather than taken from the shell, because a feature may not import the shell — that
 * edge is the one the microfrontend split exists to prevent. They are the same shapes
 * `shellPaths` produces, and a test that cared about the exact spelling would be testing the shell.
 */
const paths: KuiPaths = {
  home: () => "/ui",
  settings: () => "/ui/settings",
  clusters: () => "/ui/clusters",
  manageClusters: () => "/ui/clusters/manage",
  dashboard: (cluster, tab = "overview") => `/ui/clusters/${cluster}/dashboard/${tab}`,
  brokers: (cluster) => `/ui/clusters/${cluster}/brokers`,
  broker: (cluster, brokerId) => `/ui/clusters/${cluster}/brokers/${brokerId}`,
  topics: (cluster) => `/ui/clusters/${cluster}/topics`,
  topic: (cluster, name) => `/ui/clusters/${cluster}/topics/${encodeURIComponent(name)}`,
  topicMessages: (cluster, name) =>
    `/ui/clusters/${cluster}/topics/${encodeURIComponent(name)}/messages`,
  trackMessages: (cluster) => `/ui/clusters/${cluster}/messages/track`,
  consumerGroups: (cluster) => `/ui/clusters/${cluster}/consumer-groups`,
  consumerGroup: (cluster, groupId) =>
    `/ui/clusters/${cluster}/consumer-groups/${encodeURIComponent(groupId)}`,
};

export interface HostOptions {
  /** The address, as the router sees it: `/clusters/quickstart/topics?tab=consumers`. */
  readonly at: string;
  readonly answers: StubbedAnswers;
  /** What `useKui().permits` answers. Defaults to "everything", so a case about a control's
   *  presence is not silently a case about permissions. */
  readonly permits?: boolean | undefined;
}

/**
 * A mountable topics feature, plus the stub it is talking to.
 *
 * `sharedQueries` is module state by design — two features asking for one cluster should ask once —
 * so two cases that use the same cluster id share an answer, and the second would assert against
 * the first one's stub. {@link forgetQueries} in an `afterEach` is what keeps cases independent;
 * giving each case its own cluster id keeps them independent even when somebody forgets.
 */
export function topicsHost(options: HostOptions): {
  readonly view: () => JSX.Element;
  readonly stub: StubApi;
} {
  const stub = stubApi(options.answers);

  const Router = createRouter({
    routes: [
      { path: "/clusters/:clusterId/topics", component: () => <Topics /> },
      { path: "/clusters/:clusterId/topics/:topicName", component: () => <Topics /> },
      { path: "*", component: () => <p data-testid="no-route">no route matched</p> },
    ],
    history: memoryHistory(options.at),
  });

  const context: KuiContextValue = {
    api: stub.api,
    cluster: () => undefined,
    permits: () => options.permits ?? true,
    paths,
    report: () => {},
  };

  return {
    stub,
    view: () => (
      <KuiProvider value={context}>
        <Router>{(route: RouteSectionProps) => route.children}</Router>
      </KuiProvider>
    ),
  };
}

/**
 * Empties the shared query cache between cases.
 *
 * The registry is a browser tab's view of one server, so it outlives a component and, in a test
 * file, a case. Without this a second case asking for the same cluster reads the first case's
 * answer and passes for the wrong reason, which is the failure mode a shared cache always has.
 */
export function forgetQueries(): void {
  sharedQueries.invalidateWhere(() => true);
}

/**
 * Lets every pending request land, then lets the DOM catch up.
 *
 * `flush()` alone drains Solid's own queue; it does not resolve a promise. These screens fetch on
 * mount, map the answer, and then draw — three microtask hops before anything is on screen — and a
 * case that flushed once would assert against a skeleton and read as a component drawing nothing.
 * The loop is bounded rather than a fixed count of hops so a chain one link longer (a tab whose
 * query opens after the overview lands) does not need every case edited.
 */
export async function settle(rounds = 8): Promise<void> {
  for (let round = 0; round < rounds; round += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();
  }
}
