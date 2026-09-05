import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import groupsDocument from "./recorded/groups.json" with { type: "json" };
import groupDocument from "./recorded/group.json" with { type: "json" };
import { fetchGroup, fetchGroups, stateOf } from "./data.js";
import { subscriptions } from "./detail.js";

/**
 * The mapping, against a document a real gateway produced.
 *
 * The reason this file exists rather than a hand-written fixture: the server documents every
 * section with `Schema.any`, so the payloads are `unknown` on this side and a misspelled field is a
 * type-correct `undefined`. Every figure here has a defined rendering for "absent", so a wholly
 * broken mapping renders a table of em dashes — which on this screen reads as *no coordinator
 * answered*. The clusters feature shipped exactly that.
 *
 * Re-record with the quickstart stack running:
 *
 *   curl -s localhost:8080/api/v1/clusters/quickstart/consumer-groups \
 *     | python3 -m json.tool > src/recorded/groups.json
 */
function client(document: unknown): KuiApiClient {
  const get = vi.fn(async () => ({ ok: true, value: document }));
  return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
}

describe("the recorded consumer group list", () => {
  it("maps the rows the document carries", async () => {
    const answer = await fetchGroups(client(groupsDocument), "quickstart");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    const { groups, coordinatorsMissing } = answer.value;
    expect(groups.length).toBeGreaterThan(0);
    expect(coordinatorsMissing).toBe(0);

    const indexer = groups.find((group) => group.groupId === "analytics-indexer");
    expect(indexer).toBeDefined();
    if (indexer === undefined) return;

    expect(indexer.state).toBe("STABLE");
    expect(indexer.members).toBe(1);
    expect(indexer.topics).toBe(1);
    // Zero lag is good news and a real figure. It must survive as `0` and never become a dash —
    // "caught up" and "nobody could work it out" are opposite statements.
    expect(indexer.totalLag).toBe(0);
    expect(indexer.excludedPartitions).toBe(0);
    expect(indexer.incomplete).toBeNull();
    // The wire gives a broker id, not a `host:port`.
    expect(indexer.coordinator).toBe("broker 1");
  });

  it("does not decode the whole list to nothing", async () => {
    const answer = await fetchGroups(client(groupsDocument), "quickstart");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    // A mapping producing a row of nulls for every group satisfies every null-safety assertion and
    // renders a table that says the cluster's coordinators are down.
    expect(answer.value.groups.some((group) => group.state !== null)).toBe(true);
    expect(answer.value.groups.some((group) => group.members !== null)).toBe(true);
  });
});

describe("stateOf", () => {
  it("accepts the states Kafka reports, in any case", () => {
    expect(stateOf("STABLE")).toBe("STABLE");
    expect(stateOf("stable")).toBe("STABLE");
    expect(stateOf("PREPARING_REBALANCE")).toBe("PREPARING_REBALANCE");
  });

  it("refuses a state it does not know rather than passing it through", () => {
    // The chip is a closed set. An unrecognised word would be styled as whatever the default
    // happens to be — and on this screen the default is the healthy colour, so an unknown state
    // would render as a healthy group.
    expect(stateOf("ASSIGNING")).toBeNull();
    expect(stateOf("")).toBeNull();
    expect(stateOf(null)).toBeNull();
    expect(stateOf(undefined)).toBeNull();
  });
});

describe("the recorded group detail", () => {
  it("flattens the wire's per-topic nesting into the offsets the screen draws", async () => {
    const answer = await fetchGroup(client(groupDocument), "quickstart", "analytics-indexer");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    const group = answer.value;
    expect(group.groupId).toBe("analytics-indexer");
    expect(group.state).toBe("STABLE");
    expect(group.partitionAssignor).toBe("range");
    expect(group.protocol).toBe("CLASSIC");
    expect(group.coordinator).toBe("broker 1");

    // The wire nests partitions under `topics`; the table wants one flat list.
    expect(group.offsets.length).toBe(12);
    expect(group.offsets.every((offset) => offset.topic === "analytics.pageviews")).toBe(true);
    // Zero committed is a real position, not an absence. It must survive as `0`.
    expect(group.offsets[0]?.committed).toBe(0);
  });

  it("strips the slash Kafka puts in front of a member's host", () => {
    /*
     * Kafka reports `/172.21.0.4`, because it is rendering a Java `InetSocketAddress`. Not cosmetic:
     * an operator copies this into `ssh` or a `grep`, and `/172.21.0.4` matches nothing.
     */
    return fetchGroup(client(groupDocument), "quickstart", "analytics-indexer").then((answer) => {
      if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
      const member = answer.value.members[0];
      expect(member?.host).toBe("172.21.0.4");
      expect(member?.clientId).toBe("kui-quickstart-indexer");
      // `null` here is a real answer — this group does not use static membership.
      expect(member?.groupInstanceId).toBeNull();
    });
  });

  it("gives the reset wizard the topics this group actually holds offsets on", async () => {
    // The wizard resets one topic at a time and offers only these. Resetting a group on a topic it
    // does not consume writes offsets for a subscription that does not exist.
    const answer = await fetchGroup(client(groupDocument), "quickstart", "analytics-indexer");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    const topics = subscriptions(answer.value);
    expect(topics).toHaveLength(1);
    expect(topics[0]?.topic).toBe("analytics.pageviews");
    expect(topics[0]?.partitions).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);
  });

  it("says the pace is not measured rather than inventing one", async () => {
    /*
     * This endpoint does not carry a rate. Computing one from two observations the browser happens
     * to hold would produce a figure that changes with how often somebody reloaded the page — a
     * number that looks like a measurement and is an artefact of the reader.
     */
    const answer = await fetchGroup(client(groupDocument), "quickstart", "analytics-indexer");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.pace).toBeNull();
  });
});
