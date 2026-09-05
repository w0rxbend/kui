import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import subjectsDocument from "./recorded/subjects.json" with { type: "json" };
import versionsDocument from "./recorded/versions.json" with { type: "json" };
import schemaDocument from "./recorded/schema.json" with { type: "json" };
import compatibilityDocument from "./recorded/compatibility.json" with { type: "json" };
import {
  COMPATIBILITY_LEVELS,
  fetchGlobalCompatibility,
  fetchSchema,
  fetchSubjects,
  fetchVersions,
  levelOf,
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
