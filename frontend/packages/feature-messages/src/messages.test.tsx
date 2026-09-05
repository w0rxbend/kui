/**
 * The session and the screen.
 *
 * Every case below is a rule from the brief or from a defect the Laminar version was corrected for,
 * and each one is written so that it fails for the right reason: the pause test loses a record if
 * the queue is dropped, the identity test fails if the filter field is rebuilt, the tick test fails
 * if two records in one microtask are folded with `setRows((previous) => …)`.
 */

import { describe, expect, test, vi } from "vitest";
import { flush } from "solid-js";
import type { KafkaRecord } from "@kui/kernel";
import { mount } from "./testing.js";
import { DEFAULT_BROWSE, type BrowseQuery } from "./browse.js";
import {
  createBrowseSession,
  decodeBrowseEvent,
  MAX_ROWS,
  type BrowseHandle,
  type BrowseSession,
  type BrowseTransport,
} from "./session.js";
import { MessagesTab, pauseLabel } from "./MessagesTab.jsx";
import { toRecord, type MessageDto } from "./wire.js";

/** A transport that runs no network: the test drives the stream by hand. */
function fakeTransport(): {
  readonly transport: BrowseTransport;
  readonly urls: string[];
  emit: (record: KafkaRecord) => void;
  close: (marker?: string) => void;
  closes: () => number;
} {
  const urls: string[] = [];
  let handlers: Parameters<BrowseTransport["open"]>[1] | undefined;
  let marker: string | undefined;
  let closed = 0;
  const transport: BrowseTransport = {
    open: (url, given) => {
      urls.push(url);
      handlers = given;
      marker = undefined;
      const handle: BrowseHandle = {
        close: () => {
          closed += 1;
        },
        endMarker: () => marker,
      };
      return handle;
    },
  };
  return {
    transport,
    urls,
    emit: (record) => handlers?.onEvent({ kind: "record", record }),
    close: (end) => {
      marker = end;
      handlers?.onConnection({ phase: "closed", reason: "done" });
    },
    closes: () => closed,
  };
}

function record(offset: string, partition = 0): KafkaRecord {
  return {
    offset,
    partition,
    key: `ord_${offset}`,
    timestamp: "2026-09-05T10:00:00Z",
    headers: [],
    value: { kind: "json", text: `{"orderId":"ord_${offset}"}` },
  };
}

function withSession(run: (session: BrowseSession, fake: ReturnType<typeof fakeTransport>) => void): void {
  const fake = fakeTransport();
  /* The session registers an `onCleanup`, so it needs an owner. Mounting a component that creates
   * it is the honest way to give it one — and it is also how the screen uses it. */
  const { dispose } = mount(() => {
    const session = createBrowseSession({ streamUrl: "/stream", transport: fake.transport });
    run(session, fake);
    return null;
  });
  dispose();
}

