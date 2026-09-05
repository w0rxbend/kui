import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import clustersDocument from "./recorded/clusters.json" with { type: "json" };
import brokersDocument from "./recorded/brokers.json" with { type: "json" };
import brokerConfigsDocument from "./recorded/brokerConfigs.json" with { type: "json" };
import logDirsDocument from "./recorded/brokerLogDirs.json" with { type: "json" };
import {
  fetchBrokerConfigs,
  fetchBrokerLogDirs,
  fetchBrokers,
  fetchClusters,
} from "./data.js";

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

describe("the recorded broker configuration", () => {
  /*
   * Sixty kilobytes of one broker's `describeConfigs`, kept whole and kept compact.
   *
   * Whole, because the point of the document is that it is what a server sent — a trimmed one is a
   * fixture with a curl command in its history, and it cannot catch a field this mapping reads that
   * only appears on the three hundredth row. Compact, because pretty-printing quadruples it for no
   * reader: nobody reads this file, they diff it, and a diff of a contract change shows up either
   * way.
   *
   * Re-record it from the demonstration stack, which unlike the quickstart has a cluster with
   * brokers in it:
   *
   *   curl -s localhost:18080/api/v1/clusters/development/brokers/1/configs > src/recorded/brokerConfigs.json
   */
  it("reads the wire's own spellings, not the topic endpoint's", async () => {
    const answer = await fetchBrokerConfigs(client(brokerConfigsDocument), "development", 1);
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    // The whole document, not a page of it. A mapping that dropped rows would still pass every
    // assertion below.
    expect(answer.value.length).toBe(340);

    const listeners = answer.value.find((entry) => entry.name === "advertised.listeners");
    expect(listeners?.value).toBe("INTERNAL://kafka-dev:9092,EXTERNAL://localhost:19092");
    // `static-broker`, and the screen's word for it. Not `STATIC_BROKER_CONFIG`, which is Kafka's
    // own spelling and is not what this gateway sends.
    expect(listeners?.source).toBe("STATIC");
    expect(listeners?.overridden).toBe(true);
    expect(listeners?.sensitive).toBe(false);

    const background = answer.value.find((entry) => entry.name === "background.threads");
    expect(background?.source).toBe("DEFAULT");
    // There is no `isDefault` field on the wire, although the server's domain entity has one, so
    // this is derived from the source. If a future response grows the field, prefer it — but until
    // then reading `payload.isDefault` gives `undefined`, which is falsy, which marks every setting
    // on the broker as untouched and empties the one column this page is for.
    expect(background?.overridden).toBe(false);
  });

  it("keeps a sensitive setting out of the value column without calling it missing", async () => {
    const answer = await fetchBrokerConfigs(client(brokerConfigsDocument), "development", 1);
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);

    const jaas = answer.value.find((entry) => entry.name === "sasl.jaas.config");
    expect(jaas).toBeDefined();
    // `isSensitive` on the wire — the topic endpoint calls the same idea `sensitive`, and reading
    // that name here yields `false` for every row. Nothing would look broken: the values are null
    // anyway, so the table would draw em dashes and quietly claim Kafka has no password set.
    expect(jaas?.sensitive).toBe(true);
    expect(jaas?.value).toBeNull();

    expect(answer.value.some((entry) => entry.sensitive)).toBe(true);
  });

  it("does not silently produce three hundred empty rows", async () => {
    const answer = await fetchBrokerConfigs(client(brokerConfigsDocument), "development", 1);
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    // The property, stated on its own: a mapping that read every field from the wrong name decodes
    // to rows of nulls, and a table of em dashes reads as a broker that did not answer.
    expect(answer.value.filter((entry) => entry.value !== null).length).toBeGreaterThan(200);
    expect(answer.value.every((entry) => entry.source === "UNKNOWN")).toBe(false);
  });
});

describe("the recorded log directories", () => {
  /*
   *   curl -s 'localhost:18080/api/v1/clusters/development/log-dirs?brokerId=1' > src/recorded/brokerLogDirs.json
   */
  it("sizes a directory by what it holds, not by the disk under it", async () => {
    const answer = await fetchBrokerLogDirs(client(logDirsDocument), "development", 1);
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;

    const [dir] = answer.value;
    expect(dir?.path).toBe("/tmp/kafka-logs");
    expect(dir?.error).toBeNull();
    expect(dir?.partitions).toBe(58);

    /*
     * The assertion this document exists for. `totalBytes` is 503 GB and `usableBytes` is 199 GB —
     * both describe the filesystem — while the directory holds about 32 MB. Either of the first two
     * would draw a broker sitting on half a terabyte of Kafka data.
     */
    expect(dir?.sizeBytes).toBeGreaterThan(1_000_000);
    expect(dir?.sizeBytes).toBeLessThan(1_000_000_000);
  });

  it("gives an unreadable directory no size at all", async () => {
    // Not in the recorded document — a healthy stack has no failed disk, and one cannot be arranged
    // on demand. The shape is the server's all the same: `error` is a per-directory field, which is
    // how Kafka reports one disk failing while the rest of the answer is good.
    const failed = {
      logDirs: {
        status: "ok",
        data: [{ brokerId: 1, path: "/mnt/broken", error: "KafkaStorageException", replicas: [] }],
        fetchedAt: "2026-09-05T21:59:38.749Z",
      },
    };
    const answer = await fetchBrokerLogDirs(client(failed), "development", 1);
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);

    // Zero would be a claim about a disk nobody could read, and it would be summed into the total
    // under the table as though the directory were empty.
    expect(answer.value[0]?.sizeBytes).toBeNull();
    expect(answer.value[0]?.partitions).toBeNull();
    expect(answer.value[0]?.error).toBe("KafkaStorageException");
  });
});
