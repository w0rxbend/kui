/**
 * The connectors of every Connect cluster this Kafka cluster configures.
 *
 * ## Two levels of state, and the inner one is the shape of the response
 *
 * The outer `Fetched` is the whole read: loading, the six states, `not-configured` for a cluster
 * with no `connect` block at all. Inside it every configured Connect cluster carries **its own**
 * section, so one worker being down costs one row rather than the screen — the same rule one dead
 * alert rule follows. A screen that flattened them would have to choose between hiding a failure
 * and hiding the connectors the other workers did answer for, and both choices are wrong.
 *
 * ## A rebalancing worker is not a broken worker
 *
 * `ErrorCode.ConnectRebalancing` has been declared and unused since wave 1 and this is its first
 * reader. The service maps it to a section that is `unavailable` with `ReasonCode.Starting`,
 * keeping the worker's own sentence, and that is the browser half of a rule with three halves — the
 * other two are `ConnectHttp.errorFrom` classifying the 409 as an application error so ADR-039 §6
 * keeps it from dimming anything, and `ConnectCapabilities.probe` leaving the capability available.
 * A browser that drew it as a failure would contradict both and put a red panel and a Retry in
 * front of an operator over a state that clears itself in seconds.
 *
 * ## Three empty screens that are three different facts
 *
 * "This cluster configures no Connect worker" (the outer section), "the workers answered and named
 * no connectors" (every page empty), and "KUI could not read what the workers said" (a decode this
 * build could not make sense of, which `data.ts` refuses to answer as an empty list). All three
 * draw a sentence and nothing else, and the sentences are not interchangeable: only one of them is
 * a reason to go and edit `kui.yaml`, and only one of them is a bug report.
 */
import { For, Show, createUniqueId } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Banner, Button, EmptyState, type Fetched } from "@kui/kernel";

import { ConnectorPanel, NotDescribedPanel } from "./ConnectorPanel.jsx";
import {
  allConnectors,
  allNotDescribed,
  connectorLabel,
  NOT_CONFIGURED,
  NO_CONNECTORS,
} from "./model.js";
import type { ConnectorCommand } from "./data.js";
import type { Connector, ConnectorListing, WorkerAnswer, WorkerRow } from "./wire.js";

export interface ConnectorListProps {
  readonly state: Fetched<ConnectorListing>;
  /** The refusal for this connector's controls, or `undefined` when the principal may use them. */
  readonly refusalFor: (connector: Connector) => string | undefined;
  readonly onCommand?: ((connector: Connector, which: ConnectorCommand) => void) | undefined;
  /** Which connector has a command in flight, keyed by `connectorLabel`. */
  readonly pending?: { readonly subject: string; readonly command: ConnectorCommand } | undefined;
  /** The last command failure, keyed the same way. */
  readonly failure?: { readonly subject: string; readonly message: string } | undefined;
  readonly onRetry?: (() => void) | undefined;
}

export function ConnectorList(props: ConnectorListProps): JSX.Element {
  const listing = (): ConnectorListing | undefined => {
    const state = props.state;
    return state.kind === "ready" || state.kind === "stale" ? state.value : undefined;
  };

  /*
   * The region's name, as a heading rather than as an `aria-label`.
   *
   * `ConnectorCard`'s name is an `<h3>` and the page's title is the `PageHeader`'s `<h1>`, so
   * without a level in between every card is a heading-order violation — axe found exactly that on
   * this file's first run. It is visually hidden because `PageHeader` already says "Kafka Connect"
   * a few pixels above and two visible headings saying nearly the same thing is worse than one; the
   * outline a screen-reader user navigates by needs the level all the same.
   */
  const headingId = createUniqueId();

  return (
    <section class="kui-connect" aria-labelledby={headingId} data-testid="connect-list">
      <h2 id={headingId} class="kui-visually-hidden">
        Connectors
      </h2>

      <Show when={props.state.kind === "loading"}>
        <p role="status" data-testid="connect-loading">
          Asking the Connect clusters what they are running…
        </p>
      </Show>

      <Show when={props.state.kind === "not-configured"}>
        <EmptyState
          kind="empty"
          title="No Kafka Connect worker is configured."
          description={NOT_CONFIGURED}
          testId="connect-not-configured"
        />
      </Show>

      <Show when={props.state.kind === "forbidden"}>
        {/* No retry. A permission decision does not change because somebody pressed a button, and a
            button that cannot help teaches an operator that this product's buttons do nothing. */}
        <EmptyState
          kind="forbidden"
          title="You do not have permission to see this cluster's connectors."
          description="Ask for CONNECT:VIEW on this cluster."
          testId="connect-forbidden"
        />
      </Show>

      <Show when={props.state.kind === "failed" ? props.state : undefined}>
        {(failed) => (
          <EmptyState
            kind="unavailable"
            title="KUI could not read the connector list."
            description={failed().message}
            code={failed().code}
            testId="connect-failed"
            action={
              <Show when={props.onRetry !== undefined}>
                <Button variant="secondary" icon="refresh" onClick={() => props.onRetry?.()}>
                  Retry
                </Button>
              </Show>
            }
          />
        )}
      </Show>

      <Show when={props.state.kind === "stale" ? props.state.reason : undefined}>
        {(reason) => <Banner tone="warning" message={reason()} testId="connect-stale" />}
      </Show>

      <Show when={listing()}>
        {(held) => (
          <>
            {/* One notice per Connect cluster that did not answer with a list, named, above the
                cards — so what follows is read as a short list rather than as a complete one. */}
            <For each={held().workers}>{(worker) => <WorkerNotice worker={worker} />}</For>

            <Show
              when={allConnectors(held()).length > 0 || allNotDescribed(held()).length > 0}
              fallback={
                /* Only where every worker answered. Where one did not, its own notice above is the
                   sentence, and "the workers answered and named no connectors" would be a claim
                   about a worker that said nothing at all. */
                <Show when={held().workers.every(answered)}>
                  <EmptyState
                    kind="empty"
                    title="Nothing is deployed on the Connect workers."
                    description={NO_CONNECTORS}
                    testId="connect-empty"
                  />
                </Show>
              }
            >
              <div class="kui-connect__grid">
                <For each={allConnectors(held())}>
                  {(connector) => (
                    <ConnectorPanel
                      connector={connector}
                      refusal={props.refusalFor(connector)}
                      pending={pendingFor(props, connector)}
                      failure={failureFor(props, connector)}
                      {...(props.onCommand === undefined
                        ? {}
                        : {
                            onCommand: (which: ConnectorCommand) =>
                              props.onCommand?.(connector, which),
                          })}
                    />
                  )}
                </For>
                {/* After the described ones: a row naming a connector and claiming no more. */}
                <For each={allNotDescribed(held())}>
                  {(one) => <NotDescribedPanel connect={one.connect} name={one.name} />}
                </For>
              </div>
            </Show>
          </>
        )}
      </Show>
    </section>
  );
}

