import type { Meta, StoryObj } from "storybook-solidjs-vite";

import { AlertsFeed } from "./AlertsFeed.jsx";
import { RuleReports } from "./RuleReports.jsx";
import { FromDocument } from "./fixtures.jsx";
import openDocument from "./documents/events-open.json" with { type: "json" };
import pagedDocument from "./documents/events-paged.json" with { type: "json" };
import emptyDocument from "./documents/events-empty.json" with { type: "json" };
import neverEvaluatedDocument from "./documents/events-never-evaluated.json" with { type: "json" };
import unknownDocument from "./documents/events-unknown-vocabulary.json" with { type: "json" };
import darkRuleDocument from "./documents/events-dark-rule.json" with { type: "json" };
import staleRuleDocument from "./documents/events-stale-rule.json" with { type: "json" };

/**
 * The alerts card (`SCREENS-V4.md` §3.8), in every state it has.
 *
 * `TheScreenshot` is the one to hold beside `M05`. Everything after it is a state a healthy cluster
 * cannot be put into — a service that is not answering, a deployment that runs no alerts service at
 * all, a severity this build has never heard of, a page whose count is larger than its rows — and
 * those are the states this project's defects have always lived in.
 *
 * Every story renders a **document**, and it renders it through the same `@kui/kernel` store the
 * shell drives rather than through a decoder of this package's own: the feature had a second reader
 * of this wire until wave 7 and nothing in the product ever called it. A story built from a literal
 * is a drawing of what the author believed the server sends; a story built from a second decoder is
 * a drawing of what one of the two readers believed it.
 */
const meta: Meta<typeof AlertsFeed> = {
  title: "Screens/Alerts",
  component: AlertsFeed,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => <div style={{ padding: "24px", "max-width": "760px" }}>{Story() as never}</div>,
  ],
};

export default meta;
type Story = StoryObj<typeof AlertsFeed>;

/** A clock held still, so every story draws the same ages every time it is screenshotted. */
const NOW = new Date("2026-03-04T09:41:00Z");
const now = (): Date => NOW;

const noop = (): void => {};

/**
 * Five rows, three open, two resolved — and the two warnings carrying different glyphs.
 *
 * That pair is §3.9's correction drawn: a disk and a rebalance arrow at the same severity. A card
 * that picked its glyph from the severity would draw this story with two identical rows.
 */
export const TheScreenshot: Story = {
  render: () => (
    <FromDocument document={openDocument}>
      {(state) => <AlertsFeed state={state} onAcknowledge={noop} now={now} />}
    </FromDocument>
  ),
};

/** The Alerts screen's layout: full width, the age at the far edge. Same rows, two layouts (§3.8). */
export const FullWidth: Story = {
  render: () => (
    <FromDocument document={openDocument}>
      {(state) => <AlertsFeed wide state={state} onAcknowledge={noop} now={now} />}
    </FromDocument>
  ),
};

/**
 * A page of three from a feed with seven open events.
 *
 * The pill reads the service's figure. Counting the rows on screen would say "2 open" and would be
 * wrong in the reassuring direction on every cluster with more open events than fit a page.
 */
export const CountLargerThanThePage: Story = {
  render: () => (
    <FromDocument document={pagedDocument}>
      {(state) => <AlertsFeed state={state} onAcknowledge={noop} now={now} />}
    </FromDocument>
  ),
};

/** The service answered and is holding nothing. A sentence about the source, and no count. */
export const AnsweredAndEmpty: Story = {
  render: () => (
    <FromDocument document={emptyDocument}>
      {(state) => <AlertsFeed state={state} now={now} />}
    </FromDocument>
  ),
};

/**
 * The same empty card, over a cluster the rules have never run on.
 *
 * Hold it beside the story above: identical picture, opposite fact. One says the rules ran and
 * found nothing; this one says nobody has looked, and the pill says so rather than drawing a
 * reassuring zero. It is the whole of the product's central promise in two stories.
 */
export const NeverEvaluated: Story = {
  render: () => (
    <FromDocument document={neverEvaluatedDocument}>
      {(state) => <AlertsFeed state={state} now={now} />}
    </FromDocument>
  ),
};

/**
 * A deployment with no alerts service.
 *
 * The story draws **nothing**, and that is the assertion: ADR-032's `not_configured` is hidden, not
 * empty, and an empty card headed "Alerts & events" would send an operator hunting for an outage
 * that does not exist. The frame around it is the story's own padding.
 */
export const NoAlertsService: Story = {
  render: () => <AlertsFeed state={{ kind: "not-configured" }} now={now} />,
};

