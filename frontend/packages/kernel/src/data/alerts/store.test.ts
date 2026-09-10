/**
 * The alert feed store: the one number the bell and the card are not allowed to disagree about.
 *
 * ## Where the bytes in this file come from
 *
 * `services/alerts/contract/src/kui/alerts/contract/dto/AlertDtos.scala` — the `Json.obj(…)` in
 * `AlertEventDto`, `AlertFeedDto`, `AlertFeedResponse` and `AlertChangeDto`, field for field. They
 * are **not** the shape the store's author found convenient, and that distinction is the whole of
 * wave 6's rule 12: wave 5's producers wire had a green unit test on each side and the two sides
 * read different field names, so the decode succeeded, answered an empty array, and the card
 * announced that a source naming five producers had named none.
 *
 * `wireEvent`/`wireFeed`/`body` below are the encoder's shape written once, and every case builds
 * from them rather than from a literal beside its own assertion — so a rename on the server has one
 * place to be wrong in and it is a place that fails.
 */
import { describe, expect, it } from "vitest";
import type { ApiResult } from "@kui/api";
import { createRoot, createSignal, flush } from "solid-js";

import type { SseConnection, SseHandle, SseSubscriber } from "../sse/stream.js";
import { createAlerts, ALERTS_EVENT_NAME, ALERTS_SECTION_KEY } from "./store.js";
import { decodeAlertFeed, type AlertChange } from "./events.js";

/** One event, as `AlertEventDto`'s encoder writes it. Overridable field by field. */
function wireEvent(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: "partition:analytics.clickstream",
    severity: "critical",
    tone: "danger",
    category: "partition",
    glyph: "partition",
    openedAt: "2026-09-06T09:00:00Z",
    lastSeenAt: "2026-09-06T09:04:00Z",
    title: "2 offline partitions",
    detail: "analytics.clickstream p3, p7",
    resolution: null,
    ...over,
  };
}

/** The `data` half, as `AlertFeedDto`'s encoder writes it. */
function wireFeed(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    items: [wireEvent()],
    total: 1,
    openCount: 2,
    unreadCount: 2,
    lastReadAt: null,
    evaluatedAt: "2026-09-06T09:05:00Z",
    rules: [],
    ...over,
  };
}

/** The whole body, as `AlertFeedResponse` writes it: one section under the key `events`. */
function body(data: Record<string, unknown>, status = "ok"): unknown {
  return { events: { status, data, fetchedAt: "2026-09-06T09:05:00Z" } };
}

/** One `AlertChangeDto`. It carries a count and no events — that is the service's design. */
function wireChange(over: Record<string, unknown> = {}): Record<string, unknown> {
  return { cluster: "quickstart", openCount: 3, at: "2026-09-06T09:06:00Z", ...over };
}

function answering(value: unknown): Promise<ApiResult<unknown>> {
  return Promise.resolve<ApiResult<unknown>>({ ok: true, value });
}

/**
 * A stream that behaves the way `../sse/stream.ts` does, including the two things a counter misses.
 *
 * Every `open()` produces a **new** handle with its own connection signal, and `close()` moves that
 * handle to `closed / "closed by the client"` — which is what `SseTransport.close()` does, through
 * `closeWith`. Both matter to this store: it releases one handle and opens another, and a fake that
 * shared one signal across opens, or that counted `close()` without emitting anything, would let a
 * store that kept following a released stream pass a case the transport fails.
 */
