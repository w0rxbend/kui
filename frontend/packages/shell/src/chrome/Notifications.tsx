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
 * ## Severity and category are two axes
 *
 * The tile's tone comes from the severity and its glyph from the category, and they are separate
 * fields for the reason `SCREENS-V4.md` §3.9 gives: `M06` draws two warnings with two different
 * glyphs, so a component that derived the glyph from the severity cannot draw the design at all.
 * See {@link NoticeCategory}.
 *
 * ## Read and unread are a real distinction, and the dot is not the signal
 *
 * The bell's dot is decoration: the count is in the accessible name ("Notifications, 3 unread"),
 * and when nothing is unread there is no dot at all rather than a grey one. A marker that is always
 * present is a marker nobody looks at.
 *
 * With an alerts feed behind it the bell says two things at once and they are two fields, not one:
 * **how many events are open** is the badge's figure, taken from the server's own count and never
 * folded from the page the browser holds; **whether this principal has read them** is the badge's
 * tone. An alert that is open stays counted after somebody has looked at it — the card beside the
 * bell draws `2 open` from the same store and the two must not disagree — so reading turns an
 * alarm into a tally rather than making it vanish. Both facts are in the accessible name in words,
 * because one of them is otherwise a colour and the other is otherwise a position.
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
 * Four cases matching the four the design draws, and a fifth that draws none of them.
 *
 * `unknown` is for a severity this build has no tone for — a word `services/alerts` began sending
 * after this bundle was built. It takes the **neutral** tile, which is the only honest answer: the
 * alarming tones would claim a seriousness nothing established, and `info`'s calm blue dot beside
 * what might be the loudest event on the cluster is the specific misreading `@kui/kernel`'s alert
 * vocabulary refuses to make on the shell's behalf — it carries the word verbatim and leaves the
 * tone to whoever has one. Dropping the row instead would be worse still: an event nobody can see
 * is an event nobody acts on.
 *
 * Severity chooses the tile's **tone** and nothing else; see {@link NoticeCategory} for what
 * chooses the glyph.
 */
export type NoticeSeverity = "info" | "success" | "warning" | "danger" | "unknown";

/**
 * What happened, as opposed to how bad it is.
 *
 * These are two axes and the shipped component collapsed them into one: the glyph was picked from
 * the severity, so every warning drew the same triangle. `SCREENS-V4.md` §3.9 shows why that cannot
 * draw the design — two of the four notifications in `M06` are both warnings and carry *different*
 * glyphs, a rebalance arrow and a disk, because they are two different things going slightly wrong.
 * Collapsing the axes loses the one that says what happened, which is the half an operator scans
 * for; the severity they can already see in the colour.
 *
 * Absent falls back to a glyph chosen from the severity, which is what every notification in the
 * product does today. That is not a placeholder to be removed later: a notification whose category
 * nothing recorded is a real case, and inventing a category for it would be worse than the generic
 * glyph — a disk icon over a rebalance is a confident lie about what broke.
 */
export type NoticeCategory =
  /** A group moving its partitions around, or a broker rejoining: the arrows of `M06`. */
  | "rebalance"
  /** A partition with no leader. `services/alerts`' first rule, and its own glyph word. */
  | "partition"
  /** A partition with fewer in-sync replicas than replicas. */
  | "replication"
  /** A log directory filling up. */
  | "storage"
  /** A connector or one of its tasks. */
  | "connector"
  /** A schema registered, or a compatibility check refused. */
  | "schema"
  /** A broker, a partition, or the cluster itself. */
  | "cluster"
  /** A topic created, configured or deleted. */
  | "topic"
  /** Somebody signed in, or a permission was refused. */
  | "security";

