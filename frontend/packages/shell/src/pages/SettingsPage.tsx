/**
 * The three preferences an operator sets once, and the build they are looking at.
 *
 * ## Why this page reads nothing from any service
 *
 * It is one of two screens that has to keep working when everything behind KUI is down. Every value
 * on it is either a browser preference or a build string the shell already holds, so a gateway that
 * has stopped answering takes nothing away from it. Adding a server call here would remove the page
 * at exactly the moment somebody is on it trying to work out what has happened.
 *
 * ## Why the preferences arrive as props
 *
 * The preference objects in the kernel are module-level singletons backed by `localStorage`, which
 * is right for the application and wrong for a test: a suite that drove them would share state with
 * the next suite and would need a working browser storage. So the page is handed them, and the shell
 * is the one place that hands it the real ones. That is what makes it possible to assert "changing
 * this control writes to this preference and to nothing else".
 *
 * ## Every control takes effect immediately
 *
 * There is no Save. These are three attributes on the `<html>` element and each is written the
 * moment it is chosen, so the page you are changing is the demonstration of the change. A Save
 * button would imply a round trip that does not exist and a state — chosen but not applied — that
 * cannot occur.
 *
 * ## Why there is no timezone and no refresh rate
 *
 * Both are on the settings screens of every product this one is compared to, and both are absent
 * here on purpose: nothing in KUI reads either preference. A control that writes a value no code
 * consults is worse than a missing control, because it answers the operator's question — "can I
 * change this?" — with a yes that is false, and the timestamps go on being rendered in the browser's
 * own zone while the setting says otherwise. `docs/FEATURE_MATRIX.md` records the absence as an
 * absence rather than as a gap. They arrive with the code that reads them.
 */
import { For, Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Card, Select } from "@kui/kernel";
import type { AccentChoice, DensityChoice, RootPreference, ThemeChoice } from "@kui/kernel";

import {
  ACCENT_OPTIONS,
  DENSITY_OPTIONS,
  THEME_OPTIONS,
  appearanceHelp,
} from "../chrome/appearance.js";

/** One preference, as this page needs it: what it is now, and how to change it. */
export interface Preference<A extends string> {
  readonly choice: () => A;
  readonly select: (chosen: A) => void;
}

export interface SettingsPageProps {
  readonly theme: Preference<ThemeChoice>;
  readonly accent: Preference<AccentChoice>;
  readonly density: Preference<DensityChoice>;
  /** The build, for a bug report. `undefined` when the shell was not told. */
  readonly version?: string | undefined;
  /** Which gateway this browser is talking to, for the same reason. */
  readonly apiBase?: string | undefined;
}

export function SettingsPage(props: SettingsPageProps): JSX.Element {
  return (
    <div class="kui-settings" data-testid="page-settings">
      <h1 class="kui-settings__title">Settings</h1>

      <Card title="Appearance">
        <div class="kui-settings__fields">
          <Select
            label="Theme"
            value={props.theme.choice()}
            options={THEME_OPTIONS}
            onChange={(value) => props.theme.select(value as ThemeChoice)}
          />
          {/* `Select` carries no help text of its own, so the explanation is a sibling — and it
              comes out of the shared table rather than being written here. This page used to spell
              the sentence itself, and its option "Match the system" while the popover's said
              "Auto": one preference with two names, which an operator can only reconcile by
              changing one control and watching the other. */}
          <Help of={appearanceHelp(THEME_OPTIONS)} />
          <Select
            label="Accent"
            value={props.accent.choice()}
            options={ACCENT_OPTIONS}
            onChange={(value) => props.accent.select(value as AccentChoice)}
          />
          {/* The sentence is "the colour used for the selected item and the primary action" and not
              "colour scheme": the accent is one hue for selection and primary actions, and it does
              not change whether the interface is light or dark. */}
          <Help of={appearanceHelp(ACCENT_OPTIONS)} />
          <Select
            label="Density"
            value={props.density.choice()}
            options={DENSITY_OPTIONS}
            onChange={(value) => props.density.select(value as DensityChoice)}
          />
          <Help of={appearanceHelp(DENSITY_OPTIONS)} />
        </div>
      </Card>

      {/*
       * The two facts a bug report needs, and the reason this page is worth loading when nothing
       * else works. "It is broken" and "build 1.4.2 talking to https://kui.internal/api is broken"
       * are different reports, and only the second can be acted on.
       */}
      <Card title="About this instance">
        <dl class="kui-settings__facts">
          <For
            each={[
              { label: "Build", value: props.version },
              { label: "API", value: props.apiBase },
            ]}
          >
            {(fact) => (
              <div>
                <dt>{fact.label}</dt>
                <dd>
                  {/* Never blank. A blank value reads as a rendering fault, where "not reported" is
                      a fact about the deployment that is itself worth putting in the report. */}
                  {fact.value ?? "not reported"}
                </dd>
              </div>
            )}
          </For>
        </dl>
      </Card>
    </div>
  );
}

/**
 * The sentence under a control, drawn only when the vocabulary carries one.
 *
 * `Show` rather than an empty paragraph, because `.kui-settings__help` has margins: an element with
 * no text still moves the control below it, and a preference whose options all explain themselves
 * would open a gap that reads as a missing line.
 */
function Help(props: { readonly of: string | undefined }): JSX.Element {
  return (
    <Show when={props.of}>{(help) => <p class="kui-settings__help">{help()}</p>}</Show>
  );
}

/** Narrows a kernel `RootPreference` to what this page uses. Present so the page's type stays small. */
export function asPreference<A extends string>(preference: RootPreference<A>): Preference<A> {
  return { choice: preference.choice, select: preference.select };
}