function fakeStream() {
  let subscriber: SseSubscriber<AlertChange> | undefined;
  let opens = 0;
  let closes = 0;
  let live: { readonly handle: SseHandle; readonly set: (next: SseConnection) => void } | undefined;

  function openOne(): { handle: SseHandle; set: (next: SseConnection) => void } {
    const [connection, setConnection] = createSignal<SseConnection>(
      { phase: "connecting" },
      { ownedWrite: true },
    );
    const handle: SseHandle = {
      connection,
      close: () => {
        closes += 1;
        setConnection({ phase: "closed", reason: "closed by the client" });
      },
      endMarker: () => undefined,
    };
    return { handle, set: setConnection };
  }

  const setConnection = (next: SseConnection): void => {
    if (live === undefined) throw new Error("nothing is open");
    live.set(next);
  };

  return {
    get opens(): number {
      return opens;
    },
    get closes(): number {
      return closes;
    },
    get listensFor(): readonly string[] {
      return subscriber?.events ?? [];
    },
    open(next: SseSubscriber<AlertChange>): SseHandle {
      subscriber = next;
      opens += 1;
      live = openOne();
      return live.handle;
    },
    /** Feeds a frame the way the wire does: as the text of one `data:` line. */
    send(frame: unknown): void {
      const decoded = subscriber?.decode(ALERTS_EVENT_NAME, JSON.stringify(frame));
      if (decoded === undefined) throw new Error("nothing is subscribed");
      if (decoded.ok) subscriber?.onEvent(decoded.value);
      else subscriber?.onError({ kind: "decode", event: ALERTS_EVENT_NAME, cause: decoded.cause });
      flush();
    },
    /**
     * A terminal transport failure, exactly as the stream module delivers one: the subscriber is
     * told and the connection then reads `closed`. Both halves matter — a fake that only called
     * `onError` would let the store's connection accessor pass a test the real transport fails.
     */
    fail(cause: string): void {
      subscriber?.onError({ kind: "transport", cause });
      setConnection({ phase: "closed", reason: cause });
      flush();
    },
    /** The transport reconnecting on its own, which only the handle can report. */
    reconnecting(attempt: number): void {
      setConnection({ phase: "reconnecting", attempt });
      flush();
    },
    /** The transport is live. */
    live(): void {
      setConnection({ phase: "open" });
      flush();
    },
  };
}

function harness(
  options: {
    load?: (markRead: boolean) => Promise<ApiResult<unknown>>;
    cluster?: () => string | undefined;
  } = {},
) {
  const stream = fakeStream();
  const warnings: string[] = [];
  const reads: boolean[] = [];

  const alerts = createAlerts({
    openStream: (subscriber) => stream.open(subscriber),
    load: (markRead) => {
      reads.push(markRead);
      return options.load?.(markRead) ?? answering(body(wireFeed()));
    },
    cluster: options.cluster ?? (() => "quickstart"),
    warn: (message) => warnings.push(message),
  });

  return {
    stream,
    alerts,
    warnings,
    /** One entry per read, holding the `markRead` it asked for. */
    reads,
    async settle(): Promise<void> {
      await Promise.resolve();
      await Promise.resolve();
      flush();
    },
  };
}

