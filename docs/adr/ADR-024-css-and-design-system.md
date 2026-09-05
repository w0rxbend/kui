# ADR-024 — CSS strategy and design-system implementation

- Status: Accepted; **amended by [ADR-048](ADR-048-solidjs-typescript-vite-frontend.md)** (2026-09-05)
- Date: 2026-09-03

> **Amended by ADR-048 for the assembly mechanism only.** The decision this ADR makes — plain CSS
> with custom properties, no CSS-in-JS, one numbered file per concern, tokens owned in one place —
> is unchanged and was ported rather than redesigned. What changes is who assembles it. The files
> live at `frontend/packages/<name>/styles/NN-*.css` rather than beside Scala sources, the numeric
> prefix still fixes cascade order, and **Vite** emits the stylesheet in place of the Mill `css`
> task, which no longer exists. Class names are written directly in TSX rather than reached through
> a `Css` object of string constants, and the components below are SolidJS components rather than
> Laminar ones. See ADR-048 §6.

## Context

The project needed a choice between plain CSS and ScalaCSS. Kafbat uses styled-components
with a 1 600-line theme file and a three-state dark mode; the Claude Design project is the
visual source of truth and its token extraction has not been delivered yet.

## Decision

- **Plain CSS**, one file per module next to its Scala sources (`src/main/resources/css/*.css`),
  concatenated by a Mill task into `kui.css` in the order tokens → reset → kernel → features.
  No preprocessor; native nesting and custom properties.
- Class naming: BEM-style with a per-module prefix (`kui-topics__row--selected`), referenced
  from Laminar through a `Css` object of string constants per module.
- Design tokens as CSS custom properties (`--kui-color-*`, `--kui-space-*`, `--kui-font-*`,
  `--kui-radius-*`) owned by `frontend/ui-kernel`; values come from the design project's
  token sheet once `research/design/tokens.md` exists, with Kafbat's palette as the interim
  placeholder.
- Dark mode: three-state `auto | light | dark` stored in a `WebStorageVar`, applied as
  `data-theme` on `<html>`, tokens redefined under `:root[data-theme="dark"]` and the
  `prefers-color-scheme` media query.
- Kernel primitives (button, input, select, tag, card, tabs, table shell, dialog, drawer,
  toast, tooltip) are hand-written Laminar components with keyboard handling and ARIA roles.
  A web-component library (Shoelace via `laminar-shoelace`) is **not** adopted in M0; the
  question is re-evaluated after the design import, because the design project defines the
  look and a third-party component set would fight it.
- Strings are centralized in a `Messages` object per feature from day one (no i18n runtime).
- ScalaCSS is rejected.

## Evidence

- `research/scala/frontend-research.md` §6 (ScalaCSS unmaintained since 2022; Kafbat theming
  model; token list; Shoelace/UI5 bindings), ADR-024 candidate; `research/scala/ecosystem-mapping.md` F12.
- The project's visual design reference: the design project is the UI source of truth; no design
  CSS copied.

## Consequences

- Class-name typos are caught by convention and tests, not by the compiler.
- Widget effort lands in the kernel (date/time input, combobox, dialog focus trap).

## Alternatives rejected

- ScalaCSS: four years without a release; runtime style generation.
- styled-components-like CSS-in-Scala: no maintained library; runtime cost.
- Shoelace in M0: premature before the design tokens exist; revisit.

## Reversibility

High for CSS; medium for kernel primitives once features build on them.
