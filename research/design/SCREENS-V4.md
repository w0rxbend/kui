# The v4 screens, as read from the screenshots

**What this is.** A reading of twenty-three screenshots captured 2026-09-06, held in a `screens/`
directory that is **not part of this repository** (see *Where the images are* below, and
[ADR-057](../../docs/adr/ADR-057-design-captures-stay-outside-the-repository.md)). They are a later
render of the same design system
`REFERENCE.md` and `SCREENS.md` describe, and they cover more of the product than either: the
dashboard now has four tabbed bodies, the topics list has a statistics region and bulk selection,
the message browser has a filter grammar, and Kafka Connect and ksqlDB are drawn as working
screens rather than as orphan components.

**Supersession.** For the screens listed below, **this document wins over `SCREENS.md`**. Where
it is silent, `SCREENS.md` still owns the answer.

| Screen | Owner now | Note |
| --- | --- | --- |
| Frame (rail, drawer, top band) | **this document** §2 | geometry unchanged; the drawer *head* and the *foot* both changed |
| Dashboard | **this document** §4.1–§4.4 | it is now four tab bodies, not one page |
| Brokers | **this document** §4.5 | four stat tiles, expandable cards |
| Topics list | **this document** §4.6–§4.8 | statistics region, chip bar, table/cards, bulk bar |
| Topic object (overview, messages, consumers) | **this document** §4.9–§4.11 | |
| Consumer groups | **this document** §4.12 | first picture of this screen since `SPEC.md` |
| Schema Registry | **this document** §4.13 | master–detail, not two pages |
| Kafka Connect | **this document** §4.14 | |
| ksqlDB | **this document** §4.15 | |
| Overlays: notifications, appearance, toasts, create-topic | **this document** §4.16–§4.19 | |
| Light theme | **this document** §5 | four light captures, not one |
| Voice | `SCREENS.md` §5 + **this document** §6 | rules unchanged, catalogue extended |
| Anything not listed | `SCREENS.md` | including `EnvTile` letter collision, `NavGroup` collapse, `ConfigChip` |

`SCREENS.md` is **not deleted** in this wave. It still holds the only reading of several
components, and its §6 open findings are still open unless §7 below closes one by name.

**Where the images are, and they are here.** Twenty-three PNGs named by capture time, in a
`screens/` directory at the root of the repository — **tracked**, so that `ls screens | wc -l` and
`git ls-files screens | wc -l` agree at twenty-three on any clone.
[ADR-058](../../docs/adr/ADR-058-design-captures-are-tracked.md) is the decision and names what it
costs: six megabytes per design revision, in history, forever. It supersedes
[ADR-057](../../docs/adr/ADR-057-design-captures-stay-outside-the-repository.md), which decided the
other way hours earlier and whose own Context section makes the case this one acted on — the
captures are the denominator of definition-of-done item 1, and an exit criterion whose subject
exists on one machine cannot be re-run by anyone who clones.

This document remains the specification of record. The captures are evidence for it, not a
replacement: nothing should read a pixel where the reading states a measurement.

This document said *"`screens/`, committed"* until 2026-09-12, and `:3` said the captures were
*"held in `screens/` in this repository"*. Both were false and both were contradicted by
`SCREENS.md:17` in this directory. **What follows from the correction, and it is not small.**
Every measurement below is re-runnable only by somebody holding the images, exactly as
`SCREENS.md`'s are; the file names are quoted throughout and indexed in §1 so that a reader who
has them can re-take any figure, and a
reader who does not can still read every derived number, because each one is written out beside the
coordinates it came from rather than left as "see the capture".

**Method.** Unchanged from `SCREENS.md`. Colours are sampled with ImageMagick, never named by eye;
distances come from scanning one row or one column for the transition between two fills. Both are
quoted with coordinates.

```bash
cd screens
magick screenshot_2026-09-06_10-27-52.png -format "%[hex:p{40,155}]" info:      # one point
magick screenshot_2026-09-06_10-27-52.png -crop 700x1+0+1100 +repage -depth 8 txt: | \
  tail -n +2 | sed 's/^\([0-9]*\),\([0-9]*\):.*\(#[0-9A-F]\{6\}\).*/\1 \3/' | \
  awk '{if($2!=last){print $1" "$2; last=$2}}'                                 # one row
```

**Capture scale.** All twenty-three are 1825×2686 device pixels, and the capture is **not** at 1:1.
Scanning row 1100 of `M01` gives the rail ending at x=80 and the drawer ending at x=390, against
the shipped tokens `--kui-rail-width: 48px` and `--kui-drawer-width: 182px`. 80 ÷ 48 = 1.667 and
310 ÷ 186 = 1.667, so the capture is **5/3 device pixels per CSS pixel**. Every distance below is
quoted in device pixels with the CSS value in parentheses; divide by 1.667 to check one yourself.
The 1999×1082 captures behind `SCREENS.md` were at 1:1, so a distance here is *not* directly
comparable with a distance there without dividing.

---

## 0. The colour finding, and the one thing that is new

`SCREENS.md` §0 found that the screenshots were a render of the tokens this repository already
ships. **That finding survives, with exactly one exception**, and the exception was predicted in
the token file's own comment.

Twenty-eight samples taken from the parts of the design that did not exist when `SCREENS.md` was
written came back equal to declared tokens:

