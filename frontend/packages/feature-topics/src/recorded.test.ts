import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import topicsDocument from "./recorded/topics.json" with { type: "json" };
import overviewDocument from "./recorded/overview.json" with { type: "json" };
import configDocument from "./recorded/config.json" with { type: "json" };
import partitionsDocument from "./recorded/partitions.json" with { type: "json" };
import consumersDocument from "./recorded/topic-consumers.json" with { type: "json" };
import planDocument from "./recorded/partition-plan.json" with { type: "json" };
import { fetchTopicConfig, sourceOf } from "./config.js";
import { fetchPartitions, fetchTopicConsumers, fetchTopicOverview, fetchTopics } from "./data.js";
import { planPartitionIncrease } from "./write.js";

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

describe("the recorded topic configuration", () => {
  it("tells the three settings somebody chose from the thirty that are inherited", async () => {
    /*
     * The distinction the whole screen turns on. Kafka reports every key for every topic, and on an
     * ordinary topic almost all of them hold the broker's default; the handful somebody set are the
     * reason anybody opens this tab. Drawing all thirty-three the same way is what makes "why is
     * this topic behaving differently" a twenty-minute job.
     */
    const answer = await fetchTopicConfig(client(configDocument), "quickstart", "orders.v1");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    expect(answer.value.entries).toHaveLength(33);
    expect(answer.value.overridden).toBe(3);

    const set = answer.value.entries.filter((entry) => entry.source === "topic").map((e) => e.name);
    expect(set).toEqual(["compression.type", "min.insync.replicas", "retention.ms"]);
  });

  it("keeps the broker's default beside the value, because reset needs it", async () => {
    const answer = await fetchTopicConfig(client(configDocument), "quickstart", "orders.v1");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    const retention = answer.value.entries.find((entry) => entry.name === "retention.ms");
    expect(retention?.value).toBeDefined();
    expect(retention?.defaultValue).toBeDefined();
    // The two differ — that is what makes it an override — and the dialog shows both so the
    // operator can see what "reset" would actually do.
    expect(retention?.value).not.toBe(retention?.defaultValue);
  });

  it("carries the broker's own documentation, which is all the operator has", async () => {
    const answer = await fetchTopicConfig(client(configDocument), "quickstart", "orders.v1");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    const policy = answer.value.entries.find((entry) => entry.name === "cleanup.policy");
    expect(policy?.documentation).toContain("retention policy");
  });
});

describe("reading a configuration entry's source", () => {
  it("treats only a topic-level setting as an override", () => {
    expect(sourceOf("dynamic-topic")).toBe("topic");
  });

  it("treats everything else as inherited, including a word it does not know", () => {
    /*
     * Kafka's `source` has six members and only one means "somebody set this here". An unrecognised
     * word must not become an override: the badge would appear on a key nobody has touched, which is
     * the same failure as the screen having no badges at all — the operator stops trusting it.
     */
    expect(sourceOf("default")).toBe("inherited");
    expect(sourceOf("static-broker")).toBe("inherited");
    expect(sourceOf("something-new")).toBe("inherited");
    expect(sourceOf(null)).toBe("inherited");
    expect(sourceOf(undefined)).toBe("inherited");
  });
});

