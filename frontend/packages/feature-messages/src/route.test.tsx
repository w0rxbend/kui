/**
 * The route, driven as the product drives it: a router, a client, and the screen it renders.
 *
 * ## Why these cases are here and not against a component
 *
 * Every rule this packet is about lives in the *joins*, and each of them can be broken with a
 * component suite still green:
 *
 *   - The partition count is a request the route makes and threads to three children. A test that
 *     passes `partitionCount={12}` to `ResendDialog` asserts that the dialog can print a number it
 *     was handed. It cannot see the route handing it a hard-coded zero, which is what the route did.
 *   - The typed predicates have no query parameter. They reach a browse only if the route compiles
 *     them, registers them with the service and quotes the id it minted — three steps, none of them
 *     inside a component, and a suite over `predicates.ts` would only assert that a string builder
 *     builds a string.
 *   - And the address writer only works if the *router* is told. `window.history.replaceState` does
 *     not fire `popstate`, so the previous version wrote a URL that `useLocation()` never saw: the
 *     bar changed the address and the browse kept reading the range it was opened with. Nothing
 *     short of a real router in the test can catch that, which is why there is one here.
 *
 * So the seam under test is the route with a router around it and a fake transport underneath, and
 * the assertions are on the two things that leave the browser: the URL the address becomes, and the
 * requests the client is asked to make.
 */

import { describe, expect, test } from "vitest";
import { flush } from "solid-js";
import { createRouter, memoryHistory } from "@solidjs/router";
import { KuiProvider, type KuiContextValue, type KuiPaths } from "@kui/kernel";
import type { KuiApiClient } from "@kui/api";

import { mount } from "./testing.js";
import Messages from "./MessagesRoute.jsx";

const CLUSTER = "quickstart";
/** Where the product is mounted. Every address this route writes has to keep it, exactly once. */
const BASE = "/ui";
const TOPIC = "orders.payments.v2";

/** Solid batches writes to a microtask; a router navigation takes a couple of them to settle. */
async function settle(times = 6): Promise<void> {
  for (let i = 0; i < times; i += 1) await flush();
}

interface Call {
  readonly method: "get" | "post";
  readonly path: string;
  readonly body?: unknown;
}

/**
 * A client that answers from a table and records what was asked.
 *
 * Cast at one boundary rather than implemented against the generated signatures: `KuiApiClient`'s
 * methods are typed from the OpenAPI document — the path constrains the parameters, the body and
 * the answer — and a fake that satisfied all of that would be a second copy of the schema. The cast
 * is the same one every stub client in this workspace makes, and the calls it records are compared
 * against the literal paths the product uses, so a renamed endpoint still shows up as a failure.
 */
function fakeApi(options: {
  readonly topicAnswer?: unknown;
  readonly topicFails?: boolean;
  readonly filterId?: string;
  readonly filterFails?: string;
}): { readonly api: KuiApiClient; readonly calls: Call[] } {
  const calls: Call[] = [];
  const api = {
    get: async (path: string) => {
      calls.push({ method: "get", path });
      if (path === "/api/v1/clusters/{clusterId}/topics/{topicName}") {
        if (options.topicFails === true) {
          const cause = "the gateway is not answering";
          return { ok: false, error: { kind: "unreachable", cause } };
        }
        return { ok: true, value: options.topicAnswer };
      }
      return { ok: false, error: { kind: "unreachable", cause: "nothing answers that here" } };
    },
    post: async (path: string, init?: { readonly body?: unknown }) => {
      calls.push({ method: "post", path, body: init?.body });
      if (path === "/api/v1/clusters/{clusterId}/messages/filters") {
        if (options.filterFails !== undefined) {
          return {
            ok: false,
            error: {
              kind: "envelope",
              code: "KUI-UNSUPPORTED",
              message: options.filterFails,
              details: [],
              correlationId: "test",
              retryable: false,
            },
          };
        }
        return { ok: true, value: { id: options.filterId ?? "0123456789abcdef" } };
      }
      return { ok: false, error: { kind: "unreachable", cause: "nothing answers that here" } };
    },
    put: async () => ({ ok: false, error: { kind: "unreachable", cause: "no" } }),
    delete: async () => ({ ok: false, error: { kind: "unreachable", cause: "no" } }),
    patch: async () => ({ ok: false, error: { kind: "unreachable", cause: "no" } }),
    raw: undefined,
  } as unknown as KuiApiClient;
  return { api, calls };
}

