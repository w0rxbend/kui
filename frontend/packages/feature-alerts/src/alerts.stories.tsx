import type { Meta, StoryObj } from "storybook-solidjs-vite";

import { AlertsFeed } from "./AlertsFeed.jsx";
import { RuleReports } from "./RuleReports.jsx";
import { feedSection } from "./wire.js";
import type { AlertsFeedPage } from "./model.js";
import openDocument from "./documents/events-open.json" with { type: "json" };
import pagedDocument from "./documents/events-paged.json" with { type: "json" };
import emptyDocument from "./documents/events-empty.json" with { type: "json" };
import neverEvaluatedDocument from "./documents/events-never-evaluated.json" with { type: "json" };
import unknownDocument from "./documents/events-unknown-vocabulary.json" with { type: "json" };
import darkRuleDocument from "./documents/events-dark-rule.json" with { type: "json" };

/**
 * The alerts card (`SCREENS-V4.md` §3.8), in every state it has.
 *
 * `TheScreenshot` is the one to hold beside `M05`. Everything after it is a state a healthy cluster
 * cannot be put into — a service that is not answering, a deployment that runs no alerts service at
 * all, a severity this build has never heard of, a page whose count is larger than its rows — and
 * those are the states this project's defects have always lived in.
 *
 * Every story renders a **document**, decoded by the same `feedSection` the product calls, rather
 * than a hand-built object. A story built from a literal is a drawing of what the author believed
 * the server sends.
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

function page(document: unknown): AlertsFeedPage {
  const section = feedSection(document);
  if (section.status !== "ok") throw new Error(`the fixture is ${section.status}, not ok`);
  return section.data;
}

const noop = (): void => {};

/**
 * Five rows, three open, two resolved — and the two warnings carrying different glyphs.
 *
 * That pair is §3.9's correction drawn: a disk and a rebalance arrow at the same severity. A card
 * that picked its glyph from the severity would draw this story with two identical rows.
 */
export const TheScreenshot: Story = {
  render: () => (
    <AlertsFeed state={{ kind: "ready", value: page(openDocument) }} onAcknowledge={noop} now={now} />
  ),
};

/** The Alerts screen's layout: full width, the age at the far edge. Same rows, two layouts (§3.8). */
export const FullWidth: Story = {
  render: () => (
    <AlertsFeed
      wide
      state={{ kind: "ready", value: page(openDocument) }}
      onAcknowledge={noop}
      now={now}
    />
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
    <AlertsFeed state={{ kind: "ready", value: page(pagedDocument) }} onAcknowledge={noop} now={now} />
  ),
};

/** The service answered and is holding nothing. A sentence about the source, and no count. */
export const AnsweredAndEmpty: Story = {
  render: () => <AlertsFeed state={{ kind: "ready", value: page(emptyDocument) }} now={now} />,
};

/**
 * The same empty card, over a cluster the rules have never run on.
 *
 * Hold it beside the story above: identical picture, opposite fact. One says the rules ran and found
 * nothing; this one says nobody has looked, and the pill says so rather than drawing a reassuring
 * zero. It is the whole of the product's central promise in two stories.
 */
export const NeverEvaluated: Story = {
  render: () => (
    <AlertsFeed state={{ kind: "ready", value: page(neverEvaluatedDocument) }} now={now} />
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

/** A principal who may read alerts and not acknowledge them. No retry: there is nothing to retry. */
export const MayNotRead: Story = {
  render: () => <AlertsFeed state={{ kind: "forbidden" }} now={now} />,
};

/** The last answer KUI received, with the reason above it and no invented code beside it. */
export const Stale: Story = {
  render: () => (
    <AlertsFeed
      state={{
        kind: "stale",
        value: page(openDocument),
        reason: "The alerts service has not answered since 09:38.",
      }}
      onAcknowledge={noop}
      now={now}
    />
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
    <AlertsFeed state={{ kind: "ready", value: page(unknownDocument) }} onAcknowledge={noop} now={now} />
  ),
};

/** Acknowledgement refused for this principal: the control is disabled and says why. */
export const MayNotAcknowledge: Story = {
  render: () => (
    <AlertsFeed
      state={{ kind: "ready", value: page(openDocument) }}
      acknowledgeRefusal="You do not have permission to acknowledge alerts on this cluster."
      now={now}
    />
  ),
};

/** An acknowledgement the service refused, said above the rows it did not change. */
export const AnAcknowledgementRefused: Story = {
  render: () => (
    <AlertsFeed
      state={{ kind: "ready", value: page(openDocument) }}
      onAcknowledge={noop}
      acknowledgeFailure={{
        message: "This event is already closed, so it cannot be acknowledged.",
        code: "KUI-INVALID-STATE",
      }}
      now={now}
    />
  ),
};

/** Every row filtered out by the reader's own filter — a different sentence from an empty feed. */
export const FilteredToNothing: Story = {
  render: () => (
    <AlertsFeed
      state={{ kind: "ready", value: page(openDocument) }}
      filter={{ severity: "warning", state: "resolved" }}
      now={now}
    />
  ),
};

/** What KUI checked, with every rule answering. */
export const TheRulesThatRan: Story = {
  render: () => <RuleReports reports={page(openDocument).rules} />,
};

/**
 * One rule whose facts could not be read.
 *
 * The row says so and carries the reason. `ok` with `openEvents: 0` is a measurement; this is the
 * absence of one, and a screen that drew them alike would let an operator read "nothing above 80%"
 * off a rule that has not looked at a disk all afternoon.
 */
export const ARuleThatCouldNotLook: Story = {
  render: () => <RuleReports reports={page(darkRuleDocument).rules} />,
};