| Sampled at | Hex | Token (dark unless noted) |
| --- | --- | --- |
| `M01` (400,1200) drawer ground | `#15181C` | `--kui-color-surface-raised` |
| `M01` (30,1200) rail column ground | `#0E1013` | `--kui-color-surface` — the rail still has no fill |
| `M01` (900,1200) card fill | `#1B1F25` | `--kui-color-surface-elevated` |
| `M01` (37,155) selected env tile | `#0B57D0` | `--kui-color-primary-container` |
| `M01` (37,150) env tile glyph | `#D3E3FD` | `--kui-color-primary-container-contrast` |
| `M01` (37,125) env tile focus ring | `#A8C7FA` | `--kui-color-primary` |
| `M01` (2100,47) search pill | `#242930` | `--kui-color-surface-hover` |
| `M01` (1949,165) active dashboard tab | `#3A4657` | `--kui-color-selected` |
| `M01` (1968,165) active tab ink | `#DCE5F5` | `--kui-color-selected-contrast` |
| `M01` (2470,165) Create-topic button | `#0B57D0` | `--kui-color-primary-container` |
| `M01` (500,700) throughput produce bar | `#A8C7FA` | `--kui-color-series-1` → `primary` |
| `M01` (1694,1504) storage legend swatch 2 | `#7FD8C7` | `--kui-color-series-2` → `accent` |
| `M01` (1808,1504) storage legend swatch 3 | `#FFD180` | `--kui-color-series-4` → `warning` |
| `M01` (700,265) brokers-online blocks | `#8FD36A` | `--kui-color-success` |
| `M01` (640,372) under-replicated strip, bad block | `#FFD180` | `--kui-color-warning` |
| `M01` (1080,372) controller-uptime ring | `#8FD36A` | `--kui-color-success` |
| `M01` (2200,1355) gauge sub-tile ground | `#242930` | `--kui-color-surface-hover` |
| `M01` (2130..,1371) gauge arc, good | `#8FD36A` | `--kui-color-success` |
| `M01` (2130..,1371) gauge arc, warning | `#FFD180` | `--kui-color-warning` |
| `M01` (2130..,1371) gauge track | `#2E343C` | `--kui-color-surface-overlay` |
| `M01` (470,1333) alert dot, warning | `#FFD180` | `--kui-color-warning` |
| `M01` (470,1400) alert dot, danger | `#FFB4AB` | `--kui-color-danger` |
| `M01` (470,1533) alert dot, success | `#8FD36A` | `--kui-color-success` |
| `M01` (470,1600) alert dot, info | `#A8C7FA` | `--kui-color-primary` |
| `M06` (2130,200) notifications panel | `#2E343C` | `--kui-color-surface-overlay` |
| `M06` (2162,200) notice severity tile, warning | `#4A3200` | `--kui-color-warning-container` |
| `M15` (665,557) `CAPTURED` badge | `#1E4416` | `--kui-color-success-container` |
| `M15` (655,1090) `DECLINED` badge | `#5C1B16` | `--kui-color-danger-container` |
| `M19` (530,330) healthy task bar | `#8FD36A` | `--kui-color-success` |
| `M19` (1650,330) failed task bar | `#FFB4AB` | `--kui-color-danger` |
| `M04` (899,545) storage segment 2, **light** | `#00695C` | `--kui-color-accent` (light) |
| `M04` (1027,545) storage segment 3, **light** | `#7A4F00` | `--kui-color-warning` (light) |
| `M04` (1103,545) stacked-bar remainder, **light** | `#D6DEE9` | `--kui-color-surface-overlay` (light) |

### 0.1 The one new colour: a sixth chart series

The stacked bar on **Storage by broker** has four segments plus a remainder, and the fourth ink is
not in the token file:

```
M01 y=1504 legend swatches:  #A8C7FA   #7FD8C7   #FFD180   #CFBCFF
M04 y=545  broker-1 bar:     #0B57D0   #00695C   #7A4F00   #6750A4   (light)
```

`#6750A4` / `#CFBCFF` is a violet pair with no counterpart in
`frontend/packages/kernel/styles/10-tokens.css`. This is not a surprise: that file's own comment at
line 169 says *"A sixth series is coming"*. The design has now supplied it.

**Therefore: add `--kui-color-series-6` in both themes**, seeded `#6750a4` light and `#cfbcff` dark,
and add it to the series list beside the existing five. It must be a *real* token and not an alias
of an existing one, because the storage card draws four series at once and would otherwise repeat
an ink inside one bar. It is the only colour this document adds.

### 0.2 Two decorative palettes that are deliberately not tokens

Two places in the design use per-entity hues that are not semantic and cannot be derived:

* **Topic-prefix glyphs in the drawer tree** (`M01` x=155): `#A8C7FA`, `#52867E`, `#FFD180`,
  `#C1B0EE`, `#F9ADC4`, `#695050` for `orders.*`, `analytics.*`, `inventory.*`, `payments.*`,
  `users.*`, `notifications.*`. A cart for `orders.*` is a guess about what a name means.
* **Client-id monogram tiles on Top producers** (`M01` x=1035): `#6797E5`, `#B2F5E8`, `#C59E57`,
  `#D4C6EF`, `#9E5774`.

Neither may be tokenised, because neither carries meaning: a token says *what a colour is for*, and
"the fourth topic prefix" is not a purpose. The monograms should be a deterministic hash of the
client id into a fixed decorative ramp declared beside the component; the prefix glyphs should
either be dropped or come from configuration. Both are recorded as open findings (§7.2, §7.3).

### 0.3 The appearance swatches confirm two documented departures

The Appearance popover (`M22`) draws four accent swatches. Scanning row 1490 gives `#0B57D0`,
`#00857A`, `#3E7A22`, `#8A5A00` — the *dark* `--kui-color-primary-container` of each accent seed.
Two of them differ from the repository:

| Accent | Mockup | Repository | Why |
| --- | --- | --- | --- |
| blue | `#0B57D0` | `#0b57d0` | equal |
| teal | `#00857A` | `#00756b` | `10-tokens.css:431` "design-adjusted; design #00857a" |
| green | `#3E7A22` | `#3d7822` | `10-tokens.css:439` "design-adjusted; design #3e7a22" |
| amber | `#8A5A00` | `#8a5a00` | equal |

The repository is right and the mockup is the raw design. **The swatch row must render the token,
not the hex above** — otherwise the swatch will not match the accent the click produces, and the
two adjustments exist because the raw values failed contrast. Recorded so nobody "fixes" it.

---

## 1. Index

Screens are referenced as `M01`…`M23`, in capture order.

