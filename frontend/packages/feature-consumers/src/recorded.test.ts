import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import groupsDocument from "./recorded/groups.json" with { type: "json" };
import { fetchGroups, stateOf } from "./data.js";

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
