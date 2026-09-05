# The style surface

**What this document is.** A complete inventory of everything in the KUI frontend that a *visual*
redesign would touch: every design token and its value, the theming machinery, every CSS file and
the order they are pasted together in, every kernel component and the tokens and class names it
depends on, and every place where a visual value is written literally instead of coming from a
token.

**Why it exists.** The project owner keeps a Claude Design project, "Kafka UI v2", which defines the
intended look. It *has* been read: the reading is recorded in
[`research/design/REFERENCE.md`](../../research/design/REFERENCE.md), which is the only place in this
repository that holds the design's own values. This document deliberately holds none of them, and
neither states nor guesses what the design contains. It describes the surface an import lands *on*,
which is knowable independently of what is landing, and which is what turns the reconciliation into a
small, reviewable diff instead of a rewrite. Reconciling the two is task
[UI-013](../plans/M0/tasks/UI-013.md); until that task runs, every value below is the shipped value
and none of it has been superseded in the product.

> **Reconcile before you trust the second half of this document.** It was written against the
> Scala.js and Laminar frontend, which ADR-048 replaced on 2026-09-05. Two parts have been
> reconciled and are current: the CSS file inventory (§3.1) and the ordering rules (§3.2), because
> the stylesheets themselves were carried across unchanged and are still the shipped ones. Everything
> that links to a `.scala` file — the token accessor, the theme machinery, the component inventory
> and its class-name and token columns, the literal-value audit's line references into Scala — is an
> inventory of code that has been deleted. The *class names and tokens* in those tables are still
> the ones the stylesheets define, which is what makes the tables useful; the *components and files
> they are attributed to* are not. Re-taking that inventory against `frontend/packages/` is
> outstanding work and is not recorded as done anywhere.

**The rule this document encodes.** The design project decides **how KUI looks**. The original plan
and the researched behaviour of the two reference products — Kafbat Kafka UI and Provectus Kafka UI,
analysed in [`research/`](../../research/) — decide **what KUI does**. Where the design implies a
behaviour that contradicts the researched behaviour, the research wins, and the difference is
recorded (there is a place for that in the last section of this file). "Styling" here means colour,
type, spacing, radius, shadow, motion, and the arrangement of pixels. It does not mean which columns
a table has, what a button is allowed to do, or which keys move focus.

**Audience.** Someone about to perform the import, and anyone reviewing that import's diff. Nothing
below assumes you have read the rest of `docs/frontend/`.