| # | File (in the uncommitted `screens/` directory) | Screen | Theme |
| --- | --- | --- | --- |
| M01 | `screenshot_2026-09-06_10-27-52.png` | Dashboard · Overview tab | dark |
| M02 | `…10-28-12.png` | Dashboard · Overview tab | light |
| M03 | `…10-28-16.png` | Dashboard · Traffic tab | light |
| M04 | `…10-28-19.png` | Dashboard · Storage tab | light |
| M05 | `…10-28-22.png` | Dashboard · Alerts tab | light |
| M06 | `…10-28-33.png` | Dashboard · Alerts tab, notifications panel open | dark |
| M07 | `…10-28-38.png` | Brokers (`prod-kyiv-01`), broker-1 expanded | dark |
| M08 | `…10-28-41.png` | Dashboard · Alerts, "Switched to staging-eu-01" toast | dark |
| M09 | `…10-28-43.png` | Brokers (`staging-eu-01`), broker-1 expanded | dark |
| M10 | `…10-28-46.png` | Topic `orders.payments.v2` · Overview | dark |
| M11 | `…10-28-49.png` | Topics list · Table, statistics on, filtered `orders.` | dark |
| M12 | `…10-28-52.png` | Topics list · Cards, same filter | dark |
| M13 | `…10-29-03.png` | Topics list · Cards, two selected, bulk bar | dark |
| M14 | `…10-29-09.png` | Topic `orders.shipped` · Overview, "2 topics deleted" toast | dark |
| M15 | `…10-29-15.png` | Topic `orders.shipped` · Messages | dark |
| M16 | `…10-29-21.png` | Topic `orders.shipped` · Consumers, **no rows** | dark |
| M17 | `…10-29-35.png` | Consumer groups | dark |
| M18 | `…10-29-37.png` | Schema Registry, `orders.payments.v2-value` v3 | dark |
| M19 | `…10-29-40.png` | Kafka Connect | dark |
| M20 | `…10-29-43.png` | ksqlDB | dark |
| M21 | `…10-29-48.png` | ksqlDB, notifications panel open | dark |
| M22 | `…10-29-52.png` | ksqlDB, Appearance popover | dark |
| M23 | `…10-29-57.png` | Dashboard · Alerts, Create-topic dialog | dark |

**Four** captures — `M05`, `M09`, `M19` and `M20` — carry a screenshot-tool toast in the top-right
corner that is **not part of the design**. Ignore anything above y≈90 on the right of those four.
(This sentence said *three* and then listed four, and the sentence after it said *those four*.)

---

## 2. The frame, re-measured

The frame is the same frame `SCREENS.md` §1 describes, and the geometry is unchanged. Row 1100 of
`M01`:

```
x=0    #0E1013  page ground (the rail floats on it)
x=80   #15181C  drawer                    ->  rail  = 80 dev (48 CSS)
x=390  #0E1013  page ground               ->  drawer = 310 dev (186 CSS)
x=425  #1B1F25  first card                ->  gutter =  35 dev ( 21 CSS)
x=2629 #0E1013  page ground, to the edge  ->  right  =  57 dev ( 34 CSS, gutter + scrollbar)
```

`--kui-rail-width`, `--kui-drawer-width`, `--kui-page-gutter` and `--kui-topbar-height` are all
correct as shipped. **No token geometry changes in this document.**

Two things inside the frame did change, and both are structural.

### 2.1 The drawer head is the cluster, not the product

`SCREENS.md` §2 left `BrandBlock` at the head. In every one of the twenty-three captures the head
is a **cluster block**: a status dot, the cluster name at 15px/600, a caption, and a `+` at the
right edge. The product wordmark is gone from the drawer entirely; the share glyph at the top of
the rail is the only place the product marks itself.

```
M01  ● prod-kyiv-01          +      dot #8FD36A (success)
     healthy · v3.7.0 · 3 brokers

M09  ● staging-eu-01         +      dot amber
     1 URP · v3.7.0 · 3 brokers
```

The caption is a three-part interpunct list and **the first token is variable in kind**: the health
word when the cluster is clean, a defect count with an abbreviation when it is not. That is the
whole reason the caption exists — a green dot and the word "healthy" say the same thing twice,
whereas "1 URP" says something the dot cannot.

The `+` is the only route to cluster registration anywhere in twenty-three screens.

### 2.2 The drawer body is a tree, and every row carries a figure

```
∨ CLUSTER                    4
  ▣ Dashboard                          (selected, filled)
  ▤ Brokers                  3/3       green
  ◈ Topics                 128  ∨      expanded
      ★ orders.payments.v2             mono, favourite
      ★ analytics.clickstr…            mono, truncated
      🛒 orders.*              3
      📈 analytics.*           3
      🗄 inventory.*           2
      💳 payments.*            2
      👤 users.*               2
      ✉ notifications.*       3
      🛡 fraud.*               2
      🔒 internal              4       muted
  ▦ Consumers      1 rebalancing       amber
∨ ECOSYSTEM                  3
  {} Schema Registry           6
  ⇅ Kafka Connect      1 failed        red
  ▶ ksqlDB           4 objects
```

Four facts here that the drawer could not express when this document was written. **Three of the
four ship now** — the second heading, the count beside each heading, and the per-row figure whose
tone follows its meaning — and the fourth is half shipped, deliberately; item 4 says which half and
why. The list is kept in its original shape rather than pruned, because what each of these cost is
the useful part of the record:

1. **Two groups, and the second is `ECOSYSTEM`.** Every registered feature declares
   `group: "Cluster"` today, so the drawer draws one heading.
2. **Group headings carry a count** — of *menu entries*, not of Kafka objects. `CLUSTER 4` beside
   `Topics 128` is a genuine misreading risk and the two must not be styled alike; the group count
   is `--kui-color-text-subtle`, the row badge is not.
3. **Every row carries a trailing figure**, and its tone follows meaning rather than magnitude:
   `3/3` green, `128` neutral, `1 rebalancing` amber, `1 failed` red.
4. **Topics nests, and the favourites above the groups are deliberately not built.** The nesting
   ships: prefix groups with counts, then a padlocked `internal`, all in the mono family on a
   vertical guide rule — folded in `frontend/packages/shell/src/nav/topicTree.ts` and drawn by
   `NavItem`. The two starred rows above them do not, and they were **removed rather than
   deferred**. The fold built them from a `favourites` list that nothing in this product records,
   so its only callers were that module's own test, a fixture and a story, and the branch had
   survived two waves as an orphan.

   What bringing it back costs is stated in that module's header, and the fold is the cheap half:
   `favourites: readonly string[]`, filtered against the names so a deleted topic does not become
   a row leading to a 404, emitted before the groups and *also* counted inside them. The expensive
   half is the thing that records the star — a control on a screen, and somewhere for the star to
   live. Until that exists, drawing the rows would mean drawing a branch that is empty on every
   cluster in every deployment. Written down here because for two waves this decision existed only
   in a source comment, which is where a design document's readers do not look.

