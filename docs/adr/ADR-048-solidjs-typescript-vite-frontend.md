# ADR-048 — SolidJS 2, TypeScript and Vite replace Scala.js and Laminar in the browser

- Status: Accepted
- Date: 2026-09-05
- Supersedes: **ADR-011** (Laminar/Airstream/Waypoint frontend)
- Amends: **ADR-012** (microfrontend loading), **ADR-018** (frontend test frameworks only),
  **ADR-024** (CSS assembly only), **ADR-025** (frontend facades)
- Amends the product property in `PLAN.md` §1: *"It is Scala from the browser down"* and
  *"no JS/TS application code"*

## Context

`PLAN.md` states as a product property that KUI is Scala from the browser down, and ADR-011
chose Laminar 17.2.1 / Airstream 17.2.1 / Waypoint 9.0.0 on Scala.js to deliver it. ADR-012
chose how feature microfrontends load. Those decisions were taken with reasons, and they have
been executed: `frontend/` today is 185 Scala source files and 106 test files across six
modules — roughly 29 700 lines of application code — and it works.

This ADR reverses the language choice. It does not reverse the architecture: the service
decomposition (ADR-004), the gateway (ADR-004, ADR-037, ADR-039, ADR-040), the capability
model (ADR-032, ADR-039), the streaming envelope (ADR-035) and the error envelope (ADR-034)
are unchanged, and **no backend module changes** except one additive OpenAPI emitter described
in §3.

Reversing a founding property in silence would leave the repository asserting two contradictory
things, so this ADR states the reversal, what it costs, and what pays for it.

### Why this is being reconsidered at all

The property "Scala from the browser down" was never valued for its own sake. It was valued
because of what it bought, and the plan says so: one language for the team, and — the real
prize — **one definition of the wire shared by both halves**. Tapir endpoint values in each
service's `contract` module cross-compile to Scala.js, so the browser and the server compile
against the same `TopicName`, the same `GroupSummaryDto`, the same path. Renaming a field
breaks both halves at compile time.

That guarantee is the thing worth protecting. The language was the means. This ADR keeps the
guarantee and changes the means, and §3 is the whole answer to "how".

## Decision

### 1. The browser is TypeScript, SolidJS 2 and Vite

The `frontend/` tree is rewritten as a pnpm workspace of TypeScript packages built by Vite,
rendered by SolidJS 2. Scala.js is removed from the frontend; it remains available in
`contract` modules for nothing, and those modules stop cross-compiling to JS (§6).

**Exact versions, pinned.** Every one of these was resolved and installed together in a spike
before this ADR was written; the version table is not aspirational.

| Concern | Choice | Version | Notes |
| --- | --- | --- | --- |
| Renderer | `solid-js` | **2.0.0-rc.6** | published under the `next` dist-tag; `latest` is still 1.9.15 |
| DOM renderer | `@solidjs/web` | **2.0.0-rc.6** | in Solid 2 the DOM APIs are a **separate package** |
| Reactive core | `@solidjs/signals` | 2.0.0-rc.6 | transitive; pinned so one copy exists |
| Router | `@solidjs/router` | **2.0.0-next.21** | peers `solid-js@^2.0.0-rc.5` |
| Build | `vite` | **8.2.2** | |
| Solid plugin | `vite-plugin-solid` | **3.0.0-next.27** | the Solid 2 line |
| Language | `typescript` | **5.9.3** | *not* 7.x — see §7 R-4 |
| Types from OpenAPI | `openapi-typescript` | **7.13.0** | peers `typescript@^5.x` |
| Typed fetch client | `openapi-fetch` | **0.17.0** | |
| Unit/DOM tests | `vitest` + `@vitest/browser` | **5.0.0** | |
| Package manager | `pnpm` | **11.25.0** | pinned in `packageManager`; `--frozen-lockfile` in CI |

Versions are pinned exactly (no `^`) in every `package.json`, and `pnpm-lock.yaml` is
committed. An RC is a moving target and a floating range across six packages is how two copies
of the reactive core end up in one bundle — which Solid detects and refuses at runtime.

**Solid 2 is not Solid 1, and every 1.x example is wrong.** The package ships its own
`CHEATSHEET.md` and a `skills/reactivity-diagnostics/SKILL.md`; those are the reference, not
training-data recall. The differences that bite, each verified against the installed rc.6
build rather than taken from prose:

