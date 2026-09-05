/**
 * The four preferences an operator sets once, and the build they are looking at.
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
 * There is no Save. These are four attributes on the `<html>` element and each is written the moment
 * it is chosen, so the page you are changing is the demonstration of the change. A Save button would
 * imply a round trip that does not exist and a state — chosen but not applied — that cannot occur.
 */
import { For } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Card, Select } from "@kui/kernel";
import type { AccentChoice, DensityChoice, RootPreference, ThemeChoice } from "@kui/kernel";

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

const THEMES: readonly { readonly value: ThemeChoice; readonly label: string }[] = [
  // `auto` first, because it is the default and the one that is right for most people: a laptop
  // switching to dark at sunset re-themes an open tab without anybody choosing anything.
  { value: "auto", label: "Match the system" },
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
];

const ACCENTS: readonly { readonly value: AccentChoice; readonly label: string }[] = [
  { value: "blue", label: "Blue" },
  { value: "teal", label: "Teal" },
  { value: "green", label: "Green" },
  { value: "amber", label: "Amber" },
];

const DENSITIES: readonly { readonly value: DensityChoice; readonly label: string }[] = [
  { value: "comfortable", label: "Comfortable" },
  { value: "compact", label: "Compact" },
];

export function SettingsPage(props: SettingsPageProps): JSX.Element {
  return (
    <div class="kui-settings" data-testid="page-settings">
      <h1 class="kui-settings__title">Settings</h1>

      <Card title="Appearance">
        <div class="kui-settings__fields">
          <Select
            label="Theme"
            value={props.theme.choice()}
            options={THEMES}
            onChange={(value) => props.theme.select(value as ThemeChoice)}
          />
          {/* `Select` carries no help text of its own, so the explanation is a sibling. It is worth
              the line: "auto" is the default and nobody guesses that it keeps following the system
              rather than resolving once at load. */}
          <p class="kui-settings__help">
            Match the system follows the operating system, including when it changes at sunset.
          </p>
          <Select
            label="Accent"
            value={props.accent.choice()}
            options={ACCENTS}
            onChange={(value) => props.accent.select(value as AccentChoice)}
          />
          {/* Not "colour scheme": the accent is one hue used for selection and primary actions, and
              it does not change whether the interface is light or dark. */}
          <p class="kui-settings__help">
            The colour used for the selected item and the primary action.
          </p>
          <Select
            label="Density"
            value={props.density.choice()}
            options={DENSITIES}
            onChange={(value) => props.density.select(value as DensityChoice)}
          />
          <p class="kui-settings__help">
            Compact fits more rows on screen by tightening the tables, and changes nothing else.
          </p>
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

/** Narrows a kernel `RootPreference` to what this page uses. Present so the page's type stays small. */
export function asPreference<A extends string>(preference: RootPreference<A>): Preference<A> {
  return { choice: preference.choice, select: preference.select };
}
