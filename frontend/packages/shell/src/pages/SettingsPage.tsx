/**
 * Preferences an operator sets once, and the build they are looking at.
 *
 * ## Why the controls arrive as props
 *
 * The shell owns immediate browser state plus durable principal-and-cluster synchronization. The
 * page only renders the preferences it is handed, so it stays usable while the gateway is down and
 * tests can drive it without sharing storage. A failed save is reported inline while the locally
 * cached choice keeps working.
 *
 * ## Every control takes effect immediately
 *
 * There is no Save. Appearance paints immediately and message defaults affect the next browse;
 * persistence follows in the background. A Save button would create an unnecessary chosen-but-not-
 * applied state.
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
import type {
  AccentChoice,
  DensityChoice,
  MessageViewMode,
  RootPreference,
  ThemeChoice,
} from "@kui/kernel";
import type { AppearanceSyncStatus } from "../data/appearance.js";
import type { MessageBrowserSyncStatus } from "../data/messageBrowser.js";

import {
  ACCENT_OPTIONS,
  DENSITY_OPTIONS,
  THEME_OPTIONS,
  appearanceHelp,
} from "../chrome/appearance.js";

/** One preference, as this page needs it: what it is now, and how to change it. */
export interface Preference<A extends string | number> {
  readonly choice: () => A;
  readonly select: (chosen: A) => void;
}

export interface SettingsPageProps {
  readonly theme: Preference<ThemeChoice>;
  readonly accent: Preference<AccentChoice>;
  readonly density: Preference<DensityChoice>;
  readonly messagePageSize: Preference<number>;
  readonly messageViewMode: Preference<MessageViewMode>;
  /** Whether the current cluster's choices have reached durable server storage. */
  readonly persistence?: AppearanceSyncStatus | undefined;
  /** Whether message browsing defaults have reached durable server storage. */
  readonly messagePersistence?: MessageBrowserSyncStatus | undefined;
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
          <Show when={props.persistence}>
            {(persistence) => <PersistenceStatus status={persistence()} subject="appearance" />}
          </Show>
        </div>
      </Card>

      <Card title="Message browsing">
        <div class="kui-settings__fields">
          <Select
            label="Default page size"
            value={String(props.messagePageSize.choice())}
            options={[
              { value: "25", label: "25 records" },
              { value: "50", label: "50 records" },
              { value: "100", label: "100 records" },
              { value: "250", label: "250 records" },
              { value: "500", label: "500 records" },
            ]}
            onChange={(value) => props.messagePageSize.select(Number(value))}
          />
          <p class="kui-settings__help">
            Used when a message URL does not include its own <code>limit</code>.
          </p>
          <Select
            label="Default mode"
            value={props.messageViewMode.choice()}
            options={[
              { value: "pages", label: "Pages" },
              { value: "infinite", label: "Infinite scroll" },
            ]}
            onChange={(value) => props.messageViewMode.select(value as MessageViewMode)}
          />
          <p class="kui-settings__help">
            Pages keep one offset range visible; infinite scroll preloads the next range near the end.
          </p>
          <Show when={props.messagePersistence}>
            {(persistence) => (
              <PersistenceStatus status={persistence()} subject="message browsing defaults" />
            )}
          </Show>
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

function PersistenceStatus(props: {
  readonly status: AppearanceSyncStatus | MessageBrowserSyncStatus;
  readonly subject: string;
}): JSX.Element {
  const copy = (): string => {
    switch (props.status.kind) {
      case "idle":
        return `Choose a cluster to sync ${props.subject}.`;
      case "loading":
        return `Loading this cluster's saved ${props.subject}…`;
      case "saving":
        return `Saving ${props.subject}…`;
      case "saved":
        return "Saved for this cluster.";
      case "local-only":
        return props.status.message;
    }
  };

  return (
    <p
      class="kui-settings__persistence"
      data-state={props.status.kind}
      role={props.status.kind === "local-only" ? "alert" : "status"}
      aria-live="polite"
    >
      {copy()}
    </p>
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
