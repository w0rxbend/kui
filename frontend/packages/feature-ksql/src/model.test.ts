/**
 * The result region's rules, driven directly.
 *
 * ADR-056's four decisions are all here rather than in a component, so each one can be put into the
 * state it is about without arranging markup. A rule that can only be reached through a render is a
 * rule whose case tends to assert the markup instead.
 */
import { describe, expect, it } from "vitest";

import {
  appendRow,
  cellText,
  endLive,
  interrupt,
  isLive,
  ksqlVoice,
  regionFor,
  rowSentence,
  unreadableSentence,
  withColumns,
  CELL_ABSENT,
  MAX_NAMED_UNREADABLE,
  MAX_RESULT_ROWS,
  NO_ROWS,
  NO_ROWS_YET,
  OPENING,
  type ResultRegion,
} from "./model.js";
import type { KsqlObjectRow, KsqlObjects } from "./wire.js";

function rows(region: ResultRegion, count: number): ResultRegion {
  let held = region;
  for (let index = 0; index < count; index += 1) held = appendRow(held, [String(index)]);
  return held;
}

describe("a push query that is still arriving", () => {
  it("takes its column list from the first phase frame and keeps it", () => {
    const after = withColumns(withColumns(OPENING, ["REGION"]), ["SOMETHING_ELSE"]);
    if (after.kind !== "streaming") throw new Error("expected a live region");
    // A server that re-sent a different column list mid-query would otherwise silently re-align
    // every row already on screen against headings they were never produced under.
    expect(after.columns).toEqual(["REGION"]);
  });

  it("says how many rows have arrived and that the query is still running", () => {
    expect(rowSentence(rows(OPENING, 2))).toBe("2 rows so far, and the query is still running.");
  });

  it("says that nothing has arrived yet rather than drawing a zero", () => {
    /*
     * The product's central promise, on the one screen where the reader is most likely to read an
     * empty table as an answer. "0 rows" over a push query that has been open for two seconds is a
     * claim about the data; this is a claim about the query.
     */
    expect(rowSentence(OPENING)).toBe(NO_ROWS_YET);
  });
});

describe("the row cap", () => {
  it("keeps the most recent rows and says how many it dropped", () => {
    const after = rows(OPENING, MAX_RESULT_ROWS + 3);
    if (after.kind !== "streaming") throw new Error("expected a live region");

    expect(after.rows).toHaveLength(MAX_RESULT_ROWS);
    expect(after.dropped).toBe(3);
    /*
     * The window is on the *most recent* rows, not the first. A region that froze after 500 rows
     * would look exactly like a query that had finished, which is the most expensive wrong
     * impression this screen can give — so the last row in is the last row held.
     */
    expect(after.rows[after.rows.length - 1]).toEqual([String(MAX_RESULT_ROWS + 2)]);
    expect(after.rows[0]).toEqual(["3"]);
    expect(rowSentence(after)).toContain("3 rows arrived earlier and are no longer held");
  });

  it("says nothing about a cap that has not been reached", () => {
    expect(rowSentence(rows(OPENING, 2))).not.toContain("no longer held");
  });
});

describe("a query that stops", () => {
  it("keeps its rows and reports the reason when it ends", () => {
    const ended = endLive(rows(OPENING, 4), "you stopped it.");
    if (ended.kind !== "ended") throw new Error("expected an ended region");
    expect(ended.rows).toHaveLength(4);
    expect(ended.reason).toBe("you stopped it.");
    expect(rowSentence(ended)).toBe("4 rows before the query ended.");
  });

  it("keeps every row already received when the server goes away", () => {
    /*
     * ADR-056's third decision, and the one that is easiest to get wrong: the rows arrived and were
     * true, and they are the only record the operator has of what the query was producing when the
     * connection died. An error panel that replaced them would delete evidence in order to show an
     * error message.
     */
    const broken = interrupt(rows(OPENING, 4), "the stream ended unexpectedly");
    if (broken.kind !== "interrupted") throw new Error("expected an interrupted region");
    expect(broken.rows).toHaveLength(4);
    expect(broken.message).toBe("the stream ended unexpectedly");
    expect(rowSentence(broken)).toBe("4 rows before the stream ended.");
  });

  it("becomes a plain failure when nothing was streaming", () => {
    // A statement that never ran is not a query that died: one wants the reason and a re-run, the
    // other wants the rows kept. Collapsing them puts an empty table under an error message.
    expect(interrupt({ kind: "running" }, "the server refused it", "KUI-FORBIDDEN")).toEqual({
      kind: "failed",
      message: "the server refused it",
      code: "KUI-FORBIDDEN",
    });
  });

  it("ignores a row that arrives after the query ended", () => {
    // The region has already said how many rows the query delivered; a late row makes that sentence
    // false, and a reader who watched the count move after "no longer running" has been lied to.
    const ended = endLive(rows(OPENING, 2), "you stopped it.");
    expect(appendRow(ended, ["99"])).toBe(ended);
  });
});

describe("a statement that is not a push query", () => {
  it("says that a pull query matched nothing rather than drawing a zero", () => {
    const region = regionFor({
      statement: "SELECT 1;",
      shape: "pull_query",
      outcome: "rows",
      columns: ["A"],
      rows: [],
      message: undefined,
      entity: undefined,
      executedAt: "t",
    });
    expect(rowSentence(region)).toBe(NO_ROWS);
  });

  it("counts a single row in the singular", () => {
    const region = regionFor({
      statement: "SELECT 1;",
      shape: "pull_query",
      outcome: "rows",
      columns: ["A"],
      rows: [["x"]],
      message: undefined,
      entity: undefined,
      executedAt: "t",
    });
    expect(rowSentence(region)).toBe("1 row.");
  });

  it("draws a status answer as a settled region rather than a live one", () => {
    const region = regionFor({
      statement: "CREATE STREAM S;",
      shape: "statement",
      outcome: "status",
      columns: [],
      rows: [],
      message: "Stream created.",
      entity: "S",
      executedAt: "t",
    });
    expect(isLive(region)).toBe(false);
    expect(region).toMatchObject({ kind: "status", message: "Stream created.", entity: "S" });
  });
});

