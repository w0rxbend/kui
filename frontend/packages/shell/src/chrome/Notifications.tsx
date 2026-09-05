/**
 * The bell in the top bar, and the panel it opens.
 *
 * ## The empty panel is the point
 *
 * A bell that opens nothing when there is no news is indistinguishable from a bell that is broken,
 * and an operator who cannot tell those apart stops trusting the bell — after which the one
 * notification that mattered goes unread. So the panel **always opens**, and when it is empty it
 * says so in words (`SCREENS.md` §2.9).
 *
 * The same reasoning covers the failure case. If notifications could not be fetched, the panel
 * opens and says that, with a retry. It never shows an empty list for a failed request, and it
 * never shows a stale list without admitting it is stale.
 *
 * ## Read and unread are a real distinction, and the dot is not the signal
 *
 * The bell's dot is decoration: the count is in the accessible name ("Notifications, 3 unread"),
 * and when nothing is unread there is no dot at all rather than a grey one. A marker that is always
 * present is a marker nobody looks at.
 *
 * Inside the panel, read items stay where they are rather than being hidden or moved. Hiding them
 * would mean an operator who read a notification by accident has no way back to it; moving them
 * would reorder the list under the pointer at the moment of the click.
 *
 * They are marked by *losing emphasis* — no marker dot, and a title that drops from strong to muted
 * — rather than by being dimmed with `--kui-opacity-stale`, which is what the rest of this product
 * uses for stale data and what this component did first. Dimming a whole row composites its 11px
 * body text down to 3.24:1 in dark and 2.82:1 in light. Dimming is safe on a figure that is
 * repeated elsewhere; it is not safe on the only copy of a sentence.
 *
 * ## Dismissal is the caller's
 *
 * This component reports "the user opened it", "the user marked all read", "the user clicked one".
 * It does not decide what any of those mean. Whether opening the panel marks its contents read is a
 * product decision with a real trade-off — it is convenient, and it silently destroys the unread
 * set of anyone who opens the bell to check the time — so it is made at the call site where that
 * trade-off is visible, not buried in a component.
 */
import { For, Show } from "solid-js";
import { Button, Icon, IconTile, Spinner, relativeAge, type IconName, type TileTone } from "@kui/kernel";

/**
 * How serious one notification is.
 *
 * Four cases, matching the four the design draws. They select the tile's tone and its glyph
 * together, so that the two can never disagree — a red tile with an information glyph is a
 * rendering that says two different things at once.
 */
export type NoticeSeverity = "info" | "success" | "warning" | "danger";

export type Notice = {
  readonly id: string;
  readonly severity: NoticeSeverity;
  /** Always present. A notification with no title is a coloured square. */
  readonly title: string;
  /** The sentence under the title. May be absent; the title then centres against the tile. */
  readonly body?: string | undefined;
  readonly at: Date;
  readonly read?: boolean | undefined;
  /** Where clicking it goes, when there is somewhere. */
  readonly href?: string | undefined;
};

/**
 * What the panel is showing.
 *
 * A union rather than `notices + loading + error` flags, for the reason the rest of this codebase
 * gives: three booleans describe eight states, five of which are nonsense, and the nonsense is
 * exactly what gets rendered when a request fails halfway.
 */
export type NoticeFeed =
  | { readonly kind: "loading" }
  | { readonly kind: "ready"; readonly notices: readonly Notice[] }
  /** We have notices, and they are out of date. Shown, with the reason. */
  | { readonly kind: "stale"; readonly notices: readonly Notice[]; readonly reason: string }
  | { readonly kind: "failed"; readonly reason: string };

const TONE: Record<NoticeSeverity, TileTone> = {
  info: "primary",
  success: "success",
  warning: "warning",
  danger: "danger",
};

const GLYPH: Record<NoticeSeverity, IconName> = {
  info: "info",
  success: "check",
  warning: "warning",
  danger: "error",
};

export type NotificationBellProps = {
  readonly unreadCount: number;
  readonly open: boolean;
  readonly onToggle: () => void;
};

export function NotificationBell(props: NotificationBellProps) {
  const label = () =>
    props.unreadCount > 0 ? `Notifications, ${props.unreadCount} unread` : "Notifications, none unread";

  return (
    <button
      type="button"
      class={["kui-bell", "kui-focusable", { "kui-bell--open": props.open }]}
      aria-label={label()}
      aria-expanded={props.open ? "true" : "false"}
      aria-haspopup="dialog"
      data-testid="notifications"
      onClick={() => props.onToggle()}
    >
      <Icon name="bell" size="18px" />
      <Show when={props.unreadCount > 0}>
        {/* Decoration: the count is already in the accessible name above. Beyond nine it becomes
            "9+", because a three-digit badge is wider than the bell it hangs off. */}
        <span class="kui-bell__badge" aria-hidden="true">
          {props.unreadCount > 9 ? "9+" : props.unreadCount}
        </span>
      </Show>
    </button>
  );
}