describe("the recorded partition table", () => {
  it("maps every partition the endpoint returns", async () => {
    const answer = await fetchPartitions(client(partitionsDocument), "quickstart", "orders.v1");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    // Six, and in the order the server sent them. The tab does not sort: partition order *is*
    // partition id, and re-sorting a table whose first column is already the natural key gains
    // nothing and loses the ability to say "look at row 4".
    expect(answer.value.map((partition) => partition.partition)).toEqual([0, 1, 2, 3, 4, 5]);
  });

  it("reads the same partition shape the overview nests, through the same mapping", async () => {
    /*
     * The two endpoints answer with the same `PartitionDto`, so both go through `toPartition`. What
     * is compared is the *shape* and not the values: the two documents were captured minutes apart
     * from a quickstart whose seeded consumer keeps producing, so their offsets differ by however
     * long sat between the two curls. Asserting equal offsets would be asserting that nothing
     * happened in the cluster, which is a property of the recording session and not of the mapping.
     *
     * The shape is the part that can break. If the standalone endpoint ever grows a different
     * replica structure, every row here becomes a row of dashes and nothing else notices.
     */
    const fromTab = await fetchPartitions(client(partitionsDocument), "quickstart", "orders.v1");
    const fromOverview = await fetchTopicOverview(
      client(overviewDocument),
      "quickstart",
      "orders.v1",
    );
    if (fromTab.kind !== "ready" || fromOverview.kind !== "ready") throw new Error("expected ready");

    expect(fromTab.value).toHaveLength(fromOverview.value.partitions.length);
    const keysOf = (row: object): readonly string[] => Object.keys(row).sort();
    expect(fromTab.value.map(keysOf)).toEqual(fromOverview.value.partitions.map(keysOf));
    // And the derived halves agree, which is the pair a wrong mapping gets wrong first.
    expect(fromTab.value.map((row) => row.replicas)).toEqual(
      fromOverview.value.partitions.map((row) => row.replicas),
    );
    expect(fromTab.value.map((row) => row.inSync)).toEqual(
      fromOverview.value.partitions.map((row) => row.inSync),
    );
  });

  it("keeps a leader, its replicas and its in-sync list apart", async () => {
    const answer = await fetchPartitions(client(partitionsDocument), "quickstart", "orders.v1");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);

    const first = answer.value[0];
    expect(first).toBeDefined();
    if (first === undefined) return;

    expect(first.leader).toBe(1);
    // The wire sends replicas as `{broker, leader, inSync}` objects. Both lists here are broker
    // ids, and `inSync` is derived from the flag rather than read from a second field, so the two
    // cannot disagree about a replica.
    expect(first.replicas).toEqual([1]);
    expect(first.inSync).toEqual([1]);
    // Nothing has been deleted by retention, so the window starts at zero — a fact, printed as `0`.
    expect(first.earliestOffset).toBe(0);
    expect(first.latestOffset).toBe(8);
    expect(first.messageCount).toBe(8);
    // Genuinely absent on a single-broker cluster with no metrics source. `null`, and it draws as a
    // dash: a partition holding eight records does not occupy no disk.
    expect(first.sizeBytes).toBeNull();
  });

  it("does not decode the whole table to nothing", async () => {
    // The property stated separately, for the reason in this file's header: a mapping that produced
    // six rows of `undefined` would satisfy every assertion that only checks for absence.
    const answer = await fetchPartitions(client(partitionsDocument), "quickstart", "orders.v1");
    if (answer.kind !== "ready") throw new Error("expected ready");
    expect(answer.value.filter((partition) => partition.latestOffset !== null)).toHaveLength(6);
    expect(answer.value.some((partition) => (partition.messageCount ?? 0) > 0)).toBe(true);
  });

  it("is an ADR-039 section, so an unavailable partition table is not an empty one", async () => {
    /*
     * The response is `{"partitions": {"status": "ok", "data": [...]}}` and not a bare array. A
     * mapping that read `answer.value.partitions` as the array would find an object, map nothing,
     * and render "this topic has no partitions" — a sentence that is never true of a Kafka topic.
     */
    /* `reason` is the code, as a *string*, and `message` is its sibling — not a nested object.
       Getting that wrong here produced a section with the code `unknown`, which is what the decoder
       falls back to and is exactly the silent degradation this suite exists to catch. */
    const unavailable = {
      partitions: {
        status: "unavailable",
        reason: "KUI-UPSTREAM-UNAVAILABLE",
        message: "the cluster service did not answer",
      },
    };
    const answer = await fetchPartitions(client(unavailable), "quickstart", "orders.v1");
    expect(answer.kind).toBe("failed");
    if (answer.kind !== "failed") return;
    expect(answer.code).toBe("KUI-UPSTREAM-UNAVAILABLE");
  });
});