/** A topic answer carrying a real partition count, in the envelope the gateway sends. */
function topicWith(partitionCount: number, name: string = TOPIC): unknown {
  return {
    partitionsTruncated: false,
    topic: {
      status: "ok",
      fetchedAt: "2026-09-05T10:00:00Z",
      data: { row: { name, internal: false, partitionCount, replicationFactor: 3 } },
    },
  };
}

/** A topic answer the gateway could not fill in: the section refuses and carries no data. */
const TOPIC_UNAVAILABLE: unknown = {
  partitionsTruncated: false,
  topic: {
    status: "unavailable",
    reason: "upstream_unavailable",
    message: "the topic service is down",
  },
};

const PATHS = new Proxy({}, { get: () => () => "/ui" }) as unknown as KuiPaths;

/**
 * Mounts the route inside a real router with an in-memory history.
 *
 * `memoryHistory` and not the browser's, because the browser's would write into the test runner's
 * own address bar and leak between cases — but it is the same adapter contract, so a navigation that
 * fails to notify the router fails here exactly as it does in a browser.
 */
function routeAt(
  search: string,
  api: KuiApiClient,
  /* Each case browses its own topic. `useQuery`'s registry is module state shared by the whole
   * process — which is the point of it, one answer per key for the whole tab — so two cases naming
   * one topic would have the second read the first's answer and assert against a count it never
   * asked for. A distinct name per case is the key being a key. */
  topic: string = TOPIC,
): { readonly container: HTMLElement; readonly dispose: () => void; readonly url: () => string } {
  /* Mounted at `/ui`, as the product is, and this is not decoration. `navigate` resolves a `to`
   * that begins with `/` against the base, and `useLocation().pathname` already carries the base —
   * so a writer that hands the pathname back produces `/ui/ui/clusters/…`. With no base a test
   * cannot see that at all, which is how the first version of this suite passed against a route
   * that broke its own address in a browser on the first keystroke. */
  const start = `${BASE}/clusters/${CLUSTER}/topics/${topic}/messages${search}`;
  const history = memoryHistory(start);
  const Router = createRouter({
    routes: [{ path: "/clusters/:clusterId/topics/:topicName/messages", component: Messages }],
    base: BASE,
    history,
    scrollRestoration: false,
  });

  const value: KuiContextValue = {
    api,
    cluster: () => CLUSTER,
    permits: () => true,
    paths: PATHS,
    report: () => undefined,
  };

  const mounted = mount(() => (
    <KuiProvider value={value}>
      <Router />
    </KuiProvider>
  ));
  return { ...mounted, url: () => history.get() };
}

/**
 * The route builds its own transport, so the fake has to be installed where it builds it.
 *
 * `createBrowseTransport` reaches the network through `fetch`, and a test that let it do so would be
 * a test that hangs. Replacing `fetch` is the honest seam: everything above it — the URL the session
 * composes, the parameters on it, the abort — is the product's own code.
 */