describe("the alert feed store", () => {
  it("the alerts store answers one open count to two subscribers", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();

      // The bell and the card. They are in packages that cannot see each other, so this is the
      // only place they can be made to agree — and a store that opened a stream per reader would
      // give them two answers and two subscriptions on the gateway.
      const bell = (): number | null => world.alerts.openCount();
      const card = (): number | null => world.alerts.openCount();
      expect([bell(), card()]).toEqual([2, 2]);
      expect(world.stream.opens).toBe(1);
      expect(world.reads).toEqual([false]);

      // And they move together, on the frame, before the re-read it triggers has landed. That is
      // why `AlertChangeDto` carries the count at all.
      world.stream.send(wireChange({ openCount: 3 }));
      expect([bell(), card()]).toEqual([3, 3]);
      dispose();
    });
  });

  it("draws the server's open count and never a count of the rows it holds", async () => {
    await createRoot(async (dispose) => {
      // Five rows on this page, of which four are open, and a cluster-wide count of eleven. Every
      // one of those is a different number, and only one of them is the answer — the service's own
      // scaladoc says `openCount` is over the whole store and `items` is one page.
      const items = [
        wireEvent({ id: "a" }),
        wireEvent({ id: "b" }),
        wireEvent({ id: "c" }),
        wireEvent({ id: "d" }),
        wireEvent({ id: "e", resolution: { at: "2026-09-06T05:30:00Z", kind: "acknowledged" } }),
      ];
      const world = harness({
        load: () => answering(body(wireFeed({ items, total: 5, openCount: 11, unreadCount: 7 }))),
      });
      world.alerts.start();
      await world.settle();

      expect(world.alerts.events()).toHaveLength(5);
      expect(world.alerts.openCount()).toBe(11);
      expect(world.alerts.openCount()).not.toBe(4);
      expect(world.alerts.unreadCount()).toBe(7);
      expect(world.alerts.unreadCount()).not.toBe(5);
      dispose();
    });
  });

  it("a feed that carries no counts answers unknown and never zero", async () => {
    await createRoot(async (dispose) => {
      const world = harness({
        load: () => answering(body({ items: [wireEvent()] })),
      });
      world.alerts.start();
      await world.settle();

      // `null` is "KUI does not know how many are open". A zero here is the most reassuring
      // possible rendering of that, and the bell would draw it as "nothing wrong".
      expect(world.alerts.openCount()).toBeNull();
      expect(world.alerts.unreadCount()).toBeNull();
      // And an unknown unread count does not light the bell either.
      expect(world.alerts.unread()).toBe(false);
      expect(world.alerts.events()).toHaveLength(1);
      dispose();
    });
  });

  it("refuses a document it cannot read rather than answering an empty feed", async () => {
    await createRoot(async (dispose) => {
      // The wave-5 wire, in miniature: the encoder called the list `entries` and this build looks
      // for `items`. The section key matches and the status is `ok`, so nothing above this line
      // notices — which is exactly how the producers card came to announce an empty source.
      const world = harness({
        load: () => answering(body({ entries: [wireEvent()], openCount: 2 })),
      });
      world.alerts.start();
      await world.settle();

      const state = world.alerts.feed();
      expect(state.kind).toBe("failed");
      expect(state.kind === "failed" && state.message).toContain("entries");
      expect(world.alerts.events()).toEqual([]);
      expect(world.alerts.openCount()).toBeNull();
      dispose();
    });
  });

  it("carries a severity and a tone it does not recognise, and maps neither", async () => {
    await createRoot(async (dispose) => {
      const world = harness({
        load: () =>
          answering(body(wireFeed({ items: [wireEvent({ severity: "catastrophic", tone: "puce" })] }))),
      });
      world.alerts.start();
      await world.settle();

      // The server sends both halves of the pair so that three screens cannot map them
      // differently. Folding an unfamiliar word onto the nearest one KUI knows would put a tone on
      // screen saying how bad something is when the server never said it.
      expect(world.alerts.events()[0]?.severity).toBe("catastrophic");
      expect(world.alerts.events()[0]?.tone).toBe("puce");
      // And the four words the service does send survive the same way, so the case above is not
      // simply asserting that this decoder drops everything on the floor.
      const known = world.alerts.events();
      expect(known).toHaveLength(1);
      expect(known[0]?.category).toBe("partition");
      expect(known[0]?.glyph).toBe("partition");
      dispose();
    });
  });

  it("refuses an event that carries no tone to draw it in", async () => {
    await createRoot(async (dispose) => {
      const toneless = { ...wireEvent() };
      delete (toneless as Record<string, unknown>)["tone"];
      const world = harness({ load: () => answering(body(wireFeed({ items: [toneless] }))) });
      world.alerts.start();
      await world.settle();

      // There is no honest colour for a row that arrived without one, and the browser choosing it
      // from the severity is the fabrication house rule 7 names — which is why the server derives
      // the tone and sends it.
      const state = world.alerts.feed();
      expect(state.kind).toBe("failed");
      expect(state.kind === "failed" && state.message).toContain("no tone");
      dispose();
    });
  });

  it("reads the resolution the server sent and never infers one", async () => {
    await createRoot(async (dispose) => {
      const world = harness({
        load: () =>
          answering(
            body(
              wireFeed({
                items: [
                  wireEvent({ id: "open-one" }),
                  wireEvent({
                    id: "closed-one",
                    resolution: { at: "2026-09-06T09:30:00Z", kind: "acknowledged", by: "alice" },
                  }),
                ],
              }),
            ),
          ),
      });
      world.alerts.start();
      await world.settle();

      const [open, closed] = world.alerts.events();
      expect(open?.resolution).toBeUndefined();
      expect(closed?.resolution).toEqual({
        at: "2026-09-06T09:30:00Z",
        kind: "acknowledged",
        by: "alice",
      });

      // And a resolution the server sent without a word for it stays without one, rather than
      // acquiring "resolved" from this build and reading as something the service said.
      const bare = decodeAlertFeed(
        wireFeed({ items: [wireEvent({ resolution: { at: "2026-09-06T09:30:00Z" } })] }),
      );
      expect(bare.ok && bare.value.items[0]?.resolution).toEqual({
        at: "2026-09-06T09:30:00Z",
        kind: undefined,
        by: undefined,
      });
      dispose();
    });
  });

  it("does not read a resolution that carries no time as an event that ended", async () => {
    /* Filed by W7-A3. `readResolution`'s own comment argues that "a resolution without a time
       cannot establish that the condition ended" — and nothing asserted it. A resolution that
       carried a `kind` and a `by` and no `at` would have been enough to make `isOpen` answer
       false and the alerts screen's `resolved` filter hide a live event, and the mutation that
       kept it (answering `{ at: "", kind, by }`) left all 452 kernel cases green.

       An event the server has not said ended is open, and that is the direction the mistake has
       to fall in: an open event drawn as resolved is one nobody goes and looks at. */
    const timeless = decodeAlertFeed(
      wireFeed({
        items: [wireEvent({ id: "no-time", resolution: { kind: "acknowledged", by: "alice" } })],
      }),
    );
    expect(timeless.ok).toBe(true);
    expect(timeless.ok && timeless.value.items[0]?.resolution).toBeUndefined();

    // Nor one whose instant is a word rather than an instant.
    const nonsense = decodeAlertFeed(
      wireFeed({
        items: [wireEvent({ id: "bad-time", resolution: { at: "yesterday", kind: "cleared" } })],
      }),
    );
    expect(nonsense.ok && nonsense.value.items[0]?.resolution).toBeUndefined();
  });

  it("refuses an instant that is not one, rather than carrying the server's word for a date", async () => {
    /* Filed by W7-A3. `optionalInstant` parses before it accepts, and the whole decoder rests on
       it: `openedAt` is required, so a row whose `openedAt` is unparseable must refuse the feed
       rather than reach a screen that renders `new Date("last Tuesday")` as `Invalid Date` and
       prints it beside a severity. Deleting the `Date.parse` check left all 452 cases green,
       because every document in this file carries well-formed instants.

       `lastReadAt` takes the same route and matters for a different reason: it is compared
       lexically against `openedAt` to decide what this principal has read, and a value that is
       not an RFC 3339 instant would mark rows read or unread by string order alone. */
    const unparseable = decodeAlertFeed(
      wireFeed({ items: [wireEvent({ id: "when", openedAt: "last Tuesday" })] }),
    );
    expect(unparseable.ok).toBe(false);
    expect(!unparseable.ok && unparseable.cause).toContain("openedAt");

    const marker = decodeAlertFeed(wireFeed({ lastReadAt: "soon" }));
    expect(marker.ok && marker.value.lastReadAt).toBeUndefined();

    // And a real instant still arrives in the server's own spelling, unreformatted.
    const good = decodeAlertFeed(wireFeed({ lastReadAt: "2026-09-06T09:03:00Z" }));
    expect(good.ok && good.value.lastReadAt).toBe("2026-09-06T09:03:00Z");
  });

  it("refuses a feed whose rules are not a list, rather than reporting no rules", async () => {
    /* Filed by W7-A3. `RuleReports` draws nothing at all when `rules` is empty, and its whole
       argument is that a feed drawn without the rule rows "looks identical whether four rules ran
       and found nothing or whether one of them has been refused by an ACL all afternoon". So a
       `rules` value this build cannot read has to refuse the document; silently answering `[]`
       hides the one panel that says which of the two the reader is looking at. Neutering the
       guard left all 452 cases green. */
    const wrong = decodeAlertFeed(wireFeed({ rules: { partition: "ok" } }));
    expect(wrong.ok).toBe(false);
    expect(!wrong.ok && wrong.cause).toContain("rules");

    // An absent `rules` key is still the ordinary case and is not a refusal.
    const absent = decodeAlertFeed(wireFeed({ rules: undefined }));
    expect(absent.ok && absent.value.rules).toEqual([]);
  });

  it("says what the transport says about the connection, holding no second opinion", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();

      // A healthy stream with nothing happening on it. Set by hand from the first frame instead,
      // this reads "connecting" for as long as the cluster stays quiet — which on a well-run
      // cluster is for ever, and the indicator then says the feed never came up.
      world.stream.live();
      expect(world.alerts.connection()).toEqual({ phase: "open" });

      // And the one phase only the transport can know. Nothing in this store could report it.
      world.stream.reconnecting(3);
      expect(world.alerts.connection()).toEqual({ phase: "reconnecting", attempt: 3 });
      dispose();
    });
  });

  it("keeps what it holds when the stream drops, and says it is stale", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();
      expect(world.alerts.feed().kind).toBe("ready");

      world.stream.fail("the connection was lost");

      const state = world.alerts.feed();
      expect(state.kind).toBe("stale");
      // Still readable, still counted, and now badged. Blanking the card at the moment the stream
      // goes takes the last known figures away exactly when they are wanted.
      expect(world.alerts.openCount()).toBe(2);
      expect(world.alerts.connection().phase).toBe("closed");
      dispose();
    });
  });

  it("a read answered after the store was stopped is not applied", async () => {
    await createRoot(async (dispose) => {
      let answer: (result: ApiResult<unknown>) => void = () => {};
      const world = harness({
        load: () => new Promise<ApiResult<unknown>>((resolve) => (answer = resolve)),
      });
      world.alerts.start();
      world.alerts.stop();

      // The request cannot be recalled and the shell has already torn the card down. Painting this
      // over the store repopulates a bell nobody is looking at, on a session the user may have
      // signed out of.
      answer({ ok: true, value: body(wireFeed()) });
      await world.settle();

      expect(world.alerts.feed().kind).toBe("loading");
      expect(world.alerts.openCount()).toBeNull();
      dispose();
    });
  });

  it("an older read that answers last does not overwrite a newer one", async () => {
    await createRoot(async (dispose) => {
      const pending: Array<(result: ApiResult<unknown>) => void> = [];
      const world = harness({
        load: () => new Promise<ApiResult<unknown>>((resolve) => pending.push(resolve)),
      });
      world.alerts.start();
      world.alerts.refresh();
      expect(pending).toHaveLength(2);

      // The second read answers first — a slow gateway and a fast one, or a retried connection —
      // and then the first arrives with the picture as it was before.
      pending[1]?.({ ok: true, value: body(wireFeed({ openCount: 9 })) });
      await world.settle();
      pending[0]?.({ ok: true, value: body(wireFeed({ openCount: 2 })) });
      await world.settle();

      expect(world.alerts.openCount()).toBe(9);
      dispose();
    });
  });

  it("closes its stream when it is stopped", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();
      expect(world.stream.opens).toBe(1);

      world.alerts.stop();
      expect(world.stream.closes).toBe(1);

      // A second stop changes nothing, and a stopped store opens nothing.
      world.alerts.stop();
      expect(world.stream.opens).toBe(1);
      dispose();
    });
  });

  it("says the teardown was its own, and stops following the stream it released", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();
      world.stream.live();
      expect(world.alerts.connection()).toEqual({ phase: "open" });

      world.alerts.stop();
      flush();
      // The close is itself a connection event on the handle. What the store publishes is its own
      // sentence about a deliberate teardown — the frame's connectivity banner reads this, and an
      // operator told the feed went down when they navigated away goes looking for an outage.
      const byTheClient = { phase: "closed", reason: "closed by the client" };
      expect(world.alerts.connection()).toEqual(byTheClient);

      // And a released handle is no longer followed: a transport retrying on a stream nobody is
      // reading would otherwise repaint the banner as `reconnecting` on a store the shell has torn
      // down. `releaseHandle()` holds that with two guards — clearing `handle` and disposing the
      // watcher — and this line was measured against both: it goes red when they are removed
      // together and green when either survives, because either is sufficient alone.
      world.stream.reconnecting(4);
      expect(world.alerts.connection()).toEqual(byTheClient);
      dispose();
    });
  });

  it("a completed read supersedes the count the frame carried", async () => {
    await createRoot(async (dispose) => {
      let served = 2;
      const world = harness({ load: () => answering(body(wireFeed({ openCount: served }))) });
      world.alerts.start();
      await world.settle();
      expect(world.alerts.openCount()).toBe(2);

      // The frame lands first and moves the bell at once; that is the whole reason the service puts
      // a count on it. Then the read it triggered answers with the server's settled figure — which
      // here is neither the old count nor the one the frame guessed at.
      served = 4;
      world.stream.send(wireChange({ openCount: 9 }));
      expect(world.alerts.openCount()).toBe(9);
      await world.settle();

      // The hint is a round trip's worth of head start and not a second source. Kept alive past the
      // read, it wins over every later answer for the life of the tab: the bell draws 9 while the
      // panel it opens lists the 4 the same store just decoded.
      expect(world.alerts.openCount()).toBe(4);
      expect(world.alerts.feed().kind).toBe("ready");
      dispose();
    });
  });

  it("a live count does not outlive the refusal that answered it", async () => {
    await createRoot(async (dispose) => {
      let refused = false;
      const world = harness({
        load: () =>
          refused ? answering({ events: { status: "forbidden" } }) : answering(body(wireFeed())),
      });
      world.alerts.start();
      await world.settle();
      expect(world.alerts.openCount()).toBe(2);

      // The principal loses the capability between one frame and the next read — a role change, or
      // a cluster they no longer see. The frame still arrives and still carries a number.
      refused = true;
      world.stream.send(wireChange({ openCount: 9 }));
      await world.settle();

      // A bell reading 9 over "You do not have permission to see this cluster's alerts" is two
      // answers from one store, and the more confident of them is the wrong one.
      expect(world.alerts.feed().kind).toBe("forbidden");
      expect(world.alerts.openCount()).toBeNull();
      dispose();
    });
  });

  it("answers unknown for a count the rules have never evaluated", async () => {
    await createRoot(async (dispose) => {
      const world = harness({
        load: () => answering(body(wireFeed({ openCount: 7, evaluatedAt: null }))),
      });
      world.alerts.start();
      await world.settle();

      // `evaluatedAt` absent means the rules have not run against this cluster here — after a
      // restart, or before the first pass. Whatever `openCount` says beside that is not a
      // measurement, and the bell must not draw it. The rows are still real and still shown.
      expect(world.alerts.feed().kind).toBe("ready");
      expect(world.alerts.events()).toHaveLength(1);
      expect(world.alerts.openCount()).toBeNull();
      dispose();
    });
  });

  it("a refresh issued after stop() is not applied", async () => {
    await createRoot(async (dispose) => {
      let served = 2;
      const world = harness({ load: () => answering(body(wireFeed({ openCount: served }))) });
      world.alerts.start();
      await world.settle();
      expect(world.alerts.openCount()).toBe(2);

      // A Retry click landing in the same tick the frame unmounts. `stop()` raises the episode, so
      // the read this issues matches its own episode and only `stopped` can refuse it — which is
      // why that clause is not the redundant half of the guard it looks like.
      world.alerts.stop();
      served = 9;
      world.alerts.refresh();
      await world.settle();

      expect(world.reads).toEqual([false, false]);
      expect(world.alerts.openCount()).toBe(2);
      dispose();
    });
  });

  it("subscribes to the feed's own event name and not to a shared one", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();
      // `AlertChangeDto.EventName`. ADR-035's four shared names are handled by the transport, and
      // listing one is a programming error the transport throws on.
      expect(world.stream.listensFor).toEqual([ALERTS_EVENT_NAME]);
      expect(world.stream.listensFor).not.toContain("heartbeat");
      dispose();
    });
  });

  it("a change frame re-reads the feed rather than carrying the rows itself", async () => {
    await createRoot(async (dispose) => {
      let count = 2;
      const world = harness({ load: () => answering(body(wireFeed({ openCount: count }))) });
      world.alerts.start();
      await world.settle();
      expect(world.reads).toEqual([false]);

      count = 3;
      world.stream.send(wireChange({ openCount: 3 }));
      // The count is taken from the frame at once, because that is what it is on the frame for.
      expect(world.alerts.openCount()).toBe(3);
      await world.settle();

      // And the rows come from a read the subscriber makes for itself, which is what keeps one
      // principal's feed off every other subscriber's socket. It does not mark anything read.
      expect(world.reads).toEqual([false, false]);
      expect(world.alerts.openCount()).toBe(3);
      dispose();
    });
  });

  it("re-reads after a same-count change because the rows may have changed", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();

      // One event may close while another opens in the same evaluation. The net count is still two,
      // but the page and its per-principal unread state have changed.
      world.stream.send(wireChange({ openCount: 2 }));
      await world.settle();

      expect(world.reads).toEqual([false, false]);
      expect(world.alerts.openCount()).toBe(2);
      dispose();
    });
  });

  it("ignores a change frame for a cluster this store is not watching", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();
      expect(world.reads).toEqual([false]);

      world.stream.send(wireChange({ cluster: "staging-eu-01", openCount: 41 }));
      await world.settle();

      // A number from a cluster the operator is not looking at, on the bell of the one they are.
      expect(world.alerts.openCount()).toBe(2);
      expect(world.reads).toEqual([false]);
      dispose();
    });
  });

  it("skips a frame it cannot read and keeps the feed on screen", async () => {
    await createRoot(async (dispose) => {
      const world = harness();
      world.alerts.start();
      await world.settle();

      world.stream.send({ openCount: 9 });
      await world.settle();

      expect(world.warnings).toHaveLength(1);
      expect(world.alerts.events()).toHaveLength(1);
      // And nothing the frame carried is taken, because the frame was never read.
      expect(world.alerts.openCount()).toBe(2);
      expect(world.reads).toEqual([false]);
      dispose();
    });
  });

  it("a forbidden section is not a failure, and a not-configured one is neither", async () => {
    await createRoot(async (dispose) => {
      const forbidden = harness({ load: () => answering({ events: { status: "forbidden" } }) });
      forbidden.alerts.start();
      await forbidden.settle();
      // A retry button here is a button that cannot work.
      expect(forbidden.alerts.feed().kind).toBe("forbidden");

      const absent = harness({ load: () => answering({ events: { status: "not_configured" } }) });
      absent.alerts.start();
      await absent.settle();
      // ADR-032: nothing is broken and there is nothing to wait for. The row is hidden, not empty.
      expect(absent.alerts.feed().kind).toBe("not-configured");
      dispose();
    });
  });

  it("the bell goes quiet because the server said so, not because the browser zeroed it", async () => {
    await createRoot(async (dispose) => {
      let unreadCount = 2;
      const world = harness({
        load: (markRead) => {
          if (markRead) unreadCount = 0;
          const lastReadAt = markRead ? "2026-09-06T09:07:00Z" : null;
          return answering(body(wireFeed({ unreadCount, lastReadAt })));
        },
      });
      world.alerts.start();
      await world.settle();
      expect(world.alerts.unread()).toBe(true);

      world.alerts.markAllRead();
      await world.settle();

      // `markAllRead` is the endpoint's own `markRead` query and the answer is the server's new
      // count. A store that set its own number to zero would go quiet even when the write was
      // refused, and the panel it opens would still be full of unread rows.
      expect(world.reads).toEqual([false, true]);
      expect(world.alerts.unreadCount()).toBe(0);
      expect(world.alerts.unread()).toBe(false);
      expect(world.alerts.lastReadAt()).toBe("2026-09-06T09:07:00Z");
      dispose();
    });
  });

  it("stays unread when the server refuses to mark it read", async () => {
    await createRoot(async (dispose) => {
      let refuse = false;
      const world = harness({
        load: (markRead) => {
          if (markRead && refuse) {
            return Promise.resolve<ApiResult<unknown>>({
              ok: false,
              error: { kind: "unreachable", cause: "the gateway is not answering" },
            });
          }
          return answering(body(wireFeed()));
        },
      });
      world.alerts.start();
      await world.settle();
      expect(world.alerts.unread()).toBe(true);

      refuse = true;
      world.alerts.markAllRead();
      await world.settle();

      // The failure is stated rather than swallowed, and nothing pretends the bell was cleared.
      expect(world.alerts.feed().kind).toBe("failed");
      expect(world.alerts.unread()).toBe(false);
      expect(world.alerts.unreadCount()).toBeNull();
      dispose();
    });
  });

  it("names the section key and the rows the milestone will be closed against", () => {
    // M8's exit criterion in `docs/plan/ROADMAP.md` closes the milestone with
    // `jq -e '.events.status == "ok" and (.events.data.items | length) > 0'` and
    // `jq '.events.data.openCount'`, and `AlertDtos.scala` writes exactly those three. A rename on
    // either side has to break something before it reaches a released build; this is the something.
    expect(ALERTS_SECTION_KEY).toBe("events");
    const decoded = decodeAlertFeed(wireFeed());
    expect(decoded.ok).toBe(true);
    expect(decoded.ok && decoded.value.items).toHaveLength(1);
    expect(decoded.ok && decoded.value.openCount).toBe(2);
    // The names wave 5 would have shipped instead, each refused rather than folded into nothing.
    expect(decodeAlertFeed({ events: [wireEvent()], openCount: 2 }).ok).toBe(false);
    expect(decodeAlertFeed({ entries: [wireEvent()], openCount: 2 }).ok).toBe(false);
  });
});
