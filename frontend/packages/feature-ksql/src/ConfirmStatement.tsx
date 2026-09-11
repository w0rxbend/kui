/**
 * The confirmation a destructive ksqlDB statement needs (ADR-045).
 *
 * ## Why this dialogue is not the same as the topic screen's
 *
 * `services/topic` plans a *known* operation over a *named* topic, so its dialogue can say "delete
 * orders.payments" and mean it. Here the operation is whatever somebody typed, and the service is
 * what classified it — so the dialogue's subject is the **statement text the service
 * canonicalised** and the warnings are the **service's own sentences**, in the order it wrote them.
 * Nothing here composes a warning: a browser that decided what was dangerous would be a second
 * classifier, and the first time the two disagreed it would be on the statement that deletes a
 * topic.
 *
 * The statement is shown as text because it is the thing being confirmed. A dialogue saying "this
 * is destructive, continue?" over a statement the reader can no longer see is a dialogue whose
 * answer is always yes.
 *
 * ## `deletesTopic` is drawn differently from `destructive`
 *
 * Every `DROP` is destructive; only `DROP … DELETE TOPIC` destroys records. They are not the same
 * risk and the dialogue does not pretend they are — the second gets the danger tone and a sentence
 * of its own, because a dropped stream can be recreated from its topic and a deleted topic cannot
 * be recreated from anything.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Banner, Button, Dialog } from "@kui/kernel";

import type { StatementPlan } from "./wire.js";

export interface ConfirmStatementProps {
  /** The plan to confirm, or `undefined` when nothing is waiting. */
  readonly plan: StatementPlan | undefined;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
  /** True while the confirmed statement is in flight, so it cannot be sent twice. */
  readonly busy?: boolean | undefined;
}

export function ConfirmStatement(props: ConfirmStatementProps): JSX.Element {
  return (
    <Dialog
      open={props.plan !== undefined}
      onClose={props.onCancel}
      title="Run this statement?"
      description="KUI asked the ksqlDB server what this statement would do before running it."
      testId="ksql-confirm"
      /* A stray click on the veil must not dismiss a dialogue the reader is reading in order to
         decide. The only ways out are the two buttons. */
      closeOnScrimClick={false}
      actions={
        <>
          <Button variant="secondary" onClick={props.onCancel}>
            Cancel
          </Button>
          <Button
            variant="danger"
            icon="trash"
            busy={props.busy === true}
            onClick={props.onConfirm}
          >
            Run it
          </Button>
        </>
      }
    >
      <Show when={props.plan}>
        {(plan) => (
          <>
            {/* The statement the *service* canonicalised, which is what the token is bound to — not
                what is in the editor, which the reader can still be typing into. */}
            <pre class="kui-ksql-confirm__statement" data-testid="ksql-confirm-statement">
              {plan().statement}
            </pre>

            <Show when={plan().deletesTopic}>
              <Banner
                tone="danger"
                message={
                  "This statement deletes the Kafka topic behind the object it drops, and every " +
                  "record in it. Nothing in KUI or in Kafka can bring those records back."
                }
                testId="ksql-confirm-deletes-topic"
              />
            </Show>

            <Show when={plan().warnings.length > 0}>
              <ul class="kui-ksql-confirm__warnings" data-testid="ksql-confirm-warnings">
                {/* The server's sentences, in the server's order. See the header. */}
                <For each={plan().warnings}>{(warning) => <li>{warning}</li>}</For>
              </ul>
            </Show>
          </>
        )}
      </Show>
    </Dialog>
  );
}
