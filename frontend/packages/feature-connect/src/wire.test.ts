/**
 * The wire: what this build reads out of a connectors document, and what it refuses to read.
 *
 * The documents these cases run on are **the service's own**, copied from
 * `services/connect/contract/test/resources/golden/` — a story or a case built from a literal is a
 * drawing of what its author believed the server sends, which is how a screen comes to be reviewed,
 * approved and wrong. `wire.golden.test.ts` reads the originals off disk, so the copies here cannot
 * drift without something going red.
 *
 * The cases that matter most are the refusals. A decoder that answers an empty list for a document
 * it did not understand is the defect that cost this project a milestone — wave 5's producers card
 * drew *"the metrics source answered and named no producers"* over a source that had named five,
 * with every gate green, because a `?? []` turned a wrong field name into a fact about the cluster.
 * This file's own first draft made that mistake: it read `data.items` where the service renders
 * `data.workers[].connectors.data.items`.
 */
import { describe, expect, it } from "vitest";

import {
  connectorState,
  decodeConnectorListing,
  decodeWorkerSection,
  CONNECTORS_SECTION_KEY,
  REBALANCING_REASON_CODE,
  Unreadable,
  type ConnectorListing,
} from "./wire.js";
import { allConnectors, allNotDescribed } from "./model.js";
import { fetchConnectors } from "./data.js";
import { serving } from "./testing.js";
import responseDocument from "./documents/connectors-response.json" with { type: "json" };
import partialDocument from "./documents/connectors-partial.json" with { type: "json" };
import emptyDocument from "./documents/connectors-empty.json" with { type: "json" };
import unknownShapeDocument from "./documents/connectors-unknown-shape.json" with { type: "json" };
import notConfiguredDocument from "./documents/connectors-not-configured.json" with {
  type: "json",
};

/** The outer `Section`'s payload, the way the fetcher reaches it. */
function payload(document: unknown): unknown {
  const section = (document as Record<string, Record<string, unknown>>)[CONNECTORS_SECTION_KEY];
  return section?.["data"];
}

function listing(document: unknown): ConnectorListing {
  const decoded = decodeConnectorListing(payload(document));
  if (decoded === Unreadable) throw new Error("this document should decode");
  return decoded;
}

describe("decoding a connectors document", () => {
  it("reads a section per Connect cluster, in configuration order", () => {
    /*
     * The shape of the whole response, and the thing this file's first draft got wrong. One worker
     * being down must cost one row rather than the screen, which is only possible because each
     * carries its own section.
     */
    const held = listing(responseDocument);
    expect(held.workers.map((worker) => worker.connect)).toEqual(["payments", "analytics"]);
    expect(held.workers[0]?.page.kind).toBe("ok");
    expect(held.workers[1]?.page.kind).toBe("rebalancing");
  });

  it("reads three connectors and every task's state", () => {
    const connectors = allConnectors(listing(responseDocument));
    expect(connectors.map((one) => `${one.connect}/${one.name}`)).toEqual([
      "payments/archive-sink",
      "payments/elastic-sink",
      "payments/orders-source",
    ]);
    expect(connectors[0]?.tasks.map((task) => task.state)).toEqual(["PAUSED"]);
    expect(connectors[1]?.tasks.map((task) => task.state)).toEqual(["RUNNING", "FAILED"]);
    expect(connectors[2]?.tasks.map((task) => task.state)).toEqual([
      "RUNNING",
      "RUNNING",
      "RUNNING",
    ]);
  });

  it("reads `failed` from the wire and never from the connector's state word", () => {
    /*
     * `elastic-sink` is `state: "RUNNING"` and `failed: true`, because one of its tasks failed. A
     * browser that derived the flag from the state word would call that connector healthy — and it
     * is the connector on this cluster that somebody has to go and fix.
     */
    const held = allConnectors(listing(responseDocument));
    const elastic = held.find((one) => one.name === "elastic-sink");
    expect(elastic?.state).toBe("RUNNING");
    expect(elastic?.failed).toBe(true);
  });

  it("reads the service's two task figures rather than counting the tasks itself", () => {
    // `runningTasks` is the domain's count and the domain is the one that says RESTARTING is not
    // running. Three browsers deriving it are three chances to derive it differently.
    const held = allConnectors(listing(responseDocument));
    const orders = held.find((one) => one.name === "orders-source");
    expect(orders?.runningTasks).toBe(3);
    expect(orders?.taskCount).toBe(3);

    const elastic = held.find((one) => one.name === "elastic-sink");
    expect(elastic?.runningTasks).toBe(1);
    expect(elastic?.taskCount).toBe(2);
  });

  it("carries the worker's own reason and its whole trace", () => {
    const failedTask = allConnectors(listing(responseDocument))
      .find((one) => one.name === "elastic-sink")
      ?.tasks.find((task) => task.state === "FAILED");

    expect(failedTask?.reason).toBe(
      "org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200",
    );
    expect(failedTask?.trace).toContain("WorkerSinkTask.deliverMessages");
  });

  it("keeps a connector the worker would not describe, by name", () => {
    /*
     * `unreadable` exists so this row can be drawn at all. A connector missing from a list is
     * indistinguishable from a connector that was deleted, and one wedged worker in a Connect
     * cluster loses the status of its share while the rest answer normally.
     */
    expect(allNotDescribed(listing(partialDocument))).toEqual([
      { connect: "payments", name: "elastic-sink" },
    ]);
    expect(allNotDescribed(listing(responseDocument))).toEqual([]);
  });

  it("folds a state word this build has never seen to UNKNOWN and never to RUNNING", () => {
    // Connect has added states across releases — STOPPED arrived in Kafka 3.5 — and guessing
    // RUNNING would be KUI telling somebody their pipeline is fine on no evidence.
    expect(connectorState("STOPPED")).toBe("UNKNOWN");
    expect(connectorState(undefined)).toBe("UNKNOWN");
    expect(connectorState("running")).toBe("RUNNING");
    expect(connectorState("RESTARTING")).toBe("RESTARTING");
  });

  it("refuses a document whose worker list is under a name this build does not know", () => {
    expect(decodeConnectorListing(payload(unknownShapeDocument))).toBe(Unreadable);
    expect(decodeConnectorListing(undefined)).toBe(Unreadable);
    expect(decodeConnectorListing({ items: [] })).toBe(Unreadable);
  });
});