describe("a browse session", () => {
  test("keeps records newest first", async () => {
    withSession((session, fake) => {
      session.start(DEFAULT_BROWSE);
      fake.emit(record("1"));
      fake.emit(record("2"));
      void flush();
      expect(session.rows().map((r) => r.offset)).toEqual(["2", "1"]);
    });
  });

  test("does not lose a record when two arrive in the same tick", () => {
    // The Solid 2 defect this session's shape exists to avoid: an updater is applied to the last
    // *committed* value, so two `setRows((previous) => [x, ...previous])` in one microtask would
    // both start from the same array and one record would vanish.
    withSession((session, fake) => {
      session.start(DEFAULT_BROWSE);
      fake.emit(record("1"));
      fake.emit(record("2"));
      fake.emit(record("3"));
      void flush();
      expect(session.rows()).toHaveLength(3);
    });
  });

  test("caps a live tail rather than growing without bound", () => {
    withSession((session, fake) => {
      session.start({ ...DEFAULT_BROWSE, live: true });
      for (let i = 0; i < MAX_ROWS + 25; i += 1) fake.emit(record(String(i)));
      void flush();
      expect(session.rows()).toHaveLength(MAX_ROWS);
      // The newest end is the one kept: that is what following live means.
      expect(session.rows()[0]?.offset).toBe(String(MAX_ROWS + 24));
    });
  });

  test("a pause holds records back and releasing shows every one of them, in order", () => {
    withSession((session, fake) => {
      session.start({ ...DEFAULT_BROWSE, live: true });
      fake.emit(record("1"));
      session.setPaused(true);
      fake.emit(record("2"));
      fake.emit(record("3"));
      void flush();
      // On screen: only the record that arrived before the pause. Held: the two after it.
      expect(session.rows().map((r) => r.offset)).toEqual(["1"]);
      expect(session.held()).toBe(2);
      // But the count still moves, because it counts what the *stream* delivered — a paused screen
      // that also stopped counting is indistinguishable from a stream that stalled.
      expect(session.progress().delivered).toBe(3);

      session.setPaused(false);
      void flush();
      expect(session.rows().map((r) => r.offset)).toEqual(["3", "2", "1"]);
      expect(session.held()).toBe(0);
    });
  });

  test("stopping shows what was held rather than discarding it", () => {
    // Those records were delivered. Throwing them away because somebody pressed Stop loses
    // evidence that arrived before the press, and on a tail there is no second chance to read it.
    withSession((session, fake) => {
      session.start({ ...DEFAULT_BROWSE, live: true });
      session.setPaused(true);
      fake.emit(record("9"));
      session.stop();
      void flush();
      expect(session.rows().map((r) => r.offset)).toEqual(["9"]);
      expect(fake.closes()).toBe(1);
    });
  });

  test("a stream that ends by itself stops reading as running", () => {
    // Without this, a bounded browse left the control saying "Stop" for ever, beside a status line
    // reading "Finished", with no way back to Read short of reloading the page.
    withSession((session, fake) => {
      session.start(DEFAULT_BROWSE);
      void flush();
      expect(session.running()).toBe(true);
      fake.close();
      void flush();
      expect(session.running()).toBe(false);
    });
  });

  test("offers a next page only when the server sent a cursor", () => {
    withSession((session, fake) => {
      session.start(DEFAULT_BROWSE);
      fake.close();
      void flush();
      // The server omits a cursor whenever asking again would be pointless. This is its answer,
      // not a guess from a full page — the guess that puts "Load more" under every last page.
      expect(session.canLoadMore()).toBe(false);

      session.start(DEFAULT_BROWSE);
      fake.close("cursor-1");
      void flush();
      expect(session.canLoadMore()).toBe(true);
    });
  });

  test("load more appends and sends the cursor, not the seek", () => {
    withSession((session, fake) => {
      session.start({ ...DEFAULT_BROWSE, partitions: [3] });
      fake.emit(record("1"));
      fake.close("cursor-1");
      void flush();

      session.loadMore();
      fake.emit(record("2"));
      void flush();

      expect(session.rows().map((r) => r.offset)).toEqual(["2", "1"]);
      const second = fake.urls[1] as string;
      expect(second).toContain("cursor=cursor-1");
      expect(second).not.toContain("seekTo");
      // The *last browse's* partitions, not whatever the controls hold now: a continuation that
      // silently changed range would move the reader sideways.
      expect(second).toContain("partition=3");
    });
  });

  test("survives a transport that finishes the stream before open() returns", () => {
    // Found by looking at a story, not by reasoning: a transport may report a terminal state
    // synchronously from inside `open` — one that refuses the request before sending it, or a
    // scripted one in a story. The first version of this file read the handle out of the `const`
    // that `open` was still being assigned to and threw `Cannot access 'opened' before
    // initialization`, taking the whole screen down. Every other case here drives the stream
    // *after* `open` has returned, which is exactly why none of them saw it.
    const synchronous: BrowseTransport = {
      open: (_url, handlers) => {
        handlers.onEvent({ kind: "record", record: record("1") });
        handlers.onConnection({ phase: "closed", reason: "done" });
        return { close: () => undefined, endMarker: () => "cursor-1" };
      },
    };
    const { dispose } = mount(() => {
      const session = createBrowseSession({ streamUrl: "/s", transport: synchronous });
      session.start(DEFAULT_BROWSE);
      void flush();
      expect(session.rows()).toHaveLength(1);
      expect(session.running()).toBe(false);
      // And the continuation the server sent is still picked up, rather than lost with the
      // early close.
      expect(session.canLoadMore()).toBe(true);
      return null;
    });
    dispose();
  });

  test("load more with no cursor does nothing at all", () => {
    withSession((session, fake) => {
      session.loadMore();
      expect(fake.urls).toHaveLength(0);
    });
  });

  test("a new browse replaces the rows; it does not mix two ranges", () => {
    withSession((session, fake) => {
      session.start(DEFAULT_BROWSE);
      fake.emit(record("1"));
      void flush();
      session.start({ ...DEFAULT_BROWSE, seek: { kind: "beginning" } });
      void flush();
      expect(session.rows()).toEqual([]);
    });
  });

  test("a failure is held beside the rows rather than replacing them", () => {
    withSession((session, fake) => {
      session.start(DEFAULT_BROWSE);
      fake.emit(record("1"));
      void flush();
      // The transport reports the failure through the same handlers.
      fake.transport.open("/stream", {
        onEvent: () => undefined,
        onFailure: () => undefined,
        onConnection: () => undefined,
      });
      expect(session.rows()).toHaveLength(1);
    });
  });
});

