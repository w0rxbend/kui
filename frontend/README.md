# The KUI browser interface

This directory holds two frontends at once, on purpose, and will until the cutover (ADR-048,
`docs/plans/SOLID/DEVPLAN.md` decision D12):

- `ui-kernel`, `ui-shell`, `ui-clusters`, `ui-topics`, `ui-messages`, `ui-consumers` — the Scala.js
  and Laminar implementation that is **currently shipped**. Built by `./mill frontend.bundle`.
- `packages/` — the TypeScript, SolidJS 2 and Vite implementation that replaces it. Built by
  `./mill frontend.solid.bundle`.

Nothing is deleted until the TypeScript one is proven (SOL-041, then SOL-043). Until then the
gateway still serves the Scala.js build; every task below builds the new one alongside it.

## What you need installed

A JVM was the only requirement before ADR-048. The browser build now also needs **Node** and
**pnpm**, pinned in `/.tool-versions`:

```
nodejs 22.12.0     # a minimum; newer is fine
pnpm   11.25.0     # exact; frontend/package.json pins the same number in `packageManager`
```

`./mill frontend.solid.toolchain` checks both and tells you what to install if either is missing.
Everything that is not the browser still builds without them.

## The packages

One package per Mill module, so the boundaries that `moduleDeps` used to enforce survive the port.
pnpm's strict `node_modules` is what enforces them now: a package can only import what its own
`package.json` declares.

| Package | Replaces | Holds |
| --- | --- | --- |
| `@kui/api` | the cross-compiled Tapir contracts | types **generated** from `docs/api/openapi.browser.json`, and the typed client |
| `@kui/kernel` | `ui-kernel` | design system, API client, query cache, SSE, capability and permission stores |
| `@kui/shell` | `ui-shell` | router, layout, navigation, feature registry, error pages |
| `@kui/feature-clusters` … `-consumers` | `ui-clusters` … `ui-consumers` | one feature microfrontend each |

Two rules about those edges, both of which are checked by the build rather than by review:

1. **The shell declares the feature packages as dependencies but never imports one statically.**
   The declaration is what lets `import("@kui/feature-topics")` resolve at all; the *dynamic* import
   is what gives the feature its own chunk. `./mill frontend.checkBundleShape` (SOL-010) reads
   `dist/.vite/manifest.json` and fails if a feature's code reaches the entry chunk — it reads the
   module graph, not the chunk text, because the entry legitimately contains a feature's *name*
   (its route pattern and its import specifier) and a text search would be wrong in both directions.
2. **No feature package depends on another feature package.** Checked over these same
   `package.json` files by SOL-045, replacing the `ArchitectureRules` edges that policed the Mill
   modules.

## Building

Everything goes through Mill, so that one command still builds the whole product:

```
./mill frontend.solid.toolchain    # is Node/pnpm present and new enough?
./mill frontend.solid.install      # pnpm install --frozen-lockfile
./mill frontend.solid.dist         # vite build, into the task's own output directory
./mill frontend.solid.bundle       # the same output laid out as the `web/` resource root the gateway serves
./mill frontend.solid.typecheck    # tsc --build, strict
./mill frontend.solid.test         # vitest
```

You can also run pnpm directly in this directory (`pnpm build`, `pnpm typecheck`) while iterating;
the Mill tasks run exactly the same commands, and only they are wired into the packaging.

## The development loop

The gateway is the only server, in development as in production. There is **no Vite dev server and
no proxy**: assets and API share an origin, which is ADR-012 Amendment 1 — a CORS configuration
that exists only in development is a configuration that only breaks in development.

Two terminals, because Mill serialises invocations on its output directory and a foreground
`./mill dev` would hold the lock the watch needs:

```
# terminal 1 — the whole product, in the background, serving frontend/dist
KUI_FRONTEND=solid ./mill devStart

# terminal 2 — rebuild the bundle on every save
./mill frontend.solid.devWatch

# then open http://localhost:8080/ui/ and refresh after a rebuild
./mill devStop
```

`KUI_FRONTEND` selects which frontend the development server puts on its classpath; without it you
get the Scala.js one exactly as before. The variable and the branch behind it disappear at cutover.

How it works: `out/dev-assets/solid/web` is a symbolic link to `frontend/dist`, placed in front of
the gateway's own resources. A classloader takes the first hit, so the `index.html` Vite generated
shadows the committed one and `/ui/assets/…` resolves to whatever the last rebuild wrote. Nothing is
copied, nothing is restarted.

One development-only wart: `frontend/dist/.vite/manifest.json` is reachable at
`/ui/.vite/manifest.json` while the link is in place. The packaged bundle leaves it behind — see
`frontend.solid.bundle` — so it never reaches a deployment.

## The base path

KUI can be mounted at the root or behind a reverse proxy at, say, `/kui`, and **one built bundle has
to work in both**. Three pieces make that true, and none of them may be "simplified" away:

- `vite.config.ts` sets `base: "./"`, so every asset URL in the built `index.html` is relative;
- `index.html` keeps the two markers `<!--KUI_BASE_HREF-->` and `<!--KUI_BOOTSTRAP-->`, which
  `kui.gateway.api.static.IndexHtml` replaces at request time with `<base href="<basePath>/ui/">`
  and a `<script id="kui-bootstrap" type="application/json">` block;
- `@kui/kernel`'s `readBootstrap()` reads that block, and the shell hands `<basePath>/ui` to the
  router as its base.

The `<base href>` is also what makes a deep link work: `/ui/clusters/orders` is answered with
`index.html` by the single-page fallback, and `./assets/index-abc.js` still resolves to
`/ui/assets/index-abc.js` rather than to `/ui/clusters/assets/…`.
