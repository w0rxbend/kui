# The KUI frontend

Everything that runs in a browser. It is Scala compiled to JavaScript by
[Scala.js](https://www.scala-js.org/), with [Laminar](https://laminar.dev/) for the user interface
and plain CSS for the styling. There is no React, no TypeScript and no npm build step (ADR-011,
ADR-012, ADR-024).

If you have written a React application, the two ideas that will feel unfamiliar are worth stating
up front:

- **There is no virtual DOM and no re-render.** A Laminar component builds real DOM nodes once. What
  changes afterwards is not the tree, it is the values flowing through the bindings attached to it.
- **State is explicit and owned.** A `Var[A]` is a value that can be written; a `Signal[A]` is a
  value that changes over time and always has a current value; an `EventStream[A]` is a series of
  events with no current value. A component takes them from its caller and never creates global
  state of its own.

## Module layout

```
frontend/
  ui-kernel/            the design system and the shell's plumbing
    src/kui/ui/kernel/
      component/        the primitives: button, input, dialog, table, …
      css/              KernelCss — the class names, as Scala constants
      feature/          KuiFeature, FeatureRegistry, LazyFeature, FeaturePanel
      theme/            Theme (light / dark / follow the system) and Tokens
    resources/css/      this module's stylesheets
    test/src/…          MUnit suites, run under jsdom
```

Later milestones add sibling modules — `ui-clusters`, `ui-topics`, `ui-messages` — one per feature,
plus `ui-shell`, which is the application entry point. Each is a *microfrontend*: it is compiled into
its own JavaScript module and downloaded only when the user actually needs it (ADR-012). See
[`features.md`](features.md) for how to add one.

Note that the Mill module is spelled `frontend.uiKernel` on the command line and `frontend/ui-kernel`
on disk. Mill names a module's directory after the Scala object; `build.mill` overrides that so the
directory can stay kebab-case like every other directory in the repository.

## CSS

### One file per module, concatenated in a fixed order

Each module keeps its stylesheets in `resources/css/*.css`. The Mill task `frontend.css` pastes them
all into a single `kui.css` in the order **tokens → reset → kernel → features** (ADR-024). Plain CSS
has no import graph: when two rules match the same element with equal specificity, the one written
later wins, so the concatenation order *is* the cascade and it is decided once, in
`kui.build.CssPipeline`, rather than by whatever order the filesystem returns.

```bash
./mill show frontend.css        # prints the path to the assembled kui.css
```

Note that the numeric prefixes in file names (`00-reset.css`, `10-tokens.css`) group related files
in a directory listing; they do **not** decide the cascade. A feature file called `00-anything.css`
still lands after every kernel file.

There is no preprocessor. Native CSS nesting and custom properties cover what Sass was for, and
ScalaCSS was rejected outright: its last release was in 2022 and it generates styles at run time.

### Class names live in `Css` objects, never in Scala string literals

```scala
// yes
div(cls := KernelCss.Card, …)

// no — a typo here is silent, and nothing will ever tell you
div(cls := "kui-crad", …)
```

Every module has one object of class-name constants (`KernelCss`, and one per feature). Naming is
BEM with a `kui` prefix — `kui-<block>__<element>--<modifier>` — which keeps every selector to a
single class, so specificity is uniform and the cascade stays predictable.

### Colours come from tokens, always

No component may contain a colour, a spacing value or a font size of its own. They come from the
design tokens in [`tokens.md`](tokens.md), as `var(--kui-color-…)`. That is what makes dark mode a
property of one file instead of a property of every component.

### Degraded rendering: no component may *need* its CSS

If `kui.css` fails to load — a broken deploy, a proxy that drops it — the application must still be
usable, not merely present. Concretely:

- a control's *function* comes from the HTML: a button is a `<button>`, a disabled control carries
  the `disabled` attribute, a modal carries `role="dialog"`;
- CSS supplies appearance only. Nothing may be hidden, revealed, enabled or disabled by a class
  alone.

Tabs are the usual place this rule gets broken: an implementation that renders every panel and hides
the inactive ones with `display: none` shows all of them at once when the stylesheet is missing. KUI
renders only the selected panel, so the failure mode is "unstyled but correct".

## Tests

Frontend suites are MUnit, run under jsdom (a `document` implemented in JavaScript) rather than a
real browser. jsdom is fast and good enough for structure, attributes and events; it is not a
browser, and it approximates layout, so nothing here may assert geometry.

```bash
export PATH="$HOME/.nvm/versions/node/<version>/bin:$PATH"
npm install --no-save jsdom          # once per checkout, into the repository root
./mill frontend.uiKernel.test
```

See [`../development/toolchain.md`](../development/toolchain.md) for why jsdom has to live in a
`node_modules` at the repository root.

Run Scala.js test tasks on their own rather than folding them into `./mill __.test`: a Scala.js test
module and a JVM test module in one Mill invocation currently fail together (blocker B-003).

## Further reading

- [`tokens.md`](tokens.md) — the design tokens, the theme model and the contrast rules.
- [`components.md`](components.md) — the primitive catalogue, with each component's API and its
  accessibility contract.
- [`features.md`](features.md) — how to add a microfrontend.
