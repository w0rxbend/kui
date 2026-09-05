import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import groupsDocument from "./recorded/groups.json" with { type: "json" };
import groupDocument from "./recorded/group.json" with { type: "json" };
import fullDocument from "./recorded/lag-full.json" with { type: "json" };
import deltaDocument from "./recorded/lag-delta.json" with { type: "json" };
import quietDocument from "./recorded/lag-quiet.json" with { type: "json" };
import expiredDocument from "./recorded/lag-expired.json" with { type: "json" };
import { fetchGroup, fetchGroups, stateOf } from "./data.js";
import { subscriptions } from "./detail.js";
import { applyLagDelta, fetchLagDelta, pollLag } from "./lag.js";
import type { GroupSummary } from "./model.js";

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

/* ---------------------------------------------------------------------------------------------- */
/* Incremental lag                                                                                  */
/* ---------------------------------------------------------------------------------------------- */

/**
 * The incremental lag protocol, against four documents a running gateway produced.
 *
 * Re-record with the quickstart stack running. The order matters, because the token is a snapshot
 * version and the interesting answers only exist relative to one:
 *
 *   curl -s "…/consumer-groups/lag"                  > src/recorded/lag-full.json      # no token
 *   curl -s "…/consumer-groups/lag?since=<fresh>"    > src/recorded/lag-quiet.json     # nothing moved
 *   # produce a few records, wait for the snapshot to advance, then poll again
 *   curl -s "…/consumer-groups/lag?since=<fresh>"    > src/recorded/lag-delta.json     # one group moved
 *   curl -s "…/consumer-groups/lag?since=<stale>"    > src/recorded/lag-expired.json   # token not honoured
 */
async function baseline(): Promise<readonly GroupSummary[]> {
  const answer = await fetchGroups(client(groupsDocument), "quickstart");
  if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
  return answer.value.groups;
}

function withLag(
  rows: readonly GroupSummary[],
  lags: Readonly<Record<string, number | null>>,
): readonly GroupSummary[] {
  return rows.map((row) => (row.groupId in lags ? { ...row, totalLag: lags[row.groupId] ?? null } : row));
}