/** The service is not answering: its sentence, its code, and a retry that does something. */
export const NotAnswering: Story = {
  render: () => (
    <AlertsFeed
      state={{
        kind: "failed",
        message: "The alerts service did not answer.",
        code: "KUI-UPSTREAM-UNAVAILABLE",
      }}
      onRetry={noop}
      now={now}
    />
  ),
};

/**
 * A principal who may read alerts and not acknowledge them.
 *
 * **No retry, and the handler is passed on purpose.** `forbidden` is not a read that might work
 * next time; a Retry button here cannot work, and the story is drawn with `onRetry` given
 * so that what it shows is the card refusing to offer one rather than the card not having
 * been handed one.
 */
export const MayNotRead: Story = {
  render: () => <AlertsFeed state={{ kind: "forbidden" }} onRetry={noop} now={now} />,
};

/** The last answer KUI received, with the reason above it and no invented code beside it. */
export const Stale: Story = {
  render: () => (
    <FromDocument document={openDocument}>
      {(state) => (
        <AlertsFeed
          state={
            state.kind === "ready"
              ? {
                  kind: "stale",
                  value: state.value,
                  reason: "The alerts service has not answered since 09:38.",
                }
              : state
          }
          onAcknowledge={noop}
          now={now}
        />
      )}
    </FromDocument>
  ),
};

/** Still reading. Three skeleton lines, and no figure anywhere. */
export const Reading: Story = {
  render: () => <AlertsFeed state={{ kind: "loading" }} now={now} />,
};

/**
 * A severity and a category from a newer KUI than this browser.
 *
 * Drawn as unrecognised, in words, with the neutral mark — never mapped onto the nearest thing this
 * build does know. The words are what somebody quotes to whoever wrote the rule.
 */
export const AVocabularyThisBuildDoesNotKnow: Story = {
  render: () => (
    <FromDocument document={unknownDocument}>
      {(state) => <AlertsFeed state={state} onAcknowledge={noop} now={now} />}
    </FromDocument>
  ),
};

/** Acknowledgement refused for this principal: the control is disabled and says why. */
export const MayNotAcknowledge: Story = {
  render: () => (
    <FromDocument document={openDocument}>
      {(state) => (
        <AlertsFeed
          state={state}
          acknowledgeRefusal="This cluster is read-only, so nothing here can be acknowledged."
          now={now}
        />
      )}
    </FromDocument>
  ),
};

/**
 * The same disabled control with **no** reason supplied by the caller.
 *
 * It still says why. A disabled button with an empty tooltip cannot be told from a broken build,
 * and the card is mounted this way by anybody who draws it beside another card without threading a
 * refusal through — which is exactly what the dashboard does.
 */
export const MayNotAcknowledgeWithNoReasonGiven: Story = {
  render: () => (
    <FromDocument document={openDocument}>
      {(state) => <AlertsFeed state={state} now={now} />}
    </FromDocument>
  ),
};

/**
 * Every row on the page removed by the reader's own filter.
 *
 * A different sentence from an empty feed, and the difference is whose doing it was: this page
 * holds three events and the reader asked for resolved criticals, of which it holds none. Saying
 * "the alerts service is holding no events for this cluster" would blame the service for a chip.
 */
export const FilteredToNothing: Story = {
  render: () => (
    <FromDocument document={pagedDocument}>
      {(state) => (
        <AlertsFeed state={state} filter={{ severity: "critical", state: "resolved" }} now={now} />
      )}
    </FromDocument>
  ),
};

/** What KUI checked, with every rule answering. */
export const TheRulesThatRan: Story = {
  render: () => (
    <FromDocument document={openDocument}>
      {(state) => <RuleReports reports={state.kind === "ready" ? state.value.rules : []} />}
    </FromDocument>
  ),
};

/**
 * One rule whose facts could not be read.
 *
 * The row says so and carries the reason. `ok` with `openEvents: 0` is a measurement; this is the
 * absence of one, and a screen that drew them alike would let an operator read "nothing above 80%"
 * off a rule that has not looked at a disk all afternoon.
 */
export const ARuleThatCouldNotLook: Story = {
  render: () => (
    <FromDocument document={darkRuleDocument}>
      {(state) => <RuleReports reports={state.kind === "ready" ? state.value.rules : []} />}
    </FromDocument>
  ),
};

/**
 * One rule whose figure is real and old.
 *
 * The third thing a rule row can be, and the one that reads most like the other two: a measured
 * figure, kept, with a line saying it has not been refreshed. Neither *"Nothing open."* alone —
 * which claims a fresh reading — nor *"KUI could not evaluate…"*, which throws the reading away.
 */
export const ARuleWhoseFigureIsOld: Story = {
  render: () => (
    <FromDocument document={staleRuleDocument}>
      {(state) => <RuleReports reports={state.kind === "ready" ? state.value.rules : []} />}
    </FromDocument>
  ),
};