- `solid-js/web` → **`@solidjs/web`**; `solid-js/store` → **`solid-js`** (stores moved into
  core). TypeScript needs `"jsxImportSource": "@solidjs/web"`.
- `createEffect` takes **two** arguments, `(compute, apply)`. The single-argument form is gone.
- `onMount` → **`onSettled`**, which returns its own cleanup.
- Updates are **microtask-batched**: after `setCount(1)`, `count()` still returns the old value
  until a flush. `flush()` applies queued updates synchronously. `batch` no longer exists.
- `createSignal(fn)` and `createStore(fn)` are the derived-state forms; store setters are
  **draft-first** (`set(d => { d.x = 1 })`), and `produce` / `createMutable` are gone.
- `Index` → **`<For keyed={false}>`**; in that form `item` is an **accessor** and the index is a
  plain number, which is the reverse of the keyed form.
- `Suspense` → **`<Loading>`**, `SuspenseList` → **`<Reveal>`**, `ErrorBoundary` → **`<Errored>`**.
- `createResource` is gone: async is an ordinary computation returning a promise.
- `mergeProps` → `merge`, `splitProps` → `omit`, `unwrap` → `snapshot`.
- `classList` is gone; `class` takes the array/object form.
- Props carry **values**, not accessors: `<X v={count()} />`, and the child reads `props.v`.
- Writing to a signal from inside an owned scope **throws** in dev
  (`REACTIVE_WRITE_IN_OWNED_SCOPE`). Writes belong in event handlers or `onSettled`.

The last two, plus the two-argument effect, are the ones that will be got wrong repeatedly by
anyone carrying 1.x or React habits, so they are restated in the DEVPLAN's lane-A task specs
and are the subject of a lint rule (SOL-006).

### 2. State, routing and structure

- **State.** Signals and stores; no external state library. Kernel-owned state (`AuthState`,
  `CapabilityState`, `CurrentCluster`, `NotificationBus`, `Theme`, `PermissionStore`) is a
  module-scope store per concern, exactly as the Laminar kernel owns `Var`s today —
  Solid's own guidance is that app-wide state should be a module-scope store rather than a
  context. Feature-local state stays feature-local. Server state goes through the kernel's
  `QueryCache` (SOL-008), reimplemented on Solid 2 async memos with the same key-prefix
  invalidation contract it has now.
- **Routing.** `@solidjs/router@2.0.0-next.21` is a **configuration-object** router:
  `createRouter({ routes })` returns an instance that *is* the provider component. There is no
  `<Router>`/`<Route>` JSX — every 1.x routing example is wrong here. Crucially the instance
  exposes **`paths`, a typed path proxy**: `Router.paths.clusters(id)["consumer-groups"]()`
  builds a URL through property access and fails to compile on a typo or a missing parameter.
  That is a direct, type-checked replacement for the sealed `Page` ADT of ADR-011, and it keeps
  the project's "never hand-write a path" rule on the UI side too.
- **Package layout**, one Vite/pnpm package per current Mill module, so the module boundary and
  its `moduleDeps` edges survive the port (§4).

### 3. The contract guarantee, and how it is kept

**This is the load-bearing part of the decision.** Losing compile-time contract sharing is the
largest cost of leaving Scala.js, and the only acceptable answer is a mechanism that fails the
**build**, not the browser.

**The source document.** The committed OpenAPI documents are the asset: `docs/api/openapi.json`
(46 paths, 135 schemas, OpenAPI 3.1.0) is emitted by
`kui.gateway.api.openapi.OpenApiDocument` from the gateway's Tapir endpoints, and each service
emits its own beside it. They are regenerated and diff-checked by the build already
(`openApi` / `openApiCheck`), so they cannot drift from the server.

**They cannot be used directly, and finding out why is the reason this section exists.** The
committed aggregate describes the *service-facing* contract. `X-Kui-Principal` is declared a
**required header on 42 of its 46 paths**, and `If-Match` on two more. But ADR-020 makes that
header a signed statement the **gateway** mints, and ADR-040 makes the gateway **strip every
inbound `X-Kui-*` header from browsers at the edge**. Generating a browser client from that
document produces types that oblige every call site to supply an internal trust header the
browser must never send — the type system would be enforcing the exact inverse of the security
boundary. (Observed: `tsc` demanded `header: { "X-Kui-Principal": string }` on a consumer-group
call.)

So:

- The gateway's existing generator gains a second output, **`docs/api/openapi.browser.json`** —
  the *edge view*: the aggregate with every `X-Kui-*` header parameter removed, produced by the
  same projection that `EdgeHeaders.strip` applies at runtime, in the same module, from the
  same list. `X-Csrf-Token` (required on 19 paths) and `If-Match` **stay**, because the browser
  genuinely does send those and the types should force it to.
- It is committed and `--check`ed exactly like the existing documents, so a contract change that
  is not regenerated fails CI.
- One document, one derivation, no hand-maintained copy: the browser view cannot drift from the
  service view because it is computed from it.

**What generates, and when.** `openapi-typescript@7.13.0` turns
`docs/api/openapi.browser.json` into `frontend/packages/api/src/schema.d.ts` — types only, no
runtime. `openapi-fetch@0.17.0` is the runtime: ~6 kB, no codegen, and it type-checks path,
method, path parameters, query, headers, request body and response body against `paths`.
Generation runs as a **Mill task** (`frontend.apiTypes`) that the frontend build depends on, so
`./mill __.compile` regenerates it; a `--check` mode fails if the committed
`schema.d.ts` differs from what the document produces.

**What fails when a contract changes.** Measured, not asserted. Renaming
`GroupSummaryDto.totalLag` to `lagTotal` in the document and regenerating produced:

```
src/api/client.ts(17,12): error TS2339: Property 'totalLag' does not exist on type
'{ groupId: string; state: string; ... lagTotal?: number; }'
tsc exit=2
```

The chain is: server code changes → `openApiCheck` fails until the document is regenerated →
regenerating the document changes `schema.d.ts` → `tsc --noEmit` fails at every browser call
site that used the old shape. Three build gates, no browser involved. A removed endpoint, a
changed path parameter, a narrowed enum and a newly-required header all fail the same way.

**What this is weaker than, honestly.** It is a *generated* shared definition rather than a
*shared* one, and that difference is real:

- It is only as good as the document. A Tapir endpoint whose schema is wrong produces a wrong
  client that compiles. Under ADR-011 the same bug also produced a wrong client that compiled,
  so this is not a regression — but it is not an improvement either, and the **seam suites
  (recorded documents) remain the thing that catches it**. They are more important now, not
  less, and the DEVPLAN gives them their own lane (SOL-036).
- The loop is longer: a Scala change is only visible to the browser after regeneration. Mill
  ordering makes that automatic in CI and in `./mill dev`, but a developer editing Scala with a
  Vite dev server already running sees the old types until the task reruns.
- Smart constructors do not survive. `TopicName` in Scala refuses an invalid name at the type
  level; `string` in TypeScript does not. Branded types are declared in `@kui/api` for the
  identifier set, and validation at the edge of the browser is a kernel concern (SOL-004), but
  it is a convention backed by a lint rule, not a compiler guarantee. **This is a genuine loss
  and it is not fully mitigated.**

### 4. Feature modules: how they load, and how it stays enforced

ADR-012 chose "one link, module splitting, dynamic import", with a build check that fails if a
feature's code leaks into the main bundle. The mechanism changes; **the property and its
enforcement do not**.

- Each feature is a workspace package (`@kui/feature-topics`, …) reached **only** through
  `lazy(() => import("@kui/feature-topics"))` in the shell's feature registry. The shell never
  imports a feature statically. Vite/Rollup gives each dynamic import its own chunk.
- **ADR-012 Amendment 2 survives unchanged and matters more here.** Route *patterns* are static
  data the shell links against normally, so a deep link resolves before the feature's chunk has
  been downloaded; only the rendering is dynamically imported. With this router the route tree
  is a plain configuration array, which makes the split natural: `path` is data, `component` is
  `lazy(...)`.
- **Enforcement.** `vite build --manifest` emits `dist/.vite/manifest.json`, which states for
  the entry chunk exactly which modules are static `imports` and which are `dynamicImports`. A
  build test (SOL-010) asserts, for every registered feature: it appears as a chunk with
  `isDynamicEntry: true`; it is listed in the entry's `dynamicImports`; and it is **not** in
  the transitive closure of the entry's static `imports`. Plus a byte budget on the entry chunk.

  This replaces BUILD-006's `fullLinkJS` module-file check and is at least as strong, because
  it reads the module graph rather than the file listing. Observed on the spike: with a feature
  behind `lazy()`, the manifest listed `src/features/consumers.tsx` under the entry's
  `dynamicImports` and gave it its own 250-byte chunk, while `imports` held only the shared
  runtime.

  **A grep would not have worked.** The main chunk *does* contain the string `consumers` — the
  route path and the import specifier — so a text search for a feature's name in `main.js`
  reports a leak that is not there. The check must read the manifest's module graph, and the
  task spec says so.