/** One Connect cluster that did not answer with a list, said in the words it used. */
function WorkerNotice(props: { readonly worker: WorkerRow }): JSX.Element {
  const page = (): WorkerAnswer => props.worker.page;
  return (
    <>
      <Show when={page().kind === "rebalancing" ? page() : undefined}>
        {(state) => (
          <Banner
            /* Warning, not danger: it settles by itself. The other kinds are an afternoon. */
            tone="warning"
            message={
              `${props.worker.connect}: ${sentence(state())} This settles on its own; ask ` +
              "again in a moment."
            }
            testId="connect-worker-rebalancing"
          />
        )}
      </Show>
      <Show when={page().kind === "failed" ? page() : undefined}>
        {(state) => (
          <Banner
            tone="danger"
            message={
              `${props.worker.connect}: ${sentence(state())} Any connectors on it are missing ` +
              "from this list."
            }
            code={codeOf(state())}
            testId="connect-worker-failed"
          />
        )}
      </Show>
      <Show when={page().kind === "forbidden"}>
        <Banner
          tone="info"
          message={
            `You do not have permission to see the connectors on ${props.worker.connect}, so ` +
            "any it is running are missing from this list."
          }
          testId="connect-worker-forbidden"
        />
      </Show>
      <Show when={page().kind === "not-configured"}>
        <Banner
          tone="info"
          message={
            `${props.worker.connect} is configured for this cluster and has nothing to list.`
          }
          testId="connect-worker-not-configured"
        />
      </Show>
      <Show when={page().kind === "unreadable" ? page() : undefined}>
        {(state) => (
          <Banner
            tone="danger"
            message={`KUI could not read what ${props.worker.connect} said: ${sentence(state())}`}
            testId="connect-worker-unreadable"
          />
        )}
      </Show>
      <Show when={page().kind === "stale" ? page() : undefined}>
        {(state) => (
          <Banner
            tone="warning"
            message={`${props.worker.connect}: ${sentence(state())}`}
            testId="connect-worker-stale"
          />
        )}
      </Show>
    </>
  );
}

/** Whether this Connect cluster answered with a list at all. */
function answered(worker: WorkerRow): boolean {
  return worker.page.kind === "ok" || worker.page.kind === "stale";
}

/** The sentence a worker's answer carries, whichever field it lives in. Never one of ours. */
function sentence(state: WorkerAnswer): string {
  if ("message" in state) return state.message;
  if ("reason" in state) return state.reason;
  if ("detail" in state) return state.detail;
  return "";
}

function codeOf(state: WorkerAnswer): string | undefined {
  return "code" in state ? state.code : undefined;
}

function pendingFor(props: ConnectorListProps, connector: Connector): ConnectorCommand | undefined {
  const pending = props.pending;
  return pending !== undefined && pending.subject === connectorLabel(connector)
    ? pending.command
    : undefined;
}

function failureFor(props: ConnectorListProps, connector: Connector): string | undefined {
  const failure = props.failure;
  return failure !== undefined && failure.subject === connectorLabel(connector)
    ? failure.message
    : undefined;
}
