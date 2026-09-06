/**
 * The schemas feature mounted at an address, over a stubbed gateway.
 *
 * Its own file rather than part of `testing.ts` — the same split `feature-topics` makes, and for
 * the same reason: everything here is JSX and everything there is not, and a `.tsx` that four other
 * packages have a `.ts` copy of would make the seventh copy of that helper look like a different
 * thing.
 */
import { flush } from "solid-js";
import type { JSX } from "@solidjs/web/jsx-runtime";
import { createRouter, memoryHistory, type RouteSectionProps } from "@solidjs/router";
import type { ApiError, KuiApiClient } from "@kui/api";
import { KuiProvider, sharedQueries } from "@kui/kernel";
import Schemas from "./SchemasRoute.jsx";
import { testContext } from "./testing.js";


/**
 * What the stub was told to answer, keyed by method and templated path.
 *
 * `"GET /api/v1/clusters/{clusterId}/schemas/subjects"` — the method is in the key because two of
 * this screen's calls share a path and differ only by verb: reading the compatibility level and
 * setting it. A stub keyed by path alone would answer the read's document to the write.
 */
export type StubbedAnswers = Readonly<Record<string, unknown>>;

/** A failure the stub should produce instead of an answer, for the refusal cases. */
export interface StubRefusal {
  readonly refuses: ApiError;
}

export function refuses(error: ApiError): StubRefusal {
  return { refuses: error };
}

/**
 * Different answers to the same call, in order, the last one repeating.
 *
 * For the states that only exist *after* a second attempt — `useQuery` marks a query stale when a
 * refusal arrives over a value it already holds, so a case about staleness has to be able to say
 * "answer, then stop answering".
 */
export interface StubSequence {
  readonly inOrder: readonly unknown[];
}

export function inOrder(...answers: readonly unknown[]): StubSequence {
  return { inOrder: answers };
}

/** One call the screen made, with what it asked for. */
export interface StubCall {
  readonly method: string;
  readonly path: string;
  readonly init: Record<string, unknown> | undefined;
}

export interface StubApi {
  readonly api: KuiApiClient;
  /**
   * Every call, in order, **with its options**.
   *
   * The options are the half that matters here and the half the topics harness does not keep: a
   * sort control that is wired to nothing still produces a request to the right path, and only the
   * `direction` inside `params.query` says whether the listbox reached the registry.
   */
  readonly calls: readonly StubCall[];
}

/**
 * A gateway that answers by method and templated path, refuses what it was not given, and records
 * what it was asked.
 *
 * An unstubbed path is an `unreachable` failure naming itself rather than an empty `{}`, so a screen
 * asking for something nobody wrote down shows up in the assertion instead of as a blank panel.
 */
export function stubApi(answers: StubbedAnswers): StubApi {
  const calls: StubCall[] = [];
  /** How many times each key has been answered, so a sequence can advance. */
  const served = new Map<string, number>();
  async function reply(
    method: string,
    path: string,
    init?: Record<string, unknown>,
  ): Promise<unknown> {
    calls.push({ method, path, init });
    const key = `${method} ${path}`;
    if (Object.hasOwn(answers, key)) {
      let stubbed = answers[key];
      if (typeof stubbed === "object" && stubbed !== null && "inOrder" in stubbed) {
        const sequence = (stubbed as StubSequence).inOrder;
        const taken = served.get(key) ?? 0;
        served.set(key, taken + 1);
        stubbed = sequence[Math.min(taken, sequence.length - 1)];
      }
      if (typeof stubbed === "object" && stubbed !== null && "refuses" in stubbed) {
        return { ok: false, error: (stubbed as StubRefusal).refuses };
      }
      return { ok: true, value: stubbed };
    }
    const error: ApiError = {
      kind: "unreachable",
      cause: `this test stubbed no answer for ${key}`,
    };
    return { ok: false, error };
  }

  function answer(method: string) {
    return async (path: string, init?: Record<string, unknown>): Promise<unknown> =>
      reply(method, path, init);
  }
  const client = {
    get: answer("GET"),
    post: answer("POST"),
    put: answer("PUT"),
    delete: answer("DELETE"),
    patch: answer("PATCH"),
    raw: {},
  } as unknown as KuiApiClient;
  return { api: client, calls };
}

export interface HostOptions {
  /** The address, as the router sees it: `/clusters/quickstart/schemas/orders.avro-value`. */
  readonly at: string;
  readonly answers: StubbedAnswers;
  /** What `useKui().permits` answers. Defaults to "everything". */
  readonly permits?: boolean | undefined;
}

/**
 * The real route, over a real router and a real `KuiProvider`, with only the gateway stubbed.
 *
 * ## Why the cases go through this and not through the components
 *
 * Every rule worth gating on this screen lives in the *wiring* and not in a component's props: that
 * the sort control's direction reaches the request, that the header's subject count is the
 * registry's total rather than the page's row count, that a registration refreshes the list and
 * raises a toast. A case that composes `SubjectList` by hand and passes it `direction="desc"` has
 * asserted the arrangement the case itself made — it goes on passing after `SchemasRoute` starts
 * ignoring the control, which is precisely the defect worth a test, and is precisely the defect
 * that shipped here.
 *
 * `useParams`, `useLocation`, `useQuery`, `createMutation` and every mapping in `data.ts` are the
 * product's own. `memoryHistory` rather than jsdom's single URL, because the selected subject is in
 * the address and a case that could not set it would be a case about the empty pane.
 */
export function schemasHost(options: HostOptions): {
  readonly view: () => JSX.Element;
  readonly stub: StubApi;
} {
  const stub = stubApi(options.answers);

  const Router = createRouter({
    routes: [
      { path: "/clusters/:clusterId/schemas", component: () => Schemas() },
      { path: "/clusters/:clusterId/schemas/:subject", component: () => Schemas() },
      { path: "*", component: () => null },
    ],
    history: memoryHistory(options.at),
  });

  const context = testContext(stub.api, options.permits ?? true);

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
 * `sharedQueries` is module state by design — two panes reading one registry should ask once — so it
 * outlives a component and, in a test file, a case. Without this a second case asking for the same
 * cluster reads the first case's answer and passes for the wrong reason.
 */
export function forgetQueries(): void {
  sharedQueries.invalidateWhere(() => true);
}

/**
 * Lets every pending request land, then lets the DOM catch up.
 *
 * `flush()` alone drains Solid's queue; it does not resolve a promise. This screen fetches on mount,
 * maps the answer and then draws — several microtask hops before anything is on screen — so a case
 * that flushed once would assert against an empty list and read as a component drawing nothing.
 */
export async function settle(rounds = 8): Promise<void> {
  for (let round = 0; round < rounds; round += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await flush();
  }
}
