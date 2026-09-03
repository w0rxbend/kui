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
- Dev loop: `fastLinkJS` output served statically by the gateway itself, so a frontend change
  is `./mill frontend.uiShell.fastLinkJS` plus a browser refresh. No Vite step unless npm ES
  modules (CodeMirror, uPlot) are bundled rather than import-mapped. See amendment 1.

## Amendments

Settled during M0 grooming (G5) and reviewed at the G6 gate on 2026-09-03. Both are
clarifications of Option B, not changes to it.

**Amendment 1 — the dev loop needs no proxy.**

The original wording described serving the linker output from a separate static server with
`/api` proxied through to the gateway, which is how a Vite- or webpack-based frontend is
normally developed. KUI does not need that step. The gateway already serves the production
frontend assets itself (`ARCHITECTURE.md` §12, task GW-008), so pointing it at the
`fastLinkJS` output directory instead of the packaged one gives assets and API the same
origin. With one origin there is nothing to proxy, and no second process to run, no port
mapping to remember, and no CORS configuration that exists only in development and therefore
only breaks in development. Implemented as `./mill dev` and `./mill devWatch` by task
INFRA-003.

**Amendment 2 — route patterns are static; only rendering is dynamically imported.**

A deep link (a bookmarked or pasted URL that lands directly on a feature page) must resolve
before that feature's module has been downloaded — otherwise the very first thing the router
sees is a URL it cannot match, and the user gets a 404 for a page that exists. Therefore each
registry entry carries two things: a small static `List[RoutePattern]` describing which URLs
the feature owns, which the shell links against normally, and the `js.dynamicImport` thunk
that produces the feature and its render functions, which is called only once the route
matches. The static half is data (path shapes), not code, so it costs a few bytes in
`main.js` and does not pull the feature's classes in with it; the bundle-shape check in task
BUILD-006 still fails the build if a real class reference leaks. Implemented by tasks UI-007
and UI-009, documented in `docs/frontend/features.md`.

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