describe("decoding what the stream sends", () => {
  test("one unreadable event does not end the stream", () => {
    // A decode failure is informational; the transport reports it and keeps going, which is the
    // same rule ADR-035 gives the server.
    expect(decodeBrowseEvent("message", "not json")).toEqual({ ok: false, cause: "not JSON" });
  });

  test("reads a phase and a consumed figure", () => {
    expect(decodeBrowseEvent("phase", '{"phase":"seeking"}')).toEqual({
      ok: true,
      value: { kind: "phase", name: "seeking" },
    });
    // The field is `records`, verified against `ConsumedDto` in
    // `services/message/contract/src/kui/message/contract/StreamEventDtos.scala`. This assertion
    // used to say `messages`, which the server has never sent — so the decoder read the fallback
    // and the status line reported "scanned 0 records" for every browse that has ever run, while
    // the test passed against a payload nobody produces.
    expect(decodeBrowseEvent("consumed", '{"records":10,"bytes":40,"elapsedMs":5,"filterErrors":2}')).toEqual({
      ok: true,
      value: { kind: "consumed", consumed: { records: 10, bytes: 40, elapsedMs: 5, filterErrors: 2 } },
    });

    // A server that sends no `filterErrors` is not an error; the field is simply absent.
    expect(decodeBrowseEvent("consumed", '{"records":3,"bytes":9,"elapsedMs":1}')).toEqual({
      ok: true,
      value: { kind: "consumed", consumed: { records: 3, bytes: 9, elapsedMs: 1 } },
    });
  });
});

describe("a record on the wire", () => {
  const base: MessageDto = {
    partition: 3,
    offset: 18442901,
    timestamp: "2026-09-05T10:00:00Z",
    timestampType: "CreateTime",
    key: { text: "ord_9f21ac", kind: "string", serde: "String", properties: {} },
    value: { text: '{"a":1}', kind: "json", serde: "Json", properties: {} },
    headers: { "content-type": "application/json" },
    keySize: 10,
    valueSize: 7,
    headersSize: 24,
    deserializeErrors: [],
  };

  test("carries the offset as a string", () => {
    expect(toRecord(base).offset).toBe("18442901");
  });

  test("a null key is null, not the empty string", () => {
    // A null key in a compacted topic *is* the deletion, and the row draws the two differently.
    const dto = { ...base, key: { text: "", kind: "null", serde: "String", properties: {} } };
    expect(toRecord(dto).key).toBeNull();
  });

  test("a failed decode wins over the kind the fallback serde reported", () => {
    // The fallback delivers something plausible-looking, so reading `kind` first would draw a
    // decode failure as an ordinary text payload — absorbing the very failure that was sent
    // alongside the record so that it would not be absorbed.
    const dto: MessageDto = {
      ...base,
      value: { text: "0xdeadbeef", kind: "binary", serde: "Fallback", properties: {} },
      deserializeErrors: [{ target: "value", serde: "Avro", cause: "schema 42 not found" }],
    };
    expect(toRecord(dto).value).toEqual({
      kind: "undecodable",
      reason: "schema 42 not found",
      hex: "0xdeadbeef",
    });
  });

  test("a key failure does not paint the value red", () => {
    const dto: MessageDto = {
      ...base,
      deserializeErrors: [{ target: "key", serde: "Int64", cause: "not eight bytes" }],
    };
    expect(toRecord(dto).value.kind).toBe("json");
  });

  test("a timestamp type KUI does not know is dropped rather than shown", () => {
    expect(toRecord({ ...base, timestampType: "NoTimestampType" }).timestampType).toBeUndefined();
  });
});

