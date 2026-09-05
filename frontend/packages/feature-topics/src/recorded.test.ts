import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import topicsDocument from "./recorded/topics.json" with { type: "json" };
import overviewDocument from "./recorded/overview.json" with { type: "json" };
import { fetchTopicOverview, fetchTopics } from "./data.js";

/**
 * The mapping, against documents a real gateway produced.
 *
 * The clusters feature shipped a mapping that was wrong in almost every field, and nothing caught
 * it: the server documents each section with `Schema.any`, so every payload is `unknown` here and a
 * misspelled field is a type-correct `undefined` — which every screen renders as an em dash,
 * because "absent" always has a defined reading. A wholly broken mapping therefore looks exactly
 * like a cluster that is not answering.
 *
 * These documents were captured from the quickstart stack and are the only thing that makes the
 * field names checkable. Re-record them with:
 *
 *   curl -s localhost:8080/api/v1/clusters/quickstart/topics | python3 -m json.tool > src/recorded/topics.json
 *   curl -s localhost:8080/api/v1/clusters/quickstart/topics/orders.v1/overview | python3 -m json.tool > src/recorded/overview.json
 *
 * A diff in them is a contract change and should be read as one.
 */
function client(document: unknown): KuiApiClient {
  const get = vi.fn(async () => ({ ok: true, value: document }));
  return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
}

describe("the recorded topic list", () => {
  it("maps the rows the document carries", async () => {
    const answer = await fetchTopics(client(topicsDocument), "quickstart");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    const { topics, incomplete } = answer.value;
    expect(topics.length).toBeGreaterThan(0);
    expect(incomplete).toBe(0);

    const pageviews = topics.find((topic) => topic.name === "analytics.pageviews");
    expect(pageviews).toBeDefined();
    if (pageviews === undefined) return;

    // `partitionCount` on the wire becomes `partitions` on the row, and `messageCount` becomes
    // `records`. Both are renames, which is exactly the kind of thing that silently returns
    // `undefined` when it is got the wrong way round.
    expect(pageviews.partitions).toBe(12);
    expect(pageviews.replicationFactor).toBe(1);
    expect(pageviews.records).toBe(36);
    expect(pageviews.bytes).toBe(2180);
    expect(pageviews.internal).toBe(false);
    expect(pageviews.health).toBe("in-sync");
  });

  it("does not decode the whole list to nothing", async () => {
    // The property, stated separately: a mapping that produced a row of undefineds for every topic
    // would satisfy every null-safety assertion above and render a screen of dashes.
    const answer = await fetchTopics(client(topicsDocument), "quickstart");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    const withFigures = answer.value.topics.filter((topic) => topic.records !== undefined);
    expect(withFigures.length).toBeGreaterThan(0);
  });

  it("reads the page envelope beside the rows", async () => {
    const answer = await fetchTopics(client(topicsDocument), "quickstart");
    if (answer.kind !== "ready") throw new Error("expected ready");
    expect(answer.value.page).toEqual({ page: 1, pageSize: 25, totalItems: 8 });
  });

  it("records that this document has no internal topics, and why", async () => {
    // The recorded call did not pass `showInternal`, and the server excludes Kafka's bookkeeping
    // topics by default — which is how the list page's "Show internal topics" checkbox came to be
    // unable to work: it was filtering data that had never contained one. The route now asks for
    // them. This assertion pins the default so that a server which changes it is noticed here.
    const answer = await fetchTopics(client(topicsDocument), "quickstart");
    if (answer.kind !== "ready") throw new Error("expected ready");
    expect(answer.value.topics.some((topic) => topic.internal)).toBe(false);
  });
});

describe("the recorded topic overview", () => {
  it("maps the topic and its partitions", async () => {
    const answer = await fetchTopicOverview(client(overviewDocument), "quickstart", "orders.v1");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    // The detail is nested under `row`, beside `partitions` — the section's data is not the row.
    expect(answer.value.topic.name).toBe("orders.v1");
    expect(answer.value.topic.partitions).toBe(6);
    expect(answer.value.partitions.length).toBe(6);

    const first = answer.value.partitions[0];
    expect(first).toBeDefined();
    if (first === undefined) return;

    expect(first.partition).toBe(0);
    expect(first.leader).toBe(1);
    // The wire sends replicas as objects with `broker`, `leader` and `inSync`; the screen wants two
    // lists of broker ids, and the in-sync list is derived from the flag so the two cannot disagree.
    expect(first.replicas).toEqual([1]);
    expect(first.inSync).toEqual([1]);
    expect(first.latestOffset).toBe(1);

    // Genuinely null on this cluster — a single broker reports no per-partition size. Not zero.
    expect(first.sizeBytes).toBeNull();
  });
});
