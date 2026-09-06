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

    const { groups, coordinatorsMissing, page } = answer.value;
    expect(groups.length).toBeGreaterThan(0);
    expect(coordinatorsMissing).toBe(0);
    // The server's own page block, read rather than derived. `totalItems` is the figure the voice
    // line prints; on this recording it agrees with the row count, and on a cluster with more
    // groups than a page holds it must not.
    expect(page.totalItems).toBe(3);
    expect(page.pageSize).toBe(25);

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
    // `host:port`, from the two fields the wire carries together. Not `broker 1`, which is a
    // number dressed as an address and is nowhere an operator can point a tool.
    expect(indexer.coordinator).toBe("kafka:9092");
  });

  it("leaves the coordinator absent when the wire carried an id and no address", async () => {
    /*
     * Derived from the recording rather than written: the quickstart's coordinator always answers,
     * so the refusal path cannot be recorded from it. What is taken from the document is every
     * other field and the document's shape; what is removed is exactly the two fields under test.
     *
     * The old mapping filled this in as `broker 1` from `coordinatorId`, which is a broker id
     * printed where an address goes — nowhere to point a tool, and on screen indistinguishable
     * from a coordinator that answered.
     */
    const stripped = JSON.parse(JSON.stringify(groupsDocument)) as {
      groups: { data: { items: Record<string, unknown>[] } };
    };
    for (const item of stripped.groups.data.items) {
      delete item["coordinatorHost"];
      delete item["coordinatorPort"];
    }
    expect(stripped.groups.data.items[0]?.["coordinatorId"]).toBe(1);

    const answer = await fetchGroups(client(stripped), "quickstart");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.groups.every((group) => group.coordinator === null)).toBe(true);
  });

  it("falls back to the request's own page when the server sent no page block", async () => {
    // `page` and `pageSize` describe the request, so echoing them back is honest. `totalItems` has
    // no such fallback — nothing in the answer knows the cluster's figure — so it stays `null` and
    // the screen says so in words rather than publishing this page's length.
    const withoutPage = { groups: { status: "ok", data: { items: [] } }, incompleteCoordinators: 0 };
    const answer = await fetchGroups(client(withoutPage), "quickstart", { page: 4, pageSize: 8 });
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.page).toEqual({ page: 4, pageSize: 8, totalItems: null });
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
    expect(group.coordinator).toBe("kafka:9092");

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
      expect(member?.host).toBe("172.18.0.4");
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

  it("leaves the coordinator absent when the detail carried an id and no address", async () => {
    /*
     * The list mapping's twin, and it needed its own case for a reason worth writing down: the
     * assertion above reads `coordinator === "kafka:9092"` off a recording that carries *both* the
     * address fields and `coordinatorId`. Restoring the old `` `broker ${coordinatorId}` ``
     * fallback in the detail mapping changed nothing anybody could see, and the rule that the two
     * mappings share — a broker id is not an address — held on one of them only.
     *
     * The quickstart's coordinator always answers, so the refusal cannot be recorded from it. What
     * is taken from the document is every other field and its shape; what is removed is exactly the
     * two fields under test, leaving the id behind for the fallback to reach for.
     */
    const stripped = JSON.parse(JSON.stringify(groupDocument)) as Record<string, unknown>;
    delete stripped["coordinatorHost"];
    delete stripped["coordinatorPort"];
    expect(stripped["coordinatorId"]).toBe(1);

    const answer = await fetchGroup(client(stripped), "quickstart", "analytics-indexer");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.coordinator).toBeNull();
    // Stated as the thing that must not be on screen: a broker id dressed as an address is nowhere
    // an operator can point a tool, and on the page it looks exactly like a coordinator that spoke.
    // `String(...)` because the honest answer here is `null`; the point of the second assertion is
    // that the failure message names what was drawn instead when the fallback comes back.
    expect(String(answer.value.coordinator)).not.toMatch(/broker/);

    // And the rest of the mapping still worked, so this is a missing coordinator rather than a
    // document the mapping failed to read at all — which would satisfy the two lines above too.
    expect(answer.value.groupId).toBe("analytics-indexer");
    expect(answer.value.offsets.length).toBe(12);
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
    expect(merged.rows.find((row) => row.groupId === "order-fulfilment")?.coordinator).toBe("kafka:9092");
  });

  it("maps an update that carries no lag as unknown, not as caught up", async () => {
    /*
     * The most expensive `null` in `lag.ts`, asserted where the mapping applies it.
     *
     * There was a case for this rule and it went through `applyLagDelta` with an update written by
     * hand in this file — so the rule it proved was the test's own arithmetic, and `toUpdate` could
     * be changed to `payload.totalLag ?? 0` with all 68 of this package's tests green. A group
     * whose lag the server could not compute would read as a group that had caught up: the two
     * opposite statements this whole screen turns on telling apart.
     *
     * Derived from the recording rather than written: the quickstart's coordinators always answer,
     * so this state cannot be recorded from it. Every field but the one under test is the server's.
     */
    const stripped = JSON.parse(JSON.stringify(deltaDocument)) as {
      changed: Record<string, unknown>[];
    };
    delete stripped.changed[0]?.["totalLag"];
    expect(stripped.changed[0]?.["groupId"]).toBe("order-fulfilment");

    const answer = await fetchLagDelta(client(stripped), "quickstart", "cXVpY2tzdGFydDo2");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.changed[0]?.totalLag).toBeNull();

    // And through the merge onto the row, which is what the column draws: the row must stop
    // claiming the 9 it had, and must not claim a 0 either.
    const merged = applyLagDelta(await baseline(), answer.value);
    if (merged.kind !== "merged") throw new Error(`expected merged, got ${merged.reason}`);
    expect(merged.rows.find((row) => row.groupId === "order-fulfilment")?.totalLag).toBeNull();
  });

  it("treats an answer that did not say whether it is complete as a full one", async () => {
    /*
     * `full` decides whether a delta may be merged at all, and the safe reading of "the server did
     * not say" is "do not merge" — a lag answer carries no topic count, no coordinator and no
     * partial-read note, so merging one that restates the cluster would guess five columns.
     *
     * Every recorded document carries the field, so the absent case cannot come off the wire. It is
     * produced by removing it, which is exactly what a truncated body or an older server sends.
     */
    const withoutFull = JSON.parse(JSON.stringify(deltaDocument)) as Record<string, unknown>;
    delete withoutFull["full"];

    const answer = await fetchLagDelta(client(withoutFull), "quickstart", "cXVpY2tzdGFydDo2");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.full).toBe(true);
    expect(applyLagDelta(await baseline(), answer.value).kind).toBe("needs-full-list");
  });

  it("carries the state and the member count of a group the answer did speak about", async () => {
    /*
     * The merge updates three fields, and only one of them had anything watching it. Dropping
     * `state` or `members` from the write left the suite green — and on screen that is a row whose
     * lag moves every thirty seconds beside a chip that still says `Stable` for a group the
     * coordinator has already reported empty, which is worse than a stale row because half of it is
     * visibly live.
     *
     * The rows are seeded away from the delta's own figures on purpose: the recording's
     * `order-fulfilment` is already `EMPTY` with no members in the list, so a merge that wrote
     * neither field would agree with the answer by coincidence.
     */
    const rows = (await baseline()).map((row) =>
      row.groupId === "order-fulfilment"
        ? { ...row, state: "STABLE" as const, members: 4 }
        : row,
    );
    const answer = await fetchLagDelta(client(deltaDocument), "quickstart", "cXVpY2tzdGFydDo2");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);

    const merged = applyLagDelta(rows, answer.value);
    if (merged.kind !== "merged") throw new Error(`expected merged, got ${merged.reason}`);
    const moved = merged.rows.find((row) => row.groupId === "order-fulfilment");
    expect(moved?.state).toBe("EMPTY");
    expect(moved?.members).toBe(0);
    // And a group the answer did not mention keeps both of its own.
    expect(merged.rows.find((row) => row.groupId === "analytics-indexer")?.state).toBe("STABLE");
    expect(merged.rows.find((row) => row.groupId === "analytics-indexer")?.members).toBe(1);
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
    /** The `group` parameters each lag request carried, in order. `[]` for an unscoped request. */
    readonly scopes: readonly string[][];
    readonly listCalls: () => number;
  } {
    const since: string[] = [];
    const scopes: string[][] = [];
    let lag = 0;
    let lists = 0;
    const get = vi.fn(
      async (
        path: string,
        init?: { params?: { query?: { since?: string; group?: readonly string[] } } },
      ) => {
        if (path.endsWith("/lag")) {
          since.push(init?.params?.query?.since ?? "");
          scopes.push([...(init?.params?.query?.group ?? [])]);
          return { ok: true, value: answers[Math.min(lag++, answers.length - 1)] };
        }
        lists += 1;
        return { ok: true, value: groupsDocument };
      },
    );
    return {
      api: { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient,
      since,
      scopes,
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

  it("asks only about the groups on the page, so paging does not defeat the protocol", async () => {
    /*
     * The rule CG-006's whole saving rests on, and the one nothing in this repository could see.
     *
     * The endpoint answers cluster-wide when `group` is absent, and this list draws one page. Asked
     * unscoped from page 1 of a cluster with more groups than fit, every poll comes back naming
     * groups that are not in `rows` — and `applyLagDelta` refuses those, so every poll took the
     * `needs-full-list` branch and paid for a whole group list. No figure was wrong; the entire
     * optimisation was off, silently, on exactly the clusters it was written for.
     *
     * Asserted at the request, because that is where the scoping either happens or does not: the
     * merge downstream behaves identically either way, which is why this was invisible.
     */
    vi.useFakeTimers();
    try {
      const { api, scopes } = scripted([fullDocument, deltaDocument, quietDocument]);
      let rows = await baseline();
      const stop = pollLag(api, "quickstart", () => rows, (next) => {
        rows = next as GroupSummary[];
      });

      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(30_000);

      // Every request, the seeding one included: the page's own group ids and nothing else.
      const onThePage = rows.map((row) => row.groupId);
      expect(onThePage.length).toBeGreaterThan(0);
      for (const scope of scopes) expect(scope).toEqual(onThePage);

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
