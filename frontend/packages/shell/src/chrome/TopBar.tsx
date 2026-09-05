import { Show } from "solid-js";
import { Avatar, Icon } from "@kui/kernel";
import { ClusterSelector } from "./ClusterSelector.js";
import { SearchField, type SearchFieldProps } from "./SearchField.js";
import type { ClusterSummary } from "./types.js";

/**
 * The bar across the top of the content column: search, cluster, theme, appearance, notifications,
 * account.
 *
 * ## Three theme states, not two
 *
 * The theme control cycles auto → light → dark and shows which one is in force. Two states would be
 * simpler and would be wrong: an operator on "follow the system" whose laptop turns dark at sunset
 * expects KUI to turn with it, and one who explicitly chose light expects light at midnight. A
 * toggle cannot express the difference between "light" and "light because everything else is", and
 * the difference is the whole point. The accessible name says the mode in words — "Theme: follows
 * system" — because the glyph alone cannot distinguish "currently light" from "auto, and it is
 * daytime".
 *
 * ## The bell counts, and says so
 *
 * A dot with no number is a colour-only signal. The count goes in the accessible name
 * ("Notifications, 3 unread"), and when there is nothing unread there is no dot at all rather than a
 * grey one — a permanently present marker is a marker nobody looks at.
 *
 * ## An avatar with no identity is not initials
 *
 * If the identity service is unavailable the avatar is a neutral person glyph, not guessed initials.
 * Inventing somebody's initials is worse than admitting we do not know them, and in a product where
 * the avatar is how you check whose credentials are about to purge a topic, it is worse by a lot.
 */
export type ThemeMode = "auto" | "light" | "dark";

export type TopBarProps = {
  readonly search: SearchFieldProps;
  readonly clusters: readonly ClusterSummary[];
  readonly currentClusterId?: string | undefined;
  readonly onSelectCluster?: ((id: string) => void) | undefined;
  readonly onAddCluster?: (() => void) | undefined;
  readonly theme: ThemeMode;
  readonly onCycleTheme?: (() => void) | undefined;
  readonly onOpenAppearance?: (() => void) | undefined;
  /** Unread notifications. Zero means no marker at all. */
  readonly unreadCount?: number | undefined;
  readonly onOpenNotifications?: (() => void) | undefined;
  /** The signed-in principal's display name, or `undefined` when identity is unavailable. */
  readonly accountName?: string | undefined;
  readonly onOpenAccount?: (() => void) | undefined;
};

const THEME_LABEL: Record<ThemeMode, string> = {
  auto: "Theme: follows system. Change theme",
  light: "Theme: light. Change theme",
  dark: "Theme: dark. Change theme",
};

const THEME_ICON = { auto: "theme-auto", light: "sun", dark: "moon" } as const;

export function TopBar(props: TopBarProps) {
  const unread = () => props.unreadCount ?? 0;

  return (
    <header class="kui-topbar" data-testid="topbar">
      <SearchField {...props.search} />

      <div class="kui-topbar__actions">
        <ClusterSelector
          clusters={props.clusters}
          currentId={props.currentClusterId}
          onSelect={props.onSelectCluster}
          onAdd={props.onAddCluster}
        />

        <button
          type="button"
          class="kui-topbar__icon-button"
          aria-label={THEME_LABEL[props.theme]}
          onClick={() => props.onCycleTheme?.()}
          data-testid="theme-control"
        >
          <Icon name={THEME_ICON[props.theme]} size="18px" />
        </button>

        <button
          type="button"
          class="kui-topbar__icon-button"
          aria-label="Appearance: accent colour and density"
          onClick={() => props.onOpenAppearance?.()}
          data-testid="appearance-control"
        >
          <Icon name="sliders" size="18px" />
        </button>

        <button
          type="button"
          class="kui-topbar__icon-button kui-topbar__bell"
          aria-label={unread() > 0 ? `Notifications, ${unread()} unread` : "Notifications, none unread"}
          onClick={() => props.onOpenNotifications?.()}
          data-testid="notifications"
        >
          <Icon name="bell" size="18px" />
          <Show when={unread() > 0}>
            <span class="kui-topbar__bell-dot" aria-hidden="true" />
          </Show>
        </button>

        {/* The kernel's own Avatar: it owns the rule that an unknown identity is a neutral person
            glyph rather than guessed initials, and that rule has to hold in exactly one place. */}
        <Avatar name={props.accountName} onClick={props.onOpenAccount} />
      </div>
    </header>
  );
}