**`ECOSYSTEM`'s third row, and the figure it does not carry.** The ksqlDB row ships with the
eleventh service: it is a route and a nav destination at `/clusters/<id>/ksql`, registered by
`@kui/feature-ksql` with `group: "Ecosystem"`, and it is the third row under that heading beside
Schema Registry and Kafka Connect — the drawing above, finally, as drawn. Two things about it are
decisions rather than omissions. **It is not a dashboard tab**, for the reason Alerts and Connect
are not: a tab lives in the shell's `overview/`, and a feature package reaching into the shell's
dashboard inverts the dependency the whole feature split exists to keep. And **it carries no figure
yet**, where the capture draws `4 objects`. Nothing in this product counts ksqlDB streams and tables
for the drawer: the count in `NavCounts` is the cluster store's, the cluster store reads the cluster
service, and a row that printed `0 objects` over a ksqlDB server holding four would be the exact
misreading `countBadge` refuses — an absent count is *not known*, which is no badge, and it is never
a zero. When something counts them the row gets a neutral total and this paragraph loses its second
half; until then the row is a link with a label, which is true.

### 2.3 The drawer foot is the storage meter

Unchanged from `SCREENS.md` §2.7 and drawn identically in all twenty-three:
`STORAGE 67%`, a segmented meter with one amber segment, `842 GB of 1.25 TB · broker-3 hot`.

---

## 3. New and changed components

Each entry gives **shows / varies / states / absent**, as `SCREENS.md` §4 does. The two rules of
`SCREENS.md` §4.0 — a dash means "no value", and colour is never the only signal — apply
throughout without restatement.

### 3.1 `DashboardTabs`

**Shows.** Four glyph+word segments on a `--kui-color-surface-elevated` track in the page header,
right-aligned, immediately left of the primary action: Overview · Traffic · Storage · Alerts. The
active segment is a raised chip in `--kui-color-selected` with `--kui-color-selected-contrast` ink
(`M01` x=1949..2084, y=165).

**Varies.** Which segment is active; the address (`M03` is Traffic, `M04` Storage, `M05` Alerts).

**States.** Selecting a tab changes three things and only three: the voice line, the card set, and
the address. The title, the eight stat cards, and the drawer selection are identical in `M01`,
`M03`, `M04` and `M05` — the drawer's selected row stays **Dashboard** on every tab, so a tab is
not a navigation destination.

**Absent.** A tab whose data is not collected. `SCREENS.md`'s rule about a `RangeSelector` whose
every setting produces the same nothing applies whole: do not draw four tabs of the same sentence.

### 3.2 `StatCard`, with a visual slot

**Shows.** Eight cards in a 6+2 grid (`M01` y=222..500). Each is: a rounded icon tile, a large
figure with a small unit beside it, the label *beneath* the figure, and a micro-visual on the
right. Five different micro-visuals appear:

| Card | Figure | Micro-visual | Sampled |
| --- | --- | --- | --- |
| Brokers online | `3 /3` | three solid blocks | `#8FD36A` |
| Topics | `128 total` | rising sparkline | `#D3E3FD` stroke, no axis |
| Partitions in sync | `99.1 %` | flat thick line | ditto |
| Produce rate | `86.4 MB/s` | jagged sparkline | ditto |
| Consume rate | `71.2 MB/s` | jagged sparkline | ditto |
| Consumer lag | `4,212 msgs` | three-quarter ring, amber | `#FFD180` |
| Under-replicated | `12 partitions` | eight-block strip, one amber | `#8FD36A`/`#FFD180` |
| Controller uptime | `99.98 %` | full ring, green | `#8FD36A` |

**Varies.** Icon tile tone; the presence of the visual at all.

**States.** A pending figure keeps the card and shows a skeleton. An unknown figure is an em dash.
A figure nothing collects is the `NotMeasured` sentence and **not** an em dash — those are
different claims and this screen makes both.

**Absent.** The visual is optional. A card with no series has no sparkline; it does not draw a
flat line at zero, which would assert a measured zero.

### 3.3 `Sparkline` (new kernel component)

**Shows.** An axis-less, legend-less trend mark ~24px tall inside a stat card, drawn in one
neutral ink (`#D3E3FD` dark) rather than a series colour, because it is not one series among
several — it is the same figure the card already prints.

**States.** A `null` bucket breaks the line, as `LineChart` already does. One point draws a dot.

**Absent.** Empty draws nothing at all. It is `aria-hidden`; the card's figure carries the meaning,
so there is no hidden data table.

### 3.4 `RingGauge` (new kernel component)

**Shows.** A single scalar as a ring with the percentage in the centre and an uppercase caption
beneath (`M01` **Request handlers**: 71% NETWORK IDLE, 64% IO IDLE, 38% PURGATORY, each on its own
`--kui-color-surface-hover` sub-tile). Also the two stat-card rings.

**Varies.** The threshold *direction*. 64% idle is green and 38% purgatory is amber in the same
card, so the component needs an explicit `goodDirection` — `Donut`'s `warnBelow`/`criticalBelow`
bakes in "higher is better" and is wrong here.

**States.** Track `--kui-color-surface-overlay`, arc `--kui-color-success` or
`--kui-color-warning`.

**Absent.** An unmeasured gauge draws the plain track and an em dash, never a full ring. This is
the same rule `Donut` already states for an all-zero segment set.

### 3.5 `Histogram` (new kernel component)

**Shows.** Twelve buckets, 256 B → 64 KB+, five axis labels on every third boundary, and **three
different inks in one series**: pale grey for ordinary buckets, solid `--kui-color-accent` for the
modal bucket, `--kui-color-warning` for the three oversize buckets, drawn as short stubs (`M03`,
`M04` **Message size distribution**). Three readout chips beneath: `p50 · 1.1 KB` success tone,
`p99 · 18 KB` neutral, `max · 0.9 MB` warning.

**Why it is not `BarChart`.** Colour in that family is a property of a *series*
(`Series.tone` in `charts/plot.ts`), and every bar of a series is painted alike. A histogram tones
*bars*. `BarChart`'s own comment already disclaims being one.

**Varies.** Bucket boundaries, which come from the server — the axis must not invent them.

**Absent.** A zero-count bucket draws no bar. An oversize threshold with no served value draws no
amber at all rather than guessing 16 KB.

### 3.6 `StackedBar` (new kernel component)

**Shows.** Proportional segments summing to the used total, over a remainder track
(`--kui-color-surface-overlay`), one row per broker (`M01`, `M04` **Storage by broker**). Legend
below, shared across the rows: `analytics.*` `inventory.*` `orders.*` `other`.

**Why it is not `SegmentBar`.** `SegmentBar`'s own comment says it is "neither a progress bar …
nor a stacked bar": its segments are equal by design, one per thing. This one is sized by share.

