/**
 * A topic's configuration, and changing one key of it.
 *
 * ## Three of thirty-three
 *
 * Kafka reports every configuration key for every topic, and on an ordinary topic thirty of them
 * hold the broker's default. The three that somebody set are the reason anybody opens this screen —
 * "why is this topic behaving differently from the others" is answered by those three and by nothing
 * else — so they are shown first, badged, and the rest are behind a switch.
 *
 * That is the opposite of a table sorted by name with everything equal in it, which is what makes
 * finding an override a twenty-minute job.
 *
 * ## Editing is one key at a time, on purpose
 *
 * The endpoint is incremental: keys named in neither `set` nor `remove` are untouched. A form that
 * submitted the whole table would turn every inherited value into an explicit override the first
 * time anybody saved anything — thirty new overrides, none of which anybody chose, and all of which
 * then stop tracking the broker default.
 *
 * ## Reset is not "set it to the default"
 *
 * Removing an override puts the key back to *the broker's default for it*, which is not the same as
 * setting it to that default's current value: the difference shows up the next time somebody changes
 * the broker default, and one of the two topics follows and the other does not. So reset sends
 * `remove`, and the button says "Reset to default" rather than filling the box in.
 */
import { For, Show, createEffect, createMemo, createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  Banner,
  Button,
  Checkbox,
  Dialog,
  StatusPill,
  TextField,
  type Mutation,
} from "@kui/kernel";
import type { ConfigEntry, TopicConfig } from "./config.js";

export interface ConfigChange {
  readonly set?: Readonly<Record<string, string>> | undefined;
  readonly remove?: readonly string[] | undefined;
}

export interface TopicSettingsProps {
  readonly config: TopicConfig;
  readonly loading?: boolean | undefined;
  /** Absent when this principal may not change the configuration; then nothing is editable. */
  readonly onChange?: ((change: ConfigChange) => void) | undefined;
  /** Why editing is not offered. Shown once, at the top, rather than on thirty-three rows. */
  readonly changeDisabledReason?: string | undefined;
  readonly state: Mutation<unknown>;
}

export function TopicSettings(props: TopicSettingsProps): JSX.Element {
  const [showInherited, setShowInherited] = createSignal(false);
  const [search, setSearch] = createSignal("");
  const [editing, setEditing] = createSignal<ConfigEntry | undefined>(undefined);

  /*
   * A saved change closes the editor, and the table behind it is the receipt.
   *
   * This is the opposite of the produce drawer, which stays open on success — and the difference is
   * that a produced record leaves no trace on the screen behind it, so the drawer has to report the
   * partition and offset itself, whereas an edited setting is *right there* in the row, showing the
   * value the broker stored rather than the one that was typed.
   *
   * Leaving it open was not merely redundant: the dialog is modal, so after a save it sat over the
   * table blocking every other row, with nothing on it to say the save had worked.
   */
  createEffect(
    () => props.state.kind,
    (kind) => {
      if (kind === "done") setEditing(undefined);
    },
  );

  const visible = createMemo(() => {
    const needle = search().trim().toLowerCase();
    return props.config.entries.filter(
      (entry) =>
        (showInherited() || entry.source === "topic") &&
        (needle === "" || entry.name.toLowerCase().includes(needle)),
    );
  });

  /*
   * Local filtering is right here and wrong on the topic list, and the difference is worth stating:
   * this is the *whole* configuration — thirty-three rows, all of them already in the browser — not
   * one page of a list the server is holding the rest of.
   */

  const withheld = () => props.config.entries.length === 0 && props.loading !== true;

  return (
    <section class="kui-topic-config" aria-label="Configuration">
      <Show when={props.changeDisabledReason}>
        {(reason) => <Banner tone="info" message={reason()} />}
      </Show>

      <Show when={withheld()}>
        <Banner
          tone="info"
          /* ADR-039: a caller who may see the topic but not its configuration gets a
             `not_permitted` view rather than a 403, so the rest of the page keeps working. Drawn as
             an empty table it would read as "this topic has no configuration", which is a very
             different and entirely false statement. */
          message="This topic's configuration was not returned. Either the broker did not describe it, or you may see the topic but not its settings."
        />
      </Show>

      <div class="kui-topic-config__controls">
        <TextField
          label="Search settings"
          labelHidden
          type="search"
          icon="search"
          placeholder="Search settings…"
          value={search()}
          onInput={setSearch}
        />
        <Checkbox
          label="Show inherited settings"
          checked={showInherited()}
          onChange={setShowInherited}
        />
        <span class="kui-topic-config__count">{overrideCount(props.config)}</span>
      </div>

      <Show
        when={visible().length > 0}
        fallback={
          <Show when={!withheld()}>
            <p role="status" class="kui-topic-config__empty">
              <Show
                when={search().trim() !== ""}
                fallback={
                  /* Nothing overridden is a *fact about the topic*, and a good one: it is behaving
                     exactly like every other topic on this cluster. Saying so beats an empty table. */
                  <>
                    Nothing is set on this topic — every setting is the broker's own. Show inherited
                    settings to see what those are.
                  </>
                }
              >
                No setting has that in its name.
              </Show>
            </p>
          </Show>
        }
      >
        <table class="kui-topic-config__table">
          <caption class="kui-visually-hidden">This topic's configuration</caption>
          <thead>
            <tr>
              <th scope="col">Setting</th>
              <th scope="col">Value</th>
              <th scope="col">Where it comes from</th>
              <th scope="col">
                <span class="kui-visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <For each={visible()}>
              {(entry) => (
                <tr>
                  <th scope="row" class="kui-topic-config__key">
                    {entry.name}
                  </th>
                  <td class="kui-topic-config__value">
                    <Show
                      when={entry.value !== null}
                      fallback={
                        /* A sensitive key arrives with no value. Never an empty box: an empty box
                           beside Save invites somebody to overwrite a secret with "". */
                        <span class="kui-table__cell-muted">
                          <span aria-hidden="true">—</span>
                          <span class="kui-visually-hidden">
                            hidden, because the broker marks this setting sensitive
                          </span>
                        </span>
                      }
                    >
                      <code>{entry.value}</code>
                    </Show>
                  </td>
                  <td>
                    <Show
                      when={entry.source === "topic"}
                      fallback={<span class="kui-table__cell-muted">broker default</span>}
                    >
                      <StatusPill tone="accent">set on this topic</StatusPill>
                    </Show>
                  </td>
                  <td class="kui-topic-config__actions">
                    <Show
                      when={props.onChange !== undefined && !entry.readOnly && !entry.sensitive}
                    >
                      <Button variant="ghost" onClick={() => setEditing(entry)}>
                        Edit
                      </Button>
                    </Show>
                    <Show when={entry.readOnly}>
                      {/* Kafka will refuse it. Saying so here beats letting somebody compose a
                          change and learn it on save. */}
                      <span class="kui-table__cell-muted">read-only</span>
                    </Show>
                  </td>
                </tr>
              )}
            </For>
          </tbody>
        </table>
      </Show>

      <Show when={editing()}>
        {(entry) => (
          <EditSettingDialog
            entry={entry()}
            onClose={() => setEditing(undefined)}
            onChange={(change) => props.onChange?.(change)}
            state={props.state}
          />
        )}
      </Show>
    </section>
  );
}

