/**
 * The documents the **connect service's own encoder** renders, decoded by the browser that reads
 * them.
 *
 * ## Why this file exists, and what it caught the first time it ran
 *
 * House rule 12 is the only instruction in six waves that has measurably worked: one packet owns
 * the DTO, the decoder and the golden between them, and the binding case decodes the encoder's own
 * output. It closed M7 after two waves of prose contracts had produced two mismatched wires and a
 * card that lied about its source — wave 5 shipped `producers.data.{measuredBy, topics[]}` on the
 * server and a reader for `data.entries[]` in the browser, both green, and the card drew *"the
 * metrics source answered and named no producers"* over a source that had named five.
 *
 * This wave split that ownership across two packets: `services/connect` and its goldens are
 * W7-01's, and this package is W7-04's, with the contract stated in prose between them — precisely
 * the arrangement that failed twice. **It failed a third time and this suite is what caught it.**
 * This package's first draft read `{ connectors: { data: { items: [...] } } }`; the service renders
 * `{ connectors: { data: { workers: [ { connect, connectors: Section<{items, unreadable}> } ] } }
 * }`. The outer section key matched, so a decoder written the obvious way would have *succeeded*
 * and answered nothing, and the screen would have drawn "the Connect workers answered and named no
 * connectors" over a worker running three. Every unit case in this package was green at the time.
 *
 * The rule is one line:
 *
 *   **Every committed connect golden that carries a `connectors` section must reach a screen state
 *   through the same fetcher the product calls, must not be a failure, and must hold every
 *   connector the raw document names.**
 *
 * The last clause is not pedantry: without it a decoder that dropped every row would pass. And a
 * missing directory is a failure and never a skip — a suite that quietly passes when its fixture
 * has moved is the failure mode the whole file is about, and it would report green over a browser
 * and a service that have never met.
 *
 * If it is red because a document decodes to `failed`, the two sides disagree about the shape of
 * that document and one of them is wrong. `wire.ts`'s header says which fields this side reads.
 */
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { fetchConnectors } from "./data.js";
import { allConnectors, allNotDescribed } from "./model.js";
import { CONNECTORS_SECTION_KEY } from "./wire.js";
import { serving } from "./testing.js";

/** The connect contract module's committed documents, four directories up from this file. */
const GOLDEN = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "..",
  "..",
  "services",
  "connect",
  "contract",
  "test",
  "resources",
  "golden",
);

interface Golden {
  readonly name: string;
  readonly document: unknown;
}

/** Every committed document that carries a connectors section, or a thrown, named failure. */
function goldens(): readonly Golden[] {
  if (!existsSync(GOLDEN)) {
    throw new Error(
      `There are no connect golden documents at ${GOLDEN}, so nothing in this browser has been ` +
        "compared against the shape the connect service actually renders. They are rendered from " +
        "the service's own encoder and committed by services/connect/contract (W7-01 item 7, " +
        "the " +
        "shape services/metrics/contract already has). Until they exist, feature-connect's " +
        "wire is " +
        "transcribed from prose — which is exactly how wave 5 shipped two halves of one wire " +
        "that " +
        "never met, with every other gate green.",
    );
  }

  const found = readdirSync(GOLDEN)
    .filter((name) => name.endsWith(".json"))
    .map((name) => ({
      name,
      document: JSON.parse(readFileSync(join(GOLDEN, name), "utf8")) as unknown,
    }))
    .filter(({ document }) => hasSection(document));

  if (found.length === 0) {
    throw new Error(
      `${GOLDEN} holds no document carrying a '${CONNECTORS_SECTION_KEY}' section, so the ` +
        "connector " +
        "list this screen is built on has no rendered example to be checked against.",
    );
  }
  return found;
}

function hasSection(document: unknown): boolean {
  return (
    typeof document === "object" &&
    document !== null &&
    CONNECTORS_SECTION_KEY in (document as Record<string, unknown>)
  );
}

describe("a document the connect service rendered", () => {
  it("decodes into a connector listing in the browser", async () => {
    const documents = goldens();

    for (const { name, document } of documents) {
      const state = await fetchConnectors(serving(document).api, "quickstart");
      /*
       * Not "the decode succeeded". The decode succeeded in wave 5 too, and answered an empty
       * array. `fetchConnectors` refuses a payload it does not recognise, so `failed` here means
       * this build and the service disagree about the document's shape — which is exactly what this
       * suite found on its first run against these files, and why `wire.ts` reads
       * `data.workers[].connectors.data.items` rather than the `data.items` its first draft read.
       */
      expect(
        state.kind,
        `${name} did not reach a readable state: ${JSON.stringify(state)}`,
      ).not.toBe("failed");

      if (state.kind === "ready" || state.kind === "stale") {
        /*
         * Every connector the document names is a connector the browser holds, counted from the raw
         * JSON rather than from the decoder. A decoder that dropped every row would otherwise
         * satisfy the line above — which is the shape of the wave-5 defect, not a pedantic extra.
         */
        expect(
          allConnectors(state.value).length,
          `${name} names connectors this build did not hold`,
        ).toBe(countIn(document, "items", hasName));
        expect(
          allNotDescribed(state.value).length,
          `${name} names connectors the worker would not describe, and this build dropped them`,
        ).toBe(countIn(document, "unreadable", () => true));
        const dropped = `${name} names Connect clusters this build dropped`;
        expect(state.value.workers.length, dropped).toBe(rawWorkers(document).length);
      }
    }
  });
});

/** The document's `workers` array, read straight out of the JSON. */
function rawWorkers(document: unknown): readonly unknown[] {
  const section = (document as Record<string, unknown>)[CONNECTORS_SECTION_KEY];
  const data = (section as Record<string, unknown> | undefined)?.["data"];
  const workers = (data as Record<string, unknown> | undefined)?.["workers"];
  return Array.isArray(workers) ? workers : [];
}

/** How many entries the raw document holds under one key of every worker's inner section. */
function countIn(document: unknown, key: string, keep: (entry: unknown) => boolean): number {
  return rawWorkers(document).reduce<number>((total, worker) => {
    const section = (worker as Record<string, unknown>)["connectors"];
    const data = (section as Record<string, unknown> | undefined)?.["data"];
    const entries = (data as Record<string, unknown> | undefined)?.[key];
    return total + (Array.isArray(entries) ? entries.filter(keep).length : 0);
  }, 0);
}

function hasName(entry: unknown): boolean {
  return (
    typeof entry === "object" &&
    entry !== null &&
    typeof (entry as { name?: unknown }).name === "string"
  );
}
