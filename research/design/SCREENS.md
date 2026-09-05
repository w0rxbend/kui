# The v3 screens, as read from the screenshots

**What this is.** A reading of seventeen screenshots of the Kafka UI design, captured 2026-09-05,
covering the screens the earlier reading could not cover because no picture of them existed.

**How it relates to the two documents beside it.**

| Document | Source | Covers |
| --- | --- | --- |
| `REFERENCE.md` | the artboard `Kafka UI v2.dc.html` | the token values |
| `.agent/design/SPEC.md` (local, not committed) | five screenshots, 2026-09-03 | dashboard, topic messages, consumer groups |
| **this document** | seventeen screenshots, 2026-09-05 | the frame, and every screen the other two left as "implied but not shown" |

Where this document and `SPEC.md` disagree, **this document wins**: it reads later screenshots of
the same design, and the disagreements are all places where the design moved (§1).

**Where the images are.** `.agent/design/screens/`, outside the repository by way of
`.git/info/exclude`, for the same reason the artboard is: design source does not enter this
repository, but a reading of it must be reproducible by someone holding the source. The file names
are quoted throughout so a reader with the images can re-run every measurement below.

**Method.** Unchanged from `SPEC.md`. Colours are sampled with ImageMagick, never named by eye.
Distances come from scanning one row or one column of pixels for the transition between two fills.
Both are quoted with their coordinates:

```bash
cd .agent/design/screens
magick 13-topics-list.png -format "%[hex:p{300,144}]" info:      # one point
magick 13-topics-list.png -crop 261x1+0+700 +repage txt: | ...   # one row, for transitions
```

All seventeen images are 1999×1082, the same size as the five that came before, so every
measurement here is directly comparable with every measurement in `SPEC.md`.

---

## 0. The finding, restated and re-confirmed

`SPEC.md` §0 found that the screenshots were a render of the tokens this repository already
ships, not a new palette to import. **That finding survives the new screens intact.** Twenty
further samples, taken from the parts of the design that did not exist when it was written — the
environment rail, the notifications panel, the filter chips, the connector cards, the schema
pane — came back equal to tokens already declared in
`frontend/packages/kernel/styles/10-tokens.css`:

| Sampled at | Hex | Existing token |
| --- | --- | --- |
| `13` (140,700) drawer ground | `#15181C` | `--kui-color-surface-raised` |
| `13` (22,90) selected environment tile | `#0B57D0` | `--kui-color-primary-container` |
| `13` (22,130) unselected environment tile | `#242930` | `--kui-color-surface-hover` |
| `13` (140,150) selected navigation item | `#3A4657` | `--kui-color-selected` |
| `13` (70,90) dashboard icon tile | `#0B57D0` | `--kui-color-primary-container` |
| `13` (70,118) brokers icon tile | `#1E4416` | `--kui-color-success-container` |
| `13` (1580,26) search field | `#242930` | `--kui-color-surface-hover` |
| `13` (300,171) statistic card | `#1B1F25` | `--kui-color-surface-elevated` |
| `13` (273,559) active filter chip | `#3C4859` | `--kui-color-selected` |
| `13` (400,559) inactive filter chip | `#0F1114` | `--kui-color-surface` (chip is unfilled) |
| `13` (690,144) "Show statistics" switch, on | `#A8C7FA` | `--kui-color-primary` |
| `13` (140,1044) storage meter | `#8FD36A` | `--kui-color-success` |
| `14` (1700,251) broker disk bar | `#8FD36A` | `--kui-color-success` |
| `14` (300,343) configuration chip | `#2C3239` | `--kui-color-surface-overlay` |
| `16` (1900,203) LIVE pill | `#1B4014` | `--kui-color-success-container` |
| `19` (600,160) selected subject row | `#3A4657` | `--kui-color-selected` |
| `19` (600,207) subject row | `#1B1F25` | `--kui-color-surface-elevated` |
| `20` (300,192) healthy task bar | `#8FD36A` | `--kui-color-success` |
| `20` (1200,192) failed task bar | `#FFB4AB` | `--kui-color-danger` |
| `22` (1800,200) notifications panel | `#2E343C` | `--kui-color-surface-overlay` |

**So: this document adds no colour.** Everything below is composition and geometry. If you find
yourself reaching for a hex literal while building one of these screens, you have misread this
document; the token is already there.

---

## 1. The frame changed

This is the one place where the new screenshots contradict `SPEC.md`, and it is the change with
the widest blast radius, so it comes first.

