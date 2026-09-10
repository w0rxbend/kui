import { describe, expect, it, vi } from "vitest";
import { createBrowseTransport } from "./transport.js";
import type { BrowseEvent, BrowseFailure } from "./session.js";

/**
 * The adapter between the browse session and the network.
 *
 * Two properties are asserted here and neither was provable before this adapter existed, because
 * every implementation of `BrowseTransport` was a fake:
 *
 * 1. **Stopping a browse aborts the request.** The whole reason the kernel has a `fetch`-based
 *    transport beside the `EventSource` one. `EventSource.close()` stops the browser listening and
 *    leaves the request open, which leaves a Kafka consumer assigned on the message service until
 *    its budget expires. A test that only checked "close() was called" would pass against the
 *    leaking implementation, so this one asserts on the `AbortSignal` the request was made with.
 *
 * 2. **`phase` frames reach the session.** `phase` is a shared event, so a caller may not list it
 *    among its own — and both transports used to drop it silently, leaving the status line blank
 *    with nothing to say why.
 */

/** A `fetch` that never answers, so a test can stop the browse mid-flight. */
function hangingFetch(): { readonly fetch: typeof globalThis.fetch; readonly signal: () => AbortSignal | undefined } {
  let captured: AbortSignal | undefined;
  const fetchImpl = vi.fn((_input: unknown, init?: { signal?: AbortSignal }) => {
    captured = init?.signal;
    return new Promise<Response>(() => {
      /* never settles: the request is still open when the test aborts it */
    });
  });
  return { fetch: fetchImpl as unknown as typeof globalThis.fetch, signal: () => captured };
}

describe("createBrowseTransport", () => {
  it("aborts the request when the browse is stopped", async () => {
    const { fetch, signal } = hangingFetch();
    vi.stubGlobal("fetch", fetch);

    const handle = createBrowseTransport().open("/api/v1/stream", {
      onEvent: () => undefined,
      onFailure: () => undefined,
      onConnection: () => undefined,
    });

    await Promise.resolve();
    expect(signal()?.aborted).toBe(false);

    handle.close();

    // The property that matters: the signal the request was made with is now aborted, so the body
    // read is torn down, so the gateway's stream is cancelled, so the Kafka consumer is released.
    expect(signal()?.aborted).toBe(true);
    vi.unstubAllGlobals();
  });

  it("does not report the client's own abort as a failure", async () => {
    const { fetch } = hangingFetch();
    vi.stubGlobal("fetch", fetch);

    const failures: BrowseFailure[] = [];
    const handle = createBrowseTransport().open("/api/v1/stream", {
      onEvent: () => undefined,
      onFailure: (failure) => failures.push(failure),
      onConnection: () => undefined,
    });

    handle.close();
    await Promise.resolve();
    await Promise.resolve();

    // Pressing Stop is not an error. Reporting it as one puts a red panel on screen every time
    // somebody stops a browse, which teaches them to ignore the panel.
    expect(failures).toEqual([]);
    vi.unstubAllGlobals();
  });

  it("delivers a phase frame to the session", async () => {
    // `phase` is shared, so it is never in `events` and never goes through `decode`'s data path.
    // Before the kernel grew `onPhase`, this frame was parsed and then dropped on the floor.
    const body = ["event: phase", 'data: {"phase":"seeking"}', "", ""].join("\n");
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } })),
    );

    const events: BrowseEvent[] = [];
    createBrowseTransport().open("/api/v1/stream", {
      onEvent: (event) => events.push(event),
      onFailure: () => undefined,
      onConnection: () => undefined,
    });

    // Two microtask turns: one for the response, one for the first chunk.
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(events).toContainEqual({ kind: "phase", name: "seeking" });
    vi.unstubAllGlobals();
  });

  it("asks for the browse as a GET with the whole query in the address", async () => {
    /*
     * The property `transport.ts` argues for in its own comment and nothing asserted: "the whole
     * query is in the URL, which is what makes a browse a link somebody can paste to a colleague".
     * Changing `method: "GET"` to `"POST"` left all 163 cases in this package green — and what that
     * ships is a browse nobody can share, plus a request shape the gateway's streaming route does
     * not answer at all, so every browse fails at once with a method error rather than with
     * anything a reader could act on.
     *
     * Read off the `fetch` the transport actually made, which is the only place the verb exists.
     */
    let seen: { method?: string; body?: unknown } | undefined;
    const fetchImpl = vi.fn((_input: unknown, init?: { method?: string; body?: unknown }) => {
      seen = { ...(init ?? {}) };
      return new Promise<Response>(() => {
        /* never settles: this case is about the request, not the answer */
      });
    });
    vi.stubGlobal("fetch", fetchImpl as unknown as typeof globalThis.fetch);

    const handle = createBrowseTransport().open(
      "/api/v1/clusters/quickstart/topics/orders.v1/messages?partition=0",
      {
        onEvent: () => undefined,
        onFailure: () => undefined,
        onConnection: () => undefined,
      },
    );
    await Promise.resolve();

    expect(seen?.method).toBe("GET");
    // And nothing in a body, which is the other half of "the query is in the address".
    expect(seen?.body ?? null).toBeNull();
    // The address the caller gave, unchanged: a transport that rewrote it would break the link too.
    expect(fetchImpl.mock.calls[0]?.[0]).toBe(
      "/api/v1/clusters/quickstart/topics/orders.v1/messages?partition=0",
    );

    handle.close();
    vi.unstubAllGlobals();
  });

  it("reports a frame it could not read as a decode failure, naming the frame", async () => {
    /*
     * `toFailure`'s own docstring: "a `decode` failure is deliberately *not* terminal: one record
     * whose payload this build cannot read must not end a browse that is otherwise delivering good
     * records." The mapping that keeps that promise is one `case` in a `switch`, and folding it
     * onto the `transport` branch left all 163 cases in this package green.
     *
     * The difference is not cosmetic. A `transport` failure is the session's "this browse is over"
     * shape; a `decode` failure is "skip this one and carry on", and it carries the **event name**
     * as its own field because the status line says which kind of frame it could not read. Collapse
     * the two and one unreadable record ends a browse that was otherwise working — which is the
     * failure mode this whole streaming path was built to avoid.
     *
     * `data: {` is not JSON, so `decodeBrowseEvent` refuses it and the kernel reports a decode
     * error on the `message` event, which is exactly the frame this mapping is about.
     */
    const body = ["event: message", "data: {", "", ""].join("\n");
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(body, {
            status: 200,
            headers: { "content-type": "text/event-stream" },
          }),
      ),
    );

    const failures: BrowseFailure[] = [];
    createBrowseTransport().open("/api/v1/stream", {
      onEvent: () => undefined,
      onFailure: (failure) => failures.push(failure),
      onConnection: () => undefined,
    });

    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(failures).toHaveLength(1);
    const only = failures[0];
    expect(only?.kind).toBe("decode");
    // The name, separable from the prose, which is what a reader chasing it needs.
    expect(only?.kind === "decode" && only.event).toBe("message");
    vi.unstubAllGlobals();
  });
});
