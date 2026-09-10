/**
 * One connector: the card, the reason it is failing, and its two controls.
 *
 * ## Why this wraps `ConnectorCard` instead of being it
 *
 * `@kui/kernel`'s `ConnectorCard` is the drawing `SCREENS-V4.md` §3.14 specifies and it has been in
 * the workspace, story-only, since before there was a service. One thing this screen has to say is
 * outside it, and it is an open finding rather than an oversight: **the failure reason** (§7.7).
 * The design's failed card carries a state and a task count and nothing else, while the
 * notification for the same event carries `Task 0: connection refused to es-01:9200`. It is
 * rendered here, under the card, in the worker's own words.
 *
 * ## Two things the card is handed that it must not compute
 *
 * The task caption and the failure flag both come off the wire. `runningTasks`, `taskCount` and
 * `failed` are computed once by `services/connect` and travel so that this card, a drawer row and
 * the page's voice line show one number — three browsers deriving them are three chances to derive
 * them differently, and `RESTARTING` being "not running" is the case where they would.
 *
 * `failed` is not `state === "FAILED"`, and the service's own golden document is why:
 * `elastic-sink` has `state: "RUNNING"` and `failed: true`, because one of its tasks has failed. A
 * panel that keyed the reason off the state word would hide the reason on exactly that connector.
 *
 * ## And no throughput, on any card, ever
 *
 * The Connect REST API measures none (ADR-054 §6), so `ConnectorCard` is handed no `throughput` and
 * draws "throughput not measured". §3.14's *Absent* paragraph is the rule: a literal `0 msg/s` on a
 * paused connector is a measured zero, and an unmeasured one must never look like it.
 *
 * ## The gate is not decided here
 *
 * `refusal` arrives already answered and `onCommand` is absent when the principal may not operate
 * this Connect cluster, so there is no path from an unpermitted principal to an enabled control and
 * no second copy of the permission question to drift from the first. `ConnectRoute` asks it, once
 * per connector, with the Connect cluster's name as the subject.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { ConnectorCard, IconTile, StatusPill, connectorChip } from "@kui/kernel";

import {
  connectorLabel,
  failureReason,
  pillState,
  segmentsOf,
  toggleOf,
  NOT_DESCRIBED,
  NO_REASON_REPORTED,
} from "./model.js";
import type { ConnectorCommand } from "./data.js";
import type { Connector } from "./wire.js";

export interface ConnectorPanelProps {
  readonly connector: Connector;
  /** Absent means permitted. Present is the sentence, and the controls are disabled carrying it. */
  readonly refusal?: string | undefined;
  /** Absent for exactly the same reason. A component cannot invent a handler it was not given. */
  readonly onCommand?: ((which: ConnectorCommand) => void) | undefined;
  /** The command currently in flight against this connector, if any. */
  readonly pending?: ConnectorCommand | undefined;
  /** The sentence the last command against this connector failed with. */
  readonly failure?: string | undefined;
}