**Related reading.** [`tokens.md`](tokens.md) (what each token means and why the set is this size),
[`components.md`](components.md) (each primitive's API and accessibility contract),
[`README.md`](README.md) (the frontend as a whole), [ADR-024](../adr/ADR-024-css-and-design-system.md)
(plain CSS with custom properties; ScalaCSS rejected), [ADR-011](../adr/ADR-011-laminar-waypoint-frontend.md)
(Laminar + Waypoint), [ADR-032](../adr/ADR-032-navigation-state-model.md) (capability-driven
navigation and degraded states), [UI-002](../plans/M0/tasks/UI-002.md) (the token decision),
[UI-013](../plans/M0/tasks/UI-013.md) (the reconciliation task this document prepares for — it owns
the *method*; this document owns the *inventory* that method operates on).

---

## 1. Design tokens

Tokens are CSS custom properties declared in
[`frontend/packages/kernel/styles/10-tokens.css`](../../frontend/packages/kernel/styles/10-tokens.css).
A *custom property* is a variable the browser itself understands: `--kui-color-primary: #4a56d6;`
declares one, and `color: var(--kui-color-primary)` reads it. Because the browser resolves it at
paint time rather than at build time, changing a token re-themes a running page — that is the whole
reason the theme switcher does not need a rebuild.

Every token name is mirrored as a Scala constant in
[`frontend/ui-kernel/src/kui/ui/kernel/theme/Tokens.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/theme/Tokens.scala).
Scala never sees the *values* (Scala.js has no filesystem at run time); it needs the *names* so a
test can read a computed value and so drift between the two files can be caught. `TokensSuite`
asserts the two lists match in both directions.

**Count: 46 tokens.** 13 colour, 9 spacing, 12 type, 4 radius, 2 shadow, 4 stacking, 2 motion.
`TokensSuite` fails the build above 60 — not tidiness, but the measurable symptom of the one rule
UI-002 exists to protect (§1.8).

### 1.1 Provenance markers

Every colour declaration in the stylesheet ends with a trailing comment recording where its value
came from, as do the two light-theme shadow values (§1.6) — 41 marked declarations in all, from 39
colours and 2 shadows. There are exactly two markers today; UI-013 adds a third, `/* design */`, for
a value taken from the import:

- `/* seeded: kafbat */` — the exact value from Kafbat's palette (`frontend/src/theme/theme.ts` in
  the reference checkout, analysed in
  [`research/kafbat/ui-analysis.md`](../../research/kafbat/ui-analysis.md)).
- `/* kui */` — a value this project chose, which in every case here means a Kafbat value darkened
  or lightened until it passed the contrast test in §5.

The marker must survive a value change. It is what lets a later reconciliation tell a deliberate KUI
decision from an inherited default: a `kui` marker on a colour means somebody moved that value *for
a reason*, and replacing it needs that reason re-checked.

### 1.2 Colour (13 tokens)

Light values are declared on `:root`; dark values are declared twice (§2). The "Source" column is the
provenance marker on the light declaration.

| Token | Light | Dark | Source (light / dark) | Semantic meaning |
| --- | --- | --- | --- | --- |
| `--kui-color-surface` | `#f9fafa` | `#171a1c` | kafbat / kafbat | The page itself. Off-white rather than pure white: a full-screen table of pure white is fatiguing, and it lets a raised card read as raised |
| `--kui-color-surface-raised` | `#ffffff` | `#22282a` | kafbat / kafbat | Anything above the page: cards, dialogs, drawers, dropdown menus, sticky headers |
| `--kui-color-border` | `#d5dadd` | `#2f3639` | kafbat / kafbat | A separator carrying no meaning — a rule between table rows, a card edge. Deliberately faint and deliberately **not** contrast-checked, because nothing is lost if a user cannot see it |
| `--kui-color-border-strong` | `#73848c` | `#8f9ca3` | kafbat / kafbat | The outline of an interactive control: input, select, unfilled button. Contrast-checked at 3:1 (WCAG 1.4.11 Non-text Contrast) |
| `--kui-color-text` | `#171a1c` | `#f1f2f3` | kafbat / kafbat | Body text |
| `--kui-color-text-muted` | `#5c6970` | `#abb5ba` | **kui** / kafbat | Secondary text: timestamps, row counts, help text. Kafbat's equivalent grey fails AA against its own background; this is the next step darker, at 5.4:1 |
| `--kui-color-primary` | `#4a56d6` | `#8f9bff` | **kui** / **kui** | Brand: primary buttons, links, the active navigation item. Darkened from Kafbat's blue so white text on it clears AA |
| `--kui-color-primary-contrast` | `#ffffff` | `#0b0d0e` | kafbat / kafbat | Text and icons drawn on top of `primary` |
| `--kui-color-success` | `#177a3c` | `#5cd685` | **kui** / kafbat | In sync, healthy, completed |
| `--kui-color-warning` | `#8a5a00` | `#ffb84d` | **kui** / **kui** | Degraded, lagging, deprecated |
| `--kui-color-danger` | `#cf1717` | `#f5a3a3` | kafbat / kafbat | Unavailable, failed, destructive |
| `--kui-color-info` | `#2c5bb8` | `#90caf9` | **kui** / kafbat | Neutral notices |
| `--kui-color-focus` | `#4a56d6` | `#8f9bff` | **kui** / **kui** | The keyboard focus ring. Same hue as primary today, but its own token on purpose: a redesign may want focus loud while primary goes quiet |

The four status colours are used as *text or an icon on the page background*, never as a large fill
behind body text, which is why each is checked at 4.5:1 against `surface` and not against itself.

### 1.3 Spacing (9 tokens)

A 4-pixel scale. Every margin, padding and gap in the product is one of these nine values.

| Token | Value | | Token | Value |
| --- | --- | --- | --- | --- |
| `--kui-space-0` | `0` | | `--kui-space-5` | `1.5rem` (24px) |
| `--kui-space-1` | `0.25rem` (4px) | | `--kui-space-6` | `2rem` (32px) |
| `--kui-space-2` | `0.5rem` (8px) | | `--kui-space-7` | `3rem` (48px) |
| `--kui-space-3` | `0.75rem` (12px) | | `--kui-space-8` | `4rem` (64px) |
| `--kui-space-4` | `1rem` (16px) | | | |

Source: **kui**. Not seeded from anything; a 4px scale is a convention, not a borrowed value.

### 1.4 Typography (12 tokens)

| Token | Value | Meaning |
| --- | --- | --- |
| `--kui-font-family-sans` | `Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif` | Interface font. See remediation R7 — Inter is named but not shipped |
| `--kui-font-family-mono` | `ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace` | Offsets, partition ids, keys, headers, JSON. Digits must line up |
| `--kui-font-size-xs` | `0.6875rem` (11px) | Table metadata, badges |
| `--kui-font-size-sm` | `0.75rem` (12px) | Dense table cells |
| `--kui-font-size-md` | `0.875rem` (14px) | Body. Kafbat's base size, and right for a dense tool |
| `--kui-font-size-lg` | `1rem` (16px) | Section headings |
| `--kui-font-size-xl` | `1.25rem` (20px) | Page titles |
| `--kui-font-weight-regular` | `400` | |
| `--kui-font-weight-medium` | `500` | |
| `--kui-font-weight-bold` | `600` | |
| `--kui-font-line-height-tight` | `1.25` | Headings, table cells, buttons |
| `--kui-font-line-height-normal` | `1.5` | Prose, help text |

Source: **kui**, with the 14px body size taken from Kafbat.

### 1.5 Radius (4 tokens)

`--kui-radius-sm` `2px` (tags, inline code) · `--kui-radius-md` `4px` (buttons, inputs, cards) ·
`--kui-radius-lg` `8px` (dialogs, drawers, popovers) · `--kui-radius-pill` `9999px` (status dots,
counters). Source: **kui**.

### 1.6 Shadow (2 tokens)

| Token | Light | Dark |
| --- | --- | --- |
| `--kui-shadow-sm` | `0 1px 2px rgba(10, 10, 10, 0.08)` (kafbat) | `0 1px 2px rgba(0, 0, 0, 0.4)` |
| `--kui-shadow-md` | `0 4px 16px rgba(10, 10, 10, 0.16)` (kafbat) | `0 4px 16px rgba(0, 0, 0, 0.55)` |

Two levels only, on purpose: a shadow says "this floats above the page", and a product with five
subtly different shadows says nothing at all. In dark mode depth comes mostly from
`surface-raised` being lighter than `surface`; the shadows are kept but heavier so a dialog still
detaches.

### 1.7 Stacking and motion (6 tokens)

`--kui-z-dropdown: 100` → `--kui-z-drawer: 200` → `--kui-z-dialog: 300` → `--kui-z-toast: 400`.
Every `z-index` KUI writes is meant to be one of these four (two escapees are listed in §6).

`--kui-duration-fast: 100ms`, `--kui-duration-normal: 200ms`. Both neutralised by the reset under
`prefers-reduced-motion` (§4.1).

### 1.8 The rule the set exists to protect

There are **no component-scoped tokens**. A button reads `--kui-color-primary`, not
`--kui-button-primary-hover-background`. This is a direct reaction to what the research found:
Kafbat's theme is a 1 664-line TypeScript object with an entry per component per state, which is
exactly why changing its palette is a multi-day edit. Kouncil has a single SCSS palette and no dark
mode at all.

`TokensSuite` enforces this mechanically: it fails if any colour, spacing, type or radius token name
contains `button`, `input`, `dialog`, `table`, `card`, `tab`, `toast` or `drawer`. Stacking tokens
are the deliberate exception — `--kui-z-dialog` names a *layer*, and a layer has no name other than
what sits on it.

Hover, active and pressed shades are **derived, not declared**: the stylesheet uses CSS
`color-mix()` (e.g. `color-mix(in srgb, var(--kui-color-primary) 88%, black)` for a primary button's
hover). That is why one token can serve every state of a control.

---

## 2. The theming mechanism

Three states, not two: `Auto` (follow the operating system, and change with it), `Light`, `Dark`.
`Auto` is genuinely a third state — a user on `Auto` whose laptop switches to dark at sunset expects
KUI to switch with it, and a user who picked `Light` expects KUI to stay light at midnight.

### 2.1 How three states become two palettes

`10-tokens.css` contains three rule blocks, in this order, and the order is load-bearing:

1. `:root` — the **light** palette, plus every token that never changes with theme (spacing, type,
   radius, stacking, motion). Ends with `color-scheme: light`, which tells the browser to draw its
   *own* widgets — scrollbars, form controls, the default text-selection colour — to match.
2. `@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { … } }` — the dark palette
   for a system that asks for dark. The `:not([data-theme="light"])` guard is what stops it applying
   to a user who explicitly chose light.
3. `:root[data-theme="dark"] { … }` — the dark palette again, for an explicit choice. It is written
   **last** in the file so it wins over a system set to light.

The dark palette is therefore declared twice, and the two copies must stay identical or a user who
picks dark by hand sees a different product from one whose laptop picked it. `ContrastSuite` has a
dedicated test asserting the two blocks are equal
([`ContrastSuite.scala`](../../frontend/ui-kernel/test/src/kui/ui/kernel/theme/ContrastSuite.scala),
test *"the media-query dark palette and the explicit dark palette agree"*).

### 2.2 How the choice is applied

[`frontend/ui-kernel/src/kui/ui/kernel/theme/Theme.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/theme/Theme.scala)
does exactly one thing: it keeps a `data-theme` attribute on `<html>` in step with the user's
choice. No colour is ever computed in Scala.

| Choice | Attribute written on `<html>` | Which rules then win |
| --- | --- | --- |
| `Auto` (default) | attribute **removed** | Only the media query decides, so the page follows the OS live |
| `Light` | `data-theme="light"` | The media query's guard excludes it; dark cannot apply |
| `Dark` | `data-theme="dark"` | The last block in the file wins over any system setting |

`Auto` must *remove* the attribute rather than write `"auto"`, because the media query matches on the
attribute's absence.

Supporting pieces:

- **Persistence** — `Theme.StorageKey = "kui.theme"` in `localStorage`, through Airstream's
  `WebStorageVar`. A browser that refuses storage (Safari private browsing throws on the first
  write; enterprise policy can disable it) yields an ordinary in-memory `Var`: the switcher still
  works, it just forgets on reload. An unrecognised stored value decodes to `Auto`.
- **System preference** — `window.matchMedia("(prefers-color-scheme: dark)")`, wrapped in a `Try`
  because jsdom and some embedded browsers do not implement it. Absent means "the system does not
  ask for dark". A `change` listener is what makes `Auto` live.
- **`Theme.effective`** — a `Signal[ThemeChoice]` that is never `Auto`; components that need to know
  whether it is *currently* dark read this, not `choice`.
- **`Theme.install()`** — called once by the shell during start-up; the subscription is deliberately
  never released.

### 2.3 Where the switch appears in the UI

- [`layout/Header.scala`](../../frontend/ui-shell/src/kui/ui/shell/layout/Header.scala) — an icon
  button cycling `Auto → Light → Dark → Auto`, icon `Icon.dot` / `Icon.sun` / `Icon.moon`, with an
  `aria-label` that states the current state in words ("Switch theme (currently following the
  system)").
- [`page/SettingsPage.scala`](../../frontend/ui-shell/src/kui/ui/shell/page/SettingsPage.scala) — the
  same three values as an explicit labelled `Select`.

### 2.4 Where a new palette is dropped in

**One file, three blocks: `frontend/packages/kernel/styles/10-tokens.css`.** A new palette means
replacing the 13 colour values in `:root`, and the same 13 in *both* dark blocks. Nothing else has
to change for colour: no Scala file, no component, no other stylesheet. That is the property UI-002
was designed to buy, and UI-013's acceptance criterion is literally `git diff --stat frontend/`
showing only that file changed. Anything *else* the import forces you to touch is a finding about
KUI's own code — some component holding a literal it should have read from a token — and UI-013 asks
for it to be reported as such rather than absorbed quietly. §6 below is the list of places where that
is already known to be true.

Two constraints on the drop-in, both non-negotiable and both enforced by tests:

- the token **names** must not change unless `Tokens.scala` changes with them (`TokensSuite`);
- every pair in the table in [`tokens.md`](tokens.md) must still pass (§5).

---

## 3. The CSS files and the cascade

### 3.1 The files

CSS lives next to the Scala sources of the module that owns it, one directory per module. Eight
files, 1 471 lines total.

| File | Lines | Owns |
| --- | --- | --- |
| [`packages/kernel/styles/00-reset.css`](../../frontend/packages/kernel/styles/00-reset.css) | 137 | Browser-default normalisation, the global `:focus-visible` ring, the `prefers-reduced-motion` neutraliser, `.kui-visually-hidden` |
| [`packages/kernel/styles/10-tokens.css`](../../frontend/packages/kernel/styles/10-tokens.css) | 210 | All 46 tokens, in three theme blocks |
| [`packages/kernel/styles/20-kernel-controls.css`](../../frontend/packages/kernel/styles/20-kernel-controls.css) | 299 | Icon, spinner, Button, Field (input/select), Tag, Card, Tabs, action gate |
| [`packages/kernel/styles/21-kernel-overlays.css`](../../frontend/packages/kernel/styles/21-kernel-overlays.css) | 302 | Dialog, Drawer, Toast, Tooltip, Breadcrumbs, EmptyState |
| [`packages/kernel/styles/22-kernel-table.css`](../../frontend/packages/kernel/styles/22-kernel-table.css) | 66 | DataTable: sticky header, sort button, row hover, loading dim, empty row |
| [`packages/shell/styles/30-shell.css`](../../frontend/packages/shell/styles/30-shell.css) | 269 | The application frame (grid), skip link, header, sidebar, content, page wrapper, gallery, error pages, gateway-unreachable overlay, the responsive breakpoint |
| [`packages/shell/styles/31-shell-nav.css`](../../frontend/packages/shell/styles/31-shell-nav.css) | 136 | Capability-driven navigation states (dimmed / disabled / degraded dot), the capability banner, the feature fallback panel, the feature-loading spinner |
| [`packages/feature-clusters/styles/40-clusters.css`](../../frontend/packages/feature-clusters/styles/40-clusters.css) | 52 | The clusters feature page: layout, lead paragraph, form row, error and stale-data treatments |

### 3.2 The ordering rules

Plain CSS has no module system: when two rules match the same element with the same specificity, the
one written **later** wins. So the assembly order *is* the cascade. It is written out as an explicit
`@import` list in
[`frontend/packages/kernel/styles/index.css`](../../frontend/packages/kernel/styles/index.css), which
**Vite** inlines at build time into the single stylesheet it emits into `frontend/dist/assets/`.

Until ADR-048 the order was computed instead, by
[`build-tests/src/kui/build/CssPipeline.scala`](../../build-tests/src/kui/build/CssPipeline.scala)
driven from a Mill task. That task is gone with the Scala.js build; `CssPipeline` survives only as
the record of the grouping rule the list is written to obey. What a scan could not do is forget a
file, so [`CssReferences.scala`](../../build-tests/src/kui/build/CssReferences.scala) fails the build
if any `styles/*.css` file in the workspace is missing from the list or named by it twice.

Four groups, in this order (ADR-024 as amended by ADR-048 §6):

1. **tokens** — first, so a reader opening the built stylesheet sees the palette before the rules
   consuming it.
2. **reset** — next, because its job is to overwrite browser defaults and everything KUI writes
   afterwards must be able to overwrite the reset in turn.
3. **kernel** — the shared primitives every screen is built from (any file in
   `packages/kernel/styles/` that is not tokens or reset).
4. **features** — last, so a feature can adjust a kernel primitive on its own page without winning a
   specificity war. This is why `40-clusters.css` can dim a table without `!important`.

Two details worth knowing before you move a file:

- **The numeric filename prefixes do not decide the order.** The `@import` list does. The prefixes
  are there so a directory listing reads in cascade order for a human; a file called
  `00-anything.css` in a feature package still lands after every kernel file, because that is where
  the list puts it.
- **Within a group, files sort by module then file name**, so the output is byte-identical for
  identical inputs. That determinism is what lets Mill and the browser cache it.

Each source file is preceded in the output by a banner comment naming its module and file name, so a
developer reading the served stylesheet in the network tab can find the source without a source map.

The one consequence worth flagging for a redesign: **CSS is concatenated for everyone**, including
users who never open a given feature, while the *JavaScript* is split per feature. That is a
deliberate trade recorded in `40-clusters.css` — a stylesheet is small, and a flash of unstyled
content when a lazily loaded module arrives is not worth avoiding it. Feature stylesheets must stay
small enough for that to remain true.

### 3.3 Where the stylesheet is loaded

[`frontend/index.html`](../../frontend/index.html):
a single stylesheet link, which Vite writes into `index.html` with a content hash in its name.
There is no other stylesheet, no inline
`<style>`, and no web-font link (see remediation R7).

### 3.4 Class naming

BEM with a `kui` prefix: `kui-<block>__<element>--<modifier>`. The point is not orthodoxy — it is
that **every selector is a single class with the same specificity**, so the cascade is decided by the
file order above rather than by whoever nested their selectors most deeply.

No Scala file writes a class-name string literal. Names live in one `Css` object per module and are
referenced by constant, so a typo becomes a compile error rather than a silently unstyled element:

- [`ui-kernel/.../css/KernelCss.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/css/KernelCss.scala) — 96 constants
- [`ui-shell/.../ShellCss.scala`](../../frontend/ui-shell/src/kui/ui/shell/ShellCss.scala) — 52 constants
- [`ui-clusters/.../ClustersPage.scala`](../../frontend/ui-clusters/src/kui/ui/clusters/ClustersPage.scala) — 7 constants in a private object at the foot of the file

---

## 4. Kernel components and what they depend on

Every primitive lives in `frontend/ui-kernel/src/kui/ui/kernel/component/`. The API of each is
documented in [`components.md`](components.md); this section records only the *style* surface —
which class names and which tokens each one reaches for.

Rules that apply to all of them, and that a restyle must not break:

- A component owns no application state; it takes a `Var`/`Signal` and writes through an `Observer`.
- Every component takes an optional `testId` rendered as `data-testid`. End-to-end tests select on
  that and **never** on a CSS class or on visible text — which is precisely what makes a restyle
  safe.
- A disabled control is disabled *in the DOM*, not merely styled to look it.
- **No component contains a hex value.** There is not one in `component/`, and there must never be.
- **No component needs its stylesheet to work** ("degraded rendering"): function comes from the HTML
  — a `<button>`, a `disabled` attribute, a `role` — and CSS supplies appearance only.
- Missing data renders as an em dash `—`, never as a blank cell.

### 4.1 Global rules in the reset

| Rule | What it does | Tokens |
| --- | --- | --- |
| `*, *::before, *::after { box-sizing: border-box }` | Declared width includes padding and border | — |
| margin zeroing on headings/paragraphs/lists | Spacing comes from the scale instead | — |
| `html { scroll-behavior: smooth; scrollbar-gutter: stable }` | Smooth anchor jumps; reserved scrollbar gutter so a growing page does not shift sideways | — |
| `body { … }` | Base type and colour | `line-height-normal`, `font-family-sans`, `font-size-md`, `color-text`, `color-surface` |
| `input, button, textarea, select { font: inherit; color: inherit }` | Form controls are the one part of HTML that does not inherit typography | — |
| `img, picture, svg, video, canvas { display: block; max-width: 100% }` | No baseline gap, no horizontal overflow | — |
| `overflow-wrap: break-word` on text elements and cells | Long broker hostnames, group ids and topic names have no spaces to break at | — |
| `:focus-visible { outline: 2px solid …; outline-offset: 2px }` | The global keyboard focus ring. `:focus-visible` is the browser's own judgement of keyboard navigation, so a mouse click draws nothing and a Tab press draws a ring | `color-focus` |
| `@media (prefers-reduced-motion: reduce)` | Animations and transitions clamped to `0.01ms`, smooth scrolling off | — |
| `.kui-visually-hidden` | Present but invisible, still announced and still focusable. **Not** `display: none`, which would also remove it from the accessibility tree | — |

### 4.2 Controls (`20-kernel-controls.css`)

| Component | Classes | Tokens used |
| --- | --- | --- |
| **Icon** ([`Icon.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Icon.scala)) | `kui-icon` | none (inherits `currentColor`) |
| **Spinner** | `kui-spinner` | none — an 800ms `kui-spin` keyframe (§6, R5) |
| **Button** ([`Button.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Button.scala)) | `kui-button`, `--primary`, `--secondary`, `--danger`, `--ghost`, `--sm`, `--md`, `--lg`, `--loading`, `__icon`, `__label` | `space-1/2/3/5`, `radius-md`, `font-weight-medium`, `font-line-height-tight`, `font-size-sm/md/lg`, `duration-fast`, `color-primary`, `color-primary-contrast`, `color-surface-raised`, `color-border-strong`, `color-text`, `color-danger` |
| **TextInput / Select** (`Field`) | `kui-field`, `__label`, `__control`, `__hint`, `__error`, `--invalid` | `space-1/2`, `radius-md`, `font-size-xs/sm/md`, `font-weight-medium`, `color-border-strong`, `color-surface-raised`, `color-text`, `color-text-muted`, `color-danger` |
| **Tag** ([`Tag.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Tag.scala)) | `kui-tag`, `--neutral/--info/--success/--warning/--danger`, `__dot`, `__remove` | `space-1/2/5`, `radius-pill`, `font-size-xs`, `font-weight-medium`, `color-text-muted`, `color-info`, `color-success`, `color-warning`, `color-danger`. The wash behind a tag is `color-mix(currentColor 14%)`, so one declaration covers all five tones and they cannot drift apart |
| **Card** ([`Card.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Card.scala)) | `kui-card`, `--elevated`, `__header`, `__body`, `__footer` | `space-2/3/4`, `radius-lg`, `color-border`, `color-surface-raised`, `shadow-md`, `font-weight-bold` |
| **Tabs** ([`Tabs.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Tabs.scala)) | `kui-tabs`, `__list`, `__tab`, `__tab--selected`, `__panel` | `space-1/2/3/4`, `color-border`, `color-text-muted`, `color-text`, `color-primary`, `color-focus`, `font-size-md`, `font-weight-medium` |
| **ActionPermissionWrapper** ([`ActionPermissionWrapper.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/ActionPermissionWrapper.scala)) | `kui-action-gate` | none directly; sets `cursor` and `opacity` on `[aria-disabled='true']` |

### 4.3 Overlays (`21-kernel-overlays.css`)

| Component | Classes | Tokens used |
| --- | --- | --- |
| **Dialog** ([`Dialog.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Dialog.scala)) | `kui-dialog-host`, `-backdrop`, `kui-dialog`, `--sm/--md/--lg`, `__header`, `__title`, `__close`, `__body`, `__actions` | `z-dialog`, `space-1/4/8`, `radius-lg/sm`, `color-surface-raised`, `color-border`, `shadow-md`, `font-size-lg`, `font-weight-bold` |
| **Drawer** ([`Drawer.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Drawer.scala)) | `kui-drawer-host`, `-backdrop`, `kui-drawer`, `--right/--left`, `__header`, `__title`, `__close`, `__body` | `z-drawer`, `space-3/4`, `color-surface-raised`, `color-border`, `shadow-md`, `font-size-lg`, `font-weight-bold` |
| **Toast** ([`Toast.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Toast.scala)) | `kui-toast-stack`, `__queued`, `kui-toast`, `--neutral/--info/--success/--warning/--danger`, `__content`, `__title`, `__message`, `__dismiss` | `z-toast`, `space-2/3/4/6`, `radius-md`, `color-surface-raised`, `shadow-md`, `color-text`, `color-text-muted`, `color-info/success/warning/danger`, `font-size-xs/sm`, `font-weight-medium` |
| **Tooltip** ([`Tooltip.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Tooltip.scala)) | `kui-tooltip-host`, `kui-tooltip`, `--top/--bottom/--left/--right` | `z-dropdown`, `space-1/2`, `radius-sm`, `color-text` (as background), `color-surface` (as text), `font-size-xs` |
| **Breadcrumbs** ([`Breadcrumbs.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Breadcrumbs.scala)) | `kui-breadcrumbs`, `__list`, `__item`, `__separator` | `space-1`, `font-size-sm`, `color-text-muted`, `color-primary`, `color-border-strong` |
| **EmptyState** ([`EmptyState.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/EmptyState.scala)) | `kui-empty-state`, `__icon`, `__title`, `__description`, `__action` | `space-2/4/7`, `color-text-muted`, `color-text`, `font-size-xl/sm`, `font-weight-medium` |
| **FocusTrap** ([`FocusTrap.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/FocusTrap.scala)) | none — behaviour only | none |

`kui-empty-state__action` is declared in `KernelCss` but has no rule in the stylesheet; it is a hook
for a caller's button and inherits the button's own styling.

### 4.4 Table (`22-kernel-table.css`)

**DataTable** ([`DataTable.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/component/DataTable.scala)):
`kui-table`, `--loading`, `__body`, `__row`, `__cell`, `__header-cell`, `__sort`,
`__sort-placeholder`, `__empty`. Tokens: `space-1/2/3`, `font-size-sm`, `font-weight-medium`,
`color-border`, `color-border-strong`, `color-text-muted`, `color-surface`.

Three behaviours here are structural rather than decorative and are called out again in §7:

- the header is `position: sticky; top: 0` with an opaque `color-surface` background, which only
  works with `border-collapse: collapse` if the border sits on the *cell* — hence the border being
  declared on `__header-cell` and not on the row;
- `__sort-placeholder` reserves `1em` so a column does not shift sideways the moment it is sorted;
- `--loading` dims the existing rows (`opacity: 0.5`) rather than replacing them, because replacing
  them collapses the table, jumps the page, and jumps it back a moment later.

### 4.5 Shell (`30-shell.css`, `31-shell-nav.css`)

The frame is a **CSS grid** — `grid-template-areas: "header header" / "sidebar content"` — rather
than a flex column containing a flex row, so the header can span both columns and the sidebar can
reach the bottom of the viewport without either knowing the other's size.

| Region | Classes | Notes for a restyle |
| --- | --- | --- |
| Frame | `kui-shell` | Grid columns `var(--kui-sidebar-width, 15rem) 1fr` — see R2 |
| Skip link | `kui-shell__skip` | Off-screen at `top: -3rem`, slides to `top: var(--kui-space-2)` on focus. Must remain the first focusable element in the document |
| Header | `__header`, `__brand`, `__header-spacer`, `__header-actions`, `__version` | The cluster switcher is in the drawer, not here: it scopes the destinations below it. `__cluster-slot`, which reserved space for it, was removed once that was settled |
| Sidebar | `__sidebar`, `__sidebar-list`, `__sidebar-link`, `--current`, `--dimmed`, `--disabled`, `-label`, `-dot` | See §7 on the non-colour cues |
| Content | `__content`, `__page`, `__page-error`, `__page-error-detail` | `__content:focus { outline: none }` — it is focusable only so the skip link can move focus into it |
| Gallery | `__gallery`, `-section`, `-row`, `-swatch`, `-icons`, `-icon` | A development page (the component gallery, UI-003). Its styling only has to be legible, not designed |
| Error pages | `__error-page`, `-detail` | 403 / 404, capped at `40rem` because one-message pages read better in a column |
| Gateway unreachable | `__unreachable`, `-card`, `-icon`, `-countdown`, `-last-contact` | The one full-screen state. `position: fixed`, because a state that scrolls away is a state the user can scroll past into a dead application |
| Capability banner | `__capability-banner`, `-body`, `-icon`, `-text` | Bordered in `color-warning` |
| Feature fallback | `__fallback`, `-title`, `-reason`, `-reason-icon`, `-since`, `-feature`, `-actions`, `-error`, `-still-works`, `-still-works-title`, `-still-works-empty` | Capped at `44rem`: it is prose, and prose set across a wide screen is measurably harder to read |
| Feature loading | `__feature-loading`, `-icon`, `-label` | Occupies the content area rather than sitting at the top, so the page does not jump when the feature replaces it |

**The one breakpoint in the product**: `@media (max-width: 48rem)` collapses the grid to a single
column and turns the sidebar into a wrapping strip above the content. Nothing is hidden — a
navigation that disappears on a narrow window is a navigation somebody cannot use.

### 4.6 Feature CSS (`40-clusters.css`)

`kui-clusters`, `__lead`, `__form`, `__error`, `__stale`, `__table--stale`, `__fallback`. The only
notable rule is `__table--stale { opacity: 0.6 }`, which draws ADR-032's stale-data rule: data that
was fetched successfully stays on screen and is dimmed rather than cleared — and never as the only
signal, because the sentence above the table says the same thing in words.

---

## 5. The contrast test

[`ContrastSuite.scala`](../../frontend/ui-kernel/test/src/kui/ui/kernel/theme/ContrastSuite.scala)
fails the build on any documented foreground/background pair that misses WCAG AA. Neither reference
product checks contrast, and several of Kafbat's muted-text pairs fail it.

### 5.1 What it does

Contrast is the one visual property that is objectively measurable and invisible to the person
choosing the colour: a designer on a good monitor in a lit room picks a grey a user on a dimmed
laptop cannot read, and nobody notices until an audit.

The suite computes the WCAG 2.2 contrast ratio — relative luminance per channel with the sRGB gamma
undone, weighted 0.2126 red / 0.7152 green / 0.0722 blue, then
`(lighter + 0.05) / (darker + 0.05)` — and compares it against a documented minimum.

Both inputs are compiled into the test binary by `build.mill` (Scala.js has no filesystem):

- the **token stylesheet** itself, so the checked values are the ones that actually ship. The suite
  splits the file at the last occurrence of `@media (prefers-color-scheme: dark)` and of
  `:root[data-theme="dark"]` and parses `--kui-color-*: #rrggbb;` declarations out of each of the
  three blocks;
- **`docs/frontend/tokens.md`**, so the pair list is *documentation that happens to be executable*.
  Any three-cell Markdown table row whose first two cells are `--kui-color-*` names and whose third
  parses as a number becomes a checked pair. Adding a row to that table makes the suite check it.
  That ordering is deliberate: a pair list maintained only in a test file drifts out of the docs
  within a month.

Five tests run:

1. the stylesheet defines every colour in `Tokens.Color`, in both themes;
2. the documented pair list is non-empty and contains at least one 4.5 pair (a parser that silently
   matched nothing would make every other assertion vacuously true — this is the failure mode the
   whole suite exists to prevent);
3. every documented pair meets its minimum in **light**;
4. every documented pair meets its minimum in **dark**;
5. the media-query dark palette and the explicit dark palette are identical.

### 5.2 The 15 enforced pairs

| Foreground | Background | Minimum |
| --- | --- | --- |
| `--kui-color-text` | `--kui-color-surface` | 4.5 |
| `--kui-color-text` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-text-muted` | `--kui-color-surface` | 4.5 |
| `--kui-color-text-muted` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-primary-contrast` | `--kui-color-primary` | 4.5 |
| `--kui-color-primary` | `--kui-color-surface` | 4.5 |
| `--kui-color-primary` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-success` | `--kui-color-surface` | 4.5 |
| `--kui-color-warning` | `--kui-color-surface` | 4.5 |
| `--kui-color-danger` | `--kui-color-surface` | 4.5 |
| `--kui-color-info` | `--kui-color-surface` | 4.5 |
| `--kui-color-border-strong` | `--kui-color-surface` | 3.0 |
| `--kui-color-border-strong` | `--kui-color-surface-raised` | 3.0 |
| `--kui-color-focus` | `--kui-color-surface` | 3.0 |
| `--kui-color-focus` | `--kui-color-surface-raised` | 3.0 |

4.5:1 is the AA threshold for body text; 3:1 is the AA threshold for user-interface components and
graphical objects (WCAG 1.4.11), which is what a control outline and a focus ring are.

### 5.3 What a new palette must satisfy

1. **All 15 pairs, in both themes, at the stated minimum.** All 30 checks.
2. **UI-013's rule for a failure: adjust, do not adopt.** An imported pair that fails WCAG AA is
   adjusted until it passes; the minimum is never relaxed and a row is never deleted from the pair
   table. Accessibility outranks fidelity to a mockup; this is a decision already taken, not a
   question to send back to the designer. The adjusted value keeps a `/* kui */` provenance marker,
   and UI-013 requires the original design value to be recorded *beside* the shipped one — without
   it, the next importer reads the mismatch as a transcription error and "fixes" the accessibility
   adjustment back out.
3. **The dark palette must be written identically in both dark blocks.**
4. **A token the import does not define keeps KUI's value** and is listed in `tokens.md` under "not
   covered by the import". A gap in the import is not a reason to lose a token components depend on.
5. **Do not add a token group no component needs**, however many the import defines. UI-013 allows
   the set to grow by an individually justified semantic token, never by a component-scoped one.
6. `--kui-color-border` is intentionally exempt: it carries no meaning and nothing is lost if it
   cannot be seen. Anything a user must perceive uses `--kui-color-border-strong`, which *is*
   checked. A redesign must keep that split; promoting `border` to a load-bearing role means adding
   it to the pair table.

### 5.4 The limits of the check

It verifies documented *token pairs*, and only those whose two names both resolve in the palette: a
row naming a token that does not exist is skipped in silence rather than failing, so a renamed token
can quietly take a pair out of the check. (Test 1 catches a *missing* token, not a *mistyped pair*.)
It also cannot see a colour composed at run time — the
`color-mix()` hover shades, the `rgba(0, 0, 0, 0.45)` backdrops, the `opacity: 0.55` on disabled
controls, or text laid over a `currentColor 14%` tag wash. A palette whose primary is much lighter
could make the 88 %-toward-black hover shade indistinguishable from the base without failing a single
test. Check those by eye against the component gallery.

---

## 6. Hard-coded visual values: the remediation list

These are the places where a visual value is written literally instead of coming from a token. Each
one is a spot where a redesign will not propagate on its own — you change the token and this value
stays where it was. They are listed so the import can decide to fix or to accept each, deliberately.

Ordered roughly by how much trouble each will cause.

### R1 — `--kui-z-modal` does not exist

**File:** `frontend/packages/shell/styles/30-shell.css:226` —
`z-index: var(--kui-z-modal, 1000);` on `.kui-shell__unreachable`.

There is no `--kui-z-modal` token. The declared stacking tokens are `dropdown` (100), `drawer` (200),
`dialog` (300), `toast` (400). The `var()` therefore always falls back to the literal `1000`,
placing the gateway-unreachable overlay above every token layer.

This may well be the intent — when the gateway is unreachable, nothing behind the overlay works, so
covering even a toast is arguably right — but it is intent expressed by accident. **`TokensSuite`
cannot catch this**: it parses *declarations* (`--kui-…:`), not *usages*, so referencing an undefined
custom property is silent. Either add a fifth stacking token or use `--kui-z-toast` and say why.

### R2 — `--kui-sidebar-width` does not exist

**File:** `frontend/packages/shell/styles/30-shell.css:12` —
`grid-template-columns: var(--kui-sidebar-width, 15rem) 1fr;`

Same class of problem. The sidebar width is effectively the literal `15rem`, and a design that wants
a different sidebar width has to edit shell CSS rather than a token. If the redesign treats sidebar
width as a design decision, this should become a real token.

### R3 — hard-coded font weights in the shell

- `30-shell.css:53` — `.kui-shell__brand a { font-weight: 700 }`. **700 is not on the scale**; the
  scale tops out at `--kui-font-weight-bold: 600`. A redesign changing the bold weight will not move
  the brand.
- `30-shell.css:115` — `.kui-shell__sidebar-link--current { font-weight: 600 }`. The right value, but
  written literally instead of as `var(--kui-font-weight-bold)`.

### R4 — hard-coded opacity values

Six literals, all in the 0.5–0.6 band, all meaning some flavour of "de-emphasised", none tokenised:

| File:line | Selector | Value | Means |
| --- | --- | --- | --- |
| `20-kernel-controls.css:50` | `.kui-button:disabled` | `0.55` | disabled |
| `20-kernel-controls.css:133` | `.kui-field__control:disabled` | `0.55` | disabled |
| `20-kernel-controls.css:298` | `.kui-action-gate [aria-disabled='true']` | `0.55` | permission-blocked |
| `31-shell-nav.css:29` | `.kui-shell__sidebar-link--dimmed .…-label` | `0.55` | capability unavailable |
| `22-kernel-table.css:61` | `.kui-table--loading .kui-table__body` | `0.5` | refreshing |
| `40-clusters.css:46` | `.kui-clusters__table--stale` | `0.6` | stale data |

Three distinct meanings share two near-identical values by coincidence rather than by decision.
The value `0.55` alone appears four times, so a redesign that wants disabled controls at a
different strength has four edits to find. Candidate
remediation: `--kui-opacity-disabled` and `--kui-opacity-stale`. Note the accessibility floor in §7:
whatever the values become, none of these may be the *only* signal.

### R5 — motion values outside the duration scale

- `20-kernel-controls.css:21` — `.kui-spinner { animation: kui-spin 800ms linear infinite }`. 800ms
  is not `--kui-duration-fast` (100ms) or `--kui-duration-normal` (200ms), and arguably should not be
  — a spinner is a different kind of motion from a state transition — but it is undocumented as a
  third value.
- `30-shell.css:28` — `.kui-shell__skip { top: -3rem }`. An off-screen distance tied to nothing.

### R6 — backdrop colours are literal `rgba(0, 0, 0, …)`

- `21-kernel-overlays.css:20` — `.kui-dialog-backdrop { background-color: rgba(0, 0, 0, 0.45) }`
- `21-kernel-overlays.css:94` — `.kui-drawer-backdrop { background-color: rgba(0, 0, 0, 0.35) }`

These are the only two colours in kernel CSS that are not tokens. They do not change between light
and dark, and the two different alphas are undocumented. A design with a tinted or blurred scrim has
no token to change.

### R7 — Inter is named but never shipped

`--kui-font-family-sans` names `Inter` first, but `index.html` links no font file and no `@font-face`
rule exists anywhere in the eight CSS files. Inter therefore renders only for users who happen to
have it installed locally; everyone else silently gets `system-ui`. Two users on different machines
see different type, and no test notices.

If the design specifies a typeface, shipping it — self-hosted, since the gateway serves the assets —
is part of the import, not an afterthought. Note the ordering hazard: a webfont that arrives after
first paint reflows the page.

### R8 — literal `rem` widths, the layout constants

None of these is wrong; all are literals a design system might reasonably want to own. Collected so
they can be found in one pass:

| Value | File:line | What it sizes |
| --- | --- | --- |
| `24rem` / `36rem` / `56rem` | `21-kernel-overlays.css:35,39,43` | Dialog `--sm` / `--md` / `--lg` max-widths |
| `28rem` | `Drawer.scala:32` | Default drawer width — a Scala default parameter, not CSS, and passed through as an inline `style` attribute |
| `min(24rem, …)` | `21-kernel-overlays.css:145` | Toast stack width |
| `18rem` | `21-kernel-overlays.css:215` | Tooltip max-width |
| `64rem` | `30-shell.css:135` | Page content max-width |
| `40rem` | `30-shell.css:205` | Error-page max-width |
| `34rem` | `30-shell.css:239` | Gateway-unreachable card max-width |
| `44rem` | `31-shell-nav.css:75`, `40-clusters.css:20,51` | Prose measure (fallback panel, lead paragraphs) |
| `60rem` | `40-clusters.css:15` | Clusters page max-width |
| `8rem` | `30-shell.css:168` | Gallery icon grid minimum column |
| `48rem` | `30-shell.css:182` | **The one responsive breakpoint in the product** |

### R9 — literal border and outline widths

`1px` (most borders), `2px` (the selected-tab underline and the focus outline), `3px` (the toast's
left tone stripe at `21-kernel-overlays.css:156`, and the sidebar current-item inset bar at
`30-shell.css:116`). There is no border-width scale. A design with heavier or hairline borders means
touching every one of these by hand.

Note that the focus ring's `2px` is duplicated in two places — `00-reset.css:102` (global) and
`20-kernel-controls.css:285` (the tab panel). Changing one and not the other produces two different
focus rings.

### R10 — `font-size: 0.6em` on the degraded dot

`31-shell-nav.css:47` — `.kui-shell__sidebar-link-dot { font-size: 0.6em }`. A relative size outside
the type scale, sizing the amber degraded indicator.

### R11 — token fallbacks that duplicate values

Several `var()` calls carry a literal fallback that repeats the token's real value:

- `00-reset.css:56–60, 102` — `1.5`, `system-ui, sans-serif`, `0.875rem`, `#1a1a1a`, `#ffffff`,
  `#1668dc`
- `31-shell-nav.css:81` — `var(--kui-font-size-xl, 1.25rem)`
- `31-shell-nav.css:120` — `var(--kui-font-size-md, 1rem)` — note the fallback here says `1rem`, but
  `--kui-font-size-md` is `0.875rem`. They disagree.

In the reset these are defensible: they are the last line of defence if the token sheet fails to
load, and `#1a1a1a` on `#ffffff` at least stays readable. In `31-shell-nav.css` they are noise, and
one of them is wrong. Either way, **a redesign that changes a token does not change these**, so a
palette change that removed or renamed a token would leave stale hard-coded colours behind in the
reset.

### R12 — inline `style` attributes from Scala

Two, both computing a width that cannot be known at authoring time:

- [`Drawer.scala:54`](../../frontend/ui-kernel/src/kui/ui/kernel/component/Drawer.scala) —
  `styleAttr := s"width: $width"`, from a `String` parameter defaulting to `"28rem"`. Documented as
  deliberate: "any CSS length. Given as a string rather than a token because the right width depends
  on the content."
- [`DataTable.scala:99`](../../frontend/ui-kernel/src/kui/ui/kernel/component/DataTable.scala) —
  `column.width.map(value => styleAttr := s"width: $value")`, per-column widths supplied by callers.

An inline style has higher specificity than any stylesheet rule, so a redesign cannot override either
from CSS. Both are narrow and neither carries a colour, so this is a note rather than a defect.

### What is *not* on this list

Worth stating, because it is the good news: **there is not one hex colour, one spacing literal, or
one font-size literal in `frontend/ui-kernel/src/**/component/`**, and not one in
`20-kernel-controls.css`, `21-kernel-overlays.css` or `22-kernel-table.css`. Every colour in every
one of the eight stylesheets is a token, except the two backdrops in R6 and the reset fallbacks in
R11. The remediation list above is short, and that is the point of ADR-024.

---

## 7. What a design import can and cannot change

The dividing line: **appearance is replaceable; behaviour and accessibility guarantees are not.**
Where the design implies a behaviour that contradicts what the research established, the research
wins and the difference is recorded (§7.3).

### 7.1 Safe to replace wholesale — purely visual

| Surface | Where | Why it is safe |
| --- | --- | --- |
| **All 13 colour values, both themes** | `10-tokens.css`, three blocks | Nothing in Scala reads a value. Subject only to §5's contrast gate |
| **The spacing scale's values** | `10-tokens.css` §1.3 | Nine names are referenced; the numbers behind them are free. Changing the *count* is not free — every reference to a removed step breaks silently |
| **Font families, sizes, weights, line heights** | `10-tokens.css` §1.4 | Same: names fixed, values free. If `--kui-font-size-md` moves far from 14px, re-check table density by eye — nothing tests it |
| **Radii** | `10-tokens.css` §1.5 | Entirely cosmetic |
| **Shadow values** | `10-tokens.css` §1.6 | Cosmetic. Keep the two-level discipline |
| **Motion durations** | `10-tokens.css` §1.7 | Cosmetic, and already neutralised under reduced motion |
| **Layout constants** | R8 | Max-widths, the breakpoint, dialog sizes. Free to change; keep the prose measure narrow (§7.2) |
| **Icon artwork** | `Icon.scala` | 18 icons, inline 24×24 stroked SVG on one grid. Paths can be swapped freely provided the skeleton keeps `1em` sizing, `currentColor`, `aria-hidden="true"` and `focusable="false"` (§7.2) |
| **The component gallery's own styling** | `30-shell.css`, `__gallery*` | A development page; it only has to be legible |
| **Wording** | `Messages.scala` per module | Not styling, but equally free — no test selects on visible text |

### 7.2 Must survive any restyle — behaviour and accessibility

Each of these is either enforced by a test or is a researched behaviour. Restyle them; do not remove
them.

**Enforced by `A11ySuite`** ([`A11ySuite.scala`](../../frontend/ui-kernel/test/src/kui/ui/kernel/component/A11ySuite.scala)) —
a redesign that drops any of these fails the build:

- Button: a real `<button type="button">`; while loading, `aria-busy="true"` and `disabled`.
- TextInput: `aria-invalid="true"` when invalid, `aria-describedby` pointing at the message, and the
  error message in a `role="alert"` region.
- Toast: `role="status"`, dismiss button carries an `aria-label`.
- Tabs: `role="tablist"` / `role="tab"` with `aria-selected` and `aria-controls`, roving `tabindex`,
  and `role="tabpanel"` with `aria-labelledby`.
- Dialog and ConfirmDialog: `role="dialog"`, `aria-modal="true"`, `aria-labelledby`, `tabindex="-1"`,
  and a labelled close button.
- Tooltip: trigger carries `aria-describedby`; the bubble is `role="tooltip"`.
- Breadcrumbs: `<nav aria-label="Breadcrumb">` with `aria-current="page"` on the last item.
- DataTable: `<th scope="col">` with `aria-sort`.
- **Every icon is hidden from assistive technology.**

**Enforced by `ContrastSuite`:** the 15 pairs in §5.2, in both themes. This is the hard gate on a new
palette.

**Enforced by `TokensSuite`:** token names in `Tokens.scala` and `10-tokens.css` agree exactly, the
set stays under 60, and no colour/spacing/type/radius token names a component. A design system that
arrives with per-component tokens must be *flattened into* this set, not adopted alongside it.

**Not test-enforced, but non-negotiable** — each is a decision with a recorded reason:

- **The focus ring exists and is never removed.** `:focus-visible` in `00-reset.css:102`. It may
  change colour, width and offset; it may not become `outline: none` without an equally visible
  replacement. The two places that draw it (R9) must stay in step.
- **`prefers-reduced-motion` is honoured.** `00-reset.css:106–121`. Animations are *neutralised*
  (0.01ms) rather than removed, so code waiting for `transitionend` still gets its event. Any new
  animation inherits this automatically; do not add one that opts out.
- **Nothing communicates by colour alone.** Stated at the top of `31-shell-nav.css` and true
  throughout: the current nav item is marked by weight *and* an inset bar as well as colour; a
  dimmed entry also carries `aria-disabled` or a tooltip; the amber degraded dot is always
  accompanied by a tooltip sentence; stale table data is dimmed *and* announced in words above the
  table. Roughly one man in twelve cannot reliably tell amber from grey, and "the entry that looks
  slightly different" is not a message. A redesign may change every one of these treatments; it may
  not reduce any of them to a single colour cue.
- **`.kui-visually-hidden` stays `clip-path`-based, not `display: none`.** `display: none` removes
  the element from the accessibility tree as well as from the page.
- **A disabled control is disabled in the DOM.** Styling something to look disabled is not
  disabling it.
- **The skip link stays first in the document and stays reachable.** Moving it down, on the grounds
  that it is invisible anyway, makes every page start with a walk through the navigation.
- **Degraded rendering: no component may *need* its CSS.** If the stylesheet fails to load, every screen
  must still be operable — a `<button>` is still a button, a disabled control still refuses input, an
  unselected tab panel is simply not in the document. A redesign that moves function into CSS
  (`display: none` used to gate interaction, a `::after` carrying meaning, a click target that only
  exists once styled) breaks this.
- **The navigation never disappears.** The `48rem` breakpoint reflows the sidebar into a strip; it
  hides nothing.
- **Stale data stays on screen.** ADR-032: what was fetched successfully is dimmed, never cleared.
  Clearing it hands the operator a blank screen at the exact moment they need the last known state.
- **Loading dims rows rather than replacing them.** Same reasoning, plus the page-jump argument in
  §4.4.
- **The gateway-unreachable state is `position: fixed` and covers everything.** By definition nothing
  behind it works; a state the user can scroll past is a state they will scroll past.
- **`data-testid` is the selector contract.** End-to-end tests select on `data-testid` and never on a
  class or on visible text — which is exactly the property that lets class names be rewritten freely.
  A redesign must keep emitting them.
- **Reserved space stays reserved.** `__sort-placeholder` (so sorting does not shift a column), the transparent 2px underline
  on unselected tabs (so selecting one does not move it), and the loading spinner replacing the icon
  rather than joining it (so a button does not change width). These look like styling; each is
  preventing a specific layout jump.
- **The three-state theme model.** `Auto` is a state, not a synonym. A design that ships one palette
  does not license removing dark mode: dark mode is table stakes for a tool operators stare at all
  day, and Kouncil's omission of it was recorded as a gap, not a simplification.
- **`color-scheme` is declared in both palettes**, so browser-drawn scrollbars, form controls and
  selection colours match the theme.

**Structural facts that constrain a redesign** (not accessibility, but things that will bite):

- The shell is a CSS grid with named areas. A design assuming a different frame (a top-nav-only
  layout, a collapsible rail) is a change to `30-shell.css` structure and to `Layout.scala`, which is
  a *code* change, not a token swap.
- The table header's stickiness depends on `border-collapse: collapse` plus the border living on the
  cell and an opaque background on the header cell. Moving that border to the row silently breaks it.
- Inline `style` attributes (R12) outrank any stylesheet rule.
- The cascade order (§3.2) is what makes single-class BEM selectors sufficient. A design import that
  introduces nested or compound selectors starts a specificity war the pipeline was built to avoid.

### 7.3 Where a design would contradict the research

Nothing to record yet. The design has been read into
[`research/design/REFERENCE.md`](../../research/design/REFERENCE.md), but no reconciliation has been
attempted, so no contradiction has been *established* — and this document does not speculate about
the design's contents either way. The procedure UI-013 follows:

1. Anything in §7.1 → adopt.
2. Anything in §7.2 → the existing behaviour wins. Restyle it; keep it.
3. A contradiction between a design-implied behaviour and a researched one → **the research wins**
   ([`research/kafbat/ui-analysis.md`](../../research/kafbat/ui-analysis.md) and the rest of
   `research/`), and the difference is written up here, in this subsection, naming the design element,
   the researched behaviour, and the reason — UI-013 puts the same record in
   `research/design/gaps.md`, with both positions written out so the owner can revisit it
   deliberately. UI-013 already fixes the precedent for the accessibility case: an imported colour
   pair that fails WCAG AA is adjusted, not adopted.
4. A design element with no backing feature, or a required feature with no design screen → record
   both directions. A missing screen is not permission to drop the feature,
   and an artboard is not permission to add one: UI-013 forbids adding feature-matrix rows from the
   design, because rows come from research and product scope.

---

## 8. Contradictions with existing documents

Found while compiling this inventory. Recorded, not resolved — resolving them needs the owner.

**1. ADR-024 still calls the token values placeholders; UI-002 and `10-tokens.css` say they are not.**
ADR-024 ("Decision") reads: *"values come from the design project's token sheet once
`research/design/tokens.md` exists, with Kafbat's palette as the interim placeholder."*
`10-tokens.css`'s header says the opposite in as many words: *"These are NOT placeholders waiting for
a design import. Task UI-002 decided the set…"*, and UI-013 records that UI-002 took the token
decision on KUI's own authority and that the set is authoritative with `NX-007` `DONE`. The token
set as shipped is also not Kafbat's palette — six of the thirteen light values carry a `/* kui */`
marker because Kafbat's fail contrast. UI-013's "Notes for whoever picks this up" already flags this
sentence and asks for it to be fixed during the import; it is recorded here too because a reader of
ADR-024 alone would come away believing the token *vocabulary* is still negotiable, when only the
*values* are.

**2. Resolved — the division of authority is now stated explicitly.** This entry previously
recorded that the design project was called the "UI source of truth" without qualification,
which read as making the design authoritative over what a screen *does*. That has since been
settled explicitly: the design is the authority on appearance and explicitly *not* on behaviour —
plus a conflict rule, an accessibility rule and a reimplementation rule. The project is also named
**"Kafka UI v2"**, matching UI-013 and `research/design/REFERENCE.md`, so the old naming
discrepancy is gone too. The reading this document assumes throughout is now the settled one. Kept
as a row rather than deleted so a reader of an older revision can see it was settled deliberately.

**3. `tokens.md` says "~45 semantic tokens"; there are 46.**
Trivial, and `TokensSuite`'s ceiling is 60 either way, so nothing is broken. The approximation is in
prose only — one row of the comparison table in `tokens.md`. Not worth a commit on its own; correct
it when that file is next edited.

**4. Two `var()` calls reference tokens that do not exist** — `--kui-z-modal` and
`--kui-sidebar-width` (R1, R2). No document mentions either. `TokensSuite` guarantees declarations
and constants agree, and the "no hard-coded z-index" rule is stated in three separate file headers,
so a reader would reasonably conclude no such gap exists. It does, because the suite checks
declarations rather than usages.

**5. `31-shell-nav.css:120` carries a fallback that disagrees with its token** —
`var(--kui-font-size-md, 1rem)` where `--kui-font-size-md` is `0.875rem` (R11).