- `preloadLinks` (on by default) prefetches a route's chunk on link hover/focus, which is a
  free improvement over today's load-on-navigate.
- The **bounded-time load with a retry** (`LazyFeature` today) is preserved verbatim in
  behaviour: a chunk request that hangs must fail visibly within a timeout and offer a retry,
  because a dynamic import is an HTTP request made minutes after page load and it can stall
  without ever settling. That is a shipped defect, not a hypothesis, and SOL-024 reimplements
  the state machine (`NotLoaded → Loading → Loaded | Failed`) with the same attempt-generation
  guard so a late arrival cannot overwrite a failure the user is looking at.
- Option C (separately built third-party plugins) stays deferred, as ADR-012 left it.

### 5. The design system moves nearly intact

The stylesheets are framework-agnostic and are **the largest asset carried across unchanged**.
16 files, 4 901 lines, built on semantic design tokens with two themes, four accent palettes
and a density switch. They contain no Laminar, no Scala, and no class names that depend on
either.

**Moved byte-for-byte** (only their directory changes):

| From | To |
| --- | --- |
| `frontend/ui-kernel/resources/css/00-reset.css` | `frontend/packages/kernel/styles/00-reset.css` |
| `frontend/ui-kernel/resources/css/10-tokens.css` | `frontend/packages/kernel/styles/10-tokens.css` |
| `frontend/ui-kernel/resources/css/20-kernel-controls.css` | `frontend/packages/kernel/styles/20-kernel-controls.css` |
| `frontend/ui-kernel/resources/css/21-kernel-overlays.css` | `frontend/packages/kernel/styles/21-kernel-overlays.css` |
| `frontend/ui-kernel/resources/css/22-kernel-table.css` | `frontend/packages/kernel/styles/22-kernel-table.css` |
| `frontend/ui-kernel/resources/css/23-kernel-data.css` | `frontend/packages/kernel/styles/23-kernel-data.css` |
| `frontend/ui-kernel/resources/css/25-virtualized-table.css` | `frontend/packages/kernel/styles/25-virtualized-table.css` |
| `frontend/ui-kernel/resources/css/26-list-controls.css` | `frontend/packages/kernel/styles/26-list-controls.css` |
| `frontend/ui-shell/resources/css/30-shell.css` | `frontend/packages/shell/styles/30-shell.css` |
| `frontend/ui-shell/resources/css/31-shell-nav.css` | `frontend/packages/shell/styles/31-shell-nav.css` |
| `frontend/ui-shell/resources/css/32-shell-dashboard.css` | `frontend/packages/shell/styles/32-shell-dashboard.css` |
| `frontend/ui-clusters/resources/css/40-clusters.css` | `frontend/packages/feature-clusters/styles/40-clusters.css` |
| `frontend/ui-clusters/resources/css/41-clusters-brokers.css` | `frontend/packages/feature-clusters/styles/41-clusters-brokers.css` |
| `frontend/ui-topics/resources/css/50-topics.css` | `frontend/packages/feature-topics/styles/50-topics.css` |
| `frontend/ui-messages/resources/css/60-messages.css` | `frontend/packages/feature-messages/styles/60-messages.css` |
| `frontend/ui-consumers/resources/css/70-consumers.css` | `frontend/packages/feature-consumers/styles/70-consumers.css` |

A task that changes one of these files fails review unless the change is described and
justified; the port is a move, and `git log --follow` should show it as one.

**What changes around them.** ADR-024's cascade order — tokens, reset, kernel, features — is
kept, but its *mechanism* changes. `kui.build.CssPipeline` (the Mill task that discovered and
concatenated the files by role) is replaced by one `frontend/packages/kernel/styles/index.css`
that `@import`s the sixteen files in that exact order; Vite inlines the imports at build time
into a single stylesheet. The order becomes an explicit, readable list instead of a sorting
rule.

That trade loses one thing worth naming: `CssPipeline` **discovered** files, so a new
stylesheet could not be silently left out of the bundle. An explicit list can be forgotten. So
SOL-011 adds a build test asserting every `styles/*.css` file in the workspace is referenced by
`index.css` exactly once — the discovery property, kept as a check.

