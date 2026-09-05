/**
 * The subjects a registry holds, and the level it checks them against.
 *
 * ## The global level is the headline, not a footnote
 *
 * A registry's global compatibility level decides what every inheriting subject will accept
 * tomorrow, and `NONE` means it checks nothing at all. Putting that in a settings page somewhere is
 * how a cluster ends up with `NONE` set during an incident two years ago and nobody knowing. It is
 * the first thing on this screen, it says whether it is `NONE` in words rather than only in a
 * colour, and changing it is one click from where it is displayed.
 */
import { For, Show, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  Banner,
  Button,
  EmptyState,
  Pagination,
  Select,
  StatusPill,
  TextField,
  type Mutation,
} from "@kui/kernel";
import { COMPATIBILITY_LEVELS, type Compatibility, type CompatibilityLevel } from "./data.js";

export interface SubjectListProps {
  readonly subjects: readonly string[];
  readonly loading?: boolean | undefined;
  readonly global: Compatibility | undefined;
  readonly search: string;
  readonly onSearch: (text: string) => void;
  readonly page: number;
  readonly pageSize: number;
  readonly totalItems: number | undefined;
  readonly onPage: (page: number) => void;
  readonly hrefFor: (subject: string) => string;
  /** Absent when this principal may not change the global level. */
  readonly onSetGlobal?: ((level: CompatibilityLevel) => void) | undefined;
  readonly setGlobalDisabledReason?: string | undefined;
  readonly state: Mutation<unknown>;
  /** The registry is not reachable, or not configured. Drawn instead of an empty list. */
  readonly failure?: { readonly message: string; readonly code?: string | undefined } | undefined;
}

export function SubjectList(props: SubjectListProps): JSX.Element {
  const [editingGlobal, setEditingGlobal] = createSignal(false);
  const [chosen, setChosen] = createSignal<CompatibilityLevel>("BACKWARD");

  return (
    <section class="kui-schemas" aria-label="Schema registry">
      <h1 class="kui-schemas__title">Schema registry</h1>

      <Show when={props.failure}>
        {(problem) => (
          <Banner
            tone="danger"
            message={problem().message}
            {...(problem().code === undefined ? {} : { code: problem().code })}
          />
        )}
      </Show>

      <Show when={props.global}>
        {(level) => (
          <div class="kui-schemas__global">
            <span class="kui-schemas__global-label">Compatibility, for every subject that has no level of its own</span>
            <Show
              when={level().level !== null}
              fallback={
                /* An unrecognised level. Not drawn as a level: this value decides whether tomorrow's
                   schema is accepted, and showing a word the browser does not understand as though
                   it were a setting is worse than saying so. */
                <span class="kui-table__cell-muted">
                  the registry reported a level KUI does not recognise
                </span>
              }
            >
              <StatusPill tone={level().level === "NONE" ? "warning" : "neutral"}>
                {level().level ?? ""}
              </StatusPill>
              <Show when={level().level === "NONE"}>
                {/* In words as well as in a colour. `NONE` means the registry accepts any schema,
                    including one that breaks every consumer of the topic, and it is the setting
                    somebody switches on during an incident and never switches back. */}
                <span class="kui-schemas__none">
                  Nothing is checked: the registry will accept a schema that breaks existing readers.
                </span>
              </Show>
            </Show>

            <Show
              when={props.onSetGlobal !== undefined}
              fallback={
                <Show when={props.setGlobalDisabledReason}>
                  {(reason) => <span class="kui-table__cell-muted">{reason()}</span>}
                </Show>
              }
            >
              <Show
                when={editingGlobal()}
                fallback={
                  <Button variant="ghost" onClick={() => setEditingGlobal(true)}>
                    Change
                  </Button>
                }
              >
                <Select
                  label="Compatibility level"
                  labelHidden
                  value={chosen()}
                  options={COMPATIBILITY_LEVELS.map((one) => ({ value: one, label: one }))}
                  onChange={(value) => setChosen(value as CompatibilityLevel)}
                />
                <Button
                  variant="primary"
                  icon="check"
                  busy={props.state.kind === "running"}
                  onClick={() => {
                    props.onSetGlobal?.(chosen());
                    setEditingGlobal(false);
                  }}
                >
                  Save
                </Button>
                <Button variant="ghost" onClick={() => setEditingGlobal(false)}>
                  Cancel
                </Button>
              </Show>
            </Show>
          </div>
        )}
      </Show>

      <div class="kui-schemas__controls">
        <TextField
          label="Search subjects"
          labelHidden
          type="search"
          icon="search"
          placeholder="Search subjects…"
          value={props.search}
          onInput={props.onSearch}
        />
        <span class="kui-schemas__count">
          {props.totalItems === undefined
            ? `${props.subjects.length} shown`
            : `${props.subjects.length} of ${props.totalItems.toLocaleString()} subjects`}
        </span>
      </div>

      <Show
        when={props.subjects.length > 0}
        fallback={
          <Show when={props.loading !== true && props.failure === undefined}>
            <EmptyState
              kind={props.search === "" ? "empty" : "filtered"}
              title={props.search === "" ? "No subjects registered." : "No subject matches that text."}
              description={
                props.search === ""
                  ? "A subject appears here when a producer or a schema tool registers one."
                  : "No subject in this registry has that in its name."
              }
            />
          </Show>
        }
      >
        <ul class="kui-schemas__list">
          <For each={props.subjects}>
            {(subject) => (
              <li class="kui-schemas__item">
                <a class="kui-schemas__link" href={props.hrefFor(subject)}>
                  {subject}
                </a>
              </li>
            )}
          </For>
        </ul>
      </Show>

      <Pagination
        page={props.page}
        pageSize={props.pageSize}
        total={props.totalItems}
        shown={props.subjects.length}
        onPage={props.onPage}
        hasNext={props.totalItems === undefined && props.subjects.length === props.pageSize}
        label="Subject list pages"
      />
    </section>
  );
}
