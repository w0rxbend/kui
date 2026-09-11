import type { Meta, StoryObj } from "storybook-solidjs-vite";

import { KsqlResult } from "./KsqlResult.jsx";
import { ConfirmStatement } from "./ConfirmStatement.jsx";
import { RunningQueries } from "./RunningQueries.jsx";
import {
  appendRow,
  endLive,
  interrupt,
  regionFor,
  withColumns,
  MAX_RESULT_ROWS,
  OPENING,
  type ResultRegion,
} from "./model.js";
import { decodeObjects, decodePlan, decodeResult, sectionOf, Unreadable } from "./wire.js";
import objectsResponse from "./documents/objects-response.json" with { type: "json" };
import planDrop from "./documents/statement-plan.json" with { type: "json" };
import resultRows from "./documents/statement-rows.json" with { type: "json" };
import resultStatus from "./documents/statement-status.json" with { type: "json" };

/**
 * The ksqlDB result region (`SCREENS-V4.md` §7.9), in every state it has.
 *
 * **There is no capture to hold beside these.** §3.16 draws the workspace with an unrun query and
 * `SCREENS.md` open finding 4 — restated as §7.9 — says the result region is *"still never shown:
 * columns, streaming rows, the row cap and the error rendering are all unspecified while `Run` is
 * drawn as an enabled control"*. ADR-056 is the decision these stories are the review of, and they
 * are the review that has to happen by eye: the cases assert the words and the state, and only a
 * person can say whether a live query reads as alive and a dead one reads as dead.
 *
 * The three worth staring at are {@link Streaming}, {@link Capped} and {@link Interrupted}. None of
 * them may draw a zero, and the last one keeps its rows on screen underneath the reason they
 * stopped — deleting the operator's only record of what the query was producing in order to show an
 * error message is the failure ADR-056 §4 is about.
 *
 * Every fixture is one of the **service's own** documents, byte-for-byte, decoded by the same
 * functions the screen calls. A story built from a literal is a drawing of what its author believed
 * the server sends, which is how a screen comes to be reviewed, approved and wrong — this package's
 * first draft invented five fields and its stories would have looked perfect.
 */
const meta: Meta<typeof KsqlResult> = {
  title: "Screens/Ksql",
  component: KsqlResult,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => <div style={{ padding: "24px", "max-width": "1120px" }}>{Story() as never}</div>,
  ],
};

export default meta;
type Story = StoryObj<typeof KsqlResult>;

/** A golden's statement answer, decoded exactly as `runStatement` decodes it. */
function settled(document: unknown): ResultRegion {
  const result = decodeResult(document);
  if (result === Unreadable) {
    throw new Error("a story fixture that does not decode — fix the fixture, not this line");
  }
  return regionFor(result);
}

/** A live push query holding `count` rows of the committed pull-query result. */
function live(count: number): ResultRegion {
  let region = withColumns(OPENING, ["ID", "TOTAL", "NOTE"]);
  for (let index = 0; index < count; index += 1) {
    region = appendRow(region, [String(index), "42.50", index % 3 === 0 ? null : "gift wrap"]);
  }
  return region;
}

/**
 * A pull query that answered.
 *
 * The second row's `NOTE` is a SQL null — the column was selected and its value is nothing — and it
 * is drawn as the word rather than as a blank cell, because a blank cell reads as an empty string
 * and the two are different answers.
 */
export const Rows: Story = { render: () => <KsqlResult region={settled(resultRows)} /> };

/**
 * A `CREATE STREAM` that worked.
 *
 * The server's own sentence and nothing else. There is no table, because there are no rows — and an
 * empty grid under a statement that succeeded is the fourth kind of nothing this screen refuses to
 * draw.
 */
export const Status: Story = { render: () => <KsqlResult region={settled(resultStatus)} /> };

/** A push query whose stream has opened and which has produced nothing yet. Never a zero. */
export const NothingYet: Story = { render: () => <KsqlResult region={OPENING} /> };

/** A push query producing rows. The count is a floor and the sentence says so. */
export const Streaming: Story = { render: () => <KsqlResult region={live(4)} /> };

/**
 * A push query past the row cap.
 *
 * The window is on the **most recent** rows. A region that froze at 500 would look exactly like a
 * query that had finished, which is the most expensive wrong impression this screen can give — so
 * the rows keep moving and the sentence says how many are no longer held.
 */
export const Capped: Story = { render: () => <KsqlResult region={live(MAX_RESULT_ROWS + 12)} /> };

/** A push query the reader stopped. Info tone: it is not a fault. */
export const Ended: Story = {
  render: () => <KsqlResult region={endLive(live(3), "you stopped it.")} />,
};

/**
 * A push query whose stream died.
 *
 * The rows stay. They arrived and they were true, and they are the only record of what the query
 * was producing when the connection went — ADR-056 §4.
 */
export const Interrupted: Story = {
  render: () => <KsqlResult region={interrupt(live(3), "the stream ended unexpectedly")} />,
};

/** A statement that never ran. Distinct from the one above: there is nothing to keep. */
export const Failed: Story = {
  render: () => (
    <KsqlResult
      region={{
        kind: "failed",
        message: "line 1:8: mismatched input 'FORM' expecting 'FROM'",
        code: "KUI-UPSTREAM-KSQL",
      }}
    />
  ),
};

/**
 * The confirmation a `DROP … DELETE TOPIC` gets (ADR-045).
 *
 * The statement shown is the **service's** canonicalised text, which is what its token is bound to,
 * and the warning is the service's own sentence. A dialogue saying "this is destructive, continue?"
 * over a statement the reader can no longer see is a dialogue whose answer is always yes.
 */
export const Confirmation: StoryObj<typeof ConfirmStatement> = {
  render: () => {
    const plan = decodePlan(planDrop);
    if (plan === Unreadable) throw new Error("the plan fixture does not decode");
    return <ConfirmStatement plan={plan} onConfirm={() => {}} onCancel={() => {}} />;
  },
};

/**
 * What ksqlDB is running.
 *
 * No throughput and no lag anywhere on it: ksqlDB reports a query's record rate in its metrics
 * endpoints, which `services/ksql` does not read, so a `0 msg/s` here would be a fabricated zero.
 */
export const Queries: StoryObj<typeof RunningQueries> = {
  render: () => {
    const objects = decodeObjects(sectionPayload(objectsResponse));
    if (objects === Unreadable) throw new Error("the objects fixture does not decode");
    return <RunningQueries queries={objects.items.filter((one) => one.kind === "query")} />;
  },
};

function sectionPayload(document: unknown): unknown {
  const section = sectionOf(document, "objects");
  return section?.status === "ok" ? section.data : undefined;
}