**The contrast test survives as Scala, and does not move.** `ContrastSuite` imports nothing but
`munit`: it parses the token stylesheet, resolves the cascade the way a browser does, and
checks WCAG contrast for every documented pair across eight theme-and-accent combinations,
reading its pair list out of `docs/frontend/tokens.md` so the documentation is executable.
Rewriting it in TypeScript would risk a proven accessibility gate for no gain. It is **kept in
Scala and moved from a Scala.js test module to a JVM build test** that reads the CSS and the
Markdown off disk — which is simpler than today, where both files must be compiled into a
Scala.js test binary because Scala.js has no filesystem. `Tokens.scala` (a dependency-free list
of token names) moves with it. This is the one piece of Scala that keeps describing the browser,
and it is worth the seam.

**The components that consume the CSS are rewritten.** All 31 kernel components, the shell's
layout and navigation, and every feature screen are new code in TypeScript. The CSS class names
they emit are unchanged, which is what makes the stylesheets portable and what lets the E2E
suite's selectors keep working across the cutover.

### 6. Build integration: `./mill` still builds the product

Non-negotiable: `./mill __.compile` and the packaging tasks build the whole product, and the
gateway serves the bundle from its own resources on one origin (ADR-011's static-serving rule
and ADR-012 Amendment 1 both survive).

- A `frontend` Mill module keeps its name and its public task surface. `frontend.bundle` still
  produces the `web/` directory the gateway's `resources` picks up; it now runs
  `pnpm install --frozen-lockfile` and `pnpm build` (Vite) in a Mill task with the workspace as
  input and `dist/` as output, instead of invoking the Scala.js linker. Downstream —
  `StaticRoutes`, `IndexHtml`, the `#kui-bootstrap` element, the ETag/SPA-fallback behaviour —
  is untouched. `IndexHtml`'s two markers stay exactly as they are; Vite's generated
  `index.html` carries them.
- **Node and pnpm are build inputs.** Their versions are pinned in `.tool-versions` and checked
  by the Mill task with a clear error, the way the Playwright browser version is pinned today.
  This adds a toolchain requirement to a build that previously needed only a JVM. That is a real
  cost (§7 R-1) and the offline story is §7 R-2.
- `./mill dev` keeps working and keeps its one-origin property: the gateway serves Vite's
  output directory. `./mill devWatch` runs Vite in watch mode. ADR-012 Amendment 1's reasoning —
  no proxy, no second port, no dev-only CORS — is preserved; a Vite dev server with an
  `/api` proxy is explicitly **not** adopted, because a CORS configuration that exists only in
  development is a configuration that only breaks in development.
- `contract` modules **stop cross-compiling to Scala.js**. Their `.js` variants and every
  `frontend.* → services.*.contract.js` edge are deleted, which removes the JS half of five
  cross-built modules from the build graph. `ArchitectureRules` loses the rules that policed
  those edges and gains the manifest-based bundle-shape rule instead.

### 7. Testing

ADR-018 is amended **for frontend unit and DOM tests only**. Everything else it decides — MUnit
everywhere on the JVM, Testcontainers, the seam suites, and **JVM Playwright for E2E** — is
unchanged. The E2E suite is the cutover's safety net precisely because it is written against
the rendered DOM and the HTTP surface rather than against Laminar, so it should pass against
both implementations; where it does not, that is a finding.

- **Vitest 5.0.0** for unit tests, **`@vitest/browser` 5.0.0** (Playwright chromium provider) for
  DOM and accessibility tests, replacing MUnit-under-Node and `JsEnvConfig.JsDom`.
- Two traps found while spiking the harness, both recorded here because each produces a test
  suite that passes while testing nothing:
  1. Under `environment: "node"`, Vitest resolves Solid's **server** build; effects never run
     and effect assertions fail or vacuously pass. Frontend tests must run against the browser
     build (`environment: "jsdom"` or browser mode).
  2. The obvious idiom — build the graph inside `createRoot` and write to it there — **throws**
     `REACTIVE_WRITE_IN_OWNED_SCOPE` in dev builds. The correct idiom builds inside the root,
     hoists the setters out, and writes outside it. The kernel ships that harness (SOL-035) so
     it is written once.
- `flush()` before asserting on any signal, in every test. This follows from microtask batching
  and is the single most common way a Solid 2 test is written wrongly.
