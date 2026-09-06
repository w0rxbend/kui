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
 * ## `Register schema` is a control now, and the reason it can be disabled has changed
 *
 * For three waves the design's `+ Register schema` action was drawn `aria-disabled` beside a true
 * sentence: the gateway served no endpoint that wrote a schema. It serves one now, so the only
 * reason left to refuse the control is a principal without `SCHEMA:CREATE` — and a cluster KUI is
 * configured read-only for, which is the *server's* answer rather than a prediction made here, for
 * the reason `registerBlockedReason` gives.
 *
 * The dialog itself is not opened from here. This component owns layout and the header, and the
 * route owns the write, the toast and the refresh — the same split as `list`, and for the same
 * reason: a component that both arranges a page and issues a `POST` cannot be put in a story.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Button, EmptyState } from "@kui/kernel";
import { registryVoice } from "./model.js";
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
  /**
   * The subjects request has not answered yet.
   *
   * Its own prop rather than being inferred from an absent `subjectCount`, because the two are
   * different states and the voice line has different sentences for them: nothing has been asked
   * yet, versus the registry answered and did not count. Inferring one from the other is what put
   * *"The registry did not say how many subjects it holds"* on screen during every first paint.
   */
  readonly loading?: boolean | undefined;
  /** Opens the register dialog. Absent when this principal may not register a schema. */
  readonly onRegister?: (() => void) | undefined;
  /** Why the control will not press. Required by `Button` whenever `onRegister` is absent. */
  readonly registerDisabledReason?: string | undefined;
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
              loading: props.loading,
            })}
          </p>
        </div>
        {/* Two branches rather than `disabled={…}`: `Button` makes `disabledReason` mandatory
            exactly when `disabled` is true, which is the rule that stops a greyed-out control with
            no explanation beside it. */}
        <Show
          when={props.onRegister}
          fallback={
            <Button
              icon="plus"
              disabled
              disabledReason={
                props.registerDisabledReason ??
                "You do not have permission to register a schema in this cluster's registry."
              }
            >
              Register schema
            </Button>
          }
        >
          {(open) => (
            <Button icon="plus" variant="primary" onClick={() => open()()}>
              Register schema
            </Button>
          )}
        </Show>
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
