/**
 * The documents the **ksql service's own encoder** renders, decoded by the browser that reads them.
 *
 * ## Why this file exists, and what it is for
 *
 * House rule 12 is the only instruction in seven waves that has measurably worked: one packet owns
 * the DTO, the decoder and the golden between them, and the binding case decodes the encoder's own
 * output. It closed M7 after two waves of prose contracts had produced two mismatched wires and a
 * card that lied about its source; it caught `feature-connect`'s first draft reading `data.items`
 * where the service renders `data.workers[].connectors.data.items`, with every unit case in that
 * package green at the time.
 *
 * This wave splits the ksqlDB wire across two packets again — `services/ksql` and its goldens are
 * W8-01's, this package is W8-05's, with the contract stated in prose between them. That is exactly
 * the arrangement that has failed three times, and it very nearly failed a fourth here: this
 * package's first draft read a `queries` array beside `items`, expected the statement's answer
 * inside a `Section`, had no plan phase at all, typed a cell as a JSON value and put the push
 * query's columns on the row frame. Every one of those was self-consistent and every unit case was
 * green.
 *
 * So this suite binds five separate claims:
 *
 *  1. **every golden that carries an `objects` section decodes into a listing** through the same
 *     fetcher the product calls, and is not a failure;
 *  2. **it holds every object the raw JSON names**, counted out of the document rather than out of
 *     the decoder — without this, a decoder that dropped every row would satisfy claim 1, which is
 *     the wave-5 defect verbatim;
 *  3. **every plan document decodes**, including its `destructive` and `deletesTopic` flags, which
 *     are what decide whether a reader is asked to confirm;
 *  4. **every result document decodes**, so the write half of the wire is bound too —
 *     `feature-connect`'s suite binds only the read half, and the one place this project has ever
 *     been bitten is the half nobody bound;
 *  5. **the push query's two frames are the service's own**: the row event name, compared against a
 *     committed frame rather than against a second literal, and the `phase` frame's column list.
 *     `ALERTS_EVENT_NAME` being a hand-copied mirror is this project's standing example of that
 *     mistake, and `kernel/src/data/alerts/wire.golden.test.ts:212` is the case shape this copies.
 *
 * And it binds this package's own `src/documents/` copies to those goldens, byte for byte, so the
 * fixtures the stories and the unit cases are built from cannot drift into a third opinion about
 * the wire — which is what `e2e/connect.spec.ts` did and what kept M9 open for a wave.
 *
 * ## A missing directory is a failure and never a skip
 *
 * Until `services/ksql/contract` commits its goldens this suite is **red**, with a message naming
 * the path and saying why — because a suite that quietly passed when its fixture is absent would
 * report green over a browser and a service that have never met, which is the whole failure mode
 * the file is about. `feature-connect`'s equivalent was red for the same reason for most of wave 7
 * and caught the defect the day it went green.
 */
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { fetchObjects, KSQL_OBJECTS_PATH } from "./data.js";
import { pushQueryPlan, serving } from "./testing.js";
import {
  decodePlan,
  decodeQueryHeader,
  decodeQueryRow,
  decodeResult,
  KSQL_OBJECTS_SECTION_KEY,
  KSQL_ROW_EVENT_NAME,
  Unreadable,
} from "./wire.js";

const HERE = dirname(fileURLToPath(import.meta.url));

/** The ksql contract module's committed documents, four directories up from this file. */
const GOLDEN = join(
  HERE,
  "..",
  "..",
  "..",
  "..",
  "services",
  "ksql",
  "contract",
  "test",
  "resources",
  "golden",
);

/** This package's own copies, which must be byte-identical to the goldens of the same name. */
const DOCUMENTS = join(HERE, "documents");

interface Golden {
  readonly name: string;
  readonly text: string;
  readonly document: unknown;
}