### 1.1 What moved

`SPEC.md` §4.1 describes an `AppFrame` of a 196px drawer and a 58px top bar. Scanning row 700 of
the old dashboard and the new topics list, at the same image size, gives:

```
01-dashboard.png  y=700:  x=0    #15181C  (drawer)
                          x=196  #0E1013  (page ground)
                          x=217  #1B1F25  (first card)

13-topics-list.png y=700: x=0    #0E1013  (page ground — the rail sits on it)
                          x=48   #15181C  (drawer)
                          x=230  #0E1013  (page ground)
                          x=250  #1B1F25  (first card)
```

Three changes, and each of them is deliberate rather than incidental:

1. **A 48px environment rail appeared to the left of the drawer**, and it is drawn on the page
   ground rather than on its own fill. It is not a second drawer; it is a column of tiles floating
   on the page, which is why it has no edge.
2. **The drawer narrowed from 196px to 182px.** The rail took 48px and the drawer gave back 14px
   of it, so the left chrome grew by 34px in total, not 48px.
3. **The top bar has no fill.** Scanning column 1000 from y=0 to y=120 on `13` returns `#0E1013`
   the whole way: there is no bar. What was a 58px bar with a fill is now a 58px *band* of page
   ground with three floating objects in it — the breadcrumb, the search pill (`#242930`,
   beginning at x=1566), and the control cluster on the right.

The content column consequently starts at x=250 and ends at x=1977, giving gutters of 20px on the
left and 22px on the right of a 1999px window. Read that as **a 20px gutter**; the 2px on the
right is the scrollbar gutter, and appears only on a scrolling page.

### 1.2 The tokens this implies

`--kui-drawer-width` moves from 196px to 182px, `--kui-page-gutter` from 24px to 20px, and two
names are added:

```css
--kui-rail-width: 48px;        /* the environment rail */
--kui-rail-tile-size: 36px;    /* the environment tile inside it */
```

`--kui-topbar-height` stays at 58px. The band is still 58px tall; it just no longer paints.

**These are the only four token changes in this document,** and three of the four are geometry.
No colour moves.

---

## 2. New components

Each entry follows `SPEC.md` §4's shape: **shows**, **varies**, **states**, **absent/failed**. The
two rules of `SPEC.md` §4.0 — a dash means "no value", and colour is never the only signal — apply
to every component here without being restated.

### 2.1 `EnvRail`

**Shows.** A 48px column on the page ground, running the full height of the window. At its top, a
share/topology glyph, the product mark, at 24px. Beneath it the caption **ENVS**, centred, at 9px
in `--kui-color-text-subtle` — cropping `13` at `48x24+0+52` and scaling it 4× shows the word
plainly. Then one `EnvTile` per configured environment. At the foot, separated by flexible space, a
stack of destination glyphs and finally the account avatar.

The caption is absent from the earlier chrome (`06`–`10`) and present in the later (`11`–`22`);
the later set is the target, so it is drawn.

**Varies.** The number of environments; which one is selected; which destination glyph is active.

