/**
 * The screen, drawn over the documents the **server's own encoder** rendered.
 *
 * ## What this adds that the kernel's golden suite does not
 *
 * `packages/kernel/src/data/alerts/wire.golden.test.ts` already reads
 * `services/alerts/contract/test/resources/golden/*.json` and checks that the browser's decoder
 * agrees with `AlertDtos`' encoder about what is in them. That is one half of house rule 12 and it
 * is the half that catches a renamed field.
 *
 * It does not catch the other half. A document can decode perfectly and still be drawn as a
 * sentence that is false about the cluster it came from — which is how *"the metrics source
 * answered and named no producers"* appeared over a source that had named five. So this file takes
 * the same files and puts them through the components: the card, its pill, its sentence and the
 * rules panel. A fixture in `src/documents/` is this packet's belief about what the service sends;
 * these three files are what it sent.
 *
 * ## What the three goldens are, and what they are not
 *
 * `services/alerts` commits six documents and three of them are feeds: one answered feed with
 * rows, one whose rules ran and opened nothing while a fourth could not look, and one over a
 * cluster the rules have never run on. The other three are the stream frame, the acknowledgement
 * response and a change record, which this screen does not draw. Seven of the states in
 * `src/documents/` — a second page of a long feed, an unrecognised vocabulary, a rule this
 * deployment does not run, a stale evaluation — have no golden, and their *content* is therefore
 * still this packet's. That is stated here rather than left for an adversary to find.
 *
 * ## What is checked across all of them anyway, and why it had to be
 *
 * Two properties of a feed do not depend on having a golden of that state, and both were broken in
 * this directory until wave 8. The **rule roster** is `AlertVocabulary`'s and not a fixture
 * author's: six documents named `log-directory-usage` and one invented `connector-task-failure`,
 * ids `AlertRule.fromWire` rejects. And the **open count** is the sum of what the rules that
 * measured reported: `events-unknown-vocabulary.json` carried `openCount: 1` over an empty rules
 * list. Both are asserted below over every file in `src/documents/`, read off disk, against a
 * roster taken from the goldens — so a document added tomorrow is covered by nobody remembering.
 *
 * A missing file fails loudly and never skips: a suite that quietly passes when its fixture has
 * moved is the failure mode the whole file is about.
 */
import { describe, expect, it } from "vitest";
import { flush } from "solid-js";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { mount } from "./testing.js";
import { feedState, pageOf } from "./fixtures.jsx";
import { AlertsFeed, RuleReports, NOT_EVALUATED, NO_EVENTS, SEVERITIES } from "./index.jsx";

/** The alerts contract module's committed documents, four directories up from this file. */
const GOLDEN = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "..",
  "..",
  "services",
  "alerts",
  "contract",
  "test",
  "resources",
  "golden",
);

/** One committed document, parsed. A missing file is a failure and never a skip. */
function golden(name: string): unknown {
  const path = join(GOLDEN, name);
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch (cause) {
    throw new Error(
      `${name} is not where this suite expects the alerts goldens to be (${path}). ` +
        `They are committed by services/alerts/contract; if they moved, this suite moves too.`,
      { cause },
    );
  }
  return JSON.parse(text) as unknown;
}

/** This package's own fixtures, read off disk so an added document joins these cases unasked. */
const DOCUMENTS = join(dirname(fileURLToPath(import.meta.url)), "documents");

/**
 * The one fixture written as a **newer service's** answer, and the reason it is named here.
 *
 * `events-unknown-vocabulary.json` exists to draw a severity, a tone, a category and a rule that
 * this build has never heard of, which is the forward-compatibility promise `events.ts` makes and
 * the thing no document off today's service can express. Naming it once, here, is what lets the
 * case below be a real roster check for every other file rather than a check with a hole in it.
 */
const FROM_A_LATER_SERVICE = "events-unknown-vocabulary.json";

interface RuleRow {
  readonly rule: string;
  readonly evaluation: { readonly status: string; readonly data?: { readonly openEvents: number } };
}

interface FeedData {
  readonly openCount: number;
  readonly rules: readonly RuleRow[];
}

/** The `data` of a feed document, or `undefined` when the section is a refusal and carries none. */
function feedData(document: unknown): FeedData | undefined {
  const events = (document as { events?: { data?: FeedData } }).events;
  return events?.data;
}

/** One of this package's fixtures, parsed. Missing is a failure, exactly as a golden's is. */
function fixture(name: string): unknown {
  return JSON.parse(readFileSync(join(DOCUMENTS, name), "utf8")) as unknown;
}

