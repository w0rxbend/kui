import { describe, expect, it } from "vitest";
import type { ApiResult } from "@kui/api";
import { createRoot, createSignal, flush } from "solid-js";

import type { SseConnection, SseHandle, SseSubscriber } from "../sse/stream.js";
import { createCapabilities, type CapabilityNotice } from "./store.js";
import type { CapabilityFrame } from "./frames.js";

/** A stream the test drives: it hands over frames and moves the connection by hand. */
function fakeStream() {
  const [connection, setConnection] = createSignal<SseConnection>(
    { phase: "connecting" },
    { ownedWrite: true },
  );
  let subscriber: SseSubscriber<CapabilityFrame> | undefined;
  let closes = 0;

  const handle: SseHandle = {
    connection,
    close: () => {
      closes += 1;
      setConnection({ phase: "closed", reason: "closed by the client" });
    },
    endMarker: () => undefined,
  };

  return {
    handle,
    get closes(): number {
      return closes;
    },
    open(next: SseSubscriber<CapabilityFrame>): SseHandle {
      subscriber = next;
      setConnection({ phase: "connecting" });
      return handle;
    },
    connect(): void {
      setConnection({ phase: "open" });
      flush();
    },
    drop(reason = "the connection was lost"): void {
      setConnection({ phase: "closed", reason });
      flush();
    },
    /** Feeds a frame the way the wire does: as the text of one `data:` line. */
    send(frame: unknown): void {
      const decoded = subscriber?.decode("capabilities", JSON.stringify(frame));
      if (decoded === undefined) throw new Error("nothing is subscribed");
      if (decoded.ok) subscriber?.onEvent(decoded.value);
      else subscriber?.onError({ kind: "decode", event: "capabilities", cause: decoded.cause });
      flush();
    },
    sendRaw(text: string): void {
      const decoded = subscriber?.decode("capabilities", text);
      if (decoded === undefined) throw new Error("nothing is subscribed");
      if (decoded.ok) subscriber?.onEvent(decoded.value);
      else subscriber?.onError({ kind: "decode", event: "capabilities", cause: decoded.cause });
      flush();
    },
  };
}

const available = { status: "available" };
const unavailable = {
  status: "unavailable",
  reason: "UPSTREAM_UNAVAILABLE",
  message: "the cluster is not answering",
  since: "2026-09-05T09:00:00Z",
};

function snapshot(entries: readonly unknown[]) {
  return { generatedAt: "2026-09-05T09:00:00Z", entries };
}

function entry(service: string, state: unknown, extra: Record<string, unknown> = {}) {
  return { key: { service }, state, updatedAt: "2026-09-05T09:00:00Z", ...extra };
}

/** Builds a store with a fake stream, a fake clock and a scheduler the test steps by hand. */
function harness(
  options: {
    poll?: () => Promise<ApiResult<unknown>>;
  } = {},
) {
  const stream = fakeStream();
  const notices: CapabilityNotice[] = [];
  const warnings: string[] = [];
  const timers: Array<{ at: number; action: () => void }> = [];
  let clock = 0;
  let polls = 0;

  const capabilities = createCapabilities({
    openStream: (subscriber) => stream.open(subscriber),
    poll: () => {
      polls += 1;
      return (
        options.poll?.() ??
        Promise.resolve<ApiResult<unknown>>({
          ok: false,
          error: { kind: "unreachable", cause: "the gateway is not answering" },
        })
      );
    },
    notify: (notice) => notices.push(notice),
    schedule: (delayMs, action) => {
      timers.push({ at: clock + delayMs, action });
    },
    now: () => clock,
    warn: (message) => warnings.push(message),
  });

  return {
    stream,
    notices,
    warnings,
    capabilities,
    get polls(): number {
      return polls;
    },
    advance(ms: number): void {
      clock += ms;
      const due = timers.filter((timer) => timer.at <= clock);
      for (const timer of due) timers.splice(timers.indexOf(timer), 1);
      for (const timer of due) timer.action();
      flush();
    },
    async settle(): Promise<void> {
      await Promise.resolve();
      await Promise.resolve();
      flush();
    },
  };
}

