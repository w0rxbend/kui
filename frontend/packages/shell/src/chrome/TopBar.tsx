import { Show } from "solid-js";
import { Icon } from "@kui/kernel";
import { Breadcrumb } from "./Breadcrumb.js";
import { NotificationBell, NotificationPanel, type NoticeFeed } from "./Notifications.js";
import { SearchField, type SearchFieldProps } from "./SearchField.js";
import type { Crumb } from "./types.js";

/**
 * The band across the top of the content column: where you are, what you are looking for, and the
 * controls that are not about any one page.
 *
 * ## It is a band, not a bar
 *
 * It has no fill. Scanning column 1000 of `13-topics-list.png` from the top returns the page ground
 * the whole way down (`SCREENS.md` §1.1): what used to be a 58px bar with its own surface is now
 * 58px of page with three things floating in it — the breadcrumb, the search pill, and the control
 * cluster. The height is unchanged, so nothing below it moves.
 *
 * ## What left, and where it went
 *
 * The cluster selector is gone from here. It is the environment rail now, and for a reason worth
 * stating: a dropdown answers "which cluster am I on?" only when it is asked, and that is the one
 * question an operator should never have to ask. The account avatar moved to the rail's foot with
 * it, so that identity and environment — the two things that decide what a destructive action will
 * actually destroy — sit together.
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
 */
export type ThemeMode = "auto" | "light" | "dark";

export type TopBarProps = {
  /**
   * Where you are, beginning with the cluster: `prod-kyiv-01 › Topics › analytics.clickstream`.
   *
   * This is the *installation* trail. Object pages keep a second, shorter breadcrumb in the content
   * column, and the two are not redundant: this one says where you are in the deployment and stays
   * put, that one says where you are in the section and scrolls away.
   */
  readonly crumbs?: readonly Crumb[] | undefined;
  readonly search: SearchFieldProps;
  readonly theme: ThemeMode;
  readonly onCycleTheme?: (() => void) | undefined;
  readonly onOpenAppearance?: (() => void) | undefined;
  /** Unread notifications. Zero means no marker at all. */
  readonly unreadCount?: number | undefined;
  /** Whether the notifications panel is showing. Owned by the caller, so that Escape and a click
   * elsewhere can close it from outside this component. */
  readonly notificationsOpen?: boolean | undefined;
  readonly onToggleNotifications?: (() => void) | undefined;
  /** What the panel shows. Absent means it has not been asked for yet. */
  readonly notifications?: NoticeFeed | undefined;
  readonly onMarkAllRead?: (() => void) | undefined;
  readonly onRetryNotifications?: (() => void) | undefined;
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
      {/* The trail sits at the band's left edge and takes the slack, so the search pill and the
          controls stay hard against the right however long the trail is. */}
      <div class="kui-topbar__where">
        <Show when={props.crumbs !== undefined && props.crumbs.length > 0}>
          <Breadcrumb trail={props.crumbs ?? []} />
        </Show>
      </div>

      <div class="kui-topbar__actions">
        <SearchField {...props.search} />

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

        {/* The panel is anchored to the bell rather than portalled, so that it stays under it when
            the window is resized and so that Tab moves from the bell straight into it. */}
        <div class="kui-topbar__bell-anchor">
          <NotificationBell
            unreadCount={unread()}
            open={props.notificationsOpen === true}
            onToggle={() => props.onToggleNotifications?.()}
          />
          <Show when={props.notificationsOpen === true}>
            <div class="kui-topbar__bell-panel">
              <NotificationPanel
                feed={props.notifications ?? { kind: "loading" }}
                onMarkAllRead={props.onMarkAllRead}
                onRetry={props.onRetryNotifications}
              />
            </div>
          </Show>
        </div>
      </div>
    </header>
  );
}
