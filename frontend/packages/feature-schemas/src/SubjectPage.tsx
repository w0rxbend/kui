/**
 * One subject: its versions, its compatibility level, and the schema text itself.
 *
 * ## The version list is newest first, and the id is not the version
 *
 * A subject has version numbers, which count from 1 and are local to the subject, and each version
 * also has a registry-wide **id**, which is the number written into every record's header. They are
 * different numbers and an operator debugging a decode failure needs the id, not the version — the
 * record does not carry a version. Both are shown, labelled, and never conflated.
 *
 * ## The schema text is not pretty-printed
 *
 * It is shown exactly as the registry stores it, because that string is what the registry compares
 * for compatibility and what a producer's tooling will send. Reformatting it here would make a
 * "these are identical" comparison in a terminal fail for reasons the operator cannot see.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Banner, Select, StatusPill, type Mutation } from "@kui/kernel";
import { CompatibilityCheck } from "./CompatibilityCheck.jsx";
import {
  COMPATIBILITY_LEVELS,
  type Compatibility,
  type CompatibilityVerdict,
  type CompatibilityLevel,
  type ProposedSchema,
  type SchemaVersion,
} from "./data.js";

export interface SubjectPageProps {
  readonly subject: string;
  readonly versions: readonly number[];
  readonly current: SchemaVersion | undefined;
  readonly compatibility: Compatibility | undefined;
  readonly loading?: boolean | undefined;
  readonly listHref: string;
  readonly hrefForVersion: (version: number) => string;
  readonly onSetCompatibility?: ((level: CompatibilityLevel) => void) | undefined;
  readonly setCompatibilityDisabledReason?: string | undefined;
  readonly state: Mutation<unknown>;
  readonly failure?: { readonly message: string; readonly code?: string | undefined } | undefined;
  /**
   * Runs the "check a schema" panel's question. Absent leaves the panel off the page entirely, which
   * is what a story showing only the registered schema wants — it is not a permission gate: the
   * check is a read and every principal who may see this page may ask it.
   */
  readonly onCheckCompatibility?: ((proposed: ProposedSchema) => void) | undefined;
  readonly checkState?: Mutation<CompatibilityVerdict> | undefined;
}

export function SubjectPage(props: SubjectPageProps): JSX.Element {
  return (
    <section class="kui-subject" aria-label={`Subject ${props.subject}`}>
      <nav class="kui-subject__trail" aria-label="Breadcrumb">
        <a href={props.listHref}>Schema registry</a>
      </nav>

      <h1 class="kui-subject__title">{props.subject}</h1>

      <Show when={props.failure}>
        {(problem) => (
          <Banner
            tone="danger"
            message={problem().message}
            {...(problem().code === undefined ? {} : { code: problem().code })}
          />
        )}
      </Show>

      <Show when={props.compatibility}>
        {(level) => (
          <div class="kui-subject__compat">
            <span class="kui-subject__compat-label">Compatibility</span>
            <StatusPill tone={level().level === "NONE" ? "warning" : "neutral"}>
              {level().level ?? "not recognised"}
            </StatusPill>
            {/* The distinction the whole feature turns on: a subject either has its own level or
                follows the registry's. Changing the global one moves every subject in the second
                group, and an operator who cannot see which group a subject is in cannot know what
                they are about to change. */}
            <span class="kui-subject__compat-source">
              {level().inherited
                ? "inherited from the registry's global level"
                : "set on this subject"}
            </span>

            <Show
              when={props.onSetCompatibility !== undefined}
              fallback={
                <Show when={props.setCompatibilityDisabledReason}>
                  {(reason) => <span class="kui-table__cell-muted">{reason()}</span>}
                </Show>
              }
            >
              <Select
                label="Compatibility level"
                labelHidden
                value={level().level ?? "BACKWARD"}
                options={COMPATIBILITY_LEVELS.map((one) => ({ value: one, label: one }))}
                onChange={(value) => props.onSetCompatibility?.(value as CompatibilityLevel)}
                disabled={props.state.kind === "running"}
              />
            </Show>
          </div>
        )}
      </Show>

      <Show when={props.versions.length > 0}>
        <div class="kui-subject__versions">
          <span class="kui-subject__versions-label">Versions</span>
          <ul class="kui-subject__version-list">
            <For each={props.versions}>
              {(version) => (
                <li>
                  <a
                    href={props.hrefForVersion(version)}
                    aria-current={props.current?.version === version ? "page" : undefined}
                  >
                    v{version}
                  </a>
                </li>
              )}
            </For>
          </ul>
        </div>
      </Show>

      <Show when={props.current}>
        {(schema) => (
          <>
            <dl class="kui-subject__facts">
              <div>
                <dt>Version</dt>
                <dd>{schema().version}</dd>
              </div>
              <div>
                {/* The number a record's header actually carries, and the one an operator needs
                    when a decode fails. It is registry-wide and is not the version. */}
                <dt>Schema id</dt>
                <dd>{schema().id}</dd>
              </div>
              <div>
                <dt>Type</dt>
                <dd>{schema().schemaType}</dd>
              </div>
            </dl>

            <Show when={schema().references.length > 0}>
              <div class="kui-subject__references">
                <span class="kui-subject__versions-label">References</span>
                <ul>
                  <For each={schema().references}>
                    {(reference) => (
                      <li>
                        {reference.name} → {reference.subject} v{reference.version}
                      </li>
                    )}
                  </For>
                </ul>
              </div>
            </Show>

            {/* Exactly as the registry stores it. Reformatting would make a byte-for-byte comparison
                in a terminal fail for reasons that are invisible on this screen. */}
            <pre class="kui-subject__definition" tabindex={0}>
              <code>{schema().definition}</code>
            </pre>
          </>
        )}
      </Show>

      {/* Below the registered schema, because the ordinary use is to read what is there, copy it and
          change one field — and the box starts with that schema for the same reason. */}
      <Show when={props.onCheckCompatibility}>
        {(check) => (
          <CompatibilityCheck
            subject={props.subject}
            level={props.compatibility?.level}
            initialSchemaType={props.current?.schemaType}
            initialDefinition={props.current?.definition}
            onCheck={(proposed) => check()(proposed)}
            state={props.checkState ?? { kind: "idle" }}
          />
        )}
      </Show>
    </section>
  );
}