**Varies.** Segment count and keys; the fourth ink needs `--kui-color-series-6` (§0.1).

**States.** The used figure beside each row is inked by threshold — `347 GB` is amber at 83% while
`254 GB` and `241 GB` are not.

**Absent.** A broker whose directories report no capacity has no denominator: draw the neutral
track and no fill, the rule `diskPercentOf` already applies.

### 3.7 `BulkActionBar` (new kernel component)

**Shows.** A dark pill floating bottom-centre (`M13`): `2 selected │ ⚙ Edit config │ 🗑 Purge │
🗑 Delete │ ×`. The selected cards carry a `--kui-color-primary` ring and a filled checkbox at
their top-right.

**Varies.** The count; which actions the principal may perform.

**States.** Absent at zero selection. Selection survives the Table↔Cards switch — `M13`'s two
ticks are on cards, and the same set must survive `M11`'s table.

**Absent.** An action the principal may not perform is disabled with a stated reason, not hidden —
a bar whose buttons change position between users is worse than a disabled button.

### 3.8 `AlertsFeed`

**Shows.** A card headed by a bell, a danger `2 open` pill in `headerEnd`, and five rows (`M01`,
`M05`, `M06`): severity dot · bold title · indented detail line · right-aligned relative age.
Detail lines name the entity: `partition reassignment · 12 members`,
`connection refused es-01:9200`, `threshold 80% · analytics.clickstream`,
`inventory.stock.events p7 back in sync`, `orders.payments.v2-value · BACKWARD`.

**Varies.** Width. It is one third of the row on the Overview tab and full width on the Alerts tab
(`M05`), with the age moved to the far right edge — same rows, two layouts.

**States.** Four severities, sampled to `primary` / `success` / `warning` / `danger` (§0). The pill
counts *open* items, which is fewer than the rows: two of the five are resolved.

**Absent.** No feed at all is the not-configured sentence, not an empty card.

**As built: two components over one store, and the pill is not a fold over the rows.** The two
layouts are two components in two packages, because the shell may not statically import a feature
package — `frontend/scripts/bundle-shape.mjs` fails the build on it — so the dashboard's third-width
card is the shell's (`packages/shell/src/overview/AlertsCard.tsx`, beside `Storage by broker` in row
4 of §4.1) and the full-width layout is `@kui/feature-alerts`'. Neither holds a feed: both read the
kernel's `Alerts` store, which the frame constructs once, and the bell in the top band and the
drawer's Alerts badge read the same accessor. That is what makes one number in three places unable
to disagree, and it is the reason the design's fourth reader — the tab — was not built: **Alerts is
a route** at `/clusters/<id>/alerts`, not a dashboard tab, so §4.4's strip position is a row in the
drawer's `CLUSTER` group instead. A tab would have put the screen in the shell's `overview/` and
inverted the dependency the feature split exists to keep.

The pill is the **server's own `openCount`** and never a count of the rows on screen. The feed is
paged and the pill is not, so folding the page would draw a smaller number wearing the same badge —
which is exactly what §3.8's own note about "fewer than the rows" would be read as licence to do.
And it has a third state the capture cannot show: `openCount: 0` beside an absent `evaluatedAt` is a
cluster the service's rules have **never run over**, so the card draws no pill at all and says so in
words. A green `None open` there would be the most reassuring thing this screen can say and it would
be about nothing at all.

### 3.9 `NotificationPanel`, corrected

**Shows.** Anchored under the bell, `--kui-color-surface-overlay`, heading `Notifications` with
`Mark all read` at the right, four items (`M06`, `M21`). Each: a rounded severity tile with a
**category** glyph, a bold title, a two-line advisory body, and a relative age.

**The correction.** Two of the four items are the same severity (warning) and carry **different
glyphs** — a rebalance arrow and a disk. The shipped component picks the glyph from the severity,
so it cannot draw this. Severity chooses the *tone*; category chooses the *glyph*; they are two
fields, not one.

**States.** The bell carries a bare unread dot with no count (`M01` top right) — the shipped
component draws a numeric badge capped at `9+`. Settle this deliberately; the dot is what is drawn
in all twenty-three.

### 3.10 `AppearancePopover`

**Shows.** (`M22`) A scrim-less elevated card anchored above the rail's foot sliders glyph, opening
up and to the right over the drawer. Title `Appearance`; then `ACCENT` and a row of four colour
swatches, blue selected with a white check and a ring; then `THEME` — a two-segment
Dark|Light control; then `DENSITY` — Comfortable|Compact. No OK, no Cancel.

**Varies.** Nothing but the three selections.

**States.** Dismissed by outside click or Escape. The rail glyph takes **no** active backing while
it is open, which contradicts the bell's behaviour — recorded as §7.5.

**Absent.** There is no "Auto" theme segment. The shipped preference has three values
(auto/light/dark) and this control has two; §7.4.

### 3.11 `Toast`, in anger

**Shows.** Two of them, both bottom-centre pills:

* `M08` — blue circular check, "Switched to staging-eu-01", after an environment click. It is the
  only place the newly selected environment is named in full; the rail tile only shows `S`.
* `M14` — a **red** pill with a trash glyph, "2 topics deleted (in spirit)", carried across a
  navigation from the topics list to a topic page.

**States.** The red one is the finding: `notify` forces `durationMs: null` for tone `danger`, so a
red toast never auto-dismisses — and this one has clearly dismissed by `M15`. A *successful*
destructive action needs a tone that is red and transient; §7.6.

### 3.12 `MessageFilterBar`

**Shows.** Three rows above the record list (`M15`):

```
row 1  ◈ Seek [Latest ⌄]  ▤ Partition [all 6 ⌄]  # Offset [from] → [to]
       🕐 Time [5m|15m|1h|24h]        …    [{} JSON|▤ Table] [⏸ LIVE] [⤓]
row 2  ⚿ Key [contains] [ord_…]   {} Value [contains…]   ⚑ Status [any ⌄]
       ƒx ƒ smart [ value.amount > 1000 && value.currency == 'UAH' ]
row 3  PRESETS  [⃠ Declined only] [$ Big tickets] [↺ Refunds] [⃠ Non-UAH]      7 of 7 messages
```

**Varies.** The whole grammar. Five of these controls have no server parameter behind them today:
the time window, the offset *end* bound, the key/value match modes, the status facet, and the
preset set.

**States.** `LIVE` is a filled success pill and coexists with a 15m window — a tail whose window is
"the last fifteen minutes" is a start-at-timestamp forward read that then follows, not an invalid
request.

