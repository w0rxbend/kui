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
  Tag,
  TextField,
  type Mutation,
} from "@kui/kernel";
import {
  COMPATIBILITY_LEVELS,
  type Compatibility,
  type CompatibilityLevel,
  type SubjectRow,
} from "./data.js";
import { formatTone, levelPhrase, rowLabel, versionCountSentence } from "./model.js";

export interface SubjectListProps {
  readonly subjects: readonly SubjectRow[];
  readonly loading?: boolean | undefined;
  readonly global: Compatibility | undefined;
  readonly search: string;
  readonly onSearch: (text: string) => void;
  /**
   * Which way the registry orders the page.
   *
   * The *registry* orders it, not the browser: this is a page of a list that may be four thousand
   * long, so sorting here would order fifty rows out of four thousand and call it a sort. The
   * service has ordered the page since M5 and, until this control existed, nothing asked it to.
   */
  readonly direction: "asc" | "desc";
  readonly onDirection: (direction: "asc" | "desc") => void;
  readonly page: number;
  readonly pageSize: number;
  readonly totalItems: number | undefined;
  readonly onPage: (page: number) => void;
  readonly hrefFor: (subject: string) => string;
  /** The subject the right-hand pane is showing, so the row it came from reads as selected. */
  readonly selected?: string | undefined;
  /** Absent when this principal may not change the global level. */
  readonly onSetGlobal?: ((level: CompatibilityLevel) => void) | undefined;
  readonly setGlobalDisabledReason?: string | undefined;
  readonly state: Mutation<unknown>;
  /**
   * The registry is not reachable, not configured, or answering with something older than the last
   * attempt. Drawn instead of an empty list.
   *
   * `tone` is part of the failure rather than fixed at the banner, because one of these is not a
   * failure at all: a *stale* answer is real data with a badge on it, and drawing it in the tone
   * this product reserves for something being wrong tells an operator to stop trusting figures that
   * are the best anybody has. `danger` remains the default, so a caller that says nothing gets the
   * loud one.
   */
  readonly failure?:
    | {
        readonly message: string;
        readonly code?: string | undefined;
        readonly tone?: "danger" | "warning" | undefined;
      }
    | undefined;
}

export function SubjectList(props: SubjectListProps): JSX.Element {
  const [editingGlobal, setEditingGlobal] = createSignal(false);
  const [chosen, setChosen] = createSignal<CompatibilityLevel>("BACKWARD");

  return (
    <section class="kui-schemas" aria-label="Subjects">
      {/* An `h2`, not the page's `h1`: this card is the left pane of the workspace and the page's
          heading is above both panes. Rendered alone in a story it is still the first heading in the
          container, which is what the sweep checks. */}
      <h2 class="kui-schemas__title">Subjects</h2>

      <Show when={props.failure}>
        {(problem) => (
          <Banner
            tone={problem().tone ?? "danger"}
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
        <Select
          label="Order subjects"
          labelHidden
          value={props.direction}
          options={[
            { value: "asc", label: "Name A→Z" },
            { value: "desc", label: "Name Z→A" },
          ]}
          onChange={(value) => props.onDirection(value === "desc" ? "desc" : "asc")}
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
            {(row) => (
              <li class="kui-schemas__item">
                <a
                  class={[
                    "kui-schemas__link",
                    { "kui-schemas__link--selected": props.selected === row.subject },
                  ]}
                  href={props.hrefFor(row.subject)}
                  /* The selected row is the one the address names, so the link to it is the current
                     page. That is the fact a screen reader needs, and it is what the fill in the
                     design says; the class above is the same statement for everyone else. */
                  aria-current={props.selected === row.subject ? "page" : undefined}
                  /* The row's four parts are grid items with no text between them, so the name a
                     browser computes from them runs the badge into the subject:
                     `AVROorders.avro-value`. `rowLabel` states the same four facts as a sentence,
                     built from the same helpers the row draws with. */
                  aria-label={rowLabel(row)}
                >
                  {/* A row whose batch did not cover the format shows no badge. Not `AVRO`: the
                      registry holds Protobuf and JSON schemas too, and a guessed language is a
                      worse answer than a missing one. */}
                  <Show when={row.format}>
                    {(format) => (
                      <Tag class="kui-schemas__format" tone={formatTone(format())}>
                        {format()}
                      </Tag>
                    )}
                  </Show>
                  <span class="kui-schemas__name">{row.subject}</span>
                  {/* The caption the design draws as `3 versions · BACKWARD`, with the one word it
                      does not draw and this screen exists for: whether that level is the subject's
                      own or the registry's. A subject showing the global level as if it were its
                      own tells an operator the global setting is safe to change here, when this is
                      exactly the subject it will move. */}
                  <span class="kui-schemas__facts">
                    {versionCountSentence(row.versionCount)}
                    {" \u00b7 "}
                    <span
                      class={[{ "kui-schemas__inherited": row.compatibility?.inherited === true }]}
                    >
                      {levelPhrase(row.compatibility)}
                    </span>
                  </span>
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