describe("the recorded consumer groups for one topic", () => {
  it("maps the group, its lag on this topic, and the dormant mark", async () => {
    const answer = await fetchTopicConsumers(client(consumersDocument), "quickstart", "orders.v1");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    expect(answer.value).toHaveLength(1);
    const row = answer.value[0];
    expect(row).toBeDefined();
    if (row === undefined) return;

    // `groupId` and `state` are nested under `group`; `topicLag`, `partitions` and `dormant` sit
    // beside it. Reading either from the wrong level is a type-correct `undefined` on this side.
    expect(row.groupId).toBe("order-fulfilment");
    expect(row.state).toBe("EMPTY");
    // The figure the recording holds. It is `topicLag` and not `group.totalLag`; on this group the
    // two happen to be equal, because it reads one topic — which is why the assertion below that it
    // reads exactly one topic is part of reading this one.
    expect(row.topicLag).toBe(21);
    expect(row.topics).toBe(1);
    expect(row.partitions).toBe(6);
    expect(row.dormant).toBe(true);
    // Zero members is a *fact* about this group, and it is what "dormant" means. It prints as `0`.
    expect(row.members).toBe(0);
  });

  it("is not an ADR-039 section, which is the drift this test exists for", async () => {
    /*
     * Every other read in `data.ts` is sectioned. This one is a bare `{ "rows": [...] }` — verified
     * against a running gateway. Decoding it as a section would find no `status`, yield nothing, and
     * the tab would say "no consumer group reads this topic" about a topic with a group on it.
     */
    expect(Object.keys(consumersDocument)).toEqual(["rows"]);
    expect("status" in consumersDocument).toBe(false);
  });

  it("answers with no rows for a topic nothing reads, which is not a failure", async () => {
    // Recorded from a freshly created topic: the server answers `{"rows": []}` rather than 404 or
    // an empty section. The tab draws "no consumer group reads this topic", which is a fact.
    const answer = await fetchTopicConsumers(client({ rows: [] }), "quickstart", "m4.scratch");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;
    expect(answer.value).toHaveLength(0);
  });

  it("keeps a lag that could not be computed apart from a lag of zero", async () => {
    const answer = await fetchTopicConsumers(
      client({ rows: [{ group: { groupId: "g", state: "STABLE", members: 2 }, partitions: 3, dormant: false }] }),
      "quickstart",
      "orders.v1",
    );
    if (answer.kind !== "ready") throw new Error("expected ready");
    // `null`, never `0`. A group whose lag KUI could not compute has not caught up.
    expect(answer.value[0]?.topicLag).toBeNull();
  });
});

describe("the recorded partition-increase plan", () => {
  it("maps the plan and derives what the schema does not carry", async () => {
    const answer = await planPartitionIncrease(
      client(planDocument),
      "quickstart",
      "orders.v1",
      12,
    );
    expect(answer.ok).toBe(true);
    if (!answer.ok) return;

    expect(answer.value.topic).toBe("orders.v1");
    expect(answer.value.current).toBe(6);
    expect(answer.value.target).toBe(12);
    /*
     * The real response carries `added: 6` and `PartitionPlanDto` in `openapi.browser.json` does
     * not declare it. So it is derived here rather than read: a field that is not in the schema is
     * not in the generated types, and reading it would be a cast today and an `undefined` the day
     * the server stops sending it. The value below matches what the server sent, which is the whole
     * point of deriving it rather than inventing a different arithmetic.
     */
    expect(answer.value.added).toBe(6);
    expect(answer.value.token).not.toBeNull();
    expect(answer.value.expiresAt).not.toBeNull();
  });

  it("carries the server's own warning about key routing, which the dialog shows verbatim", async () => {
    const answer = await planPartitionIncrease(client(planDocument), "quickstart", "orders.v1", 12);
    if (!answer.ok) throw new Error("expected a plan");

    expect(answer.value.warnings).toHaveLength(1);
    const warning = answer.value.warnings[0];
    expect(warning?.code).toBe("KEY_ROUTING_CHANGES");
    /* The sentence the operator reads is the server's, not one composed here: it names both counts,
       which the browser could do, and the ordering guarantee that breaks, which is the part a
       client-side sentence has historically got wrong. */
    expect(warning?.message).toContain("hash(key) % partitions");
    expect(warning?.message).toContain("Per-key ordering");
  });

  it("reports a plan the server spent as having no token left", async () => {
    /*
     * Applying the increase answers with the same plan and `token: null`. Recorded from a real
     * apply against a scratch topic. A screen that read the returned `token` as still valid would
     * offer a second confirmation of a change that has already happened.
     */
    const applied = { ...planDocument, token: null, expiresAt: null };
    const answer = await planPartitionIncrease(client(applied), "quickstart", "orders.v1", 12);
    if (!answer.ok) throw new Error("expected a plan");
    expect(answer.value.token).toBeNull();
    expect(answer.value.expiresAt).toBeNull();
  });
});