export type Notice = {
  readonly id: string;
  readonly severity: NoticeSeverity;
  /** What happened. Absent means nobody recorded one; see {@link NoticeCategory}. */
  readonly category?: NoticeCategory | undefined;
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
  | { readonly kind: "failed"; readonly reason: string }
  /**
   * There is no feed behind this bell at all, because the deployment configures none.
   *
   * Its own case and emphatically not `ready` with an empty list. "Nothing to report. The cluster
   * has been quiet." is a statement *about the cluster*, and a deployment that runs no alerts
   * service has not established that the cluster is quiet — it has established nothing. This is
   * ADR-032's `not_configured` rule reaching a control the rule cannot hide: the drawer's Alerts
   * row is left out entirely, but the bell is part of the frame and predates the feed, so what it
   * owes the reader is the sentence rather than an absence.
   */
  | { readonly kind: "not_configured"; readonly reason: string };

const TONE: Record<NoticeSeverity, TileTone> = {
  info: "primary",
  success: "success",
  warning: "warning",
  danger: "danger",
  unknown: "neutral",
};

/**
 * The fallback glyph, for a notification that carries no category.
 *
 * It restates the severity, which is the only thing such a notification is known to be about. That
 * is honest and it is also weak — a panel of four amber triangles is a panel nobody scans — which
 * is the argument for recording a category wherever one is known.
 */
const SEVERITY_GLYPH: Record<NoticeSeverity, IconName> = {
  info: "info",
  success: "check",
  warning: "warning",
  danger: "error",
  /* The same mark `info` takes, and that is not a collapse of the two: this is the *fallback*
     glyph, reached only when the notification also carried no category, and its whole claim is "a
     notification happened". The seriousness is in the tile's tone, which is neutral here and blue
     there. A glyph invented for the unknown case would be a picture of something nobody said. */
  unknown: "info",
};

/**
 * The glyph for each category, from the icon set the rest of the drawer already uses.
 *
 * `rebalance` takes the refresh arrows, which is the same mark the consumer screens use for a group
 * that is moving; `storage` takes the disk the storage meter is headed with. Reusing the marks the
 * feature screens use is the point — a notification is a pointer at a screen, and an operator who
 * has learned the disk glyph on the storage card should not have to learn a second one here.
 */
const CATEGORY_GLYPH: Record<NoticeCategory, IconName> = {
  rebalance: "refresh",
  /* The mark the topic screens already use for a partition list, for the reason the rest of this
     table gives: a notification is a pointer at a screen, and an operator who has learned a glyph
     on the screen it points at should not have to learn a second one here. */
  partition: "partitions",
  /* Replicas are a partition drawn across brokers, which is what the topology mark says and what no
     other glyph in this set says. Not `partitions`, because a partition with no leader and a
     partition short of replicas are the two rules an operator most needs to tell apart at a
     glance — and they arrive as two `warning` rows with, without this, one picture. */
  replication: "topology",
  storage: "disk",
  connector: "connect",
  schema: "schema",
  cluster: "brokers",
  topic: "topics",
  security: "shield",
};

/** The tone from the severity, the glyph from the category. Two axes, two lookups. */
function glyphOf(notice: Notice): IconName {
  const category = notice.category;
  return category === undefined ? SEVERITY_GLYPH[notice.severity] : CATEGORY_GLYPH[category];
}

export type NotificationBellProps = {
  readonly unreadCount: number;
  readonly open: boolean;
  readonly onToggle: () => void;
  /**
   * How many alert events are open, **as the alerts service counted them** — or `null` when
   * nothing has said yet, and absent when this deployment has no alerts feed behind the bell.
   *
   * Three values and three renderings, which is the whole reason it is not a `number`:
   *
   * - a positive figure is the badge, and it is the server's own count and never a count of the
   *   rows the browser happens to hold. The feed is paged and the bell is not, so a count folded
   *   from a page is a different number wearing the same badge.
   * - `0` is **no badge**. A permanently present marker is a marker nobody looks at, which is the
   *   rule `countBadge` already keeps for every figure down the drawer's side.
   * - `null` is also no badge, and for the opposite reason: nobody has said how many are open, and
   *   a bell that draws nothing while *claiming* nothing is open is the reassuring misreading this
   *   product refuses everywhere else. The accessible name says which of the two it is, because
   *   the absence of a badge cannot.
   *
   * Absent leaves {@link unreadCount} in charge, which is what every caller that is not the frame
   * — the stories, the notification cases — passes.
   */
  readonly openCount?: number | null | undefined;
  /**
   * Whether this principal has anything unread, from the alerts service's own per-principal unread
   * count — never a comparison the browser made over the page it happens to hold.
   *
   * It chooses the badge's *tone* and never whether the badge exists: an alert that is open is
   * open whether or not somebody has looked at it, and hiding the count once it had been read
   * would make the bell disagree with the card beside it, which draws `2 open` from the same
   * store. Reading them turns the badge from an alarm into a tally.
   *
   * Not carried by colour alone: the accessible name says "unread" in words.
   */
  readonly unread?: boolean | undefined;
};

export function NotificationBell(props: NotificationBellProps) {
  /* `undefined` means the caller supplied no feed at all, which is not the same as a feed that has
     answered `null`. The nullish coalescing has to keep those apart, so the test is explicit. */
  const alerts = () => props.openCount !== undefined;

  /**
   * What the badge says, or `undefined` when there is no badge to draw.
   *
   * A **string**, so that the `Show` below cannot be defeated by a falsy zero. No branch here can
   * produce a `0` — both are guarded by `> 0` — but `<Show when={aNumber()}>` is the exact shape
   * that made the storage meter draw an em dash over a disk it had read perfectly, one file over,
   * and a rule that is only safe because of a guard three lines away is a rule waiting to be
   * edited into a defect. Beyond nine it is `9+`: a three-digit badge is wider than the bell.
   */
  const badgeText = (): string | undefined => {
    const open = props.openCount;
    const count =
      open === undefined
        ? props.unreadCount
        : open !== null && Number.isFinite(open)
          ? open
          : 0;
    return count > 0 ? (count > 9 ? "9+" : String(count)) : undefined;
  };

  const label = () => {
    if (!alerts()) {
      return props.unreadCount > 0
        ? `Notifications, ${props.unreadCount} unread`
        : "Notifications, none unread";
    }
    const open = props.openCount;
    /* The three sentences the three values need. "None open" for a `null` would be a claim about
       the cluster that nothing measured — the same defect as a `0` badge, moved into the words a
       screen reader is given. */
    if (open === null || !Number.isFinite(open)) {
      return "Notifications, the number of open alerts is not known";
    }
    if (open === 0) return "Notifications, no open alerts";
    const read = props.unread === true ? ", unread" : "";
    return `Notifications, ${open} open ${open === 1 ? "alert" : "alerts"}${read}`;
  };

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
      <Show when={badgeText()}>
        {(text) => (
          /* Decoration: the count and the read state are both already in the accessible name
             above, so neither reaches a screen-reader user through a colour or a position. */
          <span
            class={[
              "kui-bell__badge",
              { "kui-bell__badge--read": alerts() && props.unread !== true },
            ]}
            aria-hidden="true"
          >
            {text()}
          </span>
        )}
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
      {/* A `div`, not a `header`. A `<header>` here is exposed as a `banner` landmark, and a page
          with the top bar's banner plus this one has two — which axe reports as
          `landmark-no-duplicate-banner`, and which leaves a screen-reader user with two
          indistinguishable "banner" entries. There is exactly one banner in this product and it is
          the top bar. */}
      <div class="kui-notices__head">
        <h2 class="kui-notices__title">Notifications</h2>
        <Show when={anyUnread() && props.onMarkAllRead !== undefined}>
          <button type="button" class="kui-notices__mark kui-focusable" onClick={() => props.onMarkAllRead?.()}>
            Mark all read
          </button>
        </Show>
      </div>

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

      {/* No retry beside it, deliberately: nothing here failed, so there is nothing to try again.
          A refresh control under this sentence would suggest the deployment might be one press
          away from having an alerts service. */}
      <Show when={props.feed.kind === "not_configured" ? props.feed : undefined}>
        {(absent) => (
          <div class="kui-notices__state">
            <p>{absent().reason}</p>
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
      <IconTile icon={glyphOf(notice())} tone={TONE[notice().severity]} />
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