**States.** Nothing selected yet (start-up): no tile is filled. One selected. Hover on a tile
raises a tooltip naming the environment in full (`07-cluster-tooltip.png` shows this for
`staging-eu-01`, drawn as a dark plate to the tile's right, vertically centred on it).

**Absent/failed.** If the cluster list has not arrived, the rail draws the product mark and the
account avatar and nothing between them. It never collapses to zero width: the frame's geometry
does not depend on how many clusters exist.

### 2.2 `EnvTile`

**Shows.** A 36×36 tile, radius `--kui-radius-md`, carrying the environment's initial letter in
uppercase. A health dot overlaps its bottom-right corner, ringed in the page ground so it reads as
a separate object — the same construction as `BrandBlock`'s dot in `SPEC.md` §4.2.

**Varies.** The letter; the health; selected or not.

**States.** Selected: filled `--kui-color-primary-container`, letter in
`--kui-color-primary-container-contrast`. Unselected: filled `--kui-color-surface-hover`, letter in
`--kui-color-text-muted`. Sampled at `13` (22,90) and `13` (22,130) respectively. The vertical
pitch between tiles is 38px (tiles at y≈72, 110, 148), i.e. a 36px tile with a 2px gap.

**Absent/failed.** The health dot takes `--kui-color-text-subtle` when the environment's health is
not yet known, which is distinct from every health colour and so cannot be mistaken for one.

**A warning.** The letter is not an identifier. Two environments beginning with the same letter
produce two identical tiles, and the design has no answer for that — see §6, open finding 1. Until
it does, the tooltip and the drawer head carry the name, and the tile is never the only place an
environment is named.

### 2.3 `RailIconButton`

**Shows.** A 24px glyph on the page ground, no tile, no fill. Vertical pitch 37px (glyphs at
y≈221, 258, 295 in `13`).

**Varies.** The glyph and its destination. The set in the screenshots is: notifications, ksqlDB,
security, and — pinned to the very foot, above the avatar — settings.

**States.** Default `--kui-color-text-muted`; hover `--kui-color-text`; active (its destination is
the current page) `--kui-color-primary` with the glyph filled rather than outlined. `22`
(1800,10..44) shows the notifications button in its active state carrying a
`--kui-color-surface-hover` backing while its panel is open.

**Absent/failed.** A destination whose capability is absent is not drawn at all. A rail is a
shortcut bar; a dead shortcut is worse than a missing one.

### 2.4 `NavGroup`, revised

`SPEC.md` §4.3 stands, with two additions visible in `13` and `22`:

1. **The group heading carries a count on its right.** `CLUSTER 4`, `ECOSYSTEM 3` — the number of
   destinations in the group, at 11px in `--kui-color-text-subtle`. It is not a count of anything
   in Kafka, and must not be confused with the per-item counts.
2. **The heading is a disclosure.** A chevron precedes it and the group collapses. Both groups are
   open in every screenshot; the collapsed rendering is not shown and is specified in §6, open
   finding 2.

Row pitch inside a group is **30px**, measured from the icon tiles in column 70 of `13`
(Dashboard's tile spans y=80..101, Brokers' y=110..131). The icon tile is 22×22, not the 24×24 of
`SPEC.md` §4.4 — the drawer's tiles shrank with the drawer.

### 2.5 `NavItem` trailing slot, revised

The trailing slot now carries three distinct things, and they are three different components
sharing a position, not one component with three colours:

| In the screenshots | What it is | Rendering |
| --- | --- | --- |
| `3/3`, `128`, `6`, `4 objects` | a quantity | `--kui-color-text-subtle`, 11px |
| `1 rebalancing`, `1 failed` | a condition needing attention | `--kui-color-warning` / `--kui-color-danger`, 11px, and the word is always present |
| a chevron | the item has children | `--kui-color-text-muted` |

An item may carry a chevron *and* a quantity — `Topics 128 ⌄` in `13` — in that order.

### 2.6 `TopicTree`

**Shows.** When `Topics` is expanded, a list of entries indented under it: first the favourited
topics by exact name, each with a star glyph; then prefix groups (`orders.*`, `analytics.*`,
`inventory.*`, …) each with a trailing count of the topics beneath it and a glyph chosen for the
prefix; last, `internal` with a padlock and its own count.

**Varies.** The favourites, the prefixes, the counts.

**States.** An entry is selectable and takes the same selected fill as any nav item. A long name
truncates with an ellipsis at the drawer's inner width (`analytics.clickstr…` in `13`); the full
name is in the title attribute.

**Absent/failed.** No favourites: the favourites section is simply absent, no placeholder. No
topics at all: the tree does not render and `Topics` loses its chevron. Topics not yet loaded:
`Topics` keeps its chevron and expanding it shows three skeleton rows, never an empty box.

**A caution.** The prefix grouping is a *presentation* of the topic list and nothing more. It must
be computed in the browser from the names it already has, and must never become a server concept
or a filter the user cannot escape: a topic that matches no prefix still has to be reachable.

### 2.7 `StorageMeter` (drawer foot)

Replaces `ClusterStatusCard` (`SPEC.md` §4.5) in the drawer's foot.

**Shows.** A card, `--kui-color-surface-elevated`, spanning the drawer's inner width. A disk glyph
and the word **STORAGE** at 11px/600 uppercase on the left; the percentage on the right in
`--kui-color-text-strong`. Below them a segmented meter 8px tall (`13` column 140 y=1041..1048),
one segment per broker, and beneath that a caption: `842 GB of 1.25 TB · broker-3 hot`.

**Varies.** The segments, the percentage, the caption.

**States.** Each segment takes the threshold colour of *its own* broker, so a cluster with one hot
broker shows one amber segment among green ones rather than an averaged colour that hides it. The
caption names the hottest broker.

**Absent/failed.** Storage unknown: the meter is a single `--kui-color-surface-overlay` track, the
percentage is `—`, and the caption says the figure is not available. It must not draw an empty
track that reads as 0%, which is the defect `--kui-color-surface-overlay` as a track colour exists
to prevent.

### 2.8 `Breadcrumb`, in the top bar

`SPEC.md` §4.14 places a breadcrumb in the content column. In the new screens it moved into the
top-bar band, at its left edge, and it always begins with the cluster:
`prod-kyiv-01 › Topics › analytics.clickstream`.

The object pages keep a *second* breadcrumb in the content column above the title
(`Topics › analytics.clickstream` in `15`). The two are not redundant: the top-bar one says where
you are in the installation, the content one says where you are in the section and is the one that
scrolls away. Build them as one component in two placements, not two components.

### 2.9 `NotificationBell` and `NotificationPanel`

**Bell.** A bell glyph in the top bar's control cluster. When unread notifications exist it carries
a filled dot at its top right in `--kui-color-danger`; when the count is small the dot carries the
count. Sampled from `22`, the button takes a `--kui-color-surface-hover` backing while its panel is
open.

**Panel.** Measured from `22`: x=1668..1946, y=49..313 — **278px wide, 264px tall**, filled
`--kui-color-surface-overlay`, radius `--kui-radius-lg`, `--kui-shadow-md`, right-aligned under the
bell with an 8px offset below the top-bar band.

**Shows.** A header row: **Notifications** on the left, **Mark all read** on the right as a text
button in `--kui-color-primary`. Then a list of `NotificationItem`.

**States.** Empty: the panel still opens, and says that there is nothing — an operator who clicks a
bell and gets a panel that does not appear cannot tell "no news" from "broken". Overflowing: the
list scrolls inside the panel's fixed height and gains a footer linking to the full list.

**Absent/failed.** If notifications could not be fetched, the panel opens and says so, with a
retry. It never shows a stale list without saying it is stale.

### 2.10 `NotificationItem`

**Shows.** A 22px icon tile on the left, filled with the container colour of the item's severity
(`--kui-color-warning-container` sampled at `22` (1700,200)); a title at 12px/600 in
`--kui-color-text`; a body at 11px in `--kui-color-text-muted`, wrapping to at most three lines; a
relative age at 11px in `--kui-color-text-subtle`, top-right.

**Varies.** Severity — informational, warning, danger, success — which selects both the container
colour and the glyph.

**States.** Unread items are drawn as above. Read items drop to `--kui-opacity-stale`. Hovering
gives the row `--kui-color-state-layer`.

**Absent/failed.** The body may be absent; the title never is. An item with no body draws its title
vertically centred against the tile rather than leaving a gap where the body would be.

### 2.11 `StatTile` — the second statistic-card form

The new screens use **two** statistic cards, and the difference is not styling. It is which of the
two things the eye should land on first.

| | `SPEC.md` §4.6 `StatCard` | `StatTile` (new) |
| --- | --- | --- |
| Order | figure, then label | label, then figure |
| Where | the dashboard, `06` | section landing pages: `13` topics, `14` brokers, `15` topic overview |
| Reads as | "3 of 3 brokers are online" | "of the things called TOTAL PARTITIONS, there are 1,536" |
| Carries | an icon tile and a status pill | an optional trailing chip below the figure |

**Shows.** A card, `--kui-color-surface-elevated`, radius `--kui-radius-lg`. An icon tile and an
11px/600 uppercase label on the first line; the figure at `--kui-font-size-3xl` in
`--kui-color-text-strong` with its unit at body size beside it; optionally a chip below, carrying a
qualifier (`10 created this month`, `12 avg per topic`, `↗ 3.2% this week`, `3 topics at RF 2`).

**Varies.** Label, figure, unit, the presence and tone of the chip.

**States.** The chip takes `--kui-color-primary-container` for neutral facts, `--kui-color-success`
ink for improvement, `--kui-color-warning` ink for a fact that wants attention. It is *not* a
status pill and must not be shaped like one.

**Absent/failed.** Figure unknown: the skeleton of §4.0, at the figure's size. Not measured for
this cluster: the word from `SPEC.md` §7.1's not-measured rendering, in
`--kui-color-text-subtle` — never a zero. This distinction is the one that commit
`17782e2` was written to enforce and it must not be lost again.

### 2.12 `ViewToggle`

**Shows.** A segmented control of two items, **Table** and **Cards**, each with a glyph, in a
`--kui-radius-pill` track. The active segment carries a fill and `--kui-color-text-strong` ink; the
inactive one is unfilled with `--kui-color-text-muted` ink.

**Varies.** Nothing but which is active.

**States.** As above. Keyboard: it is a radio group, arrow keys move between the two.

**Absent/failed.** Not applicable — but the *choice must persist* per user, not per visit. An
operator who prefers cards and gets a table on every navigation will conclude the control does not
work.

### 2.13 `Switch`

**Shows.** A pill track with a travelling knob, and a label to its left (`Show statistics`).

**States.** On: track `--kui-color-primary`, knob `--kui-color-primary-contrast`, knob at the right.
Off: track `--kui-color-surface-overlay`, knob `--kui-color-text-muted`, knob at the left. Sampled
on at `13` (690,144).

This is the repository's first switch. Everything toggling until now has been a checkbox or a
segmented control. The rule for which to use: a **switch** takes effect immediately and describes a
state of the view; a **checkbox** contributes to something the user will submit.

### 2.14 `FilterChipBar`

**Shows.** A row of chips beneath a section's controls: `All`, `Internal`, `Out of sync`,
`Compacted` in `13`. Each chip is a glyph plus a word, `--kui-radius-pill`.

**States.** Active: filled `--kui-color-selected`, ink `--kui-color-selected-contrast`, and the
glyph becomes a check. Inactive: no fill, 1px `--kui-color-border`, ink `--kui-color-text-muted`.
Sampled at `13` (273,559) active and (400,559) inactive.

**Absent/failed.** A chip whose filter would match nothing is still drawn and still clickable; it
resolves to an empty table that says the filter matched nothing. Hiding it would leave the operator
unable to tell an impossible filter from an unavailable one.

### 2.15 `ConfigChip`

**Shows.** A rounded rectangle, `--kui-color-surface-overlay` (sampled `14` (300,343)), radius
`--kui-radius-sm`, containing a configuration key in `--kui-font-family-mono` at
`--kui-font-size-sm` in `--kui-color-text-muted`, and the value right-aligned in
`--kui-color-text-strong`.

**Varies.** Key and value. The chips wrap into as many rows as needed.

**States.** A value overridden from the cluster default is marked — the design does not show an
override, so the marking is `SPEC.md` §7.1's job and not invented here.

**Absent/failed.** A key with no value shows `—`, never an empty right side, which would read as a
rendering fault.

### 2.16 `BrokerCard`

**Shows.** A card per broker. Collapsed: the broker name and its host:port; three labelled figures
(`LEADERS`, `REPLICAS`, `RACK`); a disk `ProgressBar` with its percentage; a disclosure chevron.
Expanded: additionally a row of tags (`active controller` / `follower`, the version and protocol,
the uptime) and a `CONFIGURATION` block of `ConfigChip`s.

**Varies.** Everything above; whether this broker is the controller.

**States.** Collapsed and expanded, independently per broker — `14` shows all three expanded, `10`
shows one. The `active controller` tag takes `--kui-color-primary-container`; `follower` takes
`--kui-color-surface-overlay`, so the controller is findable at a glance without reading.

**Absent/failed.** A broker that did not answer draws its card with its identity and the reason in
place of its figures, at `--kui-opacity-stale`. It is not omitted: a missing broker card is
indistinguishable from a broker that is not configured, and those are opposite situations.

### 2.17 `SubjectList` and `SchemaViewer` (Schema Registry, `19`)

**`SubjectList`.** A card holding one row per subject. Each row: a `FormatBadge`, the subject name
at 13px/600, and a caption `3 versions · BACKWARD`. The selected row takes `--kui-color-selected`
(sampled `19` (600,160)); the others sit on `--kui-color-surface-elevated`.

**`FormatBadge`.** A 30×30 tile, radius `--kui-radius-sm`, carrying `AVRO`, `JSON` or `PROTO` at
9px/700. The three take three different container colours, and the word is always present, so the
colour is a convenience and never the signal.

**`SchemaViewer`.** The right pane. A header with the subject name and its `FormatBadge`, and a
version selector — `v1 v2 v3` as a segmented control, latest selected. Then a
`CompatibilitySelector` (`BACKWARD FORWARD FULL NONE`, the current one filled), then three
`StatTile`-shaped facts (`SCHEMA ID`, `REGISTERED`, `FIELDS`), then the schema itself in
`--kui-font-family-mono` on `--kui-color-surface-raised`.

**Absent/failed.** No subjects: an `EmptyState` in the left pane and nothing in the right. A subject
whose schema failed to fetch: the header and the facts render from the list data already held, and
the body carries the error with a retry — the right pane never blanks the header it could still
draw.

### 2.18 `ConnectorCard` and `TaskBar` (Kafka Connect, `20`)

**`ConnectorCard`.** A card per connector. A 30px icon tile chosen by connector class; the name at
14px/600; a caption `source · Debezium Postgres`; a `StatusPill` top-right
(`RUNNING` / `FAILED` / `PAUSED`). Below: a `TaskBar`. Below that a caption line
`3/3 tasks · 1,204 msg/s · orders.*`. At the foot, two buttons — `Pause`/`Resume` and `Restart`.

**`TaskBar`.** One segment per task, laid out in a row with equal widths and 4px gaps, each 6px
tall, radius `--kui-radius-xs`. A running task is `--kui-color-success` (sampled `20` (300,192)); a
failed one `--kui-color-danger` (`20` (1200,192)); a paused one
`--kui-color-surface-overlay`. A connector with no tasks draws a single full-width
`--kui-color-surface-overlay` track, which is visibly different from a track of failed tasks.

**Absent/failed.** The two action buttons obey the permission rules — absent the permission they
are disabled with an accessible explanation and issue no request, per the frontend's existing rule.
A connector whose state is unknown draws its pill as unknown rather than guessing `RUNNING`.

### 2.19 `KsqlWorkspace` (`21`, `22`)

**Shows.** Two panes. Left: `STREAMS & TABLES`, a list of objects, each with a glyph that
distinguishes a stream from a table, at 12px/600 in `--kui-font-family-mono`. Right: an editor on
`--kui-color-surface-elevated` holding SQL in `--kui-font-family-mono` with syntax colouring drawn
from the chart series tokens; below it a footer with `auto.offset.reset = earliest` in
`--kui-color-text-subtle` on the left and `Clear` and `Run query` on the right.

**States.** Idle, running (the run button becomes a cancel), and holding a result. The result
region is not in the screenshots and is §6, open finding 4.

**Absent/failed.** ksqlDB not configured: the destination does not appear in the drawer at all,
which is the existing capability rule and needs no new mechanism.

---

## 3. Screens

Compositions only; every component named is either in `SPEC.md` §4 or in §2 above.

### 3.1 Frame, on every screen

```
AppFrame
├─ EnvRail:  product mark · EnvTile ×N · RailIconButton ×N · Avatar
├─ Drawer:   BrandBlock
│            NavGroup(CLUSTER 4: Dashboard, Brokers, Topics[+TopicTree], Consumers)
│            NavGroup(ECOSYSTEM 3: Schema Registry, Kafka Connect, ksqlDB)
│            StorageMeter
├─ TopBar:   Breadcrumb · SearchField · ThemeControl · NotificationBell[+panel] · Button/primary
└─ Content:  the screen
```

### 3.2 Brokers (`14`, and `10` for the single-expanded state)

```
PageHeader(title "Brokers", voice line "3 brokers, 1 controller, 0 drama. Click a broker to
           see its configuration.", no actions)
grid(4):   StatTile ACTIVE CONTROLLER · TOTAL LEADERS · DISK USED · NETWORK
stack:     BrokerCard ×N
```

`10` and `14` differ only in how many cards are expanded, and in which statistic-card form they
use: `10` uses the dashboard's `StatCard` (icon, then figure), `14` uses `StatTile` (label, then
figure). **`14` is the target.** The section landing pages are consistent with each other, and the
dashboard is the one screen that is not, deliberately, because it is read differently.