/** Every fixture in `src/documents/`, by name, so a new one is covered by nobody remembering. */
function fixtureNames(): string[] {
  return readdirSync(DOCUMENTS)
    .filter((name) => name.endsWith(".json"))
    .sort();
}

/** A clock held still. The goldens are dated 2026-09-03; this is an hour after their last event. */
const NOW = new Date("2026-09-03T11:11:12Z");

function rowsIn(container: HTMLElement): HTMLElement[] {
  return [...container.querySelectorAll<HTMLElement>('[data-testid="alert-row"]')];
}

function pillIn(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>('[data-testid="alerts-open-count"]');
}

function ruleRow(container: HTMLElement, rule: string): HTMLElement | undefined {
  return [...container.querySelectorAll<HTMLElement>('[data-testid="alert-rule"]')].find(
    (candidate) => candidate.dataset["rule"] === rule,
  );
}

describe("the alerts screen, over the documents services/alerts renders", () => {
  it("draws the feed the encoder produced, with the encoder's own count", async () => {
    const document = golden("alerts-feed-response.json");
    const page = await pageOf(document);
    // The figures come off the file rather than being written here, so that a golden regenerated
    // with different numbers moves this case's expectations with it and never past it.
    expect(page.items).toHaveLength(2);
    expect(page.openCount).toBe(1);

    const state = await feedState(document);
    const { container, dispose } = mount(() => (
      <AlertsFeed wide state={state} onAcknowledge={() => {}} now={() => NOW} />
    ));
    await flush();

    expect(rowsIn(container)).toHaveLength(page.items.length);
    expect(pillIn(container)?.textContent).toContain(`${String(page.openCount)} open`);
    // The rows the encoder wrote, by their own titles. `2 partitions offline` is open; the log
    // directory is acknowledged, and the tag beside it names the principal who did it.
    expect(container.textContent).toContain("2 partitions offline");
    expect(container.textContent).toContain("Log directory /var/lib/kafka is 82% full on broker 1");
    expect(container.textContent).toContain("Acknowledged");
    expect(container.textContent).toContain("By ada");
    // One page, two rows, one open — and the pill is not two.
    expect(pillIn(container)?.textContent).not.toContain("2 open");
    dispose();
  });

  it("draws every severity the service opens events at, and knows all of them", async () => {
    const page = await pageOf(golden("alerts-feed-response.json"));
    const sent = new Set(page.items.map((event) => event.severity));
    expect(sent.size).toBeGreaterThan(0);
    /*
     * Every severity in a document the service actually rendered is one this build's filter offers.
     * The converse — a chip for a severity nothing opens — is `alertsRoute.test.tsx`'s chip-bar
     * case; between them the list can neither grow a chip that matches nothing nor lose one that
     * would hide a row.
     */
    for (const severity of sent) {
      expect(SEVERITIES as readonly string[]).toContain(severity);
    }
  });

  it("says the rules have never run here rather than that the cluster is well", async () => {
    const document = golden("alerts-feed-unevaluated.json");
    const page = await pageOf(document);
    // The document the whole promise rests on: a zero beside no evaluation at all.
    expect(page.openCount).toBe(0);
    expect(page.evaluatedAt).toBeUndefined();

    const state = await feedState(document);
    const { container, dispose } = mount(() => (
      <AlertsFeed wide state={state} now={() => NOW} />
    ));
    await flush();

    expect(container.textContent).toContain(NOT_EVALUATED);
    expect(container.textContent).not.toContain(NO_EVENTS);
    expect(pillIn(container)?.textContent).toContain("Not evaluated yet");
    // The two renderings of that zero which would be false, in the encoder's own document.
    expect(container.textContent).not.toContain("None open");
    expect(container.textContent).not.toContain("0 open");
    dispose();
  });

  it("keeps the rule that could not look apart from the three that found nothing", async () => {
    const document = golden("alerts-feed-blind-rule.json");
    const page = await pageOf(document);
    const dark = page.rules.find((report) => report.status !== "ok");
    expect(dark?.rule).toBe("offline-partitions");
    expect(dark?.openEvents).toBeNull();

    const { container, dispose } = mount(() => <RuleReports reports={page.rules} />);
    await flush();

    const row = ruleRow(container, "offline-partitions");
    expect(row?.textContent).toContain("Not evaluated");
    // The service's own sentence, off the file: `kafka-admin could not be reached`.
    expect(row?.textContent).toContain("kafka-admin could not be reached");
    expect(row?.textContent).not.toContain("Nothing open");

    // And the three that did measure say so, or the assertions above would pass on a panel that had
    // stopped drawing figures altogether.
    for (const measured of page.rules.filter((report) => report.status === "ok")) {
      expect(ruleRow(container, measured.rule)?.textContent).toContain("Nothing open.");
    }
    expect(page.rules.filter((report) => report.status === "ok")).toHaveLength(3);
    dispose();
  });

  /**
   * The rule ids in this package's fixtures are the service's, and one file is allowed to differ.
   *
   * `AlertVocabulary.scala` ships exactly four rules and `AlertsMapping` sends `AlertRule.All` on
   * every feed, so a rule id is not a fixture author's choice. Six of the nine documents here named
   * `log-directory-usage` and one invented `connector-task-failure` — ids `AlertRule.fromWire`
   * rejects, on documents that therefore could not have come from the service at all. Nothing
   * noticed, because the browser's decoder takes `rule` as a free string on purpose: that is what
   * makes a *later* service readable, and it is also what made these six invisible.
   *
   * So the roster is taken from the goldens rather than written here, and the one file that is
   * deliberately a later service's answer is named once at the top of this file. Both halves are
   * asserted: no other fixture may carry an unknown id, and that one **must** — otherwise the day
   * somebody quietly normalises it, the forward-compatibility cases start proving nothing.
   */
  it("names only rules services/alerts has, except in the one written as a later one", () => {
    const known = new Set<string>();
    for (const name of ["alerts-feed-response.json", "alerts-feed-blind-rule.json"]) {
      for (const row of feedData(golden(name))?.rules ?? []) known.add(row.rule);
    }
    // Four, and the case is worth nothing if the goldens stopped carrying rules at all.
    expect([...known].sort()).toEqual([
      "disk-usage",
      "offline-partitions",
      "stuck-rebalance",
      "under-replicated-partitions",
    ]);

    const strangers = new Map<string, string[]>();
    for (const name of fixtureNames()) {
      const unknown = (feedData(fixture(name))?.rules ?? [])
        .map((row) => row.rule)
        .filter((rule) => !known.has(rule));
      if (unknown.length > 0) strangers.set(name, unknown);
    }

    expect([...strangers.keys()]).toEqual([FROM_A_LATER_SERVICE]);
    expect(strangers.get(FROM_A_LATER_SERVICE)).toEqual(["connector-task-failure"]);
  });

  /**
   * The open count and the rules under it are one arithmetic, in both trees.
   *
   * `openCount` is `feed.openCount` and each row's `openEvents` is `feed.openByRule`, so on a
   * document the service rendered the second sums to the first — checked here against the goldens
   * so the claim is measured rather than asserted. A fixture that breaks it is a screen drawing a
   * pill and a rules panel that contradict each other, which is the one thing this whole card
   * exists to prevent; `events-unknown-vocabulary.json` carried `openCount: 1` over an empty rules
   * list in a set a previous pass had declared *"made internally consistent"*.
   *
   * A rule that could not look contributes nothing rather than a zero, which is the same
   * distinction the panel draws: `unavailable`, `stale` and `not_configured` carry no figure, so
   * they are not in the sum and cannot silently cover a missing one.
   */
  it("the open count is the sum of what the rules that measured reported, on both sides", () => {
    const reconciles = (document: unknown): { open: number; measured: number } | undefined => {
      const data = feedData(document);
      if (data === undefined) return undefined;
      const measured = data.rules
        .filter((row) => row.evaluation.status === "ok")
        .reduce((total, row) => total + (row.evaluation.data?.openEvents ?? 0), 0);
      return { open: data.openCount, measured };
    };

    const checked: string[] = [];
    const feeds = [
      "alerts-feed-response.json",
      "alerts-feed-blind-rule.json",
      "alerts-feed-unevaluated.json",
    ];
    for (const name of feeds) {
      const sums = reconciles(golden(name));
      expect(sums, `${name} carries a feed`).toBeDefined();
      expect(sums?.measured, `${name}: the rules and the count disagree`).toBe(sums?.open);
      checked.push(name);
    }
    for (const name of fixtureNames()) {
      const sums = reconciles(fixture(name));
      // Three of the fixtures are refusals and carry no `data` at all, which is not a disagreement.
      if (sums === undefined) continue;
      expect(sums.measured, `${name}: the rules and the count disagree`).toBe(sums.open);
      checked.push(name);
    }
    // Named rather than counted loosely: a `continue` that skipped everything would pass silently.
    expect(checked.length).toBeGreaterThanOrEqual(9);
  });

  it("fails rather than skips when a golden is not where it is expected", () => {
    expect(() => golden("alerts-feed-that-does-not-exist.json")).toThrow(
      /is not where this suite expects the alerts goldens to be/,
    );
  });
});
