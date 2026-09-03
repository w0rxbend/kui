# ADR-012 — Microfrontend loading: single link with module splitting and dynamic import

- Status: Accepted
- Date: 2026-09-03

## Context

PLAN §21 offers three options: A single bundle with a static registry, B one link with
Scala.js module splitting and lazy `import()`, C separately linked plugins. Fault isolation
in the UI requires that an unavailable feature is never downloaded and never breaks the shell.

## Decision

- **Option B.** One Scala.js link for all KUI-owned features: `ModuleKind.ESModule`,
  `ModuleSplitStyle.SmallModulesFor(List("kui.ui.clusters", "kui.ui.topics", ...))`.
- The shell holds a registry `FeatureId -> () => js.Promise[KuiFeature]` whose thunks are
  `js.dynamicImport(new XFeature())`; the shell never references feature classes statically.
  Loading is triggered when the capability registry reports the feature's capability as
  anything but `NotConfigured` for the current cluster.
- Cross-feature panels (topic page → consumers tab, broker → metrics tab) go through the
  kernel `FeaturePanel` slot keyed by feature id.
- A CI task asserts after `fullLinkJS` that `main.js` stays under a size budget and that one
  module file exists per feature package (guards against accidental static references).
- Option A is the documented fallback (`FewestModules` + eager thunks); Option C is deferred
  to a post-M8 "Plugin SDK" ADR with a Web Component boundary for third-party plugins.
- Dev loop: `fastLinkJS` output served statically with `/api` proxied to the gateway; no Vite
  step unless npm ES modules (CodeMirror, uPlot) are bundled rather than import-mapped.

## Evidence

- `research/scala/frontend-research.md` §4 (Scala.js module docs: `js.dynamicImport` as linker
  split border, class-boundary splitting, ESModule requirement; Mill `ScalaJSModule` settings;
  Airstream 18 `dynamicImport` operators; the closed-world argument against C).
- `research/kafbat/ui-analysis.md` DC-H6 (panels via slot, never direct import).

## Consequences

- Shell → feature coupling is `moduleDeps` at compile time and thunks at runtime.
- One shared Airstream runtime; no duplicated Laminar per feature.
- True third-party frontend plugins wait for the Plugin SDK ADR.

## Alternatives rejected

- Option A as the target: downloads every feature, including those `Unavailable`.
- Option C now: 3–5× bundle duplication, two Airstream runtimes, no type safety across the
  boundary.

## Reversibility

High between A and B (one setting). B → C is additive.
