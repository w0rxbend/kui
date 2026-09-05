# The KUI browser interface

TypeScript, [SolidJS 2](https://www.solidjs.com/) and [Vite](https://vite.dev/), in a pnpm
workspace (ADR-048). It replaced the Scala.js and Laminar implementation on 2026-09-05; nothing of
that build remains here.

**This is its own build.** Mill does not build it, the gateway's jar does not contain it, and it
reaches the backend over HTTP like any other client. Two things follow, and both are the point: the
backend builds and tests with nothing but a JDK, and this can be rebuilt or rolled back without
reassembling a jar. It ships as its own container image — `deployment/frontend/`.

## What you need installed

Node and pnpm, pinned in `/.tool-versions`:

```
nodejs 22.13.0     # a minimum; newer is fine
pnpm   11.25.0     # exact; frontend/package.json pins the same number in `packageManager`
```

The floor is 22.13 because pnpm 11.25 refuses to start below it, which is above what Vite itself
requires. Install pnpm with `npm install --global pnpm@11.25.0` rather than through corepack — the
corepack bundled with these Node builds fails with `Cannot find matching keyid`. Nothing outside this
directory needs either tool; see `../docs/development/toolchain.md`.

## The packages

One package per boundary. pnpm's strict `node_modules` is what enforces those boundaries: a package
can only import what its own `package.json` declares.

| Package | Holds |
| --- | --- |
| `@kui/api` | types **generated** from `docs/api/openapi.browser.json`, and the typed client. Nothing hand-written mirrors a server type |
| `@kui/kernel` | design system, query cache, SSE wrappers, capability and permission stores, theme |
| `@kui/shell` | router, layout, navigation, feature registry, error pages |
| `@kui/feature-clusters` … `-schemas` | one feature microfrontend each: clusters, topics, messages, consumers, schemas |

Two rules about those edges:

1. **The shell declares the feature packages as dependencies but never imports one statically.**
   The declaration is what lets `import("@kui/feature-topics")` resolve at all; the *dynamic* import
   is what gives the feature its own chunk (ADR-012). The check reads `dist/.vite/manifest.json` and
   fails if a feature's module is among the entry chunk's `imports` rather than its `dynamicImports`
   — the module graph, not the chunk text, because the entry legitimately contains a feature's *name*
   (its route pattern and its import specifier) and a text search would be wrong in both directions.
2. **No feature package depends on another feature package.** If two need the same thing it goes in
   `@kui/kernel`. Checked over these same `package.json` files.

`../docs/frontend/features.md` is the procedure for adding a feature.

## Building

```
pnpm install          # once, and after a dependency change
pnpm build            # vite build, into frontend/dist
pnpm watch            # the same build, rebuilding on save
pnpm typecheck        # tsc --build, strict — `vite build` transpiles without checking types
pnpm test             # vitest, under jsdom
pnpm storybook        # every component's stories, from fixtures, with no backend
```

The one thing the backend build still does for this one is `./mill frontend.apiConstants`, which
writes `packages/api/src/constants.generated.ts` from the Scala constants — the CSRF header's name
and the error-envelope vocabulary, neither of which appears in an OpenAPI document. The browser's
*types* are regenerated on this side, with `pnpm --filter @kui/api run generate`. See
`packages/api/README.md`.

## The development loop

The gateway is the only server, in development as in production. There is **no Vite dev server and
no proxy**: assets and API share an origin, which is ADR-012 Amendment 1 — a CORS configuration that
exists only in development is a configuration that only breaks in development.

Two terminals, because a foreground `./mill dev` holds Mill's lock on its output directory:

```
# terminal 1 — the backend, in the background, serving frontend/dist at /ui/
./mill devStart

# terminal 2 — rebuild the bundle on every save
cd frontend && pnpm watch

# then open http://localhost:8080/ui/ and refresh after a rebuild
./mill devStop
```

`vite build --watch` has no hot module replacement, so a refresh is the loop.

How it works: `out/dev-assets/solid/web` is a symbolic link to `frontend/dist`, placed in front of
the gateway's own resources. A classloader takes the first hit, so `/ui/…` resolves to whatever the
last rebuild wrote. Nothing is copied, nothing is restarted. The comment on `dev` in `build.mill` is
the authority.

One development-only wart: `frontend/dist/.vite/manifest.json` is reachable at
`/ui/.vite/manifest.json` while the link is in place. The frontend image's nginx never serves it.

## The base path

KUI can be mounted at the root or behind a reverse proxy at, say, `/kui`, and **one built bundle has
to work in both**. Three pieces make that true, and none of them may be "simplified" away:

- `vite.config.ts` sets `base: "./"`, so every asset URL in the built `index.html` is relative;
- `index.html` keeps the two markers `<!--KUI_BASE_HREF-->` and `<!--KUI_BOOTSTRAP-->`. Whichever
  server is in front substitutes them: the gateway at request time in development, and the frontend
  image's entrypoint at container start in a deployment (`deployment/frontend/entrypoint.sh`);
- `@kui/api`'s `readBootstrap()` reads the injected block, and the shell hands `<basePath>/ui` to the
  router as its base.

The `<base href>` is also what makes a deep link work: `/ui/clusters/orders` is answered with
`index.html` by the single-page fallback, and `./assets/index-abc.js` still resolves to
`/ui/assets/index-abc.js` rather than to `/ui/clusters/assets/…`.