### 3.3 Topics list (`13`; `11` shows the same page with statistics switched off)

```
PageHeader(title "Topics", voice line "24 of 128 topics match · 1,536 partitions · 2 of them are
           drama queens", actions [Button/secondary "Export", Button/primary "+ Create topic"])
controls:  ViewToggle(Table|Cards) · Filter ⌄ · Sort ⌄ · direction · Switch"Show statistics"
           · SearchField"Filter by name…"
grid(4):   StatTile TOTAL TOPICS · TOTAL PARTITIONS · TOTAL STORAGE · AVG REPLICATION   [statistics]
grid(3):   Card"Cleanup policy"     — Donut + legend                                     [statistics]
           Card"Largest topics"     — MagnitudeBar ×5
           Card"Highest throughput" — MagnitudeBar ×5
FilterChipBar(All | Internal | Out of sync | Compacted)
DataTable(☐ | TOPIC | PARTITIONS | OUT OF SYNC | RF | SIZE | MSG/S | CLEANUP)
Pagination(showing 1-8 of 24 · page size 8|16|32 · first ‹ 1 2 3 › last · go to #)
```

The `Show statistics` switch removes the four `StatTile`s **and** the three analysis cards — `11`
shows the switch off with the three cards still present, which is inconsistent with its own label
and is §6, open finding 3. Build the switch to govern all seven.