describe("one Connect cluster's section", () => {
  it("reads a rebalance as transient and keeps the worker's own sentence", () => {
    /*
     * The browser half of a rule with three halves. `ConnectMapping.section` maps
     * `ErrorCode.ConnectRebalancing` to `unavailable` with `ReasonCode.Starting`; a browser that
     * drew that as a failure would contradict `ConnectHttp.errorFrom` and
     * `ConnectCapabilities.probe` and put a red panel in front of an operator over a state that
     * clears itself in seconds.
     */
    const answer = decodeWorkerSection({
      status: "unavailable",
      reason: REBALANCING_REASON_CODE,
      message: "the Kafka Connect cluster 'analytics' is rebalancing and cannot answer yet",
      since: "2026-09-03T10:11:12.000Z",
    });
    expect(answer.kind).toBe("rebalancing");
    if (answer.kind !== "rebalancing") return;
    expect(answer.message).toContain("rebalancing");
  });

  it("reads any other unavailable reason as a failure that names its code", () => {
    const answer = decodeWorkerSection({
      status: "unavailable",
      reason: "UPSTREAM_UNAVAILABLE",
      message: "the Connect cluster did not answer within 10 seconds",
    });
    expect(answer.kind).toBe("failed");
    if (answer.kind !== "failed") return;
    expect(answer.code).toBe("UPSTREAM_UNAVAILABLE");
  });

  it("refuses an ok section carrying no item list, rather than answering an empty page", () => {
    const answer = decodeWorkerSection({ status: "ok", data: { entries: [] } });
    expect(answer.kind).toBe("unreadable");
  });

  it("treats a missing `unreadable` list as empty and not as an unreadable section", () => {
    // A document from an older build that omitted the field is still a readable list of connectors.
    const answer = decodeWorkerSection({ status: "ok", data: { items: [] } });
    expect(answer.kind).toBe("ok");
    if (answer.kind !== "ok") return;
    expect(answer.page.unreadable).toEqual([]);
  });
});

describe("reading the connectors endpoint", () => {
  it("asks the path the connect service publishes, for this cluster", async () => {
    const client = serving(responseDocument);
    await fetchConnectors(client.api, "quickstart");

    /*
     * The address written out as a literal rather than as `CONNECTORS_PATH`. The stub answers
     * whatever path the product passes, so comparing the request against the same constant the
     * product built it from would assert that a constant equals itself.
     * `ConnectEndpoints.connectors` is `clusters / {clusterId} / connect / connectors`, under the
     * gateway's `/api/v1`.
     */
    expect(client.calls).toEqual([
      {
        method: "get",
        path: "/api/v1/clusters/{clusterId}/connect/connectors",
        params: { clusterId: "quickstart" },
      },
    ]);
  });

  it("answers ready with both Connect clusters the document names", async () => {
    const state = await fetchConnectors(serving(responseDocument).api, "quickstart");
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;
    expect(state.value.workers).toHaveLength(2);
    expect(allConnectors(state.value)).toHaveLength(3);
  });

  it("answers not-configured for a cluster with no Connect block at all", async () => {
    // A 200 carrying a `not_configured` outer section, which is the ordinary case rather than a
    // fault: a deployment that never intended to run Kafka Connect is not a broken one.
    const state = await fetchConnectors(serving(notConfiguredDocument).api, "quickstart");
    expect(state.kind).toBe("not-configured");
  });

  it("answers an empty list only when the workers sent one", async () => {
    const state = await fetchConnectors(serving(emptyDocument).api, "quickstart");
    expect(state.kind).toBe("ready");
    if (state.kind !== "ready") return;
    expect(allConnectors(state.value)).toEqual([]);
  });

  it("reports a document it cannot read as unread, and never as an empty list", async () => {
    const state = await fetchConnectors(serving(unknownShapeDocument).api, "quickstart");
    expect(state.kind).toBe("failed");
    if (state.kind !== "failed") return;
    expect(state.code).toBe("UNREADABLE_BODY");
    expect(state.message).toContain("did not recognise");
  });

  it("reports an answer with no connectors section as unread", async () => {
    const state = await fetchConnectors(serving({ something: "else" }).api, "quickstart");
    expect(state.kind).toBe("failed");
    if (state.kind !== "failed") return;
    expect(state.message).toContain("carried no connectors section");
  });
});