describe("the recorded lag delta", () => {
  it("maps the first answer of a session, which carries no measured pace", async () => {
    const answer = await fetchLagDelta(client(fullDocument), "quickstart");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);

    const delta = answer.value;
    // The schema does not mark `pace` nullable; the server sends `null` here, because with one
    // observation it has no interval to divide by and will not invent one.
    expect(delta.changed.map((update) => update.pace)).toEqual([null, null, null]);
    expect(delta.changed.find((update) => update.groupId === "order-fulfilment")?.totalLag).toBe(21);
    // Zero lag is a measurement. It has to survive the mapping as `0`.
    expect(delta.changed.find((update) => update.groupId === "analytics-indexer")?.totalLag).toBe(0);
    expect(delta.token).toBe("cXVpY2tzdGFydDox");
    expect(delta.nextPollMs).toBe(30_000);
    expect(delta.full).toBe(true);
  });

  it("maps an incremental answer that names one group and leaves two unsaid", async () => {
    const answer = await fetchLagDelta(client(deltaDocument), "quickstart", "cXVpY2tzdGFydDo2");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.full).toBe(false);
    expect(answer.value.changed.map((update) => update.groupId)).toEqual(["order-fulfilment"]);
    expect(answer.value.gone).toEqual([]);
    expect(answer.value.token).toBe("cXVpY2tzdGFydDo3");
  });

  it("leaves a group the answer did not mention exactly as it was", async () => {
    /*
     * The whole risk of an incremental protocol, in one assertion.
     *
     * `lag-delta.json` names only `order-fulfilment`. A merge written as "rebuild every row from the
     * update, defaulting what is missing" satisfies every type here and quietly rewrites the other
     * two groups' lag to `0` — a screen full of confident, wrong good news that is indistinguishable
     * from a healthy cluster. "Not mentioned" means unchanged, which is neither zero nor unknown.
     */
    const answer = await fetchLagDelta(client(deltaDocument), "quickstart", "cXVpY2tzdGFydDo2");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);

    // A real figure and an unreadable one, so both directions of the mistake are covered: a number
    // must not be zeroed, and a `null` must not become `0` either.
    const rows = withLag(await baseline(), { "analytics-indexer": 4_242, "payments-ledger-sync": null });
    const merged = applyLagDelta(rows, answer.value);
    if (merged.kind !== "merged") throw new Error(`expected merged, got ${merged.reason}`);

    const lagOf = (groupId: string): number | null =>
      merged.rows.find((row) => row.groupId === groupId)?.totalLag ?? null;
    expect(lagOf("analytics-indexer")).toBe(4_242);
    expect(merged.rows.find((row) => row.groupId === "payments-ledger-sync")?.totalLag).toBeNull();
    // The one the server did speak about moved, from the list's 9 to the delta's 21.
    expect(lagOf("order-fulfilment")).toBe(21);
    // And the row's other five columns, which a lag answer does not carry, are untouched.
    expect(merged.rows.find((row) => row.groupId === "order-fulfilment")?.topics).toBe(1);
    expect(merged.rows.find((row) => row.groupId === "order-fulfilment")?.coordinator).toBe("broker 1");
  });

  it("reads a quiet answer as nothing changed, not as no groups", async () => {
    // `changed: []` with `full: false` is the server saying the cluster is still. `changed: []` with
    // `full: true` would be a cluster with no groups at all. Reading the first as the second empties
    // a table that should not have moved.
    const answer = await fetchLagDelta(client(quietDocument), "quickstart", "cXVpY2tzdGFydDo3");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.full).toBe(false);

    const rows = await baseline();
    const merged = applyLagDelta(rows, answer.value);
    if (merged.kind !== "merged") throw new Error(`expected merged, got ${merged.reason}`);
    expect(merged.rows).toEqual(rows);
  });

  it("falls back to a full list when the token was not honoured", async () => {
    /*
     * `lag-expired.json` is a real answer to a token cut from a snapshot the server had discarded.
     * It restates all three groups with `full: true` — and it is still only a lag answer: no topic
     * count, no coordinator, no partial-read note. Merging it would be fine for the three figures it
     * carries and a guess for everything else, so the merge refuses and asks for the list instead.
     */
    const answer = await fetchLagDelta(client(expiredDocument), "quickstart", "cXVpY2tzdGFydDoy");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.full).toBe(true);

    const merged = applyLagDelta(await baseline(), answer.value);
    expect(merged.kind).toBe("needs-full-list");
  });
});

describe("applyLagDelta", () => {
  const quiet = { changed: [], gone: [], token: "t", nextPollMs: 30_000, full: false };

  it("turns a lag the server could no longer compute into a dash, not a zero", async () => {
    const rows = await baseline();
    const merged = applyLagDelta(rows, {
      ...quiet,
      // `totalLag` absent on an update is the server saying it could not work this one out. It is a
      // change — from 9 to unknown — and the row must stop claiming a number.
      changed: [{ groupId: "order-fulfilment", totalLag: null, state: "EMPTY", members: 0, pace: null }],
    });
    if (merged.kind !== "merged") throw new Error(`expected merged, got ${merged.reason}`);
    expect(merged.rows.find((row) => row.groupId === "order-fulfilment")?.totalLag).toBeNull();
  });

  it("removes a group the server says is gone rather than blanking its figures", async () => {
    const rows = await baseline();
    const merged = applyLagDelta(rows, { ...quiet, gone: ["payments-ledger-sync"] });
    if (merged.kind !== "merged") throw new Error(`expected merged, got ${merged.reason}`);
    expect(merged.rows.map((row) => row.groupId)).toEqual(["analytics-indexer", "order-fulfilment"]);
  });

  it("asks for the whole list when a group it has never seen turns up", async () => {
    // There is no honest row to build: a lag answer carries no topic count and no coordinator, and
    // printing `0` topics beside a dash for the coordinator states two things nobody reported.
    const rows = await baseline();
    const merged = applyLagDelta(rows, {
      ...quiet,
      changed: [{ groupId: "brand-new", totalLag: 5, state: "STABLE", members: 2, pace: null }],
    });
    expect(merged.kind).toBe("needs-full-list");
  });
});