**The `Cards` view is not shown in any screenshot.** Its composition is specified here rather than
left to be invented: the same page, the same controls, the same statistics, with the `DataTable`
replaced by a responsive grid of topic cards. Each card carries what the table's columns carry —
the name with its internal padlock or topic glyph, then partitions, out-of-sync, RF, size and
msg/s as labelled figures, and the cleanup policy as a `Tag`. Selection is a checkbox in the
card's top-left, so a selection survives switching views. Sorting and filtering are the view's, not
the table's, and must not reset when the view changes.

### 3.4 Topic overview (`15`; `12` is the same page in the older chrome)

```
Breadcrumb(Topics › analytics.clickstream)          [in the content column]
PageHeader(title "analytics.clickstream", StatusPill "in sync",
           actions [Button/secondary "Produce message", Button/danger "Purge"])
TabStrip(Overview* | Messages | Consumers | Settings)
grid(4):   StatTile PARTITIONS(48, "RF 3 · min.isr 2")
                    SIZE ON DISK(540.3 GB, "retention 7 days")
                    PRODUCE RATE(18,220 /s, "avg message 1.1 KB")
                    CONSUMER GROUPS(1, "all replicas in sync")
Card"Partitions":
  DataTable(ID | LEADER | REPLICAS | IN SYNC | START OFFSET | END OFFSET | SIZE)
```

