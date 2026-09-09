/**
 * What KUI checked, and what it could not.
 *
 * ## Why this is on the screen at all
 *
 * `AlertFeedDto` carries a `Section`-wrapped report per rule, and `AlertRuleReportDto`'s scaladoc
 * says exactly what it is for: *"`ok` with `openEvents: 0` is a measurement; `unavailable` is the
 * absence of one, and the product's central promise is that a screen can tell the reader which it
 * is looking at."* A feed drawn without it is a feed that looks identical whether four rules ran and
 * found nothing or whether one of them has been refused by an ACL all afternoon — and the second is
 * the state in which somebody is about to trust an empty screen.
 *
 * ## And why the two numbers under a rule are not added up
 *
 * `unmeasuredSubjects` is how many subjects a rule declined to judge because the figure it compares
 * was not measured — a broker that reports no capacity for a log directory. It is drawn beside
 * `openEvents` and never folded into it: "nothing above 80%" and "nothing above 80% of the four
 * directories whose capacity this cluster reports" are different claims, and only the second one is
 * true.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Card, StatusPill } from "@kui/kernel";

import { ruleRefusal, type RuleReport } from "./model.js";

export interface RuleReportsProps {
  readonly reports: readonly RuleReport[];
  readonly testId?: string | undefined;
}

/** The rules, or nothing at all when the document carried none — never an empty table. */
export function RuleReports(props: RuleReportsProps): JSX.Element {
  return (
    <Show when={props.reports.length > 0}>
      <Card
        title="What KUI checked"
        icon="shield"
        class="kui-alerts-rules"
        testId={props.testId ?? "alerts-rules"}
        caption={
          "One row per rule. A rule whose facts could not be read says so rather than " +
          "reporting nothing found."
        }
      >
        <ul class="kui-alerts-rules__list">
          <For each={props.reports}>
            {(report) => (
              <li class="kui-alerts-rules__row" data-testid="alert-rule" data-rule={report.rule}>
                <span class="kui-alerts-rules__name">{report.rule}</span>
                <Show
                  when={ruleRefusal(report)}
                  fallback={
                    <>
                      <span class="kui-alerts-rules__figure">{openWords(report.openEvents)}</span>
                      <Show when={unmeasured(report)}>
                        {(sentence) => (
                          <span class="kui-alerts-rules__unmeasured">{sentence()}</span>
                        )}
                      </Show>
                      <Show when={report.status === "stale" ? report.reason : undefined}>
                        {(reason) => (
                          <span class="kui-alerts-rules__refusal">{`Last known reading: ${reason()}`}</span>
                        )}
                      </Show>
                    </>
                  }
                >
                  {(refusal) => (
                    <>
                      {/* The pill is the second, non-colour cue that this row is not a figure. */}
                      <StatusPill tone="warning" dot>
                        Not evaluated
                      </StatusPill>
                      <span class="kui-alerts-rules__refusal">{refusal()}</span>
                    </>
                  )}
                </Show>
              </li>
            )}
          </For>
        </ul>
      </Card>
    </Show>
  );
}

/**
 * The figure a rule found.
 *
 * A rule that ran and opened nothing says so in words. `0 open` is true here and is drawn as a
 * sentence anyway, because the row beside it may be a refusal and the two must not read alike at a
 * glance.
 */
function openWords(openEvents: number | null): string {
  if (openEvents === null) return "The service reported no figure for this rule.";
  if (openEvents === 0) return "Nothing open.";
  return openEvents === 1 ? "1 open event." : `${openEvents} open events.`;
}

/** The subjects a rule could not judge, or nothing when there were none to mention. */
function unmeasured(report: RuleReport): string | undefined {
  const skipped = report.unmeasuredSubjects;
  if (skipped === null || skipped === 0) return undefined;
  return skipped === 1
    ? "1 subject was skipped because the figure it compares was not measured."
    : `${skipped} subjects were skipped because the figures they compare were not measured.`;
}
