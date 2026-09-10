import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Button, Card, StatusPill, type Alerts } from "@kui/kernel";

/**
 * How many rows the compact card draws, however many the feed holds.
 *
 * §3.8 gives this card **one third of the row** on the Overview tab; the full-width layout with all
 * five rows is the alerts route's, in `@kui/feature-alerts`. Unbounded, a cluster mid-incident with
 * forty open events would push the storage card beside it off the bottom of a dashboard whose whole
 * point is to be read in one glance — and the card would still be *correct*, which is why nothing
 * would report it. The pill above the rows is the server's own count, so the card never pretends
 * that the three it draws are all there are.
 */
const ROWS_ON_THE_DASHBOARD = 3;

/** The dashboard's compact view of the same store used by the bell and the alerts route. */
export function AlertsCard(props: { readonly alerts: Alerts }): JSX.Element {
  const state = () => props.alerts.feed();
  const rows = () => props.alerts.events().slice(0, ROWS_ON_THE_DASHBOARD);
  const failure = () => {
    const current = state();
    return current.kind === "failed" ? current : undefined;
  };
  const stale = () => {
    const current = state();
    return current.kind === "stale" ? current : undefined;
  };
  const cardState = () => {
    switch (state().kind) {
      case "loading": return "loading" as const;
      case "failed": return "unavailable" as const;
      case "forbidden": return "forbidden" as const;
      case "not-configured": return "empty" as const;
      case "ready":
      case "stale": return rows().length === 0 ? "empty" as const : "ready" as const;
    }
  };
  const message = () => {
    const current = state();
    if (current.kind === "failed") return current.message;
    if (current.kind === "forbidden") return "You do not have permission to read this cluster's alerts.";
    if (current.kind === "ready" || current.kind === "stale") {
      return current.value.evaluatedAt === undefined
        ? "KUI has not evaluated this cluster's alert rules yet."
        : "The alerts service answered and is holding no events for this cluster.";
    }
    return undefined;
  };

  return (
    <Show when={state().kind !== "not-configured"}>
      <Card
        title="Alerts & events"
        icon="bell"
        testId="panel-alerts"
        state={cardState()}
        message={message()}
        code={failure()?.code}
        stateAction={
          <Show when={state().kind === "failed"}>
            <Button variant="secondary" icon="refresh" onClick={() => props.alerts.refresh()}>
              Retry
            </Button>
          </Show>
        }
        headerEnd={
          <Show when={props.alerts.openCount() !== null}>
            <StatusPill tone={(props.alerts.openCount() ?? 0) > 0 ? "danger" : "success"} dot>
              {props.alerts.openCount() === 0 ? "None open" : `${props.alerts.openCount()} open`}
            </StatusPill>
          </Show>
        }
      >
        <Show when={stale()}>
          {(answer) => <p class="kui-alerts-summary__note">{answer().reason}</p>}
        </Show>
        <ul class="kui-alerts-summary">
          <For each={rows()}>
            {(event) => (
              <li class="kui-alerts-summary__row">
                <span class="kui-alerts-summary__severity" data-tone={event.tone} />
                <span>{event.title}</span>
              </li>
            )}
          </For>
        </ul>
      </Card>
    </Show>
  );
}