**Absent.** A preset set that is not configured draws no `PRESETS` row rather than an empty one.

### 3.13 `RecordRow`, with a payload badge

**Shows.** `# 18,442,901 · p3 · CAPTURED` then the key on the next line and the JSON preview at the
right (`M15`). The badge is read out of the payload's own `status` field, in four tones sampled to
`success-container` / `primary-container` / `warning-container` / `danger-container`.

**This contradicts a decision already taken.** `SCREENS.md` §3.5 says of this badge: **"Do not
build it"**, because there is no generic Kafka record property it corresponds to, and records it as
open finding 6 so nobody silently re-adds it. The design has re-asserted it, twice as loudly:
there is now a `Status` dropdown *and* a badge. §7.1 states the terms on which it could be built.

### 3.14 `ConnectorCard` and `TaskBar`, as drawn

**Shows.** (`M19`) Four cards in a row. Each: a rounded icon tile, the connector name, a
`source · Debezium Postgres` sub-line, a state pill top-right (`RUNNING` success, `FAILED` danger,
`PAUSED` neutral), then a `TaskBar` of one segment per task, then a caption
`3/3 tasks · 1,204 msg/s · orders.*`, then two buttons: `Pause`/`Resume` and `Restart`.

**States.** The failed card shows `1/2 tasks · 0 msg/s · audit.log` and **no reason at all**. An
operator learns that something broke and must leave the product to find out what; §7.7.

**Absent.** The paused card's task bar is a single dim segment (`#2A3038`, between `surface-hover`
and `surface-overlay` — a translucent overlay rather than a token) and reads `0/1 tasks · 0 msg/s`.
A literal `0 msg/s` on a paused connector is a *measured* zero; an unmeasured one must stay a dash.

### 3.15 `SchemaWorkspace`

**Shows.** (`M18`) One page, two panes. Left: a subject list card, each row a coloured format
badge (`AVRO` blue, `JSON` teal, `PROTO` amber), the subject name, and a caption
`3 versions · BACKWARD`; the selected row is filled `--kui-color-selected`. Right: the subject name
with its format badge, version chips `v1 v2 v3` right-aligned, a four-segment compatibility
control `BACKWARD|FORWARD|FULL|NONE`, three fact tiles (`SCHEMA ID 1042`, `REGISTERED Aug 12, 2026`,
`FIELDS 6`), then the definition in a mono block.

**Varies.** Selecting a left row swaps the right pane and keeps the list on screen.

**Two things this asks for that the product refuses.** The compatibility control has four segments
and the registry has seven levels; the shipped `Select` is correct and this control is lossy —
the kernel's own `SegmentedControl` doc says five is where segments stop working. And `REGISTERED`
has no source: the Confluent-compatible API returns no registration timestamp. §7.8.

### 3.16 `KsqlWorkspace`, as drawn

**Shows.** (`M20`) Two panes. Left: `STREAMS & TABLES` and four monospace uppercase names, each
with a stream or table glyph — the glyph is the only thing that says which. Right: a resizable SQL
editor, then a footer strip with `auto.offset.reset = earliest` as a *caption* on the left and
`Clear` / `▶ Run query` on the right.

**Absent.** The result region. Every ksqlDB capture is of an unrun query; `SCREENS.md` open
finding 4 stands unresolved and is restated as §7.9.

---

## 4. Screens

### 4.1 Dashboard · Overview (`M01`, `M02`)

Address, from the drawer selection and the tab strip: `/clusters/{id}/dashboard/overview`.

Header: title `Cluster overview`; voice `All brokers vibing. Zero offline partitions. You may sip
your coffee.`; the four-tab strip; primary `+ Create topic`.

Then, top to bottom:

1. **Eight stat cards** (§3.2), 6 across then 2.
2. **Throughput** (2/3 width) — 24 paired produce/consume bars, legend chips, a `24h | 7d | 30d`
   selector with 24h active, axis `00:00 · 06:00 · 12:00 · 18:00 · now`.
   **Broker health** (1/3) — three rows `broker-N.kyiv`, `id N · 512 leaders`, a disk progress bar
   and a percentage; caption `Controller: broker 1. It won the election fair and square.`;
   `View all` in `headerEnd`.
3. **Partition health** donut `99.1% IN SYNC`, legend `In sync 1,522 / Under-replicated 12 /
   Offline 2` · **Top consumer lag** magnitude list · **Latency · p99** two-series line with the
   current value in each legend chip and ticks `-60 min / -30 min / now`.
4. **Alerts & events** · **Top producers · client.id** · **Storage by broker** · **Request
   handlers**.

Eleven of the fourteen figures on this page have no source in the product today.

### 4.2 Dashboard · Traffic (`M03`)

Voice: `Throughput, latency and who is producing all of it.`

Same title, same eight stat cards, same rows 2 and 3. Row 4 changes: **Top producers** (wider),
**Message size distribution**, **Request handlers**. `Alerts & events` and `Storage by broker` are
gone.

Note the composition rule this proves: **the tab selects the last row only**, and the stat cards
and the first two card rows are tab-invariant. Rows 2 and 3 being identical across all four tabs is
what makes the strip cheap.

### 4.3 Dashboard · Storage (`M04`)

Voice: `Disk, retention and the topics eating your budget.`

Stat cards, then **exactly two cards** — `Storage by broker` and `Message size distribution` — and
then empty page ground. The page is short and is not padded to fill the viewport. Rows 2 and 3 are
absent here, which contradicts §4.2's rule: **Storage replaces the whole body, Traffic replaces
only the last row.** Recorded as §7.10; the honest reading is that the tab owns the body below the
stat cards and Traffic simply happens to repeat rows 2 and 3.

### 4.4 Dashboard · Alerts (`M05`, `M06`, `M08`, `M23`)

Voice: `Two open alerts. One is the usual suspect.` — derived from the open count.

Stat cards, then row 3 (`Partition health`, `Top consumer lag`, `Latency · p99`), then a
full-width `Alerts & events`. No `Throughput`, no `Broker health`.

### 4.5 Brokers (`M07`, `M09`)

Voice: `3 brokers, 1 controller, 0 drama. Click a broker to see its configuration.`

Four `StatTile`s: `ACTIVE CONTROLLER broker 1` · `TOTAL LEADERS 1,536` ·
`DISK USED 842 / 1,250 GB` · `NETWORK 212 MB/s`.

