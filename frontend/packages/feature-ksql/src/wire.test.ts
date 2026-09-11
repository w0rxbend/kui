/**
 * The decoders, against the documents the service renders.
 *
 * Every fixture imported here is a **byte-for-byte copy** of a file in
 * `services/ksql/contract/test/resources/golden/`, held that way by `wire.golden.test.ts`. So a
 * case in this file is a case against the shape `KsqlDtos.scala` actually encodes rather than
 * against this author's idea of it — which matters here more than usual: this package's first draft
 * invented a `queries` array, a section-wrapped statement answer, no plan phase at all, typed cells
 * and a columns-on-every-row stream, and every unit case it had was green.
 *
 * The two hand-made documents are named in `ksql.test.tsx`'s header and are the states the service
 * commits no golden for.
 */
import { describe, expect, it } from "vitest";
import { SharedSseEventNames } from "@kui/api";

import objectsResponse from "./documents/objects-response.json" with { type: "json" };
import objectsPartial from "./documents/objects-partial.json" with { type: "json" };
import objectsEmpty from "./documents/objects-empty.json" with { type: "json" };
import planHarmless from "./documents/statement-plan-harmless.json" with { type: "json" };
import planDrop from "./documents/statement-plan.json" with { type: "json" };
import planPush from "./documents/statement-plan-push-query.json" with { type: "json" };
import resultRows from "./documents/statement-rows.json" with { type: "json" };
import resultStatus from "./documents/statement-status.json" with { type: "json" };
import streamFrame from "./documents/ksql-stream-frame.json" with { type: "json" };
import streamHeader from "./documents/ksql-stream-header.json" with { type: "json" };
import {
  decodeObjects,
  decodePlan,
  decodeQueryHeader,
  decodeQueryRow,
  decodeResult,
  objectKind,
  sectionOf,
  statementShape,
  KSQL_OBJECTS_SECTION_KEY,
  KSQL_ROW_EVENT_NAME,
  Unreadable,
} from "./wire.js";

/** The section payload of a document, the way `fetchObjects` reaches it. */
function payload(document: unknown): unknown {
  const section = sectionOf(document, KSQL_OBJECTS_SECTION_KEY);
  return section?.status === "ok" || section?.status === "stale" ? section.data : undefined;
}

function frameData(document: unknown): unknown {
  return (document as { readonly data: unknown }).data;
}