- `@solidjs/testing-library` is **not** adopted on faith: its `next` line (1.0.0-beta.3) declares
  `solid-js >= 1.0.0`, a range loose enough to be meaningless about Solid 2. SOL-035 spikes it;
  if it does not hold up, the kernel's own harness over `render` from `@solidjs/web` is
  sufficient and is the default.

### 8. Conventions

- TypeScript `strict`, plus `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`. No
  `any` without a comment justifying it; enforced by lint.
- **No runtime fetch from a third party.** KUI runs in private networks: fonts, icons and
  libraries are bundled, there is no CDN, no Google Fonts, no telemetry beacon. Enforced by a
  build test that scans the built assets for absolute external URLs.
- **Accessibility is not renegotiated.** The project has repeatedly shipped controls that were
  correct for a screen reader and invisible to everyone else, and the reverse must not happen
  now. Every kernel primitive keeps its keyboard handling, focus management and ARIA roles, and
  each ports with the DOM test that proves it (§ DEVPLAN lane B).
- Strings stay centralised per feature (`Messages`), as ADR-024 has it.

### 9. 64-bit numbers — correcting a premise

The migration brief given to this work asserted that "numbers above 2^53 arrive as strings".
**That is not true of this system, and building the client around it would have been wrong.**

`KernelCodecs` states the actual rule and the reasoning: a 64-bit identifier (`Offset`,
`ByteSize`) encodes as a **JSON number**, with the documented limit that `JSON.parse`
reproduces integers exactly only to 2^53 − 1; Kafka offsets and byte counts do not reach that
magnitude, so KUI does not pay the cost of stringifying every offset, and `KernelCodecsSuite`
asserts the boundary on both platforms so the limit stays a known one. The committed document
agrees: 42 `format: int64` fields, all `type: integer`, and **no** string-encoded numeric field
anywhere in the aggregate.

Consequently the generated types map `int64` to `number`, which is correct, and the frontend
does **not** introduce `bigint` handling or string parsing for these fields. If a genuinely
large field ever appears, the change is: encode it as a string in the contract, which changes
the document, which changes the generated type from `number` to `string`, which fails the
browser build at every use site — the §3 mechanism working as intended. The DEVPLAN keeps a
JVM-side test asserting no `format: int64` field carries a value beyond the safe range in the
E2E fixtures (SOL-036).

## Evidence

All of the following was observed in this session, in a spike at
`scratchpad/spike`, against the real packages and the repository's own committed document.

1. **Version resolution.** `solid-js` `next` = `2.0.0-rc.6` (`latest` = 1.9.15);
   `@solidjs/router` `next` = `2.0.0-next.21`, peering `solid-js@^2.0.0-rc.5`;
   `vite-plugin-solid` `next` = `3.0.0-next.27`. The full set installs together with
   `found 0 vulnerabilities`.
2. **The Solid 2 API surface, checked against the build rather than the docs.** Every API this
   ADR relies on is exported by rc.6 — `onSettled`, `flush`, `createStore`, `createProjection`,
   `snapshot`, `reconcile`, `merge`, `omit`, `isPending`, `latest`, `refresh`, `affects`,
   `Loading`, `Errored`, `Reveal`, `Repeat`, `For` — and every 1.x API it replaces is **absent**:
   `onMount`, `batch`, `createResource`, `Index`, `Suspense`, `SuspenseList`, `ErrorBoundary`,
   `produce`, `createMutable`, `mergeProps`, `splitProps`, `unwrap`, `on`, `createComputed`,
   `startTransition`, `createSelector`. `createEffect.length === 3`.
3. **`solid-js/web` and `solid-js/store` are not resolvable subpaths in 2.0** — the package's
   `exports` map has only `.` and `./refresh`. This is why `@solidjs/web` is a separate
   dependency and stores import from core.
4. **Semantics, verified by a passing test run** (`vitest run`, 3 passed): a signal read returns
   the previous value after `setCount(1)` and the new one after `flush()`; a draft-first store
   setter applies nested mutation and array push; `createEffect(compute, apply)` runs `apply`
   for each committed value.
5. **The contract guarantee.** `openapi-typescript` generated 4 255 lines of types from the real
   `docs/api/openapi.json` (46 paths, 135 schemas) in 99 ms; a client compiled clean against it;
   renaming one field in the document and regenerating produced `TS2339` and `tsc exit=2`.