async function withFetch(spy: (url: string) => void, run: () => Promise<void>): Promise<void> {
  const original = globalThis.fetch;
  globalThis.fetch = ((input: RequestInfo | URL) => {
    spy(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
    // Never resolves: the browse's own transport handles a stream that never arrives, and this test
    // is about the request, not the answer.
    return new Promise<Response>(() => undefined);
  }) as typeof globalThis.fetch;
  try {
    /* Awaited *inside* the try. The first version returned the promise and restored `fetch` in the
     * `finally`, which runs at the caller's first `await` — so the replacement was gone before the
     * transport ever reached for it, and every assertion about a request read zero. */
    await run();
  } finally {
    globalThis.fetch = original;
  }
}

function press(container: HTMLElement, label: string): void {
  const button = [...container.querySelectorAll("button")].find(
    (candidate) => (candidate.textContent ?? "").trim() === label,
  );
  if (button === undefined) throw new Error(`no button reading ${label}`);
  button.click();
}

function dialogText(): string {
  const all = document.body.querySelectorAll<HTMLElement>("[role='dialog']");
  return all[all.length - 1]?.textContent ?? "";
}

describe("the partition count the route fetches", () => {
  test("the copy dialog quotes the topic's real partition count", async () => {
    const { api } = fakeApi({ topicAnswer: topicWith(12) });
    const { container, dispose } = routeAt("", api);
    await settle();

    press(container, "Copy records out");
    await settle();

    /* The figure, in the dialog, in words the operator reads. Twelve is the topic's, and it can only
     * have arrived by the route asking for the topic — nothing else in this test knows it. */
    expect(dialogText()).toContain(`${TOPIC} has 12 partitions.`);
    dispose();
  });

  test("a topic whose count is not known renders no sentence claiming one", async () => {
    const { api } = fakeApi({ topicAnswer: TOPIC_UNAVAILABLE });
    const { container, dispose } = routeAt("", api, "orders.undescribed");
    await settle();

    press(container, "Copy records out");
    await settle();

    const text = dialogText();
    expect(text).toContain("KUI has not been told how many partitions");
    /* The rule, stated as the thing that must not be on screen: no count at all, and above all not
     * the zero this route supplied for the whole life of the dialog. */
    expect(text).not.toMatch(/has \d+ partitions/);
    dispose();
  });

  test("the partition picker says so in words rather than offering `all 0`", async () => {
    const { api } = fakeApi({ topicFails: true });
    const { container, dispose } = routeAt("", api, "orders.unreachable");
    await settle();

    const trigger = container.querySelector<HTMLButtonElement>(".kui-partition-picker__trigger");
    expect(trigger?.textContent).toContain("all partitions");
    expect(trigger?.textContent).not.toContain("all 0");
    expect(trigger?.disabled).toBe(true);
    dispose();
  });
});

describe("the typed predicates", () => {
  test("a key predicate and a value predicate reach the request separately", async () => {
    const { api, calls } = fakeApi({
      topicAnswer: topicWith(12, "orders.predicates"),
      filterId: "abc0123456789def",
    });
    const opened: string[] = [];

    const search = "?key=ord_&keyMode=starts&value=UAH&valueMode=contains";
    await withFetch(
      (url) => opened.push(url),
      async () => {
        const { container, dispose } = routeAt(search, api, "orders.predicates");
        await settle();

        press(container, "Read");
        await settle();
        dispose();
      },
    );

    /* One registration, carrying both predicates as two terms over two variables. This is the
     * assertion the packet's brief names: the key's predicate is about the key and the value's is
     * about the value, and neither has been folded into the other or into the plain `q` substring,
     * which matches the whole record and could not tell them apart. */
    const registration = calls.find(
      (call) => call.path === "/api/v1/clusters/{clusterId}/messages/filters",
    );
    expect(registration).toBeDefined();
    const source = (registration?.body as { readonly source?: string } | undefined)?.source ?? "";
    expect(source).toContain('record.keyAsText.startsWith("ord_")');
    expect(source).toContain('record.valueAsText.contains("UAH")');
    expect(source).toBe('record.keyAsText.startsWith("ord_") && record.valueAsText.contains("UAH")');

    /* And it reached the browse. The id the service minted travels with the source it was minted
     * from — a browse carrying only the id is refused by a replica that never saw the registration,
     * and one carrying only the source is silently ignored by every replica. */
    expect(opened).toHaveLength(1);
    const url = new URL(opened[0] ?? "", "http://localhost");
    expect(url.searchParams.get("filterId")).toBe("abc0123456789def");
    expect(url.searchParams.get("filterSource")).toContain("record.keyAsText");
    expect(url.searchParams.get("filterSource")).toContain("record.valueAsText");
  });

  test("typing them writes an address a colleague can be sent", async () => {
    const { api } = fakeApi({ topicAnswer: topicWith(12, "orders.typed") });
    const { container, dispose, url } = routeAt("", api, "orders.typed");
    await settle();

    const boxes = [
      ...container.querySelectorAll<HTMLInputElement>(".kui-browse-bar__predicate input"),
    ];
    expect(boxes).toHaveLength(2);

    /* The address is the product's memory of what the controls hold, and it is written through the
     * router. The version that wrote it with `window.history.replaceState` passed a component test
     * and did nothing at all here: the URL changed and `useLocation()` never heard about it, so the
     * browse below went on reading whatever the page was opened with. */
    boxes[0]?.dispatchEvent(new Event("input", { bubbles: true }));
    if (boxes[0] !== undefined) boxes[0].value = "ord_";
    boxes[0]?.dispatchEvent(new Event("input", { bubbles: true }));
    await settle();
    if (boxes[1] !== undefined) boxes[1].value = "UAH";
    boxes[1]?.dispatchEvent(new Event("input", { bubbles: true }));
    await settle();

    const written = new URL(url(), "http://localhost");
    expect(written.searchParams.get("key")).toBe("ord_");
    expect(written.searchParams.get("value")).toBe("UAH");
    // The path is the one it started on. A writer that hands `location.pathname` back to `navigate`
    // doubles the mount point, and the address stops naming a route the next time it is opened.
    expect(written.pathname).toBe(`${BASE}/clusters/${CLUSTER}/topics/orders.typed/messages`);
    dispose();
  });

  test("a time-window chip sets the start and drops the window's end in one address", async () => {
    // Two halves of one change. They are written together because `query()` and `predicates()` read
    // the *location*, which the router updates on its own schedule — so a second write composed
    // from the location would be composed from the state before the first, and would land an
    // address holding a start with the stale upper bound still on it.
    const { api } = fakeApi({ topicAnswer: topicWith(12, "orders.window") });
    const { container, dispose, url } = routeAt(
      "?seekTo=timestamp%3A%3A1000&untilTime=2000",
      api,
      "orders.window",
    );
    await settle();

    const chip = [...container.querySelectorAll<HTMLButtonElement>(".kui-fchip")].find(
      (candidate) => (candidate.textContent ?? "").trim() === "15m",
    );
    expect(chip).toBeDefined();
    chip?.click();
    await settle();

    const written = new URL(url(), "http://localhost");
    expect(written.searchParams.get("untilTime")).toBeNull();
    const seek = written.searchParams.get("seekTo") ?? "";
    expect(seek.startsWith("timestamp::")).toBe(true);
    expect(seek).not.toBe("timestamp::1000");
    dispose();
  });

  test("an upper bound on the offset is a term about the offset, not a second start", async () => {
    const { api, calls } = fakeApi({ topicAnswer: topicWith(12, "orders.bounded") });
    await withFetch(
      () => undefined,
      async () => {
        const { container, dispose } = routeAt(
          "?seekTo=offset%3A%3A100&untilOffset=200",
          api,
          "orders.bounded",
        );
        await settle();
        press(container, "Read");
        await settle();
        dispose();
      },
    );

    const registration = calls.find(
      (call) => call.path === "/api/v1/clusters/{clusterId}/messages/filters",
    );
    const source = (registration?.body as { readonly source?: string } | undefined)?.source ?? "";
    /* The endpoint has a start and no stop, so the end of a range can only be a predicate. Sending
     * it as a second `seekTo` would be refused; sending it as nothing at all — which is what the bar
     * did before there was a box for it — is a range whose upper half is decoration. */
    expect(source).toBe("record.offset <= 200");
  });

  test("a cluster that will not compile the filter stops the browse and says why", async () => {
    const { api } = fakeApi({
      topicAnswer: topicWith(12, "orders.nofilter"),
      filterFails: "cluster 'quickstart' has no filter engine, so a smart filter cannot be run",
    });
    const opened: string[] = [];

    await withFetch(
      (url) => opened.push(url),
      async () => {
        const { container, dispose } = routeAt("?key=ord_", api, "orders.nofilter");
        await settle();
        press(container, "Read");
        await settle();

        /* Not a browse that quietly returns the whole topic. `filterSource` without a `filterId` is
         * dropped by the message service, so starting anyway would show every record on the topic
         * under a bar that says a filter is applied. */
        expect(opened).toHaveLength(0);
        expect(container.textContent).toContain("no filter engine");
        dispose();
      },
    );
  });

  test("a browse with nothing to compile registers nothing", async () => {
    const { api, calls } = fakeApi({ topicAnswer: topicWith(12, "orders.plain") });
    const opened: string[] = [];
    await withFetch(
      (url) => opened.push(url),
      async () => {
        const { container, dispose } = routeAt("", api, "orders.plain");
        await settle();
        press(container, "Read");
        await settle();
        dispose();
      },
    );

    // The common browse costs one request, as it did before any of this existed.
    expect(calls.some((call) => call.path.endsWith("/messages/filters"))).toBe(false);
    expect(opened).toHaveLength(1);
  });
});