describe("the object listing", () => {
  it("reads one flat list of four kinds, in the order the server sent them", () => {
    const objects = decodeObjects(payload(objectsResponse));
    if (objects === Unreadable) throw new Error("the response golden did not decode");

    /*
     * One list and not four, because the service flattens them and says why: a document with four
     * arrays would make the browser merge and order them, which is a second implementation of a
     * rule the service already applies — and the first time the two orderings disagree, the row an
     * operator clicks is not the row they meant.
     */
    expect(objects.items.map((one) => `${one.kind}:${one.name}`)).toEqual([
      "stream:ORDERS",
      "stream:PAYMENTS",
      "table:USERS",
      "query:CSAS_ENRICHED_ORDERS_5",
      "topic:orders",
    ]);
  });

  it("keeps each kind's own fields and never defaults one of them", () => {
    const objects = decodeObjects(payload(objectsResponse));
    if (objects === Unreadable) throw new Error("the response golden did not decode");

    expect(objects.items[0]?.topic).toBe("orders");
    expect(objects.items[0]?.format).toBe("JSON");
    /* A `null` format means the server did not report one, which older ksqlDB releases genuinely do
       not. Writing `JSON` there would tell an operator their Avro stream is JSON. */
    expect(objects.items[1]?.format).toBeUndefined();
    expect(objects.items[2]?.windowed).toBe(false);
    expect(objects.items[3]?.sinks).toEqual(["ENRICHED_ORDERS"]);
    expect(objects.items[3]?.statement).toContain("CREATE STREAM ENRICHED_ORDERS");
    expect(objects.items[4]?.partitions).toBe(3);
    expect(objects.items[4]?.replication).toBe(3);
  });

  it("carries the rows the server would not describe and the count it cut", () => {
    const objects = decodeObjects(payload(objectsPartial));
    if (objects === Unreadable) throw new Error("the partial golden did not decode");
    // Named rather than dropped: a row missing from a list is indistinguishable from a row that is
    // not there.
    expect(objects.unreadable).toEqual(["(a table the ksqlDB cluster did not name)"]);
    // A screen that said nothing about this would leave an operator concluding a stream does not
    // exist. It is a measured count of what the service cut, not an unknown.
    expect(objects.truncated).toBe(1811);
  });

  it("reads a server that answered and named nothing", () => {
    const objects = decodeObjects(payload(objectsEmpty));
    if (objects === Unreadable) throw new Error("the empty document did not decode");
    expect(objects.items).toEqual([]);
    expect(objects.truncated).toBe(0);
  });

  it("refuses a payload whose object list is spelled something else", () => {
    /*
     * The rule the whole package is built on, and the one its own first draft broke: it read a
     * `queries` array beside `items`. `data.items ?? []` is shorter and is the wave-5 defect
     * verbatim — it turns "this build read the wrong field name" into "this ksqlDB cluster has no
     * streams", and the second sentence is one an operator acts on.
     */
    expect(decodeObjects({ streams: [{ name: "ORDERS" }] })).toBe(Unreadable);
    expect(decodeObjects([])).toBe(Unreadable);
    expect(decodeObjects(null)).toBe(Unreadable);
  });

  it("drops an object with no name and tolerates an older service's missing extras", () => {
    const objects = decodeObjects({ items: [{ kind: "stream" }, { name: "OK", kind: "stream" }] });
    if (objects === Unreadable) throw new Error("that listing should have decoded");
    // A nameless object is unactionable: every sentence this screen writes about one names it.
    expect(objects.items.map((one) => one.name)).toEqual(["OK"]);
    // Absent `unreadable` and `truncated` say nothing was dropped — unlike an absent `items`.
    expect(objects.unreadable).toEqual([]);
    expect(objects.truncated).toBe(0);
  });

  it("folds an unknown kind word rather than guessing stream", () => {
    expect(objectKind("stream")).toBe("stream");
    expect(objectKind("TABLE")).toBe("table");
    expect(objectKind("query")).toBe("query");
    expect(objectKind("topic")).toBe("topic");
    expect(objectKind("materialized_view")).toBe("other");
    expect(objectKind(undefined)).toBe("other");
  });
});

describe("a plan", () => {
  it("reads a harmless statement as needing no confirmation and no token", () => {
    const plan = decodePlan(planHarmless);
    if (plan === Unreadable) throw new Error("the harmless plan golden did not decode");
    expect(plan.shape).toBe("statement");
    expect(plan.destructive).toBe(false);
    expect(plan.token).toBeUndefined();
  });

  it("reads a DROP ... DELETE TOPIC with its token and the server's own warning", () => {
    const plan = decodePlan(planDrop);
    if (plan === Unreadable) throw new Error("the destructive plan golden did not decode");
    expect(plan.destructive).toBe(true);
    // The one thing ksqlDB's language can do that destroys records, and the whole reason ADR-045
    // reaches this screen at all.
    expect(plan.deletesTopic).toBe(true);
    expect(plan.token).toBe("eyJ2IjoxfQ.c2ln");
    expect(plan.warnings).toEqual([
      "This deletes the Kafka topic 'orders' and every record in it. Nothing in KUI can undo it.",
    ]);
    // The canonicalised text the token is bound to — not the editor's, which the reader can still
    // be typing into while the dialogue is open.
    expect(plan.statement).toBe("DROP STREAM ORDERS DELETE TOPIC;");
  });

  it("reads a push query as a shape to stream rather than a statement to apply", () => {
    expect(decodePlan(planPush)).toMatchObject({ shape: "push_query", destructive: false });
  });

  it("refuses a plan whose two flags are missing rather than reading them as false", () => {
    /*
     * The most expensive default available on this screen. A document this build could not read,
     * decoded as `destructive: false`, is a confirmation KUI decided nobody needed — over a
     * statement that may delete a Kafka topic.
     */
    expect(decodePlan({ statement: "DROP STREAM S;", shape: "statement" })).toBe(Unreadable);
    expect(decodePlan({ statement: "x", shape: "statement", destructive: true })).toBe(Unreadable);
  });

  it("does not fold an unknown shape into a statement to apply", () => {
    // The shapes go to two different endpoints, and guessing wrong sends a push query to the
    // address that refuses it.
    expect(statementShape("pull_query")).toBe("pull_query");
    expect(statementShape("streaming_insert")).toBe("unknown");
  });
});

