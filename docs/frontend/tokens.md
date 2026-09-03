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
  --kui-color-primary: #4a56d6;
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
| Token count | hundreds of component-scoped entries (`theme.button.primary.hover.background`) | a handful of SCSS variables | ~45 semantic tokens, **no component-scoped tokens** | Per-component theming is why Kafbat's theme file is 1 664 lines and a palette change is a multi-day edit. Semantic tokens mean one edit propagates |
| Where values live | a TypeScript object; changing one needs a rebuild | SCSS; same | CSS custom properties, swappable at run time | Enables the `data-theme` switch, and lets a deployment override a colour with its own stylesheet without rebuilding KUI |
| Contrast | not enforced | not enforced | WCAG AA, enforced by `ContrastSuite` | Neither reference checks it, and several of Kafbat's muted-text pairs fail AA |

Values were seeded from Kafbat's palette because it is the closest visual reference for this product
category. **The set, the naming and the constraints are KUI's own and are not provisional.** If the
Claude Design project is ever imported, task UI-013 reconciles values inside this one file — an
optional improvement, never a gate.

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
came from:

- `seeded: kafbat` — the value verbatim from Kafbat's palette (`frontend/src/theme/theme.ts`).
- `kui` — a value KUI chose, including every value darkened or lightened to pass contrast.

Keep the marker when you change a value. A future reconciliation needs to tell a deliberate KUI
decision from an inherited default, and after the fact there is no other way to know.

## The three-state theme

`ThemeChoice` has three cases, and the third is not a synonym for either of the others:

| Choice | `data-theme` on `<html>` | Which rules win |
| --- | --- | --- |
| `Auto` (default) | attribute removed | Only `@media (prefers-color-scheme: dark)` decides, so the page follows the operating system and changes with it |
| `Light` | `light` | The media query is guarded by `:root:not([data-theme="light"])`, so dark cannot apply even at night |
| `Dark` | `dark` | `:root[data-theme="dark"]` is written last in the file, so it wins even on a system set to light |

`Theme.install()` writes the attribute; the stylesheet does everything else. No colour is computed in
Scala.

The choice is persisted in `localStorage` under `kui.theme`. A browser that refuses storage — Safari
private browsing, an enterprise policy — gets a working switcher that forgets the choice on reload,
never an application that fails to start.

## The token table

### Colour

| Token | Light | Dark | Meaning |
| --- | --- | --- | --- |
| `--kui-color-surface` | `#f9fafa` | `#171a1c` | The page itself |
| `--kui-color-surface-raised` | `#ffffff` | `#22282a` | Anything above the page: cards, dialogs, drawers, menus, sticky headers |
| `--kui-color-border` | `#d5dadd` | `#2f3639` | A separator that carries no meaning. Deliberately faint, deliberately not contrast-checked |
| `--kui-color-border-strong` | `#73848c` | `#8f9ca3` | The outline of an interactive control. Contrast-checked at 3:1 |
| `--kui-color-text` | `#171a1c` | `#f1f2f3` | Body text |
| `--kui-color-text-muted` | `#5c6970` | `#abb5ba` | Timestamps, counts, help text |
| `--kui-color-primary` | `#4a56d6` | `#8f9bff` | Brand: primary buttons, links, the active nav item |
| `--kui-color-primary-contrast` | `#ffffff` | `#0b0d0e` | Text drawn on top of `primary` |
| `--kui-color-success` | `#177a3c` | `#5cd685` | In sync, healthy, completed |
| `--kui-color-warning` | `#8a5a00` | `#ffb84d` | Degraded, lagging, deprecated |
| `--kui-color-danger` | `#cf1717` | `#f5a3a3` | Unavailable, failed, destructive actions |
| `--kui-color-info` | `#2c5bb8` | `#90caf9` | Neutral notices |
| `--kui-color-focus` | `#4a56d6` | `#8f9bff` | The keyboard focus ring |

### Everything else

| Group | Tokens | Notes |
| --- | --- | --- |
| Space | `--kui-space-0` … `--kui-space-8` | 0, 4, 8, 12, 16, 24, 32, 48, 64 px. Every gap, margin and padding is one of these nine |
| Font family | `--kui-font-family-sans`, `--kui-font-family-mono` | Inter with a `system-ui` fallback; monospace matters here because offsets and keys are compared by eye |
| Font size | `--kui-font-size-xs` … `-xl` | 11, 12, 14, 16, 20 px. 14 px body, as in the reference product — right for a dense tool |
| Font weight | `--kui-font-weight-regular/medium/bold` | 400 / 500 / 600 |
| Line height | `--kui-font-line-height-tight/normal` | 1.25 for headings, cells and buttons; 1.5 for prose |
| Radius | `--kui-radius-sm/md/lg/pill` | 2 / 4 / 8 px and fully round |
| Shadow | `--kui-shadow-sm/md` | Two levels only. Five subtly different shadows communicate nothing |
| Stacking | `--kui-z-dropdown/drawer/dialog/toast` | 100 / 200 / 300 / 400. Every `z-index` KUI writes is one of these |
| Duration | `--kui-duration-fast/normal` | 100 ms / 200 ms, both neutralised under `prefers-reduced-motion` |

## Contrast pairs

`ContrastSuite` reads **this table** and checks every row against the values it parses out of the
stylesheet, in both themes. That is deliberate: the pair list is documentation first and test input
second, so a token pair cannot be introduced without someone writing down what it is for.

The ratio is the WCAG contrast ratio, which ranges from 1 (identical) to 21 (black on white). WCAG
2.2 level AA asks for **4.5** for normal body text and **3.0** for large text, icons and the
boundaries of interactive controls.

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

Three values are marked `kui` rather than `seeded: kafbat` precisely because the seeded value failed
one of these rows: `text-muted`, `success` and `warning` were all darkened in the light theme, and
`primary` was darkened so that white text on a primary button clears 4.5.

`--kui-color-border` appears in no row. It is the faint rule between table rows and around cards —
decoration that conveys nothing. Anything a user has to perceive in order to operate the interface
uses `--kui-color-border-strong`, which is checked.

## Adding a token

1. Add the declaration to `10-tokens.css`, in all three blocks if it is a colour (`:root`, the media
   query, and `:root[data-theme="dark"]`), each with its provenance comment.
2. Add the constant to `Tokens.scala`, in its group and in that group's `all`.
3. If it is a colour used as foreground or background, add its pair (or pairs) to the table above.
4. Run `./mill frontend.uiKernel.test`. `TokensSuite` fails if the stylesheet and `Tokens.scala`
   disagree; `ContrastSuite` fails if a documented pair misses AA.