6. **The internal-header problem.** `X-Kui-Principal` is `required: true` on 42 of 46 paths in
   the aggregate and on every per-service document; `tsc` demanded it at a browser call site.
   ADR-040 §1 says the gateway strips every inbound `x-kui-*`. Hence §3's browser projection.
7. **`required` fidelity.** 116 of 135 schemas declare `required`; `GroupSummaryDto` requires 8
   of its 12 properties and leaves `totalLag` optional, matching its documented "null when it
   could not be computed". The generator reproduces optionality faithfully.
8. **Code splitting and its enforcement surface.** `vite build` put a `lazy()` feature in its own
   250-byte chunk; `dist/.vite/manifest.json` listed it under the entry's `dynamicImports` with
   `isDynamicEntry: true` and not under `imports`. The router's `serverForms` and Solid's
   `decode` are also dynamic, so they cost nothing in the entry chunk.
9. **The typed path proxy compiles.** `Router.paths.clusters("local")["consumer-groups"]()`
   type-checks; the string-literal form does not, and the compiler error names the available
   segments.
10. **Toolchain constraint.** `typescript@latest` is now `7.0.2`; `openapi-typescript@7.13.0`
    peers `typescript@^5.x` and the install fails with `ERESOLVE` against TS 7. Hence the 5.9.3
    pin.
11. **The design system's portability.** 16 CSS files, 4 901 lines, no framework coupling.
    `ContrastSuite` imports only `munit`; `Tokens.scala` imports nothing.

## Consequences

### What is gained

- **A far larger ecosystem for the browser problems this product actually has.** Virtualized
  tables, code editors, charts and date pickers are all hand-written in the kernel today because
  ADR-011 left no alternative (ADR-025 records exactly that consequence). They no longer have to
  be, and the effort saved is real: M3's message browser and M8's metrics are the two places it
  compounds.
- **A much shorter feedback loop.** Vite's HMR replaces a Scala.js link; the spike's production
  build of a small app took **82 ms**. Frontend iteration stops being the slowest part of the
  build.
- **A smaller shipped bundle**, though the DEVPLAN treats the number as unproven until SOL-042
  measures both.
- **Hiring and contribution.** The frontend is now approachable to people who do not write
  Scala, which for a public open-source Kafka UI is the difference between contributors and
  spectators.
- **Type-safe route URLs**, which ADR-011 achieved with a hand-maintained `Page` ADT and
  Waypoint route list, now come from the router itself.
- **Async as a first-class citizen of the reactive graph**, which suits a UI whose every screen
  is a partial aggregation with per-section status.

### What is lost

Stated plainly, because a reader in a year needs to know what was traded.

1. **Compile-time contract sharing becomes compile-time contract *generation*.** §3 keeps the
   build-failure property, but the browser now compiles against a *derivation* of the server's
   truth rather than the truth. The gap is the document's own fidelity, and the seam suites are
   what close it.
2. **Smart constructors do not cross.** `TopicName`, `Offset`, `ClusterId` and the rest are
   compile-time-validated in Scala and plain `string`/`number` in the browser. Branded types and
   edge validation narrow this; they do not eliminate it.
3. **One language becomes two.** Every contributor to a full-stack change now context-switches,
   and the "one effect system, one language" simplicity in `PLAN.md` is weakened.
4. **~29 700 lines of working, tested Scala are replaced.** Not deleted for being bad — replaced.
   Every behaviour in §"behaviour to preserve" was learned from a shipped defect, and the
   DEVPLAN's job is to carry those behaviours over rather than rediscover them.
5. **A new toolchain in the build.** Node, pnpm and a lockfile, with an offline story that must
   be maintained (§ R-2).
6. **A release-candidate dependency at the centre of the browser.** §R-3.
7. **`ArchitectureRules`' frontend rules and `CssPipeline` are retired**, and their properties
   have to be re-established by different checks (SOL-010, SOL-011). Until those land, the
   frontend is less policed than it is today.

### Neutral

- No backend module changes except the additive browser-projection emitter in the gateway's
  `api` module. Ports, use cases, domains and contracts are untouched on the JVM.
- The E2E suite, `StaticRoutes`, `IndexHtml`, the bootstrap element and the one-origin
  deployment model all survive unchanged.

## Alternatives rejected

- **Stay on Laminar.** The honest default, and it keeps the strongest version of the contract
  guarantee. Rejected for the ecosystem and contribution reasons above, not for any fault in
  the current implementation, which works.