export function ConnectorPanel(props: ConnectorPanelProps): JSX.Element {
  const label = () => connectorLabel(props.connector);
  const reason = () => failureReason(props.connector);
  const toggle = () => toggleOf(props.connector.state);

  return (
    <div class="kui-connect__panel" data-testid="connector" data-connector={label()}>
      <ConnectorCard
        name={props.connector.name}
        kind={kindLine(props.connector)}
        state={pillState(props.connector.state)}
        tasks={segmentsOf(props.connector)}
        /* No `throughput` prop at all. See the header: there is no rate on this wire to pass. */
        busy={props.pending !== undefined}
        testId="connector-card"
        {...(props.refusal === undefined
          ? {
              onPause: () => props.onCommand?.(toggle()),
              onRestart: () => props.onCommand?.("restart"),
            }
          : { actionsDisabledReason: props.refusal })}
      />

      {/* The service's two figures, said in words beside the bar the card draws them as. The card's
          own summary counts the task objects it was handed, which is the tasks KUI was *told*
          about; this is the cluster's count, and where they disagree the difference is a fact. */}
      <p class="kui-connect__tasks" data-testid="connector-tasks">
        {taskSentence(props.connector)}
      </p>

      {/* §7.7. Under the card rather than inside it, so that a connector that failed with no reason
          still gets a sentence — an empty reason area reads as "KUI knows and will not say". */}
      <Show when={props.connector.failed}>
        <div
          class="kui-connect__reason"
          data-testid="connector-reason"
          role="group"
          aria-label={`Why ${label()} is failing`}
        >
          <Show
            when={reason()}
            fallback={<p class="kui-connect__reason-none">{NO_REASON_REPORTED}</p>}
          >
            {(found) => (
              <>
                <p class="kui-connect__reason-head">
                  {found().task === undefined
                    ? "The Connect cluster reported this, and these are its words:"
                    : `Task ${found().task} reported this, and these are the worker's words:`}
                </p>
                <p class="kui-connect__reason-line" data-testid="connector-reason-line">
                  {found().line}
                </p>
                <Show when={found().hasTrace}>
                  <p class="kui-connect__reason-more">
                    The rest of the trace is in that task's log on the Connect worker.
                  </p>
                </Show>
              </>
            )}
          </Show>
        </div>
      </Show>

      {/* The refusal in words as well as on the control. A tooltip is reachable by hover and by
          focus and by nothing else, and this screen refuses per Connect cluster — so a page of
          cards with some buttons disabled would otherwise mean hunting for which and why. */}
      <Show when={props.refusal}>
        {(sentence) => (
          <p class="kui-connect__refusal" data-testid="connector-refusal">
            {sentence()}
          </p>
        )}
      </Show>

      <Show when={props.failure}>
        {(sentence) => (
          <p class="kui-connect__command-failure" data-testid="connector-failure" role="alert">
            {sentence()}
          </p>
        )}
      </Show>
    </div>
  );
}

/**
 * A connector the Connect cluster named and would not describe.
 *
 * One of the two states §3.14 does not draw. There is no task bar and no state pill, because there
 * is no state and no task list to draw: `ConnectorsDto.unreadable` carries a name and nothing else.
 * Drawing an empty bar would be a picture of "no tasks" and a neutral pill would be a claim about a
 * state nobody reported — and the row exists at all only because a connector missing from a list is
 * indistinguishable from one that was deleted.
 */
export function NotDescribedPanel(props: {
  readonly connect: string;
  readonly name: string;
}): JSX.Element {
  const label = () => `${props.connect}/${props.name}`;
  const chip = () => connectorChip("UNKNOWN");

  return (
    <div
      class="kui-connect__panel"
      data-testid="connector-not-described"
      data-connector={label()}
    >
      <article class="kui-connector kui-connect__undescribed">
        <header class="kui-connector__head">
          <IconTile icon="connect" tone="neutral" size="md" />
          <div class="kui-connector__identity">
            <h3 class="kui-connector__name">{props.name}</h3>
            <p class="kui-connector__kind">on {props.connect}</p>
          </div>
          <StatusPill tone={chip().tone} dot>
            {chip().label}
          </StatusPill>
        </header>
        <p class="kui-connect__undescribed-note">{NOT_DESCRIBED}</p>
      </article>
    </div>
  );
}

/**
 * The task sentence: the cluster's own two figures, and the gap between them when there is one.
 *
 * A connector reporting eight tasks whose worker described two is a connector KUI knows less about
 * than the number suggests, and the bar above cannot show that — it has two segments and looks like
 * a two-task connector. Saying it is cheaper than being asked why the bar is short.
 */
function taskSentence(connector: Connector): string {
  if (connector.taskCount === 0) return "This connector has no tasks.";
  const summary = `${connector.runningTasks} of ${connector.taskCount} tasks running`;
  return connector.tasks.length < connector.taskCount
    ? `${summary}. The worker described ${connector.tasks.length} of them.`
    : `${summary}.`;
}

/**
 * The sub-line under the connector's name: `source · on payments`.
 *
 * `kind` is the worker's own word and is empty on every Connect release before 2.0, in which case
 * it is left out rather than filled in — inferring `source` from a class name containing the word
 * "source" is the kind of guess that reads as a fact on a screen, and §7.2's *"do not infer a cart
 * from the word orders"* is the same rule. The Connect cluster is always named, because a page can
 * hold two workers' connectors and the name alone does not say which.
 */
function kindLine(connector: Connector): string {
  const kind = connector.kind.trim();
  return kind === ""
    ? `on ${connector.connect} · the worker did not say what kind of connector this is`
    : `${kind} · on ${connector.connect}`;
}