No voice line: this is an object page, and `SPEC.md` §6 already states the rule.

The partitions table is the one place in the product where a topic can have thousands of rows, so
it virtualizes. It is also the table whose row count was got wrong before (a twelve-partition topic
that drew five rows); the story for it must include a topic with more partitions than fit.

### 3.5 Topic messages (`16`, `17`, `18`)

```
… header and TabStrip as §3.4, with Messages selected …
MessageFilterBar:
  row 1: Seek(Latest ⌄) · Partition(all 48 ⌄) · Offset(from … to) · Time(5m|15m*|1h|24h)
         ┈ right ┈ ViewToggle(JSON|Table) · LIVE · download
  row 2: Key(contains, …) · Value(contains …) · Status(any ⌄) · smart(expression)
  row 3: PRESETS  PresetChip ×4                        ┈ right ┈ "14 of 14 messages"
RecordRow ×N
```

**One thing is deliberately removed from the design.** The screenshots draw a coloured badge at the
right of every record — `CAPTURED`, `AUTHORIZED`, `DECLINED`, `REFUND_REQUESTED`. **Do not build
it.** Those values come from a `status` field in one particular payload schema; there is no generic
Kafka record property they correspond to, and a message browser that promotes one deployment's
field to a first-class column is a browser that shows an empty column, or worse a wrong one, for
every other deployment. The row ends at the relative age and the disclosure chevron.