describe("a cell", () => {
  it("tells an absent cell apart from an empty value", () => {
    expect(cellText(undefined)).toBe(CELL_ABSENT);
    expect(cellText("")).toBe("");
    expect(cellText("0")).toBe("0");
  });
});

describe("the voice line", () => {
  const item = (kind: string, name: string): KsqlObjectRow => ({
    kind: kind as KsqlObjectRow["kind"],
    rawKind: kind,
    name,
    topic: undefined,
    format: undefined,
    windowed: undefined,
    sinks: [],
    statement: undefined,
    partitions: undefined,
    replication: undefined,
  });

  const listing = (
    kinds: readonly string[],
    unreadable: readonly string[] = [],
    truncated = 0,
  ): KsqlObjects => ({
    items: kinds.map((kind, index) => item(kind, `O${index}`)),
    unreadable: [...unreadable],
    truncated,
  });

  it("counts what the server named", () => {
    expect(ksqlVoice(listing(["stream", "stream", "table", "query"]))).toBe(
      "2 streams and 1 table, 1 query running.",
    );
  });

  it("says a server running no query said so, rather than printing a zero", () => {
    expect(ksqlVoice(listing(["stream"]))).toBe("1 stream, no query is running.");
  });

  it("says that a server answered and named nothing", () => {
    expect(ksqlVoice(listing([]))).toBe("This ksqlDB server answered and named nothing.");
  });

  it("names the rows it could not describe and the objects the service cut", () => {
    /*
     * Both are facts about what is *missing* from the list. A screen that drew a truncated list as
     * a complete one leaves an operator concluding a stream does not exist — which is the case
     * `KsqlObjectsDto.truncated` was put on the wire for.
     */
    const sentence = ksqlVoice(listing(["stream"], ["ODD_ROW"], 12));
    expect(sentence).toContain("1 row the server returned could not be described by this build.");
    expect(sentence).toContain("12 objects were left out to keep the answer bounded");
  });

  it("says nothing about a truncation that did not happen", () => {
    // Zero is a measured zero here, and a sentence about a cap that was not reached is noise —
    // noise is what stops the sentence being read on the run where it matters.
    expect(ksqlVoice(listing(["stream"]))).not.toContain("left out");
  });
});

describe("the banner over rows this build could not describe", () => {
  it("names them all while there are few, which is every listing this product has seen", () => {
    expect(unreadableSentence(["(a table the ksqlDB cluster did not name)"])).toBe(
      "KUI could not describe (a table the ksqlDB cluster did not name). This build and the " +
        "ksqlDB server disagree about what those rows are, so they are named here rather than " +
        "left out of the list.",
    );
  });

  it("stops naming them at the bound and counts the rest out loud", () => {
    /*
     * W10-06. `KsqlObjects.of` bounds `items` at 500 and passes `unreadable` through untouched, so
     * the length of this list is whatever the ksqlDB server and this build disagree about — and the
     * banner used to `join(", ")` every one of them into one sentence at the top of the screen.
     * Ten thousand rows is not a hypothetical shape: it is one `SHOW TOPICS` on a shared cluster
     * against a build that does not know a kind.
     *
     * Counted rather than quietly cut. A sentence that stopped at ten names would be this banner
     * doing the thing the `unreadable` list exists to refuse — leaving rows out of a list without
     * saying so.
     */
    const many = Array.from({ length: 10000 }, (_, index) => `ROW_${index}`);
    const sentence = unreadableSentence(many);

    expect(sentence).toContain("ROW_0, ROW_1");
    expect(sentence, "the banner named more rows than the bound allows").not.toContain(
      `ROW_${MAX_NAMED_UNREADABLE}`,
    );
    expect(sentence).toContain(`and ${many.length - MAX_NAMED_UNREADABLE} other rows`);
    // The whole sentence, not the list: a bounded banner that ran to a thousand characters would
    // have missed the point.
    expect(sentence.length).toBeLessThan(400);
  });

  it("names ten of them, counted out of the sentence and not derived from the bound", () => {
    /*
     * Every assertion in the case above is written in terms of `MAX_NAMED_UNREADABLE` itself, so
     * the bound cannot disagree with any of them: raising it from 10 to 30 keeps them all green.
     * The only absolute assertion up there is the 400-character length, which bounds the *sentence*
     * and not the roster — a three-fold loosening of the number of names slips under it.
     *
     * Ten is a chosen number — its own header says so — and a chosen number is asserted where it is
     * chosen. Counting the names out of the finished sentence, rather than reading the constant
     * again, is what makes this a case about the screen and not about arithmetic.
     */
    expect(MAX_NAMED_UNREADABLE).toBe(10);

    const sentence = unreadableSentence(Array.from({ length: 50 }, (_, index) => `ROW_${index}`));
    const named = sentence.slice(0, sentence.indexOf(", and ")).match(/ROW_\d+/g) ?? [];
    expect(named).toHaveLength(10);
    expect(sentence).toContain("and 40 other rows");
  });

  it("counts a single extra row as one row", () => {
    const sentence = unreadableSentence(
      Array.from({ length: MAX_NAMED_UNREADABLE + 1 }, (_, index) => `ROW_${index}`),
    );
    expect(sentence).toContain("and 1 other row.");
  });
});