describe("the capability store", () => {
  it("applies a snapshot and then a delta, and reports five states through the fold", () => {
    createRoot((dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();

      world.stream.send(
        snapshot([entry("topic", available), entry("schema", { status: "not_configured" })]),
      );

      expect(world.capabilities.featureState("topic", undefined, true)).toEqual({ kind: "ready" });
      expect(world.capabilities.featureState("schema", undefined, true)).toEqual({
        kind: "not_configured",
      });
      // Nobody has reported this one, so it is starting — never "unavailable", which would be a
      // claim the gateway has not made.
      expect(world.capabilities.featureState("connect", undefined, true).kind).toBe("degraded");
      // Permission outranks every health state, so nothing about the service leaks.
      expect(world.capabilities.featureState("topic", undefined, false)).toEqual({
        kind: "forbidden",
      });

      world.stream.send({ entry: entry("topic", unavailable), previous: available });
      expect(world.capabilities.featureState("topic", undefined, true)).toEqual({
        kind: "unavailable",
        code: "UPSTREAM_UNAVAILABLE",
        message: "the cluster is not answering",
        // The gateway's timestamp, kept exactly as it arrived.
        since: "2026-09-05T09:00:00Z",
      });
      dispose();
    });
  });

  it("keeps the picture when a frame cannot be read", () => {
    createRoot((dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();
      world.stream.send(snapshot([entry("topic", available)]));

      world.stream.sendRaw("{ not json at all");

      // Wiping the navigation because of one bad frame would take every feature from "working" to
      // "unknown" over a typo in one delta.
      expect(world.capabilities.featureState("topic", undefined, true)).toEqual({ kind: "ready" });
      expect(world.warnings).toHaveLength(1);
      dispose();
    });
  });

  it("announces a capability going down once, and its recovery", () => {
    createRoot((dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();
      world.stream.send(snapshot([entry("schema", available, { name: "Schema registry" })]));

      world.stream.send({ entry: entry("schema", unavailable) });
      world.stream.send({ entry: entry("schema", unavailable) });

      // "It is still down" is not news, and on a flapping service it would be a notice every few
      // seconds. The name the gateway reported is used, not the slug.
      expect(world.notices).toEqual([
        {
          tone: "danger",
          title: "Schema registry is unavailable",
          message: "the cluster is not answering",
          dedupKey: "capability-lost:schema/-",
        },
      ]);

      world.stream.send({ entry: entry("schema", available) });
      expect(world.notices.at(-1)).toMatchObject({ tone: "success", title: "Schema registry is back" });
      dispose();
    });
  });

  it("says nothing about a capability it is hearing about for the first time", () => {
    createRoot((dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();

      // The first frame after a page load is the picture, not a transition. Announcing it would
      // greet every operator with a wall of notices about outages that started hours ago.
      world.stream.send(snapshot([entry("schema", unavailable)]));

      expect(world.notices).toEqual([]);
      expect(world.capabilities.featureState("schema", undefined, true).kind).toBe("unavailable");
      dispose();
    });
  });

  it("never unlearns a name", () => {
    createRoot((dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();
      world.stream.send(snapshot([entry("cluster", available, { name: "Production EU" })]));

      // A frame that carries no name leaves the last one standing: a switcher that flipped between
      // the operator's name and the slug as health moved would be worse than one showing the slug.
      world.stream.send({ entry: entry("cluster", unavailable) });

      expect(world.capabilities.nameOf("cluster")).toBe("Production EU");
      expect(world.notices[0]?.title).toBe("Production EU is unavailable");
      dispose();
    });
  });

  it("falls back to polling when the stream drops, and says the picture is stale", async () => {
    await createRoot(async (dispose) => {
      const world = harness({
        poll: () =>
          Promise.resolve<ApiResult<unknown>>({
            ok: true,
            value: snapshot([entry("topic", unavailable)]),
          }),
      });
      world.capabilities.start();
      world.stream.connect();
      world.stream.send(snapshot([entry("topic", available)]));
      expect(world.capabilities.stale()).toBe(false);

      world.stream.drop();
      await world.settle();

      expect(world.capabilities.stale()).toBe(true);
      expect(world.polls).toBe(1);
      expect(world.capabilities.featureState("topic", undefined, true).kind).toBe("unavailable");
      dispose();
    });
  });

  it("does not mark everything unavailable when the poll fails too", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();
      world.stream.send(snapshot([entry("topic", available)]));

      world.stream.drop();
      await world.settle();

      // Both the stream and the poller are down. That means the picture is stale — which `stale`
      // says — and it does not mean the product is broken.
      expect(world.capabilities.stale()).toBe(true);
      expect(world.capabilities.featureState("topic", undefined, true)).toEqual({ kind: "ready" });
      expect(world.notices).toEqual([]);
      dispose();
    });
  });

  it("closes the old stream before opening a new one", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();

      world.stream.drop();
      await world.settle();
      const before = world.stream.closes;

      // Each poll tick is also another go at the stream. Every one of those used to leave an
      // `EventSource` retrying on its own, unreachable and undismissable, one per thirty seconds.
      world.advance(30_000);
      await world.settle();
      world.advance(30_000);
      await world.settle();

      expect(world.stream.closes).toBe(before + 2);
      dispose();
    });
  });

  it("does not let a reconnection attempt take the staleness banner down", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();
      world.stream.drop();
      await world.settle();

      // Re-opening moves the handle to `connecting`. Letting that through showed the user
      // thirty-second-old data as if it were live.
      world.advance(30_000);
      await world.settle();

      expect(world.capabilities.stale()).toBe(true);
      dispose();
    });
  });

  it("stands the poller down when the stream comes back", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();
      world.stream.drop();
      await world.settle();
      expect(world.polls).toBe(1);

      // Each interval is one poll and one more go at the stream.
      world.advance(30_000);
      await world.settle();
      expect(world.polls).toBe(2);

      world.stream.connect();
      await world.settle();

      world.advance(30_000);
      await world.settle();
      world.advance(30_000);
      await world.settle();

      // Recovering onto the stream is what stops the polling.
      expect(world.polls).toBe(2);
      expect(world.capabilities.stale()).toBe(false);
      dispose();
    });
  });

  it("goes quiet when it is stopped", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.capabilities.start();
      world.stream.connect();
      world.stream.drop();
      await world.settle();

      world.capabilities.stop();
      world.advance(30_000);
      await world.settle();
      world.advance(30_000);
      await world.settle();

      // A stopped store must not keep polling and re-opening a stream against a gateway the user
      // may no longer be authenticated to.
      expect(world.polls).toBe(1);
      dispose();
    });
  });
});