Then one expandable card per broker: icon tile, `broker-1.kyiv`, `10.0.1.11:9092`, then columns
`LEADERS 512`, `REPLICAS 1536`, `RACK eu-central-1a`, then `disk` + bar + `61%`, then a chevron.
Expanded (`M07` broker-1) adds a tag row — `active controller` (filled primary), `v3.7.0 · KRaft`,
`uptime 41d` — and a `CONFIGURATION` block of eight config chips.

The `NETWORK` tile and `uptime` have no source anywhere in the product; `LEADERS` is `None` by
construction in the cluster service.

### 4.6 Topics list · Table (`M11`)

Voice: `3 of 128 topics match · 1,536 partitions · 2 of them are drama queens` — the first number
tracks the filter, the rest do not.

Header actions: outlined `⤓ Export`, filled `+ Create topic`.

Control row: `[▤ Table | ▦ Cards]` · `Filter ⌄` · `Sort · topic ⌄` · a separate ascending/descending
button · `Show statistics` **switch, on** · then, far right, the name search holding `orders.`

Statistics region (governed by the switch): four `StatTile`s —
`TOTAL TOPICS 128` chip `10 created this month`; `TOTAL PARTITIONS 1,536` chip `12 avg per topic`;
`TOTAL STORAGE 842 GB` chip `↗ 3.2% this week`; `AVG REPLICATION 2.9` chip `3 topics at RF 2` —
then three cards: `Cleanup policy` donut (`128 TOPICS`, Delete 96 / Compact 32), `Largest topics`,
`Highest throughput`.

Then a four-chip bar `✓ All | 🔒 Internal | ⚠ Out of sync | ⇄ Compacted`, single-select.

Then the table: a leading checkbox column, `TOPIC ↑`, `PARTITIONS`, `OUT OF SYNC`, `RF`, `SIZE`,
`MSG/S`, `CLEANUP` (a dark tag). Then `Showing 1–3 of 3`, a page-size control `8 | 16 | 32`, and
first/prev/1/next/last plus `Go to [#] Go`.

The statistics are cluster-wide and unaffected by the `orders.` filter — `TOTAL TOPICS` still reads
128 while the table shows 3, and `Largest topics` lists `analytics.clickstream` and `audit.log`,
which do not match. That is the load-bearing fact of this screen.

### 4.7 Topics list · Cards (`M12`)

Identical page above the chip bar. Below it, three cards: icon tile + name + checkbox top-right;
a row of three grey tags `12 partitions`, `RF 3`, `delete`; a footer line with size left and rate
right; a thin magnitude bar under it. No health pill and no out-of-sync figure appear on a card.

### 4.8 Topics list · bulk selection (`M13`)

Two cards ticked, each with a `--kui-color-primary` ring; the bulk bar at the foot (§3.7).

### 4.9 Topic · Overview (`M10`, `M14`)

Content breadcrumb `Topics › orders.payments.v2`; H1 with a green `in sync` pill; actions
`➤ Produce message` (secondary) and `🗑 Purge` (outlined danger); tab strip
`Overview | Messages | Consumers | Settings` with an underline on the active one.

Body: four `StatTile`s — `PARTITIONS 12` caption `RF 3 · min.isr 2`; `SIZE ON DISK 48.2 GB` caption
`retention 7 days`; `PRODUCE RATE 1,204 /s` caption `avg message 1.1 KB`; `CONSUMER GROUPS 2`
caption `all replicas in sync` — then a `Partitions` card holding the full table:
`ID · LEADER · REPLICAS · IN SYNC · START OFFSET · END OFFSET · SIZE`.

The Overview tab renders nothing at all in the shipped product.

### 4.10 Topic · Messages (`M15`)

The filter bar (§3.12) and seven record rows (§3.13). `7 of 7 messages` sits at the right of the
presets row.

### 4.11 Topic · Consumers (`M16`)

Columns `GROUP ID · STATE · MEMBERS · COORDINATOR · LAG`, and **no rows and no empty state**. The
capture is of a topic with no consumers, and the design draws a bare header. That is a defect in
the design, not a specification: an empty table needs an `EmptyState`. Build the `EmptyState`.

`COORDINATOR` is a column no earlier document mentions.

### 4.12 Consumer groups (`M17`)

Voice: `14 groups. One is rebalancing again. We don't judge.` — 14, while six rows are drawn.
The count is the cluster total, and the page draws no pagination control.

Columns `GROUP ID · STATE · MEMBERS · TOPICS · COORDINATOR · LAG`. State is a pill:
`Stable` success, `Rebalancing` warning, `Empty` neutral. Coordinator is `broker-1:9092` —
a host and a port, not a broker id. Lag is inked amber when it is the largest.

### 4.13 Schema Registry (`M18`)

Voice: `6 subjects. Backward compatible, unlike your last migration.`
Action: `+ Register schema`. Layout §3.15.

### 4.14 Kafka Connect (`M19`)

Voice: `4 connectors · 1 failed and sulking`. Action: `🚀 Deploy connector`. Cards §3.14.
The `rocket` glyph does not exist in the icon registry.

### 4.15 ksqlDB (`M20`, `M21`, `M22`)

Voice: `SQL on streams. Press Run and pretend it's a database.` Layout §3.16.
The drawer's ksqlDB row is selected; the address in the drawer is cluster-scoped even though the
mockup's own breadcrumb reads only `ksqlDB`.

### 4.16 Notifications panel (`M06`, `M21`)

§3.9. Note it is drawn open over two different screens, so it belongs to the frame, not to a page.

### 4.17 Appearance popover (`M22`)

§3.10.

### 4.18 Toasts (`M08`, `M14`)

§3.11.

### 4.19 Create topic (`M23`)

A modal over a scrimmed page: icon tile, title `Create topic`, sub-line
`Name it well. Renaming is not a thing.`; `TOPIC NAME` (mono field, `orders.shipped.v1`);
`PARTITIONS 12` and `REPLICATION FACTOR 3` side by side; `CLEANUP POLICY` as a two-segment
`delete|compact` control; then `Cancel` and `+ Create`.

It is opened from the **dashboard's** header button, which is wired to nothing today.

---

## 5. Light theme

Four light captures (`M02`–`M05`) against nineteen dark ones. They confirm `SCREENS.md` §4 without
amendment: **same layout, light tokens substituted, nothing else moves.** Row 1100 of `M02` gives
the identical transitions to `M01` at the identical x positions.