describe("pollLag", () => {
  /**
   * A client that answers the lag endpoint from a script and the group list from the recorded
   * document, and records what it was asked for.
   *
   * The `since` it was given on each call is the thing worth capturing: an incremental protocol that
   * forgets to carry the token forward still works — it just quietly costs a full answer every time,
   * which is the whole saving gone and nothing on screen to show for it.
   */
  function scripted(answers: readonly unknown[]): {
    readonly api: KuiApiClient;
    readonly since: string[];
    readonly listCalls: () => number;
  } {
    const since: string[] = [];
    let lag = 0;
    let lists = 0;
    const get = vi.fn(async (path: string, init?: { params?: { query?: { since?: string } } }) => {
      if (path.endsWith("/lag")) {
        since.push(init?.params?.query?.since ?? "");
        return { ok: true, value: answers[Math.min(lag++, answers.length - 1)] };
      }
      lists += 1;
      return { ok: true, value: groupsDocument };
    });
    return {
      api: { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient,
      since,
      listCalls: () => lists,
    };
  }

  it("seeds a token, carries it forward, and merges without asking for the list again", async () => {
    vi.useFakeTimers();
    try {
      const { api, since, listCalls } = scripted([fullDocument, deltaDocument, quietDocument]);
      let rows = await baseline();
      const stop = pollLag(api, "quickstart", () => rows, (next) => {
        rows = next as GroupSummary[];
      });

      // The seeding call: no token, and its figures are thrown away.
      await vi.advanceTimersByTimeAsync(0);
      expect(since).toEqual([""]);

      await vi.advanceTimersByTimeAsync(30_000);
      await vi.advanceTimersByTimeAsync(30_000);
      // Every poll after the first quotes the token the previous answer issued.
      expect(since).toEqual(["", "cXVpY2tzdGFydDox", "cXVpY2tzdGFydDo3"]);
      // And the list was never fetched again: three lag answers, no describe-every-group.
      expect(listCalls()).toBe(0);
      expect(rows.find((row) => row.groupId === "order-fulfilment")?.totalLag).toBe(21);

      stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it("pays for the whole list once, when the server stops honouring the token", async () => {
    vi.useFakeTimers();
    try {
      const { api, listCalls } = scripted([fullDocument, expiredDocument, quietDocument]);
      let rows = await baseline();
      const stop = pollLag(api, "quickstart", () => rows, (next) => {
        rows = next as GroupSummary[];
      });

      await vi.advanceTimersByTimeAsync(0);
      expect(listCalls()).toBe(0);
      await vi.advanceTimersByTimeAsync(30_000);
      expect(listCalls()).toBe(1);
      await vi.advanceTimersByTimeAsync(30_000);
      // The next answer was incremental again, so the expensive call does not repeat.
      expect(listCalls()).toBe(1);

      stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it("stops asking once the screen is gone", async () => {
    /*
     * A timer left running after unmount keeps a cluster the operator has navigated away from under
     * poll forever, and holds the component graph that closed over it alive with it. The route
     * returns this function as its effect's cleanup precisely so that it runs on unmount.
     */
    vi.useFakeTimers();
    try {
      const { api, since } = scripted([fullDocument, deltaDocument, quietDocument]);
      let rows = await baseline();
      const stop = pollLag(api, "quickstart", () => rows, (next) => {
        rows = next as GroupSummary[];
      });

      await vi.advanceTimersByTimeAsync(0);
      expect(since).toHaveLength(1);
      stop();

      await vi.advanceTimersByTimeAsync(10 * 60_000);
      expect(since).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
