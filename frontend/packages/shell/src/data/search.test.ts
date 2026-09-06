/**
 * The cross-entity search, as a fold over the gateway's answer.
 *
 * Nothing here renders. What the field *draws* for each of its four states is checked in
 * `chrome/chrome.test.tsx` against a DOM; what is decided here is which state it is in, which rows
 * it holds and what it says about a service that was never asked — and every one of those is a
 * function of plain data, which is the reason they are in this file rather than in a component.
 *
 * The shape being read is the one both sides of the W3-06/W3-08 boundary code against, stated in
 * the wave plan rather than taken from either packet's diff.
 */
import { describe, expect, it } from "vitest";
import type { KuiApiClient } from "@kui/api";

import {
  NO_RESULTS,
  SEARCH_LIMIT,
  decodeSearch,
  fetchSearch,
  searchGroups,
  searchHitCount,
  searchStatus,
  unavailableServices,
  type SearchLinks,
} from "./search.js";

const ANSWER = {
  results: {
    topics: [{ cluster: "prod", name: "orders.payments.v2" }],
    groups: [{ cluster: "prod", groupId: "payments-processor" }],
    subjects: [{ cluster: "prod", subject: "orders-value" }],
  },
  partial: [],
};

const LINKS: SearchLinks = {
  topic: (cluster, name) => `/ui/clusters/${cluster}/topics/${name}`,
  group: (cluster, groupId) => `/ui/clusters/${cluster}/consumer-groups/${groupId}`,
  subjects: (cluster) => `/ui/clusters/${cluster}/schemas`,
};

describe("reading the gateway's answer", () => {
  it("takes each list's name from the field that service calls its own", () => {
    /* `name`, `groupId`, `subject` — three identifiers, because each is the one its own service
       uses. Flattening them at the gateway would have made the wire say less than the services do,
       so the flattening happens here, once. */
    const answer = decodeSearch(ANSWER);
    expect(answer.topics).toEqual([{ cluster: "prod", name: "orders.payments.v2" }]);
    expect(answer.groups).toEqual([{ cluster: "prod", name: "payments-processor" }]);
    expect(answer.subjects).toEqual([{ cluster: "prod", name: "orders-value" }]);
  });

  it("reads a body it has never seen as no results rather than throwing", () => {
    // Every list is optional and so is `results` itself: a gateway that routes no schema service
    // omits `subjects` entirely, which is a normal answer and not a malformed one.
    for (const body of [undefined, null, 7, "no", {}, { results: {} }, { results: null }]) {
      expect(decodeSearch(body)).toEqual(NO_RESULTS);
    }
  });

  it("drops a row that is missing either half rather than drawing a blank one", () => {
    const answer = decodeSearch({
      results: {
        topics: [
          { cluster: "prod" },
          { name: "orders.v1" },
          { cluster: "prod", name: "" },
          { cluster: "prod", name: "orders.v2" },
        ],
      },
    });
    expect(answer.topics).toEqual([{ cluster: "prod", name: "orders.v2" }]);
  });

  it("keeps the ids of the services that could not be asked", () => {
    expect(decodeSearch({ ...ANSWER, partial: ["schema", 7, "topic"] }).partial).toEqual([
      "schema",
      "topic",
    ]);
  });
});

describe("what the field is showing", () => {
  it("is empty only when everybody was asked and nobody matched", () => {
    expect(searchStatus({ kind: "ready", answer: NO_RESULTS })).toBe("empty");
  });

  it("is not empty when a service was never asked, however few rows came back", () => {
    /* "Nothing matches" over a search that never reached the registry is a false negative, and a
       false negative in a search box is indistinguishable from a true one. */
    const answer = { ...NO_RESULTS, partial: ["schema"] };
    expect(searchStatus({ kind: "ready", answer })).toBe("ready");
  });

  it("reports the other three states as themselves", () => {
    expect(searchStatus({ kind: "idle" })).toBe("idle");
    expect(searchStatus({ kind: "searching" })).toBe("searching");
    expect(searchStatus({ kind: "failed", reason: "no" })).toBe("failed");
  });

  it("names a service in words, and keeps an id it has never heard of", () => {
    // "schema" under a search box reads as a noun the operator was looking for rather than as the
    // name of a service that was not asked.
    expect(unavailableServices({ ...NO_RESULTS, partial: ["schema", "ksql"] })).toEqual([
      "Schema Registry",
      "ksql",
    ]);
  });
});

describe("the rows the overlay draws", () => {
  it("builds each address through the links it was handed, and names the cluster", () => {
    const groups = searchGroups(decodeSearch(ANSWER), LINKS);
    expect(groups.map((group) => group.heading)).toEqual(["TOPICS", "CONSUMER GROUPS", "SUBJECTS"]);
    expect(groups[0]?.items[0]?.href).toBe("/ui/clusters/prod/topics/orders.payments.v2");
    expect(groups[1]?.items[0]?.href).toBe("/ui/clusters/prod/consumer-groups/payments-processor");
    expect(groups[2]?.items[0]?.href).toBe("/ui/clusters/prod/schemas");
    /* The search is across clusters, so two rows can carry one topic name and differ only in which
       cluster they are on. A reader who cannot see which is which will open the wrong one. */
    expect(groups[0]?.items[0]?.detail).toBe("prod");
  });

  it("leaves out a kind that found nothing rather than drawing an empty heading", () => {
    const only = decodeSearch({ results: { topics: ANSWER.results.topics } });
    const groups = searchGroups(only, LINKS);
    expect(groups.map((group) => group.heading)).toEqual(["TOPICS"]);
    expect(searchHitCount(decodeSearch(ANSWER))).toBe(3);
  });
});

describe("the request", () => {
  /** A client that records what it was asked for and answers with a fixed body. */
  function recording(answer: unknown) {
    const asked: { path: string; init: unknown }[] = [];
    const api = {
      get: (path: string, init: unknown) => {
        asked.push({ path, init });
        return Promise.resolve({ ok: true, value: answer });
      },
    } as unknown as KuiApiClient;
    return { api, asked };
  }

  it("asks the one endpoint, with the query and the bound the contract states", async () => {
    const { api, asked } = recording(ANSWER);
    const result = await fetchSearch(api, "orders");

    expect(asked).toHaveLength(1);
    expect(asked[0]?.path).toBe("/api/v1/search");
    expect(asked[0]?.init).toEqual({ params: { query: { q: "orders", limit: SEARCH_LIMIT } } });
    expect(result.ok && searchHitCount(result.value)).toBe(3);
  });

  it("passes a failure through rather than turning it into an empty answer", async () => {
    /* An empty answer and a failed request are two different pictures: one says the cluster holds
       no such topic, the other says nobody was asked. Collapsing them is the defect the whole
       `partial` mechanism exists to prevent, one level up. */
    const api = {
      get: () => Promise.resolve({ ok: false, error: { kind: "unreachable", cause: "down" } }),
    } as unknown as KuiApiClient;
    const result = await fetchSearch(api, "orders");
    expect(result.ok).toBe(false);
  });
});
