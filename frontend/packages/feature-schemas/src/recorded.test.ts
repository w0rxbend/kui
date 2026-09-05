import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import subjectsDocument from "./recorded/subjects.json" with { type: "json" };
import versionsDocument from "./recorded/versions.json" with { type: "json" };
import schemaDocument from "./recorded/schema.json" with { type: "json" };
import compatibilityDocument from "./recorded/compatibility.json" with { type: "json" };
import compatibleDocument from "./recorded/compatibility-check-compatible.json" with { type: "json" };
import incompatibleDocument from "./recorded/compatibility-check-incompatible.json" with { type: "json" };
import noneLevelDocument from "./recorded/compatibility-check-none-level.json" with { type: "json" };
import {
  COMPATIBILITY_LEVELS,
  checkBlockedReason,
  checkCompatibility,
  checkIsMeaningful,
  fetchGlobalCompatibility,
  fetchSchema,
  fetchSubjects,
  fetchVersions,
  levelOf,
  proposedSchemaProblem,
} from "./data.js";

/**
 * The mapping, against documents a real gateway produced.
 *
 * These were captured only after fixing a backend defect that made every one of these endpoints
 * answer "the configured address does not look like a Schema Registry": the registry client built
 * its request URIs from the full configured URL, and the failover layer prefixed the base path
 * again, so a registry mounted at a sub-path — Apicurio's `/apis/ccompat/v7`, which is what the
 * quickstart runs — was asked for `/apis/ccompat/v7/apis/ccompat/v7/subjects`.
 *
 * Re-record with the quickstart stack running:
 *
 *   curl -s localhost:8080/api/v1/clusters/quickstart/schemas/subjects | python3 -m json.tool \
 *     > src/recorded/subjects.json
 */
function client(document: unknown): KuiApiClient {
  const get = vi.fn(async () => ({ ok: true, value: document }));
  return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
}

describe("the recorded subject list", () => {
  it("reads the subjects and where the page sits", async () => {
    const answer = await fetchSubjects(client(subjectsDocument), "quickstart");
    expect(answer.kind).toBe("ready");
    if (answer.kind !== "ready") return;
    expect(answer.value.subjects).toEqual(["orders.avro-value"]);
    expect(answer.value.page.totalItems).toBe(1);
  });
});

describe("the recorded compatibility level", () => {
  it("reads NONE as a level rather than as an absence", async () => {
    /*
     * The most consequential value this feature displays and the easiest to render wrongly. `NONE`
     * means the registry checks nothing at all — it will accept a schema that breaks every existing
     * reader — and it is a *setting*, not a missing value. A mapping treating it as "no level" would
     * draw the most dangerous configuration a registry can have as a blank.
     */
    const answer = await fetchGlobalCompatibility(client(compatibilityDocument), "quickstart");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.level).toBe("NONE");
    expect(answer.value.inherited).toBe(false);
  });
});

describe("the recorded versions", () => {
  it("puts the newest first, because that is the one anybody opens", async () => {
    const answer = await fetchVersions(client({ subject: "s", versions: [1, 2, 3] }), "quickstart", "s");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value).toEqual([3, 2, 1]);
  });

  it("reads the recorded subject's single version", async () => {
    const answer = await fetchVersions(client(versionsDocument), "quickstart", "orders.avro-value");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value).toEqual([1]);
  });
});

describe("the recorded schema", () => {
  it("keeps the registry id and the version apart", async () => {
    /*
     * They are different numbers and an operator debugging a decode failure needs the *id*: it is
     * what every record's header carries, and a record carries no version at all. Conflating them
     * sends somebody looking for "version 5" in a registry that has ids up to 5 and versions up to 2.
     */
    const answer = await fetchSchema(client(schemaDocument), "quickstart", "orders.avro-value", "latest");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    expect(answer.value.version).toBe(1);
    expect(answer.value.id).toBe(1);
    expect(answer.value.schemaType).toBe("AVRO");
  });

  it("keeps the definition exactly as the registry stores it", async () => {
    // Byte for byte: this is the string the registry compares for compatibility and the one a
    // producer's tooling will send. Reformatting it here makes a terminal `diff` fail for reasons
    // that are invisible on the screen.
    const answer = await fetchSchema(client(schemaDocument), "quickstart", "orders.avro-value", "latest");
    if (answer.kind !== "ready") throw new Error(`expected ready, got ${answer.kind}`);
    const recorded = (schemaDocument as { definition: string }).definition;
    expect(answer.value.definition).toBe(recorded);
  });
});

describe("reading a compatibility level", () => {
  it("accepts every level the registry has, in any case", () => {
    for (const level of COMPATIBILITY_LEVELS) {
      expect(levelOf(level)).toBe(level);
      expect(levelOf(level.toLowerCase())).toBe(level);
    }
  });

  it("refuses a word it does not know rather than passing it through", () => {
    // This value decides whether tomorrow's schema is accepted. Showing a word the browser does not
    // understand as though it were a setting is worse than saying it was not recognised.
    expect(levelOf("SIDEWAYS")).toBeNull();
    expect(levelOf("")).toBeNull();
    expect(levelOf(null)).toBeNull();
  });
});