/** Every committed golden, or a thrown, named failure. */
function goldens(): readonly Golden[] {
  if (!existsSync(GOLDEN)) {
    throw new Error(
      `There are no ksqlDB golden documents at ${GOLDEN}, so nothing in this browser has been ` +
        "compared against the shape the ksql service actually renders. They are rendered " +
        "from the " +
        "service's own encoder and committed by services/ksql/contract (W8-01 item 8, the shape " +
        "services/connect/contract already has). Until they exist, feature-ksql's wire is " +
        "transcribed from KsqlDtos.scala by eye — which is how wave 5 shipped two halves of one " +
        "wire that never met, with every other gate green. wire.ts's header states, field by " +
        "field, what this side reads.",
    );
  }

  const found = readdirSync(GOLDEN)
    .filter((name) => name.endsWith(".json"))
    .map((name) => {
      const text = readFileSync(join(GOLDEN, name), "utf8");
      return { name, text, document: JSON.parse(text) as unknown };
    });

  if (found.length === 0) {
    throw new Error(`${GOLDEN} holds no JSON document, so nothing on this wire has been compared.`);
  }
  return found;
}

function record(document: unknown): Record<string, unknown> {
  return typeof document === "object" && document !== null
    ? (document as Record<string, unknown>)
    : {};
}

/** The goldens that carry a given top-level key. */
function carrying(key: string): readonly Golden[] {
  return goldens().filter((one) => key in record(one.document));
}

/** A golden whose top-level `event` is this one — the shape an SSE frame is committed in. */
function frames(event: string): readonly Golden[] {
  return goldens().filter((one) => record(one.document)["event"] === event);
}