describe("a finished statement's answer", () => {
  it("reads a pull query's rows, keeping a SQL null as a value", () => {
    const result = decodeResult(resultRows);
    if (result === Unreadable) throw new Error("the rows golden did not decode");

    expect(result.outcome).toBe("rows");
    expect(result.columns).toEqual(["ID", "TOTAL", "NOTE"]);
    expect(result.rows[0]).toEqual(["17", "42.50", "gift wrap"]);
    /* `null` is a value the query produced — the column was selected and it is nothing — and it is
       drawn as the word. It is not the same fact as a cell the server did not send. */
    expect(result.rows[1]?.[2]).toBeNull();
  });

  it("reads a row shorter than its own column list as missing cells, not empty ones", () => {
    /*
     * A literal rather than a golden, because the service commits none: this is a disagreement
     * *inside* one document — a row narrower than the columns the same answer declared — and the
     * encoder has no reason to produce one. It is the shape a decoding fault takes, so padding it
     * into a blank cell would let a fault read as data.
     */
    const result = decodeResult({
      statement: "SELECT ID, TOTAL, NOTE FROM ORDERS;",
      shape: "pull_query",
      outcome: "rows",
      columns: ["ID", "TOTAL", "NOTE"],
      rows: [["17", "42.50"]],
      message: null,
      entity: null,
      executedAt: "2026-09-03T10:11:12Z",
    });
    if (result === Unreadable) throw new Error("that result should have decoded");
    expect(result.rows[0]?.[2]).toBeUndefined();
  });

  it("reads a status answer as the server's own sentence and what it acted on", () => {
    const result = decodeResult(resultStatus);
    if (result === Unreadable) throw new Error("the status golden did not decode");
    expect(result.outcome).toBe("status");
    expect(result.message).toBe("Stream created and running");
    expect(result.entity).toBe("stream/ENRICHED_ORDERS/create");
  });

  it("refuses an outcome it has never seen, and a status with no sentence", () => {
    // An unrecognised outcome drawn as a status would be KUI putting its own words over the
    // server's silence, which is the one thing this product's voice rules forbid everywhere else.
    expect(
      decodeResult({ statement: "x", shape: "statement", outcome: "table", executedAt: "t" }),
    ).toBe(Unreadable);
    // The whole of a status answer *is* the sentence; an empty result region under a statement that
    // ran reads as though nothing happened.
    expect(
      decodeResult({
        statement: "x",
        shape: "statement",
        outcome: "status",
        message: "  ",
        executedAt: "t",
      }),
    ).toBe(Unreadable);
    expect(
      decodeResult({
        statement: "x",
        shape: "pull_query",
        outcome: "rows",
        columns: ["A"],
        executedAt: "t",
      }),
    ).toBe(Unreadable);
  });
});

describe("a push query's frames", () => {
  it("reads the columns off the phase frame the service commits", () => {
    expect(decodeQueryHeader(frameData(streamHeader))).toEqual(["ID", "TOTAL", "NOTE"]);
  });

  it("reads the values off the row frame, which does not repeat the columns", () => {
    // A stream that repeated the schema on every row would send it a thousand times to describe a
    // thousand rows. The committed frame carries a `null`, so the streamed half of the wire has the
    // same three-valued cell the pull half does.
    expect(decodeQueryRow(frameData(streamFrame))).toEqual(["17", "42.50", null]);
  });

  it("refuses a frame with no values rather than appending an empty row", () => {
    expect(decodeQueryRow({ row: ["a"] })).toBe(Unreadable);
    expect(decodeQueryRow("values")).toBe(Unreadable);
    expect(decodeQueryHeader({ names: ["A"] })).toBe(Unreadable);
  });

  it("does not publish rows under one of ADR-035's shared event names", () => {
    /*
     * `@kui/kernel`'s stream client handles `phase`, `done`, `error` and `heartbeat` itself and
     * *throws* for a subscriber that lists one, so a row event named after any of them would take
     * the whole screen down at the first Run rather than fail a case here. The name's agreement
     * with the service is checked against the committed frame in `wire.golden.test.ts`.
     */
    expect(SharedSseEventNames).not.toContain(KSQL_ROW_EVENT_NAME);
  });
});
