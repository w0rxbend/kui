/**
 * One Kafka Connect connector.
 *
 * **This component has no backend behind it.** KUI has no connect service, so nothing routes to it
 * and nothing fetches for it; it exists in Storybook, against fixtures, so that the screens are
 * designed and reviewed before the service is written rather than after. That is a deliberate
 * decision recorded here so nobody spends an afternoon looking for the endpoint it calls.
 *
 * ## The two actions are permission-gated even with no server
 *
 * Pause and Restart are drawn disabled with a stated reason when the principal may not use them,
 * exactly as every other write control in this product is — because the rule is about the *shape* of
 * a control, and a component that learns the rule later is a component that ships without it once.
 * A hidden button makes an operator think the product cannot do the thing at all, and they go
 * looking for a command line.
 *
 * ## An unknown state is not RUNNING
 *
 * A connector whose state the cluster did not report draws its pill as unknown. Guessing `RUNNING`
 * would be this product telling somebody their pipeline is fine on no evidence, which is the single
 * worst thing a monitoring screen can do.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Button } from "./Button.jsx";
import { IconTile } from "./IconTile.jsx";
import { StatusPill, type PillTone } from "./StatusPill.jsx";
import { TaskBar, type TaskState } from "./TaskBar.jsx";
import type { IconName } from "../icon.jsx";

export type ConnectorState = "RUNNING" | "FAILED" | "PAUSED" | "UNASSIGNED" | "UNKNOWN";

export interface ConnectorCardProps {
  readonly name: string;
  /** `source` or `sink`, and the connector class in human terms: "Debezium Postgres". */
  readonly kind: string;
  readonly state: ConnectorState;
  readonly tasks: readonly TaskState[];
  /** Records a second, or `undefined` when nothing measured it. Never drawn as 0. */
  readonly throughput?: number | undefined;
  /** The topics it reads or writes, as the connector's own pattern: `orders.*`. */
  readonly topics?: string | undefined;
  readonly icon?: IconName | undefined;

  readonly onPause?: (() => void) | undefined;
  readonly onRestart?: (() => void) | undefined;
  /** Why the actions are unavailable. Shown on the disabled buttons. */
  readonly actionsDisabledReason?: string | undefined;
  readonly busy?: boolean | undefined;
  readonly testId?: string | undefined;
}

/**
 * The pill's wording and tone.
 *
 * `UNASSIGNED` is its own state and is not a failure: it means the connector exists and Connect has
 * not given it to a worker yet, which resolves on its own and is not something to page anybody
 * about. Drawing it in danger colours would produce exactly that page.
 */
export function connectorChip(state: ConnectorState): { readonly tone: PillTone; readonly label: string } {
  switch (state) {
    case "RUNNING":
      return { tone: "success", label: "running" };
    case "FAILED":
      return { tone: "danger", label: "failed" };
    case "PAUSED":
      return { tone: "warning", label: "paused" };
    case "UNASSIGNED":
      return { tone: "neutral", label: "unassigned" };
    case "UNKNOWN":
      return { tone: "neutral", label: "state not reported" };
  }
}

export function ConnectorCard(props: ConnectorCardProps): JSX.Element {
  const chip = () => connectorChip(props.state);
  const blocked = () => props.actionsDisabledReason;

  return (
    <article class="kui-connector" data-testid={props.testId}>
      <header class="kui-connector__head">
        <IconTile icon={props.icon ?? "connect"} tone="neutral" size="md" />
        <div class="kui-connector__identity">
          <h3 class="kui-connector__name">{props.name}</h3>
          <p class="kui-connector__kind">{props.kind}</p>
        </div>
        <StatusPill tone={chip().tone} dot>
          {chip().label}
        </StatusPill>
      </header>

      <TaskBar tasks={props.tasks} />

      <p class="kui-connector__facts">
        {/* Three facts on one line, and each is absent rather than zero when it is not known: a
            connector reporting "0 msg/s" is idle, and one whose throughput nobody measured is a
            connector nobody is watching. They are not the same and must not look alike. */}
        <span>{taskSummary(props.tasks)}</span>
        <Show when={props.throughput !== undefined} fallback={<span>throughput not measured</span>}>
          <span>{(props.throughput as number).toLocaleString()} msg/s</span>
        </Show>
        <Show when={props.topics}>{(pattern) => <span class="kui-connector__topics">{pattern()}</span>}</Show>
      </p>

      <footer class="kui-connector__actions">
        <Show
          when={blocked() === undefined}
          fallback={
            <>
              <Button variant="ghost" icon="pause" disabled disabledReason={blocked() ?? ""}>
                {props.state === "PAUSED" ? "Resume" : "Pause"}
              </Button>
              <Button variant="ghost" icon="restart" disabled disabledReason={blocked() ?? ""}>
                Restart
              </Button>
            </>
          }
        >
          <Button
            variant="ghost"
            icon={props.state === "PAUSED" ? "play" : "pause"}
            busy={props.busy === true}
            onClick={() => props.onPause?.()}
          >
            {props.state === "PAUSED" ? "Resume" : "Pause"}
          </Button>
          <Button
            variant="ghost"
            icon="restart"
            busy={props.busy === true}
            onClick={() => props.onRestart?.()}
          >
            Restart
          </Button>
        </Show>
      </footer>
    </article>
  );
}

function taskSummary(tasks: readonly TaskState[]): string {
  if (tasks.length === 0) return "no tasks";
  const running = tasks.filter((task) => task === "running").length;
  return `${running}/${tasks.length} tasks`;
}