/** The count beside the controls. Names the figure an operator actually wants. */
export function overrideCount(config: TopicConfig): string {
  const total = config.entries.length;
  if (total === 0) return "";
  if (config.overridden === 0) return `nothing set on this topic, ${total} inherited`;
  return `${config.overridden} set on this topic, ${total - config.overridden} inherited`;
}

function EditSettingDialog(props: {
  readonly entry: ConfigEntry;
  readonly onClose: () => void;
  readonly onChange: (change: ConfigChange) => void;
  readonly state: Mutation<unknown>;
}): JSX.Element {
  const [value, setValue] = createSignal(props.entry.value ?? "");
  const busy = () => props.state.kind === "running";
  const changed = () => value() !== (props.entry.value ?? "");

  const failure = () =>
    props.state.kind === "failed" || props.state.kind === "forbidden" ? props.state : undefined;

  return (
    <Dialog
      open
      onClose={props.onClose}
      title={props.entry.name}
      size="md"
      closeOnScrimClick={false}
      testId="edit-setting-dialog"
      actions={
        <>
          <Show
            when={props.entry.source === "topic" && !busy()}
            fallback={
              <span class="kui-visually-hidden">
                This setting is already the broker's default, so there is nothing to reset.
              </span>
            }
          >
            {/* Sends `remove`, not `set` to the default's current value. Those are different
                instructions: one keeps following the broker default, the other freezes today's
                value and stops following it. */}
            <Button variant="ghost" onClick={() => props.onChange({ remove: [props.entry.name] })}>
              Reset to default
            </Button>
          </Show>
          <Button variant="ghost" onClick={props.onClose}>
            Cancel
          </Button>
          <Show
            when={changed() && !busy()}
            fallback={
              <Button
                variant="primary"
                icon="check"
                busy={busy()}
                disabled
                disabledReason={busy() ? "The change is being saved." : "Change the value first."}
              >
                Save
              </Button>
            }
          >
            <Button
              variant="primary"
              icon="check"
              onClick={() => props.onChange({ set: { [props.entry.name]: value() } })}
            >
              Save
            </Button>
          </Show>
        </>
      }
    >
      <div class="kui-edit-setting">
        <TextField
          label="Value"
          value={value()}
          onInput={setValue}
          mono
          help={
            props.entry.defaultValue === null
              ? "The broker did not say what this defaults to."
              : `The broker's default is ${props.entry.defaultValue}.`
          }
        />

        <Show when={props.entry.documentation}>
          {(text) => (
            /* Kafka's own prose, and the only documentation an operator has to hand. It contains
               HTML — <code> and links — which is rendered as text rather than as markup: this
               string comes from a broker, and a broker is not a trusted source of HTML for this
               page. Tags in it read oddly; injected markup would read a great deal worse. */
            <p class="kui-edit-setting__doc">{text()}</p>
          )}
        </Show>

        <Show when={failure()}>
          {(problem) => (
            <Banner
              tone="danger"
              message={problem().message}
              code={problem().kind === "failed" ? (problem() as { code: string }).code : undefined}
            />
          )}
        </Show>
      </div>
    </Dialog>
  );
}