The same reasoning disposes of the `Status(any ⌄)` control in row 2 as a *fixed* control. What the
design is reaching for there is real and worth having, but it is the general mechanism, not a
special case: the smart-expression field beside it (`value.amount > 1000 && value.currency ==
'UAH'`) already expresses `value.status == 'CAPTURED'`, and the `PRESETS` row already lets a
deployment name and save exactly that. Build the presets and the expression; drop the status
dropdown.

`17` and `16` differ only in how many records the window holds. `18` shows one record expanded —
`OFFSET`, `PARTITION`, `KEY`, `TIMESTAMP` as four labelled facts, then `HEADERS` as `HeaderChip`s,
then `VALUE` as pretty-printed JSON with a `Copy JSON` action — which is `SPEC.md` §4.28 unchanged.

### 3.6 Schema Registry (`19`)

```
PageHeader(title "Schema Registry", voice line "6 subjects. Backward compatible, unlike your last
           migration.", action Button/primary "+ Register schema")
two panes, 1:1.4 —
  SubjectList
  SchemaViewer
```

### 3.7 Kafka Connect (`20`)

```
PageHeader(title "Kafka Connect", voice line "4 connectors · 1 failed and sulking",
           action Button/primary "Deploy connector")
grid(4):  ConnectorCard ×N
```

### 3.8 ksqlDB (`21`, `22`)

