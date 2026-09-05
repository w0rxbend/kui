/**
 * The ksqlDB workspace: the objects on the left, an editor and its footer on the right.
 *
 * **This component has no backend behind it.** KUI has no ksql service, so nothing routes to it and
 * nothing runs a query; it exists in Storybook, against fixtures, so the screen is designed and
 * reviewed before the service is written. Recorded here so nobody goes looking for the endpoint.
 *
 * ## `auto.offset.reset` is on the footer, not in a settings panel
 *
 * It decides whether `SELECT * FROM orders EMIT CHANGES` shows the operator the last hour of a topic
 * or only what arrives from now on — which is the difference between a query that answers and a
 * query that appears to hang. Putting it anywhere but next to the Run button guarantees somebody
 * runs the wrong one and concludes ksqlDB is broken.
 *
 * ## Running is cancellable, and that is a requirement rather than a nicety
 *
 * A push query never finishes on its own. If Run cannot become Cancel, the only way out of a query
 * over a busy topic is to close the tab — and the query goes on running on the server.
 *
 * ## The editor is a textarea
 *
 * Not a code editor with syntax colouring: the design asks for colouring, and a real editor is a
 * dependency, a bundle and an accessibility surface of its own. A `<textarea>` in the monospace face
 * is a control every assistive technology already understands, and it is the honest starting point
 * for a screen with no server behind it. Colouring can be added when there is something to run.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Button } from "./Button.jsx";
import { Icon } from "../icon.jsx";

/** One object the workspace lists. A stream and a table are different things and are drawn so. */
export interface KsqlObject {
  readonly name: string;
  readonly kind: "stream" | "table";
  /** The topic behind it, when there is one. */
  readonly topic?: string | undefined;
}

export interface KsqlWorkspaceProps {
  readonly objects: readonly KsqlObject[];
  readonly sql: string;
  readonly onSql: (sql: string) => void;
  /** `earliest` or `latest`. Shown on the footer; see the header for why it lives there. */
  readonly offsetReset: string;
  readonly onOffsetReset?: ((value: string) => void) | undefined;
  readonly running: boolean;
  readonly onRun: () => void;
  /** Stops a running query. Required whenever `running` is true — a push query never ends by itself. */
  readonly onCancel: () => void;
  readonly onClear: () => void;
  /** Absent means this principal may run queries. Present disables Run and says why. */
  readonly runDisabledReason?: string | undefined;
  /** What came back, or the failure. Rendered under the editor. */
  readonly result?: JSX.Element | undefined;
  readonly testId?: string | undefined;
}

export function KsqlWorkspace(props: KsqlWorkspaceProps): JSX.Element {
  const canRun = () => props.runDisabledReason === undefined && props.sql.trim() !== "";

  return (
    <div class="kui-ksql" data-testid={props.testId}>
      <section class="kui-ksql__objects" aria-label="Streams and tables">
        <h2 class="kui-ksql__objects-title">Streams &amp; tables</h2>
        <Show
          when={props.objects.length > 0}
          fallback={
            <p class="kui-ksql__empty" role="status">
              This ksqlDB cluster has no streams or tables yet. One appears here as soon as a
              <code> CREATE STREAM </code> or <code> CREATE TABLE </code> runs.
            </p>
          }
        >
          <ul class="kui-ksql__object-list">
            <For each={props.objects}>
              {(object) => (
                <li class="kui-ksql__object">
                  {/* A stream is an unbounded log and a table is the current value per key — the
                      distinction changes what a query means, so it is a glyph and a word rather
                      than only a colour. */}
                  <Icon name={object.kind === "stream" ? "stream" : "table"} size="12px" />
                  <span class="kui-ksql__object-name">{object.name}</span>
                  <span class="kui-ksql__object-kind">{object.kind}</span>
                  <Show when={object.topic}>
                    {(topic) => <span class="kui-ksql__object-topic">{topic()}</span>}
                  </Show>
                </li>
              )}
            </For>
          </ul>
        </Show>
      </section>

      <section class="kui-ksql__editor-pane" aria-label="Query">
        <label class="kui-visually-hidden" for="kui-ksql-editor">
          ksqlDB query
        </label>
        <textarea
          id="kui-ksql-editor"
          class="kui-ksql__editor"
          spellcheck={false}
          value={props.sql}
          onInput={(event) => props.onSql(event.currentTarget.value)}
        />

        <footer class="kui-ksql__footer">
          <label class="kui-ksql__offset">
            {/* Beside Run, because it decides whether a query reads the topic's history or only what
                arrives from now on — and the second, run by mistake, looks exactly like a hang. */}
            <span>auto.offset.reset</span>
            <select
              value={props.offsetReset}
              disabled={props.onOffsetReset === undefined}
              onChange={(event) => props.onOffsetReset?.(event.currentTarget.value)}
            >
              <option value="earliest">earliest</option>
              <option value="latest">latest</option>
            </select>
          </label>

          <div class="kui-ksql__buttons">
            <Button variant="ghost" onClick={props.onClear}>
              Clear
            </Button>
            <Show
              when={props.running}
              fallback={
                <Show
                  when={canRun()}
                  fallback={
                    <Button
                      variant="primary"
                      icon="play"
                      disabled
                      disabledReason={
                        props.runDisabledReason ?? "Write a query first."
                      }
                    >
                      Run query
                    </Button>
                  }
                >
                  <Button variant="primary" icon="play" onClick={props.onRun}>
                    Run query
                  </Button>
                </Show>
              }
            >
              {/* Not a spinner where the button was. A push query runs until it is stopped, so the
                  only way out of one over a busy topic would otherwise be closing the tab — and the
                  query would go on running on the server. */}
              <Button variant="danger" icon="pause" onClick={props.onCancel}>
                Cancel query
              </Button>
            </Show>
          </div>
        </footer>

        <Show when={props.result}>{(result) => <div class="kui-ksql__result">{result()}</div>}</Show>
      </section>
    </div>
  );
}
