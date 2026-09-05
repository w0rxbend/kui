import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import { nameProblem } from "./CreateTopicDialog.jsx";
import { describeDeletion, describePurge } from "./TopicsRoute.jsx";
import { consequenceOf } from "./PlannedActionDialog.jsx";
import { planPurge } from "./write.js";

function client(document: unknown): {
  api: KuiApiClient;
  post: ReturnType<typeof vi.fn>;
} {
  const post = vi.fn(async () => ({ ok: true, value: document }));
  return {
    api: {
      get: post,
      post,
      put: post,
      delete: post,
      patch: post,
      raw: {},
    } as unknown as KuiApiClient,
    post,
  };
}

describe("a new topic's name", () => {
  it("accepts what Kafka accepts", () => {
    expect(nameProblem("orders.payments.v2", [])).toBeUndefined();
    expect(nameProblem("orders-payments", [])).toBeUndefined();
  });

  it("says nothing about an empty box", () => {
    // Not yet typed is not yet wrong. Marking an untouched field as invalid trains people to
    // ignore the colour.
    expect(nameProblem("", [])).toBeUndefined();
  });

  it("refuses the names Kafka reserves", () => {
    expect(nameProblem(".", [])).toMatch(/reserves/);
    expect(nameProblem("..", [])).toMatch(/reserves/);
  });

  it("refuses characters Kafka refuses", () => {
    expect(nameProblem("orders/payments", [])).toMatch(/249/);
    expect(nameProblem("a".repeat(250), [])).toMatch(/249/);
  });

  it("catches a clash before the round trip", () => {
    expect(nameProblem("orders", ["orders"])).toMatch(/already has/);
  });

  it("warns about the metric collision without refusing it", () => {
    /*
     * Kafka collapses "." and "_" to the same character in metric names, so `a.b` and `a_b` report
     * into each other's graphs. Kafka itself only warns, so refusing here would be KUI inventing a
     * rule the cluster does not have — the dialog shows this as help text and leaves the button on.
     */
    const problem = nameProblem("orders.payments_v2", []);
    expect(problem).toMatch(/metrics/);
  });

  it("reports a real error ahead of the metric warning", () => {
    // A name that is both a duplicate and metric-ambiguous must report the duplicate: one blocks
    // the create and the other does not.
    expect(nameProblem("a.b_c", ["a.b_c"])).toMatch(/already has/);
  });
});

describe("a purge plan", () => {
  it("sums the record count from the partitions' offset windows", async () => {
    const { api } = client({
      topic: "orders",
      computedAt: "2026-09-05T10:00:00Z",
      token: "tok",
      partitions: [
        { partition: 0, lowWatermark: 10, highWatermark: 110 },
        { partition: 1, lowWatermark: 0, highWatermark: 5 },
      ],
    });
    const answer = await planPurge(api, "quickstart", "orders");
    expect(answer.ok).toBe(true);
    if (!answer.ok) return;
    expect(answer.value.records).toBe(105);
    expect(answer.value.partitions).toBe(2);
  });

  it("refuses to total a partial reading", async () => {
    /*
     * The expensive case. A sum over the partitions that *could* be read is a smaller number
     * presented with the confidence of a complete one, and the operator would agree to lose more
     * than the dialog said. Unknown has to stay unknown.
     */
    const { api } = client({
      topic: "orders",
      computedAt: "2026-09-05T10:00:00Z",
      token: "tok",
      partitions: [
        { partition: 0, lowWatermark: 10, highWatermark: 110 },
        { partition: 1, lowWatermark: 0 },
      ],
    });
    const answer = await planPurge(api, "quickstart", "orders");
    if (!answer.ok) return;
    expect(answer.value.records).toBeNull();
  });

  it("keeps a plan with no token, because a read-only cluster answers that way", async () => {
    const { api } = client({
      topic: "orders",
      computedAt: "2026-09-05T10:00:00Z",
      partitions: [],
    });
    const answer = await planPurge(api, "quickstart", "orders");
    if (!answer.ok) return;
    // The operator can still see what would happen; the dialog says it cannot be applied.
    expect(answer.value.token).toBeNull();
  });
});