export type NotificationPanelProps = {
  readonly feed: NoticeFeed;
  readonly onMarkAllRead?: (() => void) | undefined;
  readonly onOpenNotice?: ((id: string) => void) | undefined;
  readonly onRetry?: (() => void) | undefined;
  /** For relative ages. Injected so a story and a test are not at the mercy of the clock. */
  readonly now?: Date | undefined;
};

export function NotificationPanel(props: NotificationPanelProps) {
  const notices = (): readonly Notice[] =>
    props.feed.kind === "ready" || props.feed.kind === "stale" ? props.feed.notices : [];

  const anyUnread = () => notices().some((notice) => notice.read !== true);

  return (
    // `role="dialog"` rather than a bare div: it is a panel the bell owns, it is dismissed with
    // Escape, and a screen reader needs to be told it opened.
    <div class="kui-notices" role="dialog" aria-label="Notifications" data-testid="notification-panel">
      <header class="kui-notices__head">
        <h2 class="kui-notices__title">Notifications</h2>
        <Show when={anyUnread() && props.onMarkAllRead !== undefined}>
          <button type="button" class="kui-notices__mark kui-focusable" onClick={() => props.onMarkAllRead?.()}>
            Mark all read
          </button>
        </Show>
      </header>

      <Show when={props.feed.kind === "stale" ? props.feed : undefined}>
        {(stale) => (
          <p class="kui-notices__stale">
            <Icon name="warning" /> {stale().reason}
          </p>
        )}
      </Show>

      <Show when={props.feed.kind === "loading"}>
        <div class="kui-notices__state">
          <Spinner />
          <p>Fetching notifications…</p>
        </div>
      </Show>

      <Show when={props.feed.kind === "failed" ? props.feed : undefined}>
        {(failed) => (
          <div class="kui-notices__state">
            <p>{failed().reason}</p>
            <Show when={props.onRetry !== undefined}>
              <Button variant="secondary" size="sm" icon="refresh" onClick={() => props.onRetry?.()}>
                Try again
              </Button>
            </Show>
          </div>
        )}
      </Show>

      <Show when={(props.feed.kind === "ready" || props.feed.kind === "stale") && notices().length === 0}>
        {/* Words, not a blank panel. This is the case the component exists to get right. */}
        <div class="kui-notices__state">
          <p>Nothing to report. The cluster has been quiet.</p>
        </div>
      </Show>

      <Show when={notices().length > 0}>
        {/* `tabindex` because it scrolls: a scrollable region outside the tab order cannot be
            scrolled from the keyboard at all. */}
        <ul class="kui-notices__list" tabindex={0}>
          <For each={notices()}>
            {(notice) => <NoticeRow notice={notice} now={props.now} onOpen={props.onOpenNotice} />}
          </For>
        </ul>
      </Show>
    </div>
  );
}

function NoticeRow(props: {
  readonly notice: Notice;
  readonly now: Date | undefined;
  readonly onOpen: ((id: string) => void) | undefined;
}) {
  const notice = () => props.notice;
  const interactive = () => notice().href !== undefined || props.onOpen !== undefined;

  const content = () => (
    <>
      <IconTile icon={GLYPH[notice().severity]} tone={TONE[notice().severity]} />
      <span class="kui-notices__text">
        <span class="kui-notices__notice-title">{notice().title}</span>
        <Show when={notice().body}>{(body) => <span class="kui-notices__body">{body()}</span>}</Show>
      </span>
      {/* The absolute time is in the title: a relative age is easier to read and impossible to
          correlate with a log line, so both have to be reachable. */}
      <span class="kui-notices__age" title={notice().at.toISOString()}>
        {relativeAge(notice().at, props.now ?? new Date())}
      </span>
      <Show when={notice().read !== true}>
        {/* Decoration; the row's accessible name below says "unread" in words. */}
        <span class="kui-notices__unread" aria-hidden="true" />
      </Show>
    </>
  );

  return (
    <li
      class={["kui-notices__item", { "kui-notices__item--read": notice().read === true }]}
      data-testid={`notice-${notice().id}`}
      /* In words, because the marker dot and the lost emphasis are both visual. */
      aria-label={notice().read === true ? undefined : `${notice().title} — unread`}
    >
      <Show when={interactive()} fallback={<div class="kui-notices__row">{content()}</div>}>
        <a
          class="kui-notices__row kui-notices__row--link kui-focusable"
          href={notice().href ?? "#"}
          onClick={() => props.onOpen?.(notice().id)}
        >
          {content()}
        </a>
      </Show>
    </li>
  );
}
