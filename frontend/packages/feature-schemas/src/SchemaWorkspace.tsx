/**
 * The registry as one page with two panes.
 *
 * ## Why the list does not go away
 *
 * Reading a registry is comparing: this subject's level against that one's, this subject's format
 * against the one beside it. Two full-page screens — a list, then a subject that replaces it — turn
 * every comparison into a navigation, and the operator loses the list's ordering and their scroll
 * position each time. The design draws both panes at once (§3.15) and it is right.
 *
 * ## One address per selection
 *
 * The selection lives in the URL — `/clusters/:cluster/schemas/:subject` — and not in a signal here.
 * That is what makes a pasted link open the pane it names, which is how one engineer shows another
 * what they are looking at; a selection held in component state produces a link to the list and a
 * sentence saying "then click orders.payments.v2-value".
 *
 * ## `Register schema` is drawn and disabled, on purpose
 *
 * The design gives this screen a `+ Register schema` action and the product has no endpoint that
 * writes a schema. Hiding the control would say KUI has no opinion about registering schemas;
 * drawing it live would be a button that does nothing. It is drawn, disabled, and carries the
 * reason — the same rule `Button` enforces for an action a principal may not take, applied to an
 * action the product cannot take. See `REGISTER_UNAVAILABLE_REASON`.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Button, EmptyState } from "@kui/kernel";
import { REGISTER_UNAVAILABLE_REASON, registryVoice } from "./model.js";
import type { CompatibilityLevel } from "./data.js";

export interface SchemaWorkspaceProps {
  /** The left pane. A `SubjectList`, passed in so this component owns layout and nothing else. */
  readonly list: JSX.Element;
  /** The right pane, or `undefined` when the address names no subject. */
  readonly detail?: JSX.Element | undefined;
  /**
   * How many subjects the registry holds — the whole registry, not this page.
   *
   * `undefined` when the registry did not count, which the voice line says in words. The page's own
   * row count dressed up as a registry total would be wrong the moment there is a second page.
   */
  readonly subjectCount?: number | undefined;
  readonly globalLevel?: CompatibilityLevel | null | undefined;
}

export function SchemaWorkspace(props: SchemaWorkspaceProps): JSX.Element {
  return (
    <section class="kui-schema-workspace" aria-label="Schema registry">
      <header class="kui-schema-workspace__header">
        <div>
          <h1 class="kui-schema-workspace__title">Schema registry</h1>
          <p class="kui-schema-workspace__voice">
            {registryVoice({
              subjectCount: props.subjectCount,
              globalLevel: props.globalLevel,
            })}
          </p>
        </div>
        <Button icon="plus" disabled disabledReason={REGISTER_UNAVAILABLE_REASON}>
          Register schema
        </Button>
      </header>

      <div class="kui-schema-workspace__panes">
        <div class="kui-schema-workspace__list">{props.list}</div>
        <div class="kui-schema-workspace__detail">
          <Show
            when={props.detail !== undefined}
            fallback={
              /* Not a blank half-page. The pane says what to do with it, and it says it as an
                 instruction rather than as an error, because nothing has gone wrong. */
              <EmptyState
                kind="empty"
                title="No subject selected."
                description={
                  "Choose a subject on the left to see its versions, its compatibility level " +
                  "and its schema."
                }
              />
            }
          >
            {props.detail}
          </Show>
        </div>
      </div>
    </section>
  );
}