describe("the consequence sentences", () => {
  it("counts in figures, not adjectives", () => {
    const sentence = describePurge({
      topic: "orders",
      records: 1284003,
      partitions: 12,
      warnings: [],
      token: "tok",
      expiresAt: null,
    });
    expect(sentence).toContain("1,284,003");
    expect(sentence).toContain("12 partitions");
  });

  it("says the count is unknown rather than quoting a partial one", () => {
    const sentence = describePurge({
      topic: "orders",
      records: null,
      partitions: 12,
      warnings: [],
      token: "tok",
      expiresAt: null,
    });
    expect(sentence).toMatch(/not known/);
    // Crucially: no number that could be mistaken for the total.
    expect(sentence).not.toMatch(/\d[\d,]* records/);
  });

  it("warns that auto-create will bring the topic straight back", () => {
    /*
     * The sentence an operator is least likely to have thought of: with
     * auto.create.topics.enable on, deleting a topic something is still producing to leaves a fresh
     * one with the broker's defaults rather than removing it.
     */
    const sentence = describeDeletion({
      topic: "orders",
      partitions: 3,
      records: 10,
      autoCreateEnabled: true,
      warnings: [],
      token: "tok",
      expiresAt: null,
    });
    expect(sentence).toMatch(/recreate/);
  });

  it("says nothing about auto-create when the setting could not be read", () => {
    // `null` is "we were not told", and claiming either way invents a fact about the cluster.
    const sentence = describeDeletion({
      topic: "orders",
      partitions: 3,
      records: 10,
      autoCreateEnabled: null,
      warnings: [],
      token: "tok",
      expiresAt: null,
    });
    expect(sentence).not.toMatch(/recreate/);
  });

  it("uses the singular for one partition", () => {
    const sentence = describePurge({
      topic: "orders",
      records: 1,
      partitions: 1,
      warnings: [],
      token: "tok",
      expiresAt: null,
    });
    expect(sentence).toContain("1 partition.");
    expect(sentence).toContain("1 record ");
  });
});

describe("what a destructive confirmation actually says", () => {
  const plan = {
    topic: "orders.v1",
    records: 16,
    partitions: 6,
    token: "tok",
    expiresAt: null,
    warnings: [
      {
        code: "RECORDS_LOST",
        message:
          "16 records across 4 partitions are deleted and cannot be recovered. The topic, its configuration and its partitions stay exactly as they are; only the records go.",
      },
      {
        code: "CONSUMER_OFFSETS_UNCHANGED",
        message: "Committed consumer offsets are not moved by a purge.",
      },
    ],
  };

  it("uses the server's sentences and does not add its own", () => {
    /*
     * The defect this replaced: the dialog composed its own measurement and appended the warnings,
     * so the operator was told the size of the deletion twice, in two phrasings, with two different
     * partition counts — the server says "4 partitions" because two hold nothing, and the client can
     * only say "6". On a dialog whose whole purpose is that its numbers are read, that is the worst
     * possible place to disagree with yourself.
     */
    const sentence = consequenceOf(plan, describePurge);
    expect(sentence).toContain("16 records across 4 partitions");
    expect(sentence).toContain("Committed consumer offsets are not moved");
    expect(sentence).not.toContain("6 partitions");
  });

  it("still says something when the server had no warnings", () => {
    // A topic with nothing in it: the server has nothing to warn about, and the dialog still owes
    // the operator a sentence rather than an empty confirmation.
    const sentence = consequenceOf({ ...plan, records: 0, warnings: [] }, describePurge);
    expect(sentence).toContain("0 records");
  });

  it("says a read-only cluster cannot apply this, whatever else it says", () => {
    // The server answers a plan with no token rather than an error, so the operator can see what
    // would happen. The dialog has to say that it will not happen.
    const sentence = consequenceOf({ ...plan, token: null }, describePurge);
    expect(sentence).toMatch(/read-only/);
  });
});