/* ------------------------------------------------------------------------------------------------
 * Checking a schema before it is registered
 *
 * The three documents beside this file were captured from running gateways:
 *
 *   POST …/schemas/subjects/orders.avro.v1-value/versions/latest/compatibility
 *
 * with the registered schema (compatible), with that schema after a field's type was changed from
 * `double` to `string` and a required field added (incompatible, against a Confluent registry whose
 * level is BACKWARD), and with the same broken schema against a subject whose level is NONE.
 *
 * Re-record with the quickstart stack running, once a session cookie and CSRF token are in hand:
 *
 *   curl -s -b jar -H "X-Csrf-Token: $TOK" -H 'Content-Type: application/json' -X POST \
 *     --data '{"schemaType":"AVRO","definition":"…"}' \
 *     localhost:8080/api/v1/clusters/quickstart/schemas/subjects/orders.avro-value/versions/latest/compatibility \
 *     | python3 -m json.tool > src/recorded/compatibility-check-compatible.json
 * ---------------------------------------------------------------------------------------------- */

/** A client that records what it was asked for, so the path and body can be asserted. */
function recordingClient(document: unknown): {
  readonly api: KuiApiClient;
  readonly calls: { path: string; options: unknown }[];
} {
  const calls: { path: string; options: unknown }[] = [];
  const post = vi.fn(async (path: string, options: unknown) => {
    calls.push({ path, options });
    return { ok: true, value: document };
  });
  return {
    api: { get: post, post, put: post, delete: post, patch: post, raw: {} } as unknown as KuiApiClient,
    calls,
  };
}

describe("the recorded compatibility check", () => {
  it("reads a verdict the registry accepted", async () => {
    const answer = await checkCompatibility(client(compatibleDocument), "quickstart", "s", {
      schemaType: "AVRO",
      definition: "{}",
    });
    expect(answer.ok).toBe(true);
    if (!answer.ok) return;
    expect(answer.value.compatible).toBe(true);
    expect(answer.value.messages).toEqual([]);
  });

  it("keeps the registry's refusal in the registry's own words", async () => {
    /*
     * The whole value of the answer. A Confluent registry names the error type, the JSON pointer to
     * the field, the reader and writer types, the version it compared against and the entire older
     * schema — and every one of those is something the operator needs to fix the schema. Anything
     * this mapping summarised or dropped would be the part they had to go to the registry's own logs
     * for, which is exactly what this panel exists to save them.
     */
    const { api } = recordingClient(incompatibleDocument);
    const answer = await checkCompatibility(api, "development", "orders.avro.v1-value", {
      schemaType: "AVRO",
      definition: "{}",
    });
    if (!answer.ok) throw new Error("expected a verdict");
    expect(answer.value.compatible).toBe(false);
    expect(answer.value.messages).toHaveLength(5);
    expect(answer.value.messages).toEqual(
      (incompatibleDocument as { messages: readonly string[] }).messages,
    );
    expect(answer.value.messages[0]).toContain("TYPE_MISMATCH");
    expect(answer.value.messages[1]).toContain("READER_FIELD_MISSING_DEFAULT_VALUE");
  });

  it("asks about the latest version, and says which subject in the path", async () => {
    // `latest` and not a number: the question is "would this be accepted if I registered it now",
    // and what the registry compares a new version against is the current one. A number would also
    // go stale while somebody has the panel open.
    const { api, calls } = recordingClient(compatibleDocument);
    await checkCompatibility(api, "quickstart", "orders.avro-value", {
      schemaType: "AVRO",
      definition: '{"type":"string"}',
    });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.path).toBe(
      "/api/v1/clusters/{clusterId}/schemas/subjects/{subject}/versions/{version}/compatibility",
    );
    expect(calls[0]?.options).toMatchObject({
      params: { path: { clusterId: "quickstart", subject: "orders.avro-value", version: "latest" } },
      body: { schemaType: "AVRO", definition: '{"type":"string"}' },
    });
  });

  it("treats a refusal with no messages as a refusal, not as an empty answer", async () => {
    /*
     * A real response from this gateway, not a hypothetical. Apicurio's Confluent-compatible API —
     * what the quickstart runs — words its explanation under `reason`, which KUI's registry client
     * does not read, so a genuine refusal arrives with an empty `messages`. The mapping must not
     * turn that into an absent verdict, and the screen must not draw it as blank space.
     */
    const answer = await checkCompatibility(
      recordingClient({ compatible: false, messages: [] }).api,
      "quickstart",
      "orders.avro-value",
      { schemaType: "AVRO", definition: "{}" },
    );
    if (!answer.ok) throw new Error("expected a verdict");
    expect(answer.value.compatible).toBe(false);
    expect(answer.value.messages).toEqual([]);
  });

  it("survives a response with no messages field at all", async () => {
    // `messages` is optional in the schema and Confluent omits it on a plain yes. Absent and empty
    // mean the same thing — the registry said nothing — and neither is an error.
    const answer = await checkCompatibility(
      recordingClient({ compatible: true }).api,
      "quickstart",
      "s",
      { schemaType: "AVRO", definition: "{}" },
    );
    if (!answer.ok) throw new Error("expected a verdict");
    expect(answer.value.messages).toEqual([]);
  });
});

