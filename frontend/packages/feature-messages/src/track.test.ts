import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import { defaultWindow, emptyQuery, queryProblem, track } from "./track.js";

function client(document: unknown) {
  const sent: { path: string; body: unknown }[] = [];
  const post = vi.fn(async (path: string, init: { body?: unknown }) => {
    sent.push({ path, body: init?.body });
    return { ok: true, value: document };
  });
  return {
    sent,
    api: {
      get: post,
      post,
      put: post,
      delete: post,
      patch: post,
      raw: {},
    } as unknown as KuiApiClient,
  };
}

describe("a track query", () => {
  it("refuses a window that ends before it starts", () => {
    /*
     * Caught here rather than by the server, because an inverted window comes back as "nothing
     * matched" — which is the one answer this screen must never give wrongly. A support engineer
     * would close the ticket.
     */
    const query = {
      ...emptyQuery(),
      topics: ["orders.v1"],
      value: "4711",
      from: "2026-09-05T12:00:00Z",
      to: "2026-09-05T11:00:00Z",
    };
    expect(queryProblem(query)).toMatch(/ends before it starts/);
  });

  it("asks for a header name only when looking in a header", () => {
    const base = { ...emptyQuery(), topics: ["orders.v1"], value: "4711" };
    expect(queryProblem({ ...base, source: "value" })).toBeUndefined();
    expect(queryProblem({ ...base, source: "header" })).toMatch(/Name the header/);
    expect(queryProblem({ ...base, source: "header", header: "traceparent" })).toBeUndefined();
  });

  it("requires at least one topic, which the server also does", () => {
    /*
     * Not "empty means all". The server refuses a track that names no topics — "at least one topic"
     * — and that is the right rule: a track is a full read of everything it is pointed at, and
     * pointing it at a whole cluster during an incident is how an investigation becomes a second
     * outage. The form has to say so rather than letting a round trip say it.
     */
    expect(queryProblem({ ...emptyQuery(), value: "4711", topics: [] })).toMatch(
      /at least one topic/i,
    );
    expect(queryProblem({ ...emptyQuery(), value: "4711", topics: ["orders.v1"] })).toBeUndefined();
  });

  it("refuses a window wider than the deployment will read", () => {
    // `PT168H`, refused by the server with "the window is wider than this deployment allows".
    // Checked here so the operator learns it while choosing the window rather than after a scan
    // they waited for.
    const wide = {
      ...emptyQuery(),
      topics: ["orders.v1"],
      value: "4711",
      from: "2026-08-01T00:00:00Z",
      to: "2026-09-05T00:00:00Z",
    };
    expect(queryProblem(wide)).toMatch(/seven days/i);
  });

  it("always sends the topics, because the decoder requires the field", async () => {
    // Required by the server's decoder even though the published schema marks it optional — one of
    // several places these two have drifted, and the reason this is checked against a real answer.
    const { api, sent } = client({ hits: [], scanned: 0, matched: 0, truncated: false });
    await track(api, "quickstart", { ...emptyQuery(), value: "4711", topics: ["orders.v1"] });
    expect(sent[0]?.body).toHaveProperty("topics", ["orders.v1"]);
  });

  it("sends the header name only for a header match", async () => {
    const { api, sent } = client({ hits: [], scanned: 0, matched: 0, truncated: false });
    await track(api, "quickstart", {
      ...emptyQuery(),
      topics: ["orders.v1"],
      value: "4711",
      source: "value",
      header: "left over",
    });
    expect((sent[0]?.body as { match: Record<string, unknown> }).match).not.toHaveProperty(
      "header",
    );
  });
});

describe("a track result", () => {
  it("keeps the scanned count, which is what makes an empty answer mean anything", async () => {
    /*
     * "Nothing matched" and "nothing was read" are the same screen without it, and they mean
     * opposite things: the value is not there, versus the window was empty and nothing has been
     * established at all.
     */
    const { api } = client({ hits: [], scanned: 4_820, matched: 0, truncated: false });
    const answer = await track(api, "quickstart", {
      ...emptyQuery(),
      topics: ["orders.v1"],
      value: "4711",
    });
    expect(answer.ok).toBe(true);
    if (!answer.ok) return;
    expect(answer.value.scanned).toBe(4820);
    expect(answer.value.matched).toBe(0);
  });

  it("distinguishes a record with no key from one with an empty key", async () => {
    // And a null *value* from an empty one: a null value is a tombstone, which is the single most
    // important thing a row on this screen can be.
    const { api } = client({
      scanned: 2,
      matched: 2,
      truncated: false,
      hits: [
        {
          topic: "orders.v1",
          record: { partition: 0, offset: 1, key: null, value: { text: "{}" } },
        },
        { topic: "orders.v1", record: { partition: 0, offset: 2, key: { text: "" }, value: null } },
      ],
    });
    const answer = await track(api, "quickstart", {
      ...emptyQuery(),
      topics: ["orders.v1"],
      value: "4711",
    });
    if (!answer.ok) return;
    expect(answer.value.hits[0]?.key).toBeNull();
    expect(answer.value.hits[1]?.key).toBe("");
    expect(answer.value.hits[1]?.value).toBeNull();
  });

  it("carries the truncation flag", async () => {
    // A read that stopped early and says nothing is a read somebody draws a conclusion from, and the
    // conclusion may be exactly wrong.
    const { api } = client({ hits: [], scanned: 100000, matched: 0, truncated: true });
    const answer = await track(api, "quickstart", {
      ...emptyQuery(),
      topics: ["orders.v1"],
      value: "4711",
    });
    if (!answer.ok) return;
    expect(answer.value.truncated).toBe(true);
  });
});

describe("the default window", () => {
  it("is the last hour, where an incident being investigated usually is", () => {
    const now = new Date("2026-09-05T12:00:00.000Z");
    const window = defaultWindow(now);
    expect(window.to).toBe("2026-09-05T12:00:00.000Z");
    expect(window.from).toBe("2026-09-05T11:00:00.000Z");
  });
});