describe("the messages screen", () => {
  function screen(query: BrowseQuery = DEFAULT_BROWSE) {
    const fake = fakeTransport();
    let session!: BrowseSession;
    const mounted = mount(() => {
      session = createBrowseSession({ streamUrl: "/stream", transport: fake.transport });
      return (
        <MessagesTab
          topic="orders.payments.v2"
          partitionCount={12}
          query={query}
          onQueryChange={() => undefined}
          session={session}
          now={Date.parse("2026-09-05T10:00:02Z")}
        />
      );
    });
    return { ...mounted, fake, session: () => session };
  }

  test("does not read anything until somebody asks", () => {
    // A browse is a real Kafka consumer. Starting one because somebody clicked the wrong tab is
    // how a cluster ends up with consumers nobody asked for.
    const { fake, dispose } = screen();
    expect(fake.urls).toHaveLength(0);
    expect(document.body.textContent).toContain("Nothing has been read yet");
    dispose();
  });

  test("does not rebuild the filter field when records arrive", async () => {
    // The defect this rule was written for: the bar was rebuilt whenever results landed, and the
    // caret went with it. Holding the node and comparing identity is the only assertion that
    // actually catches it — the text would look right either way.
    const { container, fake, session, dispose } = screen();
    const field = container.querySelector<HTMLInputElement>('input[placeholder="Filter by key or value…"]');
    expect(field).not.toBeNull();

    session().start(DEFAULT_BROWSE);
    fake.emit(record("1"));
    await flush();

    expect(
      container.querySelector<HTMLInputElement>('input[placeholder="Filter by key or value…"]'),
    ).toBe(field);
    dispose();
  });

  test("draws one row per record, with the offset grouped and the partition named", async () => {
    const { container, fake, session, dispose } = screen();
    session().start(DEFAULT_BROWSE);
    fake.emit(record("18442901", 3));
    await flush();

    const rows = container.querySelectorAll(".kui-record");
    expect(rows).toHaveLength(1);
    expect(container.textContent).toContain("18,442,901");
        // A non-breaking space, so that "p" and its number never wrap apart at a narrow width.
    expect(container.textContent).toContain("p\u00a03");
    dispose();
  });

  test("the whole row is the control and it says which way it will go", async () => {
    const { container, fake, session, dispose } = screen();
    session().start(DEFAULT_BROWSE);
    fake.emit(record("1"));
    await flush();

    const summary = container.querySelector<HTMLButtonElement>(".kui-record__summary");
    expect(summary?.tagName).toBe("BUTTON");
    // The string, not the boolean: in Solid 2 a `false` boolean attribute is *removed*, and
    // `aria-expanded` absent means "this is not an expandable thing at all" — the opposite of
    // what a collapsed row is.
    expect(summary?.getAttribute("aria-expanded")).toBe("false");
    summary?.click();
    await flush();
    expect(summary?.getAttribute("aria-expanded")).toBe("true");
    dispose();
  });

  test("expanding reveals the four labelled boxes and the headers label", async () => {
    const { container, fake, session, dispose } = screen();
    session().start(DEFAULT_BROWSE);
    fake.emit(record("1"));
    await flush();
    container.querySelector<HTMLButtonElement>(".kui-record__summary")?.click();
    await flush();

    for (const label of ["OFFSET", "PARTITION", "KEY", "TIMESTAMP", "HEADERS", "VALUE"]) {
      expect(container.textContent).toContain(label);
    }
    // The label stays even with no headers. Dropping it makes the reader wonder whether the
    // product looked.
    expect(container.textContent).toContain("— none");
    dispose();
  });

  test("distinguishes a filtered empty screen from an empty one", async () => {
    const { container, fake, session, dispose } = screen({ ...DEFAULT_BROWSE, contains: "nope" });
    session().start({ ...DEFAULT_BROWSE, contains: "nope" });
    fake.close();
    await flush();
    expect(container.textContent).toContain("No record matched that filter");
    dispose();
  });

  test("the pause control never draws a zero as a quantity", () => {
    // `Resume (0)` is a zero drawn as a quantity — the same rule as a magnitude bar that draws an
    // empty value as a full-width track.
    expect(pauseLabel(false, 0)).toBe("Pause");
    expect(pauseLabel(true, 0)).toBe("Resume");
    expect(pauseLabel(true, 1204)).toBe("Resume (1,204)");
  });

  test("the LIVE pill is a toggle and reads PAUSED when it is off", () => {
    const { container, dispose } = screen();
    expect(container.textContent).toContain("PAUSED");
    dispose();
  });

  test("live tailing that cannot be offered stays in the bar, disabled, and says so", () => {
    // Removing it would tell the operator the product cannot tail at all.
    const fake = fakeTransport();
    const { container, dispose } = mount(() => (
      <MessagesTab
        topic="t"
        partitionCount={1}
        query={DEFAULT_BROWSE}
        onQueryChange={() => undefined}
        session={createBrowseSession({ streamUrl: "/s", transport: fake.transport })}
        liveAvailability={{ available: false, reason: "The message service is not reachable." }}
      />
    ));
    expect(container.textContent).toContain("LIVE unavailable");
    dispose();
  });

  test("a topic with one partition still shows the partition selector, disabled", () => {
    // Hiding it would make an operator think this topic is different in some way they cannot see.
    const fake = fakeTransport();
    const { container, dispose } = mount(() => (
      <MessagesTab
        topic="t"
        partitionCount={1}
        query={DEFAULT_BROWSE}
        onQueryChange={() => undefined}
        session={createBrowseSession({ streamUrl: "/s", transport: fake.transport })}
      />
    ));
    const trigger = container.querySelector<HTMLButtonElement>(".kui-partition-picker__trigger");
    expect(trigger?.disabled).toBe(true);
    expect(trigger?.textContent).toContain("all 1");
    dispose();
  });

  test("the produce action is disabled with a reason rather than hidden", () => {
    // A hidden button makes an operator think the product cannot do the thing at all.
    const fake = fakeTransport();
    const { container, dispose } = mount(() => (
      <MessagesTab
        topic="t"
        partitionCount={2}
        query={DEFAULT_BROWSE}
        onQueryChange={() => undefined}
        session={createBrowseSession({ streamUrl: "/s", transport: fake.transport })}
        mayProduce={false}
        onProduce={() => undefined}
        produceDisabledReason="You do not hold a role that permits producing to this topic."
      />
    ));
    const button = [...container.querySelectorAll("button")].find((b) =>
      b.textContent?.includes("Produce message"),
    );
    expect(button).toBeDefined();
    expect(button?.getAttribute("aria-disabled")).toBe("true");
    dispose();
  });

  test("changing where to read stops whatever is running", async () => {
    // A browse in flight is reading a different range from the one the controls now describe.
    const fake = fakeTransport();
    const changes = vi.fn();
    let session!: BrowseSession;
    const { container, dispose } = mount(() => {
      session = createBrowseSession({ streamUrl: "/s", transport: fake.transport });
      return (
        <MessagesTab
          topic="t"
          partitionCount={12}
          query={DEFAULT_BROWSE}
          onQueryChange={changes}
          session={session}
        />
      );
    });
    session.start(DEFAULT_BROWSE);
    await flush();
    const pill = [...container.querySelectorAll("button")].find((b) => b.textContent === "PAUSED");
    pill?.click();
    await flush();
    expect(fake.closes()).toBeGreaterThan(0);
    // And turning LIVE on sets the seek to the end rather than sending both, which the server
    // refuses.
    expect(changes).toHaveBeenCalledWith(
      expect.objectContaining({ live: true, seek: { kind: "latest" } }),
    );
    dispose();
  });
});