describe("a subject whose compatibility level is NONE", () => {
  it("answers the same document for a breaking schema as for the registered one", () => {
    /*
     * The reason the panel refuses to run the check at all under `NONE`, stated as an assertion.
     * The recorded `…-none-level` document is the verdict for a schema that changes a field's type
     * and adds a required field, taken from a subject whose level is NONE; it is byte-for-byte the
     * verdict for the schema that is already registered. Nothing in the response distinguishes a
     * schema that is safe from one that breaks every reader, so nothing in the response *can* be the
     * basis for the warning — it has to come from the level.
     */
    expect(noneLevelDocument).toEqual(compatibleDocument);
    expect((noneLevelDocument as { compatible: boolean }).compatible).toBe(true);
  });

  it("is the one level under which a check tells the operator nothing", () => {
    expect(checkIsMeaningful("NONE")).toBe(false);
    for (const level of COMPATIBILITY_LEVELS.filter((one) => one !== "NONE")) {
      expect(checkIsMeaningful(level)).toBe(true);
    }
    // A level the registry named and this browser does not recognise. Not `NONE`, so not silently
    // treated as the dangerous case — the panel would rather run a check that might be useful than
    // suppress one on a guess.
    expect(checkIsMeaningful(null)).toBe(true);
  });
});

describe("a proposed schema that is not JSON", () => {
  it("is caught before the round trip, with the parser's own position", () => {
    // Against this gateway the round trip answers "the schema registry refused the request: Could
    // not execute compatibility rule on invalid Avro schema", which says less than the parser does
    // and says it a second later.
    const problem = proposedSchemaProblem("AVRO", "{ not json");
    expect(problem).toBeDefined();
    expect(problem).toContain("not valid JSON");
  });

  it("says nothing about an empty box, because not yet typed is not yet wrong", () => {
    expect(proposedSchemaProblem("AVRO", "")).toBeUndefined();
    expect(proposedSchemaProblem("AVRO", "   \n ")).toBeUndefined();
  });

  it("accepts the registered Avro schema as recorded, whitespace and all", () => {
    const definition = (schemaDocument as { definition: string }).definition;
    expect(proposedSchemaProblem("AVRO", definition)).toBeUndefined();
  });

  it("leaves Protobuf alone, because a .proto definition is not JSON", () => {
    // Parsing it here would refuse every correct Protobuf schema anybody typed.
    expect(proposedSchemaProblem("PROTOBUF", 'syntax = "proto3"; message Order { string id = 1; }'))
      .toBeUndefined();
  });
});

describe("why the check button will not press", () => {
  const proposed = { schemaType: "AVRO", definition: '{"type":"string"}' };

  it("gives no reason when there is nothing wrong", () => {
    expect(checkBlockedReason({ level: "BACKWARD", ...proposed, busy: false })).toBeUndefined();
  });

  it("names NONE before it asks for a schema", () => {
    /*
     * The order is the decision. Under `NONE` nothing typed in the box could produce an answer worth
     * having, so telling somebody to paste a schema first would send them off to do work that ends in
     * a verdict that means nothing — and hands them a green pill for a change that breaks every
     * reader of the topic.
     */
    const empty = checkBlockedReason({ level: "NONE", schemaType: "AVRO", definition: "", busy: false });
    expect(empty).toContain("NONE");
    const typed = checkBlockedReason({ level: "NONE", ...proposed, busy: false });
    expect(typed).toContain("NONE");
  });

  it("asks for a schema before it complains about one", () => {
    expect(checkBlockedReason({ level: "FULL", schemaType: "AVRO", definition: "  ", busy: false }))
      .toBe("Paste the schema you want to check first.");
  });

  it("hands on the parser's complaint when the text is not JSON", () => {
    const reason = checkBlockedReason({
      level: "BACKWARD",
      schemaType: "AVRO",
      definition: "{ not json",
      busy: false,
    });
    expect(reason).toContain("not valid JSON");
  });

  it("says a request is out rather than letting a second one start", () => {
    expect(checkBlockedReason({ level: "BACKWARD", ...proposed, busy: true })).toBe(
      "Waiting for the registry to answer.",
    );
  });

  it("does not block while the level is still being read", () => {
    // Not yet known is not yet `NONE`. Suppressing the control on a level that has not arrived would
    // make a slow registry look like a misconfigured one.
    expect(checkBlockedReason({ level: undefined, ...proposed, busy: false })).toBeUndefined();
  });
});