Two things to re-note because they are easy to get wrong and this set proves them again:

1. **The chart series move with the theme.** The throughput bars are `#A8C7FA`/`#7FD8C7` dark and
   `#0B57D0`/`#00695C` light, because `--kui-color-series-*` alias the accent and status ink.
   `--kui-color-series-6` must do the same: `#6750a4` light, `#cfbcff` dark.
2. **Cards gain a border in light.** `M02` shows card edges that `M01` does not.

One thing this set adds: **the theme control's glyph names the theme you would switch *to***. `M01`
(dark) shows a sun; `M02`–`M05` (light) show a moon. The shipped `TopBar` maps
`{auto: theme-auto, light: sun, dark: moon}` — the current theme — which is the opposite pairing,
and its comment argues for it because "auto" has no opposite. §7.4.

---

## 6. Voice

New lines, in the same register, all obeying `SCREENS.md` §5:

> "All brokers vibing. Zero offline partitions. You may sip your coffee."
> "Throughput, latency and who is producing all of it."
> "Disk, retention and the topics eating your budget."
> "Two open alerts. One is the usual suspect."
> "3 of 128 topics match · 1,536 partitions · 2 of them are drama queens"
> "14 groups. One is rebalancing again. We don't judge."
> "Name it well. Renaming is not a thing."
> "2 topics deleted (in spirit)"
> "Controller: broker 1. It won the election fair and square."

Two amendments to `SCREENS.md` §5's rules, both forced by lines above:

1. **A voice line may be per-tab.** The dashboard has four. The rule that a voice line belongs to a
   section and never to an object survives — a tab is a section.
2. **"2 topics deleted (in spirit)" breaks the rule that the joke is never the information.**
   Delete the parenthesis and the sentence still states the count, so it passes on the letter; but
   a destructive confirmation that hedges about whether it happened is the one place the register
   should be flat. Recommend `2 topics deleted`.

---

## 7. Open findings

Numbered so they can be cited. `SCREENS.md` §6 findings 1, 2 and 3 remain open and are not
restated. Finding 5 (`Request handlers` was an empty card) is **closed**: `M01` and `M03` draw its
content — three ring gauges — so it can now be built.

1. **The payload status badge and the `Status` facet are re-asserted after being ruled out.**
   `SCREENS.md` §3.5 says "Do not build it" and records it as its own finding 6. The design now
   draws both a badge and a dropdown. The badge cannot be built as drawn — `status` is not a Kafka
   record property. It could be built as a **configured projection**: a JSON path per topic or per
   cluster plus a tone map, served alongside the filter presets, with a row that is correct (no
   empty column) for every deployment that configures none. Decide before either half is built,
   and write the decision into this section rather than into a commit message.

2. **Topic-prefix glyphs are not derivable.** §0.2. Either drop them and use one neutral prefix
   glyph, or make the mapping configuration. Do not infer a cart from the word "orders".

3. **Monogram colours need a stated rule.** §0.2. A deterministic hash of the client id into a
   fixed decorative ramp is the only version that is stable across reloads and across replicas.

4. **The theme control has two problems at once.** The glyph names the target theme (§5) where the
   shipped component names the current one, and the Appearance popover offers two theme segments
   where the preference has three values. Settle both together: if "auto" survives, the popover
   needs a third segment; if the glyph names the target, "auto" needs a coherent glyph. Whichever
   wins, the accessible name must keep naming the mode in words.

5. **The rail's active-backing rule is inconsistent.** The bell takes a filled backing while its
   panel is open (`M06`, `M21`); the sliders glyph does not while the Appearance popover is open
   (`M22`). One of the two is wrong.

6. **A successful destructive action wants a red transient toast**, which the toast contract
   forbids: `danger` never auto-dismisses. Either add a fifth tone, or accept that "2 topics
   deleted" is `success` tone and red is wrong.

7. **A failed connector shows no reason.** `M19`'s failed card carries a state and a task count and
   nothing else, while the notification for the same event carries
   `Task 0: connection refused to es-01:9200`. Either the card links to a connector detail page
   that does not exist in any capture, or it carries the first line of the trace.

8. **Two things on the schema pane cannot be built as drawn.** The four-segment compatibility
   control cannot express three of the registry's seven levels, and the shipped seven-option
   `Select` is the better control — this is a place to follow the code. `REGISTERED Aug 12, 2026`
   has no source in the Confluent-compatible API; either it is served only where the registry
   exposes it (Apicurio's native metadata does) and is absent elsewhere, or the tile is dropped.

9. **The ksqlDB result region is still never shown** (`SCREENS.md` finding 4, unresolved).
   Columns, streaming rows, the row cap and the error rendering are all unspecified while `Run` is
   drawn as an enabled control.

10. **The four dashboard tabs disagree about what a tab replaces.** Traffic keeps rows 2 and 3 and
    swaps row 4; Storage replaces everything below the stat cards. Read it as "the tab owns the
    body below the stat cards" and treat Traffic's rows 2 and 3 as that tab's own content.

11. **The topic Consumers tab has no empty state** (`M16`). Build one; do not ship a bare header.

12. **`14 groups` with six rows and no pagination.** The consumer-groups page states a cluster
    total and draws a page, with no control to reach the rest. A page-size control or an explicit
    "showing 6 of 14" is needed; the shipped list already throws the server's total away.

---

## 8. How to check this document

Every colour:

```bash
cd screens
magick screenshot_2026-09-06_10-27-52.png -format "%[hex:p{40,155}]" info:
```

Every distance, as a scan of one row or column for the transition between fills:

```bash
# row: <img> <y> <x-start> <width>;  col: <img> <x> <y-start> <height>
magick "$img" -crop "${w}x1+${x}+${y}" +repage -depth 8 txt: | tail -n +2 |
  sed 's/^\([0-9]*\),\([0-9]*\):.*\(#[0-9A-F]\{6\}\).*/\1 \2 \3/' |
  awk '{if($3!=last){print $1" "$3; last=$3}}'
```

Two claims are worth re-checking before anything else is trusted:

* **§0.1, that `#6750A4`/`#CFBCFF` is genuinely absent from the tokens.**
  `grep -i '6750a4\|cfbcff' frontend/packages/kernel/styles/10-tokens.css` must return nothing.
* **The capture scale of 5/3.** If it is wrong, every CSS figure in §2 is wrong with it, and the
  check is one line: the rail must measure 80 device pixels and the shipped token must say 48px.