- **Solid 1.9.x instead of the 2.0 RC.** Stable, and every example on the internet applies.
  Rejected because the migration is a rewrite either way, and doing it onto a line that is
  months from replacement would mean paying the 1.x → 2.x cost a second time on a much larger
  codebase. §R-3 is the price.
- **React, Vue or Svelte.** React has the deepest ecosystem and is what both reference products
  use. Rejected because Solid's fine-grained reactivity is the closest available model to
  Airstream, which makes the port a translation rather than a redesign — the kernel's
  `QueryCache`, `CapabilityStore` and streaming code map almost one-to-one — and because a
  virtual DOM is a poor fit for a virtualized table streaming thousands of Kafka records.
- **Hand-written TypeScript interfaces mirroring the server.** Rejected outright: this is the
  failure mode the whole ADR exists to avoid, and it converts a build failure into a runtime
  one.
- **A richer generator** (`orval` 8.28.1, `@hey-api/openapi-ts` 0.99.0) that emits a client per
  operation with hooks. Rejected as more generated surface to review and a heavier dependency,
  for a benefit `openapi-fetch` already delivers in 6 kB. Reconsider if the hand-written
  `ApiClient` wrapper grows past its purpose.
- **`npm` or `yarn`.** Rejected in favour of pnpm's strict `node_modules`, which forbids a
  package importing a dependency it does not declare. That is the closest available equivalent
  to the `moduleDeps` discipline the Mill build enforces today, and losing it would let feature
  packages quietly couple.
- **SolidStart.** Rejected: it is a full-stack meta-framework with SSR and server functions, and
  KUI's server is Scala. The browser build is a single-page application served from the
  gateway's resources, which is a Vite job.
- **A Vite dev server proxying `/api`.** Rejected for ADR-012 Amendment 1's reasoning, which is
  unchanged: one origin, one process, no dev-only CORS.

## Risks

| ID | Risk | Mitigation |
| --- | --- | --- |
| R-1 | The build now needs Node and pnpm; a JVM-only contributor cannot build the product | Versions pinned in `.tool-versions` and checked by the Mill task with an actionable message; documented in `README` and `docs/operations/`. The Playwright browser pin is the precedent |
| R-2 | Air-gapped and offline builds break on `pnpm install` | `pnpm-lock.yaml` committed; CI uses `--frozen-lockfile` with a warmed store; the release build is reproducible from the lockfile and a populated store, and `docs/operations/` documents the mirror |
| R-3 | `solid-js` 2.0.0-rc.6 is a release candidate; the API can still move | Exact pins across every package; the RC's own `CHEATSHEET.md` is the reference; SOL-001 records observed deviations from the documentation in `docs/frontend/solid-2-notes.md`; the API surface is re-probed on every version bump by the check in SOL-001 |
| R-4 | TypeScript 7 is `latest`, the generator peers `^5.x`, and a careless upgrade breaks the install | TS pinned to 5.9.3 with a comment naming the constraint; renovate/dependabot upgrades of TS are blocked until `openapi-typescript` supports 7 |
| R-5 | The browser projection is written by hand and drifts from `EdgeHeaders.strip` | It is computed from the same header list in the same module, committed, and `--check`ed; a test asserts no `x-kui-*` parameter survives into the browser document |
| R-6 | Behaviour learned from shipped defects is quietly dropped in the rewrite | Each is an explicit exit criterion with a named test in the DEVPLAN, and the JVM E2E suite runs against both implementations during cutover |
| R-7 | Two copies of the reactive core in one bundle (Solid's classic failure) | Exact pins, a single `solid-js` in the workspace root, pnpm's strict resolution, and a build test asserting one copy in the manifest |

## Reversibility

**Low.** This is a rewrite of 29 700 lines of application code, and reverting means reverting to
the Scala.js tree wholesale. The mitigation is not reversibility but sequencing: the cutover
(DEVPLAN lane F) keeps the Scala.js frontend building and serving until the TypeScript one
passes the same E2E suite, so there is a single, reviewable switch-over commit and a working
product on both sides of it.

The pieces *within* the decision are more reversible: the generator can be swapped
(`openapi-fetch` → `orval`) behind the kernel's `ApiClient`; the router is confined to the
shell; Vitest can be replaced without touching application code. Leaving Solid is not
reversible in the same sense, which is the same shape of commitment ADR-011 recorded for
Laminar.