```
PageHeader(title "ksqlDB", voice line "SQL on streams. Press Run and pretend it's a database.",
           no actions)
two panes, 1:1.4 —
  Card"STREAMS & TABLES" — object list
  KsqlWorkspace editor + footer
```

---

## 4. Light theme

`08-dashboard-light.png` is the first screenshot of the light theme, and it settles a question that
`SPEC.md` §7.3 had to answer by inference: **the light theme is the same layout with the light
tokens substituted, and nothing else.** The card fills, the chart series, the icon tiles and the
selected navigation item all move to their light values and no geometry changes.

Two details worth recording because they are easy to get wrong:

1. **The chart series colours do not stay put.** The producer bars are pink-red in light and
   blue-teal in dark, because they are `--kui-color-series-*`, which alias the accent and status
   ink, which are theme-dependent by construction. This is correct and must not be "fixed" by
   pinning the series to fixed hexes.
2. **The card border appears.** In light, cards carry `--kui-card-border`; in dark it is
   transparent. The token already exists and already does this, for the reason its comment gives.

---

## 5. Voice

The new screens extend the copy `SPEC.md` §6 catalogued, in the same register:

> "3 brokers, 1 controller, 0 drama. Click a broker to see its configuration."
> "24 of 128 topics match · 1,536 partitions · 2 of them are drama queens"
> "4 connectors · 1 failed and sulking"
> "6 subjects. Backward compatible, unlike your last migration."
> "SQL on streams. Press Run and pretend it's a database."

`SPEC.md` §6.3's rules hold without amendment, and two of them are load-bearing here. **The joke is
never the information**: every line above states its figures plainly first and is funny second, and
the figures survive deleting the joke. **The voice line belongs to a section, never to an object**:
each of these is on a landing page, and no object page has one.

One rule needs stating that did not before, because these lines are the first to risk breaking it:
**the voice never comments on the health of the thing it is describing when that health is bad.**
"1 failed and sulking" is at the edge of acceptable and passes only because it is a count first.
An operator whose cluster is down does not want to be told about it wittily, and the not-healthy
copy of `SPEC.md` §7.1 is written flat for exactly that reason.

---

## 6. Open findings

Numbered so they can be cited. Each is a question the screenshots raise and do not answer.

1. **Two environments beginning with the same letter produce two identical rail tiles.**
   `prod-kyiv-01` and `prod-eu-02` are both `P`. Options: two letters, a per-environment colour, or
   an explicit per-environment glyph in configuration. Until it is decided, the rail is never the
   only place an environment is identified, and the tooltip is not optional.

2. **A collapsed `NavGroup` is not shown.** Both groups are open in all seventeen images. Whether
   the collapsed state hides the items entirely or shows them as a glyph strip is undecided;
   whether the state persists per user is undecided.

3. **`Show statistics` is inconsistent with itself in `11`.** The switch is off, the four
   `StatTile`s are gone, and the three analysis cards are still there. §3.3 resolves this in favour
   of the label — the switch governs all seven — but it is a decision this document made, not one
   the design stated.

4. **The ksqlDB result region is never shown.** Every ksqlDB screenshot is of an unrun query. The
   result table, the streaming-result case, and what a query error looks like are all unspecified.

5. **`Request handlers` is an empty card in every dashboard screenshot** (`06`, `07`, `08`). It has
   a title, an icon and no content, in both themes, which most likely means the design has not
   drawn it yet rather than that it renders empty. Do not build a card whose content is unknown;
   build the dashboard without it and add it when there is something to put in it.

6. **The `Status` dropdown in the message filter bar is dropped** for the reason §3.5 gives. Recorded
   here as a finding rather than a silent omission, because someone comparing the built product to
   the screenshots will notice it is missing and should find the reason rather than re-adding it.

---

## 7. How to check this document

Every colour:

```bash
cd .agent/design/screens
magick 13-topics-list.png -format "%[hex:p{300,144}]" info:
```

Every distance, as a scan of one row or column for the transition between fills — the helper used
throughout was:

```bash
# row: <img> <y> <x-start> <width>;  col: <img> <x> <y-start> <height>
magick "$img" -crop "${w}x1+${x}+${y}" +repage -depth 8 txt: | tail -n +2 |
  sed 's/^\([0-9]*\),\([0-9]*\):.*\(#[0-9A-F]\{6\}\).*/\1 \2 \3/' |
  awk '{if($3!=last){print $1" "$3; last=$3}}'
```

The claim in §0 — that this design introduces no colour — is the one worth re-checking first if
anything here looks wrong, because everything else is built on it.
