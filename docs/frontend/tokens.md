# Design tokens

Every colour, spacing step, font size, radius, shadow, stacking layer and animation duration in KUI
is declared once, as a CSS custom property, in
[`frontend/ui-kernel/resources/css/10-tokens.css`](../../frontend/ui-kernel/resources/css/10-tokens.css).
Components reference them by name and never write a value of their own. Restyling KUI means editing
that one file.

This document explains the set and records the two things the file itself cannot: why the set looks
the way it does, and which colour pairs are checked for legibility.

## What a "design token" is

A *token* here is a CSS custom property — a named value declared on an element and inherited by
everything inside it:

```css
:root {
  --kui-color-primary: #0b57d0;
}

.kui-button--primary {
  background-color: var(--kui-color-primary);
}
```

The browser resolves `var(--kui-color-primary)` at paint time, every time. That is what makes theme
switching instant and rebuild-free: redefining the property on a different selector re-paints every
element that uses it, with no JavaScript involved and nothing recompiled.

## The decision this set embodies

Two products were studied before choosing (`research/kafbat/ui-analysis.md`,
`research/kouncil/ui-analysis.md`, `research/scala/frontend-research.md` §6).

| Question | Kafbat does | Kouncil does | KUI does | Why |
| --- | --- | --- | --- | --- |
| Theme model | three-state light / dark / system, in a 1 664-line `theme.ts` read by styled-components | one SCSS palette, no dark mode | three-state, as CSS custom properties | Dark mode is table stakes for a tool operators stare at all day; Kouncil's omission is a gap, not a simplification |
| Token count | hundreds of component-scoped entries (`theme.button.primary.hover.background`) | a handful of SCSS variables | ~65 semantic tokens, **no component-scoped tokens** | Per-component theming is why Kafbat's theme file is 1 664 lines and a palette change is a multi-day edit. Semantic tokens mean one edit propagates |
| Where values live | a TypeScript object; changing one needs a rebuild | SCSS; same | CSS custom properties, swappable at run time | Enables the `data-theme` switch, and lets a deployment override a colour with its own stylesheet without rebuilding KUI |
| Contrast | not enforced | not enforced | WCAG AA, enforced by `ContrastSuite` | Neither reference checks it, and several of Kafbat's muted-text pairs fail AA |

The set was first filled from Kafbat's palette, because that was the closest visual reference
available at the time. Task UI-013 has since replaced every value with the "Kafka UI v2" design's;
the reading of that design is [`research/design/REFERENCE.md`](../../research/design/REFERENCE.md),
and the mapping from its token names to these is in the table below. **The names, the set and the
constraints are KUI's own and did not change when the design arrived.** The design supplies values;
this document decides what a value is called and what it means.

### The rule that matters most: no component-scoped tokens

There is no `--kui-button-primary-hover-background` and there never will be. A button that wants a
darker shade on hover composes one from a semantic token:

```css
.kui-button--primary:hover {
  background-color: color-mix(in srgb, var(--kui-color-primary) 85%, black);
}
```

The moment a token names a component, changing that component's look stops being a token edit, and
the token file starts growing without bound. That is exactly what happened to the reference product.

## Provenance

Every colour declaration in the stylesheet ends with a trailing comment recording where its value
came from. There are three markers, and every colour carries one of them:

- `design` — the value verbatim from the artboard.
- `design-adjusted; design #rrggbb` — the value from the artboard, moved along its own hue by the
  smallest amount that clears the contrast threshold its pair is documented at, with the original
  written beside it. The original is recorded so that a later importer, seeing a value that does not
  match the artboard, does not "correct" it back to one that fails. There are three of these, listed
  under [Contrast pairs](#contrast-pairs).
- `kui` — a value KUI chose, because the design has no colour for that role.

Keep the marker when you change a value. A future reconciliation needs to tell a deliberate KUI
decision from an imported one, and after the fact there is no other way to know.

## The three switches

Three attributes on `<html>` select a palette, and they compose freely: any theme, with any accent,
at either density. Scala writes the attributes; the stylesheet does everything else, and no colour
or measurement is ever computed in Scala.

### `data-theme` — light, dark, or follow the system

KUI's own mechanism. The design is dark-by-default with a light theme and has no opinion on how a
user chooses between them; KUI keeps the three-state model it already had, because the third state
is not a synonym for either of the others.

| Choice | `data-theme` on `<html>` | Which rules win |
| --- | --- | --- |
| `Auto` (default) | attribute removed | Only `@media (prefers-color-scheme: dark)` decides, so the page follows the operating system and changes with it |
| `Light` | `light` | The media query is guarded by `:root:not([data-theme="light"])`, so dark cannot apply even at night |
| `Dark` | `dark` | `:root[data-theme="dark"]` is written after the media query, so it wins even on a system set to light |

Written by `ThemeChoice` / `Theme` in
[`theme/Theme.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/theme/Theme.scala).

### `data-accent` — which of the four seeds

The design ships four interchangeable accent palettes — blue, teal, green and amber — each with a
light and a dark variant, and treats the choice as a control the user has rather than a constant the
build has. KUI implements all four.

The reason it is worth doing, rather than shipping only the default, is structural: **the neutral
ramp is completely independent of the seed.** A seed redefines four colours (`primary`, its text
colour, its container, and that container's text colour) and touches nothing else — not a surface,
not a status colour, not a border. So the third and fourth accents cost four lines each and no new
argument about legibility of anything but themselves. `ContrastSuite` asserts that independence
directly, so an accent block that reached outside its four colours would fail the build.

| Choice | `data-accent` on `<html>` |
| --- | --- |
| `Blue` (default) | attribute removed — blue is what plain `:root` declares |
| `Teal` | `teal` |
| `Green` | `green` |
| `Amber` | `amber` |

Written by `AccentChoice` / `Accent` in
[`theme/Appearance.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/theme/Appearance.scala).

### `data-density` — comfortable or compact

The design treats density as a switch, not a theme: compact changes the vertical padding inside a
table row from 15 px to 9 px and changes **nothing else**. That restraint is the whole point. An
operator scanning thousands of topics wants more rows on the screen; shrinking type, gaps and
control heights as well would only make the interface harder to read and harder to hit.

| Choice | `data-density` on `<html>` | `--kui-density-row-padding-y` |
| --- | --- | --- |
| `Comfortable` (default) | attribute removed | `15px` |
| `Compact` | `compact` | `9px` |

Written by `DensityChoice` / `Density` in
[`theme/Appearance.scala`](../../frontend/ui-kernel/src/kui/ui/kernel/theme/Appearance.scala).

Each of the three preferences is persisted in `localStorage` (`kui.theme`, `kui.accent`,
`kui.density`). A browser that refuses storage — Safari private browsing, an enterprise policy —
gets working switches that forget the choice on reload, never an application that fails to start.

## The token table

The **Design token** column is the artboard's own name for the same value, so that this table is
also the mapping. The reading of the design, with the artboard's descriptions, is
[`research/design/REFERENCE.md`](../../research/design/REFERENCE.md).

### Colour: the neutral ramp

Identical under all four accents. The design's structure is a Material-style surface / on-surface
pairing: a container colour and the text colour that belongs on it are declared together, and a
component never picks a text colour — it uses the one that belongs to the surface it sits on. That
is what makes the palette swappable, and it is the structure this table preserves.

Two gaps in the previous set are closed here. The neutral ramp is now five surfaces deep rather than
two, because the design distinguishes the page, the drawer, a card, a hovered row and a menu; and
every status colour now has the *container* it is drawn on, because the design's status indicator is
a filled chip rather than a coloured dot, and a chip needs a fill as well as a foreground.

| Token | Design token | Light | Dark | Meaning |
| --- | --- | --- | --- | --- |
| `--kui-color-surface` | `--sf` | `#f7f9fc` | `#0e1013` | The page itself, the lowest surface |
| `--kui-color-surface-raised` | `--sfl` | `#f0f3f8` | `#15181c` | One step up: the navigation drawer, a sticky header |
| `--kui-color-surface-elevated` | `--sfc` | `#e9eef5` | `#1b1f25` | Two steps up: cards, dialogs, panels |
| `--kui-color-surface-hover` | `--sfh` | `#e0e6ef` | `#242930` | A surface under the pointer, and the fill of a neutral chip |
| `--kui-color-surface-overlay` | `--sfx` | `#d6dee9` | `#2e343c` | The highest surface: menus, popovers, a pressed control |
| `--kui-color-text` | `--on` | `#171c22` | `#e3e6eb` | Body text. Legible on all five surfaces |
| `--kui-color-text-muted` | `--onv` | `#4a525e` | `#a6acb8` | Timestamps, counts, labels, help text. Legible on all five surfaces |
| `--kui-color-border` | `--olv` | `#cbd2dc` | `#2a3038` | A separator that carries no meaning. Deliberately faint, deliberately not contrast-checked |
| `--kui-color-border-strong` | `--ol` | `#727b88` | `#666d79` | The outline of an interactive control. Contrast-checked at 3:1 |
| `--kui-color-selected` | `--sec` | `#dce5f5` | `#3a4657` | The fill behind the current navigation destination |
| `--kui-color-selected-contrast` | `--onsec` | `#121c2b` | `#dce5f5` | Text on `selected` |
| `--kui-color-accent` | `--ter` | `#00695c` | `#7fd8c7` | A second accent, independent of the seed, for a marker that must not read as "primary" |
| `--kui-color-accent-container` | `--terc` | `#9ff2e1` | `#00504a` | The fill of a chip drawn in that second accent |
| `--kui-color-accent-container-contrast` | `--onterc` | `#00201c` | `#b2f5e8` | Text on `accent-container` |
| `--kui-color-success` | `--ok` | `#2a722e` | `#8fd36a` | In sync, healthy, completed. Also the text on `success-container` |
| `--kui-color-success-container` | `--okc` | `#c8eac1` | `#1e4416` | The fill of a success chip |
| `--kui-color-warning` | `--warn` | `#7a4f00` | `#ffd180` | Degraded, lagging, deprecated. Also the text on `warning-container` |
| `--kui-color-warning-container` | `--warnc` | `#ffe1a8` | `#4a3200` | The fill of a warning chip |
| `--kui-color-danger` | `--err` | `#b3261e` | `#ffb4ab` | Unavailable, failed, destructive actions. Also the text on `danger-container` |
| `--kui-color-danger-container` | `--errc` | `#f9dedc` | `#5c1b16` | The fill of an error chip |
| `--kui-color-state-layer` | `--st` | `rgba(23,28,34,0.06)` | `rgba(227,230,235,0.08)` | The translucent wash painted over any surface on hover and while pressed |
| `--kui-color-info` | *(none)* | `#2c5bb8` | `#90caf9` | Neutral notices. The design has no informational colour, so these are the values KUI already shipped, kept and re-checked against the new surfaces |

### Colour: the accent seeds

Only these four colours change with the seed. `--kui-color-focus` is declared alongside them and
carries the same value as `--kui-color-primary`, but stays its own token: focus reads as "the thing
you are about to act on", and a later design may want it to stay loud when primary goes quiet.

| Token | Design token | Theme | blue (default) | teal | green | amber |
| --- | --- | --- | --- | --- | --- | --- |
| `--kui-color-primary` | `--pr` | light | `#0b57d0` | `#00796b` | `#2e6b14` | `#8a5a00` |
| `--kui-color-primary-contrast` | `--onpr` | light | `#ffffff` | `#ffffff` | `#ffffff` | `#ffffff` |
| `--kui-color-primary-container` | `--prc` | light | `#d3e3fd` | `#b2f5e8` | `#c7f0ae` | `#ffe0a3` |
| `--kui-color-primary-container-contrast` | `--onprc` | light | `#041e49` | `#00201c` | `#0a2a00` | `#2a1a00` |
| `--kui-color-primary` | `--pr` | dark | `#a8c7fa` | `#7fd8c7` | `#a6d98a` | `#ffd180` |
| `--kui-color-primary-contrast` | `--onpr` | dark | `#062e6f` | `#003731` | `#0f3a00` | `#4a2e00` |
| `--kui-color-primary-container` | `--prc` | dark | `#0b57d0` | `#00756b` | `#3d7822` | `#8a5a00` |
| `--kui-color-primary-container-contrast` | `--onprc` | dark | `#d3e3fd` | `#b2f5e8` | `#d9f5c6` | `#ffe7bf` |

### Everything that is not a colour

| Group | Tokens | Value | Where it came from |
| --- | --- | --- | --- |
| Space | `--kui-space-0` … `-8` | 0, 4, 8, 12, 16, 24, 32, 48, 64 px | KUI. The design uses a wide range of one-off gaps and no visible scale, so the existing 4 px scale is kept: a screen assembled from nine steps looks composed, one assembled from arbitrary pixels looks accidental |
| Density | `--kui-density-row-padding-y` | 15 px, 9 px when compact | Design |
| Font family | `--kui-font-family-display` | Space Grotesk | Design |
| Font family | `--kui-font-family-sans` | Manrope | Design |
| Font family | `--kui-font-family-icon` | Material Symbols Rounded | Design |
| Font family | `--kui-font-family-mono` | `ui-monospace` and friends | KUI. The design has no monospace face, and this product needs one: offsets, partition ids, keys and JSON payloads are compared by eye and only line up in a fixed pitch |
| Font size | `--kui-font-size-xs` … `-3xl` | 11, 12, 14, 16, 20, 24, 32 px | Design. Base body size 14 px, as before. The artboard uses sixteen distinct sizes; these seven are the ones that carry structural meaning, and the two new steps (24, 32) are for the large single figures the dashboard shows |
| Font weight | `--kui-font-weight-regular/medium/bold/display` | 400 / 500 / 600 / 700 | Design. `display` is new: the design sets its display face heavier than its body face, and one step cannot serve both |
| Line height | `--kui-font-line-height-tight/normal` | 1.25 / 1.5 | KUI. The design has no line-height system |
| Radius | `--kui-radius-xs/sm/md/lg/xl/pill` | 4 / 8 / 12 / 16 / 24 / 999 px | Design. This is the largest visible change after colour: what was a 4 px button is a 12 px button. Six steps rather than four because the design uses radius to say how large a thing is — a tag, a control, a container, a panel — and four steps could not express that |
| Shadow | `--kui-shadow-sm/md` | two levels, in the design's shadow colour | Design supplies the colour (`--sh`), KUI keeps the two-level structure. Five subtly different shadows communicate nothing |
| Stacking | `--kui-z-dropdown/drawer/dialog/toast` | 100 / 200 / 300 / 400 | KUI. A design cannot express stacking order |
| Duration | `--kui-duration-fast/normal` | 100 ms / 200 ms | KUI. Both neutralised under `prefers-reduced-motion` |

## Serving the fonts

The artboard pulls Space Grotesk, Manrope and Material Symbols Rounded from Google Fonts with a
`<link>` in the page head. **KUI does not, and must not.**

A runtime fetch from `fonts.googleapis.com` fails in exactly the environments this product is
installed in. KUI is a Kafka operations console: it runs inside private networks, on bastion hosts
and in air-gapped estates. In those places the request does not fail quickly, it *hangs* until the
proxy times out, and the interface renders in a fallback face after a visible delay — or, with
`font-display` left at its default, renders nothing at all for three seconds. It is also a leak: it
tells a third party the address of every browser that opens the console, and when.

So the fonts are **self-hosted**: the `.woff2` files ship inside the bundle, served from KUI's own
origin by the same handler that serves `kui.css`, and declared with `@font-face` in KUI's own
stylesheet. No third-party request, works offline, and one fewer connection to open on first paint.

Two consequences are already visible in this file:

- Every family token ends in a face the operating system is guaranteed to have (`system-ui`, then
  the platform defaults, then the generic family). A deployment that ships none of the font files
  still renders correctly — one step plainer, never broken. That is what makes the next point safe.
- The font *files* and their `@font-face` block are not part of this token change. Shipping a binary
  asset means a decision about which weights and which subsets to carry, and a change to how the
  frontend's static assets are assembled — neither of which belongs in the token file. Until that
  lands, the stacks above resolve to the system interface font, which is the fallback they were
  written for.

## Contrast pairs

`ContrastSuite` reads **this table** and checks every row against the values it parses out of the
stylesheet, in all eight combinations of theme and accent. That is deliberate: the pair list is
documentation first and test input second, so a token pair cannot be introduced without someone
writing down what it is for.

The ratio is the WCAG contrast ratio, which ranges from 1 (identical) to 21 (black on white). WCAG
2.2 level AA asks for **4.5** for normal body text and **3.0** for large text, icons and the
boundaries of interactive controls.

A pair naming a token that does not resolve used to be skipped in silence — the lookup found
nothing, and the row simply disappeared from the check, so a typo in this table read as a pass. It
now fails the suite ("every documented pair names a token that exists").

| Foreground | Background | Minimum |
| --- | --- | --- |
| `--kui-color-text` | `--kui-color-surface` | 4.5 |
| `--kui-color-text-muted` | `--kui-color-surface` | 4.5 |
| `--kui-color-text` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-text-muted` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-text` | `--kui-color-surface-elevated` | 4.5 |
| `--kui-color-text-muted` | `--kui-color-surface-elevated` | 4.5 |
| `--kui-color-text` | `--kui-color-surface-hover` | 4.5 |
| `--kui-color-text-muted` | `--kui-color-surface-hover` | 4.5 |
| `--kui-color-text` | `--kui-color-surface-overlay` | 4.5 |
| `--kui-color-text-muted` | `--kui-color-surface-overlay` | 4.5 |
| `--kui-color-primary-contrast` | `--kui-color-primary` | 4.5 |
| `--kui-color-primary-container-contrast` | `--kui-color-primary-container` | 4.5 |
| `--kui-color-selected-contrast` | `--kui-color-selected` | 4.5 |
| `--kui-color-accent-container-contrast` | `--kui-color-accent-container` | 4.5 |
| `--kui-color-success` | `--kui-color-success-container` | 4.5 |
| `--kui-color-warning` | `--kui-color-warning-container` | 4.5 |
| `--kui-color-danger` | `--kui-color-danger-container` | 4.5 |
<!-- The per-cluster colour tag (`ClusterColors`) introduces no row of its own: it is a small filled
     square with no text on it, and every one of its six fills is a container colour already checked
     above against its paired text colour. That is why the palette is six semantic tokens rather than
     ten invented values — ten hexes would be ten colours no theme controls and no row here checks. -->
| `--kui-color-primary` | `--kui-color-surface` | 4.5 |
| `--kui-color-accent` | `--kui-color-surface` | 4.5 |
| `--kui-color-success` | `--kui-color-surface` | 4.5 |
| `--kui-color-warning` | `--kui-color-surface` | 4.5 |
| `--kui-color-danger` | `--kui-color-surface` | 4.5 |
| `--kui-color-info` | `--kui-color-surface` | 4.5 |
| `--kui-color-border-strong` | `--kui-color-surface` | 3.0 |
| `--kui-color-focus` | `--kui-color-surface` | 3.0 |
| `--kui-color-primary` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-accent` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-success` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-warning` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-danger` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-info` | `--kui-color-surface-raised` | 4.5 |
| `--kui-color-border-strong` | `--kui-color-surface-raised` | 3.0 |
| `--kui-color-focus` | `--kui-color-surface-raised` | 3.0 |
| `--kui-color-primary` | `--kui-color-surface-elevated` | 4.5 |
| `--kui-color-accent` | `--kui-color-surface-elevated` | 4.5 |
| `--kui-color-success` | `--kui-color-surface-elevated` | 4.5 |
| `--kui-color-warning` | `--kui-color-surface-elevated` | 4.5 |
| `--kui-color-danger` | `--kui-color-surface-elevated` | 4.5 |
| `--kui-color-info` | `--kui-color-surface-elevated` | 4.5 |
| `--kui-color-border-strong` | `--kui-color-surface-elevated` | 3.0 |
| `--kui-color-focus` | `--kui-color-surface-elevated` | 3.0 |

`--kui-color-border` appears in no row. It is the faint rule between table rows and around cards —
decoration that conveys nothing. Anything a user has to perceive in order to operate the interface
uses `--kui-color-border-strong`, which is checked. `--kui-color-state-layer` appears in no row
either: it is a translucent wash composited over whatever is beneath it, so its legibility is a
property of the pair underneath and not of the wash.

### The three adjusted values

Accessibility outranks fidelity. Three of the design's values miss a threshold in this table, and
each has been moved along its own hue by the smallest amount that clears it. The shipped value keeps
its `design-adjusted` marker and records the original beside it.

| Token | Theme | Design value | Ratio as designed | Shipped value | Ratio shipped | Against |
| --- | --- | --- | --- | --- | --- | --- |
| `--kui-color-success` | light | `#2e7d32` | 3.91 | `#2a722e` | 4.51 | `--kui-color-success-container`; it also missed 4.5 against `--kui-color-surface-elevated` at 4.40 |
| `--kui-color-primary-container` (teal) | dark | `#00857a` | 3.69 | `#00756b` | 4.56 | `--kui-color-primary-container-contrast` |
| `--kui-color-primary-container` (green) | dark | `#3e7a22` | 4.44 | `#3d7822` | 4.56 | `--kui-color-primary-container-contrast` |

Every other value in this file is the design's, unchanged.

## Adding a token

1. Add the declaration to `10-tokens.css`. A colour goes in the light block, both dark blocks, and —
   if it is one of the four the accent owns — every seed block too, each with its provenance
   comment. `ContrastSuite` resolves the cascade the way a browser does, so it will notice a block
   you missed.
2. Add the constant to `Tokens.scala`, in its group and in that group's `all`.
3. If it is a colour used as a foreground or a background, add its pair (or pairs) to the table
   above. A pair naming a token that does not exist now fails, so a typo is caught immediately.
4. Run `./mill frontend.uiKernel.test`. `TokensSuite` fails if the stylesheet and `Tokens.scala`
   disagree; `ContrastSuite` fails if a documented pair misses AA in any theme or accent.