describe("the documents the ksql service rendered", () => {
  it("turns every object listing into the screen state its own status calls for", async () => {
    const listings = carrying(KSQL_OBJECTS_SECTION_KEY);
    /*
     * Not a skip. A service whose goldens carry no object listing has not rendered the document
     * this screen is built on, and reporting green over that is the failure this whole file is
     * about.
     */
    const noListing = `${GOLDEN} holds no '${KSQL_OBJECTS_SECTION_KEY}' document`;
    expect(listings.length, noListing).toBeGreaterThan(0);

    /*
     * The mapping, by status, rather than "it did not fail".
     *
     * `feature-connect`'s equivalent asserts only that a golden does not reach `failed`, which is
     * the vacuous-pass hole W8-06 is closing in the alerts suite this wave: a `forbidden` or
     * `not_configured` golden satisfies that line without the decoder ever running. Here every
     * status has an expected state and a refusal that lands in the wrong one is a failure.
     */
    const expected: Readonly<Record<string, string>> = {
      ok: "ready",
      stale: "stale",
      unavailable: "failed",
      forbidden: "forbidden",
      not_configured: "not-configured",
    };

    let decoded = 0;
    for (const { name, document } of listings) {
      const status = String(record(record(document)[KSQL_OBJECTS_SECTION_KEY])["status"]);
      const stub = serving({ [KSQL_OBJECTS_PATH]: document });
      const state = await fetchObjects(stub.api, "quickstart");

      const unexpected = `${name} carries a status this suite has no expectation for`;
      expect(expected[status], unexpected).toBeDefined();
      const reached = `${name} (${status}) reached ${JSON.stringify(state)}`;
      expect(state.kind, reached).toBe(expected[status]);

      if (state.kind === "ready" || state.kind === "stale") {
        decoded += 1;
        /*
         * Counted from the raw JSON rather than from the decoder. A decoder that dropped every row
         * would otherwise satisfy the line above — which is the shape of the wave-5 defect, not a
         * pedantic extra. `unreadable` and `truncated` are counted too: they are what the listing
         * says about what is *missing* from it, and a browser that dropped them would draw a short
         * list as a complete one.
         */
        const data = record(record(record(document)[KSQL_OBJECTS_SECTION_KEY])["data"]);
        expect(state.value.items.length, `${name} names objects this build did not hold`).toBe(
          named(data["items"]),
        );
        expect(state.value.unreadable.length, `${name} names rows this build dropped`).toBe(
          Array.isArray(data["unreadable"]) ? data["unreadable"].length : 0,
        );
        expect(state.value.truncated, `${name}'s truncation count did not survive`).toBe(
          typeof data["truncated"] === "number" ? data["truncated"] : 0,
        );
      }
    }

    // And the counter itself refuses a vacuous pass: a golden set made entirely of refusals would
    // satisfy every line above without `decodeObjects` running once.
    const vacuous = "no golden carried a payload, so the decoder was never exercised";
    expect(decoded, vacuous).toBeGreaterThan(0);
  });

  it("decodes every plan, keeping the two flags that decide whether a reader is asked", () => {
    const plans = carrying("deletesTopic");
    const noPlan = `${GOLDEN} holds no plan document, so ADR-045's browser half is uncompared`;
    expect(plans.length, noPlan).toBeGreaterThan(0);

    for (const { name, document } of plans) {
      const plan = decodePlan(document);
      expect(plan, `${name} carried a plan this build cannot read`).not.toBe(Unreadable);
      if (plan !== Unreadable) {
        // Read strictly on the service's own values, not defaulted. A missing boolean decoded as
        // `false` would turn a document this build could not read into a confirmation KUI decided
        // nobody needed, which is the most expensive default available on this screen.
        expect(plan.destructive).toBe(record(document)["destructive"]);
        expect(plan.deletesTopic).toBe(record(document)["deletesTopic"]);
      }
    }
  });

  it("decodes every finished statement's answer", () => {
    const results = carrying("outcome");
    const noResult = `${GOLDEN} holds no statement result, so the write half is uncompared`;
    expect(results.length, noResult).toBeGreaterThan(0);

    for (const { name, document } of results) {
      const result = decodeResult(document);
      expect(result, `${name} carried a result this build cannot read`).not.toBe(Unreadable);
      if (result !== Unreadable && result.outcome === "rows") {
        const rows = record(document)["rows"];
        expect(result.rows.length, `${name} names rows this build dropped`).toBe(
          Array.isArray(rows) ? rows.length : 0,
        );
      }
    }
  });

  it("subscribes to the event name the service publishes a push query's rows under", () => {
    /*
     * House rule 12 on the half of the ksqlDB wire that is not a document body. `openPushQuery`
     * listens on `KSQL_ROW_EVENT_NAME`; the service renders the frame under `SseEventName.Row`;
     * this is the only place in the repository the two meet. Without it the name is a hand-copied
     * mirror, which is what `ALERTS_EVENT_NAME` is and why it is the standing example.
     */
    const rows = frames(KSQL_ROW_EVENT_NAME);
    expect(
      rows.length,
      `${GOLDEN} holds no frame published under '${KSQL_ROW_EVENT_NAME}', so either the ` +
        "event name " +
        "this build listens on is wrong or the service committed no stream frame",
    ).toBeGreaterThan(0);

    for (const { name, document } of rows) {
      const row = decodeQueryRow(record(document)["data"]);
      expect(row, `${name}'s row did not decode`).not.toBe(Unreadable);
    }
  });

  it("reads the column list off the phase frame the service commits", () => {
    // `phase` is ADR-035's shared name and the kernel delivers it through its own callback, so this
    // is the only place the browser's reading of that payload meets the service's writing of it.
    const headers = frames("phase");
    const noPhase = `${GOLDEN} holds no 'phase' frame, so the column list is uncompared`;
    expect(headers.length, noPhase).toBeGreaterThan(0);

    for (const { name, document } of headers) {
      const columns = decodeQueryHeader(record(document)["data"]);
      expect(columns, `${name}'s phase frame did not decode`).not.toBe(Unreadable);
    }
  });

  it("keeps this package's own copies byte-identical to the service's goldens", () => {
    const committed = goldens();
    const mine = readdirSync(DOCUMENTS).filter((name) => name.endsWith(".json"));
    const shared = mine.filter((name) => committed.some((one) => one.name === name));

    /*
     * A fixture with no golden behind it is a *third* opinion about the wire — the shape
     * `e2e/connect.spec.ts` had, which kept M9 open for a wave while every unit suite was green. If
     * this fails, the names have parted company and this package's documents should be renamed to
     * the service's, not the other way round: the service is the encoder.
     */
    expect(
      shared.length,
      `none of this package's ${mine.length} documents shares a name with a golden in ${GOLDEN}`,
    ).toBeGreaterThan(0);

    for (const name of shared) {
      const golden = committed.find((one) => one.name === name);
      const mine = readFileSync(join(DOCUMENTS, name), "utf8");
      expect(mine, `${name} has drifted from the golden`).toBe(golden?.text);
    }
  });

  it("holds no plan document that no encoder produced", () => {
    /*
     * W10-06. `statement-plan-push-query.json` lived in `documents/` and was written by hand: it
     * carried `"warnings": []` where `KsqlUseCases.plan` gives a push query exactly one warning,
     * and nothing compared the two. A hand-written file in this directory is indistinguishable
     * from a captured one, which is why house rule 12 exists — so the plan documents are held
     * to their goldens by name, and a new one with nothing behind it is a red case rather than a
     * fixture somebody trusts.
     *
     * Plans specifically, and not every document here: `objects-forbidden.json` and
     * `objects-empty.json` are hand-made on purpose and named as such in `ksql.test.tsx`'s header —
     * they are states a working ksqlDB cannot be put into to be captured. A plan has no such
     * excuse; `services/ksql` can render one for any statement it is given.
     */
    const committed = goldens().map((one) => one.name);
    const plans = readdirSync(DOCUMENTS).filter(
      (name) => name.startsWith("statement-plan") && name.endsWith(".json"),
    );
    expect(plans.length, "this package holds no plan document at all").toBeGreaterThan(0);

    const invented = plans.filter((name) => !committed.includes(name));
    expect(
      invented,
      `${invented.join(", ")} is a plan document with no golden behind it: either ` +
        "services/ksql/contract commits the golden, or the case that needs it derives the " +
        "document from one that exists and says which fields it changed (testing.ts's " +
        "pushQueryPlan)",
    ).toEqual([]);
  });

  it("derives the push query's plan from a golden, changing only two fields", () => {
    /*
     * The document the cases route a push query on. There is no golden for it — the service commits
     * no plan for a `SELECT … EMIT CHANGES` — so it is the *harmless* plan golden with the
     * statement and the shape replaced, and this case is what stops that derivation growing a third
     * field. `warnings` in particular: the real document carries `KsqlUseCases.PushQueryElsewhere`,
     * and copying that sentence here by eye would be a second copy of a Scala constant with nothing
     * comparing the two.
     */
    const golden = goldens().find((one) => one.name === "statement-plan-harmless.json");
    expect(golden, "the harmless plan golden is gone, so the derivation has no base").toBeDefined();

    const base = record(golden?.document);
    const derived = record(pushQueryPlan);
    const keys = [...new Set([...Object.keys(base), ...Object.keys(derived)])];
    const changed = keys
      .filter((key) => JSON.stringify(base[key]) !== JSON.stringify(derived[key]))
      .sort();
    expect(changed, "the derived push-query plan diverges from its golden by more than its shape")
      .toEqual(["shape", "statement"]);

    // And it is still a plan this build can read, which is the only reason it exists.
    expect(decodePlan(pushQueryPlan)).not.toBe(Unreadable);
  });
});

/**
 * How many entries of a raw list carry a name.
 *
 * Entries without one are not counted, because the decoder drops those on purpose — an object with
 * no name is unactionable, and every sentence this screen writes about one names it. Counting them
 * would make a correct decoder fail; not counting the rest would let a broken one pass.
 */
function named(entries: unknown): number {
  return Array.isArray(entries)
    ? entries.filter((entry) => typeof record(entry)["name"] === "string").length
    : 0;
}
