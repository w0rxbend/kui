import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import clustersDocument from "./recorded/clusters.json" with { type: "json" };
import brokersDocument from "./recorded/brokers.json" with { type: "json" };
import { fetchBrokers, fetchClusters } from "./data.js";

/**
 * The mapping, against documents a real server actually produced.
 *
 * ## Why recorded documents and not hand-written fixtures
 *
 * `data.test.ts` beside this one is written from hand-made payloads, and it passed against a
 * mapping that was wrong in almost every field. The generated types cannot catch it: the server
 * documents each section with `Schema.any`, so every payload is `unknown` on the browser's side and
 * a misspelled field is a type-correct `undefined`.
 *
 * And the failure is silent by construction. Every figure has a defined reading for "absent" — a
 * dash, `null`, "not measured" — because that is the rule the whole product is built on. So a
 * mapping that reads `leaderPartitions` from a payload with `leaderCount` does not throw and does
 * not warn: it renders a broker card of em dashes, which reads as *this broker did not answer*. The
 * screen looks like a cluster in trouble, and the cluster is fine.
 *
 * These two documents were captured from the quickstart stack —
 * `docker compose -f deployment/quickstart/docker-compose.quickstart.yml up --build`, then
 * `curl localhost:8080/api/v1/clusters` — and they are what caught it.
 *
 * ## Re-recording them
 *
 * Bring the quickstart up and overwrite both files:
 *
 *   curl -s localhost:8080/api/v1/clusters | python3 -m json.tool > src/recorded/clusters.json
 *   curl -s localhost:8080/api/v1/clusters/quickstart/brokers | python3 -m json.tool > src/recorded/brokers.json
 *
 * A diff in these files is a contract change, and it should be read as one.
 */

function client(document: unknown): KuiApiClient {
  const get = vi.fn(async () => ({ ok: true, value: document }));
  return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
}

describe("the recorded cluster list", () => {
  it("maps every figure the document actually carries", async () => {
    const answer = await fetchClusters(client(clustersDocument));
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    const [cluster] = answer.value;
    expect(cluster).toBeDefined();
    if (cluster === undefined) return;

    // The row is nested under `cluster`; the entry around it also carries `capability`, `topics`
    // and `consumerGroups`, each its own section. Reading the entry as though it were the row is
    // the first mistake this document caught: every field came out undefined.
    expect(cluster.id).toBe("quickstart");
    expect(cluster.name).toBe("Quickstart (local)");
    expect(cluster.readOnly).toBe(false);

    // From `cluster.summary`.
    expect(cluster.version).toBe("4.3");
    expect(cluster.brokersOnline).toBe(1);
    expect(cluster.health).toBe("healthy");
    expect(cluster.observedAt).toBeInstanceOf(Date);

    // From the entry's own `topics` section — not from the scrape, whose `onlinePartitionCount` is
    // null on this cluster. Reading it from the scrape would put a dash on screen with the real
    // number a few bytes away in the same response.
    expect(cluster.topics).toBe(10);
    expect(cluster.partitions).toBe(86);
  });

  it("does not silently produce a row of nulls", async () => {
    // The assertion that would have failed against the original mapping. Stated separately from the
    // one above because it is the *property*: a document that decoded to nothing at all still
    // satisfies every "is it null-safe" test, and looks on screen like a cluster in trouble.
    const answer = await fetchClusters(client(clustersDocument));
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    const cluster = answer.value[0];
    const figures = [cluster?.brokersOnline, cluster?.topics, cluster?.partitions];
    expect(figures.every((figure) => figure === null)).toBe(false);
  });
});

describe("the recorded broker list", () => {
  it("maps every figure the document actually carries", async () => {
    const answer = await fetchBrokers(client(brokersDocument), "quickstart");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    const [broker] = answer.value;
    expect(broker).toBeDefined();
    if (broker === undefined) return;

    expect(broker.id).toBe(1);
    expect(broker.host).toBe("kafka");
    expect(broker.port).toBe(9092);
    expect(broker.isController).toBe(true);
    expect(broker.health).toBe("healthy");

    // `replicaCount` on the wire, not `replicaPartitions`. This is the assertion that fails if
    // somebody "tidies" the field names back to the plausible ones.
    expect(broker.replicaPartitions).toBe(86);
    // `diskUsageBytes`, not `diskUsedBytes`.
    expect(broker.diskUsedBytes).toBe(58949);

    // Genuinely absent on this cluster, and absent for two different reasons: `leaderCount` is null
    // on a single-broker cluster, and the endpoint carries no disk total or per-broker out-of-sync
    // count at all. All three must be `null` — a `0` would be a claim nobody made.
    expect(broker.leaderPartitions).toBeNull();
    expect(broker.diskTotalBytes).toBeNull();
    expect(broker.outOfSyncReplicas).toBeNull();
  });
});
